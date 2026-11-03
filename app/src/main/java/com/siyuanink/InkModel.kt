package com.siyuanink

import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor
import kotlin.math.hypot

class InkPoint(val x: Float, val y: Float, val pressure: Float, val t: Long)

class Stroke(val width: Float, val points: List<InkPoint>) {

    val bounds: RectF = RectF(points[0].x, points[0].y, points[0].x, points[0].y).also { r ->
        for (p in points) r.union(p.x, p.y)
        r.inset(-width / 2f, -width / 2f)
    }

    val startTime: Long get() = points.first().t

    fun translated(dx: Float, dy: Float) =
        Stroke(width, points.map { InkPoint(it.x + dx, it.y + dy, it.pressure, it.t) })

    /** Trazo suavizado con curvas cuadráticas entre puntos medios (igual en pantalla y en SVG). */
    fun toPath(): Path {
        val path = Path()
        val p0 = points[0]
        path.moveTo(p0.x, p0.y)
        if (points.size == 1) {
            path.lineTo(p0.x + 0.1f, p0.y)
            return path
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            path.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        }
        val last = points.last()
        path.lineTo(last.x, last.y)
        return path
    }

    fun svgPathData(): String {
        val sb = StringBuilder()
        val p0 = points[0]
        sb.append("M").append(f(p0.x)).append(' ').append(f(p0.y))
        if (points.size == 1) {
            sb.append(" l0.1 0")
            return sb.toString()
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            sb.append(" Q").append(f(a.x)).append(' ').append(f(a.y)).append(' ')
                .append(f((a.x + b.x) / 2f)).append(' ').append(f((a.y + b.y) / 2f))
        }
        val last = points.last()
        sb.append(" L").append(f(last.x)).append(' ').append(f(last.y))
        return sb.toString()
    }

    /** ¿Pasa la goma (punto x,y con radio) por encima de este trazo? */
    fun isNear(x: Float, y: Float, radius: Float): Boolean {
        val r = radius + width / 2f
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
            .put("v", 1)
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
        sb.append("<g fill=\"none\" stroke=\"#000000\" stroke-linecap=\"round\" stroke-linejoin=\"round\">")
        for (s in strokes) {
            sb.append("<path stroke-width=\"").append(f(s.width)).append("\" d=\"")
                .append(s.svgPathData()).append("\"/>")
        }
        sb.append("</g></svg>")
        return sb.toString()
    }

    companion object {
        fun fromJson(json: String): Drawing {
            val d = Drawing()
            val o = JSONObject(json)
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
                            p.optDouble(2, 0.5).toFloat(), t0 + p.optLong(3, 0L)
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
