plugins {
    base
}

val traceboxGroup = "io.github.tracebox"
val traceboxVersion = providers.gradleProperty("traceboxVersion").get()

allprojects {
    group = traceboxGroup
    version = traceboxVersion
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("publishFoundation") {
    group = "publishing"
    description = "Publishes every Tracebox runtime artifact to the configured immutable Maven repository."
    dependsOn(
        ":android:tracebox-api:publish",
        ":android:tracebox-core:publish",
        ":android:tracebox-storage:publish",
        ":android:tracebox-directboot:publish",
        ":android:tracebox-anr-exit:publish",
        ":android:tracebox-native:publish",
        ":android:tracebox-export:publish",
        ":android:tracebox-export-ui:publish",
        ":android:tracebox-ui-compose:publish",
        ":android:tracebox:publish",
    )
}

tasks.register("phase0Check") {
    group = "verification"
    description = "Builds and tests every host-side Phase 0 component."
    dependsOn(
        ":android:tracebox-anr-exit:testDebugUnitTest",
        ":android:tracebox-native:testDebugUnitTest",
        ":test-apps:phase0-fixture:assembleDebug",
        ":test-apps:phase0-fixture:assembleRelease",
        ":test-apps:phase0-fixture:assembleQualificationRelease",
        ":test-apps:phase0-fixture:assembleDebuggableRelease",
        ":benchmarks:phase0-benchmark:assembleDebug",
        ":benchmarks:phase0-benchmark:assembleRelease",
    )
}

tasks.register("phase1Check") {
    group = "verification"
    description = "Runs the Phase 1 Kotlin API and build identity contract tests."
    dependsOn(
        ":android:tracebox-api:testDebugUnitTest",
        ":test-apps:phase0-fixture:captureTraceboxBuildIdentityNoInternetRelease",
        ":test-apps:phase0-fixture:captureTraceboxBuildIdentityHostNetworkRelease",
    )
}

tasks.register("phase2Check") {
    group = "verification"
    description = "Runs Phase 2 runtime and persistence fault-injection tests."
    dependsOn(
        ":android:tracebox-core:testDebugUnitTest",
        ":android:tracebox-storage:testDebugUnitTest",
        ":android:tracebox-directboot:testDebugUnitTest",
    )
}

tasks.register("phase4CoreCheck") {
    group = "verification"
    description = "Runs deterministic Phase 4 snapshot, manifest, and ZIP tests."
    dependsOn(":android:tracebox-export:testDebugUnitTest")
}
