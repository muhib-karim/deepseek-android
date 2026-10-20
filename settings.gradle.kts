pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "deepseek-android"
// :e2e is the release E2E driver (UiAutomator, test-only host app); it never ships in the app.
include(":core", ":app", ":e2e")
