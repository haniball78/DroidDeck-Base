package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairEpisodeGuardTest {

    @Test fun firstEpisodeIsCurrent() {
        val guard = PairEpisodeGuard()
        val episode = guard.startEpisode()
        assertTrue(guard.isCurrent(episode))
        assertEquals(episode, guard.current())
    }

    @Test fun newEpisodeInvalidatesThePreviousOne() {
        val guard = PairEpisodeGuard()
        val first = guard.startEpisode()
        val second = guard.startEpisode()
        assertNotEquals(first, second)
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test fun invalidateMakesTheCurrentEpisodeStale() {
        val guard = PairEpisodeGuard()
        val episode = guard.startEpisode()
        guard.invalidate()
        assertFalse(guard.isCurrent(episode))
    }

    @Test fun episodeAfterInvalidateIsCurrentAgain() {
        val guard = PairEpisodeGuard()
        val first = guard.startEpisode()
        guard.invalidate()
        val second = guard.startEpisode()
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
        assertEquals(second, guard.current())
    }

    @Test fun unknownIdIsNeverCurrent() {
        val guard = PairEpisodeGuard()
        guard.startEpisode()
        assertFalse(guard.isCurrent(999))
        assertFalse(guard.isCurrent(0))
    }

    @Test fun repeatedInvalidatesKeepOnlyTheLatestEpisodeCurrent() {
        val guard = PairEpisodeGuard()
        val first = guard.startEpisode()
        guard.invalidate()
        guard.invalidate()
        val second = guard.startEpisode()
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }
}
