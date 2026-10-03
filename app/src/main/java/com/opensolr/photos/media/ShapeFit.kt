package com.opensolr.photos.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A hand-drawn line read as the shape it was meant to be: a straight line, an arrow, a triangle, a rectangle, a circle or an oval. */
object ShapeFit {

    fun fit(raw: FloatArray, color: Int, width: Float): EditOp.Shape? {
        val input = ArrayList<P>(raw.size / 2)
        var i = 0
        while (i + 1 < raw.size) {
            input += P(raw[i], raw[i + 1])
            i += 2
        }
        if (input.size < 6) return null
        val minX = input.minOf { it.x }
        val maxX = input.maxOf { it.x }
        val minY = input.minOf { it.y }
        val maxY = input.maxOf { it.y }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < width * 4f) return null
        // evenly spaced first (a fast finger sends few points), the tremor smoothed over a fixed share of the drawing,
        // then spaced again: lengths and angles mean the drawing, not the noise or the touch rate
        val pts = even(smooth(even(input, max(diag / DENSE_STEPS, 0.5f))), max(diag / EVEN_STEPS, 1f))
        if (pts.size < 6) return null
        val len = length(pts)
        val first = pts.first()
        val last = pts.last()
        val gap = dist(first, last)

        if (gap > LINE_SPAN * len && maxDeviation(pts, 0, pts.size - 1, first, last) < LINE_TOLERANCE * gap) {
            return EditOp.Shape(EditOp.ShapeKind.LINE, floatArrayOf(first.x, first.y, last.x, last.y), color, width)
        }
        arrow(pts)?.let { (tail, tip) ->
            return EditOp.Shape(EditOp.ShapeKind.ARROW, floatArrayOf(tail.x, tail.y, tip.x, tip.y), color, width)
        }
        val loop = loop(pts, diag) ?: return null
        return closed(loop, diag, color, width)
    }

    // the drawing as a closed loop: the end has come back to the start (or run past it, which is cut off)
    private fun loop(pts: List<P>, diag: Float): List<P>? {
        val n = pts.size
        val first = pts.first()
        var best = -1
        var bestD = Float.MAX_VALUE
        for (k in (n * 2 / 3) until n) {
            val d = dist(pts[k], first)
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0 || bestD > CLOSED_GAP * diag) return null
        return pts.subList(0, best + 1)
    }

    private fun smooth(pts: List<P>): List<P> {
        if (pts.size < 5) return pts
        return pts.indices.map { i ->
            val lo = max(0, i - SMOOTH)
            val hi = min(pts.size - 1, i + SMOOTH)
            var x = 0f
            var y = 0f
            for (k in lo..hi) { x += pts[k].x; y += pts[k].y }
            P(x / (hi - lo + 1), y / (hi - lo + 1))
        }
    }

    // points [step] apart along the line, ends kept
    private fun even(pts: List<P>, step: Float): List<P> {
        val out = ArrayList<P>()
        out += pts.first()
        var carried = 0f
        for (i in 1 until pts.size) {
            var a = pts[i - 1]
            val b = pts[i]
            var d = dist(a, b)
            while (carried + d >= step) {
                val t = (step - carried) / d
                val q = P(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                out += q
                a = q
                d = dist(a, b)
                carried = 0f
            }
            carried += d
        }
        if (dist(out.last(), pts.last()) > step / 2) out += pts.last()
        return out
    }

    // a straight shaft to the point farthest from the start, then a short head that turns back around that point
    private fun arrow(pts: List<P>): Pair<P, P>? {
        val start = pts.first()
        var k = 0
        var far = 0f
        pts.forEachIndexed { i, p -> val d = dist(start, p); if (d > far) { far = d; k = i } }
        // the tip is where the shaft first reaches it: a head drawn out and back passes the tip again
        k = pts.indexOfFirst { dist(start, it) >= TIP_REACH * far }
        if (k < 3 || k > pts.size - 3) return null
        val tip = pts[k]
        val shaft = far
        if (maxDeviation(pts, 0, k, start, tip) > ARROW_SHAFT_TOLERANCE * shaft) return null
        val head = pts.subList(k, pts.size)
        val headLen = length(head)
        if (headLen < 0.08f * shaft || headLen > 1.5f * shaft) return null
        val dx = (tip.x - start.x) / shaft
        val dy = (tip.y - start.y) / shaft
        var sideways = 0f
        for (p in head) {
            if (dist(p, tip) > 0.5f * shaft) return null
            // the head lies behind the tip, never past it
            if ((p.x - tip.x) * dx + (p.y - tip.y) * dy > 0.05f * shaft) return null
            sideways = max(sideways, abs((p.x - tip.x) * dy - (p.y - tip.y) * dx))
        }
        if (sideways < 0.05f * shaft) return null
        return start to tip
    }

    private fun closed(raw: List<P>, diag: Float, color: Int, width: Float): EditOp.Shape? {
        val pts = resample(raw + raw.first(), SAMPLES)
        // the outline reduced to its real corners: few corners is a polygon, never an oval
        val corners = simplifyLoop(pts, POLYGON_TOLERANCE * diag)
        // the closing gap counts as a side only when it is a small part of what was drawn
        val gapIsSmall = dist(raw.last(), raw.first()) <= FILL_SHARE * length(raw)
        if (gapIsSmall && (corners.size == 3 || corners.size == 4)) {
            polygon(corners.map { pts[it] }, diag)?.let { return EditOp.Shape(EditOp.ShapeKind.POLYGON, it, color, width) }
        }
        openPolygon(raw, diag)?.let { return EditOp.Shape(EditOp.ShapeKind.POLYGON, it, color, width) }
        if (corners.size < OVAL_MIN_CORNERS) return null
        // the oval from the part actually drawn, so an outline left open is read as the whole oval
        return ellipse(raw)?.let { EditOp.Shape(EditOp.ShapeKind.ELLIPSE, it, color, width) }
    }

    // a polygon whose outline was left open, or begun and ended inside a corner: the two end sides, run on until they meet, give that corner back
    private fun openPolygon(raw: List<P>, diag: Float): FloatArray? {
        val v = simplifyOpen(raw, POLYGON_TOLERANCE * diag)
        if (v.size < 4) return null
        val start = raw.first()
        val end = raw.last()
        val inner = v.subList(1, v.size - 1).map { raw[it] }
        val a = raw[v[1]]
        val b = raw[v[v.size - 2]]
        val ax = a.x - start.x
        val ay = a.y - start.y
        val bx = end.x - b.x
        val by = end.y - b.y
        val cross = ax * by - ay * bx
        val sine = abs(cross) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-3f)
        val corners = if (sine < PARALLEL_SINE) {
            inner
        } else {
            // where the line through the last side meets the line through the first
            val t = ((start.x - b.x) * ay - (start.y - b.y) * ax) / (bx * ay - by * ax)
            val x = P(b.x + t * bx, b.y + t * by)
            val minX = raw.minOf { it.x } - CORNER_REACH * diag
            val maxX = raw.maxOf { it.x } + CORNER_REACH * diag
            val minY = raw.minOf { it.y } - CORNER_REACH * diag
            val maxY = raw.maxOf { it.y } + CORNER_REACH * diag
            if (x.x !in minX..maxX || x.y !in minY..maxY) return null
            inner + x
        }
        if (corners.size != 3 && corners.size != 4) return null
        // only a small part of the outline may be filled in: a check mark or an S is no polygon left open
        val filled = dist(end, corners.last()).takeIf { corners.size > inner.size } ?.let { it + dist(corners.last(), start) } ?: dist(end, start)
        if (filled > FILL_SHARE * length(raw)) return null
        return polygon(corners, diag)
    }

    private fun simplifyOpen(pts: List<P>, tolerance: Float): List<Int> {
        val keep = sortedSetOf(0, pts.size - 1)
        fun walk(from: Int, to: Int) {
            var worst = -1
            var worstD = tolerance
            for (i in from + 1 until to) {
                val d = segmentDistance(pts[i], pts[from], pts[to])
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worst < 0) return
            keep += worst
            walk(from, worst)
            walk(worst, to)
        }
        walk(0, pts.size - 1)
        return keep.toList()
    }

    // Douglas-Peucker on a closed loop, split at its two farthest-apart points; indexes of the corners kept, in order
    private fun simplifyLoop(pts: List<P>, tolerance: Float): List<Int> {
        val n = pts.size
        val cx = pts.sumOf { it.x.toDouble() }.toFloat() / n
        val cy = pts.sumOf { it.y.toDouble() }.toFloat() / n
        val a = pts.indices.maxByOrNull { hypot(pts[it].x - cx, pts[it].y - cy) } ?: return emptyList()
        val b = pts.indices.maxByOrNull { dist(pts[it], pts[a]) } ?: return emptyList()
        val keep = sortedSetOf(a, b)
        fun walk(from: Int, to: Int) {
            // indexes from [from] forward to [to] around the loop
            val span = (to - from + n) % n
            if (span < 2) return
            var worst = -1
            var worstD = tolerance
            for (s in 1 until span) {
                val i = (from + s) % n
                val d = segmentDistance(pts[i], pts[from], pts[to])
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worst < 0) return
            keep += worst
            walk(from, worst)
            walk(worst, to)
        }
        walk(a, b)
        walk(b, a)
        return keep.toList()
    }

    private fun polygon(v: List<P>, diag: Float): FloatArray? {
        for (c in v.indices) {
            val a = v[c]
            val b = v[(c + 1) % v.size]
            val side = dist(a, b)
            // the simplification already holds every point near its side; a side must only be a real side
            if (side < 0.15f * diag) return null
        }
        if (v.size == 3) return floatArrayOf(v[0].x, v[0].y, v[1].x, v[1].y, v[2].x, v[2].y)
        // four corners: the rectangle they stand for, square to its own sides, level when it was drawn nearly level
        var s4 = 0.0
        var c4 = 0.0
        for (c in 0 until 4) {
            val a = v[c]
            val b = v[(c + 1) % 4]
            val phi = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            s4 += sin(4 * phi)
            c4 += cos(4 * phi)
        }
        var theta = atan2(s4, c4) / 4
        if (abs(Math.toDegrees(theta)) < LEVEL_DEGREES) theta = 0.0
        val ux = cos(theta).toFloat()
        val uy = sin(theta).toFloat()
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (p in v) {
            val u = p.x * ux + p.y * uy
            val w = -p.x * uy + p.y * ux
            minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, w); maxV = max(maxV, w)
        }
        fun back(u: Float, w: Float) = floatArrayOf(u * ux - w * uy, u * uy + w * ux)
        return back(minU, minV) + back(maxU, minV) + back(maxU, maxV) + back(minU, maxV)
    }

    // the oval that best fits the drawn points (direct least squares, Fitzgibbon / Halir-Flusser), kept only when
    // the points sit on it and go most of the way around it
    private fun ellipse(raw: List<P>): FloatArray? {
        val n = raw.size
        if (n < 6) return null
        val mx = raw.sumOf { it.x.toDouble() } / n
        val my = raw.sumOf { it.y.toDouble() } / n
        val sc = raw.maxOf { max(abs(it.x - mx), abs(it.y - my)) }.coerceAtLeast(1.0)
        val s1 = Array(3) { DoubleArray(3) }
        val s2 = Array(3) { DoubleArray(3) }
        val s3 = Array(3) { DoubleArray(3) }
        for (p in raw) {
            val x = (p.x - mx) / sc
            val y = (p.y - my) / sc
            val d1 = doubleArrayOf(x * x, x * y, y * y)
            val d2 = doubleArrayOf(x, y, 1.0)
            for (r in 0..2) for (c in 0..2) {
                s1[r][c] += d1[r] * d1[c]
                s2[r][c] += d1[r] * d2[c]
                s3[r][c] += d2[r] * d2[c]
            }
        }
        val s3i = invert(s3) ?: return null
        // T = -S3^-1 S2^T ; M = S1 + S2 T
        val t = Array(3) { r -> DoubleArray(3) { c -> -(0..2).sumOf { k -> s3i[r][k] * s2[c][k] } } }
        val m = Array(3) { r -> DoubleArray(3) { c -> s1[r][c] + (0..2).sumOf { k -> s2[r][k] * t[k][c] } } }
        val mm = arrayOf(
            DoubleArray(3) { m[2][it] / 2 },
            DoubleArray(3) { -m[1][it] },
            DoubleArray(3) { m[0][it] / 2 },
        )
        var best: DoubleArray? = null
        for (lambda in eigenvalues(mm)) {
            val v = nullVector(mm, lambda) ?: continue
            if (4 * v[0] * v[2] - v[1] * v[1] > 0) { best = v; break }
        }
        val a1 = best ?: return null
        val a2 = DoubleArray(3) { r -> (0..2).sumOf { k -> t[r][k] * a1[k] } }
        val qa = a1[0]
        val qb = a1[1]
        val qc = a1[2]
        val qd = a2[0]
        val qe = a2[1]
        val qf = a2[2]
        val den = qb * qb - 4 * qa * qc
        if (den >= 0) return null
        val x0 = (2 * qc * qd - qb * qe) / den
        val y0 = (2 * qa * qe - qb * qd) / den
        val f0 = qa * x0 * x0 + qb * x0 * y0 + qc * y0 * y0 + qd * x0 + qe * y0 + qf
        // axes: the eigenvectors of the quadratic part
        val tr = qa + qc
        val disc = sqrt(((qa - qc) * (qa - qc) + qb * qb).coerceAtLeast(0.0))
        val l1 = (tr + disc) / 2
        val l2 = (tr - disc) / 2
        if (-f0 / l1 <= 0 || -f0 / l2 <= 0) return null
        var rx = sqrt(-f0 / l1) * sc
        var ry = sqrt(-f0 / l2) * sc
        var theta = if (abs(qb) < 1e-12) (if (qa <= qc) 0.0 else PI / 2) else atan2(l1 - qa, qb / 2)
        val ox = x0 * sc + mx
        val oy = y0 * sc + my
        val ux = cos(theta)
        val uy = sin(theta)
        var sum = 0.0
        var worst = 0.0
        val angles = DoubleArray(n)
        raw.forEachIndexed { i, p ->
            val dx = p.x - ox
            val dy = p.y - oy
            val u = (dx * ux + dy * uy) / rx
            val v = (-dx * uy + dy * ux) / ry
            val e = abs(sqrt(u * u + v * v) - 1)
            sum += e
            worst = max(worst, e)
            angles[i] = atan2(v, u)
        }
        if (sum / n > OVAL_MEAN_ERROR || worst > OVAL_WORST_ERROR) return null
        angles.sort()
        var gap = angles[0] + 2 * PI - angles[n - 1]
        for (i in 1 until n) gap = max(gap, angles[i] - angles[i - 1])
        if (2 * PI - gap < Math.toRadians(OVAL_MIN_SWEEP)) return null
        if (max(rx, ry) / min(rx, ry) < ROUND_RATIO) {
            val r = (rx + ry) / 2
            rx = r; ry = r; theta = 0.0
        } else {
            val deg = Math.toDegrees(theta)
            val off = ((deg % 180) + 180) % 180
            if (off < LEVEL_DEGREES || off > 180 - LEVEL_DEGREES) theta = 0.0
            else if (abs(off - 90) < LEVEL_DEGREES) { val tmp = rx; rx = ry; ry = tmp; theta = 0.0 }
        }
        return floatArrayOf(ox.toFloat(), oy.toFloat(), rx.toFloat(), ry.toFloat(), Math.toDegrees(theta).toFloat())
    }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val k = m[2][2]
        val det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return null
        return arrayOf(
            doubleArrayOf((e * k - f * h) / det, (c * h - b * k) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * k) / det, (a * k - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }

    // the real roots of the 3x3 matrix's characteristic polynomial
    private fun eigenvalues(m: Array<DoubleArray>): List<Double> {
        val tr = m[0][0] + m[1][1] + m[2][2]
        val minors = m[0][0] * m[1][1] - m[0][1] * m[1][0] + m[0][0] * m[2][2] - m[0][2] * m[2][0] + m[1][1] * m[2][2] - m[1][2] * m[2][1]
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        // x^3 + a x^2 + b x + c with a = -tr, b = minors, c = -det
        val a = -tr
        val b = minors
        val c = -det
        val q = (3 * b - a * a) / 9
        val r = (9 * a * b - 27 * c - 2 * a * a * a) / 54
        val d = q * q * q + r * r
        val shift = -a / 3
        return if (d > 0) {
            val sd = sqrt(d)
            listOf(shift + Math.cbrt(r + sd) + Math.cbrt(r - sd))
        } else {
            val rho = sqrt(-q * q * q)
            val phi = kotlin.math.acos((r / rho).coerceIn(-1.0, 1.0))
            val k = 2 * Math.cbrt(rho)
            listOf(shift + k * cos(phi / 3), shift + k * cos((phi + 2 * PI) / 3), shift + k * cos((phi + 4 * PI) / 3))
        }
    }

    private fun nullVector(m: Array<DoubleArray>, lambda: Double): DoubleArray? {
        val r = Array(3) { i -> DoubleArray(3) { j -> m[i][j] - if (i == j) lambda else 0.0 } }
        fun cross(u: DoubleArray, v: DoubleArray) = doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        val best = listOf(cross(r[0], r[1]), cross(r[0], r[2]), cross(r[1], r[2])).maxByOrNull { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] } ?: return null
        val norm = sqrt(best[0] * best[0] + best[1] * best[1] + best[2] * best[2])
        if (norm < 1e-12) return null
        return DoubleArray(3) { best[it] / norm }
    }

    private class P(val x: Float, val y: Float)

    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    private fun length(pts: List<P>): Float {
        var l = 0f
        for (i in 1 until pts.size) l += dist(pts[i - 1], pts[i])
        return l
    }

    private fun maxDeviation(pts: List<P>, from: Int, to: Int, a: P, b: P): Float {
        var worst = 0f
        for (i in from..to) worst = max(worst, segmentDistance(pts[i], a, b))
        return worst
    }

    private fun segmentDistance(p: P, a: P, b: P): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    // [count] points evenly spaced along the line, the last one left out (it closes on the first)
    private fun resample(pts: List<P>, count: Int): List<P> {
        val total = length(pts)
        val step = total / count
        val out = ArrayList<P>(count)
        out += pts.first()
        var carried = 0f
        for (i in 1 until pts.size) {
            var a = pts[i - 1]
            val b = pts[i]
            var d = dist(a, b)
            while (carried + d >= step && out.size < count) {
                val t = (step - carried) / d
                val q = P(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                out += q
                a = q
                d = dist(a, b)
                carried = 0f
            }
            carried += d
        }
        return out
    }

    private const val LINE_TOLERANCE = 0.06f
    private const val LINE_SPAN = 0.9f
    private const val SMOOTH = 8
    private const val DENSE_STEPS = 300f
    private const val TIP_REACH = 0.97f
    private const val EVEN_STEPS = 120f
    private const val ARROW_SHAFT_TOLERANCE = 0.08f
    private const val CLOSED_GAP = 0.6f
    private const val SAMPLES = 96
    private const val POLYGON_TOLERANCE = 0.06f
    private const val OVAL_MIN_CORNERS = 6
    private const val LEVEL_DEGREES = 10.0
    private const val OVAL_MEAN_ERROR = 0.12
    private const val OVAL_WORST_ERROR = 0.35
    private const val ROUND_RATIO = 1.12
    private const val OVAL_MIN_SWEEP = 240.0
    private const val PARALLEL_SINE = 0.34f
    private const val CORNER_REACH = 0.3f
    private const val FILL_SHARE = 0.3f
}
