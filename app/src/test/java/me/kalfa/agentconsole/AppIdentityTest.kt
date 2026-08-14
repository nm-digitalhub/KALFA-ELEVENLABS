package me.kalfa.agentconsole

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renamed from the AI Studio scaffold's `ExampleRobolectricTest`, which asserted
 * `app_name == "My Application"` — a leftover from the template that never got
 * updated when the app was renamed and so always failed. This keeps the same
 * mechanism (does R.string.app_name resolve correctly?) against the real value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIdentityTest {

  @Test
  fun `app name resolves to the real app`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("KALFA Agent Console", appName)
  }
}
