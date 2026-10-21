package com.nkd.screenrecorder.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsHelper {
    private static final String COUNTER_KEY = "event_counter";

    // Get counter (default 0 if not set)
    public static int getCounter(Context context) {
        return context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE)
                .getInt(COUNTER_KEY, 0);
    }

    public static int getDateDiff(String created) {
        if (created == null) return 0;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            java.util.Date createdDate = sdf.parse(created);
            java.util.Date now = new java.util.Date();

            if (createdDate == null) return 0;

            long diff = now.getTime() - createdDate.getTime();
            return (int) (diff / (24 * 60 * 60 * 1000)); // Days difference
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // Increment counter by 1
    public static void incrementCounter(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE);
        int current = prefs.getInt(COUNTER_KEY, 0);
        prefs.edit().putInt(COUNTER_KEY, current + 1).apply();
    }

    // Reset counter (optional)
    public static void resetCounter(Context context) {
        context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE)
                .edit()
                .putInt(COUNTER_KEY, 0)
                .apply();
    }
}
