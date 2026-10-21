package app.dsandroid.flavor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dsandroid.engine.DshCredentials
import app.dsandroid.engine.Engine
import app.dsandroid.engine.EngineActivity

/** Full build: the official DeepSeek engine (DeepSeek's harness on the bundled runtime) is the primary route. */
object EngineEntry {
    const val EXTRA_BUILT_IN = "built_in_engine"
    const val available = true
    private const val BUILT_IN = "built_in_agent"

    /** Launch goes straight to the engine once it has a key and was the last route used. */
    fun shouldOpenEngine(context: Context): Boolean = Engine.route(context) == Engine.ROUTE_ENGINE && Engine.hasKey(context)

    fun openEngine(context: Context) = EngineActivity.open(context)

    /** The web-account route was chosen: later launches open it instead of the engine. */
    fun chooseGoogle(context: Context) = Engine.setRoute(context, Engine.ROUTE_GOOGLE)

    /** overflow > Advanced > Built-in engine (fallback): this app's own API-key agent, off by default. */
    fun builtInAgentEnabled(context: Context): Boolean =
        context.getSharedPreferences("engine", Context.MODE_PRIVATE).getBoolean(BUILT_IN, false)

    fun setBuiltInAgentEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("engine", Context.MODE_PRIVATE).edit().putBoolean(BUILT_IN, enabled).apply()
    }
}

const val KEYS_URL = "https://platform.deepseek.com/api_keys"

/**
 * "Use API key": one field and one Start button. The key goes only into the harness's own credentials store
 * (app-private, mode 600); requests then go from the harness on this phone to api.deepseek.com.
 */
@Composable
fun EngineKeyForm(compact: Boolean = false, onStarted: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun start() {
        val problem = DshCredentials.problem(key)
        if (problem != null) {
            error = problem
            return
        }
        try {
            Engine.setKey(context, key)
            Engine.setRoute(context, Engine.ROUTE_ENGINE)
            key = ""
            error = null
            onStarted()
        } catch (e: Exception) {
            error = "The key could not be saved: ${e.message}"
        }
    }
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!compact) Text("Use API key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(key, { key = it.take(512); error = null }, Modifier.fillMaxWidth().testTag("engine-key-field"),
            label = { Text("DeepSeek API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = error != null, supportingText = error?.let { { Text(it, modifier = Modifier.testTag("engine-key-error")) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { start() }))
        Button(onClick = { start() }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("engine-start"),
            shape = RoundedCornerShape(24.dp)) { Text(if (compact) "Save key" else "Start", fontWeight = FontWeight.Medium) }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEYS_URL)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        }, modifier = Modifier.testTag("engine-get-key")) { Text("Get a key") }
    }
}
