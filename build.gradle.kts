buildscript {
    repositories { google() }
    dependencies {
        // R8 bundled with AGP 8.7 can't read Kotlin 2.3 metadata; this newer R8 can.
        classpath("com.android.tools:r8:8.13.25")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
