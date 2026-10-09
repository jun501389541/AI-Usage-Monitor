package com.aiusage.monitor.widget;

import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.provider.codex.CodexProvider;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.util.Freshness;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.StatusWords;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a widget's slots into the text its rows should show. Spec §29–§41.
 *
 * <p>This is the widget layer's only place where a value, a status and an age
 * become one sentence, so it is where the spec's display rules are actually
 * enforced:
 *
 * <ul>
 *   <li>the number comes from the last <em>successful</em> reading and a failed
 *       refresh never replaces it (§39) — the newest attempt only chooses the
 *       words beside it;</li>
 *   <li>every row says how old it is (§41), because a quietly stale balance is a
 *       wrong balance;</li>
 *   <li>a deleted account, a disabled account, an account never queried and a
 *       metric the provider does not offer each get their own explicit wording
 *       rather than an em dash the user has to interpret.</li>
 * </ul>
 *
 * <p>Android-free by construction, and it reads through {@link DataSource}
 * rather than holding repositories: the widget must not call a provider, and it
 * must not be able to. Spec §53 rules 8 and 9.
 */
public final class WidgetSlotResolver {

    /** Shown when a slot asks for a metric its account's provider does not offer. */
    static final String UNSUPPORTED_METRIC = "不支持该指标";

    /** Slot bound to an account id that no longer exists. */
    static final String DELETED_ACCOUNT = "账户已删除";

    /** A widget with no binding at all — the state the fallback exists to cover. */
    static final String UNCONFIGURED = "未配置账户";

    private static final String RECONFIGURE_HINT = "请重新配置该 Slot";
    private static final String DISABLED_SUFFIX = "（已停用）";
    private static final String SEPARATOR = " · ";

    private WidgetSlotResolver() {
    }

    /** Everything the resolver reads, supplied by the widget layer. */
    public interface DataSource {

        /** The account behind a slot, or null when it has been deleted. */
        Account findAccount(String accountId);

        /** The account an unbound widget falls back to, or null when there is none. */
        Account fallbackAccount();

        /** Last success plus newest attempt, the pair the display needs. */
        AccountRefreshManager.AccountView view(String accountId);

        /** Today's accumulated spend, the local estimate behind {@code today_usage}. */
        BigDecimal dailyUsage(String accountId);

        /** The provider's declarations, or null when its id is unknown. */
        ProviderCapabilities capabilities(String providerId);

        /** The interval staleness is measured against — the same one the list uses. */
        long refreshIntervalMs();
    }

    /**
     * The views for every slot of one widget, in slot order.
     *
     * <p>An unconfigured widget yields exactly one view built from the fallback
     * account rather than no views: a widget that has just been placed, or one
     * whose binding was lost, must still show the user something recognisable.
     *
     * @param nowMs the instant age is judged against, passed in so the midnight
     *              and staleness boundaries are testable without waiting for them
     */
    public static List<WidgetSlotView> resolve(WidgetConfig config, DataSource data, long nowMs) {
        List<WidgetSlotView> views = new ArrayList<>();
        if (config == null || config.getSlots().isEmpty()) {
            views.add(resolveUnbound(0, data.fallbackAccount(), data, nowMs));
            return views;
        }
        for (WidgetSlot slot : config.getSlots()) {
            views.add(resolveSlot(slot, data, nowMs));
        }
        return views;
    }

    private static WidgetSlotView resolveSlot(WidgetSlot slot, DataSource data, long nowMs) {
        int index = slot.getSlotIndex();
        List<String> metrics = slot.getMetricIds();
        String accountId = slot.getAccountId();
        if (accountId.isEmpty()) {
            // An intentionally empty row in a half-configured dashboard. Hidden
            // rather than labelled 未配置: the user is mid-way through filling it,
            // and a row of complaints is louder than the gap they are looking at.
            return new WidgetSlotView(index, "", "", dashes(metrics), "",
                    UsageStatus.NO_DATA, false);
        }
        Account account = data.findAccount(accountId);
        if (account == null) {
            return new WidgetSlotView(index, accountId, DELETED_ACCOUNT, dashes(metrics),
                    RECONFIGURE_HINT, UsageStatus.NO_DATA, true);
        }
        return resolveInto(index, account, metrics, data, nowMs);
    }

    private static WidgetSlotView resolveUnbound(int index, Account fallback, DataSource data,
                                                 long nowMs) {
        if (fallback == null) {
            return new WidgetSlotView(index, "", UNCONFIGURED,
                    dashes(WidgetMetricId.defaults()), StatusWords.NEVER_QUERIED,
                    UsageStatus.NO_DATA, true);
        }
        return resolveInto(index, fallback, WidgetMetricId.defaults(), data, nowMs);
    }

    private static WidgetSlotView resolveInto(int index, Account account, List<String> metrics,
                                              DataSource data, long nowMs) {
        AccountRefreshManager.AccountView view = data.view(account.getId());
        UsageStatus status = view.displayStatus(data.refreshIntervalMs(), nowMs);
        UsageResult newest = view.lastAttempt != null ? view.lastAttempt : view.lastSuccess;
        // The age describes the number on screen, not the newest attempt. When a
        // failure is being reported beside a retained balance, quoting the
        // failure's timestamp would stamp a three-day-old reading "刚刚" — the
        // spec's whole reason for showing an age at all (§41).
        UsageResult shown = view.showingRetainedData() ? view.lastSuccess : newest;

        List<String> lines = new ArrayList<>();
        for (String metricId : metrics) {
            lines.add(value(metricId, account, view.lastSuccess, data));
        }

        String title = account.getDisplayName()
                + (account.isEnabled() ? "" : DISABLED_SUFFIX);
        // NO_DATA has no age: "尚未查询" is the whole sentence, and quoting the
        // moment the account row was created as if it were a reading would be a
        // number the user cannot check.
        String footer = shown == null
                ? StatusWords.NEVER_QUERIED
                : Freshness.describe(shown.getUpdatedAt(), nowMs) + SEPARATOR
                        + StatusWords.describe(status, view.showingRetainedData());
        boolean quota = CodexProvider.ID.equals(account.getProviderId());
        QuotaWindow fiveHour = null;
        QuotaWindow weekly = null;
        // Durations identify windows; primary/secondary ordering is not a contract.
        if (quota && view.lastSuccess != null) {
            fiveHour = com.aiusage.monitor.util.CodexQuotaWindows.find(view.lastSuccess.getQuotaWindows(), 300L);
            weekly = com.aiusage.monitor.util.CodexQuotaWindows.find(view.lastSuccess.getQuotaWindows(), 10080L);
        }
        return new WidgetSlotView(index, account.getId(), title, lines, footer, status, true,
                quota, quota && view.lastSuccess != null, fiveHour, weekly);
    }

    private static String value(String metricId, Account account, UsageResult success,
                                DataSource data) {
        ProviderCapabilities capabilities = data.capabilities(account.getProviderId());
        if (capabilities != null && !capabilities.supportsWidgetMetric(metricId)) {
            return UNSUPPORTED_METRIC;
        }
        if (WidgetMetricId.BALANCE.equals(metricId)) {
            return success == null || success.getBalance() == null
                    ? Money.EMPTY : Money.format(success.getBalance());
        }
        if (WidgetMetricId.TODAY_USAGE.equals(metricId)) {
            // The accumulator is the difference between balance readings, so with
            // no balance in hand there is no estimate to show — an em dash, not a
            // zero that would read as "spent nothing today".
            if (success == null || success.getBalance() == null) {
                return Money.EMPTY;
            }
            BigDecimal today = data.dailyUsage(account.getId());
            return Money.format(Money.scale2(today.doubleValue()),
                    success.getBalance().getCurrency());
        }
        // A metric the provider declares but this build cannot draw yet, or one a
        // newer build left in the stored list. Explicit beats the em dash that
        // would read as "zero".
        return UNSUPPORTED_METRIC;
    }

    private static List<String> dashes(List<String> metrics) {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < (metrics == null ? 0 : metrics.size()); index++) {
            lines.add(Money.EMPTY);
        }
        return Collections.unmodifiableList(lines);
    }
}
