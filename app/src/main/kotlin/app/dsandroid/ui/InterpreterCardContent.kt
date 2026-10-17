package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.dsandroid.R
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.llm.LlmJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Code and bounded results stay beside the existing timeline status and details. */
@Composable
fun InterpreterCardContent(step: TurnStep.ToolCall) {
    val args = remember(step.argsJson) { runCatching { LlmJson.parseToJsonElement(step.argsJson) as? JsonObject }.getOrNull() }
    val code = (args?.get("code") as? JsonPrimitive)?.content.orEmpty()
    val output = remember(step.resultPreview) { runCatching { LlmJson.parseToJsonElement(step.resultPreview) as? JsonObject }.getOrNull() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.interpreter_code), style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(code, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (output != null) {
            listOf("stdout" to R.string.interpreter_stdout, "stderr" to R.string.interpreter_stderr,
                "value" to R.string.interpreter_value).forEach { (key, label) ->
                val value = (output[key] as? JsonPrimitive)?.content.orEmpty()
                if (value.isNotEmpty()) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(value, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if ((output["timedOut"] as? JsonPrimitive)?.content == "true") Text(stringResource(R.string.interpreter_timeout), color = MaterialTheme.colorScheme.error)
            if ((output["truncated"] as? JsonPrimitive)?.content == "true") Text(stringResource(R.string.interpreter_truncated), style = MaterialTheme.typography.bodySmall)
        } else if (step.resultPreview.isNotEmpty()) SelectionContainer {
            Text(step.resultPreview, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
