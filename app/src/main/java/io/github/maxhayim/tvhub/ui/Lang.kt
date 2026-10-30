package io.github.maxhayim.tvhub.ui

import android.content.Context

/**
 * The app's two languages: English (the default) and Hinglish, switched in Settings. Strings sit
 * in the code as t("English", "Hinglish") pairs, as the rest of the UI is built in code too.
 */
object L {
    @Volatile var hinglish = false; private set

    fun init(c: Context) { hinglish = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("hinglish", false) }

    fun set(c: Context, on: Boolean) {
        hinglish = on
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("hinglish", on).apply()
    }

    fun t(en: String, hi: String) = if (hinglish) hi else en
}
