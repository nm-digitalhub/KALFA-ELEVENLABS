package me.kalfa.agentconsole.ui.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface MessageCenter {
    val messages: StateFlow<List<UiMessage>>

    fun publish(message: UiMessage)
    fun dismiss(messageId: String)
    fun resolve(deduplicationKey: String)
}

object AppMessageCenter : MessageCenter {
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    override val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    override fun publish(message: UiMessage) {
        _messages.update { current ->
            val withoutDuplicate = message.deduplicationKey?.let { key ->
                current.filterNot { it.deduplicationKey == key }
            } ?: current.filterNot { it.id == message.id }

            withoutDuplicate + message
        }
    }

    override fun dismiss(messageId: String) {
        _messages.update { current ->
            current.filterNot { it.id == messageId && it.dismissible }
        }
    }

    override fun resolve(deduplicationKey: String) {
        _messages.update { current ->
            current.filterNot { it.deduplicationKey == deduplicationKey }
        }
    }
}
