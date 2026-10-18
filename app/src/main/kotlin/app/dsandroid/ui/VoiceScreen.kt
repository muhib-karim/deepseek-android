package app.dsandroid.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dsandroid.R
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.chat.FollowUps
import app.dsandroid.core.voice.VoicePhase
import app.dsandroid.core.voice.VoiceSession
import app.dsandroid.data.AppSettings
import app.dsandroid.voice.SpeechPlayer
import app.dsandroid.voice.VoiceRecognizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(state: ChatUiState, settings: AppSettings, vm: ChatViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val recognizer = remember { VoiceRecognizer(context) }
    val speaker = remember { SpeechPlayer(context) }
    var session by remember { mutableStateOf(VoiceSession()) }
    var pendingAnswer by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf(false) }
    val latestState by rememberUpdatedState(state)
    fun pause() { session = session.pause(); recognizer.cancel(); speaker.stop(); pendingAnswer = null }
    fun listen() {
        if (lifecycle.currentState != Lifecycle.State.RESUMED || latestState.running || latestState.importing || reviewing) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        vm.dismissError(); session = session.listen()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Permission UI ends the interaction; another deliberate tap starts the microphone.
        if (!granted) vm.showError(context.getString(R.string.voice_permission))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recognizer.cancel(); speaker.close() }
    }
    BackHandler { pause(); close() }
    LaunchedEffect(session.phase, session.generation) {
        if (session.phase == VoicePhase.LISTENING) {
            if (lifecycle.currentState != Lifecycle.State.RESUMED || reviewing || vm.state.value.importing || vm.state.value.running) {
                pause(); return@LaunchedEffect
            }
            val token = session.generation
            recognizer.listen(settings.audioLanguage, result = { spoken ->
                if (session.generation == token && session.phase == VoicePhase.LISTENING) {
                    session = session.heard(token)
                    if (vm.send(spoken, false, false, agent)) pendingAnswer = vm.state.value.items.lastOrNull()?.id
                    else session = session.pause()
                }
            }, failed = { if (session.generation == token) {
                session = session.pause(); vm.showError(context.getString(R.string.voice_failed))
            } })
        }
    }
    LaunchedEffect(state.running, pendingAnswer, session.phase) {
        val target = pendingAnswer
        val current = vm.state.value
        if (target != null && !current.running && session.phase == VoicePhase.THINKING) {
            val answer = current.items.filterIsInstance<ChatItem.Assistant>().find { it.id == target }
                ?.steps?.filterIsInstance<TurnStep.Answer>()?.joinToString("\n\n") { FollowUps.parse(it.text).answer }.orEmpty()
            val token = session.generation
            val success = current.error == null && !current.resumable && answer.isNotBlank()
            session = session.answered(token, success)
            pendingAnswer = null
            if (success) speaker.play(target, answer,
                onDone = { session = session.finished(token) },
                onError = { session = session.pause(); vm.showError(context.getString(R.string.read_aloud_unavailable)) },
                onInterrupted = { session = session.pause() })
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.voice_mode)) }, navigationIcon = {
        TextButton(onClick = { pause(); close() }) { Text(stringResource(R.string.voice_close)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(when (session.phase) {
                VoicePhase.PAUSED -> R.string.voice_paused
                VoicePhase.LISTENING -> R.string.listening
                VoicePhase.THINKING -> R.string.thinking
                VoicePhase.SPEAKING -> R.string.voice_speaking
            }), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.voice_mode_notice))
            Row {
                Text(stringResource(R.string.voice_agent), Modifier.weight(1f))
                Switch(agent, { agent = it }, enabled = session.phase == VoicePhase.PAUSED && !state.running)
            }
            Button(onClick = {
                when {
                    session.phase == VoicePhase.LISTENING -> pause()
                    state.running -> { pause(); vm.stop() }
                    session.phase == VoicePhase.SPEAKING -> { pause(); listen() }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    else -> listen()
                }
            }, enabled = state.settingsReady && !state.importing && !reviewing && speaker.ready) {
                Text(stringResource(when {
                    session.phase == VoicePhase.SPEAKING -> R.string.voice_interrupt
                    state.running -> R.string.voice_stop
                    session.phase == VoicePhase.LISTENING -> R.string.voice_pause
                    else -> R.string.voice_start
                }))
            }
            if (speaker.unavailable) Text(stringResource(R.string.read_aloud_unavailable))
            VoiceAttachments(!state.running && !state.importing && state.attachments.size < app.dsandroid.core.attachments.AttachmentContextBuilder.MAX_FILES, settings.supportsVision(false), vm, ::pause) { reviewing = it }
            state.attachments.forEach { upload ->
                InputChip(selected = false, onClick = {}, label = { Text(upload.name) }, trailingIcon = {
                    TextButton(onClick = { vm.removeAttachment(upload.id) }, enabled = !state.running && !state.importing) { Text(stringResource(R.string.voice_remove_image)) }
                })
            }
            if (state.importing) CircularProgressIndicator()
            Text(stringResource(R.string.voice_transcript), style = MaterialTheme.typography.titleMedium)
            state.items.takeLast(6).forEach { item ->
                when (item) {
                    is ChatItem.User -> Text(item.text)
                    is ChatItem.Assistant -> item.steps.filterIsInstance<TurnStep.Answer>().forEach { MarkdownText(FollowUps.display(it.text, state.running && item.id == state.items.lastOrNull()?.id)) }
                }
            }
        }
    }
}
