package app.dsandroid

import android.content.Context
import app.dsandroid.data.AgentModePrefs

/**
 * Agent mode is on by default since v1.2.0. Tests written for the plain chat route (prompts, Think and Search flags,
 * uploads) switch it off first, as a person would with the Agent pill.
 */
object TestAgentMode {
    fun chat(context: Context) {
        val prefs = AgentModePrefs.prefs(context)
        AgentModePrefs.write(prefs, AgentModePrefs.ACCOUNT, false)
        AgentModePrefs.write(prefs, AgentModePrefs.API, false)
    }

    /** A fresh install: no stored choice, so both routes start with Agent on. */
    fun fresh(context: Context) {
        AgentModePrefs.prefs(context).edit().remove(AgentModePrefs.ACCOUNT).remove(AgentModePrefs.ACCOUNT + "_set_by_user")
            .remove(AgentModePrefs.API).remove(AgentModePrefs.API + "_set_by_user").commit()
    }
}
