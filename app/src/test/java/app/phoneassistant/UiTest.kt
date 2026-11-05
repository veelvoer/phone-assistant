package app.phoneassistant

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)

    @Test fun mainScreenBuilds() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(a.window.decorView)
    }

    @Test fun everySettingsPageBuilds() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val open = SettingsActivity::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        for (id in listOf("home", "ai", "voice", "shortcut", "look", "me", "calls", "memory", "budget", "about")) { open.invoke(a, id) }
        // pages with extra state
        prefs().edit().putBoolean("call_on", true).putBoolean("hearth", true).putBoolean("ov_glow", false).putBoolean("ov_card", false).putBoolean("q_on", true).commit()
        for (id in listOf("ai", "look", "calls", "me")) open.invoke(a, id)
    }

    @Test fun overlayBuildsInEveryStyle() {
        for (preset in listOf("classic", "minimal", "neon", "calm", "glass", "contrast", "bottom")) {
            OverlayStyle.applyPreset(ctx, preset)
            val o = Overlay(ctx) {}
            o.update(Overlay.State.LISTENING, "Listening", "Say what I should do…", null, 0f)
            o.update(Overlay.State.WORKING, "Working", "Tapping “Berichten”", "2/4 · open app", 0.5f)
            o.level(3f); o.touchable(false); o.touchable(true)
            o.rebuild(); o.update(Overlay.State.ASKING, "Question", "Welke Jan bedoel je?", "Say your answer…", 0f)
            o.hide()
        }
        prefs().edit().putInt("ov_accent", 0xFF22AA88.toInt()).putInt("ov_accent2", 0xFFAA2288.toInt()).putInt("ov_gm", 3).putInt("ov_ws", 2).putInt("ov_anim", 2).putBoolean("ov_pos_top", true).putString("ov_pos", "top").commit()
        val o = Overlay(ctx) {}; o.update(Overlay.State.INFO, "Done", "Klaar", null, 0f); o.hide()
        OverlayStyle.reset(ctx)
    }

    @Test fun drawOverlayBuilds() {
        val svg = Svg.sanitize("<svg viewBox=\"0 0 800 500\"><circle cx=\"200\" cy=\"200\" r=\"80\" stroke=\"#fff\" fill=\"none\"/><line x1=\"280\" y1=\"200\" x2=\"500\" y2=\"200\" stroke=\"#fff\" marker-end=\"url(#a)\"/><text x=\"520\" y=\"210\" font-size=\"28\">Server</text></svg>")
        val d = DrawOverlay(ctx)
        d.show("Test", listOf(DrawOverlay.Frame(svg, "Stap een"), DrawOverlay.Frame(svg, "Stap twee")))
        d.hide()
        val d2 = DrawOverlay(ctx); d2.show("", listOf(DrawOverlay.Frame(svg, ""))); d2.hide()
    }

    @Test fun callModesPersist() {
        CallManager.setMode(ctx, CallManager.PHONE, "text"); CallManager.setMode(ctx, "com.whatsapp", "speaker")
        val m = CallManager.modes(ctx); assertEquals("text", m[CallManager.PHONE]); assertEquals("speaker", m["com.whatsapp"])
        assertFalse(CallManager.sessionActive()); assertTrue(CallManager.diagnostics(ctx).contains("Phone state"))
        CallManager.event("test"); assertTrue(CallManager.recentEvents().first().endsWith("test"))
    }

    @Test fun errorsAreExplained() {
        assertTrue(Errors.explain("API 401: invalid x-api-key", true).contains("API-sleutel")); assertTrue(Errors.explain("NETWORK: UnknownHost", false).contains("internet"))
    }
}
