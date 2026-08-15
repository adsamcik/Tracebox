package dev.tracebox.phase0

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.tracebox.Tracebox
import dev.tracebox.TraceboxConfiguration
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.TraceboxHandle

/** A real second Tracebox process for UID-wide policy and handler coordination certification. */
class ProductionParticipantService : Service() {
    private var handle: TraceboxHandle? = null

    override fun onCreate() {
        super.onCreate()
        val runtime = Tracebox.install(
            applicationContext,
            TraceboxConfiguration.Builder()
                .setProcessRole(PROCESS_ROLE_PARTICIPANT)
                .setInitialProfile(DiagnosticsProfile.MINIMAL_CRASH)
                .setNativeCaptureEnabled(true)
                .setPersistRequestedProfile(false)
                .setDirectBootC0Enabled(true)
                .build(),
        )
        handle = runtime
        Thread(
            {
                val deadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT_MILLIS
                while (
                    !isProductionReady(runtime.readiness.value, runtime.health.value) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(READINESS_POLL_MILLIS)
                }
                Log.i(
                    TAG,
                    "production_participant_ready=" +
                        isProductionReady(runtime.readiness.value, runtime.health.value) +
                        " readiness=${runtime.readiness.value} health=${runtime.health.value}",
                )
            },
            "tracebox-production-participant-readiness",
        ).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handle?.close()
        handle = null
        super.onDestroy()
    }

    private companion object {
        const val PROCESS_ROLE_PARTICIPANT = 12
        const val READINESS_TIMEOUT_MILLIS = 20_000L
        const val READINESS_POLL_MILLIS = 25L
        const val TAG = "TraceboxLab"
    }
}
