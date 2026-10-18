package app.dsandroid

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dsandroid.core.llm.*
import app.dsandroid.ui.ChatViewModel
import app.dsandroid.ui.DsAndroidApp
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<SmokeActivity>()
    private val continueReply = CompletableDeferred<Unit>()
    private lateinit var vm: ChatViewModel
    private fun label(id: Int) = compose.activity.getString(id)

    private fun launch(fontScale: Float = 1f, initialDestination: String = "chat") {
        compose.runOnUiThread {
            // Only this test activity uses this backend; the release app has no fake transport.
            listOf("conversations", "runs", "workspace", "tasks").forEach {
                File(compose.activity.filesDir, it).deleteRecursively()
            }
            val backend = object : LlmClient {
                override suspend fun listModels() = listOf(ModelInfo("deepseek-chat"))
                override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>) = flow {
                    emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = "Smoke reply")))))
                    continueReply.await()
                    emit(ChatChunk(listOf(StreamChoice(delta = StreamDelta(content = " complete\n\n```markdown\n# Smoke artifact\n```")))))
                    emit(ChatChunk(listOf(StreamChoice(finishReason = "stop"))))
                }
            }
            vm = ViewModelProvider(compose.activity, viewModelFactory {
                initializer { ChatViewModel(compose.activity.application, backend) }
            })[ChatViewModel::class.java]
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DsAndroidApp(vm, startDestination = initialDestination)
            }
        }
        compose.waitUntil(15_000) { vm.state.value.settingsReady && !vm.state.value.modelsLoading }
    }

    @Test fun appLaunches() {
        launch()
        compose.onNodeWithContentDescription(label(R.string.open_navigation)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.message_placeholder)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.attach_files)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(label(R.string.send_message)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        screenshot("chat")
    }

    @Test fun settingsOpensAtDoubleFontScale() {
        launch(2f)
        compose.onNodeWithContentDescription(label(R.string.open_navigation)).performClick()
        compose.onNodeWithText(label(R.string.profile_footer)).performClick()
        compose.onNodeWithText(label(R.string.settings), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.back)).assertIsDisplayed()
        screenshot("settings-font-200")
    }

    @Test fun sendShowsPartialThenCompletedReplyAndArtifactOpens() {
        launch()
        compose.onNodeWithContentDescription(label(R.string.message_placeholder)).performTextInput("Smoke request")
        compose.onNodeWithContentDescription(label(R.string.send_message)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Smoke reply").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Smoke reply").assertIsDisplayed()
        continueReply.complete(Unit)
        compose.waitUntil(10_000) { !vm.state.value.running }
        compose.onNodeWithText("Smoke reply complete").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.canvas_fenced_title, "MARKDOWN"))
            .performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("artifact-panel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("artifact-panel").assertIsDisplayed()
        screenshot("artifact")
    }

    @Test fun explicitApiFallbackSettingsCanReturnToChat() {
        launch(initialDestination = "settings")
        compose.onNodeWithText(label(R.string.settings), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.message_placeholder)).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(image)
        val directory = File(compose.activity.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
