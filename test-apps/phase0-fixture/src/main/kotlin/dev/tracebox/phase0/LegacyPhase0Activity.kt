package dev.tracebox.phase0

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.tracebox.anr.AnrWatchdog
import dev.tracebox.anr.NonFatalRequester
import dev.tracebox.nativecapture.NativeRuntime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated compatibility lane for the original phase-0 handler/watchdog qualification.
 *
 * It is intentionally not the launcher and runs in `:phase0_main`, so none of these legacy
 * controls can share process-global native state with the production Tracebox fixture lane.
 */
class LegacyPhase0Activity : Activity() {
    private lateinit var status: TextView
    private lateinit var watchdog: AnrWatchdog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installStarted = SystemClock.elapsedRealtimeNanos()
        val emergencyReady = LabNativeIdentity.initialize(this, PROCESS_ROLE_MAIN)
        Log.i(
            TAG,
            "install_volatile_us=${(SystemClock.elapsedRealtimeNanos() - installStarted) / 1_000} " +
                "emergency_ready=$emergencyReady",
        )
        when (intent.getStringExtra(ACTION_EXTRA)) {
            "early_abort" -> LabNativeFaults.abortProcess()
            "early_stack" -> LabNativeFaults.overflowStack()
            "handler_startup_fatal" -> {
                startService(
                    Intent(this, HandlerService::class.java)
                        .setAction(HandlerService.ACTION_CRASH),
                )
                return
            }
        }
        startService(Intent(this, HandlerService::class.java))
        startService(Intent(this, WorkerService::class.java))
        Thread(
            {
                val socketPath = "${noBackupFilesDir.absolutePath}/handler.sock"
                var connected = false
                for (attempt in 0 until INITIAL_CONNECT_ATTEMPTS) {
                    connected = LabNativeIdentity.connect(this, socketPath, PROCESS_ROLE_MAIN)
                    if (connected || attempt + 1 == INITIAL_CONNECT_ATTEMPTS) break
                    SystemClock.sleep(INITIAL_CONNECT_RETRY_DELAY_MILLIS)
                }
                Log.i(
                    TAG,
                    "main_connected=$connected " +
                        "durable_ms=${(SystemClock.elapsedRealtimeNanos() - installStarted) / 1_000_000}",
                )
            },
            "tracebox-main-client-connect",
        ).start()

        watchdog = AnrWatchdog(
            requester = NonFatalRequester {
                NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, it)
            },
            onCandidate = { candidate ->
                Log.i(
                    TAG,
                    "anr_candidate delay_ms=${candidate.delayedMillis} " +
                        "frames=${candidate.mainFrames.size} " +
                        "snapshot=${candidate.nonFatalRequested}",
                )
                runOnUiThread {
                    status.text =
                        "ANR candidate ${candidate.delayedMillis}ms " +
                            "frames=${candidate.mainFrames.size} " +
                            "snapshot=${candidate.nonFatalRequested}"
                }
            },
        ).also {
            Phase0WatchdogRegistry.watchdog = it
            it.start()
            it.setEligible(true)
        }

        status = TextView(this).apply {
            text = "Tracebox legacy phase-0 lane initialized"
            textSize = 18f
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 32)
            addView(status)
            addView(button("Legacy emergency SIGABRT") { LabNativeFaults.abortProcess() })
            addView(button("Stall legacy main for 6 seconds") {
                SystemClock.sleep(6_000)
                status.text = "Main stall completed"
            })
            addView(button("Legacy nonfatal snapshot") {
                Thread {
                    val captured =
                        NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
                    Log.i(TAG, "nonfatal_captured=$captured")
                }.start()
            })
        }
        setContentView(layout)
        executeAutomation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        executeAutomation(intent)
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
        if (::watchdog.isInitialized) {
            Phase0WatchdogRegistry.watchdog = null
            watchdog.close()
        }
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun executeAutomation(intent: Intent) {
        val scenario = LabScenario.fromId(intent.getStringExtra(SCENARIO_EXTRA))
        val action = intent.getStringExtra(ACTION_EXTRA)
        if (scenario != null) {
            Log.i(LAB_TAG, "scenario_start id=${scenario.stableId} action=$action lane=legacy")
        }
        when (action) {
            "nonfatal" -> Thread {
                val started = SystemClock.elapsedRealtimeNanos()
                val result = NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                Log.i(TAG, "nonfatal_captured=$result elapsed_us=${elapsed / 1_000}")
            }.start()
            "seeded" -> Thread {
                val result = NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
                Log.i(TAG, "seeded_nonfatal_captured=$result")
            }.start()
            "stall" -> {
                SystemClock.sleep(6_000)
                Log.i(TAG, "stall_completed=true")
            }
            "alive" -> Log.i(TAG, "handler_alive=${NativeRuntime.isHandlerAlive()}")
            "reconnect" -> {
                startService(Intent(this, HandlerService::class.java))
                Thread {
                    val connected =
                        LabNativeIdentity.connect(
                            this,
                            "${noBackupFilesDir.absolutePath}/handler.sock",
                            PROCESS_ROLE_MAIN,
                        )
                    Log.i(TAG, "main_reconnected=$connected")
                }.start()
            }
            "connect_hung_handler" -> Thread {
                val started = SystemClock.elapsedRealtimeNanos()
                val connected =
                    LabNativeIdentity.connect(
                        this,
                        "${noBackupFilesDir.absolutePath}/handler.sock",
                        PROCESS_ROLE_MAIN,
                    )
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                Log.i(
                    TAG,
                    "hung_registration_connected=$connected elapsed_us=${elapsed / 1_000}",
                )
            }.start()
            "crash_handler" ->
                startService(
                    Intent(this, HandlerService::class.java)
                        .setAction(HandlerService.ACTION_CRASH),
                )
            "hang_handler" ->
                startService(
                    Intent(this, HandlerService::class.java)
                        .setAction(HandlerService.ACTION_HANG),
                )
            "terminate_handler" ->
                startService(
                    Intent(this, HandlerService::class.java)
                        .setAction(HandlerService.ACTION_TERMINATE),
                )
            "measure_nonfatal" -> measureNonFatalPause()
            "worker_nonfatal" ->
                startService(
                    Intent(this, WorkerService::class.java)
                        .setAction(WorkerService.ACTION_NONFATAL),
                )
            "responsive" -> verifyResponsiveWatchdog()
            "hang_handler_then_nonfatal" -> {
                startService(
                    Intent(this, HandlerService::class.java)
                        .setAction(HandlerService.ACTION_HANG),
                )
                Thread {
                    SystemClock.sleep(250)
                    val captured = NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
                    Log.i(
                        LAB_TAG,
                        "scenario_result id=ANR.TIMEOUT outcome=PASS captured=$captured",
                    )
                }.start()
            }
        }
    }

    private fun verifyResponsiveWatchdog() {
        Thread {
            SystemClock.sleep(RESPONSIVE_WINDOW_MILLIS)
            val stats = Phase0WatchdogRegistry.watchdog?.stats()
            Log.i(
                LAB_TAG,
                "scenario_result id=ANR.RESPONSIVE outcome=PASS " +
                    "posted=${stats?.postedGeneration} acked=${stats?.acknowledgedGeneration}",
            )
        }.start()
    }

    private fun measureNonFatalPause() {
        val done = AtomicBoolean(false)
        val capturedResult = AtomicBoolean(false)
        val elapsedNanos = AtomicLong()
        Thread {
            SystemClock.sleep(100)
            val started = SystemClock.elapsedRealtimeNanos()
            capturedResult.set(
                NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000),
            )
            elapsedNanos.set(SystemClock.elapsedRealtimeNanos() - started)
            done.set(true)
        }.start()

        var previous = SystemClock.elapsedRealtimeNanos()
        var maximumGap = 0L
        val deadline = previous + 3_000_000_000L
        while (!done.get() && previous < deadline) {
            val now = SystemClock.elapsedRealtimeNanos()
            maximumGap = maxOf(maximumGap, now - previous)
            previous = now
        }
        Log.i(
            TAG,
            "nonfatal_measure captured=${capturedResult.get()} " +
                "elapsed_us=${elapsedNanos.get() / 1_000} " +
                "main_pause_max_us=${maximumGap / 1_000}",
        )
    }

    private companion object {
        const val ACTION_EXTRA = "tracebox.action"
        const val SCENARIO_EXTRA = "tracebox.scenario_id"
        const val PROCESS_ROLE_MAIN = 1
        const val REASON_ANR_CANDIDATE = 1
        const val INITIAL_CONNECT_ATTEMPTS = 4
        const val INITIAL_CONNECT_RETRY_DELAY_MILLIS = 250L
        const val RESPONSIVE_WINDOW_MILLIS = 4_000L
        const val TAG = "TraceboxPhase0"
        const val LAB_TAG = "TraceboxLab"
    }
}
