package com.example.demo.deepseek

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.DocumentType

class HtmlAsJsonResponseDto(
    val html: String?,
    val css: String?,
    val javaScript: String?,
    val previewHtml: String
)

private val fencedHtml = Regex("(?im)^[\\t ]*```(?:html)?[\\t ]*\\r?\\n([\\s\\S]*?)\\r?\\n[\\t ]*```[\\t ]*$")

internal fun unwrapHtml(responseHtml: String): String =
    (fencedHtml.find(responseHtml)?.groupValues?.get(1) ?: responseHtml).trim()

fun mapHtmlToResponseDto(responseHtml: String): HtmlAsJsonResponseDto {
    val document = Jsoup.parse(unwrapHtml(responseHtml))

    val css = document.select("style")
        .joinToString("\n") { it.data() }
        .takeIf { it.isNotBlank() }

    val javaScript = document.select("script:not([src])")
        .joinToString("\n") { it.data() }
        .takeIf { it.isNotBlank() }

    val body = document.body().clone().apply { select("style, script").remove() }.html()

    document.outputSettings().prettyPrint(false)
    if (document.documentType() == null) {
        document.prependChild(DocumentType("html", "", ""))
    }
    document.select("base, meta[http-equiv=refresh], meta[http-equiv=content-security-policy]").remove()
    document.head().prependElement("script").appendChild(DataNode("""
        (() => {
            const report = (message) => parent.postMessage({type: 'ascii-preview-error', message: String(message)}, '*');
            window.addEventListener('error', (event) => {
                if (event instanceof ErrorEvent) report(event.message);
            });
            window.addEventListener('unhandledrejection', (event) => report(event.reason));
        })();
    """.trimIndent()))
    document.head().prependElement("style").appendChild(DataNode("html,body{background:#000;color:#eee}"))
    document.head().prependElement("meta")
        .attr("http-equiv", "Content-Security-Policy")
        .attr("content", "default-src 'none'; script-src 'unsafe-inline' data:; style-src 'unsafe-inline' data:; img-src data: blob:; base-uri 'none'; form-action 'none'")

    return HtmlAsJsonResponseDto(
        html = body,
        css = css,
        javaScript = javaScript,
        previewHtml = document.outerHtml()
    )
}
