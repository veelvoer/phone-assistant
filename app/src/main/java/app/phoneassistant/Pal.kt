package app.phoneassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue

/** Colors taken from the system (Material You / wallpaper colors on Android 12+, theme accent below that). */
@SuppressLint("NewApi")
class Pal(private val c: Context) {
    val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val base: Int = TypedValue().let { tv -> if (c.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) tv.data else 0xFF6750A4.toInt() }
    private fun sys(res: Int, fb: Int) = if (Build.VERSION.SDK_INT >= 31) c.getColor(res) else fb
    private fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    val a200 = sys(android.R.color.system_accent1_200, base)
    val a300 = sys(android.R.color.system_accent1_300, base)
    val a400 = sys(android.R.color.system_accent1_400, base)
    val a500 = sys(android.R.color.system_accent1_500, base)
    val a600 = sys(android.R.color.system_accent1_600, base)
    val accent = if (night) a200 else a600

    val bg = a(sys(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50, if (night) 0xFF1E1F23.toInt() else 0xFFFFFFFF.toInt()), 0xEE)
    val text = sys(if (night) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900, if (night) 0xFFF1F3F4.toInt() else 0xFF1F1F1F.toInt())
    val muted = sys(if (night) android.R.color.system_neutral2_300 else android.R.color.system_neutral2_600, if (night) 0xFFB0B4B8.toInt() else 0xFF5F6368.toInt())
    val chip = a(text, 0x22)
    val stroke = a(text, 0x30)
    val container = sys(if (night) android.R.color.system_accent1_700 else android.R.color.system_accent1_100, base)
    val onContainer = sys(if (night) android.R.color.system_accent1_50 else android.R.color.system_accent1_900, text)
}

/** Material-3 style roles built from the system (Material You) palette, light or dark. */
@SuppressLint("NewApi")
class Roles(private val c: Context) {
    private val p = Pal(c)
    private val night = p.night
    private fun sys(res: Int, fb: Int) = if (Build.VERSION.SDK_INT >= 31) c.getColor(res) else fb

    private fun a(n: Int, fb: Int) = sys(c.resources.getIdentifier("system_accent1_$n", "color", "android"), fb)
    private fun n1(n: Int, fb: Int) = sys(c.resources.getIdentifier("system_neutral1_$n", "color", "android"), fb)
    private fun n2(n: Int, fb: Int) = sys(c.resources.getIdentifier("system_neutral2_$n", "color", "android"), fb)

    val bg = if (night) n1(900, 0xFF1B1B1F.toInt()) else n1(10, 0xFFFDFBFF.toInt())
    val surface = if (night) n1(800, 0xFF2A2A2F.toInt()) else n1(50, 0xFFF0F0F4.toInt())
    val surface2 = if (night) n1(700, 0xFF3A3A40.toInt()) else n1(100, 0xFFE4E2E6.toInt())
    val onSurface = if (night) n1(100, 0xFFE3E2E6.toInt()) else n1(900, 0xFF1B1B1F.toInt())
    val onSurfaceV = if (night) n2(200, 0xFFC4C6D0.toInt()) else n2(700, 0xFF44474F.toInt())
    val outline = if (night) n2(500, 0xFF8E9099.toInt()) else n2(400, 0xFF74777F.toInt())
    val primary = if (night) a(200, 0xFFAEC6FF.toInt()) else a(600, 0xFF415F91.toInt())
    val onPrimary = if (night) a(800, 0xFF002F65.toInt()) else a(0, 0xFFFFFFFF.toInt())
    val primaryC = if (night) a(700, 0xFF264777.toInt()) else a(100, 0xFFD6E3FF.toInt())
    val onPrimaryC = if (night) a(100, 0xFFD6E3FF.toInt()) else a(900, 0xFF001B3E.toInt())
    val errorC = if (night) 0xFF8C1D18.toInt() else 0xFFF9DEDC.toInt()
    val onErrorC = if (night) 0xFFF9DEDC.toInt() else 0xFF410E0B.toInt()
    val error = if (night) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
    val ok = if (night) 0xFF8BD18B.toInt() else 0xFF2E7D32.toInt()
}
