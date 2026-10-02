package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pure-Kotlin QR Code generator (byte mode, error-correction level L, versions 1–5,
 * single block, fixed mask 0). No external dependency — enough capacity for profile
 * URLs ("https://telefam.app/u/<username>", up to ~53 chars at v3, 106 at v5).
 *
 * Produces the standard module matrix; [QrCodeImage] renders it as a Composable.
 */
object QrCode {

    // --- GF(256) arithmetic, primitive polynomial x^8+x^4+x^3+x^2+1 (0x11D) ---
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else gfExp[gfLog[a] + gfLog[b]]

    /** Generator polynomial of degree [degree], coefficients (highest first). */
    private fun rsGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor gfMul(poly[j], 1)
                next[j + 1] = next[j + 1] xor gfMul(poly[j], gfExp[i])
            }
            poly = next
        }
        return poly
    }

    /** Reed–Solomon remainder of [data] with [ecCount] check codewords. */
    private fun reedSolomon(data: IntArray, ecCount: Int): IntArray {
        val gen = rsGenerator(ecCount)
        val rem = IntArray(ecCount)
        for (d in data) {
            val factor = d xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecCount - 1)
            rem[ecCount - 1] = 0
            if (factor != 0) {
                for (i in 0 until ecCount) rem[i] = rem[i] xor gfMul(gen[i + 1], factor)
            }
        }
        return rem
    }

    /** Returns an N×N matrix (true = dark module), or null if the payload is too long. */
    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        // (data codewords, ec codewords) per version for EC level L, single block.
        val versions = arrayOf(
            intArrayOf(19, 7),   // v1
            intArrayOf(34, 10),  // v2
            intArrayOf(55, 15),  // v3
            intArrayOf(80, 20),  // v4
            intArrayOf(108, 26)  // v5
        )
        val version = (0..4).firstOrNull { v ->
            // 4 mode bits + 8 length bits + payload + terminator must fit.
            val capacityBits = versions[v][0] * 8
            12 + bytes.size * 8 <= capacityBits
        }?.plus(1) ?: return null
        val (dataCw, ecCw) = versions[version - 1]

        // --- bit stream: mode(0100) + length(8) + data + terminator + pads ---
        val bits = StringBuilder()
        bits.append("0100")
        bits.append(bytes.size.toString(2).padStart(8, '0'))
        for (b in bytes) bits.append((b.toInt() and 0xFF).toString(2).padStart(8, '0'))
        val capacityBits = dataCw * 8
        bits.append("0".repeat(minOf(4, capacityBits - bits.length)))
        while (bits.length % 8 != 0) bits.append('0')
        val data = bits.chunked(8).map { it.toInt(2) }.toMutableList()
        var padToggle = true
        while (data.size < dataCw) {
            data.add(if (padToggle) 0xEC else 0x11)
            padToggle = !padToggle
        }

        // --- Reed–Solomon error correction over GF(256) ---
        val ec = reedSolomon(data.toIntArray(), ecCw)
        val all = data.toIntArray() + ec

        // --- matrix assembly ---
        val size = 17 + 4 * version
        val m = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun set(r: Int, c: Int, v: Boolean, res: Boolean = true) {
            m[r][c] = v
            if (res) reserved[r][c] = true
        }

        fun finder(r0: Int, c0: Int) {
            for (r in -1..7) for (c in -1..7) {
                val rr = r0 + r; val cc = c0 + c
                if (rr !in 0 until size || cc !in 0 until size) continue
                val dark = r in 0..6 && c in 0..6 &&
                    (r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4))
                set(rr, cc, dark)
            }
        }
        finder(0, 0); finder(0, size - 7); finder(size - 7, 0)

        // Alignment patterns (one per version 2..5, positioned off the finders).
        val alignPos = when (version) { 1 -> intArrayOf(); 2 -> intArrayOf(18); 3 -> intArrayOf(22); 4 -> intArrayOf(26); else -> intArrayOf(30) }
        for (p in alignPos) {
            for (r in -2..2) for (c in -2..2) {
                set(p + r, p + c, maxOf(kotlin.math.abs(r), kotlin.math.abs(c)) != 1)
            }
        }

        // Timing patterns.
        for (i in 8 until size - 8) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }

        // Dark module.
        set(4 * version + 9, 8, true)

        // Reserve format-info areas (written after masking).
        for (i in 0..8) {
            if (i != 6) { reserved[8][i] = true; reserved[i][8] = true }
        }
        for (i in 0..7) {
            reserved[8][size - 1 - i] = true
            reserved[size - 1 - i][8] = true
        }

        // --- data placement: zigzag from bottom-right, skipping column 6 ---
        var bitIndex = 0
        val allBits = all.flatMap { cw -> (7 downTo 0).map { (cw shr it) and 1 == 1 } }
        var upward = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)
            for (r in rows) {
                for (c in intArrayOf(col, col - 1)) {
                    if (reserved[r][c]) continue
                    var bit = if (bitIndex < allBits.size) allBits[bitIndex++] else false
                    // Mask 0: flip where (row + col) is even.
                    if ((r + c) % 2 == 0) bit = !bit
                    m[r][c] = bit
                }
            }
            upward = !upward
            col -= 2
        }

        // --- format info: EC level L (01) + mask 0, BCH(15,5), XOR 0x5412 ---
        var format = (0b01 shl 3) or 0
        var rem = format shl 10
        val generator = 0b10100110111 // x^10 + x^8 + x^5 + x^4 + x^2 + x + 1
        for (i in 14 downTo 10) {
            if ((rem shr i) and 1 == 1) rem = rem xor (generator shl (i - 10))
        }
        format = ((format shl 10) or rem) xor 0b101010000010010

        val fmtBits = (14 downTo 0).map { (format shr it) and 1 == 1 }
        // Around top-left finder.
        val tlRow = intArrayOf(8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0)
        val tlCol = intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8)
        for (i in 0..14) m[tlRow[i]][tlCol[i]] = fmtBits[i]
        // Split copies: bottom-left + top-right.
        for (i in 0..6) m[size - 1 - i][8] = fmtBits[i]
        for (i in 0..7) m[8][size - 8 + i] = fmtBits[7 + i]

        return m
    }
}

/** Renders a QR matrix as crisp black squares on white, with a quiet zone. */
@Composable
fun QrCodeImage(matrix: Array<BooleanArray>, modifier: Modifier = Modifier) {
    val size = matrix.size
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White)
            .padding(16.dp)
    ) {
        val cell = this.size.minDimension / size
        for (r in 0 until size) for (c in 0 until size) {
            if (matrix[r][c]) {
                drawRect(Color.Black, topLeft = Offset(c * cell, r * cell), size = Size(cell, cell))
            }
        }
    }
}
