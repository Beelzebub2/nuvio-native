package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * Records the *catalogue* identity beside the existing ownership and SHA-256 proof.
 * No source URLs, provider credentials, request headers or account identifiers are persisted here.
 * Historical files without this record can still be recovered using their saved DownloadItem.
 */
@Serializable
internal data class DesktopDownloadIdentity(
    val version: Int = 1,
    val ownerId: String,
    val fileName: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val contentType: String,
    val videoId: String,
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val completedBytes: Long,
    val completedSha256: String,
    val createdAtEpochMs: Long = 0L,
)

private const val IDENTITY_FILE = ".starlit-download.json"
private const val MAX_IDENTITY_SIZE = 16 * 1024L
private const val MAX_TASK_FOLDERS_TO_SCAN = 10_000
private val videoExtensions = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts")
private val hexSha256 = Regex("[0-9a-f]{64}")
private val identityJson = Json { encodeDefaults = true }

private fun validIdentity(identity: DesktopDownloadIdentity): Boolean =
    identity.version == 1 &&
        identity.ownerId.length in 1..128 && safeDownloadName(identity.ownerId) &&
        identity.fileName.length in 1..255 && safeDownloadName(identity.fileName) &&
        identity.fileName.substringAfterLast('.', "").lowercase() in videoExtensions &&
        identity.parentMetaId.isNotBlank() && identity.parentMetaId.length <= 512 &&
        identity.parentMetaType.isNotBlank() && identity.parentMetaType.length <= 64 &&
        identity.contentType.isNotBlank() && identity.contentType.length <= 64 &&
        identity.videoId.isNotBlank() && identity.videoId.length <= 512 &&
        identity.title.isNotBlank() && identity.title.length <= 1024 &&
        (identity.seasonNumber == null && identity.episodeNumber == null ||
            identity.seasonNumber != null && identity.seasonNumber >= 0 &&
                identity.episodeNumber != null && identity.episodeNumber >= 0) &&
        (identity.episodeTitle?.length ?: 0) <= 1024 &&
        identity.completedBytes > 0 && hexSha256.matches(identity.completedSha256)

private fun identityFile(item: DownloadItem): File = File(downloadPath(item).parentFile, IDENTITY_FILE)

private fun safeTaskDirectory(root: File, ownerId: String): File? = runCatching {
    if (!safeDownloadName(ownerId) || ownerId.length > 128 || !root.isDirectory ||
        Files.isSymbolicLink(root.toPath())) return null
    val base = root.toPath().toRealPath()
    val dir = File(root, ownerId)
    if (Files.isSymbolicLink(dir.toPath()) || !Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) ||
        dir.toPath().toRealPath() != base.resolve(ownerId)) return null
    val marker = File(dir, ".starlit-owner").toPath()
    if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) > 128 ||
        Files.readString(marker) != ownerId) return null
    dir
}.getOrNull()

/** Returns a proven final file in this exact task directory, never a partial or guessed title. */
private fun verifiedFile(root: File, identity: DesktopDownloadIdentity,
    verifyActive: () -> Boolean = { true }): Pair<File, DesktopDownloadProof>? {
    if (!validIdentity(identity)) return null
    val dir = safeTaskDirectory(root, identity.ownerId) ?: return null
    val file = File(dir, identity.fileName)
    return runCatching {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(downloadProofFile(file).toPath())) return null
        val proof = readDesktopDownloadProof(file) ?: return null
        if (proof.ownerId != identity.ownerId || proof.fileName != identity.fileName ||
            proof.totalBytes != identity.completedBytes ||
            proof.completedSha256 != identity.completedSha256 ||
            !verifiedDesktopDownloadContent(file, proof, verifyActive = verifyActive)) return null
        file to proof
    }.getOrNull()
}

private fun readIdentity(directory: File): DesktopDownloadIdentity? = runCatching {
    val path = File(directory, IDENTITY_FILE).toPath()
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > MAX_IDENTITY_SIZE) return null
    identityJson.decodeFromString<DesktopDownloadIdentity>(Files.readString(path))
        .takeIf(::validIdentity)
}.getOrNull()

private fun identityFrom(item: DownloadItem, proof: DesktopDownloadProof): DesktopDownloadIdentity? {
    val identity = DesktopDownloadIdentity(ownerId = item.id, fileName = item.fileName,
        parentMetaId = item.parentMetaId.trim(), parentMetaType = item.parentMetaType.trim(),
        contentType = item.contentType.trim(), videoId = item.videoId.trim(), title = item.title,
        seasonNumber = item.seasonNumber, episodeNumber = item.episodeNumber,
        episodeTitle = item.episodeTitle, completedBytes = proof.totalBytes,
        completedSha256 = proof.completedSha256 ?: return null, createdAtEpochMs = item.createdAtEpochMs)
    return identity.takeIf(::validIdentity)
}

/**
 * Add an identity only when the download already has its own valid owner marker and
 * content digest. Never alter an existing, conflicting metadata file.
 */
internal fun recordDesktopDownloadIdentity(item: DownloadItem): Boolean = runCatching {
    val root = item.destinationDirectory?.let(::File) ?: return false
    val file = downloadPath(item)
    val proof = readDesktopDownloadProof(file) ?: return false
    val identity = identityFrom(item, proof) ?: return false
    if (verifiedFile(root, identity) == null) return false
    val destination = identityFile(item)
    if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
        return readIdentity(file.parentFile) == identity
    }
    val bytes = identityJson.encodeToString(identity).toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_IDENTITY_SIZE) return false
    val temp = Files.createTempFile(file.parentFile.toPath(), ".starlit-download-", ".tmp")
    try {
        FileOutputStream(temp.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // Do not replace a sidecar another process or the user may have written.
        if (Files.exists(destination.toPath())) return false
        try {
            Files.move(temp, destination.toPath(), ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, destination.toPath())
        }
    } finally {
        Files.deleteIfExists(temp)
    }
    true
}.getOrDefault(false)

/** Saved metadata is stronger than title guessing: match the *same* task id and verified bytes. */
internal fun recoverKnownDesktopDownload(item: DownloadItem, folder: String?,
    verifyActive: () -> Boolean = { true }): DownloadItem? {
    val root = folder?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
    if (!safeDownloadName(item.id) || !safeDownloadName(item.fileName) ||
        item.id.length > 128 || item.fileName.length > 255 || item.parentMetaId.isBlank() ||
        item.videoId.isBlank()) return null
    if (safeTaskDirectory(root, item.id) == null) return null
    val candidate = item.copy(destinationDirectory = root.canonicalPath, localFileUri = null)
    val file = runCatching { recoverFinalDownload(candidate, verifyActive) }.getOrNull() ?: return null
    val proof = readDesktopDownloadProof(file) ?: return null
    val identity = identityFrom(candidate, proof) ?: return null
    val manifest = File(file.parentFile, IDENTITY_FILE)
    if (manifest.exists() || Files.isSymbolicLink(manifest.toPath())) {
        val previous = readIdentity(file.parentFile) ?: return null
        if (previous.ownerId != identity.ownerId || previous.fileName != identity.fileName ||
            previous.parentMetaId != identity.parentMetaId || previous.parentMetaType != identity.parentMetaType ||
            previous.videoId != identity.videoId || previous.seasonNumber != identity.seasonNumber ||
            previous.episodeNumber != identity.episodeNumber ||
            previous.completedSha256 != identity.completedSha256 ||
            previous.completedBytes != identity.completedBytes) return null
    }
    if (verifiedFile(root, identity, verifyActive) == null) return null
    val complete = candidate.copy(status = DownloadStatus.Completed, phase = null, bytesPerSecond = 0L,
        downloadedBytes = proof.totalBytes, totalBytes = proof.totalBytes,
        localFileUri = file.toURI().toString(), errorMessage = null)
    recordDesktopDownloadIdentity(complete)
    return complete
}

/** Scan only immediate task directories, never arbitrary filenames or nested folders. */
internal fun discoverDesktopDownloads(folder: String?): List<DownloadItem> {
    val root = folder?.takeIf { it.isNotBlank() }?.let(::File) ?: return emptyList()
    if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
    val directories = root.listFiles() ?: return emptyList()
    if (directories.size > MAX_TASK_FOLDERS_TO_SCAN) return emptyList()
    return directories.sortedBy { it.name }.mapNotNull { dir ->
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return@mapNotNull null
        val identity = readIdentity(dir) ?: return@mapNotNull null
        if (identity.ownerId != dir.name) return@mapNotNull null
        val (file, proof) = verifiedFile(root, identity) ?: return@mapNotNull null
        DownloadItem(
            id = identity.ownerId, contentType = identity.contentType,
            parentMetaId = identity.parentMetaId, parentMetaType = identity.parentMetaType,
            videoId = identity.videoId, title = identity.title,
            seasonNumber = identity.seasonNumber, episodeNumber = identity.episodeNumber,
            episodeTitle = identity.episodeTitle, streamTitle = "Verified offline file",
            providerName = "Starlit", sourceUrl = "", localFileUri = file.toURI().toString(),
            destinationDirectory = root.canonicalPath, fileName = identity.fileName,
            status = DownloadStatus.Completed, downloadedBytes = proof.totalBytes,
            totalBytes = proof.totalBytes, createdAtEpochMs = identity.createdAtEpochMs,
            updatedAtEpochMs = identity.createdAtEpochMs,
        )
    }
}

/** Never delete unknown sidecars during normal cleanup of a user-selected folder. */
internal fun removeRecordedDesktopIdentity(item: DownloadItem) {
    val dir = runCatching { downloadPath(item).parentFile }.getOrNull() ?: return
    val identity = readIdentity(dir) ?: return
    if (identity.ownerId != item.id || identity.fileName != item.fileName) return
    val manifest = File(dir, IDENTITY_FILE)
    if (!Files.isSymbolicLink(manifest.toPath())) manifest.delete()
}
