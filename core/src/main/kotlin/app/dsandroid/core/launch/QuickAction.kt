package app.dsandroid.core.launch

enum class QuickAction(val action: String) {
    NEW_CHAT("app.dsandroid.action.NEW_CHAT"),
    VOICE("app.dsandroid.action.VOICE"),
    CAMERA("app.dsandroid.action.CAMERA");

    companion object {
        fun fromAction(action: String?): QuickAction? = if (action == "android.intent.action.ASSIST") VOICE else entries.find { it.action == action }
        fun dynamic(maximum: Int, manifestCount: Int): List<QuickAction> = listOf(VOICE, CAMERA, NEW_CHAT).take((maximum - manifestCount).coerceAtLeast(0))
    }
}
