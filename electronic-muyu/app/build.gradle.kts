plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val configuredRelayBaseUrl = providers.gradleProperty("ELECTRONIC_MUYU_RELAY_BASE_URL")
    .orElse("https://relay.invalid")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "app.electronicmuyu.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.electronicmuyu.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.7.0"

        buildConfigField("String", "RELAY_BASE_URL", "\"$configuredRelayBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ALLOW_RELAY_OVERRIDE", "false")
        }
        debug {
            isDebuggable = true
            // Debug logs are allowed but sensitive data must still be masked
            buildConfigField("boolean", "ALLOW_RELAY_OVERRIDE", "true")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // AGP 8.10.1's bundled Compose detector reads at most Kotlin metadata 2.0,
        // while Kotlin 2.0 emits metadata 2.1 and crashes the detector itself.
        // Keep all other lint checks enabled; remove this once the toolchain detector is compatible.
        disable += "StateFlowValueCalledInComposition"
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle Process (for ProcessLifecycleOwner)
    implementation(libs.androidx.lifecycle.process)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // OkHttp WebSocket
    implementation(libs.okhttp)

    // Offline QR scanning and rendering. The ML Kit model is bundled in the APK.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
