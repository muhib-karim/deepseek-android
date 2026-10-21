package app.dsandroid.engine

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** v1.3.0 screenshots: /sdcard/Android/data/<pkg>/files/v130-screenshots/<name>.png, pulled by scripts/ci_engine_emulator.sh. */
object V130Shots {
    /** The engine's foreground-service notification: granted up front so no system prompt covers a screenshot. */
    fun grantNotifications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, "android.permission.POST_NOTIFICATIONS") }
        }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        device.findObject(androidx.test.uiautomator.By.text("Allow"))?.click()
    }

    fun take(name: String) {
        grantNotifications()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "v130-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
