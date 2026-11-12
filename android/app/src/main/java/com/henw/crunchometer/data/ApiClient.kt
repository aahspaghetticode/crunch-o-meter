package com.henw.crunchometer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

class ApiClient {
    companion object {
        /** Shared with the unit tests so they decode exactly like the app does. */
        internal val json = Json {
            ignoreUnknownKeys = true   // server can add fields without breaking old app builds
            coerceInputValues = true   // null for a non-null field -> its default
            explicitNulls = false
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()


    private fun endpoint(baseUrl: String, path: String): HttpUrl {
        val trimmed = baseUrl.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val base = withScheme.toHttpUrlOrNull()
            ?: throw ApiException("Server URL isn't valid: $baseUrl")
        return base.newBuilder().addPathSegments(path).build()
    }

    suspend fun fetchSummary(baseUrl: String, token: String): Summary = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "api/summary"))
            .header("Authorization", "Bearer $token")
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> json.decodeFromString(Summary.serializer(), resp.body.string())
                    401 -> throw ApiException("Server rejected the token")
                    else -> throw ApiException("Server returned HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            throw ApiException(networkMessage(e))
        } catch (e: SerializationException) {
            throw ApiException("Unexpected response from server: ${e.message}")
        }
    }

    private fun networkMessage(e: IOException): String {
        val msg = e.message.orEmpty()
        return when {
            "CLEARTEXT" in msg -> "Plain http is only allowed to *.ts.net names. Use https (tailscale serve) or the MagicDNS name."
            e is java.net.UnknownHostException -> "Can't resolve host. Is Tailscale connected on this phone?"
            e is java.net.SocketTimeoutException -> "Timed out reaching the server. Is Tailscale up on both ends?"
            e is java.net.ConnectException -> "Connection refused. Is the monitor service running?"
            else -> "Network error: ${msg.ifBlank { e.javaClass.simpleName }}"
        }
    }
}
