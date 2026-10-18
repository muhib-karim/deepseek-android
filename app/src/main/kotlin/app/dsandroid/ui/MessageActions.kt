package app.dsandroid.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/** Observe before selectable Markdown consumes the gesture; ordinary taps and scrolling stay intact. */
internal fun Modifier.branchGesture(id: String, enabled: Boolean, label: String, branch: () -> Unit): Modifier =
    sizeIn(minWidth = 48.dp, minHeight = 48.dp).pointerInput(id, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    finished = pointer == null || !pointer.pressed ||
                        (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1
                }
                true
            }
            if (ended == null) {
                branch()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }.semantics {
        if (enabled) onLongClick(label) { branch(); true }
    }
