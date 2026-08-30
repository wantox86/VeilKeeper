plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "id.quezacolt.veilkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.quezacolt.veilkeeper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-sprint1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sprint 1: no server-side config for this yet -- backend host is
        // whatever machine runs `docker compose up` (see repo root
        // docker-compose.yml, host port 18091). 10.0.2.2 is the standard
        // Android emulator alias for the host machine's localhost. Override
        // via -PapiBaseUrl=... for a real device on the same LAN.
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:18091/"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Coroutines: async orchestration for network calls + off-main-thread
    // Argon2id (CPU/memory heavy -- must never run on the main thread).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON: request/response DTOs for the Sprint 1 auth API.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP client: SPEC-BASE.md Section 4.1 explicitly recommends
    // "Retrofit or equivalent lightweight HTTP client".
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Argon2id: NOT available in the JDK/Android stdlib (unlike AES-GCM via
    // javax.crypto or HKDF, which we hand-roll over stdlib HMAC). Argon2Kt is
    // a maintained Android-specific JNI binding shipping prebuilt native
    // libs for standard ABIs -- see internal crypto/Argon2idMasterKeyDeriver.kt
    // for the technical caveat this introduces for local unit testing.
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
