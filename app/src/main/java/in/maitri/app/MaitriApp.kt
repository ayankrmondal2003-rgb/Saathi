package `in`.maitri.app

import android.app.Application
import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Sets up app-wide text translation and crash reporting before any screen, worker or notification runs. */
class MaitriApp : Application(), androidx.work.Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        // Off the main thread: collection stays off (manifest default) until this applies the user's choice.
        Thread({ CrashReports.apply(this) }, "crash-reports").start()
    }

    /** WorkManager starts on first use (a worker scheduled or run), not at process start: its initializer is removed. */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
}

/**
 * Crashlytics, release builds only, unless the user turned it off in Privacy & data.
 * Reports contain the stack trace, app version and phone model. No user id, email, location, contact,
 * medical note or message text is attached (we never call setUserId or add custom keys with such data).
 */
object CrashReports {
    private const val PREFS = "privacy_prefs"
    private const val KEY = "crash_reports"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        apply(context)
    }

    fun apply(context: Context) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            val on = !BuildConfig.DEBUG && enabled(context)
            crashlytics.isCrashlyticsCollectionEnabled = on
            if (!on) crashlytics.deleteUnsentReports()
        }
    }
}
