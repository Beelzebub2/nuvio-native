package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLanguageTest {
    @Test
    fun addedLanguagesRoundTripThroughPreferenceCodes() {
        val added = listOf(
            AppLanguage.CHINESE_SIMPLIFIED to "zh-CN",
            AppLanguage.CHINESE_TRADITIONAL to "zh-TW",
            AppLanguage.KOREAN to "ko",
            AppLanguage.THAI to "th",
            AppLanguage.UKRAINIAN to "uk",
            AppLanguage.PERSIAN to "fa",
            AppLanguage.MALAY to "ms",
            AppLanguage.FILIPINO to "fil",
            AppLanguage.SWEDISH to "sv",
            AppLanguage.FINNISH to "fi",
            AppLanguage.TAMIL to "ta",
            AppLanguage.TELUGU to "te",
            AppLanguage.SWAHILI to "sw",
        )

        added.forEach { (language, code) ->
            assertEquals(code, language.code)
            assertEquals(language, AppLanguage.fromCode(code))
        }
    }

    @Test
    fun persianUsesRightToLeftLayout() {
        assertTrue(AppLanguage.PERSIAN.isRightToLeft())
        assertFalse(AppLanguage.KOREAN.isRightToLeft())
    }

    @Test
    fun hindiIsNotAddedToTheAppLanguageSelector() {
        assertFalse(AppLanguage.entries.any { it.code == "hi" })
    }
}
