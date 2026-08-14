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
    // ConnectOptions (unlike v2, which auto-discovered it).
    //
    // MEASURED 2026-08-12 (beta/src/lib/voximplant/core.ts, MEASURED_CONNECTION_NODE):
    // a real browser login (one-time-key flow end-to-end, same account, same login
    // protocol this class implements) succeeded on NODE_1 and on no other node
    // tried. That is an actual successful SDK authentication against this account —
    // the strongest evidence available.
    //
    // Corrected 2026-08-14: this constant previously said Node2, "confirmed" from
    // the account's Management API endpoint (api-node2.voximplant.com). That
    // reasoning was wrong, not just uncertain: the Management API host
    // (beta/src/lib/voximplant/core.ts's MGMT_BASE, in fact the generic
    // `https://api.voximplant.com/platform_api`, not even node-specific) is a
    // DIFFERENT service from the SDK's realtime connection node — a hostname that
    // happens to contain "node2" is not evidence about which node the Client SDK
    // should connect to. Do not re-derive the node from a Management API URL again.
    //
    // If `Client.connect()` ever fails, re-verify the node FIRST, before assuming a
    // network problem: a wrong node fails silently at connect(), not at login(), so
    // it presents as connectivity trouble and can burn an afternoon before anyone
    // thinks to check this constant.
    val node: Node = Node.Node1

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
