package dev.tracebox.phase0

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.tracebox.nativecapture.NativeRuntime

class WorkerService : Service() {
    override fun onCreate() {
        super.onCreate()
        NativeRuntime.initializeEmergency(noBackupFilesDir.absolutePath, PROCESS_ROLE_WORKER)
        NativeRuntime.connectClient("${noBackupFilesDir.absolutePath}/handler.sock", PROCESS_ROLE_WORKER)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val PROCESS_ROLE_WORKER = 3
    }
}
