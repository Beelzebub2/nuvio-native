package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeAiringMetaCacheTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun `NSFW policy invalidation discards prior native details but retains other cached titles`() {
        MetaDetailsRepository.clear()
        val native = MetaDetails("nativeairing:91", "series", "Previously visible adult anime")
        val ordinary = MetaDetails("regular:1", "movie", "Unrelated movie")
        val owner = MetaDetailsRepository::class.java
        val cacheField = owner.getDeclaredField("cachedMetaByRequestKey").apply { isAccessible = true }
        val cache = cacheField.get(MetaDetailsRepository) as MutableMap<String, Any>
        val entryClass = owner.declaredClasses.first { it.simpleName == "CachedMetaEntry" }
        val entryConstructor = entryClass.declaredConstructors.first { it.parameterCount == 3 }.apply { isAccessible = true }
        val stateField = owner.getDeclaredField("_uiState").apply { isAccessible = true }
        val state = stateField.get(MetaDetailsRepository) as MutableStateFlow<MetaDetailsUiState>
        try {
            cache["series:nativeairing:91"] = entryConstructor.newInstance(native, null, null)
            cache["movie:regular:1"] = entryConstructor.newInstance(ordinary, null, null)
            state.value = MetaDetailsUiState(meta = native)
            assertNotNull(MetaDetailsRepository.peek("series", "nativeairing:91"))

            MetaDetailsRepository.invalidateNativeAiring()

            assertNull(MetaDetailsRepository.peek("series", "nativeairing:91"))
            assertNull(state.value.meta, "the active adult details card must disappear on a policy change")
            assertEquals("Unrelated movie", MetaDetailsRepository.peek("movie", "regular:1")?.name)
        } finally {
            MetaDetailsRepository.clear()
        }
    }
}
