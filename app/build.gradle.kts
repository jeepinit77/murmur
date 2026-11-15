import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.murmur"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.murmur"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // sherpa-onnx ships arm64 + armv7 + x86_64 libs; Pixels are arm64. Keep x86_64 for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // foss: open-source voices only (sherpa-onnx). play: also offers Google's on-device voices
    // through ML Kit, a proprietary library, on phones that support them.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            isDefault = true
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with the debug key so CI release APKs install; replace for store distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/*.md")
        // Extracting native libs keeps the ONNX runtime mmap-friendly and the APK diff small.
        jniLibs.useLegacyPackaging = true
    }
    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// sherpa-onnx comes either from the prebuilt AAR (scripts/fetch-sherpa-onnx.sh, used by CI) or
// from a source build (scripts/build-sherpa-from-source.sh), which vendors the Kotlin API into
// src/main/java/com/k2fsa and the native libraries into src/main/jniLibs.
val sherpaAar = rootProject.file("app/libs/sherpa-onnx.aar")
val sherpaFromSource = file("src/main/java/com/k2fsa/sherpa/onnx/Tts.kt").exists()
if (!sherpaAar.exists() && !sherpaFromSource) {
    logger.warn("sherpa-onnx missing: run ./scripts/fetch-sherpa-onnx.sh or ./scripts/build-sherpa-from-source.sh")
}

dependencies {
    implementation(project(":core:text"))
    if (!sherpaFromSource) implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.pdfbox.android)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.guava)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "playImplementation"(libs.mlkit.genai.tts)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
