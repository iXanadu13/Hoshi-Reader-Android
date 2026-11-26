package moe.antimony.hoshi.features.audio

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.io.inputStream
import kotlin.io.outputStream
import kotlin.toString

class UrlConnectionAudioHttpClient @Inject constructor() : AudioHttpClient {
    override fun request(url: String, form: Map<String, String>?): AudioHttpResponse = requestAudio(url, form)
}

internal fun requestAudio(url: String, form: Map<String, String>?): AudioHttpResponse =
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = if (form == null) "GET" else "POST"
        }
        try {
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(form.audioFormEncode().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.use { it.readBytes() } else byteArrayOf()
            return AudioHttpResponse(connection.url.toString(), status, connection.contentType.orEmpty(), body)
        } finally {
            connection.disconnect()
        }
    }.getOrElse { AudioHttpResponse(url, 599, "", byteArrayOf()) }
