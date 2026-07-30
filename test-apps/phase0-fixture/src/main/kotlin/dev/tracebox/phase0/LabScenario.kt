package dev.tracebox.phase0

/** Stable personal-release scenario identifiers consumed by the app and host runner. */
enum class LabScenario(
    val stableId: String,
    val transport: LabTransport,
    val requiredVariant: LabVariant = LabVariant.EITHER,
    val expectedTermination: Boolean = false,
) {
    INSTALL_READINESS("INSTALL.READINESS", LabTransport.ACTIVITY),
    HANDLER_COLD_START("HANDLER.COLD_START", LabTransport.RUNNER),
    HANDLER_RUNNING_ATTACH("HANDLER.RUNNING_ATTACH", LabTransport.RUNNER),
    HANDLER_CONFLICT("HANDLER.CONFLICT", LabTransport.ACTIVITY, expectedTermination = true),
    HANDLER_DEATH("HANDLER.DEATH", LabTransport.RUNNER),
    HANDLER_RESTART("HANDLER.RESTART", LabTransport.RUNNER),
    HANDLER_TIMEOUT("HANDLER.TIMEOUT", LabTransport.RUNNER),
    HANDLER_BACKGROUND_LIFETIME(
        "HANDLER.BACKGROUND_LIFETIME",
        LabTransport.RUNNER,
        expectedTermination = true,
    ),
    MULTIPROCESS_CAPTURE(
        "MULTIPROCESS.CAPTURE",
        LabTransport.RUNNER,
        expectedTermination = true,
    ),
    MULTIPROCESS_POLICY_BARRIER("MULTIPROCESS.POLICY_BARRIER", LabTransport.ACTIVITY),
    FAULT_JVM_UNCAUGHT("FAULT.JVM_UNCAUGHT", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_CPP_ABORT("FAULT.CPP_ABORT", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_CPP_SEGV("FAULT.CPP_SEGV", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_RUST_PANIC("FAULT.RUST_PANIC", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_EMERGENCY("FAULT.EMERGENCY", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_RECURSIVE("FAULT.RECURSIVE", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_OOM("FAULT.OOM", LabTransport.ACTIVITY, expectedTermination = true),
    FAULT_STACK_OVERFLOW("FAULT.STACK_OVERFLOW", LabTransport.ACTIVITY, expectedTermination = true),
    ANR_CANDIDATE("ANR.CANDIDATE", LabTransport.RUNNER),
    ANR_RESPONSIVE("ANR.RESPONSIVE", LabTransport.RUNNER),
    ANR_TIMEOUT("ANR.TIMEOUT", LabTransport.RUNNER),
    ANR_LIFECYCLE_SUPPRESSION("ANR.LIFECYCLE_SUPPRESSION", LabTransport.RUNNER),
    EXIT_RESTART_RECONCILIATION("EXIT.RESTART_RECONCILIATION", LabTransport.RUNNER),
    DIRECT_BOOT_C0_CAPTURE("DIRECT_BOOT.C0_CAPTURE", LabTransport.DIRECT_BOOT),
    STORAGE_PRESSURE("STORAGE.PRESSURE", LabTransport.ACTIVITY),
    DELETE_ALL_RESTART("DELETE.ALL_RESTART", LabTransport.ACTIVITY),
    DELETE_NO_ACCESSIBLE_DATA("DELETE.NO_ACCESSIBLE_DATA", LabTransport.RUNNER),
    PACKAGE_DISCLOSURE("PACKAGE.DISCLOSURE", LabTransport.ACTIVITY),
    PACKAGE_EXACT_APPROVAL("PACKAGE.EXACT_APPROVAL", LabTransport.RUNNER),
    PACKAGE_SAVE_SHARE("PACKAGE.SAVE_SHARE", LabTransport.RUNNER),
    SYMBOL_R8_RETRACE("SYMBOL.R8_RETRACE", LabTransport.HOST),
    SYMBOL_ELF("SYMBOL.ELF", LabTransport.HOST),
    NETWORK_NO_INTERNET(
        "NETWORK.NO_INTERNET",
        LabTransport.ACTIVITY,
        LabVariant.NO_INTERNET,
    ),
    NETWORK_HOST_CONTROL(
        "NETWORK.HOST_CONTROL",
        LabTransport.ACTIVITY,
        LabVariant.HOST_NETWORK,
    ),
    NETWORK_BLOCKED_EGRESS(
        "NETWORK.BLOCKED_EGRESS",
        LabTransport.RUNNER,
        LabVariant.HOST_NETWORK,
    ),
    RESOURCE_BASELINE("RESOURCE.BASELINE", LabTransport.RUNNER),
    CORPUS_PACKAGE("CORPUS.PACKAGE", LabTransport.HOST),
    CORPUS_ARCHIVE("CORPUS.ARCHIVE", LabTransport.HOST),
    CORPUS_SYMBOL("CORPUS.SYMBOL", LabTransport.HOST),
    ;

    companion object {
        private val byId = entries.associateBy(LabScenario::stableId)

        fun fromId(value: String?): LabScenario? = value?.let(byId::get)
    }
}

enum class LabTransport { ACTIVITY, BROADCAST, DIRECT_BOOT, RUNNER, HOST }

enum class LabVariant { NO_INTERNET, HOST_NETWORK, EITHER }

object LabBuildModel {
    const val NO_INTERNET_FLAVOR = "noInternet"
    const val HOST_NETWORK_FLAVOR = "hostNetwork"
    const val MINIFIED_BUILD_TYPE = "release"
    const val MINIFIED_TEST_BUILD_TYPE = "qualificationRelease"
    const val DEBUGGABLE_RELEASE_BUILD_TYPE = "debuggableRelease"
}
