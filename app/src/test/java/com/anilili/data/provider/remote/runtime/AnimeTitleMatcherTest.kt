package com.anilili.data.provider.remote.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeTitleMatcherTest {

    @Test
    fun `exact title matches with high score`() {
        val score = AnimeTitleMatcher.calculateMatchScore("Attack on Titan", "Attack on Titan")
        assertTrue("Expected score >= 0.9, got $score", score >= 0.9)
    }

    @Test
    fun `season mismatch strictly rejected with zero score`() {
        // Battle Through the Heavens Season 5 vs Season 1
        val score = AnimeTitleMatcher.calculateMatchScore(
            "Battle Through the Heavens Season 5",
            "Battle Through the Heavens Season 1"
        )
        assertEquals(0.0, score, 0.001)

        val scoreShort = AnimeTitleMatcher.calculateMatchScore(
            "BTTH S5",
            "BTTH S2"
        )
        assertEquals(0.0, scoreShort, 0.001)
    }

    @Test
    fun `same season matches successfully`() {
        val score = AnimeTitleMatcher.calculateMatchScore(
            "Battle Through the Heavens Season 5",
            "Battle Through the Heavens Season 5"
        )
        assertTrue("Expected matching season score >= 0.8, got $score", score >= 0.8)
    }

    @Test
    fun `score between similar titles meets threshold`() {
        val score = AnimeTitleMatcher.calculateMatchScore(
            "Ansatsu Kyoushitsu",
            "Ansatsu Kyoushitsu"
        )
        assertTrue("Expected score >= 0.75, got $score", score >= 0.75)
    }

    @Test
    fun `completely different title has low score`() {
        val score = AnimeTitleMatcher.calculateMatchScore(
            "One Piece",
            "Another Completely Different Show"
        )
        assertTrue("Expected score < 0.5, got $score", score < 0.5)
    }
}
