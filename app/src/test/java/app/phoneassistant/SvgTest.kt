package app.phoneassistant

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SvgSanitizerTest {
    @Test fun removesScriptsHandlersAndExternalLoads() {
        val out = Svg.sanitize("<svg viewBox=\"0 0 10 10\" onload=\"x()\"><script>alert(1)</script><rect x=\"1\" y=\"1\" width=\"5\" height=\"5\" fill=\"#f00\" onclick=\"a()\"/><image href=\"http://e.com/a.png\"/><circle r=\"2\" fill=\"url(http://evil)\"/><foreignObject><div>hack</div></foreignObject><text x=\"1\" y=\"9\">Hi &amp; <b>bye</b></text></svg>")
        assertTrue(out.startsWith("<svg")); assertTrue(out.contains("<rect")); assertTrue(out.contains("Hi &amp; bye"))
        assertFalse(out.contains("script")); assertFalse(out.contains("onclick")); assertFalse(out.contains("onload")); assertFalse(out.contains("e.com")); assertFalse(out.contains("evil")); assertFalse(out.contains("foreignobject")); assertFalse(out.contains("hack"))
    }
    @Test fun keepsLocalReferences() {
        val out = Svg.sanitize("<svg viewBox=\"0 0 10 10\"><defs><marker id=\"a\"><path d=\"M0 0\"/></marker></defs><line x1=\"0\" y1=\"0\" x2=\"5\" y2=\"5\" marker-end=\"url(#a)\" stroke=\"#000\"/></svg>")
        assertTrue(out.contains("marker-end=\"url(#a)\""))
    }
    @Test fun rejectsNonSvg() { assertEquals("", Svg.sanitize("<html><body>hi</body></html>")); assertEquals("", Svg.sanitize("")) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SvgSceneTest {
    @Test fun parsesShapesLabelsAndViewBox() {
        val svg = Svg.sanitize("""<svg viewBox="0 0 800 500">
            <rect x="10" y="10" width="100" height="60" fill="#4f6bed" stroke="#fff"/>
            <circle cx="300" cy="100" r="40" fill="none" stroke="red" stroke-width="3"/>
            <line x1="110" y1="40" x2="260" y2="100" stroke="#fff" marker-end="url(#a)"/>
            <path d="M 400 100 C 450 20 520 20 570 100 S 650 180 700 100 A 30 30 0 0 1 740 140 Z" fill="none" stroke="#0f0"/>
            <g transform="translate(50,200)"><polygon points="0,0 60,0 30,50" fill="gold"/><text x="10" y="90" font-size="24">Hallo wereld</text></g>
            <text x="400" y="460" text-anchor="middle" fill="#fff">Titel</text></svg>""")
        val sc = SvgScene(svg)
        assertEquals(800f, sc.vbW, 0.01f); assertEquals(500f, sc.vbH, 0.01f)
        val shapes = sc.shapes.filterIsInstance<SvgScene.Shape>(); val labels = sc.shapes.filterIsInstance<SvgScene.Label>()
        assertEquals("error=${sc.error} shapes=${shapes.size} labels=${labels.map { it.text }} svg=$svg", 5, shapes.size); assertEquals("labels", listOf("Hallo wereld", "Titel"), labels.map { it.text })
        assertTrue("arrow marker detected", shapes.any { it.arrow })
        assertTrue("timeline is sequential", sc.shapes.zipWithNext().all { (a, b) -> start(b) >= start(a) }); assertTrue(sc.totalMs > 1000)
        // dark colours are lifted for the dimmed screen, bright ones kept
        assertTrue(labels.all { it.color != 0xFF000000.toInt() })
    }
    private fun start(x: Any) = if (x is SvgScene.Shape) x.start else (x as SvgScene.Label).start
    @Test fun survivesGarbage() { val sc = SvgScene("<svg><path d=\"M\"/><rect/><text></text></svg>"); assertTrue(sc.totalMs >= 0) }
}
