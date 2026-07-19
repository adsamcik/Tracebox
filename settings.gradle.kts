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

includeBuild("build-logic")

rootProject.name = "tracebox"

include(
    ":android:tracebox-anr-exit",
    ":android:tracebox-native",
    ":android:tracebox-api",
    ":android:tracebox-core",
    ":android:tracebox-storage",
    ":android:tracebox-export",
    ":android:tracebox-directboot",
    ":tooling:tracebox-gradle-plugin",
    ":benchmarks:phase0-benchmark",
    ":test-apps:phase0-fixture",
)
