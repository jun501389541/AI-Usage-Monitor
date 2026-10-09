package com.aiusage.monitor.usage;

import com.aiusage.monitor.model.LimitResetCredits;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;

/** Shared optional Bridge/cache representation, independent of Android APIs. */
public final class LimitResetCreditsJson {
    private LimitResetCreditsJson() { }
    public static LimitResetCredits decode(JSONObject json) {
        if (json == null || !(json.opt("availableCount") instanceof Number)) return null;
        long count;
        try { count = new BigDecimal(json.opt("availableCount").toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { return null; }
        if (count < 0) return null;
        List<LimitResetCredits.Credit> credits = null;
        JSONArray rows = json.optJSONArray("credits");
        if (rows != null) {
            credits = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Long expiry = date(row.opt("expiresAt"));
                boolean known = row.has("expiresAt") && (row.isNull("expiresAt") || expiry != null);
                credits.add(new LimitResetCredits.Credit(text(row, "resetType"), text(row, "status"),
                        date(row.opt("grantedAt")), expiry, known, text(row, "title"), text(row, "description")));
            }
        }
        return new LimitResetCredits(count, credits);
    }
    public static JSONObject encode(LimitResetCredits resets) throws JSONException {
        JSONObject json = new JSONObject().put("availableCount", resets.getAvailableCount());
        if (resets.getCredits() == null) return json.put("credits", JSONObject.NULL);
        JSONArray rows = new JSONArray();
        for (LimitResetCredits.Credit credit : resets.getCredits()) {
            JSONObject row = new JSONObject().put("resetType", credit.getResetType()).put("status", credit.getStatus())
                    .put("title", credit.getTitle()).put("description", credit.getDescription());
            row.put("grantedAt", iso(credit.getGrantedAt()));
            if (credit.isExpiryKnown()) row.put("expiresAt", iso(credit.getExpiresAt()));
            rows.put(row);
        }
        return json.put("credits", rows);
    }
    private static String text(JSONObject row, String key) {
        return row.opt(key) instanceof String ? (String) row.opt(key) : null;
    }
    private static Long date(Object value) {
        if (!(value instanceof String)) return null;
        try { return Instant.parse((String) value).toEpochMilli(); }
        catch (DateTimeException | ArithmeticException invalid) { return null; }
    }
    private static Object iso(Long millis) {
        return millis == null ? JSONObject.NULL : Instant.ofEpochMilli(millis).toString();
    }
}
