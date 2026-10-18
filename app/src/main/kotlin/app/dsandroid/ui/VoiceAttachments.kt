package app.dsandroid.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.dsandroid.R
import app.dsandroid.attachments.ImageAttachmentDecoder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Camera and screen images enter the same importer and model capability checks as chat attachments. */
@Composable
fun VoiceAttachments(enabled: Boolean, vision: Boolean, vm: ChatViewModel, pause: () -> Unit, reviewing: (Boolean) -> Unit) {
    val context = LocalContext.current
    var screenBusy by remember { mutableStateOf(false) }
    var cameraName by rememberSaveable { mutableStateOf<String?>(null) }
    var previewName by rememberSaveable { mutableStateOf<String?>(null) }
    fun file(name: String) = File(File(context.cacheDir, "camera"), name)
    fun uri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", file(name))
    fun discard() { previewName?.let { file(it).delete() }; previewName = null }
    LaunchedEffect(cameraName, previewName, screenBusy) { reviewing(cameraName != null || previewName != null || screenBusy) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val name = cameraName
        cameraName = null
        if (name != null) {
            if (success) previewName = name else file(name).delete()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            pause()
            try {
                val name = "${UUID.randomUUID()}.jpg"
                val output = file(name)
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                check(output.createNewFile())
                cameraName = name
                camera.launch(uri(name))
            } catch (_: Exception) {
                cameraName?.let { file(it).delete() }; cameraName = null
                vm.showError(context.getString(R.string.camera_unavailable))
            }
        }, enabled = enabled && cameraName == null && previewName == null && !screenBusy) { Text(stringResource(R.string.camera)) }
        ScreenCaptureButton(enabled && cameraName == null && previewName == null, pause,
            busy = { screenBusy = it }, ready = { previewName = it.name }, report = vm::showError)
    }
    previewName?.let { name ->
        var bitmap by remember(name) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(name) { mutableStateOf(false) }
        LaunchedEffect(name) {
            var decoded: Bitmap? = null
            try {
                withContext(Dispatchers.IO) { decoded = ImageAttachmentDecoder.decode(file(name)) }
                bitmap = decoded
                decoded = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { decoded?.recycle() }
        }
        DisposableEffect(name) { onDispose { bitmap?.recycle() } }
        AlertDialog(onDismissRequest = ::discard, title = { Text(stringResource(R.string.voice_image_review)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.voice_image_review), Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                if (failed) Text(stringResource(R.string.upload_read_error))
                else if (bitmap == null) CircularProgressIndicator()
                Text(stringResource(if (vision) R.string.voice_image_vision else R.string.voice_image_ocr))
            }
        }, confirmButton = {
            TextButton(onClick = { vm.addAttachments(listOf(uri(name))); previewName = null }, enabled = bitmap != null && enabled) {
                Text(stringResource(R.string.voice_image_attach))
            }
        }, dismissButton = { TextButton(onClick = ::discard) { Text(stringResource(R.string.cancel)) } })
    }
}
