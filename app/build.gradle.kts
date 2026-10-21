import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// engine/VERSION pins the bundled DeepSeek Harness; the full flavor reports it in About and checks npm for newer ones.
val engineVersions = Properties().apply { rootProject.file("engine/VERSION").reader().use { load(it) } }

android {
    namespace = "app.dsandroid"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.dsandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.3.0"
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
    // full: DeepSeek's own agent harness (dsh) on a bundled Node runtime, the default and primary build. It targets API 28
    // because the runtime is unpacked to app storage and executed there, which Android 10+ forbids for apps targeting
    // API 29+ (W^X); such a build cannot go on Google Play, so it is a separate APK with its own application id.
    // play: the v1.2.0 app (web account + built-in API agent), Play-compatible target SDK.
    flavorDimensions += "engine"
    productFlavors {
        create("full") {
            dimension = "engine"
            isDefault = true
            applicationIdSuffix = ".full"
            targetSdk = 28
            buildConfigField("boolean", "DSH_ENGINE", "true")
            buildConfigField("int", "TARGET_SDK", "28")
            buildConfigField("String", "DSH_VERSION", "\"${engineVersions.getProperty("DSH_VERSION")}\"")
            buildConfigField("String", "DSH_WEB_MOBILE_VERSION", "\"${engineVersions.getProperty("DSH_WEB_MOBILE")}\"")
            buildConfigField("String", "DSH_ANDROID_COMMIT", "\"${engineVersions.getProperty("DSH_ANDROID_COMMIT")}\"")
        }
        create("play") {
            dimension = "engine"
            buildConfigField("boolean", "DSH_ENGINE", "false")
            buildConfigField("int", "TARGET_SDK", "35")
            buildConfigField("String", "DSH_VERSION", "\"\"")
            buildConfigField("String", "DSH_WEB_MOBILE_VERSION", "\"\"")
            buildConfigField("String", "DSH_ANDROID_COMMIT", "\"\"")
        }
    }
    // The engine payload (a gzip tar, built in CI by scripts/build-dsh-payload.sh) ships as lib/<abi>/libdshpayload.so so
    // ABI splits keep one runtime per APK. Without a payload the full build still assembles and says the engine is missing.
    sourceSets.getByName("full").jniLibs.srcDir(rootProject.file("engine/payload/jniLibs"))
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
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
        // The full flavor's API 28 target is deliberate (see productFlavors); the play flavor keeps the current target.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Not ELF: nothing to strip. Stored uncompressed (the tar is already gzip) and streamed from the APK.
        jniLibs.keepDebugSymbols += "**/libdshpayload.so"
    }
}

// One APK per ABI for full (each carries one runtime payload), one universal APK for play.
androidComponents {
    onVariants { variant ->
        val full = variant.flavorName == "full"
        variant.outputs.forEach { output ->
            val perAbi = output.filters.any { it.filterType == FilterConfiguration.FilterType.ABI }
            if (perAbi != full) output.enable.set(false)
        }
    }
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
    // Engine unit tests (full flavor): real org.json instead of android.jar's stubs, and a mock npm registry / GitHub.
    "testFullImplementation"("org.json:json:20240303")
    "testFullImplementation"(libs.mockwebserver)
    // The engine journey checks the browser hand-off and the Google page outside the app.
    "androidTestFullImplementation"(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.mockwebserver)
}
