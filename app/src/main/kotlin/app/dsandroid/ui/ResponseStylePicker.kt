package app.dsandroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.dsandroid.R
import app.dsandroid.core.personalization.ResponseStyle

@Composable
fun ResponseStylePicker(style: ResponseStyle, study: Boolean, enabled: Boolean, select: (ResponseStyle, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        InputChip(selected = style != ResponseStyle.DEFAULT, enabled = enabled, onClick = { expanded = true },
            label = { Text(stringResource(R.string.response_style_value, stringResource(styleLabel(style)))) })
        DropdownMenu(expanded, { expanded = false }) {
            ResponseStyle.entries.forEach { value ->
                DropdownMenuItem(text = { Text(stringResource(styleLabel(value))) }, onClick = { select(value, study); expanded = false })
            }
        }
    }
    FilterChip(selected = study, enabled = enabled, onClick = { select(style, !study) }, label = { Text(stringResource(R.string.study_mode)) })
}

internal fun styleLabel(style: ResponseStyle): Int = when (style) {
    ResponseStyle.DEFAULT -> R.string.style_default
    ResponseStyle.CONCISE -> R.string.style_concise
    ResponseStyle.EXPLANATORY -> R.string.style_explanatory
    ResponseStyle.FORMAL -> R.string.style_formal
}
