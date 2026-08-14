package me.kalfa.agentconsole

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renamed from the AI Studio scaffold's `ExampleInstrumentedTest`. Runs on a
 * device/emulator and verifies the real applicationId — Google Play rejects
 * `com.example`/AI-Studio-generated ids (see AGENTS.md hard rule 8), so this
 * guards against that regressing.
 */
@RunWith(AndroidJUnit4::class)
class AppIdentityInstrumentedTest {
  @Test
  fun useAppContext() {
    // Context of the app under test.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("me.kalfa.agentconsole", appContext.packageName)
  }
}
