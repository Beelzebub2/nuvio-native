package com.nuvio.app.features.updater

import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Read credentials at runtime; never write or bundle an account token. */
internal object PrivateReleaseAuth {
    fun token(): String {
        System.getenv("NUVIO_GITHUB_TOKEN")?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        val candidates = listOfNotNull(
            System.getenv("ProgramFiles")?.let { File(it, "GitHub CLI/gh.exe").takeIf(File::isFile)?.path },
            "gh",
        ).distinct()
        for (executable in candidates) {
            val token = runCatching {
                val process = ProcessBuilder(executable, "auth", "token", "--hostname", "github.com")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    if (!process.waitFor(8, TimeUnit.SECONDS)) return@runCatching null
                    if (process.exitValue() != 0) return@runCatching null
                    process.inputStream.bufferedReader().use { it.readText().trim().takeIf(String::isNotEmpty) }
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }.getOrNull()
            if (token != null) return token
        }
        error("Private updates need your GitHub sign-in. Install GitHub CLI and run: gh auth login. Then check for updates again.")
    }
}

internal fun isPrivateReleaseAssetUri(uri: URI): Boolean =
    uri.scheme == "https" && uri.host == "api.github.com" && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.query == null && uri.fragment == null &&
        // Keep saved asset URLs from before the repository rename valid.
        uri.path.matches(Regex("/repos/Beelzebub2/(?:starlit|nuvio-native)/releases/assets/[0-9]+"))
