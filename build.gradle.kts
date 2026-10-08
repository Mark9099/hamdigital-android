// Top-level build file: plugin versions only (each module applies what it needs).
plugins {
    alias(libs.plugins.android.application) apply false   // Android app plugin
    alias(libs.plugins.kotlin.compose) apply false        // Compose compiler (Kotlin 2.x)
}
