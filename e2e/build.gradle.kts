plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Test-only host for the release E2E driver; never published. Its androidTest APK drives the separately installed,
// unmodified release build of app.dsandroid through UiAutomator (scripts/run_release_e2e.sh). It shares no code with :app.
android {
    namespace = "app.dsandroid.e2e"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.dsandroid.e2e"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // FileProvider: the journey shares a fixture file into the app as an attachment, like any other app would.
    implementation("androidx.core:core:1.13.1")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.junit)
}
