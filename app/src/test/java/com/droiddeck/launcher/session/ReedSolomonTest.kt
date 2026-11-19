package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class ReedSolomonTest {
    private val random = Random(42)

    private fun randomShard(size: Int = 64): ByteArray = ByteArray(size) { random.nextInt(256).toByte() }

    @Test
    fun `parity is deterministic and round-trips with no loss`() {
        val data = List(5) { randomShard() }
        val parity = ReedSolomon.encodeParity(data, 2)
        assertEquals(2, parity.size)
        assertEquals(parity.map { it.toList() }, ReedSolomon.encodeParity(data, 2).map { it.toList() })
        val shards = HashMap<Int, ByteArray>()
        data.forEachIndexed { i, shard -> shards[i] = shard }
        parity.forEachIndexed { p, shard -> shards[5 + p] = shard }
        val decoded = ReedSolomon.decode(shards, 5, 2)
        assertTrue(decoded != null)
        for (i in data.indices) assertArrayEquals(data[i], decoded!![i])
    }

    @Test
    fun `one lost data shard recovers from a single parity shard`() {
        val data = List(4) { randomShard() }
        val parity = ReedSolomon.encodeParity(data, 1)
        val shards = HashMap<Int, ByteArray>()
        shards[0] = data[0]
        shards[2] = data[2]
        shards[3] = data[3]
        shards[4] = parity[0]
        val decoded = ReedSolomon.decode(shards, 4, 1)
        assertTrue(decoded != null)
        for (i in data.indices) assertArrayEquals(data[i], decoded!![i])
    }

    @Test
    fun `two lost data shards recover from two parity shards`() {
        val data = List(6) { randomShard() }
        val parity = ReedSolomon.encodeParity(data, 2)
        val shards = HashMap<Int, ByteArray>()
        shards[0] = data[0]
        shards[2] = data[2]
        shards[4] = data[4]
        shards[5] = data[5]
        shards[6] = parity[0]
        shards[7] = parity[1]
        val decoded = ReedSolomon.decode(shards, 6, 2)
        assertTrue(decoded != null)
        for (i in data.indices) assertArrayEquals(data[i], decoded!![i])
    }

    @Test
    fun `more losses than parity shards is unrecoverable`() {
        val data = List(4) { randomShard() }
        val parity = ReedSolomon.encodeParity(data, 1)
        val shards = HashMap<Int, ByteArray>()
        shards[0] = data[0]
        shards[3] = data[3]
        shards[4] = parity[0]
        assertNull(ReedSolomon.decode(shards, 4, 1))
    }

    @Test
    fun `a lost parity shard needs no recovery`() {
        val data = List(3) { randomShard() }
        val parity = ReedSolomon.encodeParity(data, 1)
        val shards = HashMap<Int, ByteArray>()
        data.forEachIndexed { i, shard -> shards[i] = shard }
        val decoded = ReedSolomon.decode(shards, 3, 1)
        assertTrue(decoded != null)
        for (i in data.indices) assertArrayEquals(data[i], decoded!![i])
        assertEquals(1, parity.size)
    }

    @Test
    fun `a single data shard with no parity recovers only when present`() {
        val data = listOf(randomShard())
        assertNull(ReedSolomon.decode(emptyMap(), 1, 0))
        val decoded = ReedSolomon.decode(mapOf(0 to data[0]), 1, 0)
        assertTrue(decoded != null)
        assertArrayEquals(data[0], decoded!![0])
    }

    @Test
    fun `twenty percent parity recovers a fifth of a large block`() {
        val data = List(200) { randomShard(1024) }
        val parity = ReedSolomon.encodeParity(data, 40)
        assertEquals(40, parity.size)
        val shards = HashMap<Int, ByteArray>()
        for (i in data.indices) {
            if (i % 5 != 0) shards[i] = data[i]
        }
        parity.forEachIndexed { p, shard -> shards[200 + p] = shard }
        val decoded = ReedSolomon.decode(shards, 200, 40)
        assertTrue(decoded != null)
        for (i in data.indices) assertArrayEquals(data[i], decoded!![i])
    }
}
