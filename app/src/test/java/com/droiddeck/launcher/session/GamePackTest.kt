package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

class GamePackTest {
    private val tmp = ArrayList<File>()

    @After
    fun tearDown() {
        tmp.forEach { it.deleteRecursively() }
        tmp.clear()
    }

    private fun tempDir(): File {
        val dir = Files.createTempDirectory("gamepack-test").toFile()
        tmp += dir
        return dir
    }

    private object CopyCodec : PackCodec {
        override fun compress(out: OutputStream, level: Int): OutputStream = out
        override fun decompress(input: InputStream): InputStream = input
    }

    private object CorruptCodec : PackCodec {
        override fun compress(out: OutputStream, level: Int): OutputStream = out
        override fun decompress(input: InputStream): InputStream = object : InputStream() {
            private var first = true
            override fun read(): Int {
                val b = input.read()
                if (b < 0) return -1
                if (first) {
                    first = false
                    return b xor 0xFF
                }
                return b
            }
        }
    }

    private fun gameDir(): File {
        val root = tempDir()
        File(root, "a.txt").writeText("hello game")
        File(root, "sub").mkdirs()
        File(root, "sub/b.bin").writeBytes(ByteArray(1024) { (it % 251).toByte() })
        File(root, "empty.dat").writeBytes(ByteArray(0))
        return root
    }

    @Test
    fun `stream hash matches file hash on every byte value`() {
        val file = File(tempDir(), "allbytes.bin")
        file.writeBytes(ByteArray(256) { it.toByte() })
        assertEquals(Hashes.sha256(file), hashStream(file.inputStream().buffered(1 shl 16)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashStream("abc".byteInputStream()))
        assertEquals(Hashes.sha256(file), hashFile(file))
    }

    @Test
    fun `required free is 1_1 times the largest file`() {
        assertEquals(0L, requiredFreeBytes(0L))
        assertEquals(1100L, requiredFreeBytes(1000L))
        assertEquals(1_357_857_178L, requiredFreeBytes(1_234_415_616L))
    }

    @Test
    fun `space gate refuses below required warns below double allows at double`() {
        assertEquals(SpaceDecision.REFUSE, spaceDecision(1099L, 1100L))
        assertEquals(SpaceDecision.WARN, spaceDecision(1100L, 1100L))
        assertEquals(SpaceDecision.WARN, spaceDecision(2199L, 1100L))
        assertEquals(SpaceDecision.ALLOW, spaceDecision(2200L, 1100L))
        assertEquals(SpaceDecision.ALLOW, spaceDecision(0L, 0L))
    }

    @Test
    fun `packed estimate is the 20 to 55 percent saving band`() {
        val range = estimatePackedRange(8_599_356_701L)
        assertEquals(3_869_710_515L, range.first)
        assertEquals(6_879_485_360L, range.last)
    }

    @Test
    fun `duration estimates match the measured pack rates`() {
        assertEquals(1_910_968L, estimateDurationMs(8_599_356_701L, PackMethod.MAX))
        assertEquals(573_290L, estimateDurationMs(8_599_356_701L, PackMethod.FAST))
    }

    @Test
    fun `launch is blocked when any file is packed`() {
        assertTrue(canLaunch(null))
        assertFalse(isPacked(null))
        val orig = GamePackManifest(appId = 1, method = PackMethod.MAX, files = listOf(
            PackFileEntry("a.txt", 10L, 0L, "", PackFileStatus.ORIG),
        ))
        assertTrue(canLaunch(orig))
        val packed = orig.copy(files = listOf(
            PackFileEntry("a.txt", 10L, 4L, "abc", PackFileStatus.PACKED),
            PackFileEntry("b.txt", 20L, 0L, "", PackFileStatus.ORIG),
        ))
        assertFalse(canLaunch(packed))
        assertTrue(isPacked(packed))
    }

    @Test
    fun `manifest json round trips special paths and rejects bad input`() {
        val manifest = GamePackManifest(appId = 1233410, method = PackMethod.FAST, files = listOf(
            PackFileEntry("dir with spaces/a\"b\\c.txt", 100L, 40L, "deadbeef", PackFileStatus.PACKED),
            PackFileEntry("لعبة/file.dat", 0L, 0L, "", PackFileStatus.ORIG),
        ))
        val parsed = GamePackManifest.parse(manifest.toJson())
        assertEquals(manifest, parsed)
        assertNull(GamePackManifest.parse("{bad json"))
        assertNull(GamePackManifest.parse("{\"v\":1,\"appId\":1,\"method\":\"NOPE\",\"files\":[]}"))
        assertNull(GamePackManifest.parse(""))
    }

    @Test
    fun `pack then unpack restores byte identical files`() {
        val root = gameDir()
        val before = listGameFiles(root).associate { relativePath(root, it) to it.readBytes() }
        val manifest = reconcileManifest(root, null, 42, PackMethod.FAST)
        var lastProgress: PackProgress? = null
        var saved: GamePackManifest? = null
        val pack = packGame(root, manifest, PackMethod.FAST, { false }, { lastProgress = it }, { saved = it }, CopyCodec)
        assertNull(pack.error)
        assertFalse(pack.cancelled)
        assertEquals(3, pack.filesDone)
        assertEquals(3, lastProgress?.filesDone)
        assertTrue(listGameFiles(root).isEmpty())
        assertEquals(3, root.walkTopDown().count { it.isFile && it.name.endsWith(".zst") })
        val packedManifest = saved ?: throw AssertionError("manifest was not saved")
        assertTrue(packedManifest.files.all { it.status == PackFileStatus.PACKED })
        assertFalse(canLaunch(packedManifest))

        var unpackSaved: GamePackManifest? = null
        val unpack = unpackGame(root, packedManifest, { false }, {}, { unpackSaved = it }, CopyCodec)
        assertNull(unpack.error)
        assertEquals(3, unpack.filesDone)
        assertEquals(0, root.walkTopDown().count { it.isFile && it.name.endsWith(".zst") })
        val restored = unpackSaved ?: throw AssertionError("manifest was not saved")
        assertTrue(canLaunch(restored))
        for ((rel, bytes) in before) {
            assertTrue(File(root, rel).readBytes().contentEquals(bytes))
        }
    }

    @Test
    fun `pack is resumable after cancellation`() {
        val root = gameDir()
        var stop = false
        val manifest = reconcileManifest(root, null, 42, PackMethod.FAST)
        var saved: GamePackManifest? = null
        val first = packGame(root, manifest, PackMethod.FAST, { stop }, { if (it.filesDone == 1) stop = true }, { saved = it }, CopyCodec)
        assertTrue(first.cancelled)
        assertEquals(1, first.filesDone)
        val partial = saved ?: throw AssertionError("manifest was not saved")
        assertEquals(1, partial.files.count { it.status == PackFileStatus.PACKED })
        assertEquals(2, partial.files.count { it.status == PackFileStatus.ORIG })
        val resumed = packGame(root, partial, PackMethod.FAST, { false }, {}, { saved = it }, CopyCodec)
        assertNull(resumed.error)
        assertEquals(2, resumed.filesDone)
        assertTrue(saved!!.files.all { it.status == PackFileStatus.PACKED })
    }

    @Test
    fun `unpack only touches packed files`() {
        val root = gameDir()
        val manifest = reconcileManifest(root, null, 42, PackMethod.FAST)
        var saved: GamePackManifest? = null
        packGame(root, manifest, PackMethod.FAST, { false }, {}, { saved = it }, CopyCodec)
        val packed = saved!!
        val unpacked = unpackGame(root, packed.copy(files = packed.files.take(1)), { false }, {}, {}, CopyCodec)
        assertEquals(1, unpacked.filesDone)
        assertEquals(1, root.walkTopDown().count { it.isFile && !it.name.endsWith(".zst") })
    }

    @Test
    fun `pack verify failure keeps the original and removes the partial pack`() {
        val root = gameDir()
        val manifest = reconcileManifest(root, null, 42, PackMethod.FAST)
        val result = packGame(root, manifest, PackMethod.FAST, { false }, {}, {}, CorruptCodec)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("verify failed"))
        assertEquals(3, listGameFiles(root).size)
        assertEquals(0, root.walkTopDown().count { it.isFile && it.name.endsWith(".zst") })
    }

    @Test
    fun `reconcile drops lost packs and marks restored originals`() {
        val root = gameDir()
        val manifest = GamePackManifest(appId = 42, method = PackMethod.MAX, files = listOf(
            PackFileEntry("a.txt", 10L, 4L, "aa", PackFileStatus.PACKED),
            PackFileEntry("gone.txt", 10L, 4L, "bb", PackFileStatus.PACKED),
        ))
        File(root, "a.txt.zst").writeBytes(byteArrayOf(1, 2, 3, 4))
        val merged = reconcileManifest(root, manifest, 42, PackMethod.MAX)
        assertEquals(PackFileStatus.ORIG, merged.files.first { it.relPath == "a.txt" }.status)
        assertFalse(merged.files.any { it.relPath == "gone.txt" })
        assertTrue(merged.files.any { it.relPath == "sub/b.bin" })
    }

    @Test
    fun `orphan packed files are found`() {
        val root = gameDir()
        File(root, "stray.zst").writeBytes(byteArrayOf(1))
        val manifest = GamePackManifest(appId = 42, method = PackMethod.MAX, files = listOf(
            PackFileEntry("a.txt", 10L, 4L, "aa", PackFileStatus.PACKED),
        ))
        File(root, "a.txt.zst").writeBytes(byteArrayOf(1, 2, 3, 4))
        val orphans = orphanPackedFiles(root, manifest)
        assertEquals(listOf("stray.zst"), orphans.map { it.name })
    }

    @Test
    fun `crash window orphan is kept when the original is gone`() {
        val root = gameDir()
        File(root, "a.txt").delete()
        File(root, "a.txt.zst").writeBytes(byteArrayOf(1, 2, 3, 4))
        val manifest = GamePackManifest(appId = 42, method = PackMethod.MAX, files = listOf(
            PackFileEntry("a.txt", 10L, 4L, "aa", PackFileStatus.ORIG),
        ))
        assertEquals(listOf("a.txt.zst"), orphanPackedFiles(root, manifest).map { it.name })
        assertTrue(deletableOrphans(root, manifest).isEmpty())
    }

    @Test
    fun `leftover orphan is deletable when the original is present`() {
        val root = gameDir()
        File(root, "a.txt.zst").writeBytes(byteArrayOf(1, 2, 3, 4))
        val manifest = GamePackManifest(appId = 42, method = PackMethod.MAX, files = listOf(
            PackFileEntry("a.txt", 10L, 0L, "", PackFileStatus.ORIG),
        ))
        assertEquals(listOf("a.txt.zst"), deletableOrphans(root, manifest).map { it.name })
    }

    @Test
    fun `orphan with no manifest entry is kept when the original is gone`() {
        val root = gameDir()
        File(root, "a.txt").delete()
        File(root, "a.txt.zst").writeBytes(byteArrayOf(1, 2, 3, 4))
        val manifest = GamePackManifest(appId = 42, method = PackMethod.MAX, files = emptyList())
        assertEquals(listOf("a.txt.zst"), orphanPackedFiles(root, manifest).map { it.name })
        assertTrue(deletableOrphans(root, manifest).isEmpty())
    }

    @Test
    fun `pack app id resolves plain ids and shortcut ids and fails open`() {
        assertEquals(1233410, packAppId("1233410"))
        assertEquals(8400, packAppId("8400"))
        assertEquals(440, packAppId("droiddeck://game/440"))
        val epic = java.lang.Long.toUnsignedString((1233410L shl 32) or 0x02000000L)
        assertEquals(1233410, packAppId(epic))
        val added = java.lang.Long.toUnsignedString((0x80000001L shl 32) or 0x02000000L)
        assertEquals(0x80000001L.toInt(), packAppId(added))
        assertNull(packAppId(""))
        assertNull(packAppId("0"))
        assertNull(packAppId("abc"))
        assertNull(packAppId("app:440"))
        assertNull(packAppId("steam://rungameid/440"))
        assertNull(packAppId("18446744073709551615"))
        assertNull(packAppId("9223372036854775808"))
    }

    @Test
    fun `real zstd round trip restores files`() {
        val root = gameDir()
        val before = listGameFiles(root).associate { relativePath(root, it) to it.readBytes() }
        val manifest = reconcileManifest(root, null, 42, PackMethod.MAX)
        var saved: GamePackManifest? = null
        val pack = packGame(root, manifest, PackMethod.MAX, { false }, {}, { saved = it }, ZstdPackCodec)
        assertNull(pack.error)
        assertEquals(3, pack.filesDone)
        val unpack = unpackGame(root, saved!!, { false }, {}, {}, ZstdPackCodec)
        assertNull(unpack.error)
        for ((rel, bytes) in before) {
            assertTrue(File(root, rel).readBytes().contentEquals(bytes))
        }
    }
}
