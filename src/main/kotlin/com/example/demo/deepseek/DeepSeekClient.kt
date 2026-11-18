package com.example.demo.deepseek

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

@Component
class DeepSeekClient(
    @param:Value("\${deepseek-apikey}")
    private val deepSeekApiKey: String,
    private val mapper: JsonMapper,
    @param:Value("\${deepseek.max-output-tokens:131072}")
    private val maxOutputTokens: Int = 131072
) {
    private val baseUrl = "https://api.deepseek.com"

    init {
        require(maxOutputTokens in 1..393216) { "deepseek.max-output-tokens must be between 1 and 393216" }
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun generate(
        prompt: String,
        systemPrompt: String,
        model: String = "deepseek-v4-pro"
    ): String = complete(prompt, systemPrompt, model)

    fun generateWithReferences(prompt: String, systemPrompt: String, imageDataUrls: List<String>): String {
        val content = buildList {
            add(mapOf("type" to "text", "text" to prompt))
            imageDataUrls.forEach { dataUrl ->
                add(mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUrl)))
            }
        }
        return complete(content, systemPrompt, "deepseek-flash")
    }

    private fun complete(
        userContent: Any,
        systemPrompt: String,
        model: String
    ): String {
        if (deepSeekApiKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DEEPSEEK_API_KEY fehlt auf dem Server.")
        }

        val messages = mutableListOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userContent)
        )
        var tokenBudget = maxOutputTokens
        repeat(2) { attempt ->
            val choice = request(messages, model, tokenBudget).path("choices").path(0)
            if (choice.path("finish_reason").stringValue("") != "length") {
                return choice.path("message").path("content").stringValue("").takeIf { it.isNotBlank() }
                    ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek hat kein HTML geliefert.")
            }
            if (attempt == 0) {
                tokenBudget = (tokenBudget * 2).coerceAtMost(393216)
                messages.add(mapOf(
                    "role" to "user",
                    "content" to "The previous generation exceeded the output token budget. " +
                        "Generate the complete standalone HTML document from scratch. " +
                        "Keep the original subject and reference images. Use compact procedural code, " +
                        "avoid verbose reasoning, repetition and precomputed frames, and finish all " +
                        "JavaScript and HTML within this response. Return only the complete HTML."
                ))
            }
        }
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "DeepSeek hat die Animation auch beim zweiten Versuch abgeschnitten. " +
                "Bitte reduziere die Anzahl Szenen oder Details im Prompt."
        )
    }

    private fun request(messages: List<Map<String, Any>>, model: String, tokenBudget: Int): JsonNode {
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to tokenBudget,
                "stream" to false
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Authorization", "Bearer $deepSeekApiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "DeepSeek hat zu lange gebraucht.", exception)
        } catch (exception: IOException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek ist gerade nicht erreichbar.", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Die Anfrage wurde unterbrochen.", exception)
        }

        if (response.statusCode() !in 200..299) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "DeepSeek hat mit HTTP ${response.statusCode()} geantwortet."
            )
        }

        return try {
            mapper.readTree(response.body())
        } catch (exception: JacksonException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek hat ungültiges JSON geliefert.", exception)
        }
    }
}
