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
        applicationId = "com.fennell.sentrykey"
        minSdk = 24
        targetSdk = 36

        // Auto-versioning. CI sets these env vars (see .github/workflows/build.yml):
        //  - VERSION_CODE: monotonic integer (the CI run number). Play REQUIRES a
        //    higher versionCode for every upload, so this must always increase.
        //  - VERSION_NAME: human version ("1.2.3" from a vX.Y.Z tag, else "1.0.0").
        // Local builds (no env) fall back to a safe dev value.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"

        // Tag of the build, used by the in-app updater to detect newer releases.
        // GITHUB_REF_NAME is the tag (e.g. "v1.0.0-beta.15") on CI tag builds;
        // falls back to "dev" for local builds (which then offer the latest release).
        val releaseTag = System.getenv("GITHUB_REF_NAME") ?: "dev"
        buildConfigField("String", "RELEASE_TAG", "\"$releaseTag\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Distribution channel decides the update mechanism:
    //  - play:   Google Play In-App Updates (no APK self-install permission)
    //  - github: self-updating APK from GitHub Releases (sideload)
    // The same code is built twice; upload the `play` APK/AAB to Play and attach
    // the `github` APK to GitHub Releases.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("Boolean", "USE_PLAY_UPDATES", "true")
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("Boolean", "USE_PLAY_UPDATES", "false")
        }
    }

    // Release (upload) signing comes from environment variables so the keystore
    // and passwords stay out of the repo. CI sets these from GitHub Actions
    // secrets; locally they're unset, so release builds are simply unsigned
    // (debug builds are unaffected). See RELEASE_KEYSTORE_* below + the CI job.
    val releaseStorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val hasReleaseSigning = releaseStorePath != null

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
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
            // Sign with the upload key when CI provides it; otherwise leave the
            // release build unsigned (e.g. local `bundlePlayRelease` smoke tests).
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    testOptions {
        unitTests {
            // Robolectric needs the merged manifest/resources; return defaults for
            // any incidental Android stub call in plain-JVM tests.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
    // Play In-App Updates — only linked into the `play` flavor.
    "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation(libs.play.services.code.scanner)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
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
    // Robolectric: run the android.util.Base64-dependent crypto tests headlessly.
    testImplementation(libs.androidx.junit)
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}