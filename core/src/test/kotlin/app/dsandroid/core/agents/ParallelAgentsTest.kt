package app.dsandroid.core.agents

import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.tools.ToolContext
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ParallelAgentsTest {
    @Test fun budgetIsSharedAcrossConcurrentConsumers() = runTest {
        val budget = AgentBudget(3, 2)
        val allowed = (1..8).map { async { runCatching { budget.request() }.isSuccess } }.awaitAll()
        assertEquals(3, allowed.count { it })
        budget.tool(); budget.tool()
        assertTrue(runCatching { budget.tool() }.isFailure)
    }
    @Test fun limitAndPlanAreRequired() {
        assertTrue(runCatching { AgentPlan.parse(buildJsonObject { put("plan", ""); put("tasks", JsonArray(emptyList())) }) }.isFailure)
        assertTrue(runCatching { AgentPlan("plan", List(5) { AgentTask("a$it", "task") }).validate() }.isFailure)
    }
    @Test fun childrenLeaveARequestForTheMergedAnswer() = runTest {
        val budget = AgentBudget(3, 2)
        budget.request(reserve = 1); budget.request(reserve = 1)
        assertTrue(runCatching { budget.request(reserve = 1) }.isFailure)
        budget.request()
        assertTrue(runCatching { budget.request() }.isFailure)
    }
    @Test fun successfulEvidenceAndIndividualFailuresReachTheParent() = runTest {
        val dir = Files.createTempDirectory("parallel").toFile()
        try {
            var latest = emptyList<SubAgentProgress>()
            val tool = ParallelAgentsTool({ task, progress ->
                progress(2)
                if (task.title == "missing") error("No evidence")
                "Verified https://example.com/source"
            }, { _, agents -> latest = agents })
            val result = tool.run(buildJsonObject {
                put("plan", "Compare independent evidence")
                put("tasks", JsonArray(listOf("source", "missing").map { buildJsonObject { put("title", it); put("prompt", "Investigate") } }))
            }, ToolContext(WorkspacePath(dir)))
            assertTrue(result.content.contains("https://example.com/source"))
            assertTrue(result.content.contains("\"error\""))
            assertEquals(listOf(SubAgentStatus.DONE, SubAgentStatus.ERROR), latest.map { it.status })
            assertEquals(listOf(2, 2), latest.map { it.steps })
        } finally { dir.deleteRecursively() }
    }
    @Test fun agentsOverlapAndCancellationReachesEveryChild() = runTest {
        val dir = Files.createTempDirectory("parallel").toFile()
        try {
            val started = mutableSetOf<String>(); val cancelled = mutableSetOf<String>()
            val allStarted = CompletableDeferred<Unit>()
            val tool = ParallelAgentsTool({ task, _ ->
                started.add(task.title); if (started.size == 4) allStarted.complete(Unit)
                try { awaitCancellation() } finally { cancelled.add(task.title) }
            })
            val job = launch {
                tool.run(buildJsonObject {
                    put("plan", "Check four sources")
                    put("tasks", JsonArray((1..4).map { buildJsonObject { put("title", "agent-$it"); put("prompt", "Read source $it") } }))
                }, ToolContext(WorkspacePath(dir)))
            }
            allStarted.await(); job.cancelAndJoin()
            assertEquals(started, cancelled)
        } finally { dir.deleteRecursively() }
    }
}
