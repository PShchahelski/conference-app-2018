package io.github.droidkaigi.confsched2018.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.support.multidex.MultiDexApplication
import android.support.text.emoji.EmojiCompat
import android.support.text.emoji.FontRequestEmojiCompatConfig
import android.support.v4.provider.FontRequest
import android.support.v7.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.jakewharton.threetenabp.AndroidThreeTen
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import io.github.droidkaigi.confsched2018.R
import io.github.droidkaigi.confsched2018.di.initDaggerComponent
import io.github.droidkaigi.confsched2018.presentation.common.notification.NotificationHelper
import timber.log.Timber
import uk.co.chrisjenx.calligraphy.CalligraphyConfig
import javax.inject.Inject

@SuppressLint("Registered")
open class App : MultiDexApplication(), HasActivityInjector {
    @Inject lateinit var dispatchingAndroidInjector: DispatchingAndroidInjector<Activity>

    override fun onCreate() {
        super.onCreate()
        setupFirebase()
        setupVectorDrawable()
        setupThreeTenABP()
        setupDagger()
        setupCalligraphy()
        setupEmoji()
        setupNotification()
    }

    private fun setupFirebase() {
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            val fireStore = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                    // https://github.com/DroidKaigi/conference-app-2018/issues/277#issuecomment-360171780
                    .setPersistenceEnabled(false)
                    .build()
            fireStore.firestoreSettings = settings
        }
    }

    private fun setupVectorDrawable() {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    }

    private fun setupThreeTenABP() {
        if (!isInUnitTests()) {
            AndroidThreeTen.init(this)
        }
    }

    open fun setupDagger() {
        initDaggerComponent()
    }

    private fun setupCalligraphy() {
        CalligraphyConfig.initDefault(CalligraphyConfig.Builder()
                .setDefaultFont(R.font.notosans_medium)
                .build())
    }

    private fun setupEmoji() {
        val fontRequest = FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                "Noto Color Emoji Compat",
                R.array.com_google_android_gms_fonts_certs)
        val config = FontRequestEmojiCompatConfig(applicationContext, fontRequest)
                .setReplaceAll(true)
                .registerInitCallback(object : EmojiCompat.InitCallback() {
                    override fun onInitialized() {
                        Timber.i("EmojiCompat initialized")
                    }

                    override fun onFailed(throwable: Throwable?) {
                        Timber.e(throwable, "EmojiCompat initialization failed")
                    }
                })
        EmojiCompat.init(config)
    }

    private fun setupNotification() {
        NotificationHelper.initNotificationChannel(this)
    }

    override fun activityInjector(): DispatchingAndroidInjector<Activity> =
            dispatchingAndroidInjector

    protected open fun isInUnitTests() = false
}
