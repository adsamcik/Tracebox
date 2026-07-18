package dev.tracebox.phase0.benchmark

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.tracebox.nativecapture.NativeRuntime

class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = System.nanoTime()
        val initialized = NativeRuntime.initializeEmergency(noBackupFilesDir.absolutePath, 10)
        val elapsed = System.nanoTime() - started
        setContentView(TextView(this).apply {
            text = "initialized=$initialized elapsedNs=$elapsed"
        })
    }
}
