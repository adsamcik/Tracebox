package dev.tracebox.phase0

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.tracebox.nativecapture.NativeRuntime

class HandlerService : Service() {
    override fun onCreate() {
        super.onCreate()
        NativeRuntime.initializeEmergency(noBackupFilesDir.absolutePath, PROCESS_ROLE_HANDLER)
        Thread(
            { NativeRuntime.startHandler(socketPath()) },
            "tracebox-native-handler",
        ).start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun socketPath(): String = "${noBackupFilesDir.absolutePath}/handler.sock"

    private companion object {
        const val PROCESS_ROLE_HANDLER = 2
    }
}
