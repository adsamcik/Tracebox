package dev.tracebox.phase0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.tracebox.nativecapture.NativeRuntime

class FaultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(ACTION_EXTRA)) {
            "early_abort" -> {
                NativeRuntime.initializeEmergency(
                    context.noBackupFilesDir.absolutePath,
                    PROCESS_ROLE_MAIN,
                )
                NativeRuntime.crashForTest(0)
            }
            "early_stack" -> {
                NativeRuntime.initializeEmergency(
                    context.noBackupFilesDir.absolutePath,
                    PROCESS_ROLE_MAIN,
                )
                NativeRuntime.stackOverflowForTest()
            }
            "early_recursive" -> {
                NativeRuntime.initializeEmergency(
                    context.noBackupFilesDir.absolutePath,
                    PROCESS_ROLE_MAIN,
                )
                NativeRuntime.recursiveSignalForTest()
            }
            "stall" -> {
                SystemClock.sleep(6_000)
                Log.i(TAG, "receiver_stall_completed=true")
            }
            "watchdog_stats" -> {
                val stats = Phase0WatchdogRegistry.watchdog?.stats()
                Log.i(
                    TAG,
                    "watchdog_stats posted=${stats?.postedGeneration} " +
                        "acked=${stats?.acknowledgedGeneration} eligible=${stats?.eligible}",
                )
            }
        }
    }

    private companion object {
        const val ACTION_EXTRA = "tracebox.action"
        const val PROCESS_ROLE_MAIN = 1
        const val TAG = "TraceboxPhase0"
    }
}
