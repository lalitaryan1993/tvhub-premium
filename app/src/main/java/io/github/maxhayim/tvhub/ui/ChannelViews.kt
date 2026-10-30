package io.github.maxhayim.tvhub.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.maxhayim.tvhub.TvHubApp
import io.github.maxhayim.tvhub.model.Channel
import io.github.maxhayim.tvhub.model.Programme
import io.github.maxhayim.tvhub.ui.Ui.dp

typealias NowNext = Map<String, Pair<Programme?, Programme?>>

/** A row of the channel list: number, logo, name, now and next. */
class ChannelRow(c: Context) : LinearLayout(c) {
    val number = Ui.text(c, "", 18f, Ui.DIM).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    val name = Ui.text(c, "", 20f, bold = true)
    val now = Ui.text(c, "", 16f, Ui.DIM)
    val next = Ui.text(c, "", 14f, Ui.DIM)
    val fav = Ui.text(c, "★", 18f, Ui.ACCENT)
    val progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true; isLongClickable = true
        background = Ui.focusable(c, 0x00000000)
        setPadding(dp(12), dp(10), dp(16), dp(10))
        addView(number, Ui.lp(dp(56), Ui.WRAP))
        addView(logo, Ui.lp(dp(72), dp(44)).apply { marginStart = dp(16); marginEnd = dp(16) })
        val col = Ui.vertical(c, 4)
        col.addView(name); col.addView(now); col.addView(progress, Ui.lp(Ui.MATCH, dp(4))); col.addView(next)
        addView(col, Ui.lp(0, Ui.WRAP, 1f))
        addView(fav, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(12) })
    }

    fun bind(ch: Channel, nn: Pair<Programme?, Programme?>?, isFav: Boolean, nowMs: Long) {
        number.text = ch.number ?: ""
        name.text = ch.name
        fav.visibility = if (isFav) View.VISIBLE else View.INVISIBLE
        (context.applicationContext as TvHubApp).logos.load(ch.logo, logo, dp(44))
        val cur = nn?.first
        val nx = nn?.second
        if (cur != null) {
            now.text = "${Ui.time(context, cur.start)} – ${Ui.time(context, cur.stop)}  ${cur.title}"
            progress.visibility = View.VISIBLE
            progress.progress = (((nowMs - cur.start) * 1000) / (cur.stop - cur.start).coerceAtLeast(1)).toInt()
        } else {
            now.text = if (ch.epgIds.isEmpty()) "No guide information" else "No programme listed now"
            progress.visibility = View.GONE
        }
        next.text = nx?.let { "Next  ${Ui.time(context, it.start)}  ${it.title}" } ?: ""
        next.visibility = if (nx == null) View.GONE else View.VISIBLE
        contentDescription = listOfNotNull(ch.number, ch.name, cur?.title?.let { "now $it" }).joinToString(", ")
    }
}

class ChannelAdapter(
    private val onPlay: (List<Channel>, Int) -> Unit,
    private val onLong: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.H>() {
    var items: List<Channel> = emptyList(); private set
    var nowNext: NowNext = emptyMap()
    var favorites: Set<String> = emptySet()

    class H(val row: ChannelRow) : RecyclerView.ViewHolder(row)

    fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(ChannelRow(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
    })
    override fun onBindViewHolder(h: H, position: Int) {
        val ch = items[position]
        h.row.bind(ch, nowNext[ch.key], ch.key in favorites, System.currentTimeMillis())
        h.row.setOnClickListener { onPlay(items, h.bindingAdapterPosition.coerceAtLeast(0)) }
        h.row.setOnLongClickListener { onLong(ch); true }
    }
}

/** A tile for the Home rows: logo on a glassy card with a LIVE dot, the name under it. */
class ChannelCard(c: Context) : LinearLayout(c) {
    val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    val initial = Ui.text(c, "", 16f, bold = true, lines = 2).apply { gravity = Gravity.CENTER }
    val name = Ui.text(c, "", 13f, bold = true)
    val sub = Ui.text(c, "", 11f, Ui.DIM)
    val live = Ui.pill(c, "● LIVE", 0xCCE5484D.toInt(), sizeSp = 8f)
    val tile = FrameLayout(c).apply {
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.glowRing(c, 0xFF22304F.toInt(), 12))
            addState(intArrayOf(), android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A2440.toInt(), 0xFF111A2C.toInt())).apply { cornerRadius = c.dp(12).toFloat() })
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    init {
        orientation = VERTICAL
        tile.addView(initial, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        tile.addView(logo, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        tile.addView(live, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { topMargin = -dp(8); marginStart = -dp(9) })
        tile.clipChildren = false; tile.clipToPadding = false
        tile.isDuplicateParentStateEnabled = true
        addView(tile, Ui.lp(dp(164), dp(92)))
        addView(name, Ui.lp(dp(164), Ui.WRAP).apply { topMargin = dp(7) })
        addView(sub, Ui.lp(dp(164), Ui.WRAP).apply { topMargin = dp(2) })
        isFocusable = true; isClickable = true; isLongClickable = true
        clipChildren = false
        setOnFocusChangeListener { v, has ->
            name.setTextColor(if (has) Ui.ACCENT else Ui.TEXT)
            // The focused tile lifts off the page: bigger, with a shadow under it.
            tile.elevation = if (has) dp(14).toFloat() else dp(2).toFloat()
            if (Ui.animations(v.context)) v.animate().scaleX(if (has) 1.09f else 1f).scaleY(if (has) 1.09f else 1f).translationY(if (has) -dp(3).toFloat() else 0f).setDuration(140).start()
        }
    }

    fun bindChannel(ch: Channel, now: Programme?) {
        name.text = Shelves.clean(ch.name)
        val shelf = Shelves.shelfOf(ch)
        sub.text = now?.title ?: listOfNotNull(ch.number?.let { "CH $it" }, shelf?.title, if (Shelves.isHindi(ch)) "हिंदी" else null).joinToString(" · ")
        initial.text = Shelves.clean(ch.name)
        // The name stands in for the logo until one arrives, never behind it.
        (context.applicationContext as TvHubApp).logos.load(ch.logo, logo, dp(68)) { has -> initial.visibility = if (has) View.INVISIBLE else View.VISIBLE }
        contentDescription = ch.name
    }

    /** The last card of a shelf: opens the full list. */
    fun bindMore(title: String, subtitle: String) {
        live.visibility = View.GONE
        initial.text = "→"; initial.textSize = 32f; initial.setTextColor(Ui.ACCENT)
        name.text = title; sub.text = subtitle
        contentDescription = "$title, $subtitle"
    }

    fun bindApp(label: String, icon: android.graphics.drawable.Drawable?) {
        live.visibility = View.GONE
        name.text = label
        sub.text = L.t("Opens the app", "App kholein")
        initial.text = if (icon == null) label else ""
        initial.visibility = View.VISIBLE
        logo.setImageDrawable(icon)
        contentDescription = "$label, opens the app"
    }
}
