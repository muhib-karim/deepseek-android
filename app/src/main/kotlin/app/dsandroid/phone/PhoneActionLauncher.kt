package app.dsandroid.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.dsandroid.R
import app.dsandroid.core.phone.PhoneAction
import app.dsandroid.core.tools.ToolResult
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhoneActionLauncher(private val context: Context) {
    private var host = WeakReference<Activity>(null)
    fun attach(activity: Activity) { host = WeakReference(activity) }
    fun detach(activity: Activity) { if (host.get() === activity) host.clear() }

    suspend fun launch(action: PhoneAction): ToolResult = withContext(Dispatchers.Main.immediate) {
        val activity = host.get()
        if ((activity as? LifecycleOwner)?.lifecycle?.currentState != Lifecycle.State.RESUMED) {
            return@withContext ToolResult(context.getString(R.string.phone_action_foreground), isError = true)
        }
        requireNotNull(activity)
        try {
            activity.startActivity(intent(action))
            ToolResult(activity.getString(R.string.phone_action_opened))
        } catch (_: android.content.ActivityNotFoundException) { ToolResult(activity.getString(R.string.phone_action_unavailable), isError = true) }
        catch (_: SecurityException) { ToolResult(activity.getString(R.string.phone_action_unavailable), isError = true) }
    }

    private fun intent(action: PhoneAction): Intent = when (action) {
        is PhoneAction.Sms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.to, null)).putExtra("sms_body", action.body)
        is PhoneAction.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(action.to)}?subject=${Uri.encode(action.subject)}&body=${Uri.encode(action.body)}"))
            .putExtra(Intent.EXTRA_SUBJECT, action.subject).putExtra(Intent.EXTRA_TEXT, action.body)
        is PhoneAction.Calendar -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, action.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, action.endMillis)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, action.location)
            .putExtra(CalendarContract.Events.DESCRIPTION, action.description)
        is PhoneAction.Map -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.query)}"))
    }
}
