// HF Digital Modes for Android - FT8, FT4, WSPR, JS8Call, RTTY, PSK31 and CW in one app, for the Icom IC-705.
pluginManagement {
    repositories {
        google()                                     // Android Gradle plugin, AndroidX
        mavenCentral()                               // Kotlin, libraries
        gradlePluginPortal()                         // other plugins
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) // all repositories declared here
    repositories {
        google()                                     // AndroidX
        mavenCentral()                               // kotlinx
        maven("https://jitpack.io")                  // usb-serial-for-android (IC-705 CI-V)
    }
}
rootProject.name = "HamDigital"                      // project name
include(":app")                                      // the app module
