package com.nuvio.app.features.airing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiringHiddenTitleListTest {
    private data class Entry(val id: Int, val name: String)

    private val hidden = listOf(
        Entry(1, "zenith"),
        Entry(2, "bETA"),
        Entry(3, "Alpha"),
        Entry(4, "alpha 2"),
    )

    @Test
    fun `hidden titles are displayed alphabetically without mutating the saved list`() {
        assertEquals(
            listOf(3, 4, 2, 1),
            filterAndSortHiddenAiringTitles(hidden, "") { it.name }.map { it.id },
        )
        assertEquals(listOf(1, 2, 3, 4), hidden.map { it.id })
    }

    @Test
    fun `search ignores case and surrounding whitespace`() {
        assertEquals(
            listOf(3, 4),
            filterAndSortHiddenAiringTitles(hidden, "  ALPhA  ") { it.name }.map { it.id },
        )
        assertEquals(listOf(3, 4, 2, 1),
            filterAndSortHiddenAiringTitles(hidden, "   ") { it.name }.map { it.id })
    }

    @Test
    fun `no matches and empty hidden list return empty results`() {
        assertTrue(filterAndSortHiddenAiringTitles(hidden, "missing") { it.name }.isEmpty())
        assertTrue(filterAndSortHiddenAiringTitles(emptyList<Entry>(), "") { it.name }.isEmpty())
    }

    @Test
    fun `only explicit restorations are applied on save`() {
        val original = listOf(Entry(10, "A"), Entry(20, "B"), Entry(30, "C"))
        val remaining = listOf(Entry(30, "C"), Entry(10, "A"))
        assertEquals(listOf(20), hiddenAiringIdsToRestore(original, remaining) { it.id })
        assertTrue(hiddenAiringIdsToRestore(original, original) { it.id }.isEmpty())
        assertEquals(listOf(10, 20, 30), hiddenAiringIdsToRestore(original, emptyList()) { it.id })
    }
}
