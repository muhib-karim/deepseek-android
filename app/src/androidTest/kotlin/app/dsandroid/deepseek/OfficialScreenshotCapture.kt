package app.dsandroid.deepseek

import android.graphics.Bitmap
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Shared by fixture screenshots and real ViewModel / mocked-service feature tests. */
fun <A : ComponentActivity> captureOfficialScreenshot(
    compose: AndroidComposeTestRule<ActivityScenarioRule<A>, A>, dark: Boolean, name: String,
    canary: String, source: String = "deterministic-fake-data",
) {
    compose.waitForIdle()
    // A sheet, menu, dialog or text-handle popup is a window with its own Compose root: every root is checked, and the
    // screenshot is then of the whole screen so it shows what is on top.
    val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
    val semantics = roots.printToString()
    assertFalse("Session token reached screenshot semantics", semantics.contains(canary))
    assertFalse("Authentication header reached screenshot semantics", semantics.contains("Bearer "))
    val dir = File(compose.activity.getExternalFilesDir(null), "official-ui-screenshots").apply { mkdirs() }
    val prefix = "${if (dark) "dark" else "light"}-$name"
    val bitmap = if (roots.fetchSemanticsNodes().size == 1) compose.onRoot().captureToImage().asAndroidBitmap()
        else InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
    File(dir, "$prefix.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    val metadata = "screen=$name\ntheme=${if (dark) "dark" else "light"}\nsource=$source\n"
    assertFalse(metadata.contains(canary) || metadata.contains("Authorization") || metadata.contains("Cookie"))
    File(dir, "$prefix.txt").writeText(metadata)
    val logs = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("logcat -d --pid=${Process.myPid()}").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    assertFalse("Session token reached application logs", logs.contains(canary))
}
