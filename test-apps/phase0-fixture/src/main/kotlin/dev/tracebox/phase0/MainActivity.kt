package dev.tracebox.phase0

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.tracebox.anr.AnrWatchdog
import dev.tracebox.anr.NonFatalRequester
import dev.tracebox.nativecapture.NativeRuntime

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var watchdog: AnrWatchdog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeRuntime.initializeEmergency(noBackupFilesDir.absolutePath, PROCESS_ROLE_MAIN)
        startService(Intent(this, HandlerService::class.java))
        startService(Intent(this, WorkerService::class.java))

        watchdog = AnrWatchdog(
            requester = NonFatalRequester {
                NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, it)
            },
            onCandidate = { candidate ->
                runOnUiThread {
                    status.text =
                        "ANR candidate ${candidate.delayedMillis}ms frames=${candidate.mainFrames.size} " +
                            "snapshot=${candidate.nonFatalRequested}"
                }
            },
        ).also {
            it.start()
            it.setEligible(true)
        }

        status = TextView(this).apply {
            text = "Tracebox Phase 0 initialized"
            textSize = 18f
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 32)
            addView(status)
            addView(button("Write emergency") {
                status.text = "Emergency write=${NativeRuntime.writeEmergencyForTest(6)}"
            })
            addView(button("Stall main for 6 seconds") {
                SystemClock.sleep(6_000)
                status.text = "Main stall completed"
            })
            addView(button("Native SIGABRT") { NativeRuntime.crashForTest(0) })
            addView(button("Native SIGSEGV") { NativeRuntime.crashForTest(1) })
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (::watchdog.isInitialized) {
            watchdog.setEligible(true)
        }
    }

    override fun onPause() {
        if (::watchdog.isInitialized) {
            watchdog.setEligible(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        watchdog.close()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private companion object {
        const val PROCESS_ROLE_MAIN = 1
        const val REASON_ANR_CANDIDATE = 1
    }
}
