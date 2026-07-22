package me.kalfa.agentconsole.di

import kotlinx.coroutines.flow.MutableStateFlow

/** Foreground gate for polling loops (set from MainActivity lifecycle). */
object AppVisibility {
    val isForeground = MutableStateFlow(true)
}
