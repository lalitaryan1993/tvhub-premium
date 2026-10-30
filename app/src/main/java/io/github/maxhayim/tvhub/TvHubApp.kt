package io.github.maxhayim.tvhub

import android.app.Application
import io.github.maxhayim.tvhub.data.Repository
import io.github.maxhayim.tvhub.ui.LogoLoader

class TvHubApp : Application() {
    lateinit var repo: Repository; private set
    lateinit var logos: LogoLoader; private set

    override fun onCreate() {
        super.onCreate()
        io.github.maxhayim.tvhub.ui.L.init(this)
        repo = Repository(this)
        logos = LogoLoader(this)
        repo.load()
    }
}
