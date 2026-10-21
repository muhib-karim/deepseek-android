package app.dsandroid.deepseek

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.flavor.EngineEntry
import app.dsandroid.flavor.EngineKeyForm

/**
 * The full build's first screen (v1.3.0): two routes and nothing else. "Continue with Google" is the DeepSeek
 * web-account sign-in of v1.1/v1.2 (Google through the phone's browser); "Use API key" starts DeepSeek's own agent
 * harness on this phone with one key field and one Start button.
 */
@Composable
fun EngineWelcomeScreen(
    state: AccountUiState,
    onGoogle: () -> Unit,
    onTerms: (Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var consent by rememberSaveable { mutableStateOf(false) }
    fun google() {
        EngineEntry.chooseGoogle(context)
        if (state.termsAgreed) onGoogle() else consent = true
    }
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp).testTag("welcome-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        AppMark(Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("DeepSeek", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(48.dp))
        state.error?.let { message ->
            Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 16.dp), color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 10.dp).testTag("account-error"),
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissNotice) { Text("Dismiss") }
                }
            }
        }
        if (state.validating) Row(Modifier.padding(bottom = 16.dp).testTag("welcome-validating"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking your session with DeepSeek…", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = ::google, enabled = !state.busy && !state.validating,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("welcome-google"), shape = RoundedCornerShape(26.dp)) {
            Text("Continue with Google", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.widthIn(max = 420.dp).padding(vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text("or", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        EngineKeyForm(onStarted = { EngineEntry.openEngine(context) })
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Terms of Use and Privacy Policy") },
        text = { Text("To sign in, agree to DeepSeek's Terms of Use and Privacy Policy. They govern your DeepSeek account; this app is an unofficial client.") },
        confirmButton = { TextButton(onClick = { consent = false; onTerms(true); onGoogle() }, modifier = Modifier.testTag("consent-agree")) { Text("Agree") } },
        dismissButton = { TextButton(onClick = { consent = false }, modifier = Modifier.testTag("consent-cancel")) { Text("Cancel") } })
}
