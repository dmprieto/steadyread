plugins {
    // No Kotlin plugin: AGP 9.0+ has built-in Kotlin support, and applying
    // org.jetbrains.kotlin.android alongside it is a hard error.
    // https://issuetracker.google.com/438678642
    alias(libs.plugins.android.application)
}

android {
    // Namespace kept from the spike: it is an internal identifier (the naming pass
    // made internal identifiers optional). The user- and reviewer-facing identity is
    // the applicationId and the strings, both changed for the reading port.
    namespace = "dev.spike.autoscroll"
    compileSdk = 37

    defaultConfig {
        // Permanent (attaches at distribution). Renamed from
        // the spike's dev.spike.autoscroll.
        applicationId = "io.github.dmprieto.reading"
        minSdk = 26
        // NOTE (edge-to-edge): this target is >= 36, so the app draws edge-to-edge and the old
        // windowOptOutEdgeToEdgeEnforcement flag is ignored (it was on Android 16). The settings
        // screen handles this itself now -- it owns its window insets in code
        // (SettingsActivity.applyContentInsets); the theme no longer sets the opt-out. Raising the
        // target does not affect this; the inset handling does.
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No dependencies. No third-party libraries, no AndroidX, no INTERNET.
dependencies {
}
