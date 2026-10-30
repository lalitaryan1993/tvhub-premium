package io.github.maxhayim.tvhub.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import io.github.maxhayim.tvhub.data.Repository
import io.github.maxhayim.tvhub.model.SourceConfig
import io.github.maxhayim.tvhub.model.SourceType

/**
 * One-click sources: iptv-org's public playlists of free-to-air channels, by country and by
 * language. The app never ships channels itself; these are addresses the owner chooses to add.
 */
object FreeTv {
    private const val BASE = "https://iptv-org.github.io/iptv"

    data class Pack(val en: String, val hi: String, val path: String, val sourceName: String) {
        val url get() = "$BASE/$path"
        val label get() = L.t(en, hi)
    }

    val packs = listOf(
        Pack("All of India (every language)", "Poora India (sab bhashayein)", "countries/in.m3u", "Free TV India"),
        Pack("Hindi", "Hindi", "languages/hin.m3u", "Free TV Hindi"),
        Pack("Punjabi", "Punjabi", "languages/pan.m3u", "Free TV Punjabi"),
        Pack("Marathi", "Marathi", "languages/mar.m3u", "Free TV Marathi"),
        Pack("Gujarati", "Gujarati", "languages/guj.m3u", "Free TV Gujarati"),
        Pack("Bengali", "Bangla", "languages/ben.m3u", "Free TV Bengali"),
        Pack("Tamil", "Tamil", "languages/tam.m3u", "Free TV Tamil"),
        Pack("Telugu", "Telugu", "languages/tel.m3u", "Free TV Telugu"),
        Pack("Kannada", "Kannada", "languages/kan.m3u", "Free TV Kannada"),
        Pack("Malayalam", "Malayalam", "languages/mal.m3u", "Free TV Malayalam"),
        Pack("Urdu", "Urdu", "languages/urd.m3u", "Free TV Urdu"),
    )

    /** India + Hindi: what most owners want, with no typing. */
    val quickStart = packs.take(2)

    /** Adds the packs not already added; returns how many were new. One refresh at the end. */
    fun add(repo: Repository, chosen: List<Pack>): Int {
        val have = repo.sources.value.map { it.first.url }.toSet()
        val fresh = chosen.filter { it.url !in have }
        fresh.forEachIndexed { i, p ->
            repo.saveSource(SourceConfig(type = SourceType.M3U, name = p.sourceName, url = p.url), refresh = i == fresh.lastIndex)
        }
        return fresh.size
    }

    /** A D-pad friendly tick list of languages; India and Hindi start ticked. */
    fun chooser(a: Activity, repo: Repository, onDone: () -> Unit = {}) {
        val have = repo.sources.value.map { it.first.url }.toSet()
        val checked = BooleanArray(packs.size) { i -> packs[i].url in have || packs[i] in quickStart }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Free channels: pick languages", "Free channels: bhasha chunein"))
            .setMultiChoiceItems(packs.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(L.t("Add", "Jodein")) { _, _ ->
                val n = add(repo, packs.filterIndexed { i, _ -> checked[i] })
                Toast.makeText(a, if (n == 0) L.t("Already added", "Pehle se jude hain") else L.t("Adding $n lists… channels appear in a moment", "$n list jud rahi hain… channels thodi der mein aayenge"), Toast.LENGTH_LONG).show()
                onDone()
            }
            .setNegativeButton(L.t("Cancel", "Rehne dein"), null)
            .show()
    }
}
