package app.dsandroid.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import app.dsandroid.R
import app.dsandroid.core.launch.QuickAction
import app.dsandroid.core.widget.WidgetChat
import app.dsandroid.launch.QuickLaunch

/** The home-screen host receives only the selected saved title, never messages or temporary chats. */
class AskDeepSeekWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds)
    }
    companion object {
        private const val PREFERENCES = "widget-summary"
        fun update(context: Context, chat: WidgetChat?) {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val title = chat?.title.orEmpty()
            if (prefs.getString("title", "") == title) return
            prefs.edit().putString("title", title).apply()
            refresh(context)
        }
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                render(context, manager, manager.getAppWidgetIds(ComponentName(context, AskDeepSeekWidget::class.java)))
            }
        }
        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val title = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("title", "").orEmpty()
            val views = RemoteViews(context.packageName, R.layout.ask_deepseek_widget).apply {
                setTextViewText(R.id.widget_ask, context.getString(R.string.widget_ask))
                setTextViewText(R.id.widget_voice, context.getString(R.string.voice_mode))
                setTextViewText(R.id.widget_title, title.ifBlank { context.getString(R.string.widget_empty) })
                setOnClickPendingIntent(R.id.widget_ask, QuickLaunch.pending(context, QuickAction.NEW_CHAT))
                setOnClickPendingIntent(R.id.widget_voice, QuickLaunch.pending(context, QuickAction.VOICE))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
