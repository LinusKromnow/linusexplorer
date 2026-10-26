package io.github.linuskromnow.linusexplorer.fs.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * Read-only [SeekableByteChannel] over a remote file of known [size], fetched in [BLOCK_SIZE]
 * blocks through [readAt] and kept in a small LRU cache, so a ZIP reader's many small seeks and
 * reads turn into a few network requests.
 */
internal class RangeChannel(
    private val size: Long,
    private val readAt: (offset: Long, length: Int) -> ByteArray,
) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > MAX_BLOCKS
    }

    /** Bytes fetched so far; lets tests check that browsing doesn't download the whole file. */
    @Volatile var bytesFetched = 0L
        private set

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        ensureOpen()
        if (position >= size) return -1
        var total = 0
        while (dst.hasRemaining() && position < size) {
            val blockIndex = position / BLOCK_SIZE
            val block = block(blockIndex)
            val inBlock = (position - blockIndex * BLOCK_SIZE).toInt()
            val count = minOf(dst.remaining(), block.size - inBlock)
            if (count <= 0) throw IOException("Remote file is shorter than expected (changed on the server?)")
            dst.put(block, inBlock, count)
            position += count
            total += count
        }
        return total
    }

    private fun block(index: Long): ByteArray = cache.getOrPut(index) {
        val offset = index * BLOCK_SIZE
        val length = minOf(BLOCK_SIZE.toLong(), size - offset).toInt()
        readAt(offset, length).also { bytesFetched += it.size }
    }

    @Synchronized override fun position(): Long = position

    @Synchronized
    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        require(newPosition >= 0) { "Negative position" }
        position = newPosition
        return this
    }

    override fun size(): Long = size

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = open

    @Synchronized
    override fun close() {
        open = false
        cache.clear()
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    companion object {
        const val BLOCK_SIZE = 64 * 1024

        /** 4 MB of cached blocks per open archive. */
        const val MAX_BLOCKS = 64
    }
}
