package dev.tracebox.nativecapture

object NativeRuntime {
    init {
        System.loadLibrary("tracebox_crashpad")
    }

    external fun initializeEmergency(directory: String, processRole: Int): Boolean

    external fun startHandler(socketPath: String): Int

    external fun connectClient(socketPath: String, processRole: Int): Boolean

    external fun requestNonFatal(reason: Int, timeoutMillis: Int): Boolean

    external fun requestSeededNonFatalForTest(): Boolean

    external fun isHandlerAlive(): Boolean

    external fun writeEmergencyForTest(signalNumber: Int): Boolean

    external fun writeEmergencyFaultForTest(mode: Int): Boolean

    external fun crashForTest(kind: Int)

    external fun stackOverflowForTest()

    external fun hangForTest()

    external fun recursiveSignalForTest()

    external fun terminateHandlerForTest()
}
