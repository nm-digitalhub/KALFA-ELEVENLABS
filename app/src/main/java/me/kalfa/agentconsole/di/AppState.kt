package me.kalfa.agentconsole.di

import kotlinx.coroutines.flow.MutableStateFlow
import me.kalfa.agentconsole.ui.message.AppMessageCenter
import me.kalfa.agentconsole.ui.message.MessageSeverity
import me.kalfa.agentconsole.ui.message.UiMessage

/**
 * Temporary adapter for data sources that have not migrated to typed failures yet.
 * New code must publish typed, scoped state instead of using this object.
 */
@Deprecated("Migrate the producer to a typed result or AppMessageCenter")
object ErrorBus {
    private const val LEGACY_MESSAGE_ID = "legacy-data-error"
    private const val LEGACY_DEDUPLICATION_KEY = "legacy-data-error"

    val lastError = MutableStateFlow<String?>(null)

    fun post(message: String) {
        lastError.value = message
        AppMessageCenter.publish(
            UiMessage(
                id = LEGACY_MESSAGE_ID,
                severity = MessageSeverity.ERROR,
                title = message,
                dismissible = true,
                deduplicationKey = LEGACY_DEDUPLICATION_KEY
            )
        )
    }

    fun clear() {
        lastError.value = null
        AppMessageCenter.resolve(LEGACY_DEDUPLICATION_KEY)
    }
}

/** Foreground gate for polling loops (set from MainActivity lifecycle). */
object AppVisibility {
    val isForeground = MutableStateFlow(true)
}
