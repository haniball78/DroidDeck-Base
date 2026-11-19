package com.droiddeck.launcher.session

/**
 * Reed–Solomon erasure coding over GF(256), written from the standard
 * Vandermonde construction (primitive polynomial 0x11D, generator 2).
 * Encodes parity shards for a block of equal-length data shards and
 * reconstructs missing data shards from any sufficient subset of
 * data + parity shards. Only the missing shards are solved for, so a
 * one-shard loss costs one XOR pass, not a full matrix inversion.
 */
object ReedSolomon {
    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) expTable[i] = expTable[i - 255]
    }

    private fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a] + logTable[b]]
    }

    private fun div(a: Int, b: Int): Int {
        if (a == 0) return 0
        return expTable[logTable[a] - logTable[b] + 255]
    }

    /** Parity row p, data column d of the Vandermonde matrix: 2^(p*d). */
    private fun coef(parityRow: Int, dataCol: Int): Int = expTable[(parityRow * dataCol) % 255]

    /**
     * Parity shards for [dataShards]; every shard must have the same length.
     * Returns [parityCount] shards of that length. Empty when parityCount is 0.
     */
    fun encodeParity(dataShards: List<ByteArray>, parityCount: Int): List<ByteArray> {
        require(parityCount >= 0) { "parityCount must be non-negative" }
        if (parityCount == 0 || dataShards.isEmpty()) return emptyList()
        val size = dataShards[0].size
        require(dataShards.all { it.size == size }) { "all data shards must have the same length" }
        return List(parityCount) { p ->
            val parity = ByteArray(size)
            for (d in dataShards.indices) {
                val c = coef(p, d)
                if (c == 0) continue
                val data = dataShards[d]
                if (c == 1) {
                    for (i in parity.indices) parity[i] = (parity[i].toInt() xor data[i].toInt()).toByte()
                } else {
                    for (i in parity.indices) parity[i] = (parity[i].toInt() xor mul(c, data[i].toInt() and 0xFF)).toByte()
                }
            }
            parity
        }
    }

    /**
     * Reconstructs the data shards from [shards]: keys 0..dataCount-1 are data
     * shards, keys dataCount..dataCount+parityCount-1 are parity shards.
     * Returns the data shards in order, or null when too few shards are present
     * to recover the missing ones. All shards must have the same length.
     */
    fun decode(shards: Map<Int, ByteArray>, dataCount: Int, parityCount: Int): List<ByteArray>? {
        require(dataCount > 0) { "dataCount must be positive" }
        require(parityCount >= 0) { "parityCount must be non-negative" }
        if (shards.isEmpty()) return null
        val size = shards.values.first().size
        require(shards.values.all { it.size == size }) { "all shards must have the same length" }
        val result = arrayOfNulls<ByteArray>(dataCount)
        for (d in 0 until dataCount) result[d] = shards[d]
        val missing = (0 until dataCount).filter { result[it] == null }
        if (missing.isEmpty()) return result.map { it!! }
        val presentParity = (0 until parityCount).mapNotNull { p ->
            shards[dataCount + p]?.let { p to it }
        }
        if (presentParity.size < missing.size) return null
        // Solve the k x k system from the first k present parity equations,
        // where k is the number of missing data shards.
        val k = missing.size
        val matrix = Array(k) { IntArray(k) }
        val rhs = Array(k) { ByteArray(size) }
        for (row in 0 until k) {
            val (p, parityBytes) = presentParity[row]
            for (col in 0 until k) matrix[row][col] = coef(p, missing[col])
            val acc = parityBytes.copyOf()
            for (d in 0 until dataCount) {
                val known = result[d] ?: continue
                val c = coef(p, d)
                if (c == 0) continue
                if (c == 1) {
                    for (i in acc.indices) acc[i] = (acc[i].toInt() xor known[i].toInt()).toByte()
                } else {
                    for (i in acc.indices) acc[i] = (acc[i].toInt() xor mul(c, known[i].toInt() and 0xFF)).toByte()
                }
            }
            rhs[row] = acc
        }
        val solved = solve(matrix, rhs) ?: return null
        for (i in missing.indices) result[missing[i]] = solved[i]
        return result.map { it!! }
    }

    /** Gauss-Jordan elimination over GF(256); null when the matrix is singular. */
    private fun solve(matrix: Array<IntArray>, rhs: Array<ByteArray>): Array<ByteArray>? {
        val n = matrix.size
        if (n == 0) return emptyArray()
        val size = rhs[0].size
        val a = Array(n) { row -> matrix[row].copyOf() }
        val b = Array(n) { row -> rhs[row].copyOf() }
        for (col in 0 until n) {
            var pivot = -1
            for (row in col until n) {
                if (a[row][col] != 0) {
                    pivot = row
                    break
                }
            }
            if (pivot < 0) return null
            if (pivot != col) {
                val tmpA = a[col]; a[col] = a[pivot]; a[pivot] = tmpA
                val tmpB = b[col]; b[col] = b[pivot]; b[pivot] = tmpB
            }
            val inv = div(1, a[col][col])
            if (inv != 1) {
                for (j in col until n) a[col][j] = mul(a[col][j], inv)
                for (i in b[col].indices) b[col][i] = mul(inv, b[col][i].toInt() and 0xFF).toByte()
            }
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                if (factor == 0) continue
                for (j in col until n) a[row][j] = a[row][j] xor mul(factor, a[col][j])
                for (i in b[row].indices) {
                    b[row][i] = (b[row][i].toInt() xor mul(factor, b[col][i].toInt() and 0xFF)).toByte()
                }
            }
        }
        return b
    }
}
