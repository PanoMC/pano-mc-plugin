package com.panomc.plugins.pano.core.backup.snapshot

import java.io.InputStream

/**
 * SplitMix64, the one pseudo-random generator both sides of the snapshot format agree on.
 *
 * Chosen because it is five lines, has no platform dependency and is specified down to the bit:
 * the state advances by the golden-ratio constant `0x9E3779B97F4A7C15` and each output is that
 * state run through the two xor-shift-multiply rounds below, all in wrapping unsigned 64-bit
 * arithmetic (Kotlin's `Long` arithmetic wraps the same way, and `ushr` is the logical shift the
 * algorithm means). The first value returned is the one computed after the first advance.
 */
class SplitMix64(seed: Long) {
    private var state: Long = seed

    fun next(): Long {
        state += GOLDEN_GAMMA

        var z = state

        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2

        return z xor (z ushr 31)
    }

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        private const val MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        private const val MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    }
}

/**
 * Content-defined chunking, FastCDC style, pinned down to the last bit so the node daemon and
 * this plugin cut the same file at the same offsets.
 *
 * Fixed-size blocks would make an incremental backup useless for anything that grows in the
 * middle: one byte inserted at the front of a file shifts every block after it, and every block
 * then looks new. Cutting where the *content* says to - where a rolling hash of the last bytes
 * happens to have a run of zero bits - means an insertion only disturbs the chunk it lands in,
 * and the rest of the file deduplicates against the previous snapshot as before.
 *
 * The exact algorithm, which the node side implements identically (it is the repository format,
 * not an implementation detail - two sides that cut differently still produce valid snapshots,
 * but stop sharing chunks):
 *
 * - **Gear table**: 256 64-bit values, `GEAR[i]` = the (i+1)-th output of [SplitMix64] seeded
 *   with `0x50414E4F` ("PANO" in ASCII).
 * - **Hash**: `fp = (fp << 1) + GEAR[byte & 0xFF]`, wrapping 64-bit, reset to 0 at the start of
 *   every chunk.
 * - **Masks**: normalised chunking at level 2 around an average of 2^20 bytes. The "small" mask
 *   has the top `log2(avg) + 2` = 22 bits of the 64-bit word set, the "large" mask the top
 *   `log2(avg) - 2` = 18: the high bits of a left-shifting gear hash depend on the last 64 bytes,
 *   the low bits only on the last few, so the high ones are the ones worth testing.
 * - **Cut** of a chunk starting at the current position with `n` bytes remaining: if
 *   `n <= MIN` the whole rest is one chunk; otherwise `n` is capped at `MAX`, hashing starts at
 *   offset `MIN` (the first `MIN` bytes are never a cut point and are not hashed), and for every
 *   byte at offset `i` (0-based within the chunk) the hash is updated and then tested - against
 *   the small mask while `i < AVG`, against the large mask after - and the chunk ends at `i + 1`
 *   when `(fp & mask) == 0`. No cut before `n` means the chunk is `n` bytes.
 *
 * An empty file has no chunks at all.
 */
object FastCdc {
    const val MIN_SIZE = 262_144
    const val AVG_SIZE = 1_048_576
    const val MAX_SIZE = 4_194_304

    /** "PANO" in ASCII: the seed the gear table is generated from. */
    const val GEAR_SEED = 0x50414E4FL

    private const val AVG_BITS = 20

    /** How far the masks are pushed either side of the average; level 2, as the node uses. */
    private const val NORMALIZATION_LEVEL = 2

    /** Harder to match below the average size, so chunks are less likely to come out small. */
    val MASK_SMALL: Long = topBits(AVG_BITS + NORMALIZATION_LEVEL)

    /** Easier to match above it, so chunks are less likely to run all the way to [MAX_SIZE]. */
    val MASK_LARGE: Long = topBits(AVG_BITS - NORMALIZATION_LEVEL)

    val GEAR: LongArray = SplitMix64(GEAR_SEED).let { random -> LongArray(256) { random.next() } }

    private fun topBits(count: Int): Long = -1L shl (64 - count)

    /**
     * Where the chunk that starts at [offset] in [buffer] ends, as a length.
     *
     * [length] is how many bytes from [offset] are available. Only call it with fewer than
     * [MAX_SIZE] of them when those are all the bytes the stream has left, or the cut depends on
     * how the stream happened to be read.
     */
    fun cut(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= MIN_SIZE) {
            return length
        }

        val n = if (length > MAX_SIZE) MAX_SIZE else length
        val normal = if (n < AVG_SIZE) n else AVG_SIZE

        var fp = 0L
        var i = MIN_SIZE

        while (i < normal) {
            fp = (fp shl 1) + GEAR[buffer[offset + i].toInt() and 0xFF]

            if ((fp and MASK_SMALL) == 0L) {
                return i + 1
            }

            i++
        }

        while (i < n) {
            fp = (fp shl 1) + GEAR[buffer[offset + i].toInt() and 0xFF]

            if ((fp and MASK_LARGE) == 0L) {
                return i + 1
            }

            i++
        }

        return n
    }

    /**
     * Splits [input] into chunks with a throwaway [Splitter]; see [Splitter.split].
     */
    fun split(input: InputStream, onChunk: (ByteArray, Int) -> Unit) = Splitter().split(input, onChunk)

    /**
     * Splits streams into chunks, holding on to its one [MAX_SIZE] buffer between them.
     *
     * A backup of a server with forty thousand small files would otherwise allocate forty
     * thousand 4 MiB buffers; one splitter per backup allocates one. Not thread-safe.
     */
    class Splitter {
        private val buffer = ByteArray(MAX_SIZE)

        /**
         * Splits [input] into chunks, handing each to [onChunk] as `(bytes, length)` with the
         * chunk at the start of the array.
         *
         * The array is reused between calls, so [onChunk] must be done with it before it
         * returns. The buffer is always refilled to [MAX_SIZE] before a cut is decided, which is
         * what makes the offsets independent of how the stream delivers its bytes. Does not close
         * [input].
         */
        fun split(input: InputStream, onChunk: (ByteArray, Int) -> Unit) {
            var filled = 0
            var eof = false

            while (true) {
                while (!eof && filled < MAX_SIZE) {
                    val read = input.read(buffer, filled, MAX_SIZE - filled)

                    if (read < 0) {
                        eof = true
                    } else {
                        filled += read
                    }
                }

                if (filled == 0) {
                    return
                }

                val length = cut(buffer, 0, filled)

                onChunk(buffer, length)

                System.arraycopy(buffer, length, buffer, 0, filled - length)

                filled -= length
            }
        }
    }
}
