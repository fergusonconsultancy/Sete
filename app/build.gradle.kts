plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase sync is optional. Its three values come from GitHub repository secrets
// (FIREBASE_API_KEY, FIREBASE_APP_ID, FIREBASE_PROJECT_ID). Without them the app works on one phone.
fun secret(name: String) = (System.getenv(name) ?: "").replace("\"", "")

android {
    namespace = "com.fergusonconsultancy.sete"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fergusonconsultancy.sete"
        minSdk = 30
        targetSdk = 35
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${secret("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${secret("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${secret("FIREBASE_PROJECT_ID")}\"")
        // Plant identification with Claude's vision; empty means it is off
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${secret("ANTHROPIC_API_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One fixed key for every build, so new APKs install over the old one.
    // Debug-only key for personal sideloading. Never use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/sete-debug.jks")
            storePassword = "setedebug"
            keyAlias = "sete"
            keyPassword = "setedebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
}
