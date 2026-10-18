package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dsandroid.R
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: ChatViewModel, back: () -> Unit) {
    val browser by vm.browser.state.collectAsState()
    val chat by vm.state.collectAsState()
    var address by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    LaunchedEffect(browser.viewVersion) { page = ""; entry = ""; target = "" }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.browser_agent)) }, navigationIcon = {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.browser_notice), style = MaterialTheme.typography.bodySmall)
                Text(browser.url, maxLines = 2)
                Row {
                    TextButton(onClick = { vm.stop(); vm.browser.takeOver(!browser.takeover) }) {
                        Text(stringResource(if (browser.takeover) R.string.browser_return else R.string.browser_takeover))
                    }
                    TextButton(onClick = { vm.stop(); vm.browser.close() }) { Text(stringResource(R.string.browser_clear)) }
                    TextButton(onClick = { vm.stop() }, enabled = chat.running) { Text(stringResource(R.string.stop_generating)) }
                }
                if (browser.takeover) {
                    OutlinedTextField(address, { address = it.take(2048) }, label = { Text(stringResource(R.string.browser_url)) }, singleLine = true)
                    TextButton(onClick = { vm.browserUserAction("open_url", buildJsonObject { put("url", address) }) { page = it } }, enabled = !chat.running) { Text(stringResource(R.string.browser_open)) }
                    TextButton(onClick = { vm.browserUserAction("read_page", buildJsonObject {}) { page = it } }, enabled = browser.url.isNotEmpty() && !chat.running) { Text(stringResource(R.string.browser_read)) }
                    Row {
                        TextButton(onClick = { vm.browserUserAction("back", buildJsonObject {}) { page = it } }, enabled = browser.url.isNotEmpty() && !chat.running) { Text(stringResource(R.string.back)) }
                        listOf("up" to R.string.browser_scroll_up, "down" to R.string.browser_scroll_down).forEach { (direction, label) ->
                            TextButton(onClick = { vm.browserUserAction("scroll", buildJsonObject { put("direction", direction) }) { page = it } }, enabled = browser.url.isNotEmpty() && !chat.running) { Text(stringResource(label)) }
                        }
                    }
                    OutlinedTextField(target, { target = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.browser_target)) }, singleLine = true)
                    OutlinedTextField(entry, { entry = it.take(8000) }, label = { Text(stringResource(R.string.browser_text)) }, maxLines = 3)
                    Row {
                        listOf("click" to R.string.browser_click, "type" to R.string.browser_type, "submit" to R.string.browser_submit).forEach { (action, label) ->
                            val complete = stringResource(R.string.browser_action_complete)
                            TextButton(onClick = { vm.browserUserAction(action, buildJsonObject { put("target", target.toInt()); if (action == "type") put("text", entry) }) { page = complete } }, enabled = target.toIntOrNull() in 0..499 && !chat.running) { Text(stringResource(label)) }
                        }
                    }
                    if (page.isNotEmpty()) Text(page, style = MaterialTheme.typography.bodySmall)
                }
            }
            key(browser.viewVersion) {
                AndroidView(factory = { vm.browser.view().also { (it.parent as? android.view.ViewGroup)?.removeView(it) } }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
