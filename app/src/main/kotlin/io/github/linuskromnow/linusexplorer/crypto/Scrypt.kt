package io.github.linuskromnow.linusexplorer.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * scrypt (RFC 7914) with the large N·r·128-byte table in native memory.
 *
 * Android caps the Java heap per app (often 256 MiB), but files from desktop age commonly use
 * work factors 18-20, i.e. 256 MiB-1 GiB. A direct buffer lives outside that limit.
 */
object Scrypt {
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, keyLength: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N must be a power of two" }
        val blockInts = 32 * r
        val tableBytes = n.toLong() * blockInts * 4
        if (tableBytes > Int.MAX_VALUE) throw OutOfMemoryError("scrypt table of $tableBytes bytes is too large")

        val b = pbkdf2(password, salt, p * 128 * r)
        val table = ByteBuffer.allocateDirect(tableBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val x = IntArray(blockInts)
        val scratch = IntArray(blockInts)
        val block = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until p) {
            val offset = i * 128 * r
            for (k in 0 until blockInts) x[k] = block.getInt(offset + 4 * k)
            roMix(x, scratch, table, n, r)
            for (k in 0 until blockInts) block.putInt(offset + 4 * k, x[k])
        }
        return pbkdf2(password, b, keyLength)
    }

    private fun roMix(x: IntArray, scratch: IntArray, table: IntBuffer, n: Int, r: Int) {
        val blockInts = x.size
        for (i in 0 until n) {
            table.position(i * blockInts)
            table.put(x)
            blockMix(x, scratch, r)
        }
        for (i in 0 until n) {
            val j = x[(2 * r - 1) * 16] and (n - 1)
            val base = j * blockInts
            for (k in 0 until blockInts) x[k] = x[k] xor table.get(base + k)
            blockMix(x, scratch, r)
        }
    }

    /** BlockMix-Salsa20/8: even output blocks first, then odd ones. */
    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val t = IntArray(16)
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            salsa208(t)
            val target = if (i % 2 == 0) (i / 2) * 16 else (r + i / 2) * 16
            System.arraycopy(t, 0, y, target, 16)
        }
        System.arraycopy(y, 0, b, 0, b.size)
    }

    private fun salsa208(b: IntArray) {
        var x0 = b[0]; var x1 = b[1]; var x2 = b[2]; var x3 = b[3]
        var x4 = b[4]; var x5 = b[5]; var x6 = b[6]; var x7 = b[7]
        var x8 = b[8]; var x9 = b[9]; var x10 = b[10]; var x11 = b[11]
        var x12 = b[12]; var x13 = b[13]; var x14 = b[14]; var x15 = b[15]
        repeat(4) {
            x4 = x4 xor (x0 + x12).rotateLeft(7); x8 = x8 xor (x4 + x0).rotateLeft(9)
            x12 = x12 xor (x8 + x4).rotateLeft(13); x0 = x0 xor (x12 + x8).rotateLeft(18)
            x9 = x9 xor (x5 + x1).rotateLeft(7); x13 = x13 xor (x9 + x5).rotateLeft(9)
            x1 = x1 xor (x13 + x9).rotateLeft(13); x5 = x5 xor (x1 + x13).rotateLeft(18)
            x14 = x14 xor (x10 + x6).rotateLeft(7); x2 = x2 xor (x14 + x10).rotateLeft(9)
            x6 = x6 xor (x2 + x14).rotateLeft(13); x10 = x10 xor (x6 + x2).rotateLeft(18)
            x3 = x3 xor (x15 + x11).rotateLeft(7); x7 = x7 xor (x3 + x15).rotateLeft(9)
            x11 = x11 xor (x7 + x3).rotateLeft(13); x15 = x15 xor (x11 + x7).rotateLeft(18)
            x1 = x1 xor (x0 + x3).rotateLeft(7); x2 = x2 xor (x1 + x0).rotateLeft(9)
            x3 = x3 xor (x2 + x1).rotateLeft(13); x0 = x0 xor (x3 + x2).rotateLeft(18)
            x6 = x6 xor (x5 + x4).rotateLeft(7); x7 = x7 xor (x6 + x5).rotateLeft(9)
            x4 = x4 xor (x7 + x6).rotateLeft(13); x5 = x5 xor (x4 + x7).rotateLeft(18)
            x11 = x11 xor (x10 + x9).rotateLeft(7); x8 = x8 xor (x11 + x10).rotateLeft(9)
            x9 = x9 xor (x8 + x11).rotateLeft(13); x10 = x10 xor (x9 + x8).rotateLeft(18)
            x12 = x12 xor (x15 + x14).rotateLeft(7); x13 = x13 xor (x12 + x15).rotateLeft(9)
            x14 = x14 xor (x13 + x12).rotateLeft(13); x15 = x15 xor (x14 + x13).rotateLeft(18)
        }
        b[0] += x0; b[1] += x1; b[2] += x2; b[3] += x3
        b[4] += x4; b[5] += x5; b[6] += x6; b[7] += x7
        b[8] += x8; b[9] += x9; b[10] += x10; b[11] += x11
        b[12] += x12; b[13] += x13; b[14] += x14; b[15] += x15
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password, salt, 1)
        return (generator.generateDerivedParameters(length * 8) as KeyParameter).key
    }
}
