package app.phoneassistant

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Small view toolkit: Material-3 look from the system (Material You) colors. */
class Ui(val ctx: Context) {
    val c = Roles(ctx)
    private val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    fun dpf(v: Float) = v * d

    fun shape(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(r.toFloat()); if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(base: Int, content: android.graphics.drawable.Drawable) =
        RippleDrawable(ColorStateList.valueOf((c.onSurface and 0x00FFFFFF) or 0x22000000), content, null)

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom); leftMargin = dp(left); rightMargin = dp(right) }

    fun tv(t: CharSequence, size: Float = 15f, color: Int = c.onSurface, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    }

    fun icon(res: Int, tint: Int, size: Int = 24) = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Round icon button. */
    fun iconButton(res: Int, bg: Int, tint: Int, size: Int = 44, onClick: () -> Unit) = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        background = ripple(bg, shape(bg, size / 2))
        addView(icon(res, tint, 22).apply { layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER) })
        isClickable = true; contentDescription = "button"; setOnClickListener { onClick() }
    }

    /** Pill button: filled (primary) or tonal. */
    fun button(label: String, iconRes: Int = 0, filled: Boolean = false, bg: Int? = null, fg: Int? = null, onClick: () -> Unit): LinearLayout {
        val b = bg ?: if (filled) c.primary else c.surface2
        val f = fg ?: if (filled) c.onPrimary else c.onSurface
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; minimumHeight = dp(52)
            setPadding(dp(22), dp(12), dp(22), dp(12)); background = ripple(b, shape(b, 26)); isClickable = true
            if (iconRes != 0) addView(icon(iconRes, f, 20).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
            addView(tv(label, 15f, f, true).also { it.tag = "label" })
            setOnClickListener { onClick() }
        }
    }

    fun setLabel(b: LinearLayout, text: String) { (b.findViewWithTag<TextView>("label"))?.text = text }

    fun chip(label: String, onClick: () -> Unit) = tv(label, 13f, c.onSurfaceV).apply {
        background = ripple(0, shape(0x00000000, 16, c.outline)); setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }; setOnClickListener { onClick() }
    }

    fun card(vararg v: View, bg: Int = c.surface, pad: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; background = shape(bg, 28); setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = lp(top = 14); v.forEach { addView(it) }
    }

    fun field(hint: String, multi: Boolean = false, password: Boolean = false) = EditText(ctx).apply {
        this.hint = hint; textSize = 15f; setTextColor(c.onSurface); setHintTextColor(c.onSurfaceV)
        background = shape(c.bg, 16, c.outline); setPadding(dp(16), dp(14), dp(16), dp(14))
        if (multi) { minLines = 3; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        else { setSingleLine(); inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT }
        layoutParams = lp(top = 10)
    }

    /** Title + subtitle row with a Switch. */
    fun toggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(top = 12)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, weight = 1f)
            addView(tv(title, 15f, c.onSurface, true)); if (sub.isNotEmpty()) addView(tv(sub, 12f, c.onSurfaceV).apply { layoutParams = lp(top = 2) })
        })
        addView(Switch(ctx).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) } })
    }

    /** Segmented selector. */
    fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply { layoutParams = lp(top = 10); background = shape(c.bg, 22, c.outline); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val views = options.mapIndexed { i, o ->
            tv(o, 13f, c.onSurface, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); layoutParams = lp(0, -2, weight = 1f); isClickable = true }
        }
        fun paint(sel: Int) = views.forEachIndexed { i, v -> v.background = if (i == sel) shape(c.primaryC, 19) else null; v.setTextColor(if (i == sel) c.onPrimaryC else c.onSurfaceV) }
        views.forEachIndexed { i, v -> v.setOnClickListener { paint(i); onPick(i) }; row.addView(v) }
        paint(selected); return row
    }

    fun sectionTitle(t: String) = tv(t.uppercase(), 12f, c.primary, true).apply { letterSpacing = 0.08f; layoutParams = lp(top = 26, bottom = 2, left = 6) }

    fun statusBar(a: Activity) {
        a.window.statusBarColor = c.bg; a.window.navigationBarColor = c.bg
        var f = a.window.decorView.systemUiVisibility
        if (!Pal(ctx).night) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        a.window.decorView.systemUiVisibility = f
    }

    fun page(content: View): ScrollView = ScrollView(ctx).apply { setBackgroundColor(c.bg); isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(content) }

    fun divider() = View(ctx).apply { setBackgroundColor((c.outline and 0x00FFFFFF) or 0x33000000); layoutParams = lp(-1, 1) }

    /** Category row: round icon, title, subtitle, chevron. */
    fun navRow(iconRes: Int, title: String, sub: String, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12)); isClickable = true; background = ripple(0, shape(0x00000000, 22))
        addView(FrameLayout(ctx).apply { background = shape(c.primaryC, 20); layoutParams = lp(dp(40), dp(40), right = 14); addView(icon(iconRes, c.onPrimaryC, 22).apply { layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER) }) })
        addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, weight = 1f); addView(tv(title, 16f, c.onSurface, true)); if (sub.isNotEmpty()) addView(tv(sub, 12f, c.onSurfaceV).apply { layoutParams = lp(top = 2) }) })
        addView(icon(R.drawable.ic_chevron, c.onSurfaceV, 22))
        setOnClickListener { onClick() }
    }

    fun slider(title: String, sub: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
        val label = tv(fmt(value), 13f, c.onSurfaceV)
        val bar = SeekBar(ctx).apply {
            this.max = max - min; progress = value - min
            progressTintList = ColorStateList.valueOf(c.primary); thumbTintList = ColorStateList.valueOf(c.primary); progressBackgroundTintList = ColorStateList.valueOf(c.outline)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { label.text = fmt(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onChange(progress + min) }
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp(top = 14)
            addView(LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; addView(tv(title, 15f, c.onSurface, true).apply { layoutParams = lp(0, -2, weight = 1f) }); addView(label) })
            if (sub.isNotEmpty()) addView(tv(sub, 12f, c.onSurfaceV).apply { layoutParams = lp(top = 2) })
            addView(bar, lp(top = 4))
        }
    }

    /** colors[0] = 0 means "system colours". */
    fun swatches(selected: Int, colors: List<Int>, onPick: (Int) -> Unit): HorizontalScrollView {
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val views = ArrayList<Pair<Int, View>>()
        fun paint(sel: Int) = views.forEach { (col, v) -> v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (col == 0) c.primary else col); setStroke(dp(if (col == sel) 3 else 1), if (col == sel) c.onSurface else c.outline) } }
        for (col in colors) {
            val v = FrameLayout(ctx).apply { layoutParams = lp(dp(38), dp(38), right = 10); isClickable = true
                if (col == 0) addView(tv("A", 14f, c.onPrimary, true).apply { gravity = Gravity.CENTER; layoutParams = FrameLayout.LayoutParams(-1, -1) }) }
            views.add(col to v); row.addView(v); v.setOnClickListener { paint(col); onPick(col) }
        }
        paint(selected)
        return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row); layoutParams = lp(top = 10) }
    }
}

