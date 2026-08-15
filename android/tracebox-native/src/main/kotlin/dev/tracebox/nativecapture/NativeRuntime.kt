package dev.tracebox.nativecapture

object NativeRuntime {
    init {
        System.loadLibrary("tracebox_crashpad")
    }

    /**
     * Opens the pre-ingested emergency slot for this persisted process instance.
     *
     * Initialization is deliberately fail-closed. Call [updatePolicy] with the same epoch before
     * making the process capture-capable or connecting it to the handler.
     */
    fun initializeEmergency(
        directory: String,
        processRole: Int,
        processIdentity: ByteArray,
        policyEpoch: Long,
    ): Boolean {
        require(processIdentity.size == PROCESS_IDENTITY_BYTES) {
            "processIdentity must contain exactly $PROCESS_IDENTITY_BYTES bytes"
        }
        require(policyEpoch >= 0) { "policyEpoch must be non-negative" }
        return nativeInitializeEmergency(directory, processRole, processIdentity, policyEpoch)
    }

    private external fun nativeInitializeEmergency(
        directory: String,
        processRole: Int,
        processIdentity: ByteArray,
        policyEpoch: Long,
    ): Boolean

    /**
     * Attaches the calling thread to Tracebox's emergency signal fallback.
     *
     * Tracebox-owned native threads and threads entering this JNI API are attached automatically.
     * Host-created native or Rust threads must attach explicitly before relying on stack-overflow
     * fallback. Registration is idempotent and is released automatically when the thread exits.
     */
    fun registerCurrentThreadForCapture(): Boolean = nativeRegisterCurrentThreadForCapture()

    /**
     * Releases this thread's Tracebox alternate stack while preserving any stack that the host
     * installed before registration. Call this when a long-lived host thread outlives Tracebox
     * shutdown; ordinary terminating threads are cleaned up automatically.
     */
    fun unregisterCurrentThreadForCapture(): Boolean = nativeUnregisterCurrentThreadForCapture()

    private external fun nativeRegisterCurrentThreadForCapture(): Boolean

    private external fun nativeUnregisterCurrentThreadForCapture(): Boolean

    external fun updatePolicy(policyEpoch: Long, disabled: Boolean, denyMask: Long): Boolean

    external fun preparePolicy(
        policyEpoch: Long,
        disabled: Boolean,
        denyMask: Long,
        timeoutMillis: Int,
    ): Int

    external fun commitPreparedPolicy(policyEpoch: Long, timeoutMillis: Int = DEFAULT_POLICY_TIMEOUT_MILLIS): Int

    external fun abortPreparedPolicy(policyEpoch: Long, timeoutMillis: Int = DEFAULT_POLICY_TIMEOUT_MILLIS): Int

    /**
     * Compatibility one-shot for restrictive targets only. Loosening must use prepare/CE
     * commit/commitPreparedPolicy and is rejected by the native transport.
     */
    external fun coordinatePolicy(
        policyEpoch: Long,
        disabled: Boolean,
        denyMask: Long,
        timeoutMillis: Int,
    ): Int

    external fun shutdownCapture()

    /**
     * Fences capture and synchronously drains this process's native handler lifecycle census.
     *
     * In the handler process, `true` means admission is closed, every admitted lifecycle watcher
     * has durably finished its terminal journal and any Crashpad handoff, and the handler socket
     * marker has been retired. `false` leaves the marker in place so another shutdown attempt or
     * process-death stale cleanup cannot mistake a late handoff for quiescence.
     *
     * This is process-local: an app-process caller cannot drain the separate handler process.
     * The app façade must stop the handler service and treat disappearance of its canonical socket
     * path as the cross-process completion boundary.
     */
    fun shutdownCaptureAndDrain(timeoutMillis: Int = DEFAULT_HANDLER_DRAIN_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis in 1..MAX_HANDLER_DRAIN_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 1 and $MAX_HANDLER_DRAIN_TIMEOUT_MILLIS"
        }
        return nativeShutdownCaptureAndDrain(timeoutMillis)
    }

    private external fun nativeShutdownCaptureAndDrain(timeoutMillis: Int): Boolean

    /**
     * Allocates a Rust-owned typed identity and appends its fixed CRC-framed lifecycle entry to
     * [journalPath] before returning it.
     */
    fun allocateIdentity(journalPath: String, kind: Int): ByteArray? {
        require(journalPath.isNotBlank()) { "journalPath must not be blank" }
        require(kind in MIN_IDENTITY_KIND..MAX_IDENTITY_KIND) { "unsupported identity kind: $kind" }
        return nativeAllocateIdentity(journalPath, kind)
    }

    private external fun nativeAllocateIdentity(journalPath: String, kind: Int): ByteArray?

    /**
     * Derives the canonical Rust summary ID and journals the complete tuple and result before
     * returning it.
     */
    fun deriveSummaryId(
        journalPath: String,
        rawId: ByteArray,
        extractorVersion: Int,
        schema: ByteArray,
        contentSha256: ByteArray,
    ): ByteArray? {
        require(journalPath.isNotBlank()) { "journalPath must not be blank" }
        require(rawId.size == PROCESS_IDENTITY_BYTES) { "rawId must contain exactly 32 bytes" }
        require(extractorVersion >= 0) { "extractorVersion must be non-negative" }
        require(schema.size == PROCESS_IDENTITY_BYTES) { "schema must contain exactly 32 bytes" }
        require(contentSha256.size == PROCESS_IDENTITY_BYTES) {
            "contentSha256 must contain exactly 32 bytes"
        }
        return nativeDeriveSummaryId(
            journalPath,
            rawId,
            extractorVersion,
            schema,
            contentSha256,
        )
    }

    private external fun nativeDeriveSummaryId(
        journalPath: String,
        rawId: ByteArray,
        extractorVersion: Int,
        schema: ByteArray,
        contentSha256: ByteArray,
    ): ByteArray?

    external fun installRustPanicHook(): Boolean

    /**
     * Drains one bounded panic record as payload-kind, has-location, line, and column.
     */
    external fun drainRustPanic(): IntArray?

    /**
     * Parses one regular, non-symlink Crashpad file into the six ID-free structural fields.
     */
    fun summarizeMinidump(path: String, maximumBytes: Int): IntArray? {
        require(path.isNotBlank()) { "path must not be blank" }
        require(maximumBytes in 1..MAX_MINIDUMP_BYTES) {
            "maximumBytes must be between 1 and $MAX_MINIDUMP_BYTES"
        }
        return nativeSummarizeMinidump(path, maximumBytes)
    }

    private external fun nativeSummarizeMinidump(path: String, maximumBytes: Int): IntArray?

    external fun startHandler(socketPath: String): Int

    /**
     * Removes the canonical app-owned handler socket only after a bounded native probe proves that
     * no listener is alive. Returns true when the path is already absent or the stale AF_UNIX
     * socket was unlinked and its parent directory was forced; every invalid, live, raced, timed
     * out, or I/O-ambiguous case returns false without unlinking the observed entry.
     */
    external fun cleanupStaleHandlerSocket(socketPath: String): Boolean

    fun connectClient(
        socketPath: String,
        processRole: Int,
        processIdentity: ByteArray,
        rawArtifactId: ByteArray,
        policyEpoch: Long,
    ): Boolean {
        require(processIdentity.size == PROCESS_IDENTITY_BYTES) {
            "processIdentity must contain exactly $PROCESS_IDENTITY_BYTES bytes"
        }
        require(rawArtifactId.size == PROCESS_IDENTITY_BYTES) {
            "rawArtifactId must contain exactly $PROCESS_IDENTITY_BYTES bytes"
        }
        require(policyEpoch >= 0) { "policyEpoch must be non-negative" }
        return nativeConnectClient(
            socketPath,
            processRole,
            processIdentity,
            rawArtifactId,
            policyEpoch,
        )
    }

    /**
     * Registers one long-lived handler participant and returns the exact granted capture mode.
     *
     * [CLIENT_REQUEST_CRASHPAD_REQUIRED] is never silently downgraded. Fallback-only callers must
     * pass a null [rawArtifactId], do not receive a Crashpad descriptor, and participate in global
     * PREPARE/COMMIT/ABORT while retaining only the policy-gated emergency slot and Rust panic
     * hook. [CLIENT_REQUEST_CRASHPAD_OR_EMERGENCY_RUST] may receive either explicit mode; its
     * caller remains responsible for retiring any pre-capture reservation when fallback is
     * returned. Every rejected, timed-out, malformed, or ambiguous registration returns
     * [CLIENT_MODE_REJECTED].
     */
    fun connectClientMode(
        socketPath: String,
        processRole: Int,
        processIdentity: ByteArray,
        rawArtifactId: ByteArray?,
        policyEpoch: Long,
        requestedMode: Int,
    ): Int {
        require(processRole >= 0) { "processRole must be non-negative" }
        require(processIdentity.size == PROCESS_IDENTITY_BYTES) {
            "processIdentity must contain exactly $PROCESS_IDENTITY_BYTES bytes"
        }
        require(processIdentity.any { it != 0.toByte() }) {
            "processIdentity must not be all zero"
        }
        require(policyEpoch >= 0) { "policyEpoch must be non-negative" }
        require(
            requestedMode == CLIENT_REQUEST_CRASHPAD_REQUIRED ||
                requestedMode == CLIENT_REQUEST_EMERGENCY_RUST_ONLY ||
                requestedMode == CLIENT_REQUEST_CRASHPAD_OR_EMERGENCY_RUST,
        ) {
            "unsupported client registration request: $requestedMode"
        }
        val nativeRawArtifactId =
            if (requestedMode == CLIENT_REQUEST_EMERGENCY_RUST_ONLY) {
                require(rawArtifactId == null) {
                    "fallback-only registration must not claim a raw artifact identity"
                }
                ByteArray(PROCESS_IDENTITY_BYTES)
            } else {
                val requiredRawArtifactId = requireNotNull(rawArtifactId) {
                    "Crashpad-capable registration requires a raw artifact identity"
                }
                require(requiredRawArtifactId.size == PROCESS_IDENTITY_BYTES) {
                    "Crashpad-capable registration requires exactly $PROCESS_IDENTITY_BYTES raw ID bytes"
                }
                require(requiredRawArtifactId.any { it != 0.toByte() }) {
                    "rawArtifactId must not be all zero"
                }
                requiredRawArtifactId
            }
        return nativeConnectClientMode(
            socketPath,
            processRole,
            processIdentity,
            nativeRawArtifactId,
            policyEpoch,
            requestedMode,
        )
    }

    private external fun nativeConnectClient(
        socketPath: String,
        processRole: Int,
        processIdentity: ByteArray,
        rawArtifactId: ByteArray,
        policyEpoch: Long,
    ): Boolean

    private external fun nativeConnectClientMode(
        socketPath: String,
        processRole: Int,
        processIdentity: ByteArray,
        rawArtifactId: ByteArray,
        policyEpoch: Long,
        requestedMode: Int,
    ): Int

    /** Returns the validated unfenced Crashpad handler PID, or -1 when Crashpad is unavailable. */
    external fun handlerPid(): Int

    /**
     * True while the mode-negotiated control connection remains available for global policy
     * PREPARE/COMMIT/ABORT. Unlike [isHandlerAlive], this remains true when PREPARE has fenced the
     * Crashpad descriptor but an emergency/Rust participant is still connected. After a
     * registered connection is lost, native code disables emergency/Rust capture and fences
     * Crashpad before publishing false, so false is a safe reconnect trigger. Before the first
     * successful registration it is only a liveness value, not proof that initialization ran.
     */
    external fun isPolicyParticipantAlive(): Boolean

    external fun requestNonFatal(reason: Int, timeoutMillis: Int): Boolean

    external fun isHandlerAlive(): Boolean

    const val CLIENT_MODE_REJECTED = 0
    const val CLIENT_MODE_CRASHPAD = 1
    const val CLIENT_MODE_EMERGENCY_RUST = 2
    const val CLIENT_REQUEST_CRASHPAD_REQUIRED = 1
    const val CLIENT_REQUEST_EMERGENCY_RUST_ONLY = 2
    const val CLIENT_REQUEST_CRASHPAD_OR_EMERGENCY_RUST = 3

    private const val PROCESS_IDENTITY_BYTES = 32
    private const val MIN_IDENTITY_KIND = 1
    private const val MAX_IDENTITY_KIND = 6
    private const val MAX_MINIDUMP_BYTES = 16 * 1024 * 1024
    private const val DEFAULT_POLICY_TIMEOUT_MILLIS = 2_000
    const val DEFAULT_HANDLER_DRAIN_TIMEOUT_MILLIS = 3_000
    const val MAX_HANDLER_DRAIN_TIMEOUT_MILLIS = 5_000
}
