package dev.tracebox.phase0

import android.os.Process
import android.system.Os
import android.system.OsConstants

/**
 * Destructive controls owned entirely by the fixture APK.
 *
 * Production Tracebox deliberately exposes no crash, process-stop, or handler-termination API.
 * Sending real Linux signals here still exercises the production signal handlers installed in
 * the fixture process without leaking a fault-injection surface into any Tracebox AAR.
 */
object LabNativeFaults {
    fun abortProcess(): Nothing = fatalSignal(OsConstants.SIGABRT)

    fun segvProcess(): Nothing = fatalSignal(OsConstants.SIGSEGV)

    fun terminateProcess(): Nothing = fatalSignal(OsConstants.SIGTERM)

    fun stopProcess() {
        Os.kill(Process.myPid(), OsConstants.SIGSTOP)
    }

    fun overflowStack(): Nothing {
        overflowSink = recurse(1)
        error("Stack recursion unexpectedly returned")
    }

    fun recursiveSignal(): Nothing {
        Thread(
            {
                repeat(RECURSIVE_SIGNAL_ATTEMPTS) {
                    Os.kill(Process.myPid(), OsConstants.SIGABRT)
                }
            },
            "tracebox-lab-recursive-signal",
        ).start()
        return fatalSignal(OsConstants.SIGABRT)
    }

    private fun fatalSignal(signal: Int): Nothing {
        Os.kill(Process.myPid(), signal)
        error("Signal $signal unexpectedly returned")
    }

    private fun recurse(depth: Int): Int {
        val child = recurse(depth + 1)
        val value = child xor depth
        overflowSink = value
        return value
    }

    @Volatile
    private var overflowSink = 0

    private const val RECURSIVE_SIGNAL_ATTEMPTS = 4
}
