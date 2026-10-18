package app.dsandroid

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.dsandroid.attachments.SharedInput
import app.dsandroid.ui.DsAndroidApp

class MainActivity : AppCompatActivity() {
    private var sharedInput by mutableStateOf<SharedInput?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("shareConsumed") != true) sharedInput = SharedInput.read(intent, packageName)
        enableEdgeToEdge()
        setContent { DsAndroidApp(sharedInput = sharedInput, sharedConsumed = { sharedInput = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedInput = SharedInput.read(intent, packageName)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("shareConsumed", sharedInput == null)
        super.onSaveInstanceState(outState)
    }
}
