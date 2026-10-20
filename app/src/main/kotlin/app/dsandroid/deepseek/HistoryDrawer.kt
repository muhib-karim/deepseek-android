package app.dsandroid.deepseek

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dsandroid.core.deepseek.HistoryGroups
import app.dsandroid.ui.AppIcon
import app.dsandroid.ui.Glyph
import app.dsandroid.ui.LocalOfficialColors
import java.time.ZoneId

/**
 * Official drawer (docs/reference/SCREEN_SPEC.md, "drawer"): content search, New chat, chats grouped Pinned / Today /
 * Yesterday / 7 Days / 30 Days / month, each with a "..." menu (Rename, Pin, Delete); then this app's extension rows
 * (files, plugins and tools, skills) and the account row that opens Settings.
 */
@Composable
internal fun HistoryDrawer(
    state: AccountUiState, query: String, onQuery: (String) -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit,
    onRename: (AccountHistory) -> Unit, onPin: (AccountHistory) -> Unit, onDelete: (AccountHistory) -> Unit,
    onFiles: () -> Unit, onSkills: () -> Unit, onSettings: () -> Unit, onPlugins: () -> Unit = {},
) {
    val official = LocalOfficialColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalDrawerSheet(Modifier.fillMaxWidth(.86f).widthIn(max = 320.dp), drawerContainerColor = official.drawerSurface,
        drawerShape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(Glyph.SEARCH, modifier = Modifier.size(16.dp), color = muted)
                BasicTextField(query, onQuery, Modifier.weight(1f).testTag("history-search"), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search chat content...", color = official.placeholder, style = MaterialTheme.typography.bodyMedium); inner() } })
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !state.busy, onClick = onNew)
                .heightIn(min = 48.dp).padding(horizontal = 12.dp).testTag("drawer-new-chat"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(Glyph.NEW_CHAT, modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary)
                Text("New chat", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val needle = query.trim().lowercase()
        val visible = if (needle.isEmpty()) state.history else state.history.filter { needle in it.searchText || it.title.lowercase().contains(needle) }
        val groups = HistoryGroups.group(visible, { it.updatedAt }, { it.pinned }, System.currentTimeMillis(), ZoneId.systemDefault())
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (needle.isEmpty()) "No chat yet" else "No matching chats", style = MaterialTheme.typography.bodyMedium)
                    if (needle.isEmpty()) Text("Your chats with DeepSeek appear here.", color = muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
            groups.forEach { (label, chats) ->
                item(key = "group-$label") {
                    Text(label, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp), color = muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                items(chats, key = { it.id }) { chat ->
                    HistoryRow(chat, selected = chat.id == state.conversationId, enabled = !state.busy, onOpen = { onOpen(chat.id) },
                        onRename = { onRename(chat) }, onPin = { onPin(chat) }, onDelete = { onDelete(chat) })
                }
            }
        }
        HorizontalDivider(color = official.divider)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            DrawerLink(Glyph.FOLDER, "Files in this chat", "chat-files", onFiles)
            DrawerLink(Glyph.PUZZLE, "Plugins & tools", "drawer-plugins", onPlugins)
            DrawerLink(Glyph.TOOL, "Skills", "drawer-skills", onSkills)
            DrawerLink(Glyph.GEAR, "Settings", "drawer-settings", onSettings)
            val account = state.accounts.firstOrNull { it.active }
            // The account row repeats names shown elsewhere, so screen readers get one description instead of duplicate text.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onSettings).heightIn(min = 56.dp)
                .padding(horizontal = 12.dp).clearAndSetSemantics { contentDescription = "Account and settings" }.testTag("drawer-account"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = account?.label ?: "DeepSeek"
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(label.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    (state.profileEmail ?: state.profilePhone)?.let { Text(it, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                AppIcon(Glyph.MORE, modifier = Modifier.size(18.dp), color = muted)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(chat: AccountHistory, selected: Boolean, enabled: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(enabled = enabled, onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Chat options")
            .heightIn(min = 48.dp).padding(start = 12.dp).testTag("history-open-${chat.id}"), verticalAlignment = Alignment.CenterVertically) {
            Text(chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "More options for ${chat.title}" }) {
                AppIcon(Glyph.MORE, modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { AppIcon(Glyph.EDIT, modifier = Modifier.size(18.dp)) },
                onClick = { menu = false; onRename() }, modifier = Modifier.semantics { contentDescription = "Rename ${chat.title}" })
            DropdownMenuItem(text = { Text(if (chat.pinned) "Unpin" else "Pin") }, leadingIcon = { AppIcon(Glyph.PIN, modifier = Modifier.size(18.dp)) },
                onClick = { menu = false; onPin() }, modifier = Modifier.semantics { contentDescription = "${if (chat.pinned) "Unpin" else "Pin"} ${chat.title}" })
            DropdownMenuItem(text = { Text("Delete", color = LocalOfficialColors.current.destructive) },
                leadingIcon = { AppIcon(Glyph.TRASH, modifier = Modifier.size(18.dp), color = LocalOfficialColors.current.destructive) },
                onClick = { menu = false; onDelete() }, modifier = Modifier.semantics { contentDescription = "Delete ${chat.title}" })
        }
    }
}

@Composable
private fun DrawerLink(glyph: Glyph, label: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(glyph, modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
