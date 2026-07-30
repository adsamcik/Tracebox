package dev.tracebox.phase0.benchmark

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.tracebox.nativecapture.NativeRuntime

class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = System.nanoTime()
        val identity =
            NativeRuntime.allocateIdentity(
                noBackupFilesDir.toPath().resolve("identity-lifecycle-v1.log").toString(),
                PROCESS_IDENTITY_KIND,
            )
        val initialized =
            identity != null &&
                NativeRuntime.initializeEmergency(
                    noBackupFilesDir.absolutePath,
                    PROCESS_ROLE,
                    identity,
                    POLICY_EPOCH,
                ) &&
                NativeRuntime.updatePolicy(
                    policyEpoch = POLICY_EPOCH,
                    disabled = false,
                    denyMask = 0,
                )
        val elapsed = System.nanoTime() - started
        setContentView(TextView(this).apply {
            text = "initialized=$initialized elapsedNs=$elapsed"
        })
    }

    private companion object {
        const val PROCESS_ROLE = 10
        const val PROCESS_IDENTITY_KIND = 1
        const val POLICY_EPOCH = 1L
    }
}
