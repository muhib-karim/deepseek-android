package app.dsandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dsandroid.R
import app.dsandroid.voice.SpeechPlayer

@Composable
fun rememberSpeechPlayer(): SpeechPlayer {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember { SpeechPlayer(context) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.close() }
    }
    return player
}

@Composable
fun SpokenText(text: String, player: SpeechPlayer, modifier: Modifier = Modifier) {
    val range = player.range
    val color = MaterialTheme.colorScheme.secondaryContainer
    val foreground = MaterialTheme.colorScheme.onSecondaryContainer
    Text(buildAnnotatedString {
        append(text)
        if (range != null && range.end <= text.length) addStyle(SpanStyle(background = color, color = foreground), range.start, range.end)
    }, modifier, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun ReadAloudControls(id: String, answer: String, player: SpeechPlayer, report: (String) -> Unit) {
    val context = LocalContext.current
    val active = player.owner == id
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                when {
                    active && player.speaking -> player.pause()
                    active && player.paused -> player.resume()
                    else -> player.play(id, answer, onError = { report(context.getString(R.string.read_aloud_unavailable)) })
                }
            }, enabled = player.ready && answer.isNotBlank()) {
                Text(stringResource(when {
                    active && player.speaking -> R.string.read_aloud_pause
                    active && player.paused -> R.string.read_aloud_resume
                    else -> R.string.read_aloud
                }))
            }
            if (active) TextButton(onClick = player::stop) { Text(stringResource(R.string.voice_stop)) }
        }
        if (player.unavailable) Text(stringResource(R.string.read_aloud_unavailable))
        if (active) {
            var selectedRate by remember(player.rate) { mutableStateOf(player.rate) }
            Text(stringResource(R.string.read_aloud_speed, selectedRate))
            Slider(value = selectedRate, onValueChange = { selectedRate = it }, onValueChangeFinished = { player.changeRate(selectedRate) }, valueRange = 0.5f..2f, steps = 5)
            Text(stringResource(R.string.read_aloud_resume_notice), style = MaterialTheme.typography.labelSmall)
        }
    }
}
