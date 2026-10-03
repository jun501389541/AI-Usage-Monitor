package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.Metric;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.UsageException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turns a Bridge {@code /v1/accounts/codex/usage} document into a
 * {@link UsageResult}.
 *
 * <p>Pure and static so the mapping rules can be tested on a plain JVM, the way
 * {@code DeepSeekBalanceParser} is. The Bridge payload is the Android side's only
 * view of Codex, and two of its properties are unlike DeepSeek's:
 *
 * <ul>
 *   <li>there is <strong>no balance at all</strong> - {@code getBalance()} stays
 *       {@code null} and callers must not turn that into a number;</li>
 *   <li>the reading can be <strong>deliberately stale</strong>: when a refresh
 *       fails the Bridge still answers 200 with its last good windows plus a
 *       {@code degraded} marker, because losing the numbers on screen is the
 *       failure Spec §53 rule 18 forbids. That maps to {@link UsageStatus#STALE}
 *       with the numbers kept, not to an error.</li>
 * </ul>
 *
 * <p>Expected shape (docs/PHASE-6-PLAN.md §0.3):
 *
 * <pre>
 * { "state": { "windows": [ { "id", "label", "usedPercent", "remainingPercent",
 *                             "windowMinutes", "resetAtMillis" } ],
 *              "lastFailure": { "class", "message", "at" } },
 *   "source": "codex" | "cache", "degraded": { "class": "CODEX_NOT_FOUND", … } }
 * </pre>
 */
public final class BridgeUsageParser {

    /** Provider id, matching what the Bridge reports at /v1/providers. */
    public static final String PROVIDER_ID = "codex";

    private BridgeUsageParser() {
    }

    public static UsageResult parse(String body, String accountId, long nowMs) throws UsageException {
        JSONObject root = readObject(body);

        JSONObject state = root.optJSONObject("state");
        if (state == null) {
            // A document without a state block carries no reading; reporting that
            // as "available with zero windows" would invent data.
            throw new UsageException(UsageError.UNKNOWN, "Bridge 响应里没有 state 区块");
        }

        UsageResult.Builder builder = UsageResult.builder()
                .accountId(accountId)
                .providerId(PROVIDER_ID)
                // No balance for a quota-only account. Explicitly not set, so a
                // later refactor cannot mistake an empty Balance for a missing one.
                .status(statusFor(root, state))
                .updatedAt(nowMs)
                .source(UsageResult.Source.BRIDGE);

        JSONArray windows = state.optJSONArray("windows");
        int accepted = 0;
        if (windows != null) {
            for (int i = 0; i < windows.length(); i++) {
                JSONObject raw = windows.optJSONObject(i);
                if (raw == null || !raw.has("usedPercent")) {
                    // usedPercent is the one field the Bridge's own schema marks
                    // required; without it the meter has no value to show, and a
                    // invented 0 would read as "nothing used".
                    continue;
                }
                accepted++;
                double used = raw.optDouble("usedPercent", 0d);
                double remaining = raw.has("remainingPercent")
                        ? raw.optDouble("remainingPercent", 100d - used)
                        : 100d - used;
                builder.addQuotaWindow(new QuotaWindow(
                        raw.optString("id", ""),
                        raw.optString("label", ""),
                        used,
                        remaining,
                        raw.optLong("windowMinutes", 0L),
                        raw.optLong("resetAtMillis", 0L)));
            }
        }
        if (accepted == 0) {
            throw new UsageException(UsageError.UNKNOWN, "Bridge 响应里没有可用的额度窗口");
        }

        // The Bridge answers 200 with a window list only when Codex did answer at
        // some point, which is what "available" means for this provider. DeepSeek's
        // screen reads the flag off this metric, and its default is "unavailable",
        // so a result that omits it would show 账户不可用 for a working account.
        builder.addMetric(new Metric(
                UsageResult.METRIC_ACCOUNT_AVAILABLE, "账户可用", 1d, ""));

        return builder.build();
    }

    /**
     * A {@code degraded} marker means the numbers are real but older than the
     * caller asked for. Stale, not failed.
     */
    private static UsageStatus statusFor(JSONObject root, JSONObject state) {
        JSONObject degraded = root.optJSONObject("degraded");
        if (degraded == null) {
            degraded = state.optJSONObject("lastFailure");
        }
        return degraded == null ? UsageStatus.OK : UsageStatus.STALE;
    }

    private static JSONObject readObject(String body) throws UsageException {
        if (body == null || body.trim().isEmpty()) {
            throw new UsageException(UsageError.UNKNOWN, "Bridge 返回了空响应");
        }
        try {
            return new JSONObject(body);
        } catch (JSONException exception) {
            throw new UsageException(UsageError.UNKNOWN, "Bridge 响应不是合法 JSON", exception);
        }
    }
}
