package com.opensolr.photos.media

import java.io.OutputStream
import java.util.Locale

/** A PDF written page by page straight to [out]: each page is one JPEG embedded as it is (DCTDecode), never re-encoded. */
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

    /** Adds [jpeg] ([width] x [height] px) as a page of its own shape, long edge the length of an A4 page. */
    fun addPage(jpeg: ByteArray, width: Int, height: Int) {
        val scale = PAGE_LONG_EDGE_PT / maxOf(width, height)
        val w = num(width * scale)
        val h = num(height * scale)
        val image = next()
        writeStream(image, "<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>", jpeg)
        val content = next()
        writeStream(content, null, "q $w 0 0 $h 0 0 cm /Im0 Do Q".toByteArray(Charsets.US_ASCII))
        val page = next()
        writeObject(page, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>")
        kids += page
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

    private fun num(v: Float): String = String.format(Locale.US, "%.2f", v)

    // any language in the title: UTF-16BE with its byte order mark, as a hex string
    private fun utf16(text: String): String =
        "<FEFF" + text.toByteArray(Charsets.UTF_16BE).joinToString("") { String.format(Locale.US, "%02X", it) } + ">"

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
