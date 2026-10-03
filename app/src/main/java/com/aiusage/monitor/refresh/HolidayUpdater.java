package com.aiusage.monitor.refresh;

import android.content.Context;
import android.content.SharedPreferences;

import com.aiusage.monitor.storage.HolidayStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HolidayUpdater {
    private static final long CHECK_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onFinished(boolean changed);
    }

    private HolidayUpdater() {
    }

    public static void updateIfNeeded(Context context, Callback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            boolean changed = false;
            try {
                Calendar now = Calendar.getInstance(BEIJING);
                int currentYear = now.get(Calendar.YEAR);
                List<Integer> years = new ArrayList<>();
                years.add(currentYear);
                if (now.get(Calendar.MONTH) >= Calendar.OCTOBER) {
                    years.add(currentYear + 1);
                }

                SharedPreferences preferences = appContext.getSharedPreferences(
                        HolidayStore.PREFS_NAME, Context.MODE_PRIVATE);
                long nowMillis = System.currentTimeMillis();
                for (int year : years) {
                    long lastCheck = preferences.getLong(lastCheckKey(year), 0L);
                    if (nowMillis - lastCheck < CHECK_INTERVAL_MS) {
                        continue;
                    }
                    JSONArray dates = fetchYear(year);
                    preferences.edit().putLong(lastCheckKey(year), nowMillis).apply();
                    if (dates != null) {
                        HolidayStore.saveOffDays(appContext, year, dates);
                        changed = changed || dates.length() > 0;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (callback != null) {
                    callback.onFinished(changed);
                }
            }
        });
    }

    private static JSONArray fetchYear(int year) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/" + year + ".json");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) {
                return null;
            }

            JSONObject root = new JSONObject(readText(connection.getInputStream()));
            JSONArray days = root.optJSONArray("days");
            if (days == null) {
                return null;
            }
            JSONArray offDays = new JSONArray();
            for (int index = 0; index < days.length(); index++) {
                JSONObject day = days.optJSONObject(index);
                if (day != null && day.optBoolean("isOffDay", false)) {
                    String date = day.optString("date", "").trim();
                    if (!date.isEmpty()) {
                        offDays.put(date);
                    }
                }
            }
            return offDays;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readText(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (InputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String lastCheckKey(int year) {
        return "holiday_last_check_" + year;
    }
}
