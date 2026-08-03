package dev.tracebox.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dev.tracebox.Tracebox

/** Ready-made local diagnostics activity for hosts that do not need a custom settings screen. */
class TraceboxDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handle = Tracebox.current()
        if (handle == null) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                TraceboxDiagnosticsScreen(handle = handle)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, TraceboxDiagnosticsActivity::class.java)
    }
}
