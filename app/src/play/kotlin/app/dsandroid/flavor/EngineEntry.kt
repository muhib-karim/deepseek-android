package app.dsandroid.flavor

import android.content.Context
import androidx.compose.runtime.Composable

/** Play build: no bundled engine (it needs targetSdk 28, see app/build.gradle.kts); the v1.2.0 app is unchanged. */
object EngineEntry {
    const val EXTRA_BUILT_IN = "built_in_engine"
    const val available = false

    fun shouldOpenEngine(context: Context): Boolean = false

    fun openEngine(context: Context) = Unit

    fun chooseGoogle(context: Context) = Unit

    fun builtInAgentEnabled(context: Context): Boolean = true

    fun setBuiltInAgentEnabled(context: Context, enabled: Boolean) = Unit
}

@Composable
fun EngineKeyForm(compact: Boolean = false, onStarted: () -> Unit) = Unit
