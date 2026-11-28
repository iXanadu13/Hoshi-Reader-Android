package moe.antimony.hoshi.network

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URL
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher

internal data class HttpProxy(
    val host: String,
    val port: Int,
) {
    val address: String
        get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"

    val javaProxy: Proxy
        get() = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
}

internal data class HttpProxySettings(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val address: String = "",
    val testUrl: String = DefaultHttpProxyTestUrl,
) {
    val proxy: HttpProxy?
        get() = if (enabled && configured) parseHttpProxy(address) else null

    companion object {
        const val DefaultHttpProxyTestUrl = "https://www.google.com/generate_204"
    }
}

internal fun parseHttpProxy(value: String): HttpProxy? {
    val raw = value.trim()
    if (raw.isEmpty()) return null
    val normalized = if (raw.contains("://")) raw else "http://$raw"
    val uri = try {
        URI(normalized)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals("http", ignoreCase = true) || uri.userInfo != null ||
        uri.query != null || uri.fragment != null || uri.rawPath.orEmpty().isNotEmpty()
    ) return null
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeIf { it in 1..65_535 } ?: return null
    return HttpProxy(host, port)
}

internal class HttpProxySelector(
    delegate: ProxySelector?,
) : ProxySelector() {
    @Volatile
    var proxy: HttpProxy? = null

    @Volatile
    var delegate: ProxySelector? = delegate

    override fun select(uri: URI): List<Proxy> {
        val configured = proxy
        if (configured != null && uri.scheme in setOf("http", "https")) {
            if (uri.isInternalHost()) return listOf(Proxy.NO_PROXY)
            return listOf(configured.javaProxy)
        }
        return delegate?.let { runCatching { it.select(uri) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: IOException) {
        delegate?.connectFailed(uri, sa, ioe)
    }
}

private fun URI.isInternalHost(): Boolean {
    val host = host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
    return host == "localhost" || host.endsWith(".localhost") ||
        host == "::1" || host.startsWith("127.") || host == "appassets.androidplatform.net"
}

/** Applies one process-wide proxy to Java networking and WebView. */
internal object HttpProxyRuntime {
    private val lock = Any()
    private val selector = HttpProxySelector(ProxySelector.getDefault())
    private var applicationContext: Context? = null
    @Volatile
    private var webViewOverrideApplied = false

    fun initialize(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            installSelectorLocked()
        }
    }

    fun apply(settings: HttpProxySettings) {
        val proxy = settings.proxy
        synchronized(lock) {
            installSelectorLocked()
            selector.proxy = proxy
        }
    }

    /**
     * Applies the current proxy to WebView only after the app is already using WebView.
     * WebView provider initialization must not be part of Application.onCreate().
     */
    fun applyWebViewProxy() {
        val proxy = synchronized(lock) { selector.proxy }
        applyWebViewProxy(proxy)
    }

    fun openHttpConnection(url: URL): HttpURLConnection {
        val selectedProxy = runCatching { selector.select(url.toURI()).firstOrNull() }.getOrNull()
        val connection = when {
            selectedProxy == null -> url.openConnection()
            selectedProxy.type() == Proxy.Type.DIRECT -> url.openConnection(Proxy.NO_PROXY)
            else -> url.openConnection(selectedProxy)
        }
        return connection as HttpURLConnection
    }

    private fun installSelectorLocked() {
        val current = ProxySelector.getDefault()
        if (current !== selector) {
            if (current != null) selector.delegate = current
            runCatching { ProxySelector.setDefault(selector) }
        }
    }

    private fun applyWebViewProxy(proxy: HttpProxy?) {
        if (proxy == null && !webViewOverrideApplied) return
        val context = applicationContext ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching {
            val controller = ProxyController.getInstance()
            val executor = ContextCompat.getMainExecutor(context)
            if (proxy == null) {
                if (!webViewOverrideApplied) return@runCatching
                webViewOverrideApplied = false
                controller.clearProxyOverride(executor) {}
            } else {
                val config = ProxyConfig.Builder()
                    .addProxyRule("${proxy.hostForWebView()}:${proxy.port}")
                    .addBypassRule("appassets.androidplatform.net")
                    .bypassSimpleHostnames()
                    .build()
                webViewOverrideApplied = true
                controller.setProxyOverride(config, executor) {}
            }
        }
    }

    private fun HttpProxy.hostForWebView(): String = if (host.contains(':')) "[$host]" else host
}

private val Context.httpProxyDataStore by preferencesDataStore(name = "http-proxy-settings")

@Singleton
internal class HttpProxySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val dataStore: DataStore<Preferences> = context.httpProxyDataStore
    private val lock = Mutex()
    private var initialized = false

    val settings: Flow<HttpProxySettings> = dataStore.data.map { it.toHttpProxySettings() }

    suspend fun initialize() = lock.withLock {
        if (initialized) return@withLock
        HttpProxyRuntime.initialize(context)
        HttpProxyRuntime.apply(dataStore.data.first().toHttpProxySettings())
        initialized = true
    }

    suspend fun update(transform: (HttpProxySettings) -> HttpProxySettings) = lock.withLock {
        val current = dataStore.data.first().toHttpProxySettings()
        val next = transform(current)
        dataStore.edit { preferences -> preferences.writeHttpProxySettings(next) }
        HttpProxyRuntime.initialize(context)
        HttpProxyRuntime.apply(next)
        HttpProxyRuntime.applyWebViewProxy()
    }

    suspend fun test(proxy: HttpProxy, url: String) = withContext(ioDispatcher) {
        val targetUri = URI(url.trim())
        require(targetUri.scheme?.equals("http", ignoreCase = true) == true ||
            targetUri.scheme?.equals("https", ignoreCase = true) == true
        ) { "Proxy test URL must use HTTP or HTTPS." }
        require(targetUri.userInfo == null && !targetUri.host.isNullOrBlank()) {
            "Proxy test URL must include a host and cannot include credentials."
        }
        val connection = targetUri.toURL().openConnection(proxy.javaProxy) as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..399) {
                throw IOException("Proxy test returned HTTP ${connection.responseCode}.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Preferences.toHttpProxySettings(): HttpProxySettings = HttpProxySettings(
        enabled = this[EnabledKey] ?: false,
        configured = this[ConfiguredKey] ?: false,
        address = this[AddressKey].orEmpty(),
        testUrl = this[TestUrlKey] ?: HttpProxySettings.DefaultHttpProxyTestUrl,
    )

    private fun MutablePreferences.writeHttpProxySettings(settings: HttpProxySettings) {
        this[EnabledKey] = settings.enabled
        this[ConfiguredKey] = settings.configured
        this[AddressKey] = settings.address
        this[TestUrlKey] = settings.testUrl
    }

    private companion object {
        val EnabledKey = booleanPreferencesKey("enabled")
        val ConfiguredKey = booleanPreferencesKey("configured")
        val AddressKey = stringPreferencesKey("address")
        val TestUrlKey = stringPreferencesKey("testUrl")
    }
}
