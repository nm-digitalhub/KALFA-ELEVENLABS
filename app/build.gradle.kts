plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.kotlin.serialization)
  // Consumes app/google-services.json (gitignored — each developer/CI supplies
  // their own). Declared `apply false` here and applied conditionally below:
  // the plugin itself FAILS THE BUILD unconditionally if the file is missing,
  // which is right for a real build (a silently-absent Firebase config would
  // otherwise surface much later as pushes that never arrive) but wrong for
  // `gradle test`/`assembleDebug` on a checkout or CI run that has no FCM
  // config yet — those need to keep working (see the `if` block below).
  alias(libs.plugins.google.services) apply false
}

// google-services.json is gitignored (each developer/CI supplies their own —
// see AGENTS.md "Push wake-up"). Applying the plugin only when the file is
// present keeps `gradle test`/`assembleDebug` working on a fresh checkout or
// a CI run with no Firebase secret configured, while still getting the
// plugin's hard, loud failure (see the comment above) the moment the file
// exists but is broken. CI additionally hard-gates the RELEASE build on this
// file's presence (`.github/workflows/android-build.yml`) so a real release
// artifact is never produced without working FCM config — this `if` only
// relaxes the debug/test path, not what ships.
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
} else {
  logger.warn(
    "app/google-services.json not found — building WITHOUT Firebase/FCM " +
      "config (com.google.gms.google-services plugin not applied). Push " +
      "notifications will not work in this build. See AGENTS.md 'Push wake-up'."
  )
}

android {
  namespace = "me.kalfa.agentconsole"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "me.kalfa.agentconsole"
    minSdk = 24
    targetSdk = 36
    versionCode = 10
    versionName = "5.5"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        // No real upload key found. This is a debug-signed fallback for LOCAL,
        // ad-hoc internal testing only (see AGENTS.md "Build & CI") — a build
        // signed this way can never be uploaded to Play (Play requires the
        // SAME key across every release; this generates/uses a throwaway one).
        // CI (.github/workflows/android-build.yml) verifies the real secrets
        // are present and fails before this branch is ever reached there — if
        // this warning shows up in a CI log, that guard has regressed.
        logger.warn(
          "signingConfigs.release: no upload keystore found at '$keystorePath' " +
            "(or KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD unset) — falling back " +
            "to debug.keystore. This build CANNOT be uploaded to Google Play."
        )
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// google-services plugin + google-services.json: DONE 2026-08-14 (Phase 2, FCM
// push wake-up). Plugin applied above; the JSON is gitignored and supplied per
// checkout. Firebase project `kalfa-rsvp`, package `me.kalfa.agentconsole`.
// Everything OUTSIDE this repo is now done (14.8):
//   - ops: the Firebase service-account JSON is uploaded to the Voximplant
//     control panel — verified live as credential #9108, provider GOOGLE, bound
//     to kalfa-rsvp.kalfarsvp.voximplant.com (11107202). Note that is a
//     DIFFERENT file from app/google-services.json and is a real secret; it must
//     never enter this repo.
//   - scenario: require(Modules.PushService) is deployed in ConsoleInbound,
//     ConsoleDial and ConsoleCallMeNow.
//   - server: route-inbound-retry's second audience now includes on-shift
//     agents who are not heartbeat-fresh, so a sleeping app actually gets rung
//     (which is what makes the platform send the push at all).
// In-repo app-side work: DONE 2026-08-14 — telephony/vox/VoxFirebaseMessagingService
// (onMessageReceived -> Client.handlePushNotification, onNewToken), persisted-token
// silent login (VoxClientManager.ensureLoggedIn's loginWithAccessToken/refreshToken
// chain + VoxTokenStore), registerForPushNotifications wired after every successful
// login, and POST /api/agents/shift called from ConsoleViewModel.setAgentStatus(READY)
// (without that call this agent is never in the retry audience and no push is ever
// sent, however well the rest is wired). See AGENTS.md → "Push wake-up".
// STILL OPEN, and load-bearing: VoxClientManager.onIncomingCall has no listener
// assigned anywhere — that lands with the separate "Voximplant v3 SDK wired into
// CallEngine" phase (AGENTS.md phase table). Until then, a woken app gets the SDK
// logged in and registered, but a delivered call has nowhere to go. Also open: a
// live-device cold-start timing test against the server's 15s RING_RETRY_WINDOW_MS
// (unmeasured; see the push-wake handoff report), and the loginWithAccessToken /
// refreshToken parameter order, which is inferred from this SDK's internal
// consistency (byte-verified via javap; NOT confirmed against a rendered docs page —
// voximplant.com's docs app returned an empty client-side search shell to automated
// fetches during this change).

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  // Compose (BOM-managed)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // Registers this app as a self-managed calling app (telephony/TelecomRegistration).
  // Required before a Play upload: Play revokes USE_FULL_SCREEN_INTENT from apps that
  // are not calling apps, which would kill locked-screen ringing — see AGENTS.md.
  implementation(libs.androidx.core.telecom)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Supabase (BOM-managed) — versions come from the BOM, never inline
  implementation(platform(libs.supabase.bom))
  implementation(libs.supabase.auth)
  implementation(libs.supabase.postgrest)
  implementation(libs.supabase.realtime)
  implementation(libs.ktor.client.okhttp)   // MUST stay 3.1.2 — matches supabase-kt 3.1.4

  // DI: manual service locator (di/DependencyContainer). Hilt intentionally deferred
  // to the Voximplant phase, where the fake->real CallEngine swap justifies it.

  // Serialization / images / push
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.firebase.messaging)
  // Persists the Voximplant AuthParams token pair for silent (push-woken) login —
  // see VoxTokenStore's kdoc for why plain DataStore, not androidx.security. Already
  // in the version catalog, unused until this change.
  implementation(libs.androidx.datastore.preferences)

  // Telephony — Voximplant Android SDK v3 (modular, Kotlin/coroutines, stable since
  // 3.0.0; 3.2.0 as of 2026-07). Chosen over the legacy monolithic v2 because this
  // app is Compose + coroutines end-to-end (v3 is the modern, actively-maintained
  // line — the "v3 is Beta" note that steered the earlier v2 pick is now stale).
  // The BOM aligns core+calls versions; the human-agent SDK leg is built behind the
  // existing CallEngine/CallSession interfaces against `Client` (core) + `VICalls`.
  implementation(platform(libs.voximplant.bom))
  implementation(libs.voximplant.core)
  implementation(libs.voximplant.calls)
  // Runtime permission flow (RECORD_AUDIO) for self-managed VoIP.
  implementation(libs.accompanist.permissions)

  // Testing
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
