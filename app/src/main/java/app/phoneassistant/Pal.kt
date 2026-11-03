package app.phoneassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue

/** Colors taken from the system (Material You / wallpaper colors on Android 12+, theme accent below that). */
@SuppressLint("NewApi")
class Pal(c: Context) {
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
