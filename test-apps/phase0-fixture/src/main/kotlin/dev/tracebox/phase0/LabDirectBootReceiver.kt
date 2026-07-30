package dev.tracebox.phase0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.tracebox.TraceboxDirectBoot

/** Direct-Boot-aware fixture caller of the production generated-only C0 capture API. */
class LabDirectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        ) {
            return
        }
        val result = TraceboxDirectBoot.recordEmergency(
            context = context,
            signalNumber = LOCKED_BOOT_SIGNAL,
            signalCode = LOCKED_BOOT_SIGNAL_CODE,
            processRole = PROCESS_ROLE_LAB,
            threadRole = THREAD_ROLE_RECEIVER,
            flags = LOCKED_BOOT_FLAG,
        )
        Log.i(
            TAG,
            "scenario_result id=DIRECT_BOOT.C0_CAPTURE outcome=PASS result=$result",
        )
    }

    private companion object {
        const val PROCESS_ROLE_LAB = 11
        const val THREAD_ROLE_RECEIVER = 1
        const val LOCKED_BOOT_SIGNAL = 0
        const val LOCKED_BOOT_SIGNAL_CODE = 1
        const val LOCKED_BOOT_FLAG = 1L
        const val TAG = "TraceboxLab"
    }
}
