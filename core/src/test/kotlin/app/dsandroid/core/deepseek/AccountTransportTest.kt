package app.dsandroid.core.deepseek

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Wire fixtures exercise the HTTP boundary without an account or a live service. */
class AccountTransportTest {
    private val credentials = WebCredentials(SessionToken.parse("synthetic-session")!!, "ds_session_id=synthetic-cookie")
    private val expiry = "Your DeepSeek session expired. Please sign in again."
    private val changed = "DeepSeek's web service changed and this app can't talk to it right now. Try again later, update the app, or switch to API-key mode in Settings."

    @Test fun renameAndDeleteUseRemoteSessionIdsAndAuthenticatedJson() = runBlocking<Unit> {
        withServer { server, client ->
            repeat(2) { server.enqueue(envelope("{}")) }
            client.renameSession(credentials, "server-session", "Renamed conversation")
            client.deleteSession(credentials, "server-session")
            val rename = server.takeRequest(1, TimeUnit.SECONDS)!!
            val delete = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v0/chat_session/update_title", rename.path)
            assertEquals("POST", rename.method)
            assertEquals("""{"chat_session_id":"server-session","title":"Renamed conversation"}""", rename.body.readUtf8())
            assertEquals("/api/v0/chat_session/delete", delete.path)
            assertEquals("""{"chat_session_id":"server-session"}""", delete.body.readUtf8())
            listOf(rename, delete).forEach {
                assertEquals("Bearer synthetic-session", it.getHeader("Authorization"))
                assertEquals("ds_session_id=synthetic-cookie", it.getHeader("Cookie"))
            }
        }
    }

    @Test fun mutationsAcceptExplicitNullAcknowledgementsButStillRequireTheEnvelope() = runBlocking<Unit> {
        withServer { server, client ->
            repeat(2) { server.enqueue(envelope("null")) }
            client.renameSession(credentials, "session", "Title")
            client.deleteSession(credentials, "session")
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_code":0}}"""))
            expectChanged { client.deleteSession(credentials, "session") }
            server.enqueue(envelope("null"))
            expectChanged { client.createSession(credentials) }
        }
    }

    @Test fun uploadUsesMultipartBytesNameAndMimeAndAcceptsBothIdShapes() = runBlocking<Unit> {
        withServer { server, client ->
            val file = Files.createTempFile("account-upload", ".bin").toFile()
            try {
                file.writeBytes(byteArrayOf(0, 1, 2, 10, 65, 66))
                listOf("""{"id":"file-direct"}""", """{"file":{"id":"file-nested"}}""").forEachIndexed { index, body ->
                    server.enqueue(envelope(body))
                    assertEquals(if (index == 0) "file-direct" else "file-nested", client.uploadFile(credentials, file, "original.pdf", "application/pdf"))
                    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("/api/v0/file/upload_file", request.path)
                    assertEquals("POST", request.method)
                    assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
                    assertEquals("Bearer synthetic-session", request.getHeader("Authorization"))
                    assertEquals("ds_session_id=synthetic-cookie", request.getHeader("Cookie"))
                    val wire = request.body.readUtf8()
                    assertTrue(wire.contains("name=\"file\"; filename=\"original.pdf\""))
                    assertTrue(wire.contains("Content-Type: application/pdf"))
                    assertTrue(wire.contains(String(file.readBytes(), Charsets.UTF_8)))
                }
            } finally { file.delete() }
        }
    }

    @Test fun regenerateAndEditedBranchesKeepExplicitPriorAssistantAndModeAndFileReferences() = runBlocking<Unit> {
        withServer { server, client ->
            val inputs = listOf(
                CompletionInput("session", 20L, "Original prompt", true, false, listOf("file-1")),
                CompletionInput("session", 20L, "Edited prompt", false, true, listOf("file-2", "file-3")),
            )
            inputs.forEach { input ->
                enqueueCompletion(server)
                val reply = client.complete(credentials, input) { 1 }.toList().last()
                assertEquals(21L, reply.requestMessageId)
                assertEquals(22L, reply.responseMessageId)
                server.takeRequest(1, TimeUnit.SECONDS)!!
                val body = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
                assertEquals(20L, body["parent_message_id"]!!.jsonPrimitive.long)
                assertEquals(input.prompt, body["prompt"]!!.jsonPrimitive.content)
                assertEquals(input.thinking, body["thinking_enabled"]!!.jsonPrimitive.boolean)
                assertEquals(input.search, body["search_enabled"]!!.jsonPrimitive.boolean)
                assertEquals(input.refFileIds, body["ref_file_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(JsonNull, body["action"])
            }
        }
    }

    @Test fun everySessionExpiryEnvelopeLocationHasTheExactSafeMessage() = runBlocking<Unit> {
        withServer { server, client ->
            listOf(40001, 40002, 40003).forEach { code ->
                listOf(
                    """{"code":$code,"msg":"synthetic-session"}""",
                    """{"code":0,"data":{"biz_code":$code,"biz_msg":"synthetic-session"}}""",
                ).forEach { body ->
                    server.enqueue(MockResponse().setBody(body))
                    val error = try { client.createSession(credentials); null } catch (error: SessionExpired) { error }
                    assertNotNull(error)
                    assertEquals(expiry, error!!.message)
                    assertNull(error.cause)
                }
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-session"))
            val error = try { client.createSession(credentials); null } catch (error: SessionExpired) { error }
            assertEquals(expiry, error!!.message)
        }
    }

    @Test fun unexpectedHttpAndMalformedRequiredEnvelopesHaveTheExactSafeMessage() = runBlocking<Unit> {
        withServer { server, client ->
            listOf(302, 404, 410, 500, 503).forEach { code ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("synthetic-session"))
                expectChanged { client.createSession(credentials) }
            }
            listOf(
                "synthetic-session", "[]", "{}", """{"data":{"biz_code":0,"biz_data":{"id":"wrong"}}}""",
                """{"code":0,"data":{"biz_data":{"id":"wrong"}}}""",
                """{"code":0,"data":{"biz_code":0,"biz_data":[]}}""",
                """{"code":0,"data":{"biz_code":0,"biz_data":{"id":null}}}""",
                """{"code":0,"data":{"biz_code":0,"biz_data":{"id":42}}}""",
            ).forEach { body ->
                server.enqueue(MockResponse().setBody(body))
                expectChanged { client.createSession(credentials) }
            }
        }
    }

    @Test fun malformedUploadShapeAndChallengeCannotBecomeSuccess() = runBlocking<Unit> {
        withServer { server, client ->
            val file = Files.createTempFile("bad-upload", ".txt").toFile()
            try {
                listOf("{}", """{"id":null}""", """{"file":{"id":false}}""").forEach {
                    server.enqueue(envelope(it))
                    expectChanged { client.uploadFile(credentials, file, "file.txt", "text/plain") }
                }
            } finally { file.delete() }
            listOf("{}", """{"challenge":{}}""", """{"challenge":{"algorithm":"unknown","challenge":"x","salt":"x","difficulty":1,"expire_at":9999999999999,"signature":"x"}}""").forEach {
                server.enqueue(envelope(it))
                expectChanged { client.complete(credentials, CompletionInput("s", null, "Hello", false, false)) { 0 }.toList() }
            }
        }
    }

    @Test fun malformedStreamAndBusinessExpiryAreTypedThroughTheTransport() = runBlocking<Unit> {
        withServer { server, client ->
            listOf(40001, 40002, 40003).forEach { code ->
                listOf("""{"code":$code,"message":"synthetic-session"}""", """{"data":{"biz_code":$code,"biz_msg":"synthetic-session"}}""").forEach { event ->
                    server.enqueue(challenge())
                    server.enqueue(sse("data: $event\n\n"))
                    val error = try {
                        client.complete(credentials, CompletionInput("s", null, "Hello", false, false)) { 1 }.toList(); null
                    } catch (error: SessionExpired) { error }
                    assertEquals(expiry, error!!.message)
                }
            }
            listOf("data: {bad}\n\n", "data: {\"unknown\":true}\n\ndata: [DONE]\n\n", "data: {\"v\":{\"response_message_id\":22}}\n\n").forEach { stream ->
                server.enqueue(challenge()); server.enqueue(sse(stream))
                expectChanged { client.complete(credentials, CompletionInput("s", null, "Hello", false, false)) { 1 }.toList() }
            }
        }
    }

    @Test fun anHttpBodyTruncatedDuringAStreamIsATypedProtocolFailure() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(challenge())
            server.enqueue(sse("data: {\"v\":{\"response_message_id\":22}}\n\n")
                .setHeader("Content-Length", 1000000).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            expectChanged { client.complete(credentials, CompletionInput("s", null, "Hi", false, false)) { 1 }.toList() }
        }
    }

    @Test fun allNewRemoteActionsPropagateSessionExpiry() = runBlocking<Unit> {
        withServer { server, client ->
            val file = Files.createTempFile("expired-upload", ".txt").toFile()
            try {
                val actions: List<suspend () -> Any?> = listOf(
                    { client.renameSession(credentials, "session", "Title") },
                    { client.deleteSession(credentials, "session") },
                    { client.uploadFile(credentials, file, "file.txt", "text/plain") },
                )
                actions.forEach { action ->
                    server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-session"))
                    val error = try { action(); null } catch (error: SessionExpired) { error }
                    assertEquals(expiry, error!!.message)
                }
            } finally { file.delete() }
        }
    }

    @Test fun cancellationClosesAnInitialRequestWithoutWaitingForReadTimeout() = runBlocking<Unit> {
        cancellationClosesBlockingCall(SocketPolicy.NO_RESPONSE, false)
    }

    @Test fun cancellationClosesABlockedStreamAndAllowsTheNextRequest() = runBlocking<Unit> {
        cancellationClosesBlockingCall(SocketPolicy.KEEP_OPEN, true)
    }

    private suspend fun cancellationClosesBlockingCall(policy: SocketPolicy, streaming: Boolean) = coroutineScope {
        val failed = CountDownLatch(1)
        val callErrors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val network = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: java.io.IOException) { callErrors.add("${call.request().url.encodedPath}: $ioe"); failed.countDown() }
            // A cancelled stream whose body is closed while suspended between events ends via callEnd; either way the call is closed.
            override fun callEnd(call: Call) { if (call.request().url.encodedPath.endsWith("/chat/completion")) failed.countDown() }
        }).build()
        // Bind and dial one IPv4 address: a cancelled call marks its route failed in OkHttp, and "localhost" would then fall to an unbound ::1.
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val server = MockWebServer(); server.start(loopback, 0)
        try {
            val client = DeepSeekWebClient(network, server.url("/").newBuilder().host("127.0.0.1").build())
            if (streaming) {
                server.enqueue(challenge())
                server.enqueue(sse("data: {\"v\":{\"response_message_id\":22}}\n\n").setHeader("Content-Length", 1000000).setSocketPolicy(policy))
            } else server.enqueue(MockResponse().setSocketPolicy(policy))
            val startedReading = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                if (streaming) client.complete(credentials, CompletionInput("s", null, "Hi", false, false)) { 1 }.collect { startedReading.complete(Unit) }
                else client.createSession(credentials)
            }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            if (streaming) {
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
                withTimeout(3000) { startedReading.await() }
            }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue("Cancelled HTTP call must close promptly", withContext(Dispatchers.IO) { failed.await(2, TimeUnit.SECONDS) })
            server.enqueue(envelope("""{"id":"next-session"}"""))
            val next = try { withTimeout(3000) { client.createSession(credentials) } } catch (error: WebConnectionFailed) { fail("next request failed; call failures: $callErrors; served: ${server.requestCount}") }
            assertEquals("next-session", next)
        } finally { server.shutdown() }
    }

    private suspend fun expectChanged(action: suspend () -> Any?) {
        val error = try { action(); null } catch (error: EndpointChanged) { error }
        assertNotNull(error)
        assertEquals(changed, error!!.message)
        assertNull(error.cause)
        assertTrue(error is UpdateRequired)
    }
    private suspend fun withServer(block: suspend (MockWebServer, DeepSeekWebClient) -> Unit) {
        val server = MockWebServer(); server.start()
        try { block(server, DeepSeekWebClient(OkHttpClient(), server.url("/"))) } finally { server.shutdown() }
    }
    private fun envelope(data: String) = MockResponse().setBody("""{"code":0,"data":{"biz_code":0,"biz_data":$data}}""")
    private fun challenge() = envelope("""{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"sig"}}""")
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun enqueueCompletion(server: MockWebServer) {
        server.enqueue(challenge())
        server.enqueue(sse("data: {\"v\":{\"request_message_id\":21,\"response_message_id\":22,\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"Answer\"}]}}}\n\ndata: [DONE]\n\n"))
    }
}
