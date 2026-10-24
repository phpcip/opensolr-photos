package com.opensolr.photos.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Whether this phone's own decoders can play a clip smoothly: a decoder for its format, its size and frame rate
 * within what that decoder is rated for. A clip that is not goes to the VLC player straight away.
 */
object VideoSupport {

    fun phoneCanPlay(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return true
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return true
            val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
            val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
            val fps = frameRate(format)
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            // the rate is checked below, against every decoder of the type: not asked of the lookup
            val plain = MediaFormat(format).apply { if (containsKey(MediaFormat.KEY_FRAME_RATE)) removeKey(MediaFormat.KEY_FRAME_RATE) }
            if (list.findDecoderForFormat(plain) == null) return false
            if (width <= 0 || height <= 0 || fps <= 0.0) return true
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                    val sizeOk = caps.isSizeSupported(width, height) || caps.isSizeSupported(height, width)
                    val rated = caps.getAchievableFrameRatesFor(width, height) ?: caps.getAchievableFrameRatesFor(height, width)
                    // a decoder measured on this phone: its rate; one without measurements: what it declares
                    sizeOk && (rated?.let { it.upper >= fps * 0.95 } ?: caps.areSizeAndRateSupported(width, height, fps))
                }.getOrDefault(false)
            }
        } catch (e: Exception) {
            true
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun frameRate(format: MediaFormat): Double = runCatching {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) 0.0
        else runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
    }.getOrDefault(0.0)
}
