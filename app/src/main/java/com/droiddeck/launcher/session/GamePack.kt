package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.frontend.GameLaunchLink
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class PackMethod(val level: Int, val bytesPerSec: Long) {
    MAX(19, 4_500_000L),
    FAST(12, 15_000_000L),
}

enum class PackFileStatus { ORIG, PACKED }

data class PackFileEntry(
    val relPath: String,
    val origSize: Long,
    val packedSize: Long,
    val sha256: String,
    val status: PackFileStatus,
)

data class GamePackManifest(
    val version: Int = 1,
    val appId: Int,
    val method: PackMethod,
    val files: List<PackFileEntry>,
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"v\":").append(version).append(",\"appId\":").append(appId)
            .append(",\"method\":\"").append(method.name).append("\",\"files\":[")
        files.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"p\":").append(jsonString(f.relPath))
                .append(",\"o\":").append(f.origSize)
                .append(",\"z\":").append(f.packedSize)
                .append(",\"h\":").append(jsonString(f.sha256))
                .append(",\"s\":").append(if (f.status == PackFileStatus.PACKED) 1 else 0).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        fun parse(text: String): GamePackManifest? = runCatching {
            val root = JsonParser(text).parseValue() as? Map<*, *> ?: return null
            val version = (root["v"] as? Number)?.toInt() ?: return null
            val appId = (root["appId"] as? Number)?.toInt() ?: return null
            val method = PackMethod.valueOf(root["method"] as? String ?: return null)
            val rawFiles = root["files"] as? List<*> ?: return null
            val files = rawFiles.map { raw ->
                val m = raw as? Map<*, *> ?: return null
                PackFileEntry(
                    relPath = m["p"] as? String ?: return null,
                    origSize = (m["o"] as? Number)?.toLong() ?: return null,
                    packedSize = (m["z"] as? Number)?.toLong() ?: return null,
                    sha256 = m["h"] as? String ?: return null,
                    status = if ((m["s"] as? Number)?.toInt() == 1) PackFileStatus.PACKED else PackFileStatus.ORIG,
                )
            }
            GamePackManifest(version, appId, method, files)
        }.getOrNull()
    }
}

internal fun jsonString(value: String): String {
    val sb = StringBuilder().append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}

internal class JsonParser(private val text: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWs()
        return when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos++
            return out
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            out[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return out
                }
                else -> throw IllegalStateException("bad object")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') {
            pos++
            return out
        }
        while (true) {
            out += parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return out
                }
                else -> throw IllegalStateException("bad array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    val e = text[pos++]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalStateException("bad escape")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw IllegalStateException("unterminated string")
    }

    private fun parseBoolean(): Boolean {
        return if (text.startsWith("true", pos)) {
            pos += 4
            true
        } else if (text.startsWith("false", pos)) {
            pos += 5
            false
        } else throw IllegalStateException("bad literal")
    }

    private fun parseNull(): Any? {
        if (!text.startsWith("null", pos)) throw IllegalStateException("bad literal")
        pos += 4
        return null
    }

    private fun parseNumber(): Long {
        val start = pos
        while (pos < text.length && (text[pos] in "-+0123456789.eE")) pos++
        val raw = text.substring(start, pos)
        return raw.toLongOrNull() ?: raw.toDouble().toLong()
    }

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else throw IllegalStateException("eof")

    private fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) throw IllegalStateException("expected $c")
        pos++
    }
}

fun requiredFreeBytes(largestOrigFileBytes: Long): Long {
    if (largestOrigFileBytes <= 0L) return 0L
    return (largestOrigFileBytes * 11 + 9) / 10
}

enum class SpaceDecision { ALLOW, WARN, REFUSE }

fun spaceDecision(freeBytes: Long, requiredBytes: Long): SpaceDecision = when {
    freeBytes < requiredBytes -> SpaceDecision.REFUSE
    freeBytes < requiredBytes * 2 -> SpaceDecision.WARN
    else -> SpaceDecision.ALLOW
}

fun estimatePackedRange(origTotalBytes: Long): LongRange {
    val low = origTotalBytes * 45 / 100
    val high = origTotalBytes * 80 / 100
    return low..high
}

fun estimateDurationMs(origTotalBytes: Long, method: PackMethod): Long =
    if (method.bytesPerSec <= 0L) 0L else origTotalBytes * 1000 / method.bytesPerSec

fun canLaunch(manifest: GamePackManifest?): Boolean =
    manifest == null || manifest.files.none { it.status == PackFileStatus.PACKED }

fun isPacked(manifest: GamePackManifest?): Boolean = !canLaunch(manifest)

// A launch id is a plain Steam app id, or a Steam shortcut id (appId << 32) | 0x02000000
// as produced for Epic and added games; anything else launches unchanged.
fun packAppId(gameId: String): Int? {
    val id = GameLaunchLink.parse(gameId) ?: gameId
    if (!GameLaunchLink.validId(id)) return null
    val value = id.toULong()
    if (value <= Int.MAX_VALUE.toULong()) return value.toInt()
    if (value and 0xFFFF_FFFFuL == 0x0200_0000uL) {
        val appId = value shr 32
        if (appId in 1uL..0xFFFF_FFFFuL) return appId.toInt()
    }
    return null
}

fun hashStream(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 16)
    var n: Int
    while (input.read(buf).also { n = it } >= 0) digest.update(buf, 0, n)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun hashFile(file: File): String = Hashes.sha256(file)

fun relativePath(root: File, file: File): String {
    val rootPath = root.canonicalPath
    val filePath = file.canonicalPath
    require(filePath.startsWith(rootPath + File.separator)) { "file outside game dir" }
    return filePath.removePrefix(rootPath + File.separator).replace(File.separatorChar, '/')
}

fun listGameFiles(root: File): List<File> {
    val out = ArrayList<File>()
    val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: return out
    if (!rootCanon.isDirectory) return out
    val seenDirs = HashSet<String>()
    fun walk(dir: File) {
        val canon = runCatching { dir.canonicalFile }.getOrNull() ?: return
        if (!seenDirs.add(canon.path)) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            val childCanon = runCatching { child.canonicalFile }.getOrNull() ?: continue
            if (childCanon != rootCanon && !childCanon.path.startsWith(rootCanon.path + File.separator)) continue
            if (child.isDirectory) walk(child)
            else if (child.isFile && !child.name.endsWith(".zst")) out.add(child)
        }
    }
    walk(rootCanon)
    return out.sortedBy { runCatching { relativePath(rootCanon, it) }.getOrNull() ?: it.name }
}

fun reconcileManifest(root: File, manifest: GamePackManifest?, appId: Int, method: PackMethod): GamePackManifest {
    val byPath = manifest?.files?.associateBy { it.relPath } ?: emptyMap()
    val merged = ArrayList<PackFileEntry>()
    for (file in listGameFiles(root)) {
        val rel = relativePath(root, file)
        val old = byPath[rel]
        when {
            old == null -> merged += PackFileEntry(rel, file.length(), 0L, "", PackFileStatus.ORIG)
            old.status == PackFileStatus.PACKED -> merged += old.copy(status = PackFileStatus.ORIG, packedSize = 0L)
            else -> merged += old.copy(origSize = file.length())
        }
    }
    for (old in manifest?.files ?: emptyList()) {
        if (old.status != PackFileStatus.PACKED) continue
        if (merged.any { it.relPath == old.relPath }) continue
        val zst = File(root, old.relPath + ".zst")
        if (zst.isFile && zst.length() == old.packedSize) merged += old
    }
    merged.sortBy { it.relPath }
    return GamePackManifest(appId = appId, method = manifest?.method ?: method, files = merged)
}

fun orphanPackedFiles(root: File, manifest: GamePackManifest): List<File> {
    val packedPaths = manifest.files.filter { it.status == PackFileStatus.PACKED }.map { it.relPath }.toSet()
    val out = ArrayList<File>()
    fun walk(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) walk(child)
            else if (child.isFile && child.name.endsWith(".zst")) {
                val rel = runCatching { relativePath(root, child) }.getOrNull() ?: continue
                if (rel.removeSuffix(".zst") !in packedPaths) out += child
            }
        }
    }
    if (root.isDirectory) walk(root)
    return out
}

// An orphan .zst is deletable only when its original still exists: between the
// original's deletion and the manifest save, the .zst is the only copy left.
fun deletableOrphans(root: File, manifest: GamePackManifest): List<File> {
    return orphanPackedFiles(root, manifest).filter { zst ->
        val rel = runCatching { relativePath(root, zst) }.getOrNull()?.removeSuffix(".zst") ?: return@filter false
        File(root, rel).isFile
    }
}

interface PackCodec {
    fun compress(out: OutputStream, level: Int): OutputStream
    fun decompress(input: InputStream): InputStream
}

object ZstdPackCodec : PackCodec {
    override fun compress(out: OutputStream, level: Int): OutputStream {
        val z = ZstdOutputStream(BufferedOutputStream(out, 1 shl 16), level)
        runCatching { z.setWorkers(4) }
        return z
    }

    override fun decompress(input: InputStream): InputStream =
        ZstdInputStream(BufferedInputStream(input, 1 shl 16))
}

data class PackProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long) {
    val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

data class PackRunResult(val filesDone: Int, val cancelled: Boolean, val error: String?)

private fun copyStream(input: InputStream, out: OutputStream) {
    val buf = ByteArray(1 shl 16)
    var n: Int
    while (input.read(buf).also { n = it } >= 0) out.write(buf, 0, n)
}

fun packGame(
    root: File,
    manifest: GamePackManifest,
    method: PackMethod,
    shouldStop: () -> Boolean,
    onProgress: (PackProgress) -> Unit,
    save: (GamePackManifest) -> Unit,
    codec: PackCodec = ZstdPackCodec,
): PackRunResult {
    val entries = manifest.files.toMutableList()
    val toPack = entries.filter { it.status == PackFileStatus.ORIG }
    val bytesTotal = toPack.sumOf { it.origSize }
    var bytesDone = 0L
    var filesDone = 0
    onProgress(PackProgress(0, toPack.size, 0L, bytesTotal))
    for (entry in toPack) {
        if (shouldStop()) return PackRunResult(filesDone, cancelled = true, error = null)
        val file = File(root, entry.relPath)
        val zst = File(root, entry.relPath + ".zst")
        val result = runCatching {
            val sha = hashFile(file)
            codec.compress(zst.outputStream(), method.level).use { out ->
                file.inputStream().buffered(1 shl 16).use { input -> copyStream(input, out) }
            }
            val packedSize = zst.length()
            val check = zst.inputStream().buffered(1 shl 16).use { input -> hashStream(codec.decompress(input)) }
            if (check != sha) {
                zst.delete()
                return PackRunResult(filesDone, cancelled = false, error = "verify failed: ${entry.relPath}")
            }
            if (!file.delete()) return PackRunResult(filesDone, cancelled = false, error = "cannot remove original: ${entry.relPath}")
            val index = entries.indexOfFirst { it.relPath == entry.relPath }
            entries[index] = entry.copy(packedSize = packedSize, sha256 = sha, status = PackFileStatus.PACKED)
            save(manifest.copy(files = entries.toList()))
            bytesDone += entry.origSize
            filesDone++
            onProgress(PackProgress(filesDone, toPack.size, bytesDone, bytesTotal))
        }
        result.exceptionOrNull()?.let { return PackRunResult(filesDone, cancelled = false, error = it.message ?: "pack failed: ${entry.relPath}") }
    }
    return PackRunResult(filesDone, cancelled = false, error = null)
}

fun unpackGame(
    root: File,
    manifest: GamePackManifest,
    shouldStop: () -> Boolean,
    onProgress: (PackProgress) -> Unit,
    save: (GamePackManifest) -> Unit,
    codec: PackCodec = ZstdPackCodec,
): PackRunResult {
    val entries = manifest.files.toMutableList()
    val toUnpack = entries.filter { it.status == PackFileStatus.PACKED }
    val bytesTotal = toUnpack.sumOf { it.origSize }
    var bytesDone = 0L
    var filesDone = 0
    onProgress(PackProgress(0, toUnpack.size, 0L, bytesTotal))
    for (entry in toUnpack) {
        if (shouldStop()) return PackRunResult(filesDone, cancelled = true, error = null)
        val file = File(root, entry.relPath)
        val zst = File(root, entry.relPath + ".zst")
        val result = runCatching {
            file.parentFile?.mkdirs()
            codec.decompress(zst.inputStream().buffered(1 shl 16)).use { input ->
                file.outputStream().buffered(1 shl 16).use { out -> copyStream(input, out) }
            }
            val check = hashFile(file)
            if (check != entry.sha256) {
                file.delete()
                return PackRunResult(filesDone, cancelled = false, error = "verify failed: ${entry.relPath}")
            }
            if (!zst.delete()) return PackRunResult(filesDone, cancelled = false, error = "cannot remove packed file: ${entry.relPath}")
            val index = entries.indexOfFirst { it.relPath == entry.relPath }
            entries[index] = entry.copy(packedSize = 0L, status = PackFileStatus.ORIG)
            save(manifest.copy(files = entries.toList()))
            bytesDone += entry.origSize
            filesDone++
            onProgress(PackProgress(filesDone, toUnpack.size, bytesDone, bytesTotal))
        }
        result.exceptionOrNull()?.let { return PackRunResult(filesDone, cancelled = false, error = it.message ?: "unpack failed: ${entry.relPath}") }
    }
    return PackRunResult(filesDone, cancelled = false, error = null)
}
