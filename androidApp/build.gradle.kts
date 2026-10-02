import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.6.1"
    kotlin("android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    // Reads google-services.json (add yours from the Firebase console) for incoming-call pushes.
    id("com.google.gms.google-services") version "4.4.2"
}

/** Every value below is read from environment variables at build time — nothing hardcoded. */
fun envOrProp(name: String, default: String = ""): String =
    System.getenv(name) ?: (project.findProperty(name) as String? ?: default)

android {
    namespace = "com.telefam.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    packaging {
        jniLibs.excludes += setOf("**/libsignal_jni_testing.so")
        resources.excludes += setOf("**/*.dylib", "**/*.dll")
    }

    defaultConfig {
        applicationId = "com.telefam.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "API_BASE_URL", "\"${envOrProp("API_BASE_URL", "https://api.telefam.app")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID_WEB", "\"${envOrProp("GOOGLE_CLIENT_ID_WEB")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID_ANDROID", "\"${envOrProp("GOOGLE_CLIENT_ID_ANDROID")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${envOrProp("GIPHY_API_KEY")}\"")
    }

    buildFeatures { buildConfig = true; compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by libsignal-android
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    // MainActivity and push-token registration call Ktor request/body APIs directly.
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("io.ktor:ktor-client-android:2.3.13")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.material3:material3")

    // WebRTC (maintained build of the Google SDK) — P2P media engine for calls.
    implementation("io.github.webrtc-sdk:android:125.6422.04")

    // Incoming-call pushes when the app is closed (data messages only; call UI is local).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
