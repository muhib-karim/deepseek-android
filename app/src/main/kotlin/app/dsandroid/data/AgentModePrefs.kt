package app.dsandroid.data

import android.content.Context
import android.content.SharedPreferences
import app.dsandroid.core.agent.AgentModeDefault

/**
 * Agent mode switches for both routes (on by default since v1.2.0, see [AgentModeDefault]). Kept beside the account
 * route's appearance settings; each switch also records that the person set it, so only their own "off" sticks.
 */
object AgentModePrefs {
    const val FILE = "deepseek_web_appearance"
    /** The account route's Agent pill. */
    const val ACCOUNT = "agent_mode"
    /** API mode's Chat / Agent chips. */
    const val API = "api_agent_mode"

    fun read(prefs: SharedPreferences, key: String): Boolean {
        val stored = if (prefs.contains(key)) prefs.getBoolean(key, true) else null
        val setByUser = prefs.getBoolean(key + AgentModeDefault.SET_BY_USER_SUFFIX, false)
        // The full build's engine is DeepSeek's own harness (v1.3.0): this app's agent stays off unless the person turns it on.
        if (app.dsandroid.BuildConfig.DSH_ENGINE) return setByUser && stored == true
        return AgentModeDefault.resolve(stored, setByUser)
    }

    fun write(prefs: SharedPreferences, key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).putBoolean(key + AgentModeDefault.SET_BY_USER_SUFFIX, true).apply()
    }

    fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
