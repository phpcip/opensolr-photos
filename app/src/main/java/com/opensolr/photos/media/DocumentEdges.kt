package com.opensolr.photos.media

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The four corners of a sheet of paper in a small grey picture, top-left first and clockwise, as fractions of
 * the picture; null when nothing in it looks like a page. Three ways, cheapest first, each one only when the one
 * before found nothing: the page of the frame before, followed along the edges of this one; the four straight
 * edges that close the best page; the largest even region, lighter or darker than what is around it.
 * Whatever is found is fitted onto the edges of the picture last, for corners as exact as its pixels allow.
 */
object DocumentEdges {

    /** [previous]: the corners found in the frame before, as fractions, so a page that barely moved costs little. */
    fun find(grey: IntArray, width: Int, height: Int, previous: FloatArray? = null): FloatArray? {
        if (width < MIN_SIDE || height < MIN_SIDE || grey.size < width * height) return null
        val work = WORK.get()!!
        val edges = edges(grey, width, height, work)
        val quad = previous?.let { follow(it, edges) }
            ?: byLines(edges, work)
            ?: byRegion(grey, width, height)?.let { fit(it, edges, FIT_BAND) ?: it }
            ?: return null
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

    // buffers kept per thread (the camera's analysis thread, the thread that reads the photo): no allocation per frame
    private class Work {
        var smooth = IntArray(0)
        var across = IntArray(0)
        var mag = IntArray(0)
        var angle = ShortArray(0)
        var list = IntArray(0)
        var acc = IntArray(0)

        fun ensure(n: Int, accSize: Int) {
            if (smooth.size < n) {
                smooth = IntArray(n)
                across = IntArray(n)
                mag = IntArray(n)
                angle = ShortArray(n)
                list = IntArray(n)
            }
            if (acc.size < accSize) acc = IntArray(accSize)
        }
    }

    private val WORK = ThreadLocal.withInitial { Work() }

    /** The edge pixels of a picture: [angle] is the direction of the change of brightness (0 to 179 degrees), -1 elsewhere. */
    private class Edges(val width: Int, val height: Int, val angle: ShortArray, val list: IntArray, val count: Int)

    // ── the edges, shared by the three ways ──

    // a light blur, the brightness gradient (Sobel), thinned to its ridges, kept above a threshold set by this picture's own contrast
    private fun edges(grey: IntArray, width: Int, height: Int, work: Work): Edges {
        val n = width * height
        val radius = hypot(width.toDouble(), height.toDouble()).toInt() / 2 + 1
        work.ensure(n, ANGLES * (radius * 2 + 1))
        blurInto(grey, width, height, 1, work.across, work.smooth)
        val s = work.smooth
        val mag = work.mag
        val angle = work.angle
        java.util.Arrays.fill(mag, 0, n, 0)
        java.util.Arrays.fill(angle, 0, n, (-1).toShort())
        val histogram = IntArray(HISTOGRAM)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val gx = (s[i - width + 1] + 2 * s[i + 1] + s[i + width + 1]) - (s[i - width - 1] + 2 * s[i - 1] + s[i + width - 1])
                val gy = (s[i + width - 1] + 2 * s[i + width] + s[i + width + 1]) - (s[i - width - 1] + 2 * s[i - width] + s[i - width + 1])
                val m = abs(gx) + abs(gy)
                if (m < MIN_EDGE) continue
                mag[i] = m
                // the way brightness rises, 0 to 359 degrees: which side of an edge is the lighter one is kept
                var deg = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).roundToInt()
                if (deg < 0) deg += 360
                if (deg >= 360) deg -= 360
                angle[i] = deg.toShort()
                histogram[min(m / HISTOGRAM_STEP, HISTOGRAM - 1)]++
            }
        }
        // the threshold follows the strongest edges of this picture: a pale page on a pale desk still has its own
        var total = 0
        for (h in histogram) total += h
        var seen = 0
        var top = HISTOGRAM - 1
        for (b in HISTOGRAM - 1 downTo 0) {
            seen += histogram[b]
            if (seen >= total * (1f - EDGE_PERCENTILE)) {
                top = b
                break
            }
        }
        val threshold = max(MIN_EDGE, (top * HISTOGRAM_STEP * EDGE_SHARE).toInt())
        val list = work.list
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val raw = angle[i].toInt()
                if (raw < 0) continue
                val a = raw % ANGLES
                val m = mag[i]
                if (m < threshold) {
                    angle[i] = -1
                    continue
                }
                // a ridge: no stronger neighbour across the edge
                val (p, q) = when {
                    a < 23 || a >= 158 -> i - 1 to i + 1
                    a < 68 -> i - width - 1 to i + width + 1
                    a < 113 -> i - width to i + width
                    else -> i - width + 1 to i + width - 1
                }
                if (m < mag[p] || m < mag[q]) {
                    angle[i] = -1
                    continue
                }
                list[count++] = i
            }
        }
        return Edges(width, height, angle, list, count)
    }

    // ── 1. the page of the frame before ──

    // the corners of the frame before, kept when every side still runs along this frame's edges, then fitted onto them;
    // a page that moved is left to the search below, so a stripe or a shadow beside a side never pulls it away
    private fun follow(previous: FloatArray, e: Edges): FloatArray? {
        if (previous.size < 8) return null
        val q = FloatArray(8) { previous[it] * if (it % 2 == 0) e.width else e.height }
        if (!valid(q, e.width, e.height)) return null
        if (pageScore(q, e, FOLLOW_SUPPORT) <= 0f) return null
        return fit(q, e, FIT_BAND) ?: q
    }

    // ── 2. straight edges ──

    // every edge pixel votes for the lines through it in the direction of its edge (Hough), the strongest lines are
    // paired into opposite sides, and the four sides whose length is most covered by edges make the page
    private fun byLines(e: Edges, work: Work): FloatArray? {
        val w = e.width
        val h = e.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = hypot(w.toDouble(), h.toDouble()).toInt() / 2 + 1
        val rhos = radius * 2 + 1
        val acc = work.acc
        java.util.Arrays.fill(acc, 0, ANGLES * rhos, 0)
        for (k in 0 until e.count) {
            val i = e.list[k]
            val x = i % w + 0.5f - cx
            val y = i / w + 0.5f - cy
            val a = e.angle[i].toInt() % ANGLES
            for (d in -VOTE_SPREAD..VOTE_SPREAD) {
                val t = (a + d + ANGLES) % ANGLES
                val r = (x * COS[t] + y * SIN[t]).roundToInt() + radius
                if (r in 0 until rhos) acc[t * rhos + r]++
            }
        }
        val minVotes = max(MIN_VOTES, (min(w, h) * MIN_LINE_SHARE).toInt())
        // the strongest lines, each one standing apart from the ones already taken
        val peaks = ArrayList<IntArray>()
        for (t in 0 until ANGLES) {
            val base = t * rhos
            for (r in 1 until rhos - 1) {
                val v = acc[base + r]
                if (v < minVotes || v < acc[base + r - 1] || v < acc[base + r + 1]) continue
                peaks += intArrayOf(v, t, r - radius)
            }
        }
        peaks.sortByDescending { it[0] }
        val lines = ArrayList<FloatArray>()
        for (p in peaks) {
            val t = p[1]
            val r = p[2].toFloat()
            val distinct = lines.none { l -> sameLine(t, r, l[3].toInt(), l[2]) }
            if (distinct) lines += floatArrayOf(COS[t], SIN[t], r, t.toFloat())
            if (lines.size >= MAX_LINES) break
        }
        if (lines.size < 4) return null

        // pairs of nearly parallel lines far enough apart to be two opposite sides
        val gap = min(w, h) * MIN_GAP_SHARE
        val pairs = ArrayList<IntArray>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val d = angleApart(lines[i][3].toInt(), lines[j][3].toInt())
            if (d > MAX_PARALLEL) continue
            val sameWay = lines[i][0] * lines[j][0] + lines[i][1] * lines[j][1] > 0
            val rj = if (sameWay) lines[j][2] else -lines[j][2]
            if (abs(lines[i][2] - rj) >= gap) pairs += intArrayOf(i, j)
        }
        var best: FloatArray? = null
        var bestScore = 0f
        val corner = FloatArray(2)
        for (pi in pairs.indices) for (qi in pi + 1 until pairs.size) {
            val a = pairs[pi]
            val b = pairs[qi]
            if (a[0] == b[0] || a[0] == b[1] || a[1] == b[0] || a[1] == b[1]) continue
            if (angleApart(lines[a[0]][3].toInt(), lines[b[0]][3].toInt()) < MIN_CROSS) continue
            val q = FloatArray(8)
            val order = intArrayOf(a[0], b[0], a[0], b[1], a[1], b[1], a[1], b[0])
            var ok = true
            for (c in 0 until 4) {
                if (!meet(lines[order[c * 2]], lines[order[c * 2 + 1]], corner)) { ok = false; break }
                q[c * 2] = corner[0] + cx
                q[c * 2 + 1] = corner[1] + cy
            }
            if (!ok) continue
            val page = order(q)
            if (!valid(page, w, h)) continue
            val score = pageScore(page, e, LINE_SUPPORT)
            if (score > bestScore) {
                bestScore = score
                best = page
            }
        }
        val page = best ?: return null
        return fit(page, e, FIT_BAND) ?: page
    }

    private fun sameLine(t1: Int, r1: Float, t2: Int, r2: Float): Boolean {
        val d = abs(t1 - t2)
        return if (d <= 90) d <= LINE_APART_DEG && abs(r1 - r2) <= LINE_APART_PX
        else (ANGLES - d) <= LINE_APART_DEG && abs(r1 + r2) <= LINE_APART_PX
    }

    // how far apart two directions are, 0 to 90 degrees
    private fun angleApart(a: Int, b: Int): Int {
        val d = abs(a - b) % ANGLES
        return min(d, ANGLES - d)
    }

    // where two lines (normal x, normal y, distance) cross; false when they are parallel
    private fun meet(l1: FloatArray, l2: FloatArray, out: FloatArray): Boolean {
        val det = l1[0] * l2[1] - l1[1] * l2[0]
        if (abs(det) < 1e-4f) return false
        out[0] = (l1[2] * l2[1] - l1[1] * l2[2]) / det
        out[1] = (l1[0] * l2[2] - l1[2] * l2[0]) / det
        return true
    }

    // how much of a page's outline runs along edges with the page on the same side of the change every time: a sheet is
    // lighter (or darker) than what is around it all along its four sides, a checked cloth or a striped wood flips at
    // every square; 0 when a side is covered less than [least], else the covered length
    private fun pageScore(q: FloatArray, e: Edges, least: Float): Float {
        val lighter = FloatArray(4)
        val darker = FloatArray(4)
        for (side in 0 until 4) support(q, side, e, lighter, darker)
        val inward = lighter.sum() >= darker.sum()
        var score = 0f
        for (side in 0 until 4) {
            val s = if (inward) lighter[side] else darker[side]
            if (s < least) return 0f
            score += s * sideLength(q, side)
        }
        return score
    }

    // the share of a side that runs along edge pixels within two pixels of it, split by which side is lighter:
    // [lighter] where brightness rises into the page, [darker] where it falls
    private fun support(q: FloatArray, side: Int, e: Edges, lighter: FloatArray, darker: FloatArray) {
        lighter[side] = 0f
        darker[side] = 0f
        val ax = q[side * 2]
        val ay = q[side * 2 + 1]
        val bx = q[(side + 1) % 4 * 2]
        val by = q[(side + 1) % 4 * 2 + 1]
        val length = hypot(bx - ax, by - ay)
        if (length < 1f) return
        // corners run clockwise on screen, so this normal points into the page
        val nx = -(by - ay) / length
        val ny = (bx - ax) / length
        var inward = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble())).roundToInt()
        if (inward < 0) inward += 360
        val samples = max(MIN_SAMPLES, length.toInt())
        var into = 0
        var out = 0
        for (k in 0 until samples) {
            val t = SIDE_FROM + (1f - 2 * SIDE_FROM) * k / (samples - 1)
            val x = ax + (bx - ax) * t
            val y = ay + (by - ay) * t
            var foundIn = false
            var foundOut = false
            for (d in -SUPPORT_BAND..SUPPORT_BAND) {
                val px = (x + nx * d).toInt()
                val py = (y + ny * d).toInt()
                if (px < 0 || py < 0 || px >= e.width || py >= e.height) continue
                val a = e.angle[py * e.width + px].toInt()
                if (a < 0) continue
                val turn = abs(a - inward) % 360
                val apart = min(turn, 360 - turn)
                if (apart <= FACING_DEG) foundIn = true
                if (180 - apart <= FACING_DEG) foundOut = true
            }
            if (foundIn) into++
            if (foundOut) out++
        }
        lighter[side] = into.toFloat() / samples
        darker[side] = out.toFloat() / samples
    }

    private fun sideLength(q: FloatArray, side: Int): Float =
        hypot(q[(side + 1) % 4 * 2] - q[side * 2], q[(side + 1) % 4 * 2 + 1] - q[side * 2 + 1])

    // each side refitted on the edge pixels within [band] of it that face its way, then once more on those that hug
    // that line, so stray edges beside it drop out; the corners where the sides meet
    private fun fit(q: FloatArray, e: Edges, band: Float): FloatArray? {
        val diag = hypot(e.width.toFloat(), e.height.toFloat())
        val reach = max(band, 1.5f) * diag / BAND_SCALE
        val hug = HUG_BAND * diag / BAND_SCALE
        val lines = Array(4) { FloatArray(3) }
        for (side in 0 until 4) {
            val ax = q[side * 2]
            val ay = q[side * 2 + 1]
            val bx = q[(side + 1) % 4 * 2]
            val by = q[(side + 1) % 4 * 2 + 1]
            if (hypot(bx - ax, by - ay) < 1f) return null
            val first = sideLine(e, ax, ay, bx, by, reach) ?: return null
            // the same side again, its ends moved onto the line just found, from the points within [hug] of it
            val offA = first[0] * ax + first[1] * ay - first[2]
            val offB = first[0] * bx + first[1] * by - first[2]
            lines[side] = sideLine(e, ax - first[0] * offA, ay - first[1] * offA, bx - first[0] * offB, by - first[1] * offB, hug) ?: first
        }
        val out = FloatArray(8)
        val corner = FloatArray(2)
        for (c in 0 until 4) {
            if (!meet(lines[(c + 3) % 4], lines[c], corner)) return null
            if (hypot(corner[0] - q[c * 2], corner[1] - q[c * 2 + 1]) > diag * MAX_SHIFT) return null
            out[c * 2] = corner[0]
            out[c * 2 + 1] = corner[1]
        }
        return if (valid(out, e.width, e.height)) out else null
    }

    // the line (normal x, normal y, distance) through the edge pixels within [reach] of the segment A-B, in its middle,
    // facing its way; null with too few of them
    private fun sideLine(e: Edges, ax: Float, ay: Float, bx: Float, by: Float, reach: Float): FloatArray? {
        val w = e.width
        val length = hypot(bx - ax, by - ay)
        if (length < 1f) return null
        val dx = (bx - ax) / length
        val dy = (by - ay) / length
        var normal = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).roundToInt()
        if (normal < 0) normal += 180
        if (normal >= 180) normal -= 180
        var count = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (k in 0 until e.count) {
            val i = e.list[k]
            val px = i % w + 0.5f
            val py = i / w + 0.5f
            val rx = px - ax
            val ry = py - ay
            val t = (rx * dx + ry * dy) / length
            if (t < SIDE_FROM || t > 1f - SIDE_FROM) continue
            if (abs(-rx * dy + ry * dx) > reach) continue
            if (angleApart(e.angle[i].toInt(), normal) > FACING_DEG) continue
            count++
            sx += px
            sy += py
            sxx += px.toDouble() * px
            syy += py.toDouble() * py
            sxy += px.toDouble() * py
        }
        if (count < MIN_SIDE_POINTS) return null
        val mx = sx / count
        val my = sy / count
        val dir = 0.5 * atan2(2 * (sxy / count - mx * my), (sxx / count - mx * mx) - (syy / count - my * my))
        val nx = -sin(dir)
        val ny = cos(dir)
        return floatArrayOf(nx.toFloat(), ny.toFloat(), (nx * mx + ny * my).toFloat())
    }

    // a believable page: convex, no corner too sharp, a fair share of the picture, inside it, cut by its border on one corner at most
    private fun valid(q: FloatArray, width: Int, height: Int): Boolean {
        if (!convex(q) || !angles(q)) return false
        val n = width.toFloat() * height
        val area = polygonArea(q)
        if (area < n * MIN_SHARE || area > n * MAX_SHARE) return false
        val outX = width * OUTSIDE
        val outY = height * OUTSIDE
        val marginX = width * BORDER
        val marginY = height * BORDER
        var onBorder = 0
        for (c in 0 until 4) {
            val x = q[c * 2]
            val y = q[c * 2 + 1]
            if (x < -outX || y < -outY || x > width + outX || y > height + outY) return false
            if (x <= marginX || x >= width - marginX || y <= marginY || y >= height - marginY) onBorder++
        }
        return onBorder <= 1
    }

    // ── 3. the largest even region ──

    private fun byRegion(grey: IntArray, width: Int, height: Int): FloatArray? {
        val smooth = IntArray(width * height)
        blurInto(grey, width, height, REGION_BLUR, IntArray(width * height), smooth)
        val threshold = otsu(smooth, width * height)
        val light = region(smooth, width, height) { it > threshold }
        val dark = region(smooth, width, height) { it <= threshold }
        return listOfNotNull(light, dark).maxByOrNull { polygonArea(it) }
    }

    // the largest region on one side of the threshold, kept only when its outline is a believable page
    private fun region(smooth: IntArray, width: Int, height: Int, inside: (Int) -> Boolean): FloatArray? {
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
        val quad = refineOutline(rough, points, width, height) ?: rough
        // a page fills its outline (the letters on it aside); a background around a page does not
        if (bestArea / polygonArea(quad) < MIN_FILL) return null
        return if (valid(quad, width, height)) quad else null
    }

    // each side straightened along the outline points that lie on it, the corners where the sides meet
    private fun refineOutline(q: FloatArray, points: List<FloatArray>, width: Int, height: Int): FloatArray? {
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
            lines[side] = floatArrayOf(mx.toFloat(), my.toFloat(), cos(angle).toFloat(), sin(angle).toFloat())
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

    // box blur of [radius], horizontal into [across] then vertical into [out]
    private fun blurInto(grey: IntArray, width: Int, height: Int, radius: Int, across: IntArray, out: IntArray) {
        val r = radius
        val span = 2 * r + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (x in -r..r) sum += grey[row + x.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                across[row + x] = sum / span
                sum += grey[row + (x + r + 1).coerceAtMost(width - 1)] - grey[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until width) {
            var sum = 0
            for (y in -r..r) sum += across[y.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / span
                sum += across[(y + r + 1).coerceAtMost(height - 1) * width + x] - across[(y - r).coerceAtLeast(0) * width + x]
            }
        }
    }

    // the grey level that best splits the picture in two
    private fun otsu(values: IntArray, n: Int): Int {
        val histogram = IntArray(256)
        for (i in 0 until n) histogram[values[i].coerceIn(0, 255)]++
        val total = n.toDouble()
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

    private const val ANGLES = 180
    private val COS = FloatArray(ANGLES) { cos(Math.toRadians(it.toDouble())).toFloat() }
    private val SIN = FloatArray(ANGLES) { sin(Math.toRadians(it.toDouble())).toFloat() }

    private const val MIN_SIDE = 16
    private const val MIN_EDGE = 40
    private const val HISTOGRAM = 256
    private const val HISTOGRAM_STEP = 8
    private const val EDGE_PERCENTILE = 0.98f
    private const val EDGE_SHARE = 0.2f
    private const val VOTE_SPREAD = 10
    private const val MIN_VOTES = 12
    private const val MIN_LINE_SHARE = 0.1f
    private const val MAX_LINES = 16
    private const val LINE_APART_DEG = 6
    private const val LINE_APART_PX = 6f
    private const val MIN_GAP_SHARE = 0.12f
    private const val MAX_PARALLEL = 35
    private const val MIN_CROSS = 45
    private const val LINE_SUPPORT = 0.5f
    private const val FOLLOW_SUPPORT = 0.55f
    private const val SUPPORT_BAND = 2
    private const val FACING_DEG = 15
    private const val MIN_SAMPLES = 16
    private const val FIT_BAND = 2.5f
    private const val HUG_BAND = 1.2f
    private const val BAND_SCALE = 400f
    private const val REGION_BLUR = 2
    private const val MIN_SHARE = 0.12f
    private const val MAX_SHARE = 0.985f
    private const val MIN_FILL = 0.7f
    private const val MIN_ANGLE = 35.0
    private const val THIN_TO = 10
    private const val BORDER = 0.015f
    private const val OUTSIDE = 0.02f
    private const val SIDE_TOLERANCE = 0.015f
    private const val SIDE_FROM = 0.12f
    private const val MIN_SIDE_POINTS = 6
    private const val MAX_SHIFT = 0.1f
}
