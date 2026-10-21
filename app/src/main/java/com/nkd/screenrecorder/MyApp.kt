package com.nkd.screenrecorder

import android.app.Application
import android.content.Context
import com.nkd.screenrecorder.utils.LocaleHelper

class MyApp : Application() {
    // applicationContext texts (toasts) follow the language chosen in the app
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        photoApp = this
    }

    companion object {
        var photoApp: MyApp? = null
            private set
        private val TAG = MyApp::class.java.simpleName
    }
}
