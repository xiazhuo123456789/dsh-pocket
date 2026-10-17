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
 * The pairing surface (`/api/pair`).
 *
 * ### The one asymmetry that shapes this whole class
 *
 * The desktop splits its API into two trust zones:
 *
 * - **Loopback-only**: `/api/pair`, `/api/update`, `/api/plugin-manager`. These
 *   are refused from any remote origin *even after pairing* — a paired phone is
 *   explicitly not allowed to manage pairing, self-update, or install plugins.
 *   The desktop's own error text for this is: *"api/pair stays physically local
 *   and stays unreachable from a paired remote desktop"*.
 * - **Remote-reachable**: everything under `/remote`, which requires a device
 *   credential.
 *
 * The consequence is subtle and worth stating plainly: **the phone cannot manage
 * its own pairing.** It can only redeem an invite that a human produced on the
 * desktop. Revoking a device, listing devices and generating a new invite are all
 * desktop-side actions. This class is therefore only used *during* pairing;
 * afterwards all traffic goes through [GatewayClient].
 *
 * `POST /api/pair/accept` is the exception that makes pairing possible at all: it
 * is reachable while unpaired, because redeeming a token *is* the act of pairing.
 *
 * Provenance: `LOCAL_ONLY_PREFIXES` and the accept/issue shapes were read from
 * `@linxin666/dsh-remote-web-ui` (`src/remote-methods.ts`, `src/routes.ts`) and
 * confirmed against a live desktop. See `docs/sources.md`.
 */
class PairingApi(private val http: OkHttpClient = defaultHttp()) {

    companion object {
        /**
         * Pairing is interactive and one-shot, so a finite read timeout is right
         * here — unlike the streaming client, which must never time out a live
         * session.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Outcome of redeeming an invite. */
    sealed interface Result {
        /**
         * @param deviceId the credential to send as the device header on every
         *   later request. Treat it as a secret.
         * @param cookie the same credential as a `Set-Cookie` value, when the
         *   desktop provided one; kept because it is useful for debugging a
         *   pairing from a browser context.
         */
        data class Paired(val deviceId: String, val cookie: String?) : Result

        /** @param code the desktop's own error code, when it supplied one. */
        data class Failed(val code: String, val message: String) : Result
    }

    /** A pairing link/QR payload as produced by the desktop (`/pair-accept?pair=<token>`). */
    data class Invite(val baseUrl: String, val token: String)

    /**
     * Parse a scanned or pasted pairing URL.
     *
     * Accepts the desktop's canonical form `https://<host>/pair-accept?pair=<token>`
     * and also a `token=` variant. Any extra query parameters are discarded, and
     * the path is forced to `/`, because the invite URL is a *human-facing page*,
     * not an API endpoint — only the origin and the token matter to us.
     *
     * Returns `null` for anything that is not a usable invite. Callers surface
     * that as "not a pairing link" rather than as a network error.
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

    /**
     * Redeem a pairing token and obtain the device credential.
     *
     * Blocking by design: it is called once, from a background coroutine, while
     * the user watches a spinner. Making it `suspend` would add a dispatcher hop
     * for no benefit.
     *
     * Never throws — transport failures are folded into [Result.Failed] so the UI
     * has one shape to render. The `code` values `invalid-url`, `no-device-id` and
     * `network` are produced locally; anything else comes from the desktop.
     */
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
                // Deliberately not keyed on HTTP status alone: the desktop answers
                // `ok:true`/`ok:false` in the body, and a proxy could rewrite the
                // status. The body is the contract.
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
                    // Accepted but unusable: better to fail loudly than to store a
                    // credential that will 403 on every later call.
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
     * Read the desktop's pairing status.
     *
     * **Only useful from the desktop's own loopback origin** — a paired remote
     * device gets `403` here by design (see the class comment). It is kept because
     * the conformance tests and a desktop-side helper can legitimately call it,
     * and having the shape documented in one place is better than duplicating it
     * in the test scripts.
     *
     * Returns `null` on any failure; callers must not read `null` as "unpaired".
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
