package `in`.maitri.app

import android.app.Application

/** Sets up app-wide text translation before any screen, worker or notification runs. */
class MaitriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
    }
}
