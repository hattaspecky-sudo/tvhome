plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tvhome.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvhome.launcher"
        minSdk = 28          // Android TV 9+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // tv    = real launcher (registers as Home screen)
    // phone = test build, opens as a normal app in landscape, never asks to replace your phone launcher
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "IS_HOME", "true")
            buildConfigField("boolean", "IS_PHONE", "false")
        }
        create("phone") {
            dimension = "device"
            applicationIdSuffix = ".phone"
            versionNameSuffix = "-phone"
            buildConfigField("boolean", "IS_HOME", "false")
            buildConfigField("boolean", "IS_PHONE", "true")
        }
    }

    // Fixed key so each new build installs as an update (GitHub makes a new debug key every run)
    signingConfigs {
        create("fixed") {
            storeFile = file("tvhome.jks")
            storePassword = "tvhome123"
            keyAlias = "tvhome"
            keyPassword = "tvhome123"
        }
    }

    buildTypes {
        // The cloud build runs assemble*Debug, so "debug" is made fully optimized here.
        // Non-debuggable + R8 makes Compose run several times smoother on TV chips.
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
