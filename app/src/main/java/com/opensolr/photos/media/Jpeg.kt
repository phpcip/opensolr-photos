package com.opensolr.photos.media

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** A JPEG read marker by marker: its frame (size, colour channels) and its bytes copied without the metadata segments. */
object Jpeg {

    class Frame(val width: Int, val height: Int, val components: Int)

    /** The frame of a JPEG a PDF can show as it is (baseline or progressive, 8 bits, grey or colour); null for anything else. */
    fun frame(input: InputStream): Frame? {
        val r = Reader(input)
        try {
            if (r.byte() != 0xFF || r.byte() != SOI) return null
            while (true) {
                val m = r.marker()
                if (m == EOI || m == SOS) return null
                if (m in RST || m == TEM) continue
                val length = r.u16() - 2
                if (length < 0) return null
                if (m in SOF) {
                    if (m !in SOF_PDF || length < 6) return null
                    val precision = r.byte()
                    val height = r.u16()
                    val width = r.u16()
                    val components = r.byte()
                    return if (precision == 8 && width > 0 && height > 0 && (components == 1 || components == 3)) Frame(width, height, components) else null
                }
                r.skip(length)
            }
        } catch (e: EOFException) {
            return null
        }
    }

    /**
     * The JPEG of [input] into [out] as the same picture: every segment the decoder needs kept byte for byte,
     * EXIF, XMP, maker notes, thumbnails, pictures appended after the end (gain maps) and comments left out.
     */
    fun copyWithoutMetadata(input: InputStream, out: OutputStream) {
        val r = Reader(input)
        if (r.byte() != 0xFF || r.byte() != SOI) throw java.io.IOException("not a JPEG")
        out.write(0xFF)
        out.write(SOI)
        var inScan = false
        try {
            while (true) {
                if (inScan) {
                    // entropy-coded data runs to the next marker; stuffed zero bytes and restart markers belong to it
                    if (!r.copyToByte(0xFF, out)) break
                    val m = r.code()
                    if (m == 0x00 || m in RST) {
                        out.write(0xFF)
                        out.write(m)
                        continue
                    }
                    inScan = false
                    if (!segment(r, m, out)) return
                    if (m == SOS) inScan = true
                    continue
                }
                val m = r.marker()
                if (!segment(r, m, out)) return
                if (m == SOS) inScan = true
            }
        } catch (e: EOFException) {
            // a file cut short still ends as a picture: what was read is shown
        }
        out.write(0xFF)
        out.write(EOI)
    }

    // one marker and its segment, copied or left out; false once the end of the picture is written
    private fun segment(r: Reader, m: Int, out: OutputStream): Boolean {
        if (m == EOI) {
            out.write(0xFF)
            out.write(EOI)
            return false
        }
        if (m in RST || m == TEM) {
            out.write(0xFF)
            out.write(m)
            return true
        }
        val length = r.u16()
        if (length < 2) throw java.io.IOException("bad segment")
        if (m in APP_DROPPED || m == COM) {
            r.skip(length - 2)
            return true
        }
        out.write(0xFF)
        out.write(m)
        out.write(length ushr 8)
        out.write(length and 0xFF)
        r.copy(length - 2, out)
        return true
    }

    private class Reader(private val input: InputStream) {
        private val buf = ByteArray(BUFFER)
        private var pos = 0
        private var end = 0

        private fun fill(): Boolean {
            if (pos < end) return true
            val n = input.read(buf)
            if (n <= 0) return false
            pos = 0
            end = n
            return true
        }

        fun byte(): Int {
            if (!fill()) throw EOFException()
            return buf[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (byte() shl 8) or byte()

        /** The code of the next marker, anything before its 0xFF skipped. */
        fun marker(): Int {
            while (byte() != 0xFF) Unit
            return code()
        }

        /** The code of a marker whose 0xFF was just read, fill bytes skipped. */
        fun code(): Int {
            var m = byte()
            while (m == 0xFF) m = byte()
            return m
        }

        fun skip(n: Int) {
            var left = n
            while (left > 0) {
                if (!fill()) throw EOFException()
                val step = minOf(left, end - pos)
                pos += step
                left -= step
            }
        }

        fun copy(n: Int, out: OutputStream) {
            var left = n
            while (left > 0) {
                if (!fill()) throw EOFException()
                val step = minOf(left, end - pos)
                out.write(buf, pos, step)
                pos += step
                left -= step
            }
        }

        /** Copies up to the next [stop] byte, which is consumed and not written; false at the end of the input. */
        fun copyToByte(stop: Int, out: OutputStream): Boolean {
            val target = stop.toByte()
            while (fill()) {
                var i = pos
                while (i < end && buf[i] != target) i++
                if (i > pos) out.write(buf, pos, i - pos)
                if (i < end) {
                    pos = i + 1
                    return true
                }
                pos = end
            }
            return false
        }
    }

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val COM = 0xFE
    private const val TEM = 0x01
    private val RST = 0xD0..0xD7
    private val SOF = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
    private val SOF_PDF = setOf(0xC0, 0xC1, 0xC2)
    // APP1 to APP15 except Adobe's APP14, which says how the colours were encoded
    private val APP_DROPPED = (0xE1..0xEF).toSet() - 0xEE
    private const val BUFFER = 64 * 1024
}
