package io.github.maxhayim.tvhub.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import io.github.maxhayim.tvhub.TvHubApp
import io.github.maxhayim.tvhub.model.SourceType
import io.github.maxhayim.tvhub.ui.Ui.dp

/** First launch: pick any sources to add now, or skip. Every step is optional and can be done later in Sources. */
class OnboardingActivity : Activity() {
    private val repo get() = (application as TvHubApp).repo
    private val buttons = LinkedHashMap<SourceType, TextView>()
    private val labels = linkedMapOf(
        SourceType.M3U to L.t("Add my own M3U playlist", "Apni M3U playlist jodein"),
        SourceType.XMLTV to L.t("Add an XMLTV guide", "XMLTV guide jodein"),
        SourceType.DISPATCHARR to "Connect Dispatcharr",
        SourceType.HDHOMERUN to L.t("Find an HDHomeRun tuner", "HDHomeRun tuner dhoondein"),
        SourceType.APPS to L.t("Choose streaming apps", "Streaming apps chunein"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.vertical(this, 12).apply { setPadding(dp(64), dp(36), dp(64), dp(28)); clipChildren = false; clipToPadding = false }
        col.addView(Ui.text(this, "PREMIUM", 10f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.3f })
        col.addView(Ui.text(this, L.t("Welcome to TV Hub", "TV Hub mein swagat hai"), 26f, bold = true))
        col.addView(Ui.text(this, L.t("Live TV with no typing: one click adds free public channels. Or add your own sources below; everything can be changed later under Sources.",
            "Bina kuch type kiye live TV: ek click mein free channels jud jayenge. Ya neeche apne sources jodein; baad mein Sources mein sab badal sakte hain."), 13f, Ui.DIM, lines = 0))
        val quick = Ui.primaryButton(this, L.t("★  Add free Indian channels (1 click)", "★  Free Indian channels jodein (1 click)")) {
            FreeTv.add(repo, FreeTv.quickStart); finishSetup()
        }
        col.addView(quick, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8) })
        col.addView(Ui.button(this, L.t("Choose languages…", "Bhasha chunein…")) { FreeTv.chooser(this, repo) { finishSetup() } }, Ui.lp(dp(400), Ui.WRAP))
        col.addView(Ui.text(this, L.t("OR ADD YOUR OWN", "YA APNE SOURCES"), 10f, Ui.DIM, bold = true).apply { letterSpacing = 0.2f; setPadding(0, dp(10), 0, 0) })
        val list = Ui.vertical(this, 8)
        labels.forEach { (t, l) ->
            val b = Ui.button(this, l) { startActivityForResult(SourceEditorActivity.intent(this, t, 0), 1) }
            b.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            buttons[t] = b
            list.addView(b, Ui.lp(dp(400), Ui.WRAP))
        }
        col.addView(list)
        val done = Ui.button(this, L.t("Skip for now", "Abhi nahi")) { finishSetup() }
        col.addView(done, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { background = Ui.backdrop(); addView(col) })
        quick.requestFocus()
        doneButton = done
    }

    private var doneButton: TextView? = null

    @Deprecated("Activity result API needs androidx.activity; the platform call is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val t = data?.getStringExtra(SourceEditorActivity.EXTRA_TYPE)?.let { SourceType.valueOf(it) } ?: return
        buttons[t]?.let { it.text = "✓  " + labels[t]; it.setTextColor(Ui.OK) }
        doneButton?.text = L.t("Done", "Ho gaya")
        doneButton?.requestFocus()
    }

    @Deprecated("Back is handled here so skipping setup still marks it done")
    override fun onBackPressed() { finishSetup() }

    private fun finishSetup() {
        repo.prefs.onboarded = true
        repo.load()
        finish()
    }
}
