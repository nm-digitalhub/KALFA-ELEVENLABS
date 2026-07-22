package me.kalfa.agentconsole.ui.message

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCenterTest {
    private val key = "message-center-test"

    @After
    fun tearDown() {
        AppMessageCenter.resolve(key)
    }

    @Test
    fun publishWithSameDeduplicationKeyReplacesPreviousMessage() {
        AppMessageCenter.publish(message(id = "first", title = "הודעה ראשונה"))
        AppMessageCenter.publish(message(id = "second", title = "הודעה מעודכנת"))

        val matching = AppMessageCenter.messages.value.filter {
            it.deduplicationKey == key
        }

        assertEquals(1, matching.size)
        assertEquals("second", matching.single().id)
        assertEquals("הודעה מעודכנת", matching.single().title)
    }

    @Test
    fun dismissRemovesOnlyRequestedDismissibleMessage() {
        AppMessageCenter.publish(message(id = "dismissible", title = "ניתנת לסגירה"))
        AppMessageCenter.dismiss("dismissible")

        assertFalse(
            AppMessageCenter.messages.value.any { it.id == "dismissible" }
        )
    }

    @Test
    fun dismissKeepsPersistentMessage() {
        AppMessageCenter.publish(
            message(
                id = "persistent",
                title = "הודעה מתמשכת",
                dismissible = false
            )
        )
        AppMessageCenter.dismiss("persistent")

        assertTrue(
            AppMessageCenter.messages.value.any { it.id == "persistent" }
        )
    }

    private fun message(
        id: String,
        title: String,
        dismissible: Boolean = true
    ) = UiMessage(
        id = id,
        severity = MessageSeverity.WARNING,
        title = title,
        dismissible = dismissible,
        deduplicationKey = key
    )
}
