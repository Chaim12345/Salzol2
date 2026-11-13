import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    // google-services is applied conditionally below — the repo builds without google-services.json.
}

android {
    namespace = "il.salzol.go"
    compileSdk = 36

    defaultConfig {
        applicationId = "il.salzol.go"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // debug.keystore is optional: when absent (fresh clone, CI) the default debug keystore
    // (~/.android/debug.keystore, auto-created by AGP) signs debug builds and release stays unsigned.
    val debugKs = file("${rootDir}/debug.keystore")
    if (debugKs.exists()) {
        signingConfigs {
            create("debugConfig") {
                storeFile = debugKs
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (debugKs.exists()) signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (debugKs.exists()) signingConfig = signingConfigs.getByName("debugConfig")
        }
    }
}

// Firebase's google-services plugin is optional: with no google-services.json the app still
// compiles and unit-tests (tests configure Firebase manually); drop the file in and it activates.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Firebase Auth & Firestore (BoM main artifacts without -ktx)
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")

    // Credential Manager & Google Sign-In
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")

    // Glance Home Screen Widget
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    // On-device OCR (barcodes, prices, totals) & QR generation
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.zxing:core:3.5.4")

    // Local Unit & Firestore Emulator Rules Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.21")
}

tasks.withType<Test> {
    failFast = true
    timeout.set(Duration.ofMinutes(2))
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    testLogging {
        events("passed", "failed", "skipped")
    }
}
