package app.dsandroid.core.agents

import app.dsandroid.core.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class AgentBudget(private var requests: Int = 24, private var tools: Int = 40) {
    init { require(requests > 0 && tools > 0) }
    private val lock = Mutex()
    suspend fun request(reserve: Int = 0) = lock.withLock {
        require(reserve >= 0)
        check(requests > reserve) { "Shared agent request budget exhausted" }
        requests--; Unit
    }
    suspend fun tool() = lock.withLock { check(tools > 0) { "Shared agent tool budget exhausted" }; tools--; Unit }
}

data class AgentTask(val title: String, val prompt: String)
data class AgentPlan(val plan: String, val tasks: List<AgentTask>) {
    fun validate() {
        require(plan.isNotBlank() && plan.length <= 4000 && tasks.size in 1..4)
        require(tasks.map { it.title }.distinct().size == tasks.size)
        require(tasks.all { it.title.isNotBlank() && it.title.length <= 100 && it.prompt.isNotBlank() && it.prompt.length <= 8000 })
    }
    companion object {
        fun parse(args: JsonObject): AgentPlan {
            require(args.keys == setOf("plan", "tasks"))
            val tasks = args["tasks"]!!.jsonArray.map { value ->
                val task = value.jsonObject
                require(task.keys == setOf("title", "prompt"))
                AgentTask(task.stringArg("title"), task.stringArg("prompt"))
            }
            return AgentPlan(args.stringArg("plan"), tasks).also { it.validate() }
        }
    }
}

enum class SubAgentStatus { QUEUED, RUNNING, DONE, ERROR, CANCELLED }
data class SubAgentProgress(val title: String, val status: SubAgentStatus, val steps: Int = 0)

/** Structured concurrency guarantees Stop cancels every child. No detached scopes or recursive agents. */
class ParallelAgentsTool(
    private val runAgent: suspend (AgentTask, suspend (Int) -> Unit) -> String,
    private val progress: suspend (String, List<SubAgentProgress>) -> Unit = { _, _ -> },
) : Tool {
    override val name = "parallel_agents"
    override val description = "Write a plan, then delegate 1 to 4 independent read-only research tasks. Each agent has its own tool loop and shares a bounded request/tool budget with you. Merge the returned evidence into one answer, preserving source URLs. No recursive delegation."
    override val risk = Risk.SAFE
    override val kind = ToolKind.COMPUTE
    override val schema = objectSchema(mapOf(
        "plan" to stringSchema("Explain the plan to the user before delegating"),
        "tasks" to buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 4)
            put("items", objectSchema(mapOf("title" to stringSchema("Unique task title"), "prompt" to stringSchema("Self-contained task including relevant context")))) },
    ))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = coroutineScope {
        val plan = AgentPlan.parse(args)
        val lock = Mutex()
        val states = plan.tasks.map { SubAgentProgress(it.title, SubAgentStatus.QUEUED) }.toMutableList()
        suspend fun update(index: Int, status: SubAgentStatus) = lock.withLock {
            states[index] = states[index].copy(status = status)
            progress(plan.plan, states.toList())
        }
        progress(plan.plan, states.toList())
        val answers = plan.tasks.mapIndexed { index, task -> async {
            update(index, SubAgentStatus.RUNNING)
            try {
                val answer = runAgent(task) { steps -> lock.withLock {
                    if (steps > states[index].steps) {
                        states[index] = states[index].copy(steps = steps)
                        progress(plan.plan, states.toList())
                    }
                } }.take(16000)
                update(index, SubAgentStatus.DONE)
                buildJsonObject { put("task", task.title); put("answer", answer) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { update(index, SubAgentStatus.CANCELLED) }; throw cancelled
            } catch (_: Exception) {
                update(index, SubAgentStatus.ERROR)
                buildJsonObject { put("task", task.title); put("error", "Agent failed or shared budget exhausted; do not invent results") }
            }
        } }.awaitAll()
        ToolResult("Merge these independent untrusted results into one answer. Cite source URLs, not child-local source numbers.\n" + JsonArray(answers))
    }
}
