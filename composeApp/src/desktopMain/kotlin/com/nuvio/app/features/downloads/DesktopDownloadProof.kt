package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.TorrServerFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A versioned, source-bound resume record. A digest is added only after the bytes are synced. */
internal data class DesktopDownloadProof(
    val ownerId: String,
    val fileName: String,
    val sourceFingerprint: String,
    val endpointFingerprint: String,
    val validator: String,
    val totalBytes: Long,
    val completedSha256: String? = null,
)

private const val PROOF_VERSION = "nuvio-download-v2"
private const val MAX_PROOF_BYTES = 8 * 1024L
private const val HEX = "0123456789abcdef"
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private data class DownloadFileSnapshot(
    val size: Long,
    val modified: java.nio.file.attribute.FileTime,
    val fileKey: String?,
    val proofSize: Long,
    val proofModified: java.nio.file.attribute.FileTime,
    val digest: String,
)
private val verifiedFinalSnapshots = ConcurrentHashMap<String, DownloadFileSnapshot>()

internal fun downloadProofFile(file: File): File = File(file.path + ".validator")
internal fun downloadProofTempFile(file: File): File = File(file.path + ".validator.tmp")

internal fun downloadSourceFingerprint(
    item: DownloadItem,
    requestedUrl: String,
    selectedFile: TorrServerFile? = null,
): String = if (selectedFile != null) {
    // Local TorrServer ports change between launches; the torrent identity and actual path do not.
    val torrent = canonicalDownloadTorrentHash(requireNotNull(item.torrentHash))
    downloadSha256Text("torrent\u0000$torrent\u0000${selectedFile.path.replace('\\', '/')}\u0000${selectedFile.id}\u0000${selectedFile.length}")
} else {
    val headers = item.sourceHeaders.entries
        .filter { it.key.lowercase(Locale.ROOT) !in setOf("range", "if-range", "accept-encoding", "host", "content-length") }
        .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
        .joinToString("\u0000") { "${it.key.lowercase(Locale.ROOT)}:${it.value}" }
    downloadSha256Text("http\u0000$requestedUrl\u0000$headers")
}

internal fun downloadEndpointFingerprint(responseUri: URI, selectedFile: TorrServerFile? = null): String =
    if (selectedFile != null) "-" else downloadSha256Text("endpoint\u0000$responseUri")

internal fun downloadSha256Text(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).downloadHex()

internal fun ByteArray.downloadHex(): String = buildString(size * 2) {
    for (byte in this@downloadHex) {
        val value = byte.toInt() and 0xff
        append(HEX[value ushr 4])
        append(HEX[value and 0x0f])
    }
}

internal fun downloadSha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().downloadHex()
}

/** Hash a final file on first access and again if either the data or its proof changes. */
internal fun verifiedDesktopDownloadContent(file: File, proof: DesktopDownloadProof): Boolean {
    val expected = proof.completedSha256 ?: return false
    val filePath = file.toPath()
    val proofPath = downloadProofFile(file).toPath()
    if (Files.isSymbolicLink(filePath) || Files.isSymbolicLink(proofPath)) return false
    fun signature(): DownloadFileSnapshot {
        val fileAttributes = Files.readAttributes(filePath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val proofAttributes = Files.readAttributes(proofPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return DownloadFileSnapshot(fileAttributes.size(), fileAttributes.lastModifiedTime(),
            fileAttributes.fileKey()?.toString(), proofAttributes.size(), proofAttributes.lastModifiedTime(), expected)
    }
    val before = signature()
    if (before.size != proof.totalBytes || before.size <= 0L) return false
    val key = file.absolutePath
    if (verifiedFinalSnapshots[key] == before) return true
    val valid = downloadSha256File(file) == expected && signature() == before
    if (valid) verifiedFinalSnapshots[key] = before else verifiedFinalSnapshots.remove(key)
    return valid
}

internal fun isModernDesktopDownloadProof(file: File): Boolean = runCatching {
    val path = downloadProofFile(file).toPath()
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= MAX_PROOF_BYTES &&
        Files.newBufferedReader(path, Charsets.UTF_8).use { it.readLine()?.startsWith("nuvio-download") == true }
}.getOrDefault(false)

internal fun readDesktopDownloadProof(file: File): DesktopDownloadProof? = runCatching {
    val proof = downloadProofFile(file).toPath()
    if (Files.isSymbolicLink(proof) || !Files.isRegularFile(proof) || Files.size(proof) > MAX_PROOF_BYTES) return null
    val lines = Files.readAllLines(proof, Charsets.UTF_8)
    if (lines.size != 8 || lines[0] != PROOF_VERSION) return null
    val owner = proofDecode(lines[1]) ?: return null
    val name = proofDecode(lines[2]) ?: return null
    val validator = proofDecode(lines[5]) ?: return null
    val size = lines[6].toLongOrNull() ?: return null
    val digest = lines[7].takeUnless { it == "-" }
    if (owner.isBlank() || name.isBlank() || size < -1L || !SHA256_PATTERN.matches(lines[3]) ||
        (lines[4] != "-" && !SHA256_PATTERN.matches(lines[4])) ||
        (digest != null && (!SHA256_PATTERN.matches(digest) || size <= 0L))) return null
    DesktopDownloadProof(owner, name, lines[3], lines[4], validator, size, digest)
}.getOrNull()

/** Old clients wrote only validator + size. This may migrate a COMPLETED owned file, never a partial. */
internal fun readLegacyCompletedSize(file: File): Long? = runCatching {
    val path = downloadProofFile(file).toPath()
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > MAX_PROOF_BYTES) return null
    val lines = Files.readAllLines(path, Charsets.UTF_8)
    if (lines.size != 2 || lines[0] == PROOF_VERSION) return null
    lines[1].toLongOrNull()?.takeIf { it > 0L }
}.getOrNull()

internal fun writeDesktopDownloadProof(file: File, proof: DesktopDownloadProof) {
    check(proof.ownerId.isNotBlank() && proof.fileName.isNotBlank() && SHA256_PATTERN.matches(proof.sourceFingerprint) &&
        (proof.endpointFingerprint == "-" || SHA256_PATTERN.matches(proof.endpointFingerprint)) &&
        proof.totalBytes >= -1L && (proof.completedSha256 == null ||
            (SHA256_PATTERN.matches(proof.completedSha256) && proof.totalBytes > 0L))) { "Invalid download proof" }
    val destination = downloadProofFile(file).toPath()
    val temporary = downloadProofTempFile(file).toPath()
    check(!Files.isSymbolicLink(destination) && !Files.isSymbolicLink(temporary)) { "Invalid download proof path" }
    val content = listOf(PROOF_VERSION, proofEncode(proof.ownerId), proofEncode(proof.fileName),
        proof.sourceFingerprint, proof.endpointFingerprint, proofEncode(proof.validator),
        proof.totalBytes.toString(), proof.completedSha256 ?: "-").joinToString("\n", postfix = "\n")
    FileOutputStream(temporary.toFile(), false).use { output ->
        output.write(content.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    try {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun proofEncode(value: String): String =
    Base64.getUrlEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun proofDecode(value: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}.getOrNull()
