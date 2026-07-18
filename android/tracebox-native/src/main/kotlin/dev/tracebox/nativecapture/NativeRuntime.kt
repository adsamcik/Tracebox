package dev.tracebox.nativecapture

object NativeRuntime {
    init {
        System.loadLibrary("tracebox_native")
    }

    external fun initializeEmergency(directory: String, processRole: Int): Boolean

    external fun startHandler(socketPath: String): Int

    external fun connectClient(socketPath: String, processRole: Int): Boolean

    external fun requestNonFatal(reason: Int, timeoutMillis: Int): Boolean

    external fun writeEmergencyForTest(signalNumber: Int): Boolean

    external fun crashForTest(kind: Int)
}
