package moe.antimony.hoshi.features.audio

import android.webkit.WebResourceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import moe.antimony.hoshi.network.HttpProxyRuntime

class AudioRequestHandler(
    private val localAudioRepository: LocalAudioRepository,
    private val remoteAudioRepository: RemoteWordAudioRepository,
    private val fetchRemoteAudioList: (String) -> ByteArray = ::fetchRemoteAudioList,
    private val findLocalAudioCandidates: suspend (term: String, reading: String) -> List<LocalAudioCandidate> =
        localAudioRepository::findAudioCandidates,
    private val wordAudioCache: WordAudioCache? = null,
    private val fetchRemoteAudio: (String) -> AudioHttpResponse = { url -> requestAudio(url, null) },
) {
    fun handleAudioRequest(url: String): WebResourceResponse? {
        val body = handleAudioRequestBody(url) ?: return null
        return jsonResponse(body)
    }

    internal fun handleAudioRequestBody(url: String): ByteArray? {
        val uri = audioRequestUri(url) ?: return null
        val parameters = queryParameters(uri.rawQuery.orEmpty())
        val target = parameters["url"]
            ?: return emptyAudioResponse()
        val forceRefresh = parameters["refresh"] == "1"
        val targetUri = runCatching { URI(target) }.getOrNull()
        val targetParameters = targetUri?.let { queryParameters(it.rawQuery.orEmpty()) }.orEmpty()
        val term = parameters["term"].orEmpty().ifBlank { targetParameters["term"].orEmpty() }
        val reading = parameters["reading"].orEmpty().ifBlank { targetParameters["reading"].orEmpty() }

        return if (target.startsWith(AudioSettings.InternalLocalAudioUrl.substringBefore("?"))) {
            localAudioResponse(target)
        } else if (target.startsWith("${BuiltInAudioSource.Scheme}:", ignoreCase = true)) {
            builtInAudioResponse(target, forceRefresh)
        } else {
            externalAudioResponse(
                target = target,
                term = term,
                reading = reading,
                sourceKey = parameters["source"].orEmpty().ifBlank { target.substringBefore("?") },
                forceRefresh = forceRefresh,
            )
        }
    }

    private fun builtInAudioResponse(target: String, forceRefresh: Boolean): ByteArray {
        val uri = runCatching { URI(target) }.getOrNull() ?: return emptyAudioResponse()
        val source = BuiltInAudioSource.entries.firstOrNull { it.id == uri.host } ?: return emptyAudioResponse()
        val query = runCatching { queryParameters(uri.rawQuery.orEmpty()) }.getOrNull() ?: return emptyAudioResponse()
        // WebView requires a synchronous interception response on its worker thread.
        // The repository owns the IO dispatcher; no UI or WebView state is accessed here.
        val candidates = runBlocking {
            remoteAudioRepository.resolve(
                source = source,
                term = query["term"].orEmpty(),
                reading = query["reading"].orEmpty(),
                forceRefresh = forceRefresh,
            )
        }
        return buildJsonObject {
            put("type", "audioSourceList")
            put("audioSources", buildJsonArray {
                candidates.forEach { candidate ->
                    add(buildJsonObject {
                        put("name", candidate.name)
                        put("url", candidate.url)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun externalAudioResponse(
        target: String,
        term: String,
        reading: String,
        sourceKey: String,
        forceRefresh: Boolean,
    ): ByteArray {
        val cache = wordAudioCache
        if (cache == null || term.isBlank() || sourceKey.isBlank()) return fetchRemoteAudioList(target)
        return runBlocking {
            if (forceRefresh) {
                runCatching { cache.clearSource(term, reading, sourceKey) }
            } else {
                runCatching { cache.cachedCandidates(term, reading, sourceKey) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { cached ->
                        return@runBlocking audioSourceResponse(
                            cached.map { WordAudioCandidate(it.url, it.name) },
                        )
                    }
            }
            val candidates = parseAudioSourceList(fetchRemoteAudioList(target)) ?: return@runBlocking emptyAudioResponse()
            val available = mutableListOf<WordAudioCandidate>()
            for (candidate in candidates) {
                if (!candidate.url.isRemoteAudioUrl()) {
                    available += candidate
                    continue
                }
                val response = fetchRemoteAudio(candidate.url)
                if (!response.isSuccessfulAudio()) continue
                runCatching { cache.store(term, reading, sourceKey, candidate.url, response.body, candidate.name) }
                available += candidate
            }
            audioSourceResponse(available)
        }
    }

    private fun localAudioResponse(targetUrl: String): ByteArray {
        val uri = URI(targetUrl)
        val query = queryParameters(uri.rawQuery.orEmpty())
        val term = query["term"].orEmpty()
        val reading = query["reading"].orEmpty()
        // WebView interception runs on a worker thread; the repository owns its IO dispatcher.
        val candidates = try {
            runBlocking { findLocalAudioCandidates(term, reading) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The repository records the original error; settings offers reselect/unlink.
            return emptyAudioResponse()
        }
        if (candidates.isEmpty()) return emptyAudioResponse()
        val sources = candidates.joinToString(",") { candidate ->
            """{"name":${candidate.name.jsonString()},"url":${candidate.url.jsonString()}}"""
        }
        return """{"type":"audioSourceList","audioSources":[$sources]}""".toByteArray()
    }

    private fun jsonResponse(body: ByteArray): WebResourceResponse =
        WebResourceResponse(
            "application/json",
            "UTF-8",
            ByteArrayInputStream(body),
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }

    private fun emptyAudioResponse(): ByteArray =
        """{"type":"audioSourceList","audioSources":[]}""".toByteArray()

    private fun parseAudioSourceList(body: ByteArray): List<WordAudioCandidate>? = runCatching {
        val objectBody = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        if (objectBody["type"]?.jsonPrimitive?.contentOrNull != "audioSourceList") return null
        objectBody["audioSources"]?.jsonArray.orEmpty().mapNotNull { element ->
            val candidate = element as? JsonObject ?: return@mapNotNull null
            val url = candidate["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            WordAudioCandidate(url, candidate["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }.getOrNull()

    private fun audioSourceResponse(candidates: List<WordAudioCandidate>): ByteArray =
        buildJsonObject {
            put("type", "audioSourceList")
            put("audioSources", buildJsonArray {
                candidates.forEach { candidate ->
                    add(buildJsonObject {
                        put("name", candidate.name)
                        put("url", candidate.url)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)

    private fun audioRequestUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val isIosAudioScheme = uri.scheme == "audio"
        val isAndroidAudioEndpoint = uri.scheme == "https" &&
            uri.host == "appassets.androidplatform.net" &&
            uri.path == "/audio"
        if (!isIosAudioScheme && !isAndroidAudioEndpoint) return null
        return uri
    }

    private fun queryParameters(rawQuery: String): Map<String, String> =
        rawQuery
            .split('&')
            .filter { it.contains('=') }
            .associate { part ->
                val name = part.substringBefore('=')
                val value = java.net.URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8.name())
                name to value
            }
}

private fun fetchRemoteAudioList(targetUrl: String): ByteArray =
    runCatching {
        val connection = HttpProxyRuntime.openHttpConnection(URL(targetUrl)).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        connection.inputStream.use { it.readBytes() }
    }.getOrElse {
        """{"type":"audioSourceList","audioSources":[]}""".toByteArray()
    }

private fun String.jsonString(): String =
    buildString {
        append('"')
        for (char in this@jsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
