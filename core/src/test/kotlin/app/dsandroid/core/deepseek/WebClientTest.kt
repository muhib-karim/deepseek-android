package app.dsandroid.core.deepseek

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WebClientTest {
    private val credentials = WebCredentials(SessionToken.parse("synthetic-session")!!, "ds_session_id=synthetic-cookie")
    @Test fun requestsCarryBothCredentialsAndNextTurnUsesLatestParent() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_code":0,"biz_data":{"id":"chat-1"}}}"""))
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_code":0,"biz_data":{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"sig"}}}}"""))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"v\":{\"response_message_id\":12,\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"Hi\"}]}}}\n\ndata: [DONE]\n\n"))
            }
            val client = DeepSeekWebClient(OkHttpClient(), server.url("/"))
            val id = client.createSession(credentials)
            assertEquals("chat-1", id)
            val first = client.complete(credentials, CompletionInput(id, null, "Hello", true, false)) { 7 }.toList().last()
            assertEquals("Hi", first.response)
            client.complete(credentials, CompletionInput(id, first.responseMessageId, "Next", false, true)) { 8 }.toList()
            val requests = (1..5).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            requests.forEach {
                assertEquals("Bearer synthetic-session", it.getHeader("Authorization"))
                assertEquals("ds_session_id=synthetic-cookie", it.getHeader("Cookie"))
                assertEquals("web", it.getHeader("x-client-platform"))
                assertEquals("2.5.0", it.getHeader("x-client-version"))
                assertEquals("en_US", it.getHeader("x-client-locale"))
                assertEquals("com.deepseek.chat", it.getHeader("x-client-bundle-id"))
            }
            assertEquals("{}", requests[0].body.readUtf8())
            assertEquals("/api/v0/chat/create_pow_challenge", requests[1].path)
            assertEquals("/api/v0/chat/completion", requests[2].path)
            assertNotNull(requests[2].getHeader("X-DS-PoW-Response"))
            val next = Json.parseToJsonElement(requests[4].body.readUtf8()).jsonObject
            assertEquals(12, next["parent_message_id"]!!.jsonPrimitive.int)
            assertEquals(JsonArray(emptyList()), next["ref_file_ids"])
            assertEquals(JsonNull, next["action"])
            assertEquals(false, next["preempt"]!!.jsonPrimitive.boolean)
            assertEquals(true, next["search_enabled"]!!.jsonPrimitive.boolean)
        } finally { server.shutdown() }
    }
    @Test fun terminalEventFinishesWithoutWaitingForConnectionClose() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_code":0,"biz_data":{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abcdef","salt":"salt","difficulty":64,"expire_at":9999999999999,"signature":"sig"}}}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"v":{"response_message_id":2,"response":{"fragments":[{"type":"RESPONSE","content":"Done"}]}}}

data: [DONE]

""").setHeader("Content-Length", 1000000))
            val reply = withTimeout(3000) {
                DeepSeekWebClient(OkHttpClient(), server.url("/"))
                    .complete(credentials, CompletionInput("chat", null, "Hello", false, false)) { 1 }.toList().last()
            }
            assertEquals("Done", reply.response)
            assertTrue(reply.finished)
        } finally { server.shutdown() }
    }
    @Test fun http401AndBusiness401ExpireSessionWithoutLeakingBody() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            val client = DeepSeekWebClient(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-session"))
            try { client.createSession(credentials); fail("Expected expiry") } catch (e: SessionExpired) { assertFalse(e.toString().contains("synthetic-session")) }
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_code":401,"biz_msg":"synthetic-session"}}"""))
            try { client.createSession(credentials); fail("Expected expiry") } catch (_: SessionExpired) { }
        } finally { server.shutdown() }
    }
    @Test fun redirectsAreNeverFollowedWithSessionCredentials() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/stolen")))
            val client = DeepSeekWebClient(OkHttpClient(), server.url("/"))
            try { client.createSession(credentials); fail("Expected update error") } catch (_: UpdateRequired) { }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun birthdayGateIsReadFromCurrentUser() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":{"id":"u","need_birthday":true,"minor_mode_status":"NONE"}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":{"id":"u","need_birthday":false}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":40003,"msg":"Authorization Failed (invalid token)","data":null}"""))
            val client = DeepSeekWebClient(OkHttpClient(), server.url("/"))
            assertTrue(client.needsBirthday(credentials))
            assertFalse(client.needsBirthday(credentials))
            assertThrows(SessionExpired::class.java) { runBlocking { client.needsBirthday(credentials) } }
            val first = server.takeRequest()
            assertEquals("GET", first.method)
            assertEquals("/api/v0/users/current", first.path)
        } finally { server.shutdown() }
    }

    @Test fun currentUserIsTheSignInGateAndReadsOnlyContactFields() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":{"id":"u","token":"must-not-be-read","email":"t***@example.com","mobile_number":"","need_birthday":false}}}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_code":40003,"biz_data":null}}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody("not json"))
            val client = DeepSeekWebClient(OkHttpClient(), server.url("/"))
            val user = client.currentUser(credentials)
            assertEquals("t***@example.com", user.email)
            assertNull("A blank phone number is no phone number", user.phone)
            assertFalse(user.needBirthday)
            assertFalse("Debug strings never carry contact data", user.toString().contains("example.com"))
            assertEquals("Bearer synthetic-session", server.takeRequest().getHeader("Authorization"))
            // Rejected sessions never unlock: HTTP 401 and business code 40003 are both a session error.
            assertThrows(SessionExpired::class.java) { runBlocking { client.currentUser(credentials) } }
            assertThrows(SessionExpired::class.java) { runBlocking { client.currentUser(credentials) } }
            // Service trouble (a server error) or an unreadable answer is not a confirmation either.
            assertThrows(WebServiceBusy::class.java) { runBlocking { client.currentUser(credentials) } }
            assertThrows(EndpointChanged::class.java) { runBlocking { client.currentUser(credentials) } }
        } finally { server.shutdown() }
    }

    @Test fun anUnreachableServiceIsAConnectionFailureNotASignIn() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        val url = server.url("/")
        server.shutdown()
        val client = DeepSeekWebClient(OkHttpClient(), url)
        assertThrows(WebConnectionFailed::class.java) { runBlocking { client.currentUser(credentials) } }
    }
}
