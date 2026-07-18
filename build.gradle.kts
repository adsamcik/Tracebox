plugins {
    base
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("phase0Check") {
    group = "verification"
    description = "Builds and tests every host-side Phase 0 component."
    dependsOn(
        ":android:tracebox-anr-exit:testDebugUnitTest",
        ":android:tracebox-native:testDebugUnitTest",
        ":test-apps:phase0-fixture:assembleDebug",
        ":test-apps:phase0-fixture:assembleRelease",
        ":test-apps:phase0-fixture:assembleDebuggableRelease",
        ":benchmarks:phase0-benchmark:assembleDebug",
        ":benchmarks:phase0-benchmark:assembleRelease",
    )
}
