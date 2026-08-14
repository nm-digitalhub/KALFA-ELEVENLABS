package me.kalfa.agentconsole.telephony.vox

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression guard for "register-after-login ordering" — the one thing a push-woken
// app cannot get wrong without silently losing calls (AGENTS.md "Push wake-up").
// Every SDK interaction is a fake closure that records into `calls`, so the real
// order the production code runs steps in is asserted directly, with no Android or
// Voximplant SDK on the classpath.
class VoxWakePushHandlerTest {
    private val voximplantPush = mapOf("voximplant" to "1", "callee_display_name" to "guest")
    private val notAVoximplantPush = mapOf("some_other_key" to "1")

    @Test
    fun `ignores a push without the voximplant signature entirely`() = runTest {
        val calls = mutableListOf<String>()
        val handler = recordingHandler(calls)

        handler.handle(notAVoximplantPush)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `logs in, handles the push, then re-registers, in that order`() = runTest {
        val calls = mutableListOf<String>()
        val handler = recordingHandler(calls)

        handler.handle(voximplantPush)

        assertEquals(listOf("login", "handle", "register"), calls)
    }

    @Test
    fun `still calls handlePushNotification and re-registers when the silent login fails`() = runTest {
        val calls = mutableListOf<String>()
        val handler = VoxWakePushHandler(
            ensureLoggedIn = { calls += "login"; throw IllegalStateException("no usable stored session") },
            handlePushNotification = { calls += "handle" },
            registerPushToken = { calls += "register" },
        )

        handler.handle(voximplantPush)

        assertEquals(listOf("login", "handle", "register"), calls)
    }

    @Test
    fun `still re-registers when handlePushNotification itself fails`() = runTest {
        val calls = mutableListOf<String>()
        val handler = VoxWakePushHandler(
            ensureLoggedIn = { calls += "login" },
            handlePushNotification = { calls += "handle"; throw RuntimeException("sdk parse failure") },
            registerPushToken = { calls += "register" },
        )

        handler.handle(voximplantPush)

        assertEquals(listOf("login", "handle", "register"), calls)
    }

    private fun recordingHandler(calls: MutableList<String>) = VoxWakePushHandler(
        ensureLoggedIn = { calls += "login" },
        handlePushNotification = { calls += "handle" },
        registerPushToken = { calls += "register" },
    )
}
