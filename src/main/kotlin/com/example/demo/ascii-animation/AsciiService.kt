package com.example.demo.`ascii-animation`

import com.example.demo.deepseek.DeepSeekService
import com.example.demo.deepseek.HtmlAsJsonResponseDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

@Service
class AsciiService(
    private val deepSeekService: DeepSeekService
) {
    fun getCreative(): HtmlAsJsonResponseDto {
        val prompt = "Generate a piece of art, it can express emotion, feelings, situations, animated art pieces or anything political. orient on pieces of the medieval age"
        return deepSeekService.requestDeepseek(prompt)
    }

    fun generateAscii(prompt: String, referenceImages: List<MultipartFile> = emptyList(), unrestricted: Boolean = false): HtmlAsJsonResponseDto {
        if (!unrestricted && referenceImages.size > 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Wähle höchstens drei Referenzbilder.")
        }

        val context = StringBuilder(prompt.ifEmpty { "Erstelle eine Animation anhand der angehängten Dateien." })
        val imageDataUrls = mutableListOf<String>()
        referenceImages.forEachIndexed { index, file ->
            if (!unrestricted && file.size > 5 * 1024 * 1024) {
                throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Referenzbilder dürfen höchstens 5 MB gross sein.")
            }

            val bytes = file.bytes
            try {
                val mimeType = detectImageMimeType(bytes)
                when {
                    mimeType != null -> imageDataUrls.add("data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}")
                    unrestricted -> appendFileContext(context, file.originalFilename ?: "Datei ${index + 1}", bytes)
                    else -> throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Referenzbild ${index + 1} muss ein JPEG, PNG, GIF oder WebP sein."
                    )
                }
            } finally {
                bytes.fill(0)
            }
        }
        return deepSeekService.requestDeepseek(context.toString(), imageDataUrls)
    }

    private fun appendFileContext(context: StringBuilder, name: String, bytes: ByteArray) {
        val text = if (0.toByte() in bytes) null else try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        } catch (_: CharacterCodingException) {
            null
        }
        context.append("\n\n").append(name)
        if (text != null) {
            context.append(" (UTF-8)\n").append(text)
        } else {
            context.append(" (Base64)\n").append(Base64.getEncoder().encodeToString(bytes))
        }
    }

    private fun detectImageMimeType(bytes: ByteArray): String? {
        fun matchesAt(offset: Int, signature: ByteArray): Boolean =
            bytes.size >= offset + signature.size && signature.indices.all { bytes[offset + it] == signature[it] }

        return when {
            matchesAt(0, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "image/jpeg"
            matchesAt(0, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
            matchesAt(0, "GIF87a".encodeToByteArray()) || matchesAt(0, "GIF89a".encodeToByteArray()) -> "image/gif"
            matchesAt(0, "RIFF".encodeToByteArray()) && matchesAt(8, "WEBP".encodeToByteArray()) -> "image/webp"
            else -> null
        }
    }
}
