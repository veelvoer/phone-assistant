package app.phoneassistant

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.Xml
import java.io.StringReader
import kotlin.math.*

/** A sanitised SVG turned into things that can be drawn stroke by stroke (shapes and labels), in viewBox coordinates. */
class SvgScene(svg: String) {
    class Shape(val path: Path, val fill: Int?, val stroke: Int?, val strokeWidth: Float, val arrow: Boolean, val length: Float) { var start = 0L; var dur = 0L }
    class Label(val text: String, val x: Float, val y: Float, val size: Float, val color: Int, val align: Paint.Align) { var start = 0L; var dur = 0L }

    val shapes = ArrayList<Any>()   // Shape or Label, in drawing order
    var vbX = 0f; var vbY = 0f; var vbW = 800f; var vbH = 500f
    var totalMs = 0L
    var error: String? = null

    private class St(var fill: Int? = Color.BLACK, var stroke: Int? = null, var sw: Float = 1f, var size: Float = 16f, var align: Paint.Align = Paint.Align.LEFT, var opacity: Float = 1f)


    private fun num(s: String?, def: Float = 0f): Float = s?.let { Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").find(it)?.value?.toFloatOrNull() } ?: def
    private fun nums(s: String?): List<Float> = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").findAll(s ?: "").mapNotNull { it.value.toFloatOrNull() }.toList()

    private val NAMED = mapOf("black" to 0xFF000000.toInt(), "white" to -1, "red" to 0xFFE53935.toInt(), "green" to 0xFF43A047.toInt(), "blue" to 0xFF1E88E5.toInt(), "yellow" to 0xFFFDD835.toInt(), "orange" to 0xFFFB8C00.toInt(), "purple" to 0xFF8E24AA.toInt(), "gray" to 0xFF9E9E9E.toInt(), "grey" to 0xFF9E9E9E.toInt(), "pink" to 0xFFEC407A.toInt(), "teal" to 0xFF00897B.toInt(), "brown" to 0xFF795548.toInt(), "cyan" to 0xFF00ACC1.toInt(), "navy" to 0xFF1A237E.toInt(), "lightgray" to 0xFFE0E0E0.toInt(), "lightgrey" to 0xFFE0E0E0.toInt(), "darkgray" to 0xFF616161.toInt(), "lime" to 0xFF7CB342.toInt(), "gold" to 0xFFFFC107.toInt(), "silver" to 0xFFBDBDBD.toInt())

    /** null = "none". Returns -2 for "unset". */
    private fun color(s: String?): Int? {
        val v = s?.trim()?.lowercase() ?: return -2
        if (v == "none" || v == "transparent") return null
        if (v == "currentcolor") return -1
        NAMED[v]?.let { return it }
        if (v.startsWith("#")) {
            val h = v.drop(1)
            return try { when (h.length) { 3 -> Color.parseColor("#" + h.map { "$it$it" }.joinToString("")); 6 -> Color.parseColor("#$h"); 8 -> Color.parseColor("#$h"); else -> -2 } } catch (e: Exception) { -2 }
        }
        if (v.startsWith("rgb")) { val n = nums(v); if (n.size >= 3) return Color.rgb(n[0].toInt().coerceIn(0, 255), n[1].toInt().coerceIn(0, 255), n[2].toInt().coerceIn(0, 255)) }
        if (v.startsWith("url(")) return -2
        return -2
    }

    /** Colours are chosen for light backgrounds by the model; the drawing sits on a dimmed screen, so dark colours are lifted. */
    private fun adapt(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        return if (hsv[1] < 0.18f) { if (hsv[2] < 0.75f) Color.WHITE else c }   // grays and blacks become white
        else { hsv[1] = (hsv[1] * 0.85f).coerceIn(0f, 1f); hsv[2] = max(hsv[2], 0.92f); Color.HSVToColor(Color.alpha(c), hsv) }
    }

    private fun transform(s: String?): Matrix {
        val m = Matrix(); if (s.isNullOrBlank()) return m
        for (t in Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(s)) {
            val a = nums(t.groupValues[2]); val n = Matrix()
            when (t.groupValues[1]) {
                "translate" -> n.setTranslate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                "scale" -> n.setScale(a.getOrElse(0) { 1f }, a.getOrElse(1) { a.getOrElse(0) { 1f } })
                "rotate" -> n.setRotate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f }, a.getOrElse(2) { 0f })
                "matrix" -> if (a.size >= 6) n.setValues(floatArrayOf(a[0], a[2], a[4], a[1], a[3], a[5], 0f, 0f, 1f))
            }
            m.preConcat(n)
        }
        return m
    }

    private fun parse(svg: String) {
        val p = Xml.newPullParser(); p.setInput(StringReader(svg))
        val styles = ArrayList<St>(); val mats = ArrayList<Matrix>(); styles.add(St()); mats.add(Matrix())
        var textBuf = StringBuilder(); var tx = 0f; var ty = 0f; var inText = 0; var textStyle: St? = null; var textMat: Matrix? = null
        val defs = HashMap<String, Boolean>()
        fun flushText() {
            val t = textBuf.toString().trim().replace(Regex("\\s+"), " ")
            if (t.isNotEmpty() && textStyle != null && textMat != null) {
                val pt = floatArrayOf(tx, ty); textMat!!.mapPoints(pt); val sc = textMat!!.mapRadius(1f)
                shapes.add(Label(t, pt[0], pt[1], textStyle!!.size * sc, adapt(textStyle!!.fill ?: Color.WHITE), textStyle!!.align))
            }
            textBuf = StringBuilder()
        }
        var depthDefs = 0
        var ev = p.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    val name = p.name.lowercase()
                    fun a(n: String) = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).lowercase() == n }?.let { p.getAttributeValue(it) }
                    val st = St(styles.last().fill, styles.last().stroke, styles.last().sw, styles.last().size, styles.last().align, styles.last().opacity)
                    color(a("fill"))?.let { if (it != -2) st.fill = if (it == -1) Color.WHITE else it } ?: run { if (a("fill") != null) st.fill = null }
                    color(a("stroke"))?.let { if (it != -2) st.stroke = if (it == -1) Color.WHITE else it } ?: run { if (a("stroke") != null) st.stroke = null }
                    a("stroke-width")?.let { st.sw = num(it, st.sw) }
                    a("font-size")?.let { st.size = num(it, st.size) }
                    a("text-anchor")?.let { st.align = when (it) { "middle" -> Paint.Align.CENTER; "end" -> Paint.Align.RIGHT; else -> Paint.Align.LEFT } }
                    val m = Matrix(mats.last()); m.preConcat(transform(a("transform")))
                    when (name) {
                        "svg" -> if (styles.size == 1) { val vb = nums(a("viewbox")); if (vb.size == 4 && vb[2] > 0 && vb[3] > 0) { vbX = vb[0]; vbY = vb[1]; vbW = vb[2]; vbH = vb[3] } else { val w = num(a("width"), 800f); val h = num(a("height"), 500f); vbW = w; vbH = h } }
                        "defs", "marker", "lineargradient", "radialgradient", "clippath", "mask", "symbol" -> depthDefs++
                    }
                    if (name == "text") { inText++; textBuf = StringBuilder(); tx = num(a("x")); ty = num(a("y")); textStyle = st; textMat = m }
                    if (name == "tspan" && inText > 0) { if (a("x") != null || a("y") != null || a("dy") != null) { flushText(); if (a("x") != null) tx = num(a("x")); if (a("y") != null) ty = num(a("y")); ty += num(a("dy")); textStyle = st; textMat = m } }
                    if (depthDefs == 0 && inText == 0) {
                        val path = Path(); var ok = true; var arrowCap = false
                        when (name) {
                            "rect" -> { val x = num(a("x")); val y = num(a("y")); val w = num(a("width")); val h = num(a("height")); val rx = num(a("rx")).coerceAtLeast(num(a("ry"))); if (rx > 0) path.addRoundRect(RectF(x, y, x + w, y + h), rx, rx, Path.Direction.CW) else path.addRect(x, y, x + w, y + h, Path.Direction.CW) }
                            "circle" -> path.addCircle(num(a("cx")), num(a("cy")), num(a("r")), Path.Direction.CW)
                            "ellipse" -> path.addOval(RectF(num(a("cx")) - num(a("rx")), num(a("cy")) - num(a("ry")), num(a("cx")) + num(a("rx")), num(a("cy")) + num(a("ry"))), Path.Direction.CW)
                            "line" -> { path.moveTo(num(a("x1")), num(a("y1"))); path.lineTo(num(a("x2")), num(a("y2"))); arrowCap = true }
                            "polyline", "polygon" -> { val n = nums(a("points")); if (n.size >= 4) { path.moveTo(n[0], n[1]); var i = 2; while (i + 1 < n.size) { path.lineTo(n[i], n[i + 1]); i += 2 }; if (name == "polygon") path.close() else arrowCap = true } else ok = false }
                            "path" -> { PathData.parse(a("d") ?: "", path); arrowCap = true }
                            else -> ok = false
                        }
                        if (ok && !path.isEmpty) {
                            path.transform(m)
                            val len = PathMeasure(path, false).let { pm -> var l = pm.length; while (pm.nextContour()) l += pm.length; l }
                            val sw = st.sw * m.mapRadius(1f)
                            val arrow = arrowCap && (a("marker-end") != null)
                            val stroke = st.stroke?.let { adapt(it) }; val fill = st.fill?.let { adapt(it) }
                            if (stroke != null || fill != null) shapes.add(Shape(path, fill, stroke, max(sw, 1f), arrow, max(len, 1f)))
                        }
                    }
                    styles.add(st); mats.add(m)
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inText > 0 && depthDefs == 0) textBuf.append(p.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    val name = p.name.lowercase()
                    if (name == "text") { flushText(); inText-- }
                    if (name in setOf("defs", "marker", "lineargradient", "radialgradient", "clippath", "mask", "symbol")) depthDefs--
                    if (styles.size > 1) { styles.removeAt(styles.size - 1); mats.removeAt(mats.size - 1) }
                }
            }
            ev = p.next()
        }
    }

    /** Timeline: strokes are drawn one after another; labels are typed. */
    private fun schedule() {
        var t = 200L
        for (s in shapes) {
            when (s) {
                is Shape -> { s.start = t; s.dur = (s.length / 1.1f).toLong().coerceIn(260L, 1500L); t += s.dur + (if (s.fill != null) 180L else 0L) + 50L }
                is Label -> { s.start = t; s.dur = (s.text.length * 38L).coerceIn(300L, 1600L); t += s.dur + 60L }
            }
        }
        totalMs = t + 300L
    }

    /** Stretch the timeline so the drawing lasts about as long as the narration. */
    fun stretch(targetMs: Long) {
        if (totalMs <= 0) return
        val k = (targetMs.toFloat() / totalMs).coerceIn(0.7f, 4f)
        for (s in shapes) when (s) { is Shape -> { s.start = (s.start * k).toLong(); s.dur = (s.dur * k).toLong() }; is Label -> { s.start = (s.start * k).toLong(); s.dur = (s.dur * k).toLong() } }
        totalMs = (totalMs * k).toLong()
    }

    // must stay below the property initialisers above (NAMED…): Kotlin runs them in textual order
    init { try { parse(svg) } catch (e: Exception) { error = e.toString() + " @ " + e.stackTrace.take(3).joinToString { it.toString() } } ; schedule() }
}

/** SVG path data ("M10 10 L20 20 C…") into an android Path. */
object PathData {
    fun parse(d: String, path: Path) {
        val tok = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'; var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f; var lcx = 0f; var lcy = 0f; var prev = ' '
        fun nx(): Float = tok.getOrNull(i++)?.toFloatOrNull() ?: 0f
        while (i < tok.size) {
            val t = tok[i]
            if (t.length == 1 && t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z' || cmd == 'z') { path.close(); cx = sx; cy = sy; prev = 'Z'; continue } }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> { var x = nx(); var y = nx(); if (rel) { x += cx; y += cy }; path.moveTo(x, y); cx = x; cy = y; sx = x; sy = y; cmd = if (rel) 'l' else 'L'; prev = 'M' }
                'L' -> { var x = nx(); var y = nx(); if (rel) { x += cx; y += cy }; path.lineTo(x, y); cx = x; cy = y; prev = 'L' }
                'H' -> { var x = nx(); if (rel) x += cx; path.lineTo(x, cy); cx = x; prev = 'L' }
                'V' -> { var y = nx(); if (rel) y += cy; path.lineTo(cx, y); cy = y; prev = 'L' }
                'C' -> { var x1 = nx(); var y1 = nx(); var x2 = nx(); var y2 = nx(); var x = nx(); var y = nx(); if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }; path.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y; prev = 'C' }
                'S' -> { var x2 = nx(); var y2 = nx(); var x = nx(); var y = nx(); if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }; val x1 = if (prev == 'C') 2 * cx - lcx else cx; val y1 = if (prev == 'C') 2 * cy - lcy else cy; path.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y; prev = 'C' }
                'Q' -> { var x1 = nx(); var y1 = nx(); var x = nx(); var y = nx(); if (rel) { x1 += cx; y1 += cy; x += cx; y += cy }; path.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y; prev = 'Q' }
                'T' -> { var x = nx(); var y = nx(); if (rel) { x += cx; y += cy }; val x1 = if (prev == 'Q') 2 * cx - lcx else cx; val y1 = if (prev == 'Q') 2 * cy - lcy else cy; path.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y; prev = 'Q' }
                'A' -> { val rx = nx(); val ry = nx(); val rot = nx(); val large = nx() != 0f; val sweep = nx() != 0f; var x = nx(); var y = nx(); if (rel) { x += cx; y += cy }; arc(path, cx, cy, rx, ry, rot, large, sweep, x, y); cx = x; cy = y; prev = 'A' }
                else -> i++
            }
        }
    }

    private fun arc(path: Path, x1: Float, y1: Float, rxIn: Float, ryIn: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        var rx = abs(rxIn); var ry = abs(ryIn)
        if (rx == 0f || ry == 0f || (x1 == x2 && y1 == y2)) { path.lineTo(x2, y2); return }
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1) { rx *= sqrt(lam).toFloat(); ry *= sqrt(lam).toFloat() }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = sqrt(max(0.0, num / den)); if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry; val cyp = -co * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double { val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val n = max(2, ceil(abs(dth) / (PI / 12)).toInt())
        for (k in 1..n) {
            val a = th1 + dth * k / n
            val px = cp * rx * cos(a) - sp * ry * sin(a) + cx; val py = sp * rx * cos(a) + cp * ry * sin(a) + cy
            path.lineTo(px.toFloat(), py.toFloat())
        }
    }
}
