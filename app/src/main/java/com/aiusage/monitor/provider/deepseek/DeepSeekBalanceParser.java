package com.aiusage.monitor.provider.deepseek;

import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Metric;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.UsageException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns a DeepSeek {@code /user/balance} response into a {@link UsageResult}.
 *
 * <p>Split out from {@link DeepSeekProvider} so the parsing rules can be tested
 * on a plain JVM with no Android and no network. Every branch here mirrors a
 * branch the upstream single-activity app had inline, including the exact
 * user-facing error text, so that introducing the provider layer cannot change
 * what a user sees.
 *
 * <p>Upstream response shape:
 *
 * <pre>
 * {
 *   "is_available": true,
 *   "balance_infos": [
 *     { "currency": "CNY", "total_balance": "38.52", … }
 *   ]
 * }
 * </pre>
 */
final class DeepSeekBalanceParser {

    /** Metric key carrying DeepSeek's {@code is_available} flag. */
    static final String METRIC_IS_AVAILABLE = UsageResult.METRIC_ACCOUNT_AVAILABLE;

    private DeepSeekBalanceParser() {
    }

    /**
     * Parses a balance response.
     *
     * @throws UsageException with {@link UsageError#UNKNOWN} when the body is not
     *                        valid JSON, or when it contains no CNY entry. The
     *                        upstream app surfaced both as "数据解析失败，请稍后重试",
     *                        and that mapping is preserved here.
     */
    static UsageResult parse(String accountId, String body) throws UsageException {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException exception) {
            throw new UsageException(UsageError.UNKNOWN, UsageError.UNKNOWN.getMessage(), exception);
        }

        boolean available = root.optBoolean(METRIC_IS_AVAILABLE, false);
        JSONArray balances = root.optJSONArray("balance_infos");

        if (balances != null) {
            for (int index = 0; index < balances.length(); index++) {
                JSONObject item = balances.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String currency = item.optString("currency", "").trim();
                if (!DeepSeekProvider.CURRENCY.equalsIgnoreCase(currency)) {
                    continue;
                }
                String total = item.optString("total_balance", "0").trim();
                return build(accountId, available, total, currency);
            }
        }

        // Upstream: throw new JSONException("未找到 CNY 余额"), surfaced to the user
        // as the generic parse-failure message.
        throw new UsageException(
                UsageError.UNKNOWN, "未找到 " + DeepSeekProvider.CURRENCY + " 余额");
    }

    private static UsageResult build(String accountId, boolean available, String total, String currency) {
        double amount;
        try {
            amount = new BigDecimal(total).setScale(2, RoundingMode.HALF_UP).doubleValue();
        } catch (NumberFormatException exception) {
            // Upstream returned "—" for an unparseable amount; 0 with the raw text
            // preserved keeps the same display while leaving a numeric value behind.
            amount = 0d;
        }

        return UsageResult.builder()
                .accountId(accountId)
                .providerId(DeepSeekProvider.ID)
                .balance(new Balance(amount, currency, total))
                .addMetric(new Metric(
                        METRIC_IS_AVAILABLE,
                        "账户可用",
                        available ? 1d : 0d,
                        ""))
                .status(com.aiusage.monitor.model.UsageStatus.OK)
                .source(UsageResult.Source.DIRECT_API)
                .build();
    }
}
