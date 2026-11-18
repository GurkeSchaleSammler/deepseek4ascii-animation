package com.example.demo.deepseek

import com.example.demo.`ascii-animation`.AnimationArchive
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.IOException

@Service
class DeepSeekService(
    private val deepSeekClient: DeepSeekClient,
    private val animationArchive: AnimationArchive,
    private val syntaxCheck: AnimationSyntaxCheck,
    @Value("classpath:system-prompt.md") systemPromptResource: Resource
) {
    private val systemPrompt = systemPromptResource.getContentAsString(Charsets.UTF_8)

    fun requestDeepseek(prompt: String, imageDataUrls: List<String> = emptyList()): HtmlAsJsonResponseDto {
        val response = if (imageDataUrls.isEmpty()) {
            deepSeekClient.generate(prompt, systemPrompt)
        } else {
            deepSeekClient.generateWithReferences(prompt, systemPrompt, imageDataUrls)
        }
        val initialHtml = unwrapHtml(response)
        val error = checkGeneratedHtml(initialHtml, imageDataUrls)
        val html = if (error == null) initialHtml else repair(initialHtml, error, imageDataUrls)
        animationArchive.save(html)
        return mapHtmlToResponseDto(html)
    }

    private fun repair(html: String, error: String, imageDataUrls: List<String>): String {
        val repairPrompt = "Correct the JavaScript syntax error in the HTML animation below. " +
            "Preserve the artwork and return the complete standalone HTML document.\n" +
            "Syntax diagnostic: $error\n\n$html"
        val repairedHtml = unwrapHtml(deepSeekClient.generate(repairPrompt, systemPrompt))
        checkGeneratedHtml(repairedHtml, imageDataUrls)?.let {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek konnte den JavaScript-Fehler nicht beheben: $it")
        }
        return repairedHtml
    }

    private fun checkGeneratedHtml(html: String, imageDataUrls: List<String>): String? {
        if (imageDataUrls.isNotEmpty() && (html.contains("data:image/", ignoreCase = true) ||
                imageDataUrls.any { html.contains(it.substringAfter(',')) })) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek hat Bilddaten in die Animation eingebettet.")
        }
        return checkSyntax(html)
    }

    private fun checkSyntax(html: String): String? = try {
        syntaxCheck.errorIn(html)
    } catch (exception: IOException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Die JavaScript-Syntaxprüfung konnte nicht ausgeführt werden.", exception)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Die JavaScript-Syntaxprüfung wurde unterbrochen.", exception)
    }
}
