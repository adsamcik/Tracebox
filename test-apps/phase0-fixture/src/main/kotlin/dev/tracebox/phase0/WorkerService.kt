package dev.tracebox.phase0

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.tracebox.nativecapture.NativeRuntime

class WorkerService : Service() {
    override fun onCreate() {
        super.onCreate()
        val initialized = LabNativeIdentity.initialize(this, PROCESS_ROLE_WORKER)
        val connected =
            initialized &&
                LabNativeIdentity.connect(
                    this,
                    "${noBackupFilesDir.absolutePath}/handler.sock",
                    PROCESS_ROLE_WORKER,
                )
        Log.i(TAG, "worker_connected=$connected")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_NONFATAL) {
            Thread {
                val captured = NativeRuntime.requestNonFatal(REASON_WORKER_TEST, 2_000)
                Log.i(TAG, "worker_nonfatal_captured=$captured")
            }.start()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_NONFATAL = "dev.tracebox.phase0.WORKER_NONFATAL"
        const val PROCESS_ROLE_WORKER = 3
        const val REASON_WORKER_TEST = 2
        const val TAG = "TraceboxPhase0"
    }
}
