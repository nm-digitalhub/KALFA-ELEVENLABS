package me.kalfa.agentconsole.di

import kotlinx.coroutines.flow.MutableStateFlow

/** Last data-layer error surfaced to the UI banner. null = healthy. */
object ErrorBus {
    val lastError = MutableStateFlow<String?>(null)
    fun post(message: String) { lastError.value = message }
    fun clear() { lastError.value = null }
}

/** Foreground gate for polling loops (set from MainActivity lifecycle). */
object AppVisibility {
    val isForeground = MutableStateFlow(true)
}
