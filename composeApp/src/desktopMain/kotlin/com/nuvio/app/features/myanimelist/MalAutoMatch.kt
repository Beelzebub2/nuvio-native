package com.nuvio.app.features.myanimelist

import com.nuvio.app.features.details.MetaDetails
import java.text.Normalizer
import java.util.Locale

/** Only a unique, exact title/alias match is safe to associate without confirmation. */
internal fun matchMalSearchCandidate(meta: MetaDetails, results: List<MalAnime>): Int? {
    if (!isLikelyMyAnimeListAnime(meta)) return null
    val title = normalizeMalMatchTitle(meta.name)
    if (title.length < 3) return null
    val year = Regex("""(?:^|\D)(19\d{2}|20\d{2})(?:\D|$)""")
        .find(meta.releaseInfo.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    val movie = meta.type.equals("movie", ignoreCase = true)
    val matches = results.asSequence()
        .filter { anime ->
            anime.id > 0 &&
                (anime.mediaType == null || (anime.mediaType == "movie") == movie) &&
                (year == null || anime.startYear == null || anime.startYear == year) &&
                (sequenceOf(anime.title, anime.englishTitle).filterNotNull() +
                    anime.alternativeTitles.asSequence()).any { normalizeMalMatchTitle(it) == title }
        }
        .distinctBy { it.id }
        .toList()
    if (matches.size == 1) return matches.single().id
    val onList = matches.filter { it.listStatus != null }
    if (onList.size == 1) return onList.single().id
    return onList.filter { it.listStatus?.status == MalStatus.COMPLETED }.singleOrNull()?.id
}

private fun normalizeMalMatchTitle(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
