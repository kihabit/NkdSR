package com.nkd.screenrecorder.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class DBHelper extends SQLiteOpenHelper {

    // Database name and version
    private static final String DATABASE_NAME = "app_db";
    private static final int DATABASE_VERSION = 1;

    // Table name
    private static final String TABLE_CHECKRATEUS = "checkrateus";

    // Table columns
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_RATING = "rating";
    private static final String COLUMN_CREATED = "created";

    public DBHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create table if not exists
        String createTable = "CREATE TABLE IF NOT EXISTS " + TABLE_CHECKRATEUS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_RATING + " INTEGER , " + COLUMN_CREATED + " TEXT )";
        db.execSQL(createTable);
    }
    // Check if rating exists
    public boolean isRatingExists() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_CHECKRATEUS + " LIMIT 1", null);
        boolean exists = cursor.moveToFirst();
        cursor.close();
        db.close();
        return exists;
    }
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop and recreate if database version is updated
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHECKRATEUS);
        onCreate(db);
    }

    // Insert rating
    public void insertRating(int rating) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_RATING, rating);
        values.put(COLUMN_CREATED, getCurrentDateTime()); // Save current datetime
        db.insert(TABLE_CHECKRATEUS, null, values);
        db.close();
    }

    // Update rating (only updates first row)
    public void updateRating(int newRating) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_RATING, newRating);
        values.put(COLUMN_CREATED, getCurrentDateTime()); // Update datetime

        db.update(TABLE_CHECKRATEUS, values, COLUMN_ID + " = ?", new String[]{"1"});
        db.close();
    }
    private String getCurrentDateTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        Log.d("current date", sdf.format(new java.util.Date()));
        return sdf.format(new java.util.Date());
    }
    // Get saved rating
    public int getRating() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + COLUMN_RATING + " FROM " + TABLE_CHECKRATEUS + " LIMIT 1", null);
        int rating = -1;
        if (cursor.moveToFirst()) {
            rating = cursor.getInt(0);
        }
        cursor.close();
        db.close();
        return rating;
    }
    public String getCreated() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + COLUMN_CREATED + " FROM " + TABLE_CHECKRATEUS + " LIMIT 1", null);
        String created = null;
        if (cursor.moveToFirst()) {
            created = cursor.getString(0); // <-- FIXED (use String, not Int)
        }
        cursor.close();
        db.close();
        return created;
    }
}

