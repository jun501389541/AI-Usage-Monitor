package com.aiusage.monitor.usage;

import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Metric;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Serialises a {@link UsageResult} to and from the {@code usage_data} column.
 *
 * <p>Pure string work with no Android dependency, so it is fully testable on a
 * plain JVM — which matters because a silent round-trip bug here would corrupt
 * every stored snapshot and the widget's cached fallback.
 *
 * <p>Decoding is deliberately forgiving: an unreadable payload returns null
 * rather than throwing, because the caller's fallback is to show the last known
 * good data and a corrupt row must not crash a widget update. Spec §39.
 */
public final class UsageSnapshotCodec {

    private UsageSnapshotCodec() {
    }

    /** Encodes a result. Never returns null; falls back to a minimal object. */
    public static String encode(UsageResult result) {
        if (result == null) {
            return "{}";
        }
        try {
            JSONObject root = new JSONObject();
            root.put("accountId", result.getAccountId());
            root.put("providerId", result.getProviderId());
            root.put("status", result.getStatus().name());
            root.put("updatedAt", result.getUpdatedAt());
            root.put("source", result.getSource().name());

            Balance balance = result.getBalance();
            if (balance != null) {
                JSONObject json = new JSONObject();
                json.put("amount", balance.getAmount());
                json.put("currency", balance.getCurrency());
                json.put("rawText", balance.getRawText());
                root.put("balance", json);
            }

            JSONArray windows = new JSONArray();
            for (QuotaWindow window : result.getQuotaWindows()) {
                JSONObject json = new JSONObject();
                json.put("id", window.getId());
                json.put("label", window.getLabel());
                json.put("usedPercent", window.getUsedPercent());
                json.put("remainingPercent", window.getRemainingPercent());
                json.put("windowMinutes", window.getWindowMinutes());
                json.put("resetAt", window.getResetAt());
                windows.put(json);
            }
            root.put("quotaWindows", windows);

            JSONArray metrics = new JSONArray();
            for (Metric metric : result.getMetrics()) {
                JSONObject json = new JSONObject();
                json.put("key", metric.getKey());
                json.put("label", metric.getLabel());
                json.put("value", metric.getValue());
                json.put("unit", metric.getUnit());
                metrics.put(json);
            }
            root.put("metrics", metrics);

            return root.toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    /** Decodes a stored payload, or null when it cannot be read. */
    public static UsageResult decode(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);

            UsageResult.Builder builder = UsageResult.builder()
                    .accountId(root.optString("accountId", ""))
                    .providerId(root.optString("providerId", ""))
                    .status(parseStatus(root.optString("status", "")))
                    .updatedAt(root.optLong("updatedAt", 0L))
                    .source(parseSource(root.optString("source", "")));

            JSONObject balance = root.optJSONObject("balance");
            if (balance != null) {
                builder.balance(new Balance(
                        balance.optDouble("amount", 0d),
                        balance.optString("currency", ""),
                        balance.optString("rawText", "")));
            }

            JSONArray windows = root.optJSONArray("quotaWindows");
            if (windows != null) {
                for (int index = 0; index < windows.length(); index++) {
                    JSONObject json2 = windows.optJSONObject(index);
                    if (json2 == null) {
                        continue;
                    }
                    builder.addQuotaWindow(new QuotaWindow(
                            json2.optString("id", ""),
                            json2.optString("label", ""),
                            json2.optDouble("usedPercent", 0d),
                            json2.optDouble("remainingPercent", 0d),
                            json2.optLong("windowMinutes", 0L),
                            json2.optLong("resetAt", 0L)));
                }
            }

            JSONArray metrics = root.optJSONArray("metrics");
            if (metrics != null) {
                for (int index = 0; index < metrics.length(); index++) {
                    JSONObject json2 = metrics.optJSONObject(index);
                    if (json2 == null) {
                        continue;
                    }
                    builder.addMetric(new Metric(
                            json2.optString("key", ""),
                            json2.optString("label", ""),
                            json2.optDouble("value", 0d),
                            json2.optString("unit", "")));
                }
            }

            return builder.build();
        } catch (JSONException exception) {
            return null;
        }
    }

    private static UsageStatus parseStatus(String raw) {
        try {
            return UsageStatus.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return UsageStatus.NO_DATA;
        }
    }

    private static UsageResult.Source parseSource(String raw) {
        try {
            return UsageResult.Source.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return UsageResult.Source.CACHE;
        }
    }
}
