package app.dsandroid.core.agent

/**
 * Agent mode is on by default on both routes (v1.2.0). A stored "off" counts only when the person switched it off
 * themselves (the `agent_mode_set_by_user` flag); a value left by an older version without the flag reads as on.
 */
object AgentModeDefault {
    const val SET_BY_USER_SUFFIX = "_set_by_user"

    fun resolve(stored: Boolean?, setByUser: Boolean): Boolean = if (setByUser) stored ?: true else true
}
