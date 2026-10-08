package com.opensolr.photos.media

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * The four corners of a sheet of paper in a small grey picture: the largest even region (lighter or darker than
 * what is around it) whose outline is close to a quadrilateral. Corners come back as fractions of the picture,
 * top-left, top-right, bottom-right, bottom-left; null when nothing in the picture looks like a page.
 */
object DocumentEdges {

    fun find(grey: IntArray, width: Int, height: Int): FloatArray? {
        if (width < MIN_SIDE || height < MIN_SIDE || grey.size < width * height) return null
        val smooth = blur(grey, width, height)
        val threshold = otsu(smooth)
        val light = best(smooth, width, height) { it > threshold }
        val dark = best(smooth, width, height) { it <= threshold }
        val quad = listOfNotNull(light, dark).maxByOrNull { it.area }?.corners ?: return null
        for (i in 0 until 4) {
            quad[i * 2] /= width
            quad[i * 2 + 1] /= height
        }
        return quad
    }

    /** A bitmap's pixels as the grey levels [find] reads. */
    fun grey(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return pixels
    }

    private class Found(val corners: FloatArray, val area: Float)

    // the largest region on one side of the threshold, kept only when its outline is a believable page
    private fun best(smooth: IntArray, width: Int, height: Int, inside: (Int) -> Boolean): Found? {
        val n = width * height
        val label = IntArray(n)
        val stack = IntArray(n)
        var bestLabel = 0
        var bestArea = 0
        var next = 0
        for (start in 0 until n) {
            if (label[start] != 0 || !inside(smooth[start])) continue
            next++
            var area = 0
            var top = 0
            stack[top++] = start
            label[start] = next
            while (top > 0) {
                val p = stack[--top]
                area++
                val x = p % width
                if (x > 0 && label[p - 1] == 0 && inside(smooth[p - 1])) { label[p - 1] = next; stack[top++] = p - 1 }
                if (x < width - 1 && label[p + 1] == 0 && inside(smooth[p + 1])) { label[p + 1] = next; stack[top++] = p + 1 }
                if (p >= width && label[p - width] == 0 && inside(smooth[p - width])) { label[p - width] = next; stack[top++] = p - width }
                if (p < n - width && label[p + width] == 0 && inside(smooth[p + width])) { label[p + width] = next; stack[top++] = p + width }
            }
            if (area > bestArea) {
                bestArea = area
                bestLabel = next
            }
        }
        if (bestArea < n * MIN_SHARE) return null

        // the outline: the first and last pixel of the region in every row and every column
        val rowFirst = IntArray(height) { -1 }
        val rowLast = IntArray(height) { -1 }
        val colFirst = IntArray(width) { -1 }
        val colLast = IntArray(width) { -1 }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (label[row + x] != bestLabel) continue
                if (rowFirst[y] < 0) rowFirst[y] = x
                rowLast[y] = x
                if (colFirst[x] < 0) colFirst[x] = y
                colLast[x] = y
            }
        }
        val points = ArrayList<FloatArray>((width + height) * 2)
        for (y in 0 until height) if (rowFirst[y] >= 0) {
            points += floatArrayOf(rowFirst[y] + 0.5f, y + 0.5f)
            points += floatArrayOf(rowLast[y] + 0.5f, y + 0.5f)
        }
        for (x in 0 until width) if (colFirst[x] >= 0) {
            points += floatArrayOf(x + 0.5f, colFirst[x] + 0.5f)
            points += floatArrayOf(x + 0.5f, colLast[x] + 0.5f)
        }
        val hull = hull(points)
        if (hull.size < 4) return null
        val rough = order(toQuad(hull))
        val quad = refine(rough, points, width, height) ?: rough
        val area = polygonArea(quad)
        if (area < n * MIN_SHARE || area > n * MAX_SHARE) return null
        // a page fills its outline (the letters on it aside); a background around a page does not
        if (bestArea / area < MIN_FILL) return null
        if (!convex(quad) || !angles(quad)) return null
        // a region cut by the frame on two corners or more is a wall, a table, a sky: not a page
        val marginX = width * BORDER
        val marginY = height * BORDER
        val onBorder = (0 until 4).count {
            quad[it * 2] <= marginX || quad[it * 2] >= width - marginX || quad[it * 2 + 1] <= marginY || quad[it * 2 + 1] >= height - marginY
        }
        if (onBorder >= 2) return null
        return Found(quad, area)
    }

    // each side straightened along the outline points that lie on it, the corners where the sides meet
    private fun refine(q: FloatArray, points: List<FloatArray>, width: Int, height: Int): FloatArray? {
        val diag = hypot(width.toFloat(), height.toFloat())
        val tolerance = max(2f, diag * SIDE_TOLERANCE)
        val lines = Array(4) { FloatArray(4) }
        for (side in 0 until 4) {
            val ax = q[side * 2]
            val ay = q[side * 2 + 1]
            val bx = q[(side + 1) % 4 * 2]
            val by = q[(side + 1) % 4 * 2 + 1]
            val length = hypot(bx - ax, by - ay)
            if (length < 1f) return null
            val dx = (bx - ax) / length
            val dy = (by - ay) / length
            var count = 0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (pt in points) {
                val rx = pt[0] - ax
                val ry = pt[1] - ay
                val along = (rx * dx + ry * dy) / length
                if (along < SIDE_FROM || along > 1f - SIDE_FROM) continue
                if (abs(-rx * dy + ry * dx) > tolerance) continue
                count++
                sx += pt[0]
                sy += pt[1]
                sxx += pt[0].toDouble() * pt[0]
                syy += pt[1].toDouble() * pt[1]
                sxy += pt[0].toDouble() * pt[1]
            }
            if (count < MIN_SIDE_POINTS) return null
            val mx = sx / count
            val my = sy / count
            val cxx = sxx / count - mx * mx
            val cyy = syy / count - my * my
            val cxy = sxy / count - mx * my
            // the direction the points spread along most
            val angle = 0.5 * atan2(2 * cxy, cxx - cyy)
            lines[side] = floatArrayOf(mx.toFloat(), my.toFloat(), kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
        }
        val out = FloatArray(8)
        for (corner in 0 until 4) {
            val l1 = lines[(corner + 3) % 4]
            val l2 = lines[corner]
            val det = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(det) < 1e-4f) return null
            val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / det
            val x = l1[0] + t * l1[2]
            val y = l1[1] + t * l1[3]
            if (hypot(x - q[corner * 2], y - q[corner * 2 + 1]) > diag * MAX_SHIFT) return null
            out[corner * 2] = x.coerceIn(0f, width.toFloat())
            out[corner * 2 + 1] = y.coerceIn(0f, height.toFloat())
        }
        return if (convex(out) && angles(out)) out else null
    }

    private fun blur(grey: IntArray, width: Int, height: Int): IntArray {
        val r = BLUR_RADIUS
        val across = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (x in -r..r) sum += grey[row + x.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                across[row + x] = sum / (2 * r + 1)
                sum += grey[row + (x + r + 1).coerceAtMost(width - 1)] - grey[row + (x - r).coerceAtLeast(0)]
            }
        }
        val out = IntArray(width * height)
        for (x in 0 until width) {
            var sum = 0
            for (y in -r..r) sum += across[y.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / (2 * r + 1)
                sum += across[(y + r + 1).coerceAtMost(height - 1) * width + x] - across[(y - r).coerceAtLeast(0) * width + x]
            }
        }
        return out
    }

    // the grey level that best splits the picture in two
    private fun otsu(values: IntArray): Int {
        val histogram = IntArray(256)
        for (v in values) histogram[v.coerceIn(0, 255)]++
        val total = values.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i.toDouble() * histogram[i]
        var sumBelow = 0.0
        var below = 0.0
        var bestSplit = 0.0
        var threshold = 127
        for (i in 0 until 256) {
            below += histogram[i]
            if (below == 0.0) continue
            val above = total - below
            if (above == 0.0) break
            sumBelow += i.toDouble() * histogram[i]
            val meanBelow = sumBelow / below
            val meanAbove = (sumAll - sumBelow) / above
            val split = below * above * (meanBelow - meanAbove) * (meanBelow - meanAbove)
            if (split > bestSplit) {
                bestSplit = split
                threshold = i
            }
        }
        return threshold
    }

    private fun hull(points: List<FloatArray>): List<FloatArray> {
        val sorted = points.sortedWith(compareBy<FloatArray> { it[0] }.thenBy { it[1] })
        if (sorted.size < 3) return sorted
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<FloatArray>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<FloatArray>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
            upper += p
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    // the hull thinned to a few points (the one whose removal loses the least area goes first), then the four of them that enclose the most
    private fun toQuad(hull: List<FloatArray>): FloatArray {
        val pts = ArrayList(hull)
        while (pts.size > THIN_TO) {
            var weakest = 0
            var least = Float.MAX_VALUE
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]
                val b = pts[i]
                val c = pts[(i + 1) % pts.size]
                val loss = abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]))
                if (loss < least) {
                    least = loss
                    weakest = i
                }
            }
            pts.removeAt(weakest)
        }
        var best = FloatArray(8) { pts[(it / 2).coerceAtMost(pts.size - 1)][it % 2] }
        var bestArea = -1f
        val k = pts.size
        for (i in 0 until k) for (j in i + 1 until k) for (l in j + 1 until k) for (m in l + 1 until k) {
            val q = floatArrayOf(pts[i][0], pts[i][1], pts[j][0], pts[j][1], pts[l][0], pts[l][1], pts[m][0], pts[m][1])
            val area = polygonArea(q)
            if (area > bestArea) {
                bestArea = area
                best = q
            }
        }
        return best
    }

    private fun polygonArea(q: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sum += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(sum) / 2f
    }

    /** Whether four corners (top-left first, clockwise) outline a page that can be straightened. */
    fun usable(q: FloatArray): Boolean = q.size >= 8 && convex(q) && polygonArea(q) > 0f

    private fun convex(q: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = i
            val b = (i + 1) % 4
            val c = (i + 2) % 4
            val cross = (q[b * 2] - q[a * 2]) * (q[c * 2 + 1] - q[b * 2 + 1]) - (q[b * 2 + 1] - q[a * 2 + 1]) * (q[c * 2] - q[b * 2])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    // a page seen at an angle still has no corner sharper than this
    private fun angles(q: FloatArray): Boolean {
        for (i in 0 until 4) {
            val prev = (i + 3) % 4
            val next = (i + 1) % 4
            val ax = q[prev * 2] - q[i * 2]
            val ay = q[prev * 2 + 1] - q[i * 2 + 1]
            val bx = q[next * 2] - q[i * 2]
            val by = q[next * 2 + 1] - q[i * 2 + 1]
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            if (la < 1f || lb < 1f) return false
            val angle = Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).toDouble().coerceIn(-1.0, 1.0)))
            if (angle < MIN_ANGLE || angle > 180 - MIN_ANGLE) return false
        }
        return true
    }

    /** Corners in reading order: top-left first, then clockwise. */
    fun order(q: FloatArray): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val idx = (0 until 4).sortedBy { atan2(q[it * 2 + 1] - cy, q[it * 2] - cx) }
        val first = idx.indices.minByOrNull { q[idx[it] * 2] + q[idx[it] * 2 + 1] } ?: 0
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val i = idx[(first + k) % 4]
            out[k * 2] = q[i * 2]
            out[k * 2 + 1] = q[i * 2 + 1]
        }
        return out
    }

    /** Corners in a frame turned by [degrees] clockwise, as fractions, back in the frame as it is seen. */
    fun turn(q: FloatArray, degrees: Int): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = q[i * 2]
            val y = q[i * 2 + 1]
            val (u, v) = when (((degrees % 360) + 360) % 360) {
                90 -> (1f - y) to x
                180 -> (1f - x) to (1f - y)
                270 -> y to (1f - x)
                else -> x to y
            }
            out[i * 2] = u
            out[i * 2 + 1] = v
        }
        return order(out)
    }

    /** A step to sample a [width] x [height] frame with, so its long side is about [edge]. */
    fun step(width: Int, height: Int, edge: Int): Int = max(1, (max(width, height) + edge - 1) / edge)

    private const val MIN_SIDE = 16
    private const val BLUR_RADIUS = 2
    private const val MIN_SHARE = 0.12f
    private const val MAX_SHARE = 0.985f
    private const val MIN_FILL = 0.7f
    private const val MIN_ANGLE = 35.0
    private const val THIN_TO = 10
    private const val BORDER = 0.015f
    private const val SIDE_TOLERANCE = 0.015f
    private const val SIDE_FROM = 0.12f
    private const val MIN_SIDE_POINTS = 6
    private const val MAX_SHIFT = 0.1f
}
