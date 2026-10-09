package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Maps the experimental Wham response without assigning fixed meanings to windows. */
public final class CodexDirectUsageParser {
    private CodexDirectUsageParser() { }

    public static UsageResult parse(JSONObject body, String accountId, long nowMs)
            throws JSONException {
        JSONObject root = body == null ? new JSONObject() : body;
        JSONObject rateLimit = root.optJSONObject("rate_limit");
        if (rateLimit == null) rateLimit = root.optJSONObject("rateLimit");
        List<QuotaWindow> windows = new ArrayList<>();
        if (rateLimit != null) {
            Iterator<String> keys = rateLimit.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject value = rateLimit.optJSONObject(key);
                if (value == null) continue;
                double used = number(value, "used_percent", "usedPercent");
                double remaining = number(value, "remaining_percent", "remainingPercent");
                if (Double.isNaN(used) && Double.isNaN(remaining)) continue;
                if (Double.isNaN(remaining) && !Double.isNaN(used)) {
                    remaining = Math.max(0d, 100d - used);
                }
                if (Double.isNaN(used) && !Double.isNaN(remaining)) {
                    used = Math.max(0d, 100d - remaining);
                }
                long seconds = longNumber(value, "limit_window_seconds", "limitWindowSeconds");
                long resetAt = resetAtMs(value.opt("reset_at"));
                windows.add(new QuotaWindow(key, labelFor(key), used, remaining,
                        seconds > 0L ? seconds / 60L : 0L, resetAt));
            }
        }
        return UsageResult.builder()
                .accountId(accountId)
                .providerId(CodexProvider.ID)
                .quotaWindows(windows)
                .updatedAt(nowMs)
                .source(UsageResult.Source.DIRECT_API)
                .build();
    }

    private static double number(JSONObject object, String first, String second) {
        Object value = object.has(first) ? object.opt(first) : object.opt(second);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble((String) value); }
            catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static long longNumber(JSONObject object, String first, String second) {
        Object value = object.has(first) ? object.opt(first) : object.opt(second);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); }
            catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    private static long resetAtMs(Object value) {
        long raw;
        if (value instanceof Number) raw = ((Number) value).longValue();
        else if (value instanceof String) {
            try { raw = Long.parseLong((String) value); }
            catch (NumberFormatException ignored) { return 0L; }
        } else return 0L;
        // Epoch seconds are currently the upstream shape; accepting millisecond
        // values keeps a future response change from displaying a 1970 reset.
        return raw > 0L && raw < 100_000_000_000L ? raw * 1000L : Math.max(0L, raw);
    }

    private static String labelFor(String key) {
        if ("primary_window".equals(key)) return "主额度窗口";
        if ("secondary_window".equals(key)) return "次额度窗口";
        return "用量窗口 · " + key;
    }
}
