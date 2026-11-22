import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key, kept out of git in signing/ (create it with make-release-key.ps1).
// Every APK given to testers must be signed with this same key, or they can't update.
val signingProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = signingProps.getProperty("storeFile") != null

android {
    namespace = "dev.banglish.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.banglish.keyboard"
        minSdk = 26
        targetSdk = 36
        // Bump versionCode for every APK you hand out; Android only updates to a higher one.
        versionCode = 1
        versionName = "0.1-alpha"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file("signing/" + signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else {
                logger.warn("Mixer: no signing/keystore.properties - release APK is signed with the DEBUG key. Don't share it.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    // Deliberately no AndroidX/Compose: the keyboard is plain Views drawn on a Canvas.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
