package com.panomc.plugins.pano.core.backup.snapshot

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunker is part of the repository format: the node daemon has to cut the same bytes at the
 * same places, or the two stop sharing chunks. So besides behaving sensibly it is pinned to exact
 * numbers here - the same numbers the node side's tests assert.
 */
class FastCdcTest {
    /**
     * The test vector input: [size] bytes made of consecutive SplitMix64(seed) outputs, each
     * written as 8 bytes little-endian.
     */
    private fun vector(seed: Long, size: Int): ByteArray {
        val random = SplitMix64(seed)
        val bytes = ByteArray(size)
        var index = 0

        while (index < size) {
            var value = random.next()

            for (shift in 0 until 8) {
                if (index < size) {
                    bytes[index++] = (value and 0xFF).toByte()
                }

                value = value ushr 8
            }
        }

        return bytes
    }

    private fun boundaries(input: InputStream): List<Pair<Int, String>> {
        val chunks = ArrayList<Pair<Int, String>>()
        var offset = 0

        FastCdc.split(input) { bytes, length ->
            offset += length
            chunks.add(offset to SnapshotRepository.sha256(bytes, length))
        }

        return chunks
    }

    /** Hands out at most [step] bytes per read, like a socket or a slow disk would. */
    private class Trickle(private val bytes: ByteArray, private val step: Int) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) {
                return -1
            }

            val count = minOf(length, step, bytes.size - position)

            System.arraycopy(bytes, position, buffer, offset, count)
            position += count

            return count
        }
    }

    @Test
    fun `SplitMix64 is the reference generator`() {
        // The published first output of SplitMix64 seeded with 0.
        assertEquals(-0x1ddf57c684e23251L, SplitMix64(0).next()) // 0xE220A8397B1DCDAF
    }

    @Test
    fun `the gear table is generated from the PANO seed`() {
        assertEquals(256, FastCdc.GEAR.size)
        assertEquals("a8ae1000d0bd373d", java.lang.Long.toHexString(FastCdc.GEAR[0]))
        assertEquals("8fcb7d6fe5acf2c3", java.lang.Long.toHexString(FastCdc.GEAR[1]))
        assertEquals("b9e4d3f76ba7d4f7", java.lang.Long.toHexString(FastCdc.GEAR[255]))
    }

    /**
     * The cross-implementation test vector: 10 MiB of SplitMix64(42) output, little-endian. The
     * node daemon's chunker asserts the same end offsets and the same first-chunk hash.
     */
    @Test
    fun `cuts the shared test vector at the agreed offsets`() {
        val data = vector(42, 10 * 1024 * 1024)

        assertEquals("956eeb2f2632d7bd03f166b233e3ef28", SnapshotRepository.hex(data.copyOfRange(0, 16)))

        val chunks = boundaries(ByteArrayInputStream(data))

        assertEquals(listOf(1_077_613, 2_094_741, 3_443_250, 3_819_062, 4_926_371), chunks.take(5).map { it.first })
        assertEquals("99de0b0447891e591cd1e2a71bf11744c7cbeb0e05e49ea60cdd6041a3a7b539", chunks.first().second)
        assertEquals(9, chunks.size)
        assertEquals(data.size, chunks.last().first)
    }

    /**
     * The node daemon's golden vector: 8 MiB of the low byte of successive SplitMix64(1) draws.
     * Its tests assert exactly these chunk lengths.
     */
    @Test
    fun `cuts the node daemon's golden vector into the same chunk lengths`() {
        val random = SplitMix64(1)
        val data = ByteArray(8 * 1024 * 1024) { (random.next() and 0xFF).toByte() }

        var previous = 0

        val lengths = boundaries(ByteArrayInputStream(data)).map { (end, _) -> (end - previous).also { previous = end } }

        assertEquals(listOf(322_031, 1_397_605, 1_284_441, 2_030_690, 1_552_390, 588_164, 1_213_287), lengths)
    }

    @Test
    fun `the masks are normalised at level 2`() {
        assertEquals(22, java.lang.Long.bitCount(FastCdc.MASK_SMALL))
        assertEquals(18, java.lang.Long.bitCount(FastCdc.MASK_LARGE))
        assertTrue(FastCdc.MASK_SMALL < 0 && FastCdc.MASK_LARGE < 0)
    }

    @Test
    fun `cuts the same way however the stream delivers its bytes`() {
        val data = vector(7, 6 * 1024 * 1024 + 12_345)

        val whole = boundaries(ByteArrayInputStream(data))

        assertEquals(whole, boundaries(Trickle(data, 1_000)))
        assertEquals(whole, boundaries(Trickle(data, 65_537)))
    }

    @Test
    fun `every chunk respects the size bounds`() {
        val data = vector(3, 12 * 1024 * 1024)
        var previous = 0

        val chunks = boundaries(ByteArrayInputStream(data))

        chunks.forEachIndexed { index, (end, _) ->
            val size = end - previous

            assertTrue(size <= FastCdc.MAX_SIZE, "chunk $index is $size bytes")

            if (index < chunks.size - 1) {
                assertTrue(size > FastCdc.MIN_SIZE, "chunk $index is $size bytes")
            }

            previous = end
        }

        // A run of identical bytes never matches a mask, so it is cut at the maximum.
        val zeros = boundaries(ByteArrayInputStream(ByteArray(9 * 1024 * 1024)))

        assertEquals(listOf(FastCdc.MAX_SIZE, 2 * FastCdc.MAX_SIZE, 9 * 1024 * 1024), zeros.map { it.first })
    }

    @Test
    fun `small files are one chunk and empty ones none`() {
        assertEquals(1, boundaries(ByteArrayInputStream(ByteArray(FastCdc.MIN_SIZE))).size)
        assertEquals(1, boundaries(ByteArrayInputStream(ByteArray(10))).size)
        assertEquals(0, boundaries(ByteArrayInputStream(ByteArray(0))).size)
    }

    @Test
    fun `an insertion near the start only disturbs the chunks around it`() {
        val data = vector(11, 8 * 1024 * 1024)
        val shifted = ByteArray(100) { 0x55 } + data

        val before = boundaries(ByteArrayInputStream(data)).map { it.second }.toSet()
        val after = boundaries(ByteArrayInputStream(shifted)).map { it.second }

        assertTrue(after.count { it in before } >= after.size - 2, "only ${after.count { it in before }} of ${after.size} chunks survived")
    }
}
