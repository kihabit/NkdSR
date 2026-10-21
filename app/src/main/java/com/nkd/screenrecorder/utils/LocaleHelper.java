package com.nkd.screenrecorder.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.preference.PreferenceManager;

import java.util.Locale;

public class LocaleHelper {

    private static final String SELECTED_LANGUAGE = "Locale.Helper.Selected.Language";
    private static final String KEY_LANGUAGE_SELECTED = "language_selected";

    /** Saves [language] and returns a context whose resources use it. */
    public static Context setLocale(Context context, String language) {
        persist(context, language);
        return wrap(context, language);
    }

    /** Returns a context using the saved app language (activities, services, dialogs, toasts). */
    public static Context wrap(Context context) {
        return wrap(context, getLanguage(context));
    }

    public static boolean isLanguageSelected(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(KEY_LANGUAGE_SELECTED, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_LANGUAGE_SELECTED, false);
    }

    public static void setLanguageSelected(Context context, boolean selected) {
        SharedPreferences prefs = context.getSharedPreferences(KEY_LANGUAGE_SELECTED, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_LANGUAGE_SELECTED, selected).apply();
    }

    public static String getLanguage(Context context) {
        String lang = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(SELECTED_LANGUAGE, Locale.getDefault().getLanguage());
        return "id".equals(lang) ? "in" : lang;
    }

    private static void persist(Context context, String language) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(SELECTED_LANGUAGE, language)
                .apply();
    }

    private static Context wrap(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        // Copy — never mutate the shared Configuration of the base context
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLocales(new LocaleList(locale));
        config.setLayoutDirection(locale);

        return context.createConfigurationContext(config);
    }
}
