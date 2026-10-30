package io.github.maxhayim.tvhub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.maxhayim.tvhub.TvHubApp
import java.text.DateFormat
import java.util.Date

/** The small view kit every screen is built from: colours, type sizes and the focus look. */
object Ui {
    const val BG = 0xFF070B14.toInt()
    const val SURFACE = 0xFF111A2C.toInt()
    const val SURFACE_2 = 0xFF1B2640.toInt()
    const val TEXT = 0xFFF5F7FB.toInt()
    const val DIM = 0xFF8E9BB3.toInt()
    /** Premium gold: focus rings, highlights, the primary button. */
    const val ACCENT = 0xFFFFB020.toInt()
    const val ACCENT_2 = 0xFFFF6A3D.toInt()
    const val GLOW = 0x55FFB020
    const val LIVE = 0xFFE5484D.toInt()
    const val ERROR = 0xFFFF7B72.toInt()
    const val OK = 0xFF5BC782.toInt()

    fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    fun View.dp(v: Number): Int = context.dp(v)

    fun animations(c: Context) = (c.applicationContext as TvHubApp).repo.prefs.animations

    fun rounded(c: Context, color: Int, radiusDp: Int = 12, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = c.dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(c.dp(strokeDp), strokeColor)
    }

    /** Focused: a gold ring with a soft gold halo around it. Clear focus is the main thing a 10-foot UI needs. */
    fun focusable(c: Context, normal: Int = SURFACE, radiusDp: Int = 14, selected: Int = SURFACE_2): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), glowRing(c, SURFACE_2, radiusDp))
        addState(intArrayOf(android.R.attr.state_selected), rounded(c, selected, radiusDp, ACCENT, 2))
        addState(intArrayOf(), rounded(c, normal, radiusDp))
    }

    /** A filled shape with a gold edge and a translucent gold halo outside it (no blur: cheap on 1 GB TVs). */
    fun glowRing(c: Context, fill: Int, radiusDp: Int): Drawable {
        val halo = rounded(c, 0x00000000, radiusDp + 4, GLOW, 5)
        val body = rounded(c, fill, radiusDp, ACCENT, 3)
        return android.graphics.drawable.LayerDrawable(arrayOf(halo, body)).apply { val i = c.dp(3); setLayerInset(1, i, i, i, i) }
    }

    /** Diagonal background for whole screens: deep navy to near-black with a violet hint. */
    fun backdrop() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF141B3A.toInt(), 0xFF070B14.toInt(), 0xFF0D0812.toInt()))

    /** Small rounded label: LIVE, HD, a category. */
    fun pill(c: Context, s: String, bg: Int, fg: Int = TEXT, sizeSp: Float = 11f) = text(c, s, sizeSp, fg, bold = true).apply {
        background = rounded(c, bg, 20); setPadding(c.dp(8), c.dp(3), c.dp(8), c.dp(3))
    }

    /** The main call to action: gold, dark text, white ring when focused. */
    fun primaryButton(c: Context, label: String, onClick: () -> Unit) = text(c, label, 15f, 0xFF1A1205.toInt(), bold = true).apply {
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFD25A.toInt(), ACCENT)).apply {
                cornerRadius = c.dp(28).toFloat(); setStroke(c.dp(3), Color.WHITE) })
            addState(intArrayOf(), GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT_2)).apply { cornerRadius = c.dp(28).toFloat() })
        }
        gravity = Gravity.CENTER
        setPadding(c.dp(20), c.dp(9), c.dp(20), c.dp(9))
        setOnClickListener { onClick() }
        scaleOnFocus(this, 1.06f)
    }

    /** Grows the focused view a little; off with Settings > Animations. */
    fun scaleOnFocus(v: View, scale: Float = 1.04f) {
        val prev = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, has ->
            prev?.onFocusChange(view, has)
            if (!animations(view.context)) { view.scaleX = 1f; view.scaleY = 1f; return@setOnFocusChangeListener }
            val s = if (has) scale else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    fun text(c: Context, s: CharSequence = "", sizeSp: Float = 18f, color: Int = TEXT, bold: Boolean = false, lines: Int = 1) = TextView(c).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        includeFontPadding = false
    }

    fun button(c: Context, label: String, onClick: () -> Unit) = text(c, label, 15f, bold = true).apply {
        isFocusable = true; isClickable = true
        background = focusable(c, SURFACE_2, 24)
        gravity = Gravity.CENTER
        setPadding(c.dp(18), c.dp(9), c.dp(18), c.dp(9))
        minWidth = c.dp(90)
        setOnClickListener { onClick() }
        scaleOnFocus(this, 1.05f)
    }

    // Children grow on focus (scaleOnFocus), so containers never clip them.
    fun vertical(c: Context, gapDp: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        if (gapDp > 0) { dividerDrawable = spacer(c, gapDp); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
    }
    fun horizontal(c: Context, gapDp: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false; clipToPadding = false
        if (gapDp > 0) { dividerDrawable = spacer(c, gapDp); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
    }
    private fun spacer(c: Context, d: Int) = GradientDrawable().apply { setSize(c.dp(d), c.dp(d)); setColor(Color.TRANSPARENT) }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun time(c: Context, t: Long): String = android.text.format.DateFormat.getTimeFormat(c).format(Date(t))
    fun dayTime(c: Context, t: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t)) + " " + time(c, t)
}
