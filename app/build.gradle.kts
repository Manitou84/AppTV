import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vodtv.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vodtv.client"
        minSdk = 26            // Android 8.0: java.time nativo, Ktor 3 senza desugaring
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // FilterChip e altri componenti tv-material sono ancora "experimental"
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

dependencies {
    // --- Versioni (aggiornale da qui) ---
    val composeBom = "2025.04.01"
    val tvMaterial = "1.0.0"
    val media3 = "1.6.1"
    val ktor = "3.1.2"
    val room = "2.7.1"
    val lifecycle = "2.8.7"
    val coil = "3.1.0"

    // AndroidX core + Activity
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Jetpack Compose (BOM) — foundation per LazyVerticalGrid/LazyRow, material3 solo per i TextField
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose for TV (componenti con focus e D-pad nativi)
    implementation("androidx.tv:tv-material:$tvMaterial")

    // Navigazione + ViewModel
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")

    // Media3 / ExoPlayer: core, HLS, UI (PlayerView con controlli D-pad)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Rete: Ktor client su OkHttp + parsing JSON flessibile
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-logging:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Persistenza: impostazioni (DataStore) + preferiti/cronologia (Room)
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // Immagini
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")
}
