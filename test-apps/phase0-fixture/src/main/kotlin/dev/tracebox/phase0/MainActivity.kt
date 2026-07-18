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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var watchdog: AnrWatchdog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installStarted = SystemClock.elapsedRealtimeNanos()
        val emergencyReady =
            NativeRuntime.initializeEmergency(
                noBackupFilesDir.absolutePath,
                PROCESS_ROLE_MAIN,
            )
        Log.i(
            TAG,
            "install_volatile_us=${(SystemClock.elapsedRealtimeNanos() - installStarted) / 1_000} " +
                "emergency_ready=$emergencyReady",
        )
        when (intent.getStringExtra(ACTION_EXTRA)) {
            "early_abort" -> NativeRuntime.crashForTest(0)
            "early_stack" -> NativeRuntime.stackOverflowForTest()
        }
        startService(Intent(this, HandlerService::class.java))
        startService(Intent(this, WorkerService::class.java))
        Thread(
            {
                val connected =
                    NativeRuntime.connectClient(
                        "${noBackupFilesDir.absolutePath}/handler.sock",
                        PROCESS_ROLE_MAIN,
                    )
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
                        "ANR candidate ${candidate.delayedMillis}ms frames=${candidate.mainFrames.size} " +
                            "snapshot=${candidate.nonFatalRequested}"
                }
            },
        ).also {
            Phase0WatchdogRegistry.watchdog = it
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
            addView(button("Nonfatal snapshot") {
                Thread {
                    val captured =
                        NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
                    Log.i(TAG, "nonfatal_captured=$captured")
                }.start()
            })
            addView(button("Native SIGABRT") { NativeRuntime.crashForTest(0) })
            addView(button("Native SIGSEGV") { NativeRuntime.crashForTest(1) })
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
        Phase0WatchdogRegistry.watchdog = null
        watchdog.close()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun executeAutomation(intent: Intent) {
        when (intent.getStringExtra(ACTION_EXTRA)) {
            "emergency" -> {
                val result = NativeRuntime.writeEmergencyForTest(6)
                Log.i(TAG, "emergency_written=$result")
            }
            "emergency_short" -> {
                val result = NativeRuntime.writeEmergencyFaultForTest(1)
                Log.i(TAG, "emergency_short_result=$result")
            }
            "emergency_failed" -> {
                val result = NativeRuntime.writeEmergencyFaultForTest(2)
                Log.i(TAG, "emergency_failed_result=$result")
            }
            "nonfatal" -> Thread {
                val result = NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
                Log.i(TAG, "nonfatal_captured=$result")
            }.start()
            "seeded" -> Thread {
                val result = NativeRuntime.requestSeededNonFatalForTest()
                Log.i(TAG, "seeded_nonfatal_captured=$result")
            }.start()
            "stall" -> {
                SystemClock.sleep(6_000)
                Log.i(TAG, "stall_completed=true")
            }
            "abort" -> NativeRuntime.crashForTest(0)
            "segv" -> NativeRuntime.crashForTest(1)
            "alive" -> Log.i(TAG, "handler_alive=${NativeRuntime.isHandlerAlive()}")
            "reconnect" -> {
                startService(Intent(this, HandlerService::class.java))
                Thread {
                val connected =
                    NativeRuntime.connectClient(
                        "${noBackupFilesDir.absolutePath}/handler.sock",
                        PROCESS_ROLE_MAIN,
                    )
                Log.i(TAG, "main_reconnected=$connected")
                }.start()
            }
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
        }
    }

    private fun measureNonFatalPause() {
        val running = AtomicBoolean(true)
        val last = AtomicLong(SystemClock.elapsedRealtimeNanos())
        val maximumGap = AtomicLong()
        val sampler =
            object : Runnable {
                override fun run() {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val gap = now - last.getAndSet(now)
                    maximumGap.accumulateAndGet(gap, ::maxOf)
                    if (running.get()) {
                        status.post(this)
                    }
                }
            }
        status.post(sampler)
        Thread {
            SystemClock.sleep(100)
            val started = SystemClock.elapsedRealtimeNanos()
            val captured = NativeRuntime.requestNonFatal(REASON_ANR_CANDIDATE, 2_000)
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            running.set(false)
            Log.i(
                TAG,
                "nonfatal_measure captured=$captured elapsed_us=${elapsed / 1_000} " +
                    "main_pause_max_us=${maximumGap.get() / 1_000}",
            )
        }.start()
    }

    private companion object {
        const val ACTION_EXTRA = "tracebox.action"
        const val PROCESS_ROLE_MAIN = 1
        const val REASON_ANR_CANDIDATE = 1
        const val TAG = "TraceboxPhase0"
    }
}
