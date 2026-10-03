package com.aiusage.monitor.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class HolidayStore {

    /**
     * The preference file holding the holiday cache.
     *
     * <p>Still the upstream file name on purpose: the cached dates and the
     * "last checked" timestamps live there, and moving them would throw away a
     * cache the user already paid for. The holiday rules are not part of the
     * account/credential model, so they stay where they are.
     */
    public static final String PREFS_NAME = "deepseek_balance";

    private static final String PREF_PREFIX = "holiday_dates_";
    private static final Set<String> FALLBACK_2026 = new HashSet<>(Arrays.asList(
            "2026-01-01", "2026-01-02", "2026-01-03",
            "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18",
            "2026-02-19", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
            "2026-04-04", "2026-04-05", "2026-04-06",
            "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
            "2026-06-19", "2026-06-20", "2026-06-21",
            "2026-09-25", "2026-09-26", "2026-09-27",
            "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
            "2026-10-05", "2026-10-06", "2026-10-07"
    ));

    private HolidayStore() {
    }

    public static Set<String> getOffDays(Context context, int year) {
        Set<String> result = new HashSet<>();
        if (year == 2026) {
            result.addAll(FALLBACK_2026);
        }

        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        String raw = preferences.getString(PREF_PREFIX + year, "");
        if (raw.isEmpty()) {
            return result;
        }
        try {
            JSONArray dates = new JSONArray(raw);
            for (int index = 0; index < dates.length(); index++) {
                String date = dates.optString(index, "").trim();
                if (!date.isEmpty()) {
                    result.add(date);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static void saveOffDays(Context context, int year, JSONArray dates) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_PREFIX + year, dates == null ? "[]" : dates.toString())
                .apply();
    }
}
