package dev.tracebox.phase0

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.tracebox.nativecapture.NativeRuntime
import java.io.File

class HandlerService : Service() {
    private var startupBlocked = false

    override fun onCreate() {
        super.onCreate()
        if (!recordStartAttempt()) {
            startupBlocked = true
            Log.e(TAG, "handler_start_blocked=crash_loop")
            stopSelf()
            return
        }
        NativeRuntime.initializeEmergency(noBackupFilesDir.absolutePath, PROCESS_ROLE_HANDLER)
        Thread(
            {
                val result = NativeRuntime.startHandler(socketPath())
                Log.i(TAG, "handler_exit=$result")
            },
            "tracebox-native-handler",
        ).start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupBlocked) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CRASH -> NativeRuntime.crashForTest(0)
            ACTION_HANG -> NativeRuntime.hangForTest()
            ACTION_TERMINATE ->
                Handler(Looper.getMainLooper()).post {
                    NativeRuntime.terminateHandlerForTest()
                }
        }
        return START_NOT_STICKY
    }

    private fun recordStartAttempt(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val file = File(noBackupFilesDir, "handler-start-budget.txt")
        val recent =
            if (file.isFile) {
                file.readLines()
                    .mapNotNull(String::toLongOrNull)
                    .filter { timestamp -> now >= timestamp && now - timestamp < START_WINDOW_MILLIS }
            } else {
                emptyList()
            }
        if (recent.size >= MAX_STARTS_PER_WINDOW) {
            return false
        }
        file.writeText((recent + now).joinToString(separator = "\n", postfix = "\n"))
        return true
    }

    private fun socketPath(): String = "${noBackupFilesDir.absolutePath}/handler.sock"

    companion object {
        const val PROCESS_ROLE_HANDLER = 2
        const val TAG = "TraceboxPhase0"
        const val ACTION_CRASH = "dev.tracebox.phase0.CRASH_HANDLER"
        const val ACTION_HANG = "dev.tracebox.phase0.HANG_HANDLER"
        const val ACTION_TERMINATE = "dev.tracebox.phase0.TERMINATE_HANDLER"
        const val MAX_STARTS_PER_WINDOW = 3
        const val START_WINDOW_MILLIS = 600_000L
    }
}
