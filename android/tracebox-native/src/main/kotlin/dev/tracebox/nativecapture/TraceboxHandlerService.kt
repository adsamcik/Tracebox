package dev.tracebox.nativecapture

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * The production capture-only handler process. It has no binder recording API, ordinary writer,
 * periodic work, or ANR watchdog; native bounded IPC is its sole client transport.
 */
class TraceboxHandlerService : Service() {
    override fun onCreate() {
        super.onCreate()
        val directory = noBackupFilesDir
        if (!NativeRuntime.initializeEmergency(directory.absolutePath, PROCESS_ROLE_HANDLER)) {
            stopSelf()
            return
        }
        Thread(
            { NativeRuntime.startHandler(File(directory, SOCKET_NAME).absolutePath) },
            "tracebox-native-handler",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        const val PROCESS_ROLE_HANDLER = 2
        const val SOCKET_NAME = "tracebox-handler.sock"
    }
}
