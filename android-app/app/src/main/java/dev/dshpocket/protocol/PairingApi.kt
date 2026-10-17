package dev.dshpocket.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The **local-only** pairing surface (`/api/pair`).
 *
 * By Host design these endpoints are unreachable from a paired remote device —
 * `LOCAL_ONLY_PREFIXES = ['/api/pair', '/api/update', '/api/plugin-manager']`.
 * DSH Pocket therefore never calls them over the network: it renders the pairing
 * QR/link that the desktop shows, and the phone redeems it through
 * `POST /api/pair/accept`, which is the one path that *is* reachable while
 * unpaired (it is the act of pairing). Everything after that uses
 * [GatewayClient].
 */
class PairingApi(private val http: OkHttpClient = defaultHttp()) {

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    sealed interface Result {
        data class Paired(val deviceId: String, val cookie: String?) : Result
        data class Failed(val code: String, val message: String) : Result
    }

    /** A pairing link/QR payload as produced by the desktop (`/pair-accept?pair=<token>`). */
    data class Invite(val baseUrl: String, val token: String)

    /**
     * Parse a scanned pairing URL.
     *
     * Accepts both the desktop's canonical form
     * `https://<host>/pair-accept?pair=<token>` and a bare `https://<host>` plus
     * token pair typed by hand.
     */
    fun parseInvite(raw: String): Invite? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        val token = url.queryParameter("pair")
            ?: url.queryParameter("token")
            ?: return null
        if (token.isBlank()) return null
        val base = url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        return Invite(base.toString().trimEnd('/'), token)
    }

    /** Redeem a pairing token and obtain the device credential. */
    fun accept(baseUrl: String, token: String): Result {
        val base = baseUrl.toHttpUrlOrNull()
            ?: return Result.Failed("invalid-url", "Not a valid http(s) URL: $baseUrl")
        val url = base.newBuilder()
            .addPathSegment("api").addPathSegment("pair").addPathSegment("accept")
            .build()
        val body = buildJsonObject { put("token", token) }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val obj = runCatching { Json.lenient.parseToJsonElement(text).asObjOrNull() }.getOrNull()
                val accepted = obj?.bool("ok") == true
                val deviceId = if (accepted) obj?.string("deviceId") else null
                when {
                    deviceId != null -> {
                        val cookie = response.headers.values("set-cookie")
                            .firstOrNull { it.startsWith("dsh_pair=") }
                            ?.substringBefore(';')
                            ?.substringAfter('=')
                        Result.Paired(deviceId, cookie)
                    }
                    accepted -> Result.Failed("no-device-id", "Host accepted but returned no deviceId")
                    else -> Result.Failed(
                        code = obj?.string("code") ?: "http/${response.code}",
                        message = obj?.string("message") ?: "Pairing failed (HTTP ${response.code})",
                    )
                }
            }
        } catch (t: Throwable) {
            Result.Failed("network", t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Read the desktop's pairing status. Only meaningful from the desktop's own
     * loopback origin; included so the Windows helper (and tests) can reuse it.
     */
    fun status(baseUrl: String): JsonObject? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val url: HttpUrl = base.newBuilder()
            .addPathSegment("api").addPathSegment("pair").addPathSegment("status")
            .build()
        val request = Request.Builder().url(url).get().build()
        return try {
            http.newCall(request).execute().use { r ->
                runCatching {
                    Json.lenient.parseToJsonElement(r.body?.string().orEmpty()).asObjOrNull()
                }.getOrNull()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
