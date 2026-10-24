package com.opensolr.photos.media

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * What an MP4 / QuickTime file says about itself, read from its own boxes: when it was made, how long it runs,
 * and the size of its picture. For clips Android's own reader refuses (raw PCM sound, extra metadata tracks),
 * so they still get their real date, length and picture.
 */
object Mp4Info {

    class Info(val createdMs: Long?, val durationMs: Long?, val width: Int, val height: Int, val rotation: Int = 0)

    // seconds between 1904-01-01 (MP4 time) and 1970-01-01
    private const val EPOCH_1904 = 2082844800L

    fun read(context: Context, uri: Uri): Info? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch -> parse(ch) }
        }
    } catch (e: Exception) {
        null
    }

    private fun parse(ch: FileChannel): Info? {
        val size = ch.size()
        val moov = find(ch, 0, size, "moov") ?: return null
        var created: Long? = null
        var duration: Long? = null
        var width = 0
        var height = 0
        var rotation = 0
        find(ch, moov.first, moov.second, "mvhd")?.let { (start, _) ->
            val b = read(ch, start, 32)
            val version = b.get(0).toInt()
            if (version == 1) {
                val c = b.getLong(4)
                val scale = b.getInt(20).toLong()
                val d = b.getLong(24)
                created = (c - EPOCH_1904).takeIf { it > 0 }?.times(1000)
                if (scale > 0) duration = d * 1000 / scale
            } else {
                val c = b.getInt(4).toLong() and 0xffffffffL
                val scale = b.getInt(12).toLong() and 0xffffffffL
                val d = b.getInt(16).toLong() and 0xffffffffL
                created = (c - EPOCH_1904).takeIf { it > 0 }?.times(1000)
                if (scale > 0) duration = d * 1000 / scale
            }
        }
        // the video track: the first track whose header carries a picture size
        var at = moov.first
        while (at < moov.second) {
            val trak = find(ch, at, moov.second, "trak") ?: break
            find(ch, trak.first, trak.second, "tkhd")?.let { (start, end) ->
                val len = (end - start).toInt()
                if (len >= 84) {
                    val b = read(ch, start, len)
                    val w = b.getInt(len - 8) ushr 16
                    val h = b.getInt(len - 4) ushr 16
                    if (w > 0 && h > 0 && width == 0) {
                        width = w; height = h
                        // the track's matrix says how the picture is turned when shown (a, b over c, d in 16.16)
                        val m = len - 8 - 36
                        val a = b.getInt(m) / 65536.0
                        val bb = b.getInt(m + 4) / 65536.0
                        val deg = Math.toDegrees(kotlin.math.atan2(bb, a)).let { kotlin.math.round(it).toInt() }
                        rotation = ((deg % 360) + 360) % 360
                    }
                }
            }
            at = trak.second
        }
        return Info(created, duration, width, height, rotation)
    }

    /** The first box of [type] directly inside [from, to): its content's start and end. */
    private fun find(ch: FileChannel, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var at = from
        while (at + 8 <= to) {
            val h = read(ch, at, 16)
            var boxSize = h.getInt(0).toLong() and 0xffffffffL
            val name = String(byteArrayOf(h.get(4), h.get(5), h.get(6), h.get(7)), Charsets.ISO_8859_1)
            var header = 8L
            if (boxSize == 1L) { boxSize = h.getLong(8); header = 16 }
            if (boxSize == 0L) boxSize = to - at
            if (boxSize < header) return null
            if (name == type) return (at + header) to (at + boxSize)
            at += boxSize
        }
        return null
    }

    private fun read(ch: FileChannel, at: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n)
        ch.read(b, at)
        b.flip()
        return b
    }
}
