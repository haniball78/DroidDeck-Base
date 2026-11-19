package com.droiddeck.launcher.session

/**
 * Rewrites max_num_ref_frames in an H.264 SPS to 1. Some decoders keep a
 * deep reference pool and only release a frame after several more arrive;
 * capping the SPS to a single reference lets the decoder release sooner.
 * The unit is returned unchanged when it is not an SPS, when the value
 * is already 1, or when any parse step fails.
 */
object SpsFixup {
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    data class SpsFields(
        val profileIdc: Int,
        val levelIdc: Int,
        val numRefFrames: Int,
        val picWidthInMbsMinus1: Int,
        val picHeightInMapUnitsMinus1: Int,
    )

    fun parseSps(unit: ByteArray): SpsFields? {
        val rbsp = extractRbsp(unit) ?: return null
        val reader = BitReader(rbsp)
        return parseFields(reader)
    }

    fun readNumRefFrames(unit: ByteArray): Int? = parseSps(unit)?.numRefFrames

    fun rewriteNumRefFrames(unit: ByteArray, target: Int = 1): ByteArray {
        val rbsp = extractRbsp(unit) ?: return unit
        val reader = BitReader(rbsp)
        val fields = parseFields(reader, recordRefFramesPos = true) ?: return unit
        val (refStart, refEnd) = reader.refFramesPos ?: return unit
        if (fields.numRefFrames <= target) return unit
        val writer = BitWriter()
        writer.writeBits(rbsp, 0, refStart)
        writer.writeUe(target)
        writer.writeBits(rbsp, refEnd, reader.totalBits)
        val newRbsp = insertEmulationPrevention(writer.toByteArray())
        val headerLen = startCodeLength(unit)
        val out = ByteArray(headerLen + 1 + newRbsp.size)
        unit.copyInto(out, 0, 0, headerLen + 1)
        newRbsp.copyInto(out, headerLen + 1)
        return out
    }

    private fun parseFields(reader: BitReader, recordRefFramesPos: Boolean = false): SpsFields? {
        val profileIdc = reader.readBits(8) ?: return null
        reader.readBits(8) ?: return null
        val levelIdc = reader.readBits(8) ?: return null
        reader.readUe() ?: return null
        if (profileIdc in HIGH_PROFILES) {
            val chromaFormatIdc = reader.readUe() ?: return null
            if (chromaFormatIdc == 3) reader.readBits(1) ?: return null
            reader.readUe() ?: return null
            reader.readUe() ?: return null
            reader.readBits(1) ?: return null
            val scalingMatrix = reader.readBits(1) ?: return null
            if (scalingMatrix == 1) {
                val count = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0 until count) {
                    val present = reader.readBits(1) ?: return null
                    if (present == 1) {
                        val size = if (i < 6) 16 else 64
                        var nextScale = 8
                        for (j in 0 until size) {
                            val delta = reader.readSe() ?: return null
                            nextScale = (nextScale + delta + 256) % 256
                            if (nextScale == 0) break
                        }
                    }
                }
            }
        }
        reader.readUe() ?: return null
        val picOrderCntType = reader.readUe() ?: return null
        when (picOrderCntType) {
            0 -> reader.readUe() ?: return null
            1 -> {
                reader.readBits(1) ?: return null
                reader.readSe() ?: return null
                reader.readSe() ?: return null
                val cycle = reader.readUe() ?: return null
                repeat(cycle) { reader.readSe() ?: return null }
            }
        }
        val refStart = reader.bitPos
        val numRefFrames = reader.readUe() ?: return null
        val refEnd = reader.bitPos
        if (recordRefFramesPos) reader.refFramesPos = refStart to refEnd
        reader.readBits(1) ?: return null
        val width = reader.readUe() ?: return null
        val height = reader.readUe() ?: return null
        return SpsFields(profileIdc, levelIdc, numRefFrames, width, height)
    }

    private fun startCodeLength(unit: ByteArray): Int {
        if (unit.size >= 4 && unit[0] == 0.toByte() && unit[1] == 0.toByte() &&
            unit[2] == 0.toByte() && unit[3] == 1.toByte()
        ) return 4
        if (unit.size >= 3 && unit[0] == 0.toByte() && unit[1] == 0.toByte() && unit[2] == 1.toByte()) return 3
        return -1
    }

    /** Strips the start code and NAL header, then removes emulation-prevention bytes. */
    private fun extractRbsp(unit: ByteArray): ByteArray? {
        val headerLen = startCodeLength(unit)
        if (headerLen < 0 || unit.size <= headerLen + 1) return null
        if (unit[headerLen].toInt() and 0x1F != 7) return null
        val body = unit.copyOfRange(headerLen + 1, unit.size)
        val out = ByteArrayOutput()
        var zeros = 0
        for (b in body) {
            if (zeros >= 2 && b == 0x03.toByte()) {
                zeros = 0
                continue
            }
            out.add(b)
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun insertEmulationPrevention(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutput()
        var zeros = 0
        for (b in rbsp) {
            if (zeros >= 2 && b.toInt() and 0xFF <= 0x03) {
                out.add(0x03)
                zeros = 0
            }
            out.add(b)
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val bytes: ByteArray) {
        var bitPos = 0
        var refFramesPos: Pair<Int, Int>? = null
        val totalBits: Int get() = bytes.size * 8

        fun readBits(n: Int): Int? {
            if (n < 0 || bitPos + n > totalBits) return null
            var value = 0
            for (i in 0 until n) {
                val byte = bytes[bitPos / 8].toInt() and 0xFF
                val bit = (byte shr (7 - bitPos % 8)) and 1
                value = value shl 1 or bit
                bitPos++
            }
            return value
        }

        fun readUe(): Int? {
            var zeros = 0
            while (true) {
                val bit = readBits(1) ?: return null
                if (bit == 1) break
                zeros++
                if (zeros > 31) return null
            }
            val suffix = if (zeros > 0) readBits(zeros) ?: return null else 0
            return (1 shl zeros) - 1 + suffix
        }

        fun readSe(): Int? {
            val k = readUe() ?: return null
            return if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    private class BitWriter {
        private val bits = ArrayList<Boolean>()

        fun writeBits(bytes: ByteArray, fromBit: Int, toBit: Int) {
            for (pos in fromBit until toBit) {
                val byte = bytes[pos / 8].toInt() and 0xFF
                bits.add(((byte shr (7 - pos % 8)) and 1) == 1)
            }
        }

        fun writeUe(value: Int) {
            val codeNum = value + 1
            val leadingZeros = 31 - Integer.numberOfLeadingZeros(codeNum)
            repeat(leadingZeros) { bits.add(false) }
            for (i in leadingZeros downTo 0) bits.add(((codeNum shr i) and 1) == 1)
        }

        fun toByteArray(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) {
                if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return out
        }
    }

    private class ByteArrayOutput {
        private val bytes = ArrayList<Byte>()
        fun add(b: Byte) = bytes.add(b)
        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }
    }
}
