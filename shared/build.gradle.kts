import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.android.library") version "8.6.1"
    id("app.cash.sqldelight") version "2.0.2"
}

fun sharedConfigValue(name: String, default: String = ""): String =
    System.getenv(name) ?: (project.findProperty(name) as String? ?: default)

compose.resources {
    publicResClass = true
    packageOfResClass = "com.telefam.shared.generated.resources"
    generateResClass = auto
}

sqldelight {
    databases {
        create("TelefamDatabase") {
            packageName.set("com.telefam.db.local")
            // 3.30 dialect: UPSERT (ON CONFLICT ... DO UPDATE) used by AppCache/PeerCache/ChatSettingsLocal
            dialect("app.cash.sqldelight:sqlite-3-30-dialect:2.0.2")
        }
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "shared" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)

            implementation("io.ktor:ktor-client-core:2.3.13")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
            implementation("io.ktor:ktor-client-auth:2.3.13")
            implementation("io.ktor:ktor-client-websockets:2.3.13")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("app.cash.sqldelight:runtime:2.0.2")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

            // Coil3 (Compose Multiplatform image loading) — real avatar loading, not a placeholder lib.
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            implementation("io.coil-kt.coil3:coil-network-ktor2:3.0.4")
            implementation("io.coil-kt.coil3:coil-gif:3.0.4")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:2.3.13")
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.credentials:credentials:1.3.0")
            implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
            implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
            implementation("app.cash.sqldelight:android-driver:2.0.2")
            implementation("androidx.work:work-runtime-ktx:2.10.0")
            // Client-side video compression for post uploads (720p H.264/AAC re-encode)
            implementation("androidx.media3:media3-transformer:1.5.1")
            implementation("androidx.media3:media3-effect:1.5.1")
            implementation("androidx.media3:media3-common:1.5.1")
            // Feeds playback: ExoPlayer + persistent byte cache (offline playback, chunked preload)
            implementation("androidx.media3:media3-exoplayer:1.5.1")
            implementation("androidx.media3:media3-ui:1.5.1")
            implementation("androidx.media3:media3-datasource:1.5.1")
            // media3-datasource-cache was removed upstream in Media3 1.5.x — its classes
            // (SimpleCache, CacheDataSource, CacheWriter) ship inside media3-datasource.
            implementation("androidx.media3:media3-database:1.5.1")
            implementation("androidx.security:security-crypto:1.1.0-alpha06")
            implementation("org.signal:libsignal-android:0.86.5")
            // Verification: live face tracking (liveness) + document edge capture — real ML, no simulation
            implementation("androidx.camera:camera-core:1.4.1")
            implementation("androidx.camera:camera-camera2:1.4.1")
            implementation("androidx.camera:camera-lifecycle:1.4.1")
            implementation("androidx.camera:camera-view:1.4.1")
            implementation("com.google.mlkit:face-detection:16.1.7")
            // Linked devices: on-device QR/barcode scanning for the pairing camera.
            implementation("com.google.mlkit:barcode-scanning:17.3.0")
            implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
            // WebRTC — P2P audio/video/screen-share engine for in-chat calls.
            implementation("io.github.webrtc-sdk:android:125.6422.04")
            // androidx.compose.ui:ui-viewinterop is not a real artifact — AndroidView ships inside compose ui.
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:2.3.13")
            implementation("app.cash.sqldelight:native-driver:2.0.2")
        }
    }
}

android {
    namespace = "com.telefam.shared"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "API_BASE_URL", "\"${sharedConfigValue("API_BASE_URL", "https://api.telefam.app")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${sharedConfigValue("GIPHY_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID_WEB", "\"${sharedConfigValue("GOOGLE_CLIENT_ID_WEB")}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
