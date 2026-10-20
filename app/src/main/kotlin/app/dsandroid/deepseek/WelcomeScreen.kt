package app.dsandroid.deepseek

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val TERMS_URL = "https://cdn.deepseek.com/policies/en-US/deepseek-terms-of-use.html"
const val PRIVACY_URL = "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html"

/**
 * Signed out, this is the whole app: no drawer, chat, composer, files or tools until DeepSeek confirms a session.
 * Laid out like the official sign-in page (docs/reference/SCREEN_SPEC.md, screen "login"). The browser route comes
 * first: Google refuses its sign-in inside apps, and the embedded page stays blank on some phones.
 */
@Composable
fun WelcomeScreen(
    state: AccountUiState,
    heldShare: Boolean = false,
    onBrowser: () -> Unit = {},
    onGoogle: () -> Unit = {},
    onPhoneEmail: () -> Unit = {},
    onPaste: () -> Unit = {},
    onUseApiKey: (String) -> Unit = {},
    onAccounts: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onFinishLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    val busy = state.validating || state.busy
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("welcome-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        AppMark(Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text("DeepSeek Android", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Unofficial client · not affiliated with DeepSeek", style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(36.dp))
        Text("Sign in to start chatting", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        state.error?.let { message ->
            Surface(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 10.dp).testTag("account-error"),
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissNotice) { Text("Dismiss") }
                }
            }
        }
        if (state.validating) Row(Modifier.padding(vertical = 8.dp).testTag("welcome-validating"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking your session with DeepSeek…", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (heldShare) Text("Sign in to use what you shared with this app.", Modifier.padding(vertical = 6.dp).testTag("welcome-held-share"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        if (state.logoutPending) TextButton(onClick = onFinishLogout, enabled = !busy) { Text("Finish logging out") }
        Spacer(Modifier.height(12.dp))
        val wide = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 48.dp)
        Button(onClick = onBrowser, enabled = !busy, modifier = wide.testTag("welcome-browser"), shape = RoundedCornerShape(24.dp)) {
            Text("Sign in with browser", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGoogle, enabled = !busy, modifier = wide.testTag("welcome-google"), shape = RoundedCornerShape(24.dp)) {
            Text("Continue with Google", color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onPhoneEmail, enabled = !busy, modifier = wide.testTag("welcome-phone-email"), shape = RoundedCornerShape(24.dp)) {
            Text("Log in with phone or email", color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onPaste, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("welcome-paste")) { Text("Paste session") }
        Text("Google doesn't allow its sign-in inside apps, so browser and Google sign-in use your phone's browser once and bring " +
            "the session back here. Phone and email open DeepSeek's own sign-in page in the app.",
            Modifier.widthIn(max = 420.dp), style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { more = !more }, modifier = Modifier.testTag("welcome-more")) { Text(if (more) "Fewer options" else "More sign-in options") }
        if (more) {
            OutlinedButton(onClick = { keyDialog = true }, enabled = !busy, modifier = wide.testTag("welcome-api-key"), shape = RoundedCornerShape(24.dp)) {
                Text("Use a DeepSeek API key", color = MaterialTheme.colorScheme.onBackground)
            }
            Text("Opens the separate API-key client. API use is billed to your DeepSeek platform balance.", Modifier.padding(top = 6.dp).widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
            if (state.accounts.isNotEmpty()) TextButton(onClick = onAccounts, enabled = !busy, modifier = Modifier.testTag("welcome-accounts")) {
                Text("Choose one of your accounts")
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("DeepSeek can make mistakes. Signing in to DeepSeek means accepting DeepSeek's terms.", Modifier.widthIn(max = 420.dp),
            style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { onOpenLink(TERMS_URL) }) { Text("Terms of Use", fontSize = 12.sp) }
            TextButton(onClick = { onOpenLink(PRIVACY_URL) }) { Text("Privacy Policy", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (keyDialog) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { key = ""; keyDialog = false },
            title = { Text("Use a DeepSeek API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The key is checked with DeepSeek, then stored encrypted on this phone. The API-key client uses your platform balance.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(key, { key = it.take(16_384) }, Modifier.fillMaxWidth().testTag("welcome-api-key-field"), label = { Text("API key") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = { TextButton(onClick = { onUseApiKey(key); key = ""; keyDialog = false }, enabled = key.isNotBlank()) { Text("Check and use") } },
            dismissButton = { TextButton(onClick = { key = ""; keyDialog = false }) { Text("Cancel") } },
        )
    }
}

/** While the encrypted stores open: the mark alone, as the official app's launch screen shows its logo. */
@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppMark(Modifier.size(56.dp))
            Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The app's own mark (chat bubble with a spark, as in res/drawable/ic_mark.xml), drawn in code; never DeepSeek's whale. */
@Composable
fun AppMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, spark: Color = Color.White) {
    Canvas(modifier.semantics { contentDescription = "DeepSeek Android mark" }) {
        val k = size.minDimension / 72f
        val left = (size.width - 72f * k) / 2f
        val top = (size.height - 72f * k) / 2f
        fun x(v: Float) = left + (v - 18f) * k
        fun y(v: Float) = top + (v - 22f) * k
        val bubble = Path().apply {
            moveTo(x(28f), y(28f)); lineTo(x(80f), y(28f)); quadraticBezierTo(x(86f), y(28f), x(86f), y(36f))
            lineTo(x(86f), y(68f)); quadraticBezierTo(x(86f), y(76f), x(78f), y(76f)); lineTo(x(48f), y(76f)); lineTo(x(30f), y(88f))
            lineTo(x(30f), y(76f)); quadraticBezierTo(x(22f), y(76f), x(22f), y(68f)); lineTo(x(22f), y(36f))
            quadraticBezierTo(x(22f), y(28f), x(28f), y(28f)); close()
        }
        drawPath(bubble, color)
        val star = Path().apply {
            moveTo(x(57f), y(37f)); lineTo(x(61f), y(48f)); lineTo(x(72f), y(52f)); lineTo(x(61f), y(56f))
            lineTo(x(57f), y(67f)); lineTo(x(53f), y(56f)); lineTo(x(42f), y(52f)); lineTo(x(53f), y(48f)); close()
        }
        drawPath(star, spark)
    }
}
