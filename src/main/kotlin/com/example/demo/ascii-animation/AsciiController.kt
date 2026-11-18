package com.example.demo.`ascii-animation`

import com.example.demo.deepseek.HtmlAsJsonResponseDto
import com.example.demo.deepseek.mapHtmlToResponseDto
import jakarta.servlet.http.HttpServletRequest
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Tag(name = "AsciiController")
@RestController
@RequestMapping("/ascii")
class AsciiController(
    private val asciiService: AsciiService,
    private val animationArchive: AnimationArchive
) {
    @GetMapping("/gallery")
    fun gallery(): List<GalleryEntry> = animationArchive.list()

    @GetMapping("/gallery/{id}")
    fun galleryAnimation(@PathVariable id: String): HtmlAsJsonResponseDto =
        mapHtmlToResponseDto(animationArchive.read(id))

    @GetMapping("/csrf")
    fun prepareRequest(csrfToken: CsrfToken): ResponseEntity<Void> {
        csrfToken.token
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @PostMapping("/random")
    fun generateCreativeAsciiAnimation(): ResponseEntity<HtmlAsJsonResponseDto> {
        val responseHtml = asciiService.getCreative()
        return ResponseEntity.status(HttpStatus.CREATED).body(responseHtml)
    }

    @PostMapping("/custom", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun generateCustomAsciiAnimation(
        @RequestBody request: PromptRequestDto
    ): ResponseEntity<HtmlAsJsonResponseDto> = generateCustom(request.prompt)

    @PostMapping("/custom", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun generateCustomAsciiAnimationWithReferences(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "") prompt: String,
        @RequestParam(defaultValue = "false") unrestricted: Boolean,
        @RequestParam(required = false) referenceImages: List<MultipartFile>?
    ): ResponseEntity<HtmlAsJsonResponseDto> {
        if (!unrestricted && (request.contentLengthLong > 16L * 1024 * 1024 ||
                request.parts.sumOf { it.size } > 16L * 1024 * 1024)) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Der Upload darf höchstens 16 MB gross sein.")
        }
        return generateCustom(prompt, referenceImages.orEmpty(), unrestricted)
    }

    private fun generateCustom(
        prompt: String,
        referenceImages: List<MultipartFile> = emptyList(),
        unrestricted: Boolean = false
    ): ResponseEntity<HtmlAsJsonResponseDto> {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty() && !(unrestricted && referenceImages.isNotEmpty())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Gib einen Prompt ein.")
        }

        val responseHtml = asciiService.generateAscii(trimmedPrompt, referenceImages, unrestricted)
        return ResponseEntity.status(HttpStatus.CREATED).body(responseHtml)
    }
}
