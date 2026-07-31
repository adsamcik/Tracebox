package dev.tracebox.phase0

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.tracebox.api.DeleteRequest

/**
 * Production-only fixture lane.
 *
 * This process installs and exercises the public Tracebox runtime. Legacy phase-0 native identity,
 * handler-service, worker-service, and watchdog controls live in [LegacyPhase0Activity]'s isolated
 * process and must never be initialized here.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Tracebox production runtime initialized"
            textSize = 18f
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(32, 64, 32, 32)
                addView(status)
                addView(button("Production SIGABRT") {
                    LabRuntime.install(this@MainActivity)
                    LabNativeFaults.abortProcess()
                })
                addView(button("Production SIGSEGV") {
                    LabRuntime.install(this@MainActivity)
                    LabNativeFaults.segvProcess()
                })
            },
        )
        LabRuntime.install(this)
        if (intent.getBooleanExtra(START_PARTICIPANT_EXTRA, false)) {
            startService(Intent(this, ProductionParticipantService::class.java))
        }
        Log.i(LAB_TAG, "production_lane_started=true")
        executeAutomation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        executeAutomation(intent)
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
            Log.i(LAB_TAG, "scenario_start id=${scenario.stableId} action=$action lane=production")
        }
        when (action) {
            "readiness" -> LabRuntime.reportReadiness(this)
            "abort", "emergency" -> {
                LabRuntime.install(this)
                LabNativeFaults.abortProcess()
            }
            "segv" -> {
                LabRuntime.install(this)
                LabNativeFaults.segvProcess()
            }
            "policy_barrier" -> LabRuntime.runPolicyBarrier(this)
            "handler_conflict" -> installConflictingHandlerAndCrash()
            "jvm_uncaught" -> crashJvmThread()
            "rust_panic" -> LabRuntime.recordRustPanicThenAbort(this)
            "oom" -> crashWithOutOfMemory()
            "storage_pressure" -> LabRuntime.createStoragePressure(this)
            "delete_all" ->
                LabRuntime.delete(this, DeleteRequest.ALL_TRACEBOX_DATA, "DELETE.ALL_RESTART")
            "package_disclosure" -> LabRuntime.preparePackage(this)
            "network_control" -> runNetworkControl(intent)
            "r8_frames" ->
                Log.i(
                    LAB_TAG,
                    "scenario_result id=SYMBOL.R8_RETRACE outcome=PASS " +
                        "value=${R8Scenario.optimizedFrame(7)}",
                )
        }
    }

    private fun crashJvmThread() {
        LabRuntime.install(this)
        Thread(
            { throw LabManagedFault() },
            "tracebox-lab-managed-fault",
        ).start()
    }

    private fun installConflictingHandlerAndCrash() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.i(LAB_TAG, "scenario_result id=HANDLER.CONFLICT outcome=PASS previous_invoked=true")
            previous?.uncaughtException(thread, throwable)
        }
        LabRuntime.install(this)
        Thread(
            { throw LabHandlerConflictFault() },
            "tracebox-lab-handler-conflict",
        ).start()
    }

    private fun crashWithOutOfMemory() {
        LabRuntime.install(this)
        Thread(
            {
                val retained = ArrayList<ByteArray>()
                while (true) {
                    retained += ByteArray(ONE_MIB)
                }
            },
            "tracebox-lab-oom",
        ).start()
    }

    private fun runNetworkControl(intent: Intent) {
        val host = intent.getStringExtra(PROBE_HOST_EXTRA) ?: DEFAULT_PROBE_HOST
        val port = intent.getIntExtra(PROBE_PORT_EXTRA, DEFAULT_PROBE_PORT)
        Thread {
            val result = HostNetworkControl.probe(host, port)
            Log.i(
                LAB_TAG,
                "scenario_result id=${intent.getStringExtra(SCENARIO_EXTRA)} outcome=PASS " +
                    "capability=${result.capability} dns=${result.dnsAttempted} " +
                    "connect=${result.connectAttempted} success=${result.connectSucceeded}",
            )
        }.start()
    }

    private companion object {
        const val ACTION_EXTRA = "tracebox.action"
        const val SCENARIO_EXTRA = "tracebox.scenario_id"
        const val PROBE_HOST_EXTRA = "tracebox.probe_host"
        const val PROBE_PORT_EXTRA = "tracebox.probe_port"
        const val START_PARTICIPANT_EXTRA = "tracebox.start_participant"
        const val ONE_MIB = 1024 * 1024
        const val DEFAULT_PROBE_HOST = "10.0.2.2"
        const val DEFAULT_PROBE_PORT = 9
        const val LAB_TAG = "TraceboxLab"
    }
}

private class LabManagedFault : RuntimeException()

private class LabHandlerConflictFault : RuntimeException()
