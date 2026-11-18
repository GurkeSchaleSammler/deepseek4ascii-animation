package com.example.demo.deepseek

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Component
class AnimationSyntaxCheck(
    private val mapper: JsonMapper,
    @param:Value("\${ascii.node-command:}") private val nodeCommand: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executable by lazy {
        findNode().also {
            if (it == null) logger.warn("Node.js is unavailable. JavaScript syntax checks are disabled for generated animations.")
        }
    }

    fun errorIn(html: String): String? {
        val scripts = Jsoup.parse(html).select("script:not([src])").mapNotNull { script ->
            val type = script.attr("type").substringBefore(';').trim().lowercase()
            when (type) {
                "module" -> mapOf("source" to script.data(), "module" to true)
                "", "text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript" ->
                    mapOf("source" to script.data(), "module" to false)
                else -> null
            }
        }
        if (scripts.isEmpty()) return null
        val node = executable ?: return null
        val process = ProcessBuilder(node, "--experimental-vm-modules", "-e", NODE_SYNTAX_CHECK)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            process.outputStream.use { it.write(mapper.writeValueAsBytes(scripts)) }
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw IOException("JavaScript syntax check timed out")
            if (process.exitValue() != 0) throw IOException("JavaScript syntax check failed")
            return process.inputStream.bufferedReader().use { it.readText() }.ifBlank { null }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun findNode(): String? {
        if (nodeCommand.isNotBlank()) return nodeCommand
        val fromPath = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it, "node") }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        if (fromPath != null) return fromPath.toString()

        val nvmHome = System.getenv("NVM_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".nvm")
        val nvmDirectory = nvmHome.resolve("versions/node")
        if (!Files.isDirectory(nvmDirectory)) return null
        return Files.list(nvmDirectory).use { versions ->
            versions.map { it.resolve("bin/node") }
                .filter { Files.isRegularFile(it) && Files.isExecutable(it) }
                .max(compareBy { Files.getLastModifiedTime(it) })
                .orElse(null)?.toString()
        }
    }
}

private const val NODE_SYNTAX_CHECK = """
    const fs = require('node:fs');
    const vm = require('node:vm');
    const scripts = JSON.parse(fs.readFileSync(0, 'utf8'));
    for (const [index, script] of scripts.entries()) {
        try {
            if (script.module) new vm.SourceTextModule(script.source);
            else new vm.Script(script.source);
        } catch (error) {
            process.stdout.write('Script ' + (index + 1) + ': ' + error.message.slice(0, 300));
            break;
        }
    }
"""
