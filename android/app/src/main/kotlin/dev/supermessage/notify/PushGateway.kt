package dev.supermessage.notify

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Finding the Matrix push gateway beside a UnifiedPush endpoint
 * (unifiedpush.org/users/gateway): the endpoint's server answers
 * `GET /_matrix/push/v1/notify` with `{"unifiedpush":{"gateway":"matrix"}}`
 * when it is one. ntfy.sh and a self-hosted ntfy both are.
 *
 * **No fallback gateway** (owner decision Q2): a third party's gateway would
 * see room ids, event ids and unread counts. When the endpoint's own server
 * is not a gateway, there is no pusher.
 */
internal object PushGateway {
    const val NOTIFY_PATH = "/_matrix/push/v1/notify"

    /**
     * The endpoint with its whole path and query replaced by [NOTIFY_PATH].
     * Anything that is not an absolute `https` URL is no gateway
     * (`PushConfiguration.swift:27-34`): a pusher the homeserver posts to in
     * the clear, or retries forever, is worse than none.
     */
    fun discoveryUrl(endpoint: String): String? {
        val uri = try {
            URI(endpoint.trim())
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            URI("https", null, host, uri.port, NOTIFY_PATH, null, null).toString()
        } catch (e: Exception) {
            null
        }
    }

    fun isMatrixGateway(body: String): Boolean = try {
        JSONObject(body).optJSONObject("unifiedpush")?.opt("gateway") == "matrix"
    } catch (e: Exception) {
        false
    }
}

/** One `GET`; the body on a 200, otherwise `null`. A seam, so tests never reach a server. */
fun interface GatewayProbe {
    suspend fun get(url: String): String?
}

/** [GatewayProbe] over `HttpURLConnection`, off every cooperative thread, 10 s each way. */
internal object HttpGatewayProbe : GatewayProbe {
    private const val TIMEOUT_MS = 10_000

    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return@withContext null
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.use(::readCapped)
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A discovery answer is a few dozen bytes (`{"unifiedpush":{"gateway":"matrix"}}`).
 * Anything past [MAX_PROBE_BYTES] is not one, and is not read into memory.
 */
internal const val MAX_PROBE_BYTES = 4 * 1024

/** The body as text, or `null` when it runs past [max] bytes. */
internal fun readCapped(input: InputStream, max: Int = MAX_PROBE_BYTES): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > max) return null
        out.write(buffer, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
