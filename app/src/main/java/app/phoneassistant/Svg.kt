package app.phoneassistant

/** Allow-list sanitizer for SVG written by the model: no scripts, no external loads, no event handlers. */
object Svg {
    private val ELEMENTS = setOf("svg", "g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon", "text", "tspan", "defs", "lineargradient", "radialgradient", "stop", "marker", "title", "desc", "use", "clippath", "mask", "symbol")
    private val TEXT_ELEMENTS = setOf("text", "tspan", "title", "desc")
    private val ATTRS = setOf("viewbox", "width", "height", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset", "stroke-opacity", "fill-opacity", "opacity", "fill-rule", "clip-rule", "font-size", "font-family", "font-weight", "font-style", "text-anchor", "dominant-baseline", "alignment-baseline", "letter-spacing", "text-decoration", "id", "offset", "stop-color", "stop-opacity", "gradientunits", "gradienttransform", "markerwidth", "markerheight", "refx", "refy", "orient", "markerunits", "marker-end", "marker-start", "marker-mid", "clip-path", "mask", "xmlns", "preserveaspectratio", "dx", "dy", "class", "href", "xlink:href", "patterntransform", "visibility", "display", "vector-effect")
    private val BAD = Regex("javascript:|data:|vbscript:|expression\\(|@import|<|&#", RegexOption.IGNORE_CASE)
    private val LOCAL_URL = Regex("^url\\(\\s*['\"]?#[\\w-]+['\"]?\\s*\\)$", RegexOption.IGNORE_CASE)
    private val ATTR_RE = Regex("([a-zA-Z_:][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    private fun safe(name: String, v: String): Boolean {
        val s = v.trim()
        if (BAD.containsMatchIn(s)) return false
        if (name == "href" || name == "xlink:href") return Regex("^#[\\w-]+$").matches(s)
        if (s.contains("url(", ignoreCase = true)) return LOCAL_URL.matches(s)
        return true
    }

    private fun esc(t: String) = t.replace(Regex("&(?!(amp|lt|gt|quot|apos|#\\d+);)"), "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun tagEnd(s: String, from: Int): Int {
        var q = '\u0000'
        for (k in from + 1 until s.length) { val c = s[k]; if (q != '\u0000') { if (c == q) q = '\u0000' } else if (c == '"' || c == '\'') q = c else if (c == '>') return k }
        return -1
    }

    fun sanitize(src: String, maxLen: Int = 60000): String {
        var s = src.trim().take(maxLen); if (s.isEmpty()) return ""
        s = s.replace(Regex("<!--[\\s\\S]*?-->"), "").replace(Regex("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>"), "").replace(Regex("<\\?[\\s\\S]*?\\?>"), "").replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        val start = Regex("<svg[\\s>]", RegexOption.IGNORE_CASE).find(s)?.range?.first ?: return ""
        s = s.substring(start)
        val out = StringBuilder(); var i = 0; var textOk = 0
        class Open(val name: String, val keep: Boolean)
        val stack = ArrayList<Open>()
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            if (lt < 0) { out.append(esc(s.substring(i))); break }
            if (lt > i && stack.isNotEmpty() && textOk > 0) out.append(esc(s.substring(i, lt)))
            val gt = tagEnd(s, lt); if (gt < 0) break
            val raw = s.substring(lt + 1, gt); i = gt + 1
            val closing = raw.startsWith("/")
            val nm = Regex("^/?\\s*([a-zA-Z][\\w:-]*)").find(raw) ?: continue
            val name = nm.groupValues[1].lowercase()
            if (closing) {
                val top = stack.lastOrNull()
                if (top != null && top.name == name) { stack.removeAt(stack.size - 1); if (top.keep) out.append("</$name>"); if (name in TEXT_ELEMENTS) textOk-- }
                continue
            }
            val selfClose = raw.trimEnd().endsWith("/")
            val keep = name in ELEMENTS && stack.none { !it.keep }
            if (!keep) { if (!selfClose) stack.add(Open(name, false)); continue }
            if (stack.isEmpty() && name != "svg") continue
            val attrs = ArrayList<String>()
            for (m in ATTR_RE.findAll(raw)) {
                val an = m.groupValues[1].lowercase(); val av = if (m.groupValues[3].isNotEmpty() || m.value.contains("=\"")) m.groupValues[3] else m.groupValues[4]
                if (an.startsWith("on") || an !in ATTRS || !safe(an, av)) continue
                attrs.add("$an=\"${av.replace("\"", "&quot;")}\"")
            }
            if (name == "svg" && attrs.none { it.startsWith("xmlns=") }) attrs.add("xmlns=\"http://www.w3.org/2000/svg\"")
            out.append("<").append(name); if (attrs.isNotEmpty()) out.append(" ").append(attrs.joinToString(" ")); out.append(if (selfClose) "/>" else ">")
            if (!selfClose) { stack.add(Open(name, true)); if (name in TEXT_ELEMENTS) textOk++ }
        }
        while (stack.isNotEmpty()) { val t = stack.removeAt(stack.size - 1); if (t.keep) out.append("</${t.name}>") }
        val r = out.toString()
        return if (Regex("^<svg[\\s>]").containsMatchIn(r)) r else ""
    }
}
