plugins {
    alias(libs.plugins.android.application)
}

android {
    // The package rename makes this a NEW app identity: Android treats the new
    // namespace/applicationId as a different application, so the previously
    // installed old-package build must be uninstalled and its placed widgets re-added.
    namespace = "com.aiusage.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aiusage.monitor"
        minSdk = 23
        targetSdk = 35
        // Bumped for the Phase 1 architecture rewrite. Phase 0 carried the
        // upstream baseline verbatim: --version-code 56 --version-name "3.32".
        versionCode = 57
        versionName = "4.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = false
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    // Embedded QR scanning works offline and does not require another scanner app.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
