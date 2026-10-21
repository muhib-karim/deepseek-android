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
import app.dsandroid.deepseek.DeepSeekAccountApp

class MainActivity : AppCompatActivity() {
    private var launchRequest by mutableStateOf<app.dsandroid.launch.LaunchRequest?>(null)
    private var sharedInput by mutableStateOf<SharedInput?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("shareConsumed") != true) sharedInput = SharedInput.read(intent, packageName)
        if (savedInstanceState?.getBoolean("launchConsumed") != true) launchRequest = app.dsandroid.core.launch.QuickAction.fromAction(intent?.action)?.let { app.dsandroid.launch.LaunchRequest(it) }
        // Full build: once the engine has a key and was the last route, launch goes straight to DeepSeek's harness.
        val builtIn = intent?.getBooleanExtra(app.dsandroid.flavor.EngineEntry.EXTRA_BUILT_IN, false) == true
        if (BuildConfig.DSH_ENGINE && !builtIn && savedInstanceState == null && sharedInput == null &&
            app.dsandroid.flavor.EngineEntry.shouldOpenEngine(this)) {
            app.dsandroid.flavor.EngineEntry.openEngine(this)
            finish()
            return
        }
        app.dsandroid.launch.QuickLaunch.publish(this)
        app.dsandroid.widget.AskDeepSeekWidget.refresh(this)
        enableEdgeToEdge()
        setContent { DeepSeekAccountApp(startBuiltIn = builtIn, sharedInput = sharedInput, sharedConsumed = { sharedInput = null }, launchRequest = launchRequest, launchConsumed = { launchRequest = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedInput = SharedInput.read(intent, packageName)
        launchRequest = app.dsandroid.core.launch.QuickAction.fromAction(intent.action)?.let { app.dsandroid.launch.LaunchRequest(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launchConsumed", launchRequest == null)
        outState.putBoolean("shareConsumed", sharedInput == null)
        super.onSaveInstanceState(outState)
    }
}
