package app.dsandroid.core.tasks

import java.nio.file.Files
import app.dsandroid.core.guard.*
import app.dsandroid.core.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskStoreTest {
    private fun task(interval: Long = 0) = ScheduledTask("task", "Check later", "Summarize my plan", 2000, interval, "bound-account", "model")
    @Test fun modelSchedulingRequiresConfirmationAndBindsTheApprovedPrompt() = runTest {
        val dir = Files.createTempDirectory("tasks").toFile()
        try {
            var scheduled: ScheduledTask? = null
            val tool = SchedulePromptTool("bound-account", "model", { scheduled = it }, { 1000L })
            val workspace = WorkspacePath(dir); val context = ToolContext(workspace)
            val args = buildJsonObject { put("title", "Check later"); put("prompt", "Summarize my plan"); put("runAt", 2000L); put("intervalMinutes", 15L) }
            assertEquals(Decision.CONFIRM, GuardPolicy(ToolRegistry(listOf(tool)), workspace).decide(tool.name, args, GuardSettings()))
            val preview = tool.confirmationArguments(args, context)
            assertEquals("Summarize my plan", preview["prompt"]!!.jsonPrimitive.content)
            assertNull(scheduled)
            tool.run(args, context)
            assertEquals("bound-account", scheduled!!.providerKey)
            assertEquals(15L, scheduled!!.intervalMinutes)
            assertEquals(2000L, scheduled!!.runAt)
        } finally { dir.deleteRecursively() }
    }
    @Test fun validatesTimingAndIntervals() {
        task().validate(1000)
        task(15).validate(1000)
        assertTrue(runCatching { task(14).validate(1000) }.isFailure)
        assertTrue(runCatching { task().validate(2001) }.isFailure)
        assertTrue(runCatching { task().copy(prompt = "").validate(1000) }.isFailure)
    }
    @Test fun claimPreventsDuplicateExecutionAcrossStoreInstances() {
        val dir = Files.createTempDirectory("tasks").toFile()
        try {
            val store = TaskStore(dir); store.save(task(), 1000)
            assertTrue(store.claim("task", "one"))
            assertFalse(TaskStore(dir).claim("task", "one"))
            store.complete(TaskResult("one", "task", "Check later", "Answer", 3000))
            assertEquals(1, TaskStore(dir).unread())
            store.markRead("one")
            assertEquals(0, store.unread())
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancelledTasksCannotClaimAndNewPeriodicCyclesCan() {
        val dir = Files.createTempDirectory("tasks").toFile()
        try {
            val store = TaskStore(dir); store.save(task(15), 1000)
            assertTrue(store.claim("task", "cycle-one"))
            assertTrue(store.claim("task", "cycle-two"))
            store.cancel("task")
            assertFalse(store.claim("task", "cycle-three"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun updatingAtTheActiveLimitDoesNotRemoveOtherTasks() {
        val dir = Files.createTempDirectory("tasks").toFile()
        try {
            val store = TaskStore(dir)
            repeat(50) { store.save(task().copy(id = "task-$it"), 1000) }
            store.save(task().copy(id = "task-0", title = "Updated"), 1000)
            assertEquals(50, store.tasks().count { it.active })
            assertEquals("Updated", store.tasks().single { it.id == "task-0" }.title)
            assertTrue(runCatching { store.save(task(), 1000) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun multibyteResultsStayWithinTheJournalLimit() {
        val dir = Files.createTempDirectory("tasks").toFile()
        try {
            val store = TaskStore(dir); store.save(task(15), 1000)
            repeat(90) { store.complete(TaskResult("run-$it", "task", "Result", "ক".repeat(32000), it.toLong())) }
            assertTrue(dir.resolve("tasks.json").length() <= 8 * 1024 * 1024)
            assertEquals("run-89", TaskStore(dir).results().first().id)
            assertTrue(store.tasks().single().active)
        } finally { dir.deleteRecursively() }
    }
}
