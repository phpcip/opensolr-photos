package com.opensolr.photos.media

import java.io.OutputStream
import java.util.Locale

/** A PDF written page by page straight to [out]: every picture is a JPEG embedded as it comes (DCTDecode), never re-encoded here. */
class PdfWriter(out: OutputStream, private val title: String) {
    private val sink = Counting(out)
    private val offsets = ArrayList<Long>()
    private val kids = ArrayList<Int>()

    init {
        // the binary comment line tells transfer tools the file is binary
        sink.write("%PDF-1.4\n%".toByteArray(Charsets.US_ASCII))
        sink.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        // 1 = catalog, 2 = page tree (written last, once every page is known)
        offsets += 0L
        offsets += 0L
        writeObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
    }

    val pages: Int get() = kids.size

    /**
     * A page for a photo stored [width] x [height] px and turned by its EXIF [orientation] (1 to 8): the page has the
     * shape the photo is seen in, its long edge the length of an A4 page, and the pictures [draw] adds fill it.
     * Nothing is added when [draw] returns false; true when the page is in.
     */
    fun addPage(width: Int, height: Int, orientation: Int, draw: Page.() -> Boolean): Boolean {
        val turned = orientation in 5..8
        val shownW = if (turned) height else width
        val shownH = if (turned) width else height
        val scale = PAGE_LONG_EDGE_PT / maxOf(shownW, shownH)
        val w = shownW * scale
        val h = shownH * scale
        val page = Page(height)
        if (!page.draw() || page.images.isEmpty()) return false
        val content = StringBuilder("q ").append(placement(orientation, w, h)).append(" cm\n")
        val names = StringBuilder()
        page.images.forEachIndexed { i, image ->
            val share = image.rows.toFloat() / height
            val bottom = 1f - (image.top + image.rows).toFloat() / height
            content.append("q 1 0 0 ").append(num(share)).append(" 0 ").append(num(bottom)).append(" cm /Im").append(i).append(" Do Q\n")
            names.append("/Im").append(i).append(' ').append(image.id).append(" 0 R ")
        }
        content.append("Q")
        val stream = next()
        writeStream(stream, null, content.toString().toByteArray(Charsets.US_ASCII))
        val pageId = next()
        writeObject(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(w)} ${num(h)}] /Resources << /XObject << $names>> >> /Contents $stream 0 R >>")
        kids += pageId
        return true
    }

    /** The pictures of one page: horizontal bands of the stored photo, top to bottom, each one JPEG. */
    inner class Page internal constructor(private val height: Int) {
        internal val images = ArrayList<Placed>()

        /** Rows [top] to [top] + [rows] of the photo, [width] px wide, [components] colour channels, the JPEG bytes written by [write]. */
        fun jpeg(top: Int, rows: Int, width: Int, components: Int, write: (OutputStream) -> Unit) {
            require(top >= 0 && rows > 0 && top + rows <= height)
            val image = next()
            val length = next()
            offsets[image - 1] = sink.count
            val space = if (components == 1) "/DeviceGray" else "/DeviceRGB"
            sink.write("$image 0 obj\n<< /Type /XObject /Subtype /Image /Width $width /Height $rows /ColorSpace $space /BitsPerComponent 8 /Filter /DCTDecode /Length $length 0 R >>\nstream\n".toByteArray(Charsets.US_ASCII))
            val start = sink.count
            write(Unclosable(sink))
            val size = sink.count - start
            sink.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
            writeObject(length, size.toString())
            images += Placed(image, top, rows)
        }
    }

    internal class Placed(val id: Int, val top: Int, val rows: Int)

    // the stored photo (the unit square, its first row at the top) laid on the page as its EXIF orientation shows it
    private fun placement(orientation: Int, w: Float, h: Float): String {
        val m = when (orientation) {
            2 -> floatArrayOf(-w, 0f, 0f, h, w, 0f)
            3 -> floatArrayOf(-w, 0f, 0f, -h, w, h)
            4 -> floatArrayOf(w, 0f, 0f, -h, 0f, h)
            5 -> floatArrayOf(0f, -h, -w, 0f, w, h)
            6 -> floatArrayOf(0f, -h, w, 0f, 0f, h)
            7 -> floatArrayOf(0f, h, w, 0f, 0f, 0f)
            8 -> floatArrayOf(0f, h, -w, 0f, w, 0f)
            else -> floatArrayOf(w, 0f, 0f, h, 0f, 0f)
        }
        return m.joinToString(" ") { num(it) }
    }

    /** Page tree, document info, cross-reference table and trailer. The stream is flushed, not closed. */
    fun finish() {
        writeObject(2, "<< /Type /Pages /Kids [" + kids.joinToString(" ") { "$it 0 R" } + "] /Count ${kids.size} >>")
        val info = next()
        writeObject(info, "<< /Title " + utf16(title) + " /Producer " + utf16("Opensolr Photos") + " >>")
        val xref = sink.count
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { table.append(String.format(Locale.US, "%010d 00000 n \n", it)) }
        table.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        sink.write(table.toString().toByteArray(Charsets.US_ASCII))
        sink.flush()
    }

    private fun next(): Int {
        offsets += 0L
        return offsets.size
    }

    private fun writeObject(id: Int, body: String) {
        offsets[id - 1] = sink.count
        sink.write("$id 0 obj\n$body\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    private fun writeStream(id: Int, dict: String?, data: ByteArray) {
        offsets[id - 1] = sink.count
        val head = dict ?: "<< /Length ${data.size} >>"
        sink.write("$id 0 obj\n$head\nstream\n".toByteArray(Charsets.US_ASCII))
        sink.write(data)
        sink.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    private fun num(v: Float): String = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    // any language in the title: UTF-16BE with its byte order mark, as a hex string
    private fun utf16(text: String): String =
        "<FEFF" + text.toByteArray(Charsets.UTF_16BE).joinToString("") { String.format(Locale.US, "%02X", it) } + ">"

    // a picture written into the PDF must not close the PDF
    private class Unclosable(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = Unit
    }

    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
    }

    companion object {
        private const val PAGE_LONG_EDGE_PT = 842f
    }
}
