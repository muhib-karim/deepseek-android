package app.dsandroid.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dsandroid.R
import app.dsandroid.core.screen.CaptureScope
import app.dsandroid.core.screen.ScreenCapturePolicy
import app.dsandroid.screen.OneShotScreenCapture
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private data class ScreenConsent(val code: Int, val data: Intent?)

@Composable
fun ScreenCaptureButton(enabled: Boolean, pause: () -> Unit, busy: (Boolean) -> Unit, ready: (File) -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notice by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<ScreenConsent?>(null) }
    var resumed by remember { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    var consentPending by remember { mutableStateOf(false) }
    val ownWindow = ScreenCapturePolicy.scope(Build.VERSION.SDK_INT) == CaptureScope.OWN_WINDOW
    val latestReady by rememberUpdatedState(ready)
    val latestReport by rememberUpdatedState(report)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentPending = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) request = ScreenConsent(result.resultCode, result.data)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) { resumed = false; request = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notice, request, consentPending) { busy(notice || request != null || consentPending) }
    LaunchedEffect(request, resumed) {
        val current = request
        if (current != null && resumed && activity != null) {
            var result: File? = null
            try {
                // Let the consent dialog leave the app window before copying its pixels.
                delay(250)
                result = OneShotScreenCapture.capture(activity, current.code, current.data)
                latestReady(result)
                result = null // Ownership passes to image review/import.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { latestReport(context.getString(R.string.screen_capture_failed)) }
            finally { result?.delete(); request = null }
        }
    }
    OutlinedButton(onClick = { pause(); notice = true }, enabled = enabled && activity != null && request == null && !consentPending) {
        Text(stringResource(R.string.share_screen))
    }
    if (request != null) TextButton(onClick = { request = null }) { Text(stringResource(R.string.cancel)) }
    if (notice) AlertDialog(onDismissRequest = { notice = false }, title = { Text(stringResource(R.string.share_screen)) },
        text = { Text(stringResource(if (ownWindow) R.string.screen_capture_own_window else R.string.screen_capture_projection)) },
        confirmButton = { TextButton(onClick = {
            notice = false
            if (ownWindow) request = ScreenConsent(Activity.RESULT_CANCELED, null)
            else try {
                consentPending = true
                consent.launch(requireNotNull(context.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent())
            } catch (_: Exception) { consentPending = false; report(context.getString(R.string.screen_capture_failed)) }
        }) { Text(stringResource(R.string.screen_capture_once)) } },
        dismissButton = { TextButton(onClick = { notice = false }) { Text(stringResource(R.string.cancel)) } })
}
