package com.nkd.screenrecorder.activities

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.nkd.screenrecorder.utils.LocaleHelper

/** Every screen uses the language chosen in the app (not the phone language). */
open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
