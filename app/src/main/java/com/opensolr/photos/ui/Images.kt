package com.opensolr.photos.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import com.opensolr.photos.media.PdfPages
import com.opensolr.photos.search.PhotoHit

/** One page of a PDF as something the picture loader can open. */
data class PdfPage(val uri: Uri, val page: Int, val sizeBytes: Long)

class PdfPageFetcher(private val data: PdfPage, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val wanted = (options.size.width as? Dimension.Pixels)?.px?.coerceAtLeast(64) ?: DEFAULT_EDGE
        val bitmap = PdfPages.render(options.context, data.uri, data.page, wanted.coerceAtMost(MAX_EDGE)) ?: return null
        return DrawableResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<PdfPage> {
        override fun create(data: PdfPage, options: Options, imageLoader: ImageLoader): Fetcher = PdfPageFetcher(data, options)
    }

    class Key : Keyer<PdfPage> {
        override fun key(data: PdfPage, options: Options): String = "pdf:${data.uri}:${data.page}:${data.sizeBytes}"
    }

    companion object {
        private const val DEFAULT_EDGE = 1024
        private const val MAX_EDGE = 2400
    }
}

object Images {

    /** The picture loader: photos and videos (their first frame) from their content URIs, PDF pages rendered here. */
    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(coil.decode.VideoFrameDecoder.Factory())
            add(PdfPageFetcher.Factory())
            add(PdfPageFetcher.Key())
        }
        .crossfade(true)
        .build()

    /** What the loader opens for [hit]: the 720 px copy when only the backend has it, else the file by its kind. */
    fun model(context: Context, hit: PhotoHit): Any {
        com.opensolr.photos.storage.CloudFiles.model(context, hit.id)?.let { return it }
        return if (hit.isPdfPage) PdfPage(hit.uri, hit.page, hit.sizeBytes) else hit.uri
    }

    /** "1:05" / "1:02:33" for a clip's length. */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
