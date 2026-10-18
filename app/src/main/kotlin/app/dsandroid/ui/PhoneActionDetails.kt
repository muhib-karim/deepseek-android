package app.dsandroid.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import app.dsandroid.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun phoneActionLabel(name: String): Int? = when (name) {
    "draft_sms" -> R.string.phone_sms
    "draft_email" -> R.string.phone_email
    "add_calendar_event" -> R.string.phone_calendar
    "open_map" -> R.string.phone_map
    else -> null
}

@Composable
internal fun PhoneCalendarTimes(arguments: JsonObject) {
    val start = (arguments["beginMillis"] as? JsonPrimitive)?.longOrNull
    val end = (arguments["endMillis"] as? JsonPrimitive)?.longOrNull
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]).withZone(zone)
    if (start != null && end != null) Text(stringResource(R.string.phone_calendar_times,
        formatter.format(Instant.ofEpochMilli(start)), formatter.format(Instant.ofEpochMilli(end)), zone.id))
}
