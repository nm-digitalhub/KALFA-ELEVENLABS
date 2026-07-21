package me.kalfa.agentconsole.domain.model

import kotlinx.serialization.Serializable

enum class AgentStatus(val labelHebrew: String) {
    READY("זמין"),
    NOT_READY("לא זמין"),
    DND("נא לא להפריע"),
    IN_CALL("בשיחה")
}

enum class CallKind {
    INBOUND, OUTBOUND, AI_RSVP
}

enum class CallState {
    RINGING, ACTIVE, DISCONNECTED, MONITORED, TAKEN_OVER
}

data class TranscriptTurn(
    val speaker: String, // "ai" | "customer" | "agent"
    val text: String,
    val at: String // HH:mm:ss
)

data class Call(
    val id: String,
    val direction: String, // "inbound" | "outbound"
    val kind: CallKind,
    val voxSessionId: String,
    val customerPhone: String,
    val customerName: String,
    val eventId: String = "",
    val eventName: String,
    val handledBy: String, // "ai" | "agent"
    val agentId: String?,
    val state: CallState,
    val startedAt: String,
    val answeredAt: String?,
    val endedAt: String?,
    val durationSec: Int,
    val recordingUrl: String?,
    val transcript: List<TranscriptTurn> = emptyList()
)

enum class CampaignState(val labelHebrew: String) {
    ACTIVE("פעיל"),
    PAUSED("מושהה"),
    COMPLETED("הושלם")
}

data class Campaign(
    val id: String,
    val name: String,
    val eventId: String,
    val eventName: String,
    val state: CampaignState,
    val totalTargets: Int,
    val completedTargets: Int,
    // True ONLY when the raw campaign_status is 'active' or 'paused' — the only
    // two states POST /api/campaigns/{id}/status accepts (spec §6.5). Every other
    // state (draft/pending_approval/approved/scheduled/awaiting_invoice) is NOT
    // togglable from the console: the first activation is the owner's on the web,
    // and the route 409s it. The UI renders a run-control button only when this is
    // true, so it never shows a control the backend would refuse (spec §9).
    val runControllable: Boolean = false
)

data class CampaignTarget(
    val id: String,
    val campaignId: String,
    val guestId: String,
    val guestName: String,
    val phone: String,
    val attempts: Int,
    val lastResult: String?,
    val callId: String?
)

// A guest of an event, sourced from the console_event_guests view. Distinct from
// CampaignTarget (which is an outreach_state row keyed by contact_id): this carries
// the REAL guests.id the manual-dial route resolves by, plus the two flags the UI
// needs to decide whether a dial is even offerable — `dialable` (the guest has a
// phone) and `hasActiveCampaign` (the route's own 409 gate). `phone` is empty
// unless the agent holds view_customer_data (gated server-side by the view).
data class EventGuest(
    val guestId: String,
    val eventId: String,
    val guestName: String,
    val dialable: Boolean,
    val phone: String,
    val rsvpStatus: String?,
    val hasActiveCampaign: Boolean
)

enum class RsvpAnswer(val labelHebrew: String) {
    ATTENDING("מגיע"),
    DECLINED("לא מגיע"),
    MAYBE("אולי"),
    CALLBACK("חזרו אליי")
}

data class RsvpResult(
    val id: String,
    val callId: String,
    val guestId: String,
    val guestName: String,
    val answer: RsvpAnswer,
    val guestsCount: Int,
    val notes: String,
    val eventId: String = ""
)

data class CallAnalysis(
    val callAttemptId: String,
    val eventId: String?,
    val callSuccessful: String?,   // "success" | "failure" | "unknown"
    val score: Double?,
    val durationSec: Int?,
    val terminationReason: String?,
    val rsvpStatus: String?,       // extracted: attending/declined/...
    val adults: Int?,
    val children: Int?,
    val evalCriteria: Map<String, String> = emptyMap(), // rsvp_captured, dnc_honored...
    val analysisAt: String?
)

@Serializable
data class TranscriptLine(
    val role: String,   // "user" | "agent"
    val text: String,
    val at: Long = 0L
)

data class ConsoleEvent(
    val id: String,
    val name: String,
    val type: String?,
    val date: String?,      // ISO
    val hasCampaign: Boolean
)

data class ConsoleMe(
    val displayName: String,
    val platformRole: String,
    val platformRank: Int,
    val permissions: Set<String>
) {
    val canManageVoice get() = "manage_voice" in permissions
    val canViewCustomerData get() = "view_customer_data" in permissions
}

data class EventSummary(
    val event: ConsoleEvent,
    val campaignState: CampaignState?,   // null = no campaign yet
    val targetsTotal: Int,
    val targetsDone: Int,
    val liveNow: Int,
    val attending: Int,
    val declined: Int,
    val maybe: Int,
    val callback: Int,
    val totalGuests: Int
)
