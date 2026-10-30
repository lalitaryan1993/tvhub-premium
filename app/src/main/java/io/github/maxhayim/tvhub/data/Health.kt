package io.github.maxhayim.tvhub.data

import android.content.Context
import io.github.maxhayim.tvhub.model.Channel
import io.github.maxhayim.tvhub.model.StreamRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which channels do not play from this TV's network. Free public playlists carry many dead or
 * geo-blocked streams, so Home hides the ones a check (or a failed play) found dead. The check runs
 * only when the owner asks, from the TV itself, so it judges the network the TV actually has.
 */
class Health(context: Context) {
    private val p = context.getSharedPreferences("health", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _dead = MutableStateFlow(p.getStringSet("dead", emptySet())!!.toSet())
    val dead: StateFlow<Set<String>> = _dead

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val bad: Int = 0, val lastCheck: Long = 0, val lastOk: Int = 0, val lastBad: Int = 0)
    private val _progress = MutableStateFlow(Progress(lastCheck = p.getLong("last", 0), lastOk = p.getInt("ok", 0), lastBad = p.getInt("bad", 0)))
    val progress: StateFlow<Progress> = _progress

    private fun save(set: Set<String>) { _dead.value = set; p.edit().putStringSet("dead", set).apply() }

    fun markDead(key: String) { if (key !in _dead.value) save(_dead.value + key) }
    fun markAlive(key: String) { if (key in _dead.value) save(_dead.value - key) }
    fun clear() { save(emptySet()); p.edit().remove("last").apply(); _progress.value = Progress() }

    /** Tries every channel's first two streams, eight at a time, a few seconds each. */
    fun check(channels: List<Channel>) {
        if (job?.isActive == true || channels.isEmpty()) return
        job = scope.launch {
            val total = channels.size
            val done = AtomicInteger(); val bad = AtomicInteger()
            _progress.value = _progress.value.copy(running = true, done = 0, total = total, bad = 0)
            val gate = Semaphore(8)
            val dead = channels.map { ch ->
                async {
                    gate.withPermit {
                        val ok = ch.streams.take(2).any { probe(it) }
                        if (!ok) bad.incrementAndGet()
                        val d = done.incrementAndGet()
                        if (d % 5 == 0 || d == total) _progress.value = _progress.value.copy(done = d, bad = bad.get())
                        if (ok) null else ch.key
                    }
                }
            }.awaitAll().filterNotNull().toSet()
            save(dead)
            val now = System.currentTimeMillis()
            p.edit().putLong("last", now).putInt("ok", total - dead.size).putInt("bad", dead.size).apply()
            _progress.value = Progress(false, total, total, dead.size, now, total - dead.size, dead.size)
        }
    }

    /** A stream counts as alive if its server answers 2xx with a playlist or media, not an error page. */
    private fun probe(s: StreamRef): Boolean {
        var url = s.url
        try {
            repeat(4) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 6_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", s.userAgent ?: "TVHub/1.0 (Android TV; Media3)")
                s.referrer?.let { c.setRequestProperty("Referer", it) }
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: return false
                        url = URL(URL(url), loc).toString()
                        return@repeat
                    }
                    if (code !in 200..299) return false
                    val buf = ByteArray(1024)
                    val n = c.inputStream.use { it.read(buf) }
                    if (n <= 0) return false
                    val head = String(buf, 0, n, Charsets.ISO_8859_1).trimStart()
                    return head.startsWith("#EXTM3U") || !head.contains("<html", ignoreCase = true)
                } finally { c.disconnect() }
            }
        } catch (e: Exception) { return false }
        return false
    }
}
