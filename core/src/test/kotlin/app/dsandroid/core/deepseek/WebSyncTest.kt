package app.dsandroid.core.deepseek

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/**
 * Upload processing, chat list paging, history, pins and feedback against the shapes the live service returned on
 * 2026-10-20 (fixtures under live-2026-10-20/, ids replaced with synthetic ones).
 */
class WebSyncTest {
    private val credentials = WebCredentials(SessionToken.parse("synthetic-session")!!, "")
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/live-2026-10-20/$name")) { "missing $name" }.readText()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun envelope(data: String) = json("""{"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":$data}}""")
    private fun challenge() = envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"sig","target_path":"/api/v0/file/upload_file"}}""")
    private suspend fun withServer(block: suspend (MockWebServer, DeepSeekWebClient) -> Unit) {
        val server = MockWebServer(); server.start()
        try { block(server, DeepSeekWebClient(OkHttpClient(), server.url("/"), filePollMillis = 10, fileWaitMillis = 3_000)) } finally { server.shutdown() }
    }

    @Test fun anUploadIsUsableOnlyAfterFetchFilesReportsSuccess() = runBlocking<Unit> {
        withServer { server, client ->
            val file = Files.createTempFile("planets", ".csv").toFile().apply { writeText("Planet,Diameter (km)\nEarth,12742\n") }
            try {
                server.enqueue(challenge())
                server.enqueue(json(fixture("upload_pending.json")))
                server.enqueue(envelope("""{"files":[{"id":"file-00000000-0000-4000-8000-000000000001","status":"PENDING"}]}"""))
                server.enqueue(json(fixture("fetch_files_success.json")))
                val uploaded = client.uploadFile(credentials, file, "planets.csv", "text/csv") { 7 }
                assertEquals(WebFile("file-00000000-0000-4000-8000-000000000001", "planets.csv", 68, "SUCCESS"), uploaded)
                assertEquals("""{"target_path":"/api/v0/file/upload_file"}""", server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
                val upload = server.takeRequest(1, TimeUnit.SECONDS)!!
                val pow = Json.parseToJsonElement(String(java.util.Base64.getDecoder().decode(upload.getHeader("X-DS-PoW-Response")!!), Charsets.UTF_8)).jsonObject
                assertEquals(7L, pow["answer"]!!.jsonPrimitive.long)
                assertEquals("/api/v0/file/upload_file", pow["target_path"]!!.jsonPrimitive.content)
                repeat(2) {
                    val poll = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("GET", poll.method)
                    assertEquals("/api/v0/file/fetch_files?file_ids=file-00000000-0000-4000-8000-000000000001", poll.path)
                    assertEquals("Bearer synthetic-session", poll.getHeader("Authorization"))
                }
                assertEquals(4, server.requestCount)
            } finally { file.delete() }
        }
    }

    @Test fun aFileDeepSeekCannotProcessOrDoesNotFinishNamesThatFile() = runBlocking<Unit> {
        withServer { server, client ->
            val file = Files.createTempFile("broken", ".bin").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            try {
                server.enqueue(challenge())
                server.enqueue(envelope("""{"id":"file-a","status":"PENDING","file_name":"broken.bin"}"""))
                server.enqueue(envelope("""{"files":[{"id":"file-a","status":"FAILED","error_code":"x"}]}"""))
                val failed = try { client.uploadFile(credentials, file, "broken.bin", "application/octet-stream") { 1 }; null } catch (error: WebFileFailed) { error }
                assertEquals("DeepSeek couldn't process “broken.bin”. Remove it or try another file.", failed!!.message)
                assertFalse(failed.stillProcessing)

                val slow = DeepSeekWebClient(OkHttpClient(), server.url("/"), filePollMillis = 20, fileWaitMillis = 100)
                server.enqueue(challenge())
                server.enqueue(envelope("""{"id":"file-b","status":"PENDING"}"""))
                repeat(20) { server.enqueue(envelope("""{"files":[{"id":"file-b","status":"PENDING"}]}""")) }
                val pending = try { slow.uploadFile(credentials, file, "slow.pdf", "application/pdf") { 1 }; null } catch (error: WebFileFailed) { error }
                assertTrue(pending!!.stillProcessing)
                assertTrue(pending.message!!.contains("still processing “slow.pdf”"))
            } finally { file.delete() }
        }
    }

    @Test fun aMissingOrRejectedProofOfWorkIsRetryableNotAChangedService() = runBlocking<Unit> {
        withServer { server, client ->
            val file = Files.createTempFile("pow", ".txt").toFile().apply { writeText("hello") }
            try {
                listOf("""{"code":40300,"msg":"MISSING_HEADER","data":null}""", """{"code":40301,"msg":"INVALID_POW_RESPONSE","data":null}""").forEach { reply ->
                    server.enqueue(challenge())
                    server.enqueue(json(reply))
                    val error = try { client.uploadFile(credentials, file, "x.txt", "text/plain") { 1 }; null } catch (error: WebPowRejected) { error }
                    assertNotNull(error)
                    assertTrue(error!!.isTransientWebFailure())
                }
            } finally { file.delete() }
        }
    }

    @Test fun theChatListIsReadPageByPageFromTheLastChatsCursor() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(json(fixture("sessions_page1.json")))
            server.enqueue(json(fixture("sessions_page2.json").replace("\"has_more\":true", "\"has_more\":false")))
            val (sessions, complete) = client.fetchSessions(credentials)
            assertTrue(complete)
            // The boundary chat appears on both pages (lte cursor) and is listed once.
            assertEquals(5, sessions.size)
            assertEquals(sessions.map { it.id }.distinct(), sessions.map { it.id })
            assertEquals("NASA spacewalk headline", sessions.first().title)
            assertEquals(1_792_530_214_859L, sessions.first().updatedAt)
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v0/chat_session/fetch_page?count=50", first.path)
            val second = server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals("1792529766.55", second.queryParameter("lte_cursor.updated_at"))
            assertEquals("false", second.queryParameter("lte_cursor.pinned"))
        }
    }

    @Test fun pagingStopsAtTheLimitOrWhenAPageAddsNothing() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(json(fixture("sessions_page1.json")))
            server.enqueue(json(fixture("sessions_page1.json")))
            val (sessions, complete) = client.fetchSessions(credentials)
            assertFalse("Unread pages remain", complete)
            assertEquals(3, sessions.size)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun historyFollowsTheCurrentBranchAndRendersSearchReplies() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(json(fixture("history_think_search.json")))
            val chat = client.fetchHistory(credentials, "00000000-0000-4000-8000-000000000001")
            assertEquals("/api/v0/chat/history_messages?chat_session_id=00000000-0000-4000-8000-000000000001", server.takeRequest().path)
            assertEquals(2L, chat.currentMessageId)
            assertEquals("NASA spacewalk headline", chat.session.title)
            val (user, reply) = chat.messages
            assertTrue(user.user)
            assertEquals("Latest news headline about NASA today? one line", user.text)
            assertEquals(1L, user.serverId)
            assertTrue(user.thinkingEnabled && user.searchEnabled)
            assertFalse(reply.user)
            assertEquals(2L, reply.serverId)
            assertEquals(1L, reply.parentId)
            assertEquals("NASA Prepares for Spacewalks & Advanced Biomedical Research[citation:3]", reply.text)
            assertEquals(3, reply.citations.size)
            assertEquals(4.078394319, reply.thinkingSeconds!!, 1e-6)
            assertEquals(5, reply.timeline.size)
            assertNull(reply.feedback)
        }
    }

    @Test fun historyShowsFilesAsChipsAndKeepsTheirIds() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(json(fixture("history_file.json")))
            val chat = client.fetchHistory(credentials, "00000000-0000-4000-8000-000000000002")
            val user = chat.messages.first()
            assertEquals("What is the largest planet in the attached file? One line.", user.text)
            assertEquals(listOf(WebAttachment("planets_probe.csv", 68, "file-00000000-0000-4000-8000-000000000001")), user.files)
            assertEquals(listOf("file-00000000-0000-4000-8000-000000000001"), user.refFileIds)
            assertEquals("Earth", chat.messages.last().text)
        }
    }

    @Test fun branchesAreChosenByTheCurrentMessageAndAMissingOneIsNeverGuessed() {
        fun message(id: Int, parent: Int?, role: String, text: String) =
            """{"message_id":$id,"parent_id":${parent ?: "null"},"role":"$role","feedback":${if (role == "ASSISTANT" && id == 5) "{\"feedback_type\":\"GOOD\"}" else "null"},"fragments":[{"id":1,"type":"${if (role == "USER") "REQUEST" else "RESPONSE"}","content":"$text"}]}"""
        val messages = listOf(message(1, null, "USER", "Q"), message(2, 1, "ASSISTANT", "First"), message(3, 1, "ASSISTANT", "Second try"),
            message(4, 3, "USER", "Follow up"), message(5, 4, "ASSISTANT", "Answer")).joinToString(",")
        fun history(current: String) = Json.parseToJsonElement(
            """{"chat_session":{"id":"s","title":"T","pinned":true,"updated_at":1792530214.859,"current_message_id":$current},"chat_messages":[$messages]}""").jsonObject
        val chat = ServerHistory.chat(history("5"))
        assertEquals(listOf("Q", "Second try", "Follow up", "Answer"), chat.messages.map { it.text })
        assertEquals("GOOD", chat.messages.last().feedback)
        assertEquals(listOf("Q", "First"), ServerHistory.chat(history("2")).messages.map { it.text })
        listOf("null", "99").forEach { missing ->
            val unsafe = ServerHistory.chat(history(missing))
            assertNull(unsafe.currentMessageId)
            assertTrue(unsafe.messages.isEmpty())
        }
    }

    @Test fun thisAppsAgentProtocolMessagesReadAsAConversation() {
        val request = "[Agent mode: instructions from the DeepSeek Android app]\\nTools:\\n- write_file(...)\\n\\nUser request:\\nMake a CSV"
        val history = Json.parseToJsonElement("""{"chat_session":{"id":"s","title":"T","updated_at":1.0,"current_message_id":4},"chat_messages":[
            {"message_id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"$request"}]},
            {"message_id":2,"parent_id":1,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"Calling write_file."}]},
            {"message_id":3,"parent_id":2,"role":"USER","fragments":[{"type":"REQUEST","content":"```tool_result\\n{}\\n```"}]},
            {"message_id":4,"parent_id":3,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"Done."}]}]}""").jsonObject
        val chat = ServerHistory.chat(history)
        assertEquals(listOf("Make a CSV", "Calling write_file.", "Done."), chat.messages.map { it.text })
        assertEquals(4L, chat.currentMessageId)
    }

    @Test fun pinsAndFeedbackUseTheLiveContracts() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(envelope("""{"chat_session_updated_at":1792533324.132}"""))
            server.enqueue(envelope("null"))
            server.enqueue(envelope("null"))
            client.setPinned(credentials, "session-1", true)
            client.sendFeedback(credentials, "session-1", 2, "BAD")
            client.sendFeedback(credentials, "session-1", 2, null)
            val pin = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v0/chat_session/update_pinned", pin.path)
            assertEquals("""{"chat_session_id":"session-1","pinned":true}""", pin.body.readUtf8())
            val like = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v0/chat/message_feedback", like.path)
            assertEquals("""{"chat_session_id":"session-1","message_id":2,"feedback_type":"BAD"}""", like.body.readUtf8())
            assertEquals("""{"chat_session_id":"session-1","message_id":2,"feedback_type":null}""", server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        }
    }

    @Test fun mergingTheListKeepsLocalChatsAddsRemoteOnesAndFollowsServerTitlesAndPins() {
        val sessions = listOf(
            ServerSession("s-new", "From the web", false, 3_000, "3.0"),
            ServerSession("s-known", "Generated title", true, 2_000, "2.0"),
        )
        val local = listOf(
            WebChat("a", "First prompt words", "s-known", 41, listOf(WebMessage("u", true, "Hi")), updatedAt = 1_000, syncedAt = 1_500),
            WebChat("b", "Only here", null, updatedAt = 500),
            WebChat("c", "Deleted elsewhere", "s-gone", 7, listOf(WebMessage("u", true, "Old")), updatedAt = 100),
        )
        var next = 0
        val merged = ServerHistory.merge(local, sessions, complete = false, openId = null) { "new-${next++}" }
        assertEquals(listOf("new-0", "a", "b", "c"), merged.map { it.id })
        val remote = merged.first()
        assertTrue(remote.remote)
        assertEquals("From the web", remote.title)
        assertEquals("s-new", remote.sessionId)
        val known = merged[1]
        assertEquals("Generated title", known.title)
        assertTrue(known.pinned)
        assertEquals(2_000L, known.remoteUpdatedAt)
        assertEquals("Local messages stay", 1, known.messages.size)
        // Only a complete list proves a chat was deleted elsewhere; then a plain chat goes and the open one turns read-only.
        val complete = ServerHistory.merge(local, sessions, complete = true, openId = null) { "x" }
        assertFalse(complete.any { it.id == "c" })
        val open = ServerHistory.merge(local, sessions, complete = true, openId = "c") { "x" }.single { it.id == "c" }
        assertTrue(open.blocked)
        assertNull(open.sessionId)
        assertEquals(merged, ServerHistory.merge(merged, sessions, complete = false, openId = null) { "never" })
    }
}
