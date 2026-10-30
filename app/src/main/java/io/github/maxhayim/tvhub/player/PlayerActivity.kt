package io.github.maxhayim.tvhub.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.maxhayim.tvhub.TvHubApp
import io.github.maxhayim.tvhub.model.Channel
import io.github.maxhayim.tvhub.model.Programme
import io.github.maxhayim.tvhub.model.StreamRef
import io.github.maxhayim.tvhub.ui.L
import io.github.maxhayim.tvhub.ui.Shelves
import io.github.maxhayim.tvhub.ui.Ui
import io.github.maxhayim.tvhub.ui.Ui.dp
import io.github.maxhayim.tvhub.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full-screen live playback with Media3, premium edition. Keys:
 * OK opens the channel list over the video; Up/Down and Channel +/- change channel; Left or Last
 * goes back to the previous one; Right, Menu or a long OK opens Options
 * (audio track, subtitles, screen size, sleep timer, favourite, stream info); digits tune by number;
 * Back closes a panel, then returns to the guide. The player is released as soon as the screen stops.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val scope = MainScope()
    private val repo get() = (application as TvHubApp).repo
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var overlay: InfoOverlay
    private lateinit var panel: ChannelPanel
    private lateinit var menu: OptionsPanel
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView

    private var list: List<Channel> = emptyList()
    private var index = 0
    private var previous = -1
    private var streamIndex = 0
    private var retries = 0
    private var triedHls = false
    private var digits = ""
    private var sleepAt = 0L

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    )
    private fun resizeDesc(i: Int) = when (i) {
        0 -> L.t("Fit · whole picture", "Fit · poora picture")
        1 -> L.t("Fill · stretch to screen", "Fill · poori screen")
        else -> L.t("Zoom · crop the edges", "Zoom · kinare kaatein")
    }
    private var resize: Int
        get() = prefs.getInt("resize", 0).coerceIn(0, resizeModes.size - 1)
        set(v) { prefs.edit().putInt("resize", v).apply(); playerView.resizeMode = resizeModes[v].first }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        list = Queue.channels
        index = Queue.index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = resizeModes[resize].first
            setShutterBackgroundColor(0xFF000000.toInt())
            isFocusable = false
        }
        root.addView(playerView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        spinner = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT) }
        root.addView(spinner, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        overlay = InfoOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        errorBox = Ui.vertical(this, 12).apply {
            background = Ui.rounded(this@PlayerActivity, 0xF0111A2C.toInt(), 22, Ui.LIVE, 2)
            setPadding(dp(36), dp(28), dp(36), dp(28)); visibility = View.GONE
        }
        errorText = Ui.text(this, "", 15f, lines = 4)
        errorBox.addView(Ui.text(this, L.t("CHANNEL NOT PLAYING", "CHANNEL NAHI CHAL RAHA"), 13f, Ui.LIVE, bold = true).apply { letterSpacing = 0.15f })
        errorBox.addView(errorText)
        errorBox.addView(Ui.text(this, L.t("OK: try again · Up/Down: other channel · Back: guide", "OK: phir try karein · Up/Down: doosra channel · Back: guide"), 15f, Ui.DIM))
        root.addView(errorBox, FrameLayout.LayoutParams(dp(480), Ui.WRAP, Gravity.CENTER))
        panel = ChannelPanel(this)
        root.addView(panel, FrameLayout.LayoutParams(dp(400), Ui.MATCH, Gravity.START))
        menu = OptionsPanel(this)
        root.addView(menu, FrameLayout.LayoutParams(dp(360), Ui.MATCH, Gravity.END))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (list.isEmpty()) return
        createPlayer()
        tune(index, remember = false)
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun createPlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.playWhenReady = true
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    spinner.visibility = if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && errorBox.visibility != View.VISIBLE) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) {
                        retries = 0; errorBox.visibility = View.GONE
                        main.removeCallbacks(skipDead)
                        list.getOrNull(index)?.let { repo.health.markAlive(it.key) }
                        val v = p.videoFormat; val a = p.audioFormat
                        overlay.setQuality(v)
                        // One line per tune for diagnostics: formats only, never the address.
                        Log.i("Playing: video ${v?.sampleMimeType} ${v?.width}x${v?.height}, audio ${a?.sampleMimeType} ${a?.channelCount}ch")
                    }
                    // A live stream that ends was cut by the server; start it again.
                    if (state == Player.STATE_ENDED) recover("The stream ended")
                }
                override fun onPlayerError(error: PlaybackException) { onError(error) }
            })
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(retryTask)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun tune(i: Int, remember: Boolean = true) {
        if (list.isEmpty()) return
        val target = ((i % list.size) + list.size) % list.size
        if (remember && target != index) previous = index
        index = target
        streamIndex = 0; retries = 0; triedHls = false
        errorBox.visibility = View.GONE
        main.removeCallbacks(skipDead)
        val ch = list[index]
        repo.addRecent(ch.key)
        playStream(ch.streams.getOrNull(0))
        showInfo()
    }

    private fun playStream(s: StreamRef?, forceHls: Boolean = false) {
        val p = player ?: return
        if (s == null) { showError("This channel has no stream address."); return }
        val headers = HashMap<String, String>()
        s.referrer?.let { headers["Referer"] = it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(s.userAgent ?: "TVHub/1.0 (Android TV; Media3)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(s.url).apply {
            if (forceHls || looksHls(s.url)) setMimeType(MimeTypes.APPLICATION_M3U8)
            setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build())
        }.build()
        // One retry per load: Media3's default of three, each waiting on a timeout, kept a dead host
        // on a spinner for over a minute. Recovery below decides what happens next.
        val factory = DefaultMediaSourceFactory(this).setDataSourceFactory(http)
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(1))
        p.setMediaSource(factory.createMediaSource(item))
        p.prepare()
        p.play()
    }

    private fun looksHls(url: String) = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") } || url.contains("m3u8", ignoreCase = true)

    /**
     * Recovery: fall behind a live window → jump to live; a stream with no extension that is really
     * HLS → retry as HLS; network trouble → retry twice with a short wait; then the next stream of
     * a merged channel; then say what went wrong.
     */
    private fun onError(e: PlaybackException) {
        Log.w("Playback error ${e.errorCodeName}")
        val p = player ?: return
        val ch = list[index]
        val s = ch.streams.getOrNull(streamIndex)
        when {
            e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> { p.seekToDefaultPosition(); p.prepare() }
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !triedHls && s != null && !looksHls(s.url) -> {
                triedHls = true; playStream(s, forceHls = true)
            }
            isTransient(e) && retries < 2 -> recover(null)
            streamIndex + 1 < ch.streams.size -> {
                streamIndex++; retries = 0; triedHls = false
                playStream(ch.streams[streamIndex])
                overlay.flash(L.t("Trying another stream: ", "Doosra stream try kar rahe hain: ") + ch.streams[streamIndex].sourceName)
            }
            else -> showError(message(e))
        }
    }

    private val retryTask = Runnable { player?.let { it.seekToDefaultPosition(); it.prepare(); it.play() } }

    private fun recover(note: String?) {
        retries++
        if (retries > 3) { showError(note ?: "The stream keeps stopping."); return }
        spinner.visibility = View.VISIBLE
        main.removeCallbacks(retryTask)
        main.postDelayed(retryTask, 1500L * retries)
    }

    /** Errors worth starting again from scratch. Connection failures are not: the load already retried. */
    private fun isTransient(e: PlaybackException) = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT -> true
        else -> false
    }

    private fun message(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode) {
            404, 410 -> L.t("The stream is no longer on its server (HTTP $code). The channel seems to have closed.", "Ye stream ab apne server par nahi hai (HTTP $code). Channel band ho gaya lagta hai.")
            401, 403 -> L.t("The stream's server refused it (HTTP $code). It may only play in its own country.", "Stream ke server ne mana kar diya (HTTP $code). Ye channel shayad sirf apne desh mein chalta hai.")
            null -> L.t("The stream's server answered with an error.", "Stream ke server ne error diya.")
            else -> L.t("The stream's server answered HTTP $code.", "Stream ke server ne HTTP $code bheja.")
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> L.t("The stream was not found on its server.", "Stream apne server par nahi mili.")
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> L.t("Can't reach the stream's server.", "Stream ke server tak nahi pahunch pa rahe.")
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "This TV blocked an unencrypted (http) stream."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> L.t("This stream's format is not supported.", "Is stream ka format support nahi hai.")
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            L.t("This TV can't decode this channel's video or sound (e.g. MPEG-2 or AC-4).", "Ye TV is channel ka video ya awaaz nahi chala sakta (jaise MPEG-2 ya AC-4).")
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> L.t("This channel is encrypted (DRM) and can't play here.", "Ye channel encrypted (DRM) hai, yahan nahi chal sakta.")
        else -> L.t("Playback failed (${e.errorCodeName}).", "Playback nahi hua (${e.errorCodeName}).")
    }

    private fun showError(msg: String) {
        spinner.visibility = View.GONE
        val ch = list[index]
        repo.health.markDead(ch.key)
        errorText.text = "${Shelves.clean(ch.name)}\n$msg\n\n" + L.t("Next channel in 4 seconds…", "4 second mein agla channel…")
        errorBox.visibility = View.VISIBLE
        main.removeCallbacks(skipDead)
        if (list.size > 1) main.postDelayed(skipDead, 4_000)
    }

    /** A dead channel is remembered (Home hides it) and playback moves on, unless the owner is busy in a panel. */
    private val skipDead = Runnable {
        if (errorBox.visibility == View.VISIBLE && !anyPanelOpen) { overlay.flash(L.t("Skipped a channel that isn't working", "Band channel chhod diya")); tune(index + 1) }
    }

    private fun showInfo() {
        val ch = list[index]
        overlay.bindChannel(ch, list.size, index)
        scope.launch {
            val nn = withContext(Dispatchers.IO) { repo.nowNext(listOf(ch))[ch.key] }
            if (list.getOrNull(index)?.key == ch.key) overlay.bindProgrammes(nn?.first, nn?.second)
        }
        overlay.show()
    }

    // ---------------------------------------------------------------- keys

    private val anyPanelOpen get() = panel.isOpen || menu.isOpen

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key while an error shows means the owner is deciding; don't skip under them.
        if (event.action == KeyEvent.ACTION_DOWN) main.removeCallbacks(skipDead)
        if (event.action == KeyEvent.ACTION_DOWN && anyPanelOpen) { panel.bump(); menu.bump() }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (anyPanelOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { if (menu.isOpen) menu.back() else panel.close(); return true }
            if (keyCode == KeyEvent.KEYCODE_MENU) { panel.close(); openOptions(); return true }
            if (menu.isOpen && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { menu.back(); return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> { if (previous >= 0) tune(previous) else overlay.flash(L.t("No previous channel yet", "Abhi koi pichhla channel nahi")); return true }
            KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            // The Mi remote has no Menu key: Right opens Options (its grid key never reaches apps on Android 9).
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> { openOptions(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { event.startTracking(); return true }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digit(keyCode - KeyEvent.KEYCODE_0); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!anyPanelOpen && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) { openOptions(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!anyPanelOpen && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.isTracking && !event.isCanceled) {
                if (errorBox.visibility == View.VISIBLE) tune(index, remember = false) else panel.open()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Number entry: digits gather for 1.5 s, then the channel with that number is tuned. */
    private fun digit(d: Int) {
        digits = (digits + d).takeLast(5)
        overlay.flash("Channel  $digits")
        main.removeCallbacks(tuneDigits)
        main.postDelayed(tuneDigits, 1500)
    }

    private val tuneDigits = Runnable {
        val i = list.indexOfFirst { it.number == digits }.takeIf { it >= 0 } ?: list.indexOfFirst { it.number?.substringBefore('.') == digits }
        if (i >= 0) tune(i) else overlay.flash(L.t("No channel $digits", "Channel $digits nahi mila"))
        digits = ""
    }

    // ---------------------------------------------------------------- options

    private fun langName(f: Format, fallback: String): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
        return listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifBlank { fallback }
    }

    private fun audioOptions(): List<MenuItem> {
        val p = player ?: return emptyList()
        val out = ArrayList<MenuItem>()
        p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (t in 0 until g.length) {
                if (!g.isTrackSupported(t)) continue
                val f = g.getTrackFormat(t)
                val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (f.channelCount > 0) "${f.channelCount}ch" else "" }
                out += MenuItem(langName(f, "Track ${out.size + 1}"), ch, selected = g.isTrackSelected(t)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build()
                    overlay.flash(L.t("Audio: ", "Awaaz: ") + langName(f, "Track"))
                    menu.close()
                }
            }
        }
        if (out.isEmpty()) out += MenuItem(L.t("This channel has a single audio track", "Is channel mein ek hi awaaz hai"), "", enabled = false) {}
        return out
    }

    private fun subtitleOptions(): List<MenuItem> {
        val p = player ?: return emptyList()
        val text = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val out = ArrayList<MenuItem>()
        out += MenuItem(L.t("Off", "Band (Off)"), "", selected = text.none { it.isSelected }) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            menu.close()
        }
        text.forEach { g ->
            for (t in 0 until g.length) {
                if (!g.isTrackSupported(t)) continue
                val f = g.getTrackFormat(t)
                out += MenuItem(langName(f, "Subtitle ${t + 1}"), "", selected = g.isTrackSelected(t)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build()
                    menu.close()
                }
            }
        }
        if (text.isEmpty()) out += MenuItem(L.t("No subtitles in this stream", "Is stream mein subtitles nahi hain"), "", enabled = false) {}
        return out
    }

    private fun currentAudioLabel(): String {
        val p = player ?: return ""
        p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (t in 0 until g.length) if (g.isTrackSelected(t)) return langName(g.getTrackFormat(t), "Default")
        }
        return "Default"
    }

    private fun sleepOptions(): List<MenuItem> = listOf(0, 15, 30, 45, 60, 90, 120).map { m ->
        MenuItem(if (m == 0) L.t("Off", "Band (Off)") else L.t("$m minutes", "$m minute"), "", selected = (m == 0 && sleepAt == 0L)) {
            main.removeCallbacks(sleeper)
            if (m == 0) { sleepAt = 0; overlay.flash(L.t("Sleep timer off", "Sleep timer band")) }
            else { sleepAt = System.currentTimeMillis() + m * 60_000L; main.postDelayed(sleeper, m * 60_000L); overlay.flash(L.t("Playback stops in $m minutes", "$m minute baad TV band ho jayega")) }
            menu.close()
        }
    }

    private val sleeper = Runnable { sleepAt = 0; finish() }

    private fun sleepLabel() = if (sleepAt == 0L) "Off" else L.t("${((sleepAt - System.currentTimeMillis()) / 60_000L + 1)} min left", "${((sleepAt - System.currentTimeMillis()) / 60_000L + 1)} min baaki")

    private fun infoRows(): List<MenuItem> {
        val p = player
        val v = p?.videoFormat; val a = p?.audioFormat
        val ch = list[index]
        val rows = ArrayList<Pair<String, String>>()
        rows += "Channel" to ch.name
        ch.number?.let { rows += "Number" to it }
        Shelves.shelfOf(ch)?.let { rows += "Category" to it.label }
        if (v != null) {
            rows += "Video" to "${v.width}×${v.height}" + (if (v.frameRate > 0) " · ${"%.0f".format(v.frameRate)} fps" else "")
            rows += "Video codec" to (v.sampleMimeType?.substringAfter('/') ?: "?")
            if (v.bitrate > 0) rows += "Video bitrate" to "${v.bitrate / 1000} kbps"
        }
        if (a != null) rows += "Audio" to listOfNotNull(a.sampleMimeType?.substringAfter('/'), a.channelCount.takeIf { it > 0 }?.let { "${it}ch" }, a.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }).joinToString(" · ")
        rows += "Source" to (ch.streams.getOrNull(streamIndex)?.sourceName ?: "")
        return rows.map { (k, value) -> MenuItem(k, value, enabled = true) {} }
    }

    private fun openOptions() {
        val ch = list[index]
        val fav = ch.key in repo.favorites.value
        menu.show("Options", Shelves.clean(ch.name), listOf(
            MenuItem(L.t("🔊  Audio", "🔊  Awaaz · Audio"), currentAudioLabel()) { menu.push(L.t("Audio", "Awaaz chunein"), L.t("Audio track", "Audio track"), audioOptions()) },
            MenuItem("💬  Subtitles", "") { menu.push("Subtitles", "Captions", subtitleOptions()) },
            MenuItem("⛶  Screen size", resizeModes[resize].second) {
                resize = (resize + 1) % resizeModes.size
                overlay.flash("Screen: " + resizeDesc(resize))
                openOptions()
            },
            MenuItem("⏱  Sleep timer", sleepLabel()) { menu.push("Sleep timer", L.t("Stop playback after", "Itni der baad band karein"), sleepOptions()) },
            MenuItem(if (fav) L.t("★  Remove from Favorites", "★  Favorites se hatayein") else L.t("☆  Add to Favorites", "☆  Favorites mein jodein"), "") {
                val on = repo.toggleFavorite(ch.key)
                overlay.flash(if (on) L.t("★ Added to Favorites", "★ Favorites mein juda") else L.t("Removed from Favorites", "Favorites se hataya"))
                menu.close()
            },
            MenuItem("☰  Channel list", "") { menu.close(); panel.open() },
            MenuItem(L.t("↺  Previous channel", "↺  Pichhla channel"), list.getOrNull(previous)?.name ?: "—") { menu.close(); if (previous >= 0) tune(previous) },
            MenuItem("ℹ  Stream info", "") { menu.push("Stream info", Shelves.clean(ch.name), infoRows()) },
        ))
    }

    /** One row of the Options sheet. */
    class MenuItem(val label: String, val value: String, val selected: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)

    /** Hands the channel list to the player without putting thousands of entries through an Intent. */
    object Queue {
        var channels: List<Channel> = emptyList()
        var index = 0
    }

    companion object {
        fun start(c: Context, list: List<Channel>, index: Int) {
            Queue.channels = list
            Queue.index = index
            c.startActivity(Intent(c, PlayerActivity::class.java))
        }

        fun qualityLabel(h: Int): String? = when {
            h >= 2000 -> "4K"
            h >= 1000 -> "FHD 1080p"
            h >= 700 -> "HD 720p"
            h > 0 -> "SD ${h}p"
            else -> null
        }
    }

    // ---------------------------------------------------------------- views

    /** Channel banner at the bottom: logo card, gold number, name, pills, now/next and the clock. */
    private inner class InfoOverlay(c: Context) : LinearLayout(c) {
        private val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val logoInitial = Ui.text(c, "", 18f, bold = true).apply { gravity = Gravity.CENTER }
        private val number = Ui.text(c, "", 22f, Ui.ACCENT, bold = true)
        private val title = Ui.text(c, "", 24f, bold = true)
        private val pills = Ui.horizontal(c, 6)
        private val quality = Ui.pill(c, "", 0x33FFFFFF)
        private val now = Ui.text(c, "", 14f)
        private val next = Ui.text(c, "", 12f, Ui.DIM)
        private val note = Ui.text(c, "", 14f, Ui.ACCENT, bold = true)
        private val clock = Ui.text(c, "", 22f, bold = true).apply { gravity = Gravity.END }
        private val position = Ui.text(c, "", 11f, Ui.DIM).apply { gravity = Gravity.END }
        private val hint = Ui.text(c, L.t("OK  Channel list   ·   ▶  Options (Audio, Screen, Sleep)   ·   ◀  Previous   ·   ▲▼  Change channel", "OK  Channel list   ·   ▶  Options (Audio, Screen, Sleep)   ·   ◀  Pichhla   ·   ▲▼  Channel badlein"), 11f, Ui.DIM)
        private val hide = Runnable { animate().alpha(0f).setDuration(if (Ui.animations(context)) 300 else 0).start() }

        init {
            orientation = VERTICAL
            setPadding(dp(40), dp(44), dp(40), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0xB3050814.toInt(), 0x00000000))
            addView(note)
            val row = Ui.horizontal(c, 18).apply { gravity = Gravity.CENTER_VERTICAL }
            val card = FrameLayout(c).apply { background = Ui.rounded(c, 0x26FFFFFF, 12); setPadding(dp(9), dp(8), dp(9), dp(8)) }
            card.addView(logoInitial, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            card.addView(logo, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            row.addView(card, Ui.lp(dp(124), dp(70)))
            val col = Ui.vertical(c, 5)
            val titleRow = Ui.horizontal(c, 10).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(number); titleRow.addView(title, Ui.lp(0, Ui.WRAP, 1f))
            col.addView(titleRow); col.addView(pills); col.addView(now); col.addView(next)
            row.addView(col, Ui.lp(0, Ui.WRAP, 1f))
            val right = Ui.vertical(c, 4).apply { gravity = Gravity.END }
            right.addView(clock); right.addView(position)
            row.addView(right)
            addView(row)
            addView(View(c).apply { setBackgroundColor(0x22FFFFFF) }, Ui.lp(Ui.MATCH, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(8) })
            addView(hint)
            alpha = 0f
        }

        fun bindChannel(ch: Channel, total: Int, i: Int) {
            number.text = ch.number ?: "${i + 1}"
            title.text = Shelves.clean(ch.name)
            logoInitial.text = Shelves.clean(ch.name).take(2).uppercase()
            (context.applicationContext as TvHubApp).logos.load(ch.logo, logo, dp(54)) { has -> logoInitial.visibility = if (has) View.INVISIBLE else View.VISIBLE }
            pills.removeAllViews()
            pills.addView(Ui.pill(context, "● LIVE", Ui.LIVE))
            quality.visibility = View.GONE
            pills.addView(quality)
            Shelves.shelfOf(ch)?.let { pills.addView(Ui.pill(context, it.label, (it.accent and 0x00FFFFFF) or 0x66000000)) }
            if (Shelves.isHindi(ch)) pills.addView(Ui.pill(context, "हिंदी", 0x33FFFFFF))
            if (sleepAt > 0) pills.addView(Ui.pill(context, "⏱ ${sleepLabel()}", 0x33FFFFFF))
            position.text = "${i + 1} / $total"
            clock.text = Ui.time(context, System.currentTimeMillis())
            now.text = if (ch.epgIds.isEmpty()) L.t("Live channel · no programme guide", "Live channel · guide uplabdh nahi") else ""
            next.text = ""; note.text = ""
        }

        fun setQuality(f: Format?) {
            val q = f?.let { qualityLabel(it.height) } ?: return
            quality.text = q; quality.visibility = View.VISIBLE
        }

        fun bindProgrammes(cur: Programme?, nx: Programme?) {
            if (cur != null) now.text = L.t("Now   ", "Abhi   ") + "${Ui.time(context, cur.start)} – ${Ui.time(context, cur.stop)}   ${cur.title}"
            if (nx != null) next.text = L.t("Next   ", "Agla   ") + "${Ui.time(context, nx.start)}   ${nx.title}"
        }

        fun show() { clock.text = Ui.time(context, System.currentTimeMillis()); alpha = 1f; main.removeCallbacks(hide); main.postDelayed(hide, 5000) }
        fun flash(msg: String) { note.text = msg; show() }
    }

    /** TiviMate-style channel list over the video: logo, number, name; OK tunes, Back closes. */
    private inner class ChannelPanel(c: Context) : LinearLayout(c) {
        var isOpen = false; private set
        private val header = Ui.text(c, "", 11f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.15f }
        private val rv = RecyclerView(c)
        private val autoClose = Runnable { close() }

        init {
            orientation = VERTICAL
            setPadding(dp(22), dp(24), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5070B14.toInt(), 0xE6070B14.toInt(), 0x00000000))
            addView(header, Ui.lp().apply { bottomMargin = dp(8); marginStart = dp(6) })
            rv.layoutManager = LinearLayoutManager(c)
            rv.itemAnimator = null
            rv.clipToPadding = false
            rv.setPadding(dp(6), dp(6), dp(30), dp(60))
            rv.adapter = Adapter()
            addView(rv, Ui.lp(Ui.MATCH, 0, 1f))
            visibility = View.GONE
        }

        fun open() {
            if (list.isEmpty()) return
            menu.close()
            isOpen = true
            header.text = "CHANNELS · ${list.size}"
            visibility = View.VISIBLE
            alpha = 0f; translationX = -dp(60).toFloat()
            animate().alpha(1f).translationX(0f).setDuration(if (Ui.animations(context)) 180 else 0).start()
            rv.adapter?.notifyDataSetChanged()
            (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, dp(170))
            rv.post { rv.layoutManager?.findViewByPosition(index)?.requestFocus() ?: rv.requestFocus() }
            bump()
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            main.removeCallbacks(autoClose)
            visibility = View.GONE
        }

        fun bump() { if (isOpen) { main.removeCallbacks(autoClose); main.postDelayed(autoClose, 12_000) } }

        private inner class Adapter : RecyclerView.Adapter<Adapter.H>() {
            inner class H(val row: LinearLayout, val num: TextView, val logo: ImageView, val name: TextView, val sub: TextView, val playing: TextView) : RecyclerView.ViewHolder(row)

            override fun getItemCount() = list.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
                val c = parent.context
                val row = Ui.horizontal(c, 10).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true; isClickable = true
                    background = Ui.focusable(c, 0x00000000, 10)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
                }
                val num = Ui.text(c, "", 13f, Ui.DIM).apply { gravity = Gravity.END }
                val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                val col = Ui.vertical(c, 2)
                val name = Ui.text(c, "", 14f, bold = true)
                val sub = Ui.text(c, "", 10f, Ui.DIM)
                col.addView(name); col.addView(sub)
                val playing = Ui.pill(c, L.t("▶ NOW", "▶ ABHI"), Ui.ACCENT, 0xFF1A1205.toInt(), 9f)
                row.addView(num, Ui.lp(dp(34), Ui.WRAP))
                row.addView(logo, Ui.lp(dp(52), dp(29)))
                row.addView(col, Ui.lp(0, Ui.WRAP, 1f))
                row.addView(playing, Ui.lp(Ui.WRAP, Ui.WRAP))
                row.setOnFocusChangeListener { _, has -> name.setTextColor(if (has) Ui.ACCENT else Ui.TEXT) }
                return H(row, num, logo, name, sub, playing)
            }

            override fun onBindViewHolder(h: H, position: Int) {
                val ch = list[position]
                h.num.text = ch.number ?: "${position + 1}"
                h.name.text = Shelves.clean(ch.name)
                h.sub.text = listOfNotNull(Shelves.shelfOf(ch)?.label, if (Shelves.isHindi(ch)) "हिंदी" else null).joinToString("  ·  ")
                h.playing.visibility = if (position == index) View.VISIBLE else View.GONE
                (h.row.context.applicationContext as TvHubApp).logos.load(ch.logo, h.logo, dp(29))
                h.row.setOnClickListener { val i = h.bindingAdapterPosition; if (i >= 0) { close(); tune(i) } }
            }
        }
    }

    /** The Options sheet on the right: a list of rows; rows can open a sub-list, Back goes up a level. */
    inner class OptionsPanel(c: Context) : LinearLayout(c) {
        private inner class Level(val title: String, val subtitle: String, val items: List<MenuItem>)

        var isOpen = false; private set
        private val stack = ArrayList<Level>()
        private val title = Ui.text(c, "", 21f, bold = true)
        private val subtitle = Ui.text(c, "", 11f, Ui.ACCENT, bold = true)
        private val rows = Ui.vertical(c, 4)
        private val autoClose = Runnable { close() }

        init {
            orientation = VERTICAL
            setPadding(dp(28), dp(30), dp(24), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0xFA070B14.toInt(), 0xF0070B14.toInt(), 0xC0070B14.toInt()))
            addView(subtitle); addView(title, Ui.lp().apply { topMargin = dp(3); bottomMargin = dp(14) })
            addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(rows) }, Ui.lp(Ui.MATCH, 0, 1f))
            addView(Ui.text(c, L.t("Back / ◀: go back   ·   OK: select", "Back / ◀: peeche   ·   OK: chunein"), 10f, Ui.DIM), Ui.lp().apply { topMargin = dp(8) })
            visibility = View.GONE
        }

        fun show(t: String, sub: String, items: List<MenuItem>) { stack.clear(); push(t, sub, items) }

        fun push(t: String, sub: String, items: List<MenuItem>) {
            stack += Level(t, sub, items)
            render()
            if (!isOpen) {
                isOpen = true; visibility = View.VISIBLE
                alpha = 0f; translationX = dp(60).toFloat()
                animate().alpha(1f).translationX(0f).setDuration(if (Ui.animations(context)) 180 else 0).start()
            }
            bump()
        }

        fun back() { if (stack.size > 1) { stack.removeAt(stack.lastIndex); render() } else close() }

        fun close() { isOpen = false; stack.clear(); main.removeCallbacks(autoClose); visibility = View.GONE }

        fun bump() { if (isOpen) { main.removeCallbacks(autoClose); main.postDelayed(autoClose, 20_000) } }

        private fun render() {
            val lv = stack.last()
            title.text = lv.title; subtitle.text = lv.subtitle.uppercase()
            rows.removeAllViews()
            var focus: View? = null
            lv.items.forEach { it ->
                val row = Ui.horizontal(context, 12).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true; isClickable = it.enabled
                    background = Ui.focusable(context, 0x14FFFFFF, 10)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                val label = Ui.text(context, it.label, 14f, if (it.enabled) Ui.TEXT else Ui.DIM, bold = true)
                val value = Ui.text(context, it.value, 12f, if (it.selected) Ui.ACCENT else Ui.DIM).apply { gravity = Gravity.END }
                row.addView(label, Ui.lp(0, Ui.WRAP, 1f))
                if (it.selected) row.addView(Ui.text(context, "✓", 15f, Ui.ACCENT, bold = true))
                else row.addView(value, Ui.lp(Ui.WRAP, Ui.WRAP))
                row.setOnClickListener { _ -> if (it.enabled) it.action() }
                rows.addView(row, Ui.lp())
                if (focus == null || it.selected) focus = row
            }
            post { focus?.requestFocus() }
        }
    }
}
