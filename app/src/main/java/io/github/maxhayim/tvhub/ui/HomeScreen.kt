package io.github.maxhayim.tvhub.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import io.github.maxhayim.tvhub.TvHubApp
import io.github.maxhayim.tvhub.model.Channel
import io.github.maxhayim.tvhub.source.Apps
import io.github.maxhayim.tvhub.ui.Ui.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Premium Home: a hero banner for the last channel (or a first pick), then shelves of channel
 * cards by category (News, Music, Bhakti, ...), Hindi channels first, each ending in "See all".
 */
class HomeScreen(host: MainActivity) : Screen(host) {
    private val clock = Ui.text(ctx, "", 22f, bold = true)
    private val date = Ui.text(ctx, "", 12f, Ui.DIM)
    private val greet = Ui.text(ctx, "", 11f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.15f }
    private val hero = FrameLayout(ctx)
    private val rows = Ui.vertical(ctx, 18)
    private val body = Ui.vertical(ctx, 16)
    private var firstFocus: View? = null
    private val checkLine = Ui.text(ctx, "", 12f, Ui.ACCENT, bold = true, lines = 2)
    private var allChannels: List<Channel> = emptyList()

    override val view: View = ScrollView(ctx).apply {
        clipChildren = false; clipToPadding = false
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        val head = Ui.horizontal(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            val left = Ui.vertical(ctx, 4)
            left.addView(greet)
            left.addView(Ui.text(ctx, L.t("Live TV", "Live TV"), 24f, bold = true))
            addView(left, Ui.lp(0, Ui.WRAP, 1f))
            val right = Ui.vertical(ctx, 2).apply { gravity = Gravity.END }
            right.addView(clock.apply { gravity = Gravity.END }); right.addView(date.apply { gravity = Gravity.END })
            addView(right)
        }
        body.addView(head)
        body.addView(hero.apply { minimumHeight = ctx.dp(180) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        body.addView(rows)
        addView(body, Ui.lp())
    }

    override fun onShow() {
        watch(combine(repo.channels, repo.favorites, repo.sources, repo.guideVersion, repo.health.dead) { c, f, s, _, d -> listOf(c, f, s, d) }) { v ->
            @Suppress("UNCHECKED_CAST")
            launch { build(v[0] as List<Channel>, v[1] as Set<String>, (v[2] as List<*>).isNotEmpty(), v[3] as Set<String>) }
        }
        watch(repo.health.progress) { pr ->
            checkLine.text = when {
                pr.running -> L.t("⟳  Checking channels… ${pr.done}/${pr.total}  ·  ${pr.bad} not working", "⟳  Channels check ho rahe hain… ${pr.done}/${pr.total}  ·  ${pr.bad} band mile")
                pr.lastCheck > 0 -> L.t("✓  Last check ${Ui.dayTime(ctx, pr.lastCheck)}: ${pr.lastOk} working, ${pr.lastBad} hidden", "✓  Aakhri check ${Ui.dayTime(ctx, pr.lastCheck)}: ${pr.lastOk} chal rahe, ${pr.lastBad} band (chhupaye gaye)")
                else -> L.t("Tip: press ✓ Check to hide channels that don't play on your network.", "Tip: ✓ Check dabaiye, band channels Home se hat jayenge.")
            }
        }
        launch {
            while (true) {
                val now = System.currentTimeMillis()
                clock.text = Ui.time(ctx, now)
                date.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(java.util.Date(now))
                greet.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                    in 5..11 -> L.t("GOOD MORNING", "SUPRABHAT · GOOD MORNING")
                    in 12..16 -> L.t("GOOD AFTERNOON", "NAMASTE · GOOD AFTERNOON")
                    in 17..20 -> L.t("GOOD EVENING", "SHUBH SANDHYA · GOOD EVENING")
                    else -> L.t("GOOD NIGHT", "SHUBH RATRI · GOOD NIGHT")
                }
                delay(15_000)
            }
        }
    }

    private suspend fun build(channels: List<Channel>, favs: Set<String>, hasSources: Boolean, dead: Set<String>) {
        val hadFocus = view.findFocus() != null
        allChannels = channels
        val recents = withContext(Dispatchers.IO) { repo.recents() }.filter { it.key !in dead }
        val ranked = Shelves.rank(channels.filter { it.key !in dead })
        val favList = Shelves.rank(channels.filter { it.key in favs })
        val apps = repo.appShortcuts()

        buildHero(ranked, recents.firstOrNull(), hasSources)

        rows.removeAllViews()
        if (recents.isNotEmpty()) rows.addView(channelRow(L.t("Recently watched", "Recently watched"), L.t("Pick up where you left off", "Jo aapne abhi dekha"), Ui.ACCENT, recents))
        if (favList.isNotEmpty()) rows.addView(channelRow(L.t("Favorites", "Favorites"), L.t("Your channels", "Aapke pasandida"), Ui.ACCENT, favList))
        Shelves.all.forEach { s ->
            val list = ranked.filter { ch -> Shelves.cats(ch).any { it in s.cats } }
            if (list.isNotEmpty()) rows.addView(channelRow(s.title, L.t("${list.size} channels", "${s.en} · ${list.size}"), s.accent, list))
        }
        if (apps.isNotEmpty()) rows.addView(appRow(apps))
        if (channels.isNotEmpty() && favList.isEmpty()) rows.addView(Ui.text(ctx, L.t("Tip: hold OK on a channel to add it to Favorites.", "Tip: kisi channel par OK dabaye rakhein, wo Favorites mein jud jayega."), 12f, Ui.DIM))
        if (hadFocus) focusFirst()
    }

    /** The big banner: logo on the right, name, LIVE and category pills, and a gold Watch button. */
    private fun buildHero(ranked: List<Channel>, last: Channel?, hasSources: Boolean) {
        hero.removeAllViews()
        val pick = last ?: ranked.firstOrNull()
        val tint = pick?.let { Shelves.shelfOf(it)?.accent } ?: Ui.ACCENT
        // A diagonal wash plus a soft glow in the channel's colour behind its logo.
        val wash = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF111A2C.toInt(), (tint and 0x00FFFFFF) or 0x55000000)).apply { cornerRadius = ctx.dp(18).toFloat() }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = ctx.dp(260).toFloat()
            setGradientCenter(0.82f, 0.5f)
            colors = intArrayOf((tint and 0x00FFFFFF) or 0x66000000, 0x00000000)
            cornerRadius = ctx.dp(18).toFloat()
        }
        hero.background = android.graphics.drawable.LayerDrawable(arrayOf(wash, glow))
        hero.clipChildren = false

        val text = Ui.vertical(ctx, 7).apply { setPadding(ctx.dp(26), ctx.dp(18), ctx.dp(12), ctx.dp(16)) }
        val actions = Ui.horizontal(ctx, 10).apply { clipChildren = false; setPadding(0, ctx.dp(6), 0, 0) }
        firstFocus = null
        if (pick == null) {
            text.addView(Ui.text(ctx, L.t("GET STARTED", "SHURU KAREIN · GET STARTED"), 10f, Ui.ACCENT, bold = true))
            text.addView(Ui.text(ctx, if (hasSources) L.t("Loading channels…", "Channels load ho rahe hain…") else L.t("Add a channel list", "Ek channel list jodiye"), 26f, bold = true, lines = 2))
            text.addView(Ui.text(ctx, L.t("Add an M3U playlist address in Sources. For free channels, iptv-org's lists work well.", "Sources mein M3U playlist ka address daaliye. Free channels ke liye iptv-org ki list chalti hai."), 13f, Ui.DIM, lines = 2))
            actions.addView(Ui.primaryButton(ctx, L.t("★  Add free channels", "★  Free channels jodein")) {
                FreeTv.add(repo, FreeTv.quickStart)
                Toast.makeText(ctx, L.t("Adding free Indian channels…", "Free Indian channels jud rahe hain…"), Toast.LENGTH_LONG).show()
            }.also { firstFocus = it })
            actions.addView(Ui.button(ctx, L.t("Choose languages", "Bhasha chunein")) { FreeTv.chooser(host, repo) })
            actions.addView(Ui.button(ctx, L.t("Own sources", "Apne sources")) { ctx.startActivity(Intent(ctx, OnboardingActivity::class.java)) })
            actions.addView(Ui.button(ctx, "Refresh") { repo.refreshAll(); Toast.makeText(ctx, "Refreshing…", Toast.LENGTH_SHORT).show() })
        } else {
            text.addView(Ui.text(ctx, if (last != null) L.t("CONTINUE WATCHING", "CONTINUE WATCHING · PHIR SE DEKHEIN") else L.t("TOP PICK", "AAJ KI PASAND · TOP PICK"), 10f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.12f })
            text.addView(Ui.text(ctx, Shelves.clean(pick.name), 28f, bold = true, lines = 1))
            val pills = Ui.horizontal(ctx, 6)
            pills.addView(Ui.pill(ctx, "● LIVE", Ui.LIVE))
            Shelves.shelfOf(pick)?.let { pills.addView(Ui.pill(ctx, it.label, (it.accent and 0x00FFFFFF) or 0x66000000)) }
            if (Shelves.isHindi(pick)) pills.addView(Ui.pill(ctx, "हिंदी", 0x33FFFFFF))
            pick.number?.let { pills.addView(Ui.pill(ctx, "CH $it", 0x33FFFFFF)) }
            text.addView(pills)
            text.addView(Ui.text(ctx, L.t("${ranked.size} free channels · News, Music, Devotional, Movies and more", "${ranked.size} free channels · News, Gaane, Bhakti, Movies aur bhi"), 12f, Ui.DIM))
            actions.addView(Ui.primaryButton(ctx, L.t("▶  Watch now", "▶  Abhi dekhein")) {
                host.play(ranked, ranked.indexOfFirst { it.key == pick.key }.coerceAtLeast(0))
            }.also { firstFocus = it })
            actions.addView(Ui.button(ctx, L.t("All channels", "Saare channels")) { host.go(MainActivity.Page.LIVE) })
            actions.addView(Ui.button(ctx, "✓ Check") {
                repo.health.check(allChannels)
                Toast.makeText(ctx, L.t("Checking every channel. The ones that don't play will be hidden from Home.", "Saare channels check ho rahe hain. Band wale Home se hat jayenge."), Toast.LENGTH_LONG).show()
            })

            // Watermark: the logo again, large and faint, bleeding off the banner's right edge.
            val mark = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.10f }
            (ctx.applicationContext as TvHubApp).logos.load(pick.logo, mark, ctx.dp(100))
            hero.addView(mark, FrameLayout.LayoutParams(ctx.dp(420), ctx.dp(240), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -ctx.dp(70) })
            val logoCard = FrameLayout(ctx).apply {
                background = Ui.rounded(ctx, 0x2EFFFFFF, 16)
                elevation = ctx.dp(14).toFloat()
                setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            }
            val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val initial = Ui.text(ctx, Shelves.clean(pick.name).take(2).uppercase(), 36f, bold = true).apply { gravity = Gravity.CENTER }
            logoCard.addView(initial, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            logoCard.addView(logo, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            (ctx.applicationContext as TvHubApp).logos.load(pick.logo, logo, ctx.dp(100)) { has -> initial.visibility = if (has) View.INVISIBLE else View.VISIBLE }
            hero.addView(logoCard, FrameLayout.LayoutParams(ctx.dp(200), ctx.dp(124), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ctx.dp(26) })
        }
        text.addView(actions)
        (checkLine.parent as? android.view.ViewGroup)?.removeView(checkLine)
        if (pick != null) text.addView(checkLine)
        hero.addView(text, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginEnd = if (pick != null) ctx.dp(236) else 0 })
    }

    private fun channelRow(title: String, subtitle: String, accent: Int, list: List<Channel>): View {
        val box = Ui.vertical(ctx, 6)
        val head = Ui.horizontal(ctx, 8).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(ctx).apply { background = Ui.rounded(ctx, accent, 3) }, Ui.lp(ctx.dp(4), ctx.dp(18)))
        head.addView(Ui.text(ctx, title, 17f, bold = true))
        head.addView(Ui.text(ctx, subtitle, 12f, Ui.DIM))
        box.addView(head)
        val strip = Ui.horizontal(ctx, 14).apply { clipChildren = false; setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8)) }
        val shown = list.take(24)
        shown.forEachIndexed { i, ch ->
            strip.addView(ChannelCard(ctx).apply {
                bindChannel(ch, null)
                setOnClickListener { host.play(list, i) }
                setOnLongClickListener {
                    val on = repo.toggleFavorite(ch.key)
                    Toast.makeText(ctx, if (on) L.t("★ Added ${ch.name} to Favorites", "★ ${ch.name} Favorites mein juda") else L.t("Removed ${ch.name} from Favorites", "${ch.name} Favorites se hataya"), Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }
        if (list.size > shown.size) strip.addView(ChannelCard(ctx).apply {
            bindMore(L.t("See all", "Sab dekhein"), L.t("+${list.size - shown.size} more", "+${list.size - shown.size} aur channels"))
            setOnClickListener { host.go(MainActivity.Page.LIVE) }
        })
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip) })
        return box
    }

    private fun appRow(apps: List<io.github.maxhayim.tvhub.model.AppShortcut>): View {
        val box = Ui.vertical(ctx, 12)
        box.addView(Ui.text(ctx, "Apps", 23f, bold = true))
        val strip = Ui.horizontal(ctx, 22).apply { clipChildren = false; setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10)) }
        val pm = ctx.packageManager
        apps.forEach { a ->
            val icon = runCatching { pm.getApplicationBanner(a.pkg) ?: pm.getApplicationIcon(a.pkg) }.getOrNull()
            strip.addView(ChannelCard(ctx).apply {
                bindApp(a.label, icon)
                setOnClickListener {
                    val i = Apps.launchIntent(ctx, a.pkg)
                    if (i != null) ctx.startActivity(i) else Toast.makeText(ctx, "${a.label} is not installed any more", Toast.LENGTH_SHORT).show()
                }
            })
        }
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(strip) })
        return box
    }

    override fun focusFirst(): Boolean = firstFocus?.requestFocus() ?: false
}
