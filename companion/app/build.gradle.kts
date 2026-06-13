plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.sentrykey"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Public Play Store identity (must not be com.example.*). Kept distinct
        // from the code `namespace` above, which Play does not see.
        applicationId = "com.chrisdfennell.sentrykey"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Tag of the build, used by the in-app updater to detect newer releases.
        // GITHUB_REF_NAME is the tag (e.g. "v1.0.0-beta.15") on CI tag builds;
        // falls back to "dev" for local builds (which then offer the latest release).
        val releaseTag = System.getenv("GITHUB_REF_NAME") ?: "dev"
        buildConfigField("String", "RELEASE_TAG", "\"$releaseTag\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(libs.play.services.code.scanner)
    implementation(libs.coil.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}