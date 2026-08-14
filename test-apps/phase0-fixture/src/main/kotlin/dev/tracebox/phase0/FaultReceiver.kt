package dev.tracebox.phase0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class FaultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(SCENARIO_EXTRA)?.let { stableId ->
            Log.i(LAB_TAG, "scenario_start id=$stableId action=${intent.getStringExtra(ACTION_EXTRA)}")
        }
        when (intent.getStringExtra(ACTION_EXTRA)) {
            "early_abort" -> {
                LabNativeIdentity.initialize(context, PROCESS_ROLE_MAIN)
                LabNativeFaults.abortProcess()
            }
            "early_stack" -> {
                LabNativeIdentity.initialize(context, PROCESS_ROLE_MAIN)
                LabNativeFaults.overflowStack()
            }
            "early_recursive" -> {
                LabNativeIdentity.initialize(context, PROCESS_ROLE_MAIN)
                LabNativeFaults.recursiveSignal()
            }
            "fatal" -> LabNativeFaults.abortProcess()
            "stall" -> {
                SystemClock.sleep(6_000)
                Log.i(TAG, "receiver_stall_completed=true")
            }
            "watchdog_stats" -> {
                val stats = Phase0WatchdogRegistry.watchdog?.stats()
                Log.i(
                    TAG,
                    "watchdog_stats posted=${stats?.postedGeneration} " +
                        "acked=${stats?.acknowledgedGeneration} eligible=${stats?.eligible} " +
                        "heartbeat_p99_ns=${stats?.heartbeatP99Nanos}",
                )
            }
            "reset_watchdog_stats" ->
                Phase0WatchdogRegistry.watchdog?.resetMeasurementStats()
        }
    }

    private companion object {
        const val ACTION_EXTRA = "tracebox.action"
        const val SCENARIO_EXTRA = "tracebox.scenario_id"
        const val PROCESS_ROLE_MAIN = 1
        const val TAG = "TraceboxPhase0"
        const val LAB_TAG = "TraceboxLab"
    }
}
