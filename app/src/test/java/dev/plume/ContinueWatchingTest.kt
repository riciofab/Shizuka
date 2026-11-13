package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    @Test fun newestFirstAndThreePerAppAhead() {
        // Netflix reports times for ten shows; Stremio's channel rows all get the time it was last opened.
        val netflix = (1..10).map { 100L - it to "netflix/$it" }
        val stremio = (1..4).map { 95L to "stremio/$it" }
        val nuvio = (1..2).map { 0L to "nuvio/$it" } // never opened from Plume
        val shown = newestPerApp(netflix + stremio + nuvio, 3) { it.substringBefore('/') }
        // Each app's three newest first, then the rest, newest first.
        assertEquals(
            listOf("netflix/1", "netflix/2", "netflix/3", "stremio/1", "stremio/2", "stremio/3", "nuvio/1", "nuvio/2",
                "netflix/4", "netflix/5", "stremio/4") + (6..10).map { "netflix/$it" },
            shown,
        )
    }

    @Test fun oneAppIsNotCapped() {
        val stremio = (1..10).map { 100L - it to "stremio/$it" }
        assertEquals(stremio.map { it.second }, newestPerApp(stremio, 3) { it.substringBefore('/') })
    }

    @Test fun busyAppCantPushOthersBack() {
        // Four newer Stremio shows, then one Netflix show: Netflix moves up to fourth.
        val items = (1..4).map { 100L - it to "stremio/$it" } + (90L to "netflix/1")
        assertEquals(listOf("stremio/1", "stremio/2", "stremio/3", "netflix/1", "stremio/4"), newestPerApp(items, 3) { it.substringBefore('/') })
    }

    @Test fun whatYouStartedComesBeforeSuggestionsWhateverTheUnit() {
        // Wholphin dates what is playing in seconds and its "next episode" suggestions in milliseconds. Sorted on the
        // raw numbers the suggestions win, though they are older (11:52 against 13:14).
        val rows = listOf(
            watchSortKey(1, 1_792_576_332_167) to "wholphin/suggestion-a",
            watchSortKey(1, 1_792_576_332_167) to "wholphin/suggestion-b",
            watchSortKey(0, 1_792_581_271) to "wholphin/ncis",
            watchSortKey(0, 1_791_758_381) to "wholphin/descendants",
            watchSortKey(0, 1_791_242_669) to "wholphin/coquin",
            watchSortKey(0, 1_791_237_906_401) to "moonfin/clochette", // another app, in milliseconds
        )
        val shown = newestPerApp(rows, 3) { it.substringBefore('/') }
        // Wholphin's fourth and fifth rows follow the other apps' front places.
        assertEquals(listOf("wholphin/ncis", "wholphin/descendants", "wholphin/coquin", "moonfin/clochette",
            "wholphin/suggestion-a", "wholphin/suggestion-b"), shown)
    }

    @Test fun suggestionsStillShowWhenAnAppHasNothingStarted() {
        val rows = listOf(watchSortKey(1, 1_792_576_332_167) to "netflix/next", watchSortKey(0, 1_792_581_271) to "wholphin/ncis")
        assertEquals(listOf("wholphin/ncis", "netflix/next"), newestPerApp(rows, 3) { it.substringBefore('/') })
    }

    @Test fun channelCardsNewestFirstNotInsertOrder() {
        // Nuvio adds each new title at the end of its channel (A, then B, then C), with its last-watched time and
        // a weight that is highest for the newest. Netflix's Watch Next row sits between B and C.
        val nuvio = byWeight(listOf(
            Triple(Int.MAX_VALUE - 2L, channelSortKey(1_000, 0), "nuvio/A"),
            Triple(Int.MAX_VALUE - 1L, channelSortKey(2_000, 0), "nuvio/B"),
            Triple(Int.MAX_VALUE - 0L, channelSortKey(3_000, 0), "nuvio/C"),
        ))
        val shown = newestPerApp(nuvio + (watchSortKey(0, 2_500) to "netflix/1"), 3) { it.substringBefore('/') }
        assertEquals(listOf("nuvio/C", "netflix/1", "nuvio/B", "nuvio/A"), shown)
    }

    @Test fun channelCardsWithNoTimeFollowWeightThenAppOrder() {
        // No last-watched time: every card gets the time the app was opened from Plume, so the weight decides.
        val weighted = byWeight(listOf(Triple(1L, channelSortKey(0, 500), "a"), Triple(3L, channelSortKey(0, 500), "c"),
            Triple(2L, channelSortKey(0, 500), "b")))
        assertEquals(listOf("c", "b", "a"), newestPerApp(weighted, 3) { "app" })
        // No weight either (Stremio, as far as we know): the app's own order, as before.
        val plain = byWeight(listOf(Triple(0L, channelSortKey(0, 500), "x"), Triple(0L, channelSortKey(0, 500), "y")))
        assertEquals(listOf("x", "y"), newestPerApp(plain, 3) { "app" })
    }

    @Test fun aDrawerStartsWithWhatWasWatchedLast() {
        // Watch Next rows as the TV hands them over, in no useful order: a suggestion, then two shows in progress.
        val started = listOf(
            watchSortKey(2, 9_000_000_000_000) to "new season",
            watchSortKey(0, 1_700_000_000_000) to "watched last week",
            watchSortKey(0, 1_700_500_000_000) to "watched yesterday",
        )
        // The app's own channel: undated cards keep the app's order, a dated one comes before them.
        val opened = 1_600_000_000_000
        val channel = listOf(channelSortKey(0, opened) to "pick 1", channelSortKey(0, opened) to "pick 2", channelSortKey(1_650_000_000_000, opened) to "resumed")
        assertEquals(listOf("watched yesterday", "watched last week", "new season", "resumed", "pick 1", "pick 2"), drawerOrder(started, channel))
    }

    @Test fun rowsOfOneSyncKeepTheAppsOrder() {
        // What Netflix wrote on a TV: two older shows, then, in one sync, the show just watched and one it promotes 17 ms later.
        val sansRegret = 1_791_214_431_000
        val jobMortelle = 1_794_183_396_000
        val ghosts = 1_794_605_138_435
        val lupin = 1_794_605_138_452
        val times = syncOrder(listOf(sansRegret, jobMortelle, ghosts, lupin))
        val names = listOf("Sans regret", "Une job mortelle", "Ghosts", "Lupin")
        assertEquals(listOf("Ghosts", "Lupin", "Une job mortelle", "Sans regret"), names.indices.sortedByDescending { times[it] }.map { names[it] })
        // Shows watched minutes apart are not a sync: newest first, as written.
        assertEquals(listOf(1_000_000L, 1_600_000L), syncOrder(listOf(1_000_000L, 1_600_000L)))
        // A longer sync, each row within the gap of the next: still one batch, first written first.
        assertEquals(listOf(5_000L, 3_500L, 2_000L), syncOrder(listOf(2_000L, 3_500L, 5_000L)))
        assertEquals(emptyList<Long>(), syncOrder(emptyList()))
        assertEquals(listOf(0L, 0L), syncOrder(listOf(0L, 0L))) // rows with no time stay as they are
    }

    @Test fun onlyReadableSeasonAndEpisodeNumbersShow() {
        assertEquals("5", displayNumber("5"))
        assertEquals("12", displayNumber(" 12 "))
        assertEquals("1A", displayNumber("1A"))
        // What Alder stores: a letter and an encoded blob. Neither prints, so the card shows only the time left.
        assertEquals(null, displayNumber("e"))
        assertEquals(null, displayNumber("eyJzIjoidHYiLCJrIjoidHUgaGkgcmUgZGlsIG1laW4iLCJ0IjoidHUgaGkgcmUgZGlsIG1laW4ifQ"))
        assertEquals(null, displayNumber(""))
        assertEquals(null, displayNumber(null))
    }

    @Test fun continueWatchingChannelsStartOnOthersOff() {
        assertEquals(true, channelOn("Continue Watching", "stremio/Continue Watching", emptySet()))
        assertEquals(false, channelOn("On Deck", "plex/On Deck", emptySet()))
        // Switched by the user, each way.
        assertEquals(true, channelOn("On Deck", "plex/On Deck", setOf("plex/On Deck")))
        assertEquals(false, channelOn("Continue watching", "stremio/Continue watching", setOf("stremio/Continue watching")))
    }
}
