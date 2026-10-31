plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.watchreborn"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.orbital"
        // OnBackInvokedCallback and the Android 13 themed-icon APIs.
        minSdk = 33
        targetSdk = 36
        versionCode = 81
        versionName = "3.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from ~/.gradle/gradle.properties (never the repo): REBORN_KEYSTORE,
    // REBORN_KEYSTORE_PASSWORD, REBORN_KEY_ALIAS, REBORN_KEY_PASSWORD. Without them, release builds
    // fall back to the debug key so anyone can still build and sideload.
    val keystore = providers.gradleProperty("REBORN_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("REBORN_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("REBORN_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("REBORN_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // Play prep (2.9.2): shrink + obfuscate. Safe here — the only
            // reflection in the app targets a system status-bar object,
            // and manifest-declared components are kept by aapt rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
