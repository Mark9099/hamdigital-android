import java.util.Properties                          // keystore.properties reader

// The app module: Kotlin + Jetpack Compose UI, and the native decoders (open-source C/C++) built with the NDK (src/main/cpp).
plugins {
    alias(libs.plugins.android.application)          // Android app
    alias(libs.plugins.kotlin.compose)               // Compose compiler
}

// Release signing: keystore.properties (project root, not in version control) names the keystore and its passwords.
// Without it the release build is left unsigned (debug builds always use Android Studio's own debug key).
val signing = Properties().apply {                   // the signing details, if present
    val f = rootProject.file("keystore.properties")  // the file
    if (f.exists()) f.inputStream().use { load(it) } // read it when there
}

android {
    namespace = "uk.hamdigital"                      // Kotlin package / R class
    compileSdk = 37                                  // Android 17 APIs (the current Compose libraries need them)
    ndkVersion = "30.0.16248370"                     // NDK from the SDK Manager

    defaultConfig {
        applicationId = "uk.hamdigital"              // installed app id
        minSdk = 26                                  // Android 8.0 and newer
        targetSdk = 35                               // Android 15 behaviour
        versionCode = 25                             // store version number
        versionName = "0.10.5"                        // shown on the startup screen and in Settings
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } // phones, tablets, the emulator
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") { // the release key
            storeFile = file(signing.getProperty("storeFile"))     // keystore
            storePassword = signing.getProperty("storePassword")  // its password
            keyAlias = signing.getProperty("keyAlias")            // the key in it
            keyPassword = signing.getProperty("keyPassword")      // the key's password
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it } // signed with the release key when it is set up
            isMinifyEnabled = false                  // no shrinking (keeps JNI names simple)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }                 // Jetpack Compose
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt") // the decoders
            version = "4.1.2"                        // CMake from the SDK Manager
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)                     // Kotlin helpers for Android
    implementation(libs.androidx.lifecycle.runtime.ktx)        // lifecycle scopes
    implementation(libs.androidx.lifecycle.viewmodel.compose)  // viewModel() in Compose
    implementation(libs.androidx.lifecycle.runtime.compose)    // collectAsStateWithLifecycle
    implementation(libs.androidx.activity.compose)             // setContent
    implementation(platform(libs.androidx.compose.bom))        // Compose versions
    implementation(libs.androidx.ui)                           // Compose UI
    implementation(libs.androidx.ui.graphics)                  // drawing
    implementation(libs.androidx.ui.tooling.preview)           // previews
    implementation(libs.androidx.material3)                    // Material 3
    implementation(libs.androidx.material.icons.extended)      // icons
    implementation(libs.androidx.datastore.preferences)        // settings
    implementation(libs.kotlinx.coroutines.android)            // coroutines
    implementation(libs.usb.serial)                            // USB serial (CDC-ACM) for the IC-705 CI-V port (MIT)
    debugImplementation(libs.androidx.ui.tooling)              // layout inspector
}
