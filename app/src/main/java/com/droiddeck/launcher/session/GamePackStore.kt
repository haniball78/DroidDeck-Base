package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.LibraryCache
import java.io.File

data class GamePackSnapshot(
    val appId: Int,
    val name: String,
    val dir: File,
    val fileCount: Int,
    val totalBytes: Long,
    val largestBytes: Long,
    val packedCount: Int,
    val packedBytes: Long,
    val packedOrigBytes: Long,
    val packedLargestBytes: Long,
    val method: PackMethod?,
    val freeBytes: Long,
    val manifest: GamePackManifest?,
) {
    val isPacked: Boolean get() = packedCount > 0
    val requiredBytes: Long get() = requiredFreeBytes(largestBytes)
    val unpackRequiredBytes: Long get() = requiredFreeBytes(packedLargestBytes)
    val decision: SpaceDecision get() = spaceDecision(freeBytes, requiredBytes)
    val unpackDecision: SpaceDecision get() = spaceDecision(freeBytes, unpackRequiredBytes)
}

object GamePackStore {
    fun manifestFile(context: Context, appId: Int): File =
        File(File(context.filesDir, "gamepacks"), "$appId.json")

    fun loadManifest(context: Context, appId: Int): GamePackManifest? {
        val f = manifestFile(context, appId)
        if (!f.isFile) return null
        return runCatching { GamePackManifest.parse(f.readText()) }.getOrNull()
    }

    fun saveManifest(context: Context, appId: Int, manifest: GamePackManifest) {
        val f = manifestFile(context, appId)
        f.parentFile?.mkdirs()
        f.writeText(manifest.toJson())
    }

    fun scan(context: Context, game: Library.SteamGame): GamePackSnapshot? {
        val dir = game.gameFiles?.takeIf { it.isDirectory } ?: return null
        val files = listGameFiles(dir)
        val manifest = loadManifest(context, game.appId)
        val packed = manifest?.files?.filter {
            it.status == PackFileStatus.PACKED && File(dir, it.relPath + ".zst").isFile
        } ?: emptyList()
        return GamePackSnapshot(
            appId = game.appId,
            name = game.name,
            dir = dir,
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            largestBytes = files.maxOfOrNull { it.length() } ?: 0L,
            packedCount = packed.size,
            packedBytes = packed.sumOf { it.packedSize },
            packedOrigBytes = packed.sumOf { it.origSize },
            packedLargestBytes = packed.maxOfOrNull { it.origSize } ?: 0L,
            method = manifest?.method,
            freeBytes = freeBytes(dir),
            manifest = manifest,
        )
    }

    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    fun isPacked(context: Context, game: Library.SteamGame): Boolean {
        val dir = game.gameFiles?.takeIf { it.isDirectory } ?: return false
        val manifest = loadManifest(context, game.appId) ?: return false
        return manifest.files.any { it.status == PackFileStatus.PACKED && File(dir, it.relPath + ".zst").isFile }
    }

    fun isPacked(context: Context, appId: Int): Boolean {
        val manifest = loadManifest(context, appId) ?: return false
        if (manifest.files.none { it.status == PackFileStatus.PACKED }) return false
        val dir = LibraryCache.load(context).firstOrNull { it.appId == appId }?.gameFiles?.takeIf { it.isDirectory }
            ?: Library.steamGames(context).firstOrNull { it.appId == appId }?.gameFiles?.takeIf { it.isDirectory }
            ?: return false
        return manifest.files.any { it.status == PackFileStatus.PACKED && File(dir, it.relPath + ".zst").isFile }
    }
}
