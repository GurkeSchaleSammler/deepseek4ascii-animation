package com.example.demo

import com.example.demo.deepseek.AnimationSyntaxCheck
import com.example.demo.deepseek.DeepSeekClient
import com.example.demo.deepseek.DeepSeekService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@SpringBootTest(properties = ["ascii.backup-directory=target/test-animation-backups"])
@AutoConfigureMockMvc
class AsciiPreviewTests @Autowired constructor(
    private val mvc: MockMvc,
    private val deepSeekService: DeepSeekService,
    private val syntaxCheck: AnimationSyntaxCheck
) {
    @MockitoBean
    private lateinit var deepSeekClient: DeepSeekClient

    @Test
    fun `preview accepts a long prompt and adds the complete animation to the gallery`() {
        val prompt = "Leuchtturm mit Möwen!" + " Sturm".repeat(900)
        val systemPrompt = ClassPathResource("system-prompt.md").getContentAsString(Charsets.UTF_8)
        val html = "<!doctype html><html><head><title>Leuchtturm</title><style>pre{color:red}</style></head>" +
            "<body class=\"scene\"><pre>ASCII</pre><script>const frame = 1;</script></body></html>"
        val backupDirectory = Files.createDirectories(Path.of("target/test-animation-backups"))
        val existingBackups = Files.list(backupDirectory).use { it.toList().toSet() }
        `when`(deepSeekClient.generate(prompt, systemPrompt))
            .thenReturn("```html\n$html\n```")

        mvc.get("/").andExpect {
            status { isOk() }
            forwardedUrl("index.html")
        }
        mvc.get("/index.html").andExpect {
            status { isOk() }
            content { string(containsString("<app-root>")) }
        }
        val token = checkNotNull(mvc.get("/ascii/csrf").andExpect {
            status { isNoContent() }
            header { string("Cache-Control", "no-store") }
        }.andReturn().response.getCookie("XSRF-TOKEN"))

        mvc.post("/ascii/custom") {
            cookie(token)
            header("X-XSRF-TOKEN", token.value)
            contentType = MediaType.APPLICATION_JSON
            content = """{"prompt":"$prompt"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.html") { value("<pre>ASCII</pre>") }
            jsonPath("$.css") { value("pre{color:red}") }
            jsonPath("$.javaScript") { value("const frame = 1;") }
            jsonPath("$.previewHtml") { value(containsString("<body class=\"scene\">")) }
        }

        val backup = Files.list(backupDirectory).use { (it.toList().toSet() - existingBackups).single() }
        assertEquals("html", backup.fileName.toString().substringAfterLast('.'))
        assertEquals(html, Files.readString(backup))

        mvc.get("/ascii/gallery").andExpect {
            status { isOk() }
            jsonPath("$[?(@.id == '${backup.fileName}')].title") { value(hasItem("Leuchtturm")) }
        }
        mvc.get("/ascii/gallery/{id}", backup.fileName.toString()).andExpect {
            status { isOk() }
            jsonPath("$.previewHtml") { value(containsString("<script>const frame = 1;</script>")) }
        }
        mvc.get("/ascii/gallery/missing.html").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a syntax error is repaired before the animation is archived`() {
        val invalid = "<pre>ASCII</pre><script>const frame = -;</script>"
        val valid = "<pre>ASCII</pre><script>const frame = '-';</script>"
        assumeTrue(syntaxCheck.errorIn(invalid) != null, "Node.js is required for syntax validation")
        val backupDirectory = Files.createDirectories(Path.of("target/test-animation-backups"))
        val existingBackups = Files.list(backupDirectory).use { it.toList().toSet() }
        `when`(deepSeekClient.generate(anyString(), anyString(), anyString())).thenReturn(invalid, valid)

        assertEquals("const frame = '-';", deepSeekService.requestDeepseek("Fish").javaScript)
        val backup = Files.list(backupDirectory).use { (it.toList().toSet() - existingBackups).single() }
        assertEquals(valid, Files.readString(backup))
    }

    @Test
    fun `random request requires a CSRF token`() {
        mvc.post("/ascii/random").andExpect { status { isForbidden() } }
    }

    @Test
    fun `three uploaded references reach DeepSeek and only the generated HTML is archived`() {
        val prompt = "Leuchtturm aus den Referenzen"
        val systemPrompt = ClassPathResource("system-prompt.md").getContentAsString(Charsets.UTF_8)
        val html = "<html><body><pre>ASCII lighthouse</pre></body></html>"
        val backupDirectory = Files.createDirectories(Path.of("target/test-animation-backups"))
        val existingBackups = Files.list(backupDirectory).use { it.toList().toSet() }
        `when`(deepSeekClient.generateWithReferences(prompt, systemPrompt, List(3) { "data:image/png;base64,$PNG" }))
            .thenReturn(html)

        submitReferences(prompt, referencePng(), referencePng(), referencePng()).andExpect {
            status { isCreated() }
            jsonPath("$.html") { value("<pre>ASCII lighthouse</pre>") }
        }

        val backup = Files.list(backupDirectory).use { (it.toList().toSet() - existingBackups).single() }
        assertEquals(html, Files.readString(backup))
    }

    @Test
    fun `invalid references are rejected before calling DeepSeek`() {
        val invalid = MockMultipartFile("referenceImages", "fake.png", "image/png", "text".encodeToByteArray())
        submitReferences("Motiv", invalid).andExpect { status { isBadRequest() } }
        submitReferences("Motiv", *Array(4) { referencePng() }).andExpect { status { isBadRequest() } }
        verifyNoInteractions(deepSeekClient)
    }

    @Test
    fun `a response containing the uploaded image is not archived`() {
        val systemPrompt = ClassPathResource("system-prompt.md").getContentAsString(Charsets.UTF_8)
        val dataUrl = "data:image/png;base64,$PNG"
        val backupDirectory = Files.createDirectories(Path.of("target/test-animation-backups"))
        val existingBackups = Files.list(backupDirectory).use { it.toList().toSet() }
        `when`(deepSeekClient.generateWithReferences("Motiv", systemPrompt, listOf(dataUrl)))
            .thenReturn("<html><body><img src=\"$dataUrl\"></body></html>")

        submitReferences("Motiv", referencePng()).andExpect { status { isBadGateway() } }
        assertEquals(existingBackups, Files.list(backupDirectory).use { it.toList().toSet() })
    }

    @Test
    fun `unrestricted uploads accept mixed context without a prompt`() {
        val text = MockMultipartFile("referenceImages", "scene.md", "text/markdown", "Fisch im See".toByteArray())
        val binary = MockMultipartFile("referenceImages", "scene.bin", null, byteArrayOf(0, 1))
        val expected = "Erstelle eine Animation anhand der angehängten Dateien." +
            "\n\nscene.md (UTF-8)\nFisch im See\n\nscene.bin (Base64)\nAAE="
        val system = ClassPathResource("system-prompt.md").getContentAsString(Charsets.UTF_8)
        `when`(deepSeekClient.generateWithReferences(expected, system, List(2) { "data:image/png;base64,$PNG" }))
            .thenReturn("<html><body><pre>Fish</pre></body></html>")

        submitReferences("", text, referencePng(), binary, referencePng(), unrestricted = true)
            .andExpect { status { isCreated() } }
        submitReferences("", unrestricted = true).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `only unrestricted mode accepts a reference larger than five MiB`() {
        val bytes = Base64.getDecoder().decode(PNG).copyOf(5 * 1024 * 1024 + 1)
        val image = MockMultipartFile("referenceImages", "large.png", "image/png", bytes)
        val system = ClassPathResource("system-prompt.md").getContentAsString(Charsets.UTF_8)
        val dataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
        `when`(deepSeekClient.generateWithReferences("See", system, listOf(dataUrl)))
            .thenReturn("<html><body><pre>See</pre></body></html>")
        submitReferences("See", image).andExpect { status { isPayloadTooLarge() } }
        submitReferences("See", image, unrestricted = true).andExpect { status { isCreated() } }
    }

    private fun submitReferences(prompt: String, vararg references: MockMultipartFile, unrestricted: Boolean = false) =
        mvc.multipart("/ascii/custom") {
            val token = checkNotNull(mvc.get("/ascii/csrf").andReturn().response.getCookie("XSRF-TOKEN"))
            cookie(token)
            header("X-XSRF-TOKEN", token.value)
            param("prompt", prompt)
            if (unrestricted) param("unrestricted", "true")
            references.forEach { file(it) }
        }

    private fun referencePng() = MockMultipartFile(
        "referenceImages", "sample.png", "image/png", Base64.getDecoder().decode(PNG)
    )

    companion object {
        private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/FfsAAAAASUVORK5CYII="
    }
}
