package app.dsandroid.launch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.dsandroid.MainActivity
import app.dsandroid.R
import app.dsandroid.core.launch.QuickAction
import java.util.UUID

data class LaunchRequest(val action: QuickAction, val token: String = UUID.randomUUID().toString())

object QuickLaunch {
    fun intent(context: Context, action: QuickAction) = Intent(context, MainActivity::class.java)
        .setAction(action.action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, action: QuickAction): PendingIntent = PendingIntent.getActivity(context, action.ordinal,
        intent(context, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun publish(context: Context) {
        runCatching {
            val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
            if (manager.isRateLimitingActive) return
            val shortcuts = QuickAction.dynamic(manager.maxShortcutCountPerActivity, manager.manifestShortcuts.size).map { action ->
                val label = context.getString(when (action) {
                    QuickAction.NEW_CHAT -> R.string.new_chat
                    QuickAction.VOICE -> R.string.voice_mode
                    QuickAction.CAMERA -> R.string.camera
                })
                ShortcutInfo.Builder(context, "dynamic_${action.name.lowercase(java.util.Locale.ROOT)}")
                    .setShortLabel(label).setLongLabel(label)
                    .setIcon(Icon.createWithResource(context, when (action) {
                        QuickAction.NEW_CHAT -> R.drawable.ic_shortcut_chat
                        QuickAction.VOICE -> R.drawable.ic_shortcut_voice
                        QuickAction.CAMERA -> R.drawable.ic_shortcut_camera
                    })).setIntent(intent(context, action)).build()
            }
            if (manager.dynamicShortcuts.map { it.id to it.shortLabel.toString() } != shortcuts.map { it.id to it.shortLabel.toString() }) {
                manager.setDynamicShortcuts(shortcuts)
            }
        }
    }
}
