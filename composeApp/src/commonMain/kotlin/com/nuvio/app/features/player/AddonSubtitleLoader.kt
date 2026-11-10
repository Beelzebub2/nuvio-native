package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

@Serializable
data class SubtitleAddonRequest(
    val url: String,
    val addonId: String,
    val addonName: String,
)

internal fun addonSubtitleRequests(type: String, videoId: String): List<SubtitleAddonRequest> {
    val requestType = canonicalSubtitleType(type)
    return AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        if (manifest.resources.none { resource ->
                (resource.name.equals("subtitles", true) || resource.name.equals("subtitle", true)) &&
                    resource.supportsSubtitleType(requestType, videoId)
            }) return@mapNotNull null
        SubtitleAddonRequest(
            url = buildAddonResourceUrl(manifest.transportUrl, "subtitles", requestType, videoId),
            addonId = manifest.id,
            addonName = addon.displayTitle,
        )
    }
}

internal suspend fun loadAddonSubtitles(
    requests: List<SubtitleAddonRequest>,
    onLoaded: (SubtitleAddonRequest, List<AddonSubtitle>) -> Unit = { _, _ -> },
): List<AddonSubtitle> = supervisorScope {
    requests.map { request ->
        async {
            val subtitles = try {
                withTimeoutOrNull(10_000L) {
                    parseAddonSubtitles(fetchAddonResponseText(request.url), request)
                }.orEmpty()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            onLoaded(request, subtitles)
            subtitles
        }
    }.awaitAll().flatten()
}

private suspend fun parseAddonSubtitles(response: String, request: SubtitleAddonRequest): List<AddonSubtitle> {
    val subtitles = Json.parseToJsonElement(response).jsonObject["subtitles"]?.jsonArray.orEmpty()
    return subtitles.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val url = obj.stringValue("url") ?: return@mapIndexedNotNull null
        // A descriptive track label is not a language code.
        val language = listOf("lang", "language", "languageCode", "locale")
            .firstNotNullOfOrNull(obj::stringValue) ?: "unknown"
        val displayLabel = addonSubtitleDisplayLabel(
            label = obj.stringValue("label"),
            name = obj.stringValue("name"),
            title = obj.stringValue("title"),
        )
        AddonSubtitle(
            id = obj.stringValue("id") ?: "${request.addonId}_$index",
            url = url,
            language = normalizeLanguageCode(language) ?: language,
            display = displayLabel ?: getString(
                Res.string.player_addon_subtitle_display_format,
                getLanguageLabelForCode(language),
                request.addonName,
            ),
            addonName = request.addonName,
        )
    }
}

/** Keep distinct addon tracks identifiable without rendering control/bidi spoofing characters. */
internal fun addonSubtitleDisplayLabel(label: String?, name: String?, title: String?): String? =
    listOf(label, name, title).firstNotNullOfOrNull { candidate ->
        candidate?.filterNot { char ->
            char.code in 0..31 || char.code in 127..159 ||
                char.code in 0x202A..0x202E || char.code in 0x2066..0x2069
        }?.trim()?.takeIf { it.isNotBlank() }?.take(240)
    }

private fun canonicalSubtitleType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonical = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { canonicalSubtitleType(it).equals(canonical, ignoreCase = true) }
    return typeMatches && (idPrefixes.isEmpty() || idPrefixes.any { videoId.startsWith(it) })
}

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
