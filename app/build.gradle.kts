plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "app.dsandroid"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.dsandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Chaquopy ships native Python per ABI: current phones and x86_64 emulators (CI) only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // No project properties, files, or fallback passwords: CI supplies all four values.
    val releaseSigning = listOf(
        "DSA_KEYSTORE_PATH", "DSA_KEYSTORE_PASSWORD", "DSA_KEY_ALIAS", "DSA_KEY_PASSWORD"
    ).map { providers.environmentVariable(it).orNull }
    if (releaseSigning.all { !it.isNullOrBlank() }) {
        signingConfigs.create("ciRelease") {
            storeFile = file(requireNotNull(releaseSigning[0]))
            storePassword = releaseSigning[1]
            keyAlias = releaseSigning[2]
            keyPassword = releaseSigning[3]
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("ciRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Python 3.13 is the newest version with Chaquopy wheels for numpy and matplotlib, and it supports
// 16 KB page devices. The build machine needs python3.13 on PATH (CI uses actions/setup-python).
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            // PyPI read timeouts on the release runner failed one v0.6.0 build; retry instead.
            options("--timeout", "120", "--retries", "10")
            install("numpy==1.26.2")
            install("matplotlib==3.8.4")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.security.crypto)
    implementation(libs.browser)
    implementation(libs.work.runtime)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.exifinterface)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.mockwebserver)
}
