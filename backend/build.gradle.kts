plugins {
    kotlin("jvm") version "2.0.21"
    id("io.ktor.plugin") version "2.3.13"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.telefam"
version = "1.0.0"

application {
    mainClass.set("com.telefam.ApplicationKt")
}

repositories { mavenCentral() }

val exposedVersion = "0.55.0"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-server-auth-jwt-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")
    implementation("io.ktor:ktor-server-rate-limit:2.3.13")
    implementation("io.ktor:ktor-server-websockets-jvm")
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
    implementation("io.ktor:ktor-client-content-negotiation-jvm")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.29.0")

    // Real Apple Sign In verification (JWKS + JWT)
    implementation("com.auth0:jwks-rsa:0.22.1")

    // Media processing (real resize/re-encode, no stub)
    implementation("net.coobird:thumbnailator:0.4.20")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.0")

    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation(kotlin("test"))
}

// ---------------------------------------------------------------------------------------------
// Tests. Pure unit tests always run. Integration tests need a real Postgres and are skipped
// (not failed) when none is reachable:
//   docker run -d --name telefam-test-db -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=telefam_test -p 5432:5432 postgres:16
//   gradle test
// Override with TEST_DATABASE_URL / TEST_DATABASE_USER / TEST_DATABASE_PASSWORD.
// ---------------------------------------------------------------------------------------------
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
    environment(
        mapOf(
            "DATABASE_URL" to (System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/telefam_test"),
            "DATABASE_USER" to (System.getenv("TEST_DATABASE_USER") ?: "postgres"),
            "DATABASE_PASSWORD" to (System.getenv("TEST_DATABASE_PASSWORD") ?: "postgres"),
            "JWT_ACCESS_SECRET" to "test-access-secret-0123456789-0123456789-0123456789",
            "JWT_REFRESH_SECRET" to "test-refresh-secret-0123456789-0123456789-0123456789",
            "GOOGLE_CLIENT_ID_WEB" to "test-web.apps.googleusercontent.com",
            "GOOGLE_CLIENT_ID_ANDROID" to "test-android.apps.googleusercontent.com",
            "APPLE_CLIENT_ID" to "com.telefam.app",
            "RESEND_API_KEY" to "re_test_not_used",
            "RESEND_FROM_ADDRESS" to "Telefam <test@example.com>"
        )
    )
}
