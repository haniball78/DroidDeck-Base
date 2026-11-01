package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagesVerifyChunksTest {

    @Test
    fun verifyChunksCoverEveryTargetOnce() {
        val targets = (1..60).map { "com.example.app$it" }
        val chunks = AppFreezerEngine.packagesVerifyChunkCmds(targets)
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].contains("com.example.app1"))
        assertTrue(chunks[0].contains("com.example.app25"))
        assertTrue(!chunks[0].contains("com.example.app26"))
        assertTrue(chunks[2].contains("com.example.app60"))
        assertTrue(chunks.all { !it.contains("wc -l") == false })
    }

    @Test
    fun namesChunksCoverEveryTargetOnce() {
        val targets = listOf("com.example.a", "com.example.b")
        val chunks = AppFreezerEngine.packagesVerifyNamesChunkCmds(targets)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("com.example.a"))
        assertTrue(chunks[0].endsWith("; true"))
    }

    @Test
    fun sumCountsAddsChunksAndRejectsBadOutput() {
        assertEquals(5, PackagesVerifyChunks.sumCounts(listOf("2\n", "3\n")))
        assertEquals(0, PackagesVerifyChunks.sumCounts(listOf("0\n")))
        assertNull(PackagesVerifyChunks.sumCounts(listOf("2\n", "not a number\n")))
    }
}
