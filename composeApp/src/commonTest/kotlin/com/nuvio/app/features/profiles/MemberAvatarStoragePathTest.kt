package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberAvatarStoragePathTest {
    @Test
    fun normalizesOptionalLeadingSlashAndWhitespace() {
        assertEquals("members/icon.png", normalizeMemberAvatarStoragePath("  /members/icon.png  "))
        assertEquals("members/icon.png", normalizeMemberAvatarStoragePath("members/icon.png"))
    }

    @Test
    fun rejectsMalformedOrTraversingPaths() {
        assertNull(normalizeMemberAvatarStoragePath("  /// "))
        assertNull(normalizeMemberAvatarStoragePath("members/../secret.png"))
        assertNull(normalizeMemberAvatarStoragePath("members/./icon.png"))
        assertNull(normalizeMemberAvatarStoragePath("members/\nicon.png"))
    }
}
