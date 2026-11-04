package com.siyuanink

import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor
import kotlin.math.hypot

/** [pressure] va normalizada de 0 a 1 (0.5 = presión media = grosor base). */
class InkPoint(val x: Float, val y: Float, val pressure: Float, val t: Long)

class Stroke(val width: Float, val points: List<InkPoint>) {

    val bounds: RectF = RectF(points[0].x, points[0].y, points[0].x, points[0].y).also { r ->
        for (p in points) r.union(p.x, p.y)
        r.inset(-width * MAX_FACTOR / 2f, -width * MAX_FACTOR / 2f)
    }

    val startTime: Long get() = points.first().t

    fun translated(dx: Float, dy: Float) =
        Stroke(width, points.map { InkPoint(it.x + dx, it.y + dy, it.pressure, it.t) })

    /** Grosor de cada punto según la presión (suavizada para evitar saltos). */
    private fun widths(): FloatArray {
        val n = points.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            var c = 0
            for (j in maxOf(0, i - 2)..minOf(n - 1, i + 2)) { sum += points[j].pressure; c++ }
            out[i] = widthFor(width, sum / c)
        }
        return out
    }

    /**
     * Contorno del trazo de grosor variable: polígono (lado izquierdo + lado derecho)
     * más círculos en los extremos. Se usa igual en pantalla y en el SVG.
     */
    private fun outline(): Pair<List<FloatArray>, List<FloatArray>> {
        val w = widths()
        val n = points.size
        val left = ArrayList<FloatArray>(n)
        val right = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val a = points[maxOf(0, i - 1)]
            val b = points[minOf(n - 1, i + 1)]
            var dx = b.x - a.x
            var dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 0.0001f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            val h = w[i] / 2f
            val p = points[i]
            left.add(floatArrayOf(p.x - dy * h, p.y + dx * h))
            right.add(floatArrayOf(p.x + dy * h, p.y - dx * h))
        }
        return left to right
    }

    fun toPath(): Path {
        val path = Path()
        path.fillType = Path.FillType.WINDING
        val w = widths()
        val first = points.first()
        val last = points.last()
        path.addCircle(first.x, first.y, w.first() / 2f, Path.Direction.CW)
        if (points.size > 1) {
            path.addCircle(last.x, last.y, w.last() / 2f, Path.Direction.CW)
            val (left, right) = outline()
            path.moveTo(left[0][0], left[0][1])
            for (i in 1 until left.size) path.lineTo(left[i][0], left[i][1])
            for (i in right.indices.reversed()) path.lineTo(right[i][0], right[i][1])
            path.close()
        }
        return path
    }

    fun svgPathData(): String {
        val sb = StringBuilder()
        val w = widths()
        circle(sb, points.first().x, points.first().y, w.first() / 2f)
        if (points.size > 1) {
            circle(sb, points.last().x, points.last().y, w.last() / 2f)
            val (left, right) = outline()
            sb.append("M").append(f(left[0][0])).append(' ').append(f(left[0][1]))
            for (i in 1 until left.size) sb.append(" L").append(f(left[i][0])).append(' ').append(f(left[i][1]))
            for (i in right.indices.reversed()) sb.append(" L").append(f(right[i][0])).append(' ').append(f(right[i][1]))
            sb.append("Z")
        }
        return sb.toString()
    }

    private fun circle(sb: StringBuilder, cx: Float, cy: Float, r: Float) {
        sb.append("M").append(f(cx - r)).append(' ').append(f(cy))
            .append(" a").append(f(r)).append(' ').append(f(r)).append(" 0 1 0 ").append(f(2 * r)).append(" 0")
            .append(" a").append(f(r)).append(' ').append(f(r)).append(" 0 1 0 ").append(f(-2 * r)).append(" 0Z ")
    }

    companion object {
        const val MIN_FACTOR = 0.35f
        const val MAX_FACTOR = 1.65f

        /** Presión 0 → 35 % del grosor base; 0.5 → grosor base; 1 → 165 %. */
        fun widthFor(base: Float, pressure: Float): Float =
            base * (MIN_FACTOR + (MAX_FACTOR - MIN_FACTOR) * pressure.coerceIn(0f, 1f))
    }

    /** ¿Pasa la goma (punto x,y con radio) por encima de este trazo? */
    fun isNear(x: Float, y: Float, radius: Float): Boolean {
        val r = radius + width * MAX_FACTOR / 2f
        if (x < bounds.left - r || x > bounds.right + r || y < bounds.top - r || y > bounds.bottom + r) return false
        if (points.size == 1) return hypot(x - points[0].x, y - points[0].y) <= r
        for (i in 1 until points.size) {
            if (distToSegment(x, y, points[i - 1], points[i]) <= r) return true
        }
        return false
    }

    private fun distToSegment(px: Float, py: Float, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - a.x, py - a.y)
        val t = (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }
}

class Drawing {
    val strokes = mutableListOf<Stroke>()

    fun isEmpty() = strokes.isEmpty()

    fun bounds(): RectF? {
        if (strokes.isEmpty()) return null
        val r = RectF(strokes[0].bounds)
        for (s in strokes) r.union(s.bounds)
        return r
    }

    /** Mueve todo el dibujo para que su esquina superior izquierda quede en (x, y). */
    fun moveTo(x: Float, y: Float) {
        val b = bounds() ?: return
        val dx = x - b.left
        val dy = y - b.top
        val moved = strokes.map { it.translated(dx, dy) }
        strokes.clear()
        strokes.addAll(moved)
    }

    /**
     * Agrupa los trazos en renglones usando las líneas guía del lienzo
     * (cada trazo va al renglón donde cae su centro) y los ordena por orden de escritura.
     */
    fun lines(lineSpacing: Float): List<List<Stroke>> =
        strokes.groupBy { floor(it.bounds.centerY() / lineSpacing).toInt() }
            .toSortedMap()
            .values
            .map { line -> line.sortedBy { it.startTime } }

    fun toJson(canvasWidth: Int, canvasHeight: Int, density: Float): String {
        val arr = JSONArray()
        for (s in strokes) {
            val pts = JSONArray()
            val t0 = s.points.first().t
            for (p in s.points) {
                pts.put(JSONArray().put(r1(p.x)).put(r1(p.y)).put(r2(p.pressure)).put(p.t - t0))
            }
            arr.put(JSONObject().put("w", r1(s.width)).put("t", t0).put("p", pts))
        }
        return JSONObject()
            .put("app", "siyuan-ink")
            .put("v", 2)
            .put("canvasW", canvasWidth)
            .put("canvasH", canvasHeight)
            .put("density", r2(density))
            .put("strokes", arr)
            .toString()
    }

    /**
     * SVG recortado al contenido. Las dimensiones se dividen entre la densidad de la pantalla
     * para que en SiYuan se vea al mismo tamaño físico que se escribió.
     */
    fun toSvg(density: Float, margin: Float): String {
        val b = bounds() ?: RectF(0f, 0f, 1f, 1f)
        val x0 = b.left - margin
        val y0 = b.top - margin
        val w = b.width() + 2 * margin
        val h = b.height() + 2 * margin
        val d = if (density > 0f) density else 1f
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(f(w / d))
            .append("\" height=\"").append(f(h / d))
            .append("\" viewBox=\"").append(f(x0)).append(' ').append(f(y0)).append(' ')
            .append(f(w)).append(' ').append(f(h)).append("\">")
        sb.append("<g fill=\"#000000\" stroke=\"none\">")
        for (s in strokes) {
            sb.append("<path d=\"").append(s.svgPathData()).append("\"/>")
        }
        sb.append("</g></svg>")
        return sb.toString()
    }

    companion object {
        fun fromJson(json: String): Drawing {
            val d = Drawing()
            val o = JSONObject(json)
            // La versión 1 guardaba la presión sin normalizar: se dibuja con grosor constante.
            val hasPressure = o.optInt("v", 1) >= 2
            val arr = o.getJSONArray("strokes")
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val t0 = so.optLong("t", 0L)
                val pts = so.getJSONArray("p")
                val list = ArrayList<InkPoint>(pts.length())
                for (j in 0 until pts.length()) {
                    val p = pts.getJSONArray(j)
                    list.add(
                        InkPoint(
                            p.getDouble(0).toFloat(), p.getDouble(1).toFloat(),
                            if (hasPressure) p.optDouble(2, 0.5).toFloat() else 0.5f, t0 + p.optLong(3, 0L)
                        )
                    )
                }
                if (list.isNotEmpty()) d.strokes.add(Stroke(so.getDouble("w").toFloat(), list))
            }
            return d
        }
    }
}

private fun f(v: Float): String {
    val s = String.format(Locale.US, "%.1f", v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

private fun r1(v: Float): Double = Math.round(v * 10.0) / 10.0
private fun r2(v: Float): Double = Math.round(v * 100.0) / 100.0
