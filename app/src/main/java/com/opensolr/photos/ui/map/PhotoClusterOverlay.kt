package com.opensolr.photos.ui.map

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.provider.MediaStore
import android.util.LruCache
import android.view.MotionEvent
import androidx.core.content.res.ResourcesCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.opensolr.photos.R
import com.opensolr.photos.search.PhotoPin
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.roundToInt

class PhotoCluster(val pins: List<PhotoPin>, val centerX: Float, val centerY: Float) {

    val geoCenter: GeoPoint get() = GeoPoint(pins.map { it.lat }.average(), pins.map { it.lon }.average())
}

class PhotoClusterOverlay(
    private val context: Context,
    private val colors: MarkerColors,
    private val onTap: (PhotoCluster) -> Unit,
) : Overlay() {

    data class MarkerColors(val chip: Int, val paper: Int, val hairline: Int, val accent: Int, val onAccent: Int)

    var pins: List<PhotoPin> = emptyList()
        set(value) {
            field = value

            points = value.map { GeoPoint(it.lat, it.lon) }
            clusters = emptyList()
        }

    private var points: List<GeoPoint> = emptyList()

    private val density = context.resources.displayMetrics.density
    private val markerSize = 56f * density
    private val cellSize = 84f * density
    private val corner = 2f * density
    private val badgeRadius = 12f * density

    private val loader = ImageLoader(context)
    private val thumbs = object : LruCache<String, Bitmap>(64) {}
    private val loading = HashSet<String>()

    private var clusters: List<PhotoCluster> = emptyList()
    private var lastZoom = -1.0
    private var lastCenter: GeoPoint? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = colors.paper }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density; color = colors.hairline }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colors.accent }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.onAccent
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val point = Point()
    private val src = Rect()
    private val dst = RectF()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || pins.isEmpty()) return
        val projection = mapView.projection
        val center = mapView.mapCenter as? GeoPoint
        if (clusters.isEmpty() || mapView.zoomLevelDouble != lastZoom || center != lastCenter) {
            clusters = cluster(projection)
            lastZoom = mapView.zoomLevelDouble
            lastCenter = center?.let { GeoPoint(it) }
        }
        clusters.forEach { drawCluster(canvas, it, mapView) }
    }

    private fun cluster(projection: Projection): List<PhotoCluster> {
        val width = projection.width
        val height = projection.height
        val margin = markerSize
        val cell = cellSize.toInt()

        val cells = LinkedHashMap<Long, ArrayList<PhotoPin>>()
        val sums = HashMap<Long, LongArray>()
        for (i in pins.indices) {
            val p = projection.toPixels(points[i], point)
            if (p.x < -margin || p.y < -margin || p.x > width + margin || p.y > height + margin) continue
            val key = Math.floorDiv(p.x, cell).toLong() * 1_000_003L + Math.floorDiv(p.y, cell).toLong()
            cells.getOrPut(key) { ArrayList() }.add(pins[i])
            val sum = sums.getOrPut(key) { LongArray(2) }
            sum[0] += p.x.toLong()
            sum[1] += p.y.toLong()
        }
        return cells.map { (key, members) ->
            val sum = sums[key]!!
            PhotoCluster(members, sum[0].toFloat() / members.size, sum[1].toFloat() / members.size)
        }
    }

    private fun drawCluster(canvas: Canvas, cluster: PhotoCluster, mapView: MapView) {
        val half = markerSize / 2
        dst.set(cluster.centerX - half, cluster.centerY - half, cluster.centerX + half, cluster.centerY + half)

        val first = cluster.pins.first().hit
        val thumb = thumbs.get(first.id) ?: run { requestThumb(first.id, first.mediaId, mapView); null }

        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(dst, corner, corner, Path.Direction.CW)
        canvas.clipPath(clipPath)
        if (thumb != null) {

            val side = minOf(thumb.width, thumb.height)
            val left = (thumb.width - side) / 2
            val top = (thumb.height - side) / 2
            src.set(left, top, left + side, top + side)
            canvas.drawBitmap(thumb, src, dst, bitmapPaint)
        } else {
            fill.color = colors.chip
            canvas.drawRect(dst, fill)
        }
        canvas.restore()
        canvas.drawRoundRect(dst, corner, corner, hairline)
        canvas.drawRoundRect(dst, corner, corner, border)

        if (cluster.pins.size > 1) {
            val bx = dst.right - badgeRadius * 0.6f
            val by = dst.top + badgeRadius * 0.6f
            canvas.drawCircle(bx, by, badgeRadius, badge)
            val label = if (cluster.pins.size > 999) "999+" else cluster.pins.size.toString()
            val textY = by - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText(label, bx, textY, badgeText)
        }
    }

    private fun requestThumb(id: String, mediaId: Long, mapView: MapView) {
        if (mediaId <= 0 || !loading.add(id)) return
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(max(1, (markerSize * 2).roundToInt()))
            .allowHardware(false)
            .target(
                onSuccess = { drawable ->
                    (drawable as? BitmapDrawable)?.bitmap?.let {
                        thumbs.put(id, it)

                        loading.remove(id)
                    }
                    mapView.postInvalidate()
                },
                onError = { loading.remove(id) },
            )
            .build()
        loader.enqueue(request)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val half = markerSize / 2
        val hit = clusters.lastOrNull { c ->
            e.x >= c.centerX - half && e.x <= c.centerX + half && e.y >= c.centerY - half && e.y <= c.centerY + half
        } ?: return false
        onTap(hit)
        return true
    }

    override fun onDetach(mapView: MapView?) {
        thumbs.evictAll()
        loading.clear()
        loader.shutdown()
        super.onDetach(mapView)
    }

}
