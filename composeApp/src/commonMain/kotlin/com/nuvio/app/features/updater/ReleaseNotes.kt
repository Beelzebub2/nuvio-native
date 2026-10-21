package com.nuvio.app.features.updater

import io.ktor.http.Url

internal data class ReleaseNotesVersion(
    val version: String?,
    val title: String,
    val blocks: List<ReleaseNotesBlock>,
)

internal data class ReleaseNotesBlock(val text: String, val kind: Kind = Kind.Paragraph) {
    enum class Kind { Paragraph, Heading, Bullet, Code }
}

private val versionLine = Regex(
    "^(?:(Starlit|Nuvio Native)\\s+)?v?(\\d+\\.\\d+\\.\\d+(?:[-+][\\w.-]+)?)(?=$|[\\s:–—-])(.*)$",
    RegexOption.IGNORE_CASE,
)
private val headingLine = Regex("^#{1,6}\\s+(.+?)(?:\\s+#+)?$")
private val bulletLine = Regex("^\\s*(?:[-*+] |\\d+[.)] )(.+)$")
private val generatedNotesMarker = Regex("^<!-- Release notes generated using configuration in \\.github/release\\.ya?ml at .+ -->$")

// Deliberately a text-only Markdown subset: unsupported syntax stays visible; no HTML or images execute.
internal fun parseReleaseNotes(notes: String, fallbackTitle: String, fallbackVersion: String? = null): List<ReleaseNotesVersion> {
    if (notes.isBlank()) return emptyList()
    val releases = mutableListOf<ReleaseNotesVersion>()
    var title = fallbackTitle
    var version = fallbackVersion
    var explicitRelease = false
    var generatedStartIndex: Int? = null
    var blocks = mutableListOf<ReleaseNotesBlock>()
    val paragraph = mutableListOf<String>()
    var fenced = false
    val code = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += ReleaseNotesBlock(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    fun flushRelease() {
        flushParagraph()
        if (blocks.isNotEmpty() || explicitRelease) releases += ReleaseNotesVersion(version, title, blocks.toList())
        blocks = mutableListOf()
    }
    notes.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        if (!fenced && generatedStartIndex == null && generatedNotesMarker.matches(line)) {
            // gh --generate-notes --notes-file appends this current-release tail after the historical notes.
            flushRelease()
            generatedStartIndex = releases.size
            title = fallbackTitle
            version = fallbackVersion
            explicitRelease = false
        } else if (line.trimStart().startsWith("```")) {
            flushParagraph()
            if (fenced) {
                blocks += ReleaseNotesBlock(code.joinToString("\n"), ReleaseNotesBlock.Kind.Code)
                code.clear()
            }
            fenced = !fenced
        } else if (fenced) {
            code += line
        } else {
            val heading = headingLine.matchEntire(line.trim())
            val content = heading?.groupValues?.get(1) ?: line.trim()
            val release = if (generatedStartIndex == null) versionLine.matchEntire(content) else null
            val bullet = bulletLine.matchEntire(line)
            when {
                release != null -> {
                    flushRelease()
                    explicitRelease = true
                    version = release.groupValues[2]
                    title = listOf(release.groupValues[1], version).filterNotNull().filter(String::isNotBlank).joinToString(" ")
                    val summary = release.groupValues[3].trim().trimStart(':', '–', '—', '-').trim()
                    if (summary.isNotEmpty()) blocks += ReleaseNotesBlock(summary.replaceFirstChar { it.titlecase() })
                }
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    blocks += ReleaseNotesBlock(content, ReleaseNotesBlock.Kind.Heading)
                }
                bullet != null -> {
                    flushParagraph()
                    blocks += ReleaseNotesBlock(bullet.groupValues[1], ReleaseNotesBlock.Kind.Bullet)
                }
                // Indented continuation lines belong to the preceding list item.
                line.startsWith("  ") && paragraph.isEmpty() && blocks.lastOrNull()?.kind == ReleaseNotesBlock.Kind.Bullet -> {
                    val previous = blocks.removeAt(blocks.lastIndex)
                    blocks += previous.copy(text = previous.text + "\n" + line.trim())
                }
                else -> paragraph += line
            }
        }
    }
    if (fenced) blocks += ReleaseNotesBlock(code.joinToString("\n"), ReleaseNotesBlock.Kind.Code)
    flushRelease()
    generatedStartIndex?.takeIf { it > 0 }?.let { boundary ->
        val generatedBlocks = releases.drop(boundary).flatMap { it.blocks }
        val history = releases.take(boundary).toMutableList()
        val currentIndex = history.indexOfFirst { it.version == fallbackVersion?.removePrefix("v") }.takeIf { it >= 0 } ?: 0
        history[currentIndex] = history[currentIndex].copy(blocks = history[currentIndex].blocks + generatedBlocks)
        return history
    }
    return releases
}

internal fun safeReleaseNotesUrl(value: String): String? = runCatching {
    if (value.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = Url(value)
    value.takeIf {
        value.startsWith("${url.protocol.name}://", ignoreCase = true) &&
            url.protocol.name.lowercase() in setOf("http", "https") && url.host.isNotBlank() &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
    }
}.getOrNull()

internal fun githubReleaseNotesUrl(value: String?): String? = value?.let(::safeReleaseNotesUrl)?.takeIf {
    val url = Url(it)
    url.host.equals("github.com", ignoreCase = true) &&
        Regex("^/[^/]+/[^/]+/releases(?:/.*)?$").matches(url.encodedPath)
}
