package com.aiusage.monitor.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny key/value store over the {@code app_meta} table.
 *
 * <p>Settings that are genuinely global — the background refresh interval, the
 * "this migration already ran" flag — live here instead of in
 * {@code SharedPreferences}, so that all persistent state has one home and one
 * backup story. Spec §28 and §42.
 */
public final class AppSettings {

    /** Foreground refresh interval chosen by the user, in milliseconds. */
    public static final String KEY_FOREGROUND_REFRESH_INTERVAL = "refresh_interval_ms";

    /** Provider-specific foreground intervals; the unsuffixed key is the legacy fallback. */
    public static final String KEY_DEEPSEEK_REFRESH_INTERVAL = "refresh_interval_ms.deepseek";
    public static final String KEY_BRIDGE_REFRESH_INTERVAL = "refresh_interval_ms.bridge";

    /** Background refresh interval chosen by the user, in milliseconds. */
    public static final String KEY_BACKGROUND_REFRESH_INTERVAL = "widget_refresh_interval_ms";

    /** The interval used when the user has never chosen one. */
    public static final long DEFAULT_BACKGROUND_REFRESH_INTERVAL_MS = 900000L;

    private final Database database;

    public AppSettings(Context context) {
        this.database = Database.get(context);
    }

    public long getLong(String key, long fallback) {
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_META, new String[]{"value"}, "key = ?",
                new String[]{key}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return fallback;
            }
            try {
                return Long.parseLong(cursor.getString(0));
            } catch (NumberFormatException exception) {
                return fallback;
            }
        }
    }

    public void setLong(String key, long value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", String.valueOf(value));
        // A -1 return is SQLite saying "nothing was written" without throwing, and a
        // setting that silently did not land is read back as the old value forever —
        // the same shape the bridges table was fixed for. See StorageWriteReportTest.
        long written = database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_META, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        if (written < 0) {
            throw new IllegalStateException("app_meta refused the row for " + key);
        }
    }

    public String getString(String key, String fallback) {
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_META, new String[]{"value"}, "key = ?",
                new String[]{key}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : fallback;
        }
    }

    public void setString(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        long written = database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_META, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        if (written < 0) {
            throw new IllegalStateException("app_meta refused the row for " + key);
        }
    }

    /** The background refresh interval, with the upstream default. */
    public long backgroundRefreshIntervalMs() {
        long stored = getLong(KEY_BACKGROUND_REFRESH_INTERVAL,
                DEFAULT_BACKGROUND_REFRESH_INTERVAL_MS);
        return com.aiusage.monitor.refresh.RefreshIntervalOptions.normalizeBackground(stored);
    }

    /** Every key currently stored, for diagnostics. */
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_META, new String[]{"key"}, null, null, null, null, "key ASC")) {
            while (cursor.moveToNext()) {
                keys.add(cursor.getString(0));
            }
        }
        return keys;
    }
}
