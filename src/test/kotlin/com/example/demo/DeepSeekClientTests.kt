package com.example.demo

import com.example.demo.deepseek.DeepSeekClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.Flow

class DeepSeekClientTests {
    private val mapper = JsonMapper.builder().build()
    private val requests = mutableListOf<JsonNode>()
    private val replies = ArrayDeque<Pair<Int, String>>()

    @Test
    fun `complete output is returned with an explicit larger token budget`() {
        reply("stop", "<html>complete</html>")

        assertEquals("<html>complete</html>", client().generate("Train", "System"))
        assertEquals(1, requests.size)
        assertEquals(131072, requests.single().path("max_tokens").intValue())
        assertEquals("deepseek-v4-pro", requests.single().path("model").stringValue())
    }

    @Test
    fun `truncated output is regenerated once without concatenating partial code`() {
        reply("length", "<html><script>const broken =")
        reply("stop", "<html>complete replacement</html>")

        assertEquals("<html>complete replacement</html>", client().generate("Train", "System"))
        assertEquals(2, requests.size)
        assertEquals(262144, requests[1].path("max_tokens").intValue())
        val messages = requests[1].path("messages")
        assertEquals("System", messages.path(0).path("content").stringValue())
        assertEquals("Train", messages.path(1).path("content").stringValue())
        assertFalse(messages.toString().contains("const broken"))
    }

    @Test
    fun `reference images and model survive a reasoning-only truncated response`() {
        reply("length", "")
        reply("stop", "<html>train</html>")
        val image = "data:image/png;base64,reference"

        assertEquals("<html>train</html>", client().generateWithReferences("Train", "System", listOf(image)))
        assertEquals(2, requests.size)
        assertEquals(requests[0].path("messages").path(1), requests[1].path("messages").path(1))
        assertEquals("deepseek-flash", requests[1].path("model").stringValue())
        assertEquals(image, requests[1].path("messages").path(1).path("content")
            .path(1).path("image_url").path("url").stringValue())
    }

    @Test
    fun `repeated truncation stops after two attempts and respects the provider limit`() {
        reply("length", "partial one")
        reply("length", "partial two")

        val error = assertThrows(ResponseStatusException::class.java) {
            client(300000).generate("Train", "System")
        }

        assertEquals(HttpStatus.BAD_GATEWAY, error.statusCode)
        assertTrue(error.reason.orEmpty().contains("zweiten Versuch"))
        assertEquals(2, requests.size)
        assertEquals(393216, requests[1].path("max_tokens").intValue())
    }

    @Test
    fun `provider errors are not retried`() {
        replies.add(429 to "{}")

        val error = assertThrows(ResponseStatusException::class.java) {
            client().generate("Train", "System")
        }

        assertEquals(HttpStatus.BAD_GATEWAY, error.statusCode)
        assertTrue(error.reason.orEmpty().contains("429"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `empty successful output remains an error`() {
        reply("stop", "")

        val error = assertThrows(ResponseStatusException::class.java) {
            client().generate("Train", "System")
        }

        assertEquals("DeepSeek hat kein HTML geliefert.", error.reason)
        assertEquals(1, requests.size)
    }

    private fun client(maxTokens: Int = 131072): DeepSeekClient {
        val httpClient = mock(HttpClient::class.java)
        `when`(httpClient.send(any(HttpRequest::class.java), any<HttpResponse.BodyHandler<String>>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<HttpRequest>(0)
                val subscriber = HttpResponse.BodySubscribers.ofString(Charsets.UTF_8)
                request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) = subscriber.onSubscribe(subscription)
                    override fun onNext(item: ByteBuffer) = subscriber.onNext(listOf(item))
                    override fun onError(error: Throwable) = subscriber.onError(error)
                    override fun onComplete() = subscriber.onComplete()
                })
                requests.add(mapper.readTree(subscriber.body.toCompletableFuture().get()))
                val (status, body) = replies.removeFirst()
                @Suppress("UNCHECKED_CAST")
                val response = mock(HttpResponse::class.java) as HttpResponse<String>
                `when`(response.statusCode()).thenReturn(status)
                `when`(response.body()).thenReturn(body)
                response
            }
        return DeepSeekClient("test-key", mapper, maxOutputTokens = maxTokens).also {
            ReflectionTestUtils.setField(it, "client", httpClient)
        }
    }

    private fun reply(finishReason: String, content: String) {
        replies.add(200 to mapper.writeValueAsString(mapOf(
            "choices" to listOf(mapOf(
                "finish_reason" to finishReason,
                "message" to mapOf("content" to content)
            ))
        )))
    }
}
