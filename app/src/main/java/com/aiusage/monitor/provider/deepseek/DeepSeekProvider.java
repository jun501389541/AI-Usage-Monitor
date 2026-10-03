package com.aiusage.monitor.provider.deepseek;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.util.Http;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * DeepSeek, as a provider rather than as the application.
 *
 * <p>Everything DeepSeek-specific lives in this package. The rest of the app
 * talks to {@link UsageProvider} and never learns that DeepSeek exists, which is
 * what lets a second platform be added by writing one class and registering it.
 * Spec §5, §13.
 *
 * <p>Behaviour is a faithful port of the upstream app's inline HTTP and JSON
 * code, including its timeouts, its headers and its error text, so that this
 * refactor introduces no visible regression. Spec §53 rule 6.
 *
 * <p>Note that this provider has no notion of "the user's key" or "the saved
 * account": it receives an {@link Account} and an {@link AuthContext} and
 * returns data. Adding a third DeepSeek account is therefore a data change, not
 * a code change. Spec §14.
 */
public final class DeepSeekProvider implements UsageProvider {

    public static final String ID = "deepseek";

    /** Upstream {@code MainActivity.java:54}. */
    public static final String BALANCE_URL = "https://api.deepseek.com/user/balance";

    /** Upstream {@code MainActivity.java:55}. */
    public static final String PLATFORM_URL = "https://platform.deepseek.com/";

    /** The only currency this provider reports today. */
    public static final String CURRENCY = "CNY";

    /**
     * Upstream used 12000/15000 for the foreground request
     * ({@code MainActivity.java:425-426}) and 5000/5000 for the background one
     * ({@code WidgetRefreshReceiver.java:74-75}). The more generous pair is used
     * here so that the unified path cannot time out earlier than the old
     * foreground path did.
     */
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 15000;

    /**
     * Upstream {@code MainActivity.java:424}. Kept verbatim because it identifies
     * the client to the platform.
     */
    private static final String USER_AGENT = "DeepSeekBalance/1.0 Android";

    private static final ProviderCapabilities CAPABILITIES = ProviderCapabilities.builder()
            .supports(AuthType.API_KEY)
            .reportsBalance(true)
            .reportsQuotaWindows(false)
            .reportsMetrics(true)
            // Spec §34's widget-facing list for DeepSeek. The ids are literals here
            // because a provider may not import the widget layer; the dependency
            // direction is widget → provider, and DeepSeekProviderIdContractTest
            // pins these two strings against WidgetMetricId so the two spellings
            // cannot drift apart.
            //
            // today_usage is declared here but not returned by the API: DeepSeek
            // has no per-key usage endpoint, so the widget reads the locally
            // accumulated estimate. Spec §34 lists it as a DeepSeek metric, and the
            // README's 今日用量口径 says plainly that it is an estimate.
            .widgetMetric("balance", "余额")
            .widgetMetric("today_usage", "今日用量")
            // Spec §43: DeepSeek's balance changes slowly; 30 minutes is plenty and
            // matches the recommended background interval in §42.
            .recommendedRefreshIntervalMs(30 * 60 * 1000L)
            .build();

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "DeepSeek";
    }

    @Override
    public List<AuthType> getSupportedAuthTypes() {
        return Arrays.asList(AuthType.API_KEY);
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public UsageResult fetchUsage(Account account, AuthContext authContext) throws UsageException {
        if (authContext == null || !authContext.has(AuthContext.KEY_API_KEY)) {
            throw new UsageException(UsageError.INVALID_CREDENTIAL, "缺少 API Key");
        }
        String apiKey = authContext.get(AuthContext.KEY_API_KEY).trim();
        if (apiKey.isEmpty()) {
            throw new UsageException(UsageError.INVALID_CREDENTIAL, "缺少 API Key");
        }

        Http.Response response;
        try {
            response = Http.get(
                    BALANCE_URL,
                    "Authorization",
                    "Bearer " + apiKey,
                    CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS);
        } catch (IOException exception) {
            // Upstream mapped IOException to this exact message
            // (MainActivity.java:452-453).
            throw new UsageException(UsageError.NETWORK_ERROR, UsageError.NETWORK_ERROR.getMessage(), exception);
        }

        int code = response.getCode();
        if (code != 200) {
            throw httpFailure(code);
        }

        UsageResult parsed = DeepSeekBalanceParser.parse(account.getId(), response.getBody());
        return parsed.toBuilder()
                .providerId(ID)
                .updatedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * Mirrors upstream status-code handling ({@code MainActivity.java:438-447}),
     * which showed a dedicated message for 401, 403 and 429 and a generic
     * {@code 请求失败（HTTP n）} otherwise.
     */
    private static UsageException httpFailure(int code) {
        UsageError error = UsageError.fromHttpStatus(code);
        if (error == UsageError.UNKNOWN) {
            return new UsageException(error, "请求失败（HTTP " + code + "）");
        }
        return new UsageException(error, error.getMessage());
    }
}
