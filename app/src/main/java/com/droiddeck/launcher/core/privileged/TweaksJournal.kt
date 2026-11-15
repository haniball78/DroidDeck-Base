package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Log
import java.io.File

enum class JournalVerifyMode {
    VALUE,
    CONTAINS,
    NOT_CONTAINS,
    UNVERIFIED,
}

data class JournalItem(
    val key: String,
    val original: String?,
    val restoreCmd: String,
    val verifyCmd: String?,
    val verifyMode: JournalVerifyMode,
    val persistent: Boolean,
    val generation: Int,
    val verifyExpect: String? = null,
)

internal object JournalCodec {
    fun encode(items: List<JournalItem>): String {
        val body = items.joinToString(",") { item ->
            val verify = if (item.verifyCmd == null) "null" else "\"${escape(item.verifyCmd)}\""
            val original = if (item.original == null) "null" else "\"${escape(item.original)}\""
            val mode = when (item.verifyMode) {
                JournalVerifyMode.VALUE -> "value"
                JournalVerifyMode.CONTAINS -> "contains"
                JournalVerifyMode.NOT_CONTAINS -> "not_contains"
                JournalVerifyMode.UNVERIFIED -> "unverified"
            }
            val expect = if (item.verifyExpect == null) "null" else "\"${escape(item.verifyExpect)}\""
            "{\"key\":\"${escape(item.key)}\",\"original\":$original," +
                "\"restore\":\"${escape(item.restoreCmd)}\",\"verify\":$verify," +
                "\"mode\":\"$mode\",\"persistent\":${item.persistent},\"generation\":${item.generation}," +
                "\"verifyExpect\":$expect}"
        }
        return "{\"version\":1,\"items\":[$body]}"
    }

    data class PartialDecode(val items: List<JournalItem>, val badItems: List<String>)

    fun decode(text: String): List<JournalItem>? {
        val partial = decodePartial(text) ?: return null
        return if (partial.badItems.isEmpty()) partial.items else null
    }

    fun decodePartial(text: String): PartialDecode? {
        return try {
            val parsed = JsonParser(text).parseDocument()
            if (parsed !is Map<*, *>) return null
            val version = (parsed["version"] as? Number)?.toInt() ?: return null
            if (version != 1) return null
            val rawItems = parsed["items"] as? List<*> ?: return null
            val items = ArrayList<JournalItem>()
            val badItems = ArrayList<String>()
            for (raw in rawItems) {
                val item = decodeItem(raw)
                if (item == null) badItems.add(stringify(raw)) else items.add(item)
            }
            PartialDecode(items, badItems)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeItem(raw: Any?): JournalItem? {
        val map = raw as? Map<*, *> ?: return null
        val key = map["key"] as? String ?: return null
        if (key.isEmpty()) return null
        val restore = map["restore"] as? String ?: return null
        if (restore.isEmpty()) return null
        val original = map["original"] as? String?
        val verify = map["verify"] as? String?
        val expect = map["verifyExpect"] as? String?
        val mode = when (map["mode"] as? String) {
            "value" -> JournalVerifyMode.VALUE
            "contains" -> JournalVerifyMode.CONTAINS
            "not_contains" -> JournalVerifyMode.NOT_CONTAINS
            "exit", "unverified" -> JournalVerifyMode.UNVERIFIED
            else -> JournalVerifyMode.UNVERIFIED
        }
        val effectiveMode = when (mode) {
            JournalVerifyMode.VALUE -> if (verify == null) JournalVerifyMode.UNVERIFIED else mode
            JournalVerifyMode.CONTAINS, JournalVerifyMode.NOT_CONTAINS ->
                if (verify == null || expect == null) JournalVerifyMode.UNVERIFIED else mode
            JournalVerifyMode.UNVERIFIED -> mode
        }
        val persistent = map["persistent"] as? Boolean ?: false
        val generation = (map["generation"] as? Number)?.toInt() ?: 0
        return JournalItem(key, original, restore, verify, effectiveMode, persistent, generation, expect)
    }

    private fun stringify(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "\"${escape(k.toString())}\":${stringify(v)}" }
            is List<*> -> value.joinToString(",", "[", "]") { stringify(it) }
            else -> "\"${escape(value.toString())}\""
        }
    }

    private fun escape(value: String): String {
        val out = StringBuilder()
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
                }
            }
        }
        return out.toString()
    }

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw IllegalStateException("unexpected end")
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }.also { skipWhitespace() }
        }

        fun parseDocument(): Any? {
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw IllegalStateException("trailing data")
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') throw IllegalStateException("expected :")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (pos >= text.length) throw IllegalStateException("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalStateException("expected , or }")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (pos >= text.length) throw IllegalStateException("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalStateException("expected , or ]")
                }
            }
        }

        private fun parseString(): String {
            if (pos >= text.length || text[pos] != '"') throw IllegalStateException("expected string")
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        when (val esc = text[pos++]) {
                            '\\' -> out.append('\\')
                            '"' -> out.append('"')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalStateException("bad unicode escape")
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalStateException("bad escape")
                        }
                    }
                    else -> out.append(ch)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw IllegalStateException("bad literal")
            pos += word.length
            return value
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "-+eE.")) pos++
            if (start == pos) throw IllegalStateException("bad number")
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalStateException("bad number")
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }

}

internal object JournalRollback {
    data class Failure(
        val key: String,
        val stage: String,
    )

    data class Result(
        val success: Boolean,
        val remaining: List<JournalItem>,
        val failedKey: String?,
        val completed: List<JournalItem> = emptyList(),
        val failedStage: String? = null,
        val failures: List<Failure> = emptyList(),
    )

    fun rollback(
        items: List<JournalItem>,
        execute: (String) -> Int,
        readVerified: (JournalItem) -> String?,
    ): Result {
        val completed = ArrayList<JournalItem>()
        val failures = ArrayList<Failure>()
        for (index in items.indices.reversed()) {
            val item = items[index]
            val stage = rollbackItem(item, execute, readVerified)
            if (stage == null) {
                completed += item
            } else {
                failures.add(Failure(item.key, stage))
            }
        }
        if (failures.isEmpty()) return Result(true, emptyList(), null, completed)
        val failedKeys = failures.map { it.key }.toSet()
        val remaining = items.filter { it.key in failedKeys }
        val first = failures.first()
        return Result(false, remaining, first.key, completed, first.stage, failures)
    }

    private fun rollbackItem(
        item: JournalItem,
        execute: (String) -> Int,
        readVerified: (JournalItem) -> String?,
    ): String? {
        if (!SafetyChecks.isValidSettingValue(item.key, item.original) || item.restoreCmd.contains("Daemon error")) {
            return "corrupt"
        }
        if (execute(item.restoreCmd) != 0) {
            return "execute"
        }
        val verified = when (item.verifyMode) {
            JournalVerifyMode.VALUE -> {
                val raw = readVerified(item) ?: return "verify-unreadable"
                SafetyChecks.parseBackgroundLimit(raw) == item.original
            }
            JournalVerifyMode.CONTAINS -> {
                val output = readVerified(item) ?: return "verify-unreadable"
                output.contains(item.verifyExpect ?: "")
            }
            JournalVerifyMode.NOT_CONTAINS -> {
                val output = readVerified(item) ?: return "verify-unreadable"
                !output.contains(item.verifyExpect ?: "")
            }
            JournalVerifyMode.UNVERIFIED -> true
        }
        return if (verified) null else "verify-mismatch"
    }
}

internal object AppFreezerPackageJournal {
    internal val packagePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val pmEnablePattern = Regex("pm enable ([A-Za-z0-9_.]+)")

    fun parseSuspendedNames(output: String): List<String> {
        val names = ArrayList<String>()
        for (line in output.lines()) {
            val name = line.trim()
            if (packagePattern.matches(name) && !names.contains(name)) names.add(name)
        }
        return names
    }

    fun parseEnabledNames(restoreCmd: String): List<String> {
        return pmEnablePattern.findAll(restoreCmd).map { it.groupValues[1] }.distinct().toList()
    }

    fun splitItem(item: JournalItem, remaining: List<String>): JournalItem {
        return item.copy(
            restoreCmd = AppFreezerEngine.packagesRestoreCmd(remaining, parseEnabledNames(item.restoreCmd)),
            verifyCmd = AppFreezerEngine.packagesVerifyCmd(remaining),
        )
    }
}

internal object SafeRestoreResult {
    // A restore is only successful when both engines report success AND
    // no journal item remains: a remaining item means something was not
    // restored, even if both engines returned true.
    fun succeeded(gameModeOk: Boolean, freezerOk: Boolean, journalRemaining: Int): Boolean =
        gameModeOk && freezerOk && journalRemaining == 0

    fun resultFields(gameModeOk: Boolean, freezerOk: Boolean, journalRemaining: Int): Map<String, String> = mapOf(
        "game_mode_ok" to gameModeOk.toString(),
        "freezer_ok" to freezerOk.toString(),
        "journal_remaining" to journalRemaining.toString(),
        "succeeded" to succeeded(gameModeOk, freezerOk, journalRemaining).toString(),
    )
}

internal object PackagesVerifyChunks {
    fun sumCounts(outputs: List<String>): Int? {
        var total = 0
        for (output in outputs) {
            total += output.trim().toIntOrNull() ?: return null
        }
        return total
    }
}

internal enum class RepairDecision {
    CORRUPT,
    ALREADY_OK,
    RETRY,
    UNREADABLE,
}

internal object JournalRepair {
    fun classify(item: JournalItem, currentValue: String?): RepairDecision {
        if (!SafetyChecks.isValidSettingValue(item.key, item.original) || item.restoreCmd.contains("Daemon error")) {
            return RepairDecision.CORRUPT
        }
        if (currentValue == null) {
            return RepairDecision.UNREADABLE
        }
        if (item.verifyMode == JournalVerifyMode.VALUE) {
            return if (SafetyChecks.parseBackgroundLimit(currentValue) == item.original) RepairDecision.ALREADY_OK else RepairDecision.RETRY
        }
        return RepairDecision.RETRY
    }

    fun rowMatches(scannedGeneration: Int, scannedOriginal: String?, current: JournalItem?): Boolean {
        if (current == null) return false
        if (current.generation != scannedGeneration) return false
        return current.original == scannedOriginal
    }
}

internal object JournalMerge {
    fun merge(current: List<JournalItem>, newItems: List<JournalItem>): List<JournalItem> {
        val merged = current.toMutableList()
        for (item in newItems) {
            val index = merged.indexOfFirst { it.key == item.key }
            if (index < 0) {
                merged.add(item)
            } else if (isFreezerPackagesKey(item.key)) {
                merged[index] = unionFreezerPackages(merged[index], item)
            } else if (isNewerGeneration(item, merged[index])) {
                merged[index] = merged[index].copy(generation = item.generation)
            }
        }
        return merged
    }

    private fun isNewerGeneration(incoming: JournalItem, existing: JournalItem): Boolean =
        incoming.generation != 0 && incoming.generation > existing.generation

    private fun unionFreezerPackages(existing: JournalItem, incoming: JournalItem): JournalItem {
        val targets = (Tweaks.storedTargets(existing.restoreCmd) + Tweaks.storedTargets(incoming.restoreCmd)).distinct()
        if (targets.isEmpty()) {
            Log.w("TweaksJournal", "freezer merge dropped unparseable targets for ${existing.key}")
            return existing
        }
        val disabled = (AppFreezerPackageJournal.parseEnabledNames(existing.restoreCmd) + AppFreezerPackageJournal.parseEnabledNames(incoming.restoreCmd)).distinct()
        val newer = isNewerGeneration(incoming, existing)
        return existing.copy(
            restoreCmd = AppFreezerEngine.packagesRestoreCmd(targets, disabled),
            verifyCmd = AppFreezerEngine.packagesVerifyCmd(targets),
            original = if (newer) incoming.original else existing.original,
            verifyExpect = if (newer) incoming.verifyExpect else existing.verifyExpect,
            generation = if (newer) incoming.generation else existing.generation,
        )
    }
}

object Tweaks {
    private const val TAG = "TweaksJournal"
    private const val JOURNAL_FILE = "safety-journal.json"
    private const val REPAIR_BACKUP_FILE = "safety-journal-repair-backup.json"
    private const val QUARANTINE_FILE = "safety-journal-quarantine.json"
    private val journalLock = Any()

    fun recoveryNeeded(context: Context): Boolean {
        return synchronized(journalLock) {
            val file = journalFile(context)
            file.exists() || AtomicStateFile.bakFile(file).exists() || AtomicStateFile.tmpFile(file).exists()
        }
    }

    // Pure reads do not take the journal lock: the journal file is written
    // by atomic rename, so a concurrent read sees a complete version, and
    // every caller re-validates under the lock before mutating. Holding the
    // lock across a disk read once stalled a rollback for 593ms.
    fun hasKey(context: Context, key: String): Boolean {
        return load(context)?.any { it.key == key } == true
    }

    fun journalSnapshot(context: Context): List<JournalItem> {
        return load(context) ?: emptyList()
    }

    fun recordItems(context: Context, items: List<JournalItem>): Boolean {
        return synchronized(journalLock) {
            if (items.isEmpty()) return@synchronized true
            for (item in items) {
                if (item.verifyMode == JournalVerifyMode.VALUE && item.verifyCmd == null) return@synchronized false
                if ((item.verifyMode == JournalVerifyMode.CONTAINS || item.verifyMode == JournalVerifyMode.NOT_CONTAINS) &&
                    (item.verifyCmd == null || item.verifyExpect == null)
                ) return@synchronized false
            }
            val current = load(context) ?: return@synchronized false
            val saved = save(context, JournalMerge.merge(current, items))
            DiagnosticsLog.log(context, "safety.journal", if (saved) "saved" else "save-failed", fields = mapOf("items" to items.size.toString()))
            saved
        }
    }

    fun recordItem(context: Context, item: JournalItem): Boolean = recordItems(context, listOf(item))

    internal fun effectiveVerifyCmd(item: JournalItem): String? {
        val stored = item.verifyCmd ?: return null
        if (!isFreezerPackagesKey(item.key) || !stored.contains("list packages -s")) return stored
        val targets = storedTargets(item.restoreCmd)
        if (targets.isEmpty()) return stored
        return AppFreezerEngine.packagesVerifyCmd(targets)
    }

    internal fun storedTargets(restoreCmd: String): List<String> {
        val found = ArrayList<String>()
        val packagePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
        for ((index, segment) in restoreCmd.split("unsuspend").withIndex()) {
            if (index == 0) continue
            for (token in segment.trim().split(Regex("\\s+"))) {
                if (token.startsWith("2>")) break
                if (packagePattern.matches(token) && !found.contains(token)) found.add(token)
            }
        }
        return found
    }

    fun removeKey(context: Context, key: String): Boolean {
        return synchronized(journalLock) {
            val current = load(context) ?: return@synchronized false
            save(context, current.filter { it.key != key })
        }
    }

    fun backupJournal(context: Context): Boolean {
        return synchronized(journalLock) {
            val dir = context.applicationContext.noBackupFilesDir
            val file = journalFile(context)
            val source = listOf(file, AtomicStateFile.bakFile(file), AtomicStateFile.tmpFile(file)).firstOrNull { it.exists() }
                ?: return@synchronized true
            val text = try {
                source.readText()
            } catch (_: Exception) {
                return@synchronized false
            }
            if (JournalCodec.decode(text) == null) return@synchronized false
            AtomicStateFile.save(File(dir, REPAIR_BACKUP_FILE), text)
        }
    }

    fun quarantineKey(context: Context, key: String): Boolean {
        return synchronized(journalLock) {
            val current = load(context) ?: return@synchronized false
            val item = current.firstOrNull { it.key == key } ?: return@synchronized false
            val quarantineFile = File(context.applicationContext.noBackupFilesDir, QUARANTINE_FILE)
            val previousText = if (quarantineFile.exists()) runCatching { quarantineFile.readText() }.getOrNull() else null
            if (appendQuarantine(context, listOf(item)).isEmpty()) return@synchronized false
            if (!save(context, current.filter { it.key != key })) {
                if (previousText == null) AtomicStateFile.delete(quarantineFile)
                else AtomicStateFile.save(quarantineFile, previousText)
                save(context, current)
                return@synchronized false
            }
            true
        }
    }

    private fun readQuarantinePartial(context: Context): JournalCodec.PartialDecode? {
        val quarantineFile = File(context.applicationContext.noBackupFilesDir, QUARANTINE_FILE)
        if (!quarantineFile.exists() && !AtomicStateFile.bakFile(quarantineFile).exists() && !AtomicStateFile.tmpFile(quarantineFile).exists()) {
            return JournalCodec.PartialDecode(emptyList(), emptyList())
        }
        return AtomicStateFile.readRecovered(quarantineFile, JournalCodec::decodePartial)
    }

    private fun appendQuarantine(context: Context, items: List<JournalItem>): List<String> {
        val quarantineFile = File(context.applicationContext.noBackupFilesDir, QUARANTINE_FILE)
        val existing = readQuarantinePartial(context) ?: JournalCodec.PartialDecode(emptyList(), emptyList())
        if (existing.badItems.isNotEmpty()) return emptyList()
        val merged = existing.items.toMutableList()
        for (item in items) {
            val index = merged.indexOfFirst { it.key == item.key }
            if (index < 0) merged.add(item) else merged[index] = item
        }
        if (!AtomicStateFile.save(quarantineFile, JournalCodec.encode(merged))) return emptyList()
        return items.map { it.key }
    }

    private fun readVerifiedForItem(context: Context, item: JournalItem): String? {
        if (isFreezerPackagesKey(item.key)) {
            val targets = storedTargets(item.restoreCmd)
            if (targets.isNotEmpty()) {
                val outputs = ArrayList<String>()
                for (cmd in AppFreezerEngine.packagesVerifyChunkCmds(targets)) {
                    val read = execChecked(context, cmd)
                    if (read.exitCode != 0) return null
                    outputs.add(read.output)
                }
                return PackagesVerifyChunks.sumCounts(outputs)?.toString()
            }
        }
        val verifyCmd = effectiveVerifyCmd(item) ?: return null
        val read = execChecked(context, verifyCmd)
        return if (read.exitCode != 0) null else read.output
    }

    fun rollbackOne(context: Context, key: String): Boolean {
        return synchronized(journalLock) {
            val current = load(context) ?: return@synchronized false
            val item = current.firstOrNull { it.key == key } ?: return@synchronized false
            val result = JournalRollback.rollback(
                listOf(item),
                execute = { cmd -> execChecked(context, cmd).exitCode },
                readVerified = { verified -> readVerifiedForItem(context, verified) },
            )
            if (result.success) {
                save(context, current.filter { it.key != key })
            } else {
                false
            }
        }
    }

    fun rollback(context: Context): Boolean {
        return synchronized(journalLock) { rollbackLocked(context) }
    }

    private fun rollbackLocked(context: Context): Boolean {
        var current = load(context) ?: return false
        if (current.isEmpty()) {
            return AtomicStateFile.delete(journalFile(context))
        }
        while (true) {
            DiagnosticsLog.log(context, "safety.journal", "rollback-started", fields = mapOf("items" to current.size.toString()))
            val result = JournalRollback.rollback(
                current,
                execute = { cmd -> execChecked(context, cmd).exitCode },
                readVerified = { item -> readVerifiedForItem(context, item) },
            )
            for (item in result.completed) {
                DiagnosticsLog.log(
                    context, "safety.restore", "restored",
                    fields = mapOf(
                        "key" to item.key,
                        "verify" to if (item.verifyMode == JournalVerifyMode.UNVERIFIED) "unverified" else "verified",
                    ),
                )
            }
            for (failure in result.failures) {
                DiagnosticsLog.log(
                    context, "safety.restore", "failed",
                    fields = mapOf(
                        "key" to failure.key,
                        "verify" to "failed",
                        "stage" to failure.stage,
                    ),
                )
            }
            DiagnosticsLog.log(
                context, "safety.journal", if (result.success) "rollback-finished" else "rollback-failed",
                fields = mapOf("restored" to result.completed.size.toString(), "remaining" to result.remaining.size.toString()),
            )
            if (result.success) {
                return AtomicStateFile.delete(journalFile(context))
            }
            val corruptKeys = result.failures.filter { it.stage == "corrupt" }.map { it.key }.toSet()
            val quarantinedKeys = if (corruptKeys.isEmpty()) {
                emptySet()
            } else {
                val stored = appendQuarantine(context, result.remaining.filter { it.key in corruptKeys }).toSet()
                for (key in stored) {
                    DiagnosticsLog.log(
                        context, "safety.restore", "quarantined",
                        fields = mapOf("key" to key),
                    )
                }
                stored
            }
            val kept = result.remaining.filter { it.key !in quarantinedKeys }
            if (kept.isEmpty()) {
                return AtomicStateFile.delete(journalFile(context))
            }
            val failedKey = result.failedKey
            if (failedKey == null || failedKey in quarantinedKeys || !isFreezerPackagesKey(failedKey)) {
                Log.w(TAG, "rollback failed at $failedKey, journal kept")
                save(context, kept)
                return false
            }
            val failedItem = current.firstOrNull { it.key == failedKey } ?: run {
                save(context, kept)
                return false
            }
            val targets = storedTargets(failedItem.restoreCmd)
            if (targets.isEmpty()) {
                save(context, kept)
                return false
            }
            var bridgeDown = false
            for (target in targets) {
                if (execChecked(context, "cmd package unsuspend --user 0 $target 2>/dev/null").exitCode == -1) {
                    bridgeDown = true
                }
            }
            if (bridgeDown) {
                save(context, kept)
                return false
            }
            val namesOutputs = ArrayList<String>()
            var namesFailed = false
            for (cmd in AppFreezerEngine.packagesVerifyNamesChunkCmds(targets)) {
                val chunkRead = execChecked(context, cmd)
                if (chunkRead.exitCode != 0) {
                    namesFailed = true
                    break
                }
                namesOutputs.add(chunkRead.output)
            }
            if (namesFailed) {
                save(context, kept)
                return false
            }
            val still = AppFreezerPackageJournal.parseSuspendedNames(namesOutputs.joinToString("\n"))
            if (still.any { it !in targets }) {
                save(context, kept)
                return false
            }
            if (still.isEmpty()) {
                // The bulk restore failed, so the one-by-one unsuspend
                // ran instead — but it never re-enables the sticky apps
                // the bulk script disables (pm disable-user). Re-enable
                // them here, or they stay hidden after the session.
                for (pkg in AppFreezerPackageJournal.parseEnabledNames(failedItem.restoreCmd)) {
                    execChecked(context, "pm enable $pkg 2>/dev/null")
                }
                DiagnosticsLog.log(
                    context, "safety.restore", "restored",
                    fields = mapOf("key" to failedItem.key, "verify" to "verified"),
                )
                current = kept.filter { it.key != failedItem.key }
                if (current.isEmpty()) {
                    return AtomicStateFile.delete(journalFile(context))
                }
                save(context, current)
                continue
            }
            DiagnosticsLog.log(
                context, "safety.restore", "still-suspended",
                fields = mapOf(
                    "key" to failedItem.key,
                    "count" to still.size.toString(),
                    "packages" to still.joinToString(","),
                ),
            )
            if (still.size == targets.size) {
                save(context, kept)
                return false
            }
            val split = AppFreezerPackageJournal.splitItem(failedItem, still)
            val remaining = kept.map { if (it.key == failedItem.key) split else it }
            save(context, remaining)
            return false
        }
    }

    fun applyExistingKeyChange(context: Context, key: String, applyCmd: String): Boolean {
        return synchronized(journalLock) {
            if (load(context)?.any { it.key == key } != true) return@synchronized false
            execChecked(context, applyCmd).exitCode == 0
        }
    }

    private fun load(context: Context): List<JournalItem>? {
        val file = journalFile(context)
        if (!file.exists() && !AtomicStateFile.bakFile(file).exists() && !AtomicStateFile.tmpFile(file).exists()) {
            return emptyList()
        }
        val partial = AtomicStateFile.readRecovered(file, JournalCodec::decodePartial) ?: return null
        if (partial.badItems.isEmpty()) return partial.items
        return synchronized(journalLock) {
            val fresh = AtomicStateFile.readRecovered(file, JournalCodec::decodePartial) ?: return@synchronized null
            if (fresh.badItems.isEmpty()) return@synchronized fresh.items
            if (!quarantineBadItems(context, fresh.badItems)) {
                Log.w(TAG, "journal quarantine failed, keeping restorable items")
            }
            if (!save(context, fresh.items)) return@synchronized null
            fresh.items
        }
    }

    private fun quarantineBadItems(context: Context, badItems: List<String>): Boolean {
        val existing = readQuarantinePartial(context)?.items ?: emptyList()
        val fresh = badItems.filter { raw -> existing.none { it.original == raw } }.map { raw ->
            JournalItem(
                key = corruptItemKey(raw),
                original = raw,
                restoreCmd = "true 2>/dev/null",
                verifyCmd = null,
                verifyMode = JournalVerifyMode.UNVERIFIED,
                persistent = true,
                generation = 0,
            )
        }
        if (fresh.isEmpty()) return true
        return appendQuarantine(context, fresh).size == fresh.size
    }

    private fun corruptItemKey(raw: String): String {
        val match = Regex("\"key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw) ?: return "corrupt-item"
        val key = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        return key.ifEmpty { "corrupt-item" }
    }

    private fun save(context: Context, items: List<JournalItem>): Boolean {
        val file = journalFile(context)
        if (items.isEmpty()) return AtomicStateFile.delete(file)
        val saved = AtomicStateFile.save(file, JournalCodec.encode(items))
        if (!saved) Log.w(TAG, "journal save failed")
        return saved
    }

    private fun journalFile(context: Context): File {
        return File(context.applicationContext.noBackupFilesDir, JOURNAL_FILE)
    }

    private fun exec(context: Context, cmd: String): String {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.execute(cmd).output
        } catch (_: Throwable) {
            ""
        }
    }

    private fun execChecked(context: Context, cmd: String): PrivilegedResult {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.executeChecked(cmd)
        } catch (_: Throwable) {
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
    }
}
