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
    val completedTargets: Int
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
    val notes: String
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
