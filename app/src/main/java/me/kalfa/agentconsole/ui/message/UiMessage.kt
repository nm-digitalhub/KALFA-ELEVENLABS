package me.kalfa.agentconsole.ui.message

enum class MessageSeverity {
    INFO,
    WARNING,
    ERROR
}

data class MessageAction(
    val label: String,
    val actionId: String
)

data class UiMessage(
    val id: String,
    val severity: MessageSeverity,
    val title: String,
    val body: String? = null,
    val primaryAction: MessageAction? = null,
    val secondaryAction: MessageAction? = null,
    val dismissible: Boolean = true,
    val deduplicationKey: String? = null
)

sealed interface UiEffect {
    data class ShowSnackbar(
        val message: String,
        val actionLabel: String? = null,
        val actionId: String? = null
    ) : UiEffect
}
