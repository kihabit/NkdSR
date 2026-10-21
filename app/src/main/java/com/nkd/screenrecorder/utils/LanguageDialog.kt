package com.nkd.screenrecorder.utils

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import com.nkd.screenrecorder.R

object LanguageDialog {

    val languages = arrayOf(
        "English", "हिंदी", "Français",
        "Español", "Português", "العربية", "Bahasa Indonesia", "Русский"
    )
    val languageCodes = arrayOf("en", "hi", "fr", "es", "pt", "ar", "in", "ru")

    fun show(activity: Activity, cancelable: Boolean, onSelected: (String) -> Unit) {
        val dialog = Dialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_language, null)
        dialog.setContentView(view)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)   // Settings: outside tap closes, no change

        val listView = view.findViewById<ListView>(R.id.languageListView)
        listView.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_list_item_single_choice,
            languages
        )
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        // Current language pre-selected; tapping a row only moves the radio check
        val index = languageCodes.indexOf(LocaleHelper.getLanguage(activity))
        listView.setItemChecked(if (index != -1) index else 0, true)

        // OK applies the checked language (cancel / back from Settings changes nothing)
        view.findViewById<View>(R.id.btnLanguageOk).setOnClickListener {
            val position = listView.checkedItemPosition.takeIf { it != ListView.INVALID_POSITION } ?: 0
            val code = languageCodes[position]
            LocaleHelper.setLocale(activity, code)
            LocaleHelper.setLanguageSelected(activity, true)
            dialog.dismiss()
            onSelected(code)
        }

        dialog.show()
        dialog.window?.apply {
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
}