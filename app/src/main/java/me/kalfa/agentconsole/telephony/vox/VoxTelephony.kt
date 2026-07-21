package me.kalfa.agentconsole.telephony.vox

import com.voximplant.android.sdk.core.Node
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ─────────────────────────────────────────────────────────────────────────────
// Voximplant Android SDK v3 — human-agent leg configuration + one-time-key auth.
//
// The console agent authenticates to Voximplant with a ONE-TIME KEY whose hash is
// computed SERVER-SIDE (the Vox password never touches the device): the app asks
// the SDK for a one-time key, POSTs it to /api/agents/sdk-auth with the agent's
// Supabase JWT, gets back { hash }, and logs in with it. Identity (vox_username)
// comes from console_me; the account/application names are fixed.
// ─────────────────────────────────────────────────────────────────────────────

object VoxConfig {
    // Fixed, non-secret (integration reference §5). The full SDK username is
    // "<vox_username>@<application>.<account>.voximplant.com".
    const val APPLICATION = "kalfa-rsvp"
    const val ACCOUNT = "kalfarsvp"

    // The account's Voximplant data-center node. v3 REQUIRES this explicitly in
    // ConnectOptions (unlike v2, which auto-discovered it). It MUST match the real
    // node kalfarsvp is hosted on (Voximplant control panel → account) — a wrong
    // node makes connect() fail. Non-secret; single source of truth here.
    // TODO(owner): confirm kalfarsvp's node and set it. Placeholder until confirmed.
    val node: Node = Node.Node4

    // The exact full-username the SDK expects. Getting the "@app.account…" suffix
    // wrong is the #1 silent auth failure, so it is built in ONE place and tested.
    fun fullUsername(voxUsername: String): String =
        "$voxUsername@$APPLICATION.$ACCOUNT.voximplant.com"
}

enum class VoxLoginState { LOGGED_OUT, CONNECTING, LOGGING_IN, LOGGED_IN, FAILED }

sealed class VoxAuthException(message: String) : Exception(message) {
    object NoSession : VoxAuthException("no Supabase session")
    object NoIdentity : VoxAuthException("agent has no Voximplant identity (sdk-auth 409)")
    object NotAgent : VoxAuthException("not a console agent (sdk-auth 401)")
    class Http(val code: Int) : VoxAuthException("sdk-auth HTTP $code")
    class Sdk(reason: String) : VoxAuthException(reason)
}

// The ONLY network call in the login flow: exchange a Voximplant one-time key for
// the server-computed login hash. Kept separate from the SDK plumbing so its HTTP
// contract (request shape, status mapping, hash parsing) is unit-testable with a
// ktor MockEngine, with no Android or SDK on the classpath.
class VoxSdkAuthClient(
    private val httpClient: HttpClient,
    private val getJwt: suspend () -> String?,
    private val baseUrl: String = "https://beta.kalfa.me",
) {
    suspend fun fetchHash(oneTimeKey: String): String {
        val jwt = getJwt() ?: throw VoxAuthException.NoSession
        val resp = httpClient.post("$baseUrl/api/agents/sdk-auth") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            // The server identifies the agent from the JWT; the body carries ONLY
            // the one-time key. A body naming another agent is rejected (400).
            setBody(buildJsonObject { put("one_time_key", oneTimeKey) }.toString())
        }
        return when (resp.status.value) {
            200 ->
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["hash"]?.jsonPrimitive?.content
                    ?: throw VoxAuthException.Sdk("sdk-auth 200 without a hash")
            401 -> throw VoxAuthException.NotAgent
            409 -> throw VoxAuthException.NoIdentity // no vox_username provisioned
            else -> throw VoxAuthException.Http(resp.status.value)
        }
    }
}
