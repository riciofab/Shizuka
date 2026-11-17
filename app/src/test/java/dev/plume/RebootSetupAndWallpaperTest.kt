package dev.plume

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebootSetupAndWallpaperTest {

    @Test fun googleTvLauncherIsActiveOnlyWhenEnabled() {
        assertTrue(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertTrue(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertFalse(isGoogleTvLauncher(false, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertFalse(isGoogleTvLauncher(false, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertFalse(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertFalse(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertFalse(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED))
    }

    @Test fun aPhotoIsNeverKeptLargerThanAndroidDraws() {
        // The 36 MP photo that crashed the home screen: it was kept whole (the old rule only halved from 7680 px up).
        assertEquals(2, uncroppedSample(7360, 4912))
        assertFalse(drawable(7360 * 4912 * 4))
        assertTrue(drawable(7360 / 2 * (4912 / 2) * 4))
        // Whatever the photo, what is kept fits the limit, and a photo already small enough is left alone.
        for ((w, h) in listOf(3840 to 2160, 3841 to 2160, 4000 to 3000, 2160 to 7680, 12000 to 9000, 16384 to 16384, 1920 to 1080)) {
            val s = uncroppedSample(w, h)
            assertTrue("$w x $h", maxOf(w, h) / s <= UNCROPPED_MAX)
            assertTrue("$w x $h", drawable(w / s * (h / s) * 4))
            assertTrue("$w x $h", s == 1 || maxOf(w, h) / (s / 2) > UNCROPPED_MAX) // and no smaller than it has to be
        }
        assertEquals(1, uncroppedSample(3840, 2160))
        assertEquals(1, uncroppedSample(1920, 1080))
    }

    @Test fun emptyFolderAtBootReturnsNullAndPreservesDeck() {
        val deck = ArrayDeque(listOf(10L, 20L))
        val picked = pickFolderPhoto(emptyList(), currentSaved = 10L, deck = deck)
        assertNull(picked)
        assertEquals(listOf(10L, 20L), deck.toList())
    }

    @Test fun savedPhotoReturnsDirectlyWhenPresent() {
        val deck = ArrayDeque(listOf(20L))
        val picked = pickFolderPhoto(listOf(10L, 20L), currentSaved = 10L, deck = deck)
        assertEquals(10L, picked)
        assertEquals(listOf(20L), deck.toList())
    }

    @Test fun missingSavedPhotoPicksNextFromDeck() {
        val deck = ArrayDeque(listOf(10L, 20L))
        val picked = pickFolderPhoto(listOf(20L, 30L), currentSaved = 99L, deck = deck)
        assertEquals(20L, picked)
        assertTrue(deck.isEmpty())
    }

    @Test fun emptyDeckRefillsAndPopsFirst() {
        val deck = ArrayDeque<Long>()
        val picked = pickFolderPhoto(listOf(1L, 2L), currentSaved = null, deck = deck) { it }
        assertEquals(1L, picked)
        assertEquals(listOf(2L), deck.toList())
    }
}
