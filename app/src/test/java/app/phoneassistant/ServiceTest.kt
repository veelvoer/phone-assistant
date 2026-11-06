package app.phoneassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServiceTest {
    private fun service(): AgentService { val s = Robolectric.buildService(AgentService::class.java).create().get(); s.onServiceConnected(); shadowOf(android.os.Looper.getMainLooper()).idle(); return s }

    @Test fun serviceConnectsAndBuildsOverlays() {
        val s = service()
        s.previewOverlay(); shadowOf(android.os.Looper.getMainLooper()).idle()
        s.testVoice(); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(AgentService.instance)
    }

    @Test fun drawToolAcceptsSvgAndRejectsGarbage() {
        val s = service(); s.narrate = false
        val m = AgentService::class.java.getDeclaredMethod("drawBoard", JSONObject::class.java).apply { isAccessible = true }
        val ok = JSONObject().put("title", "DNS").put("frames", JSONArray().put(JSONObject().put("svg", "<svg viewBox=\"0 0 800 500\"><rect x=\"10\" y=\"10\" width=\"100\" height=\"50\" stroke=\"#fff\" fill=\"none\"/></svg>").put("caption", "Stap 1")))
        assertTrue((m.invoke(s, ok) as String).startsWith("drew"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val bad = JSONObject().put("title", "x").put("frames", JSONArray().put(JSONObject().put("svg", "nope")))
        assertTrue((m.invoke(s, bad) as String).startsWith("error"))
    }

    @Test fun personaAndQuietHours() {
        val s = service(); val p = RuntimeEnvironment.getApplication().getSharedPreferences("p", Context.MODE_PRIVATE)
        p.edit().putString("ai_name", "Nova").putString("ai_tone", "playful").putString("ai_len", "normal").putBoolean("q_on", true).putInt("q_from", 0).putInt("q_to", 24 * 60 - 1).commit()
        val persona = AgentService::class.java.getDeclaredMethod("persona").apply { isAccessible = true }.invoke(s) as String
        assertTrue(persona.contains("Nova") && persona.contains("playful") && persona.contains("2-3 sentences")); assertTrue(s.isQuiet())
    }

    @Test fun callSessionStartsAndEnds() {
        service(); shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        CallManager.testSession(RuntimeEnvironment.getApplication()); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(CallManager.sessionActive()); CallManager.endSession("test"); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(CallManager.sessionActive())
    }
}
