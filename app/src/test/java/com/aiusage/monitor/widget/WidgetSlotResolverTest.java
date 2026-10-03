package com.aiusage.monitor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.deepseek.DeepSeekProvider;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.refresh.RefreshPolicy;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.StatusWords;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The widget display rules, asserted where they are actually decided.
 *
 * <p>Spec §39–§41 make three demands that a screenshot can only confirm one at a
 * time: a failed refresh must never replace the last good number, every reading
 * must say how old it is, and a status must be worded the same way in the list
 * and on the home screen. All three are branches in
 * {@link WidgetSlotResolver}, so all three are tested here rather than inferred
 * from a picture.
 *
 * <p>The {@code nowMs} parameter the resolver takes is what makes the age and
 * staleness boundaries testable at all: the alternative is a suite that passes
 * during the day and fails after midnight.
 *
 * <p>{@link DeepSeekProvider}'s real capability list is used deliberately. The
 * provider declares metric ids as string literals because a provider may not
 * import the widget layer, so this is the pin that stops the two spellings from
 * drifting apart.
 */
public class WidgetSlotResolverTest {

    private static final String DEEPSEEK = DeepSeekProvider.ID;
    private static final String ACCOUNT_A = "acct_aaaaaa";
    private static final String ACCOUNT_B = "acct_bbbbbb";
    private static final String ACCOUNT_GONE = "acct_deleted";
    private static final long INTERVAL = 15L * 60L * 1000L;
    private static final long NOW = 1_700_000_000_000L;

    private final StubData data = new StubData();

    // ------------------------------------------------------------ the readings

    @Test
    public void aFreshSuccessShowsItsNumberAndSaysSo() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW - 20_000L);
        data.usage(ACCOUNT_A, "3.00");

        WidgetSlotView view = resolveOne(WidgetConfig.unbound(1, "4x2", INTERVAL));

        assertEquals("¥24.94", view.valueLines.get(0));
        assertEquals("¥3.00", view.valueLines.get(1));
        assertEquals("刚刚 · 账户可用", view.footer);
        assertEquals(UsageStatus.OK, view.status);
        assertEquals("DeepSeek", view.title);
    }

    @Test
    public void theFirstLineIsBalanceAndTheSecondTodaysUsage() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);
        data.usage(ACCOUNT_A, "1.28");

        WidgetConfig config = new WidgetConfig(1, "2x2",
                Collections.singletonList(new WidgetSlot(0, ACCOUNT_A,
                        Arrays.asList(WidgetMetricId.BALANCE, WidgetMetricId.TODAY_USAGE))),
                INTERVAL, 0, 0L);

        assertEquals(Arrays.asList("¥24.94", "¥1.28"), resolveOne(config).valueLines);
    }

    @Test
    public void aSlotAskingForOneMetricShowsOneLine() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);
        data.usage(ACCOUNT_A, "1.28");

        WidgetConfig config = new WidgetConfig(1, "2x1",
                Collections.singletonList(new WidgetSlot(0, ACCOUNT_A,
                        Collections.singletonList(WidgetMetricId.TODAY_USAGE))),
                INTERVAL, 0, 0L);

        assertEquals(Collections.singletonList("¥1.28"), resolveOne(config).valueLines);
    }

    @Test
    public void aBalanceThatHasNeverBeenReadShowsDashesAndSaysNeverQueried() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));

        WidgetConfig config = new WidgetConfig(1, "4x2",
                Collections.singletonList(WidgetSlot.ofAccount(0, ACCOUNT_A)),
                INTERVAL, 0, 0L);
        WidgetSlotView view = resolveOne(config);

        assertEquals(Arrays.asList(Money.EMPTY, Money.EMPTY), view.valueLines);
        assertEquals("there is no age to quote when nothing was ever read",
                StatusWords.NEVER_QUERIED, view.footer);
        assertEquals(UsageStatus.NO_DATA, view.status);
    }

    // ------------------------------------------------- failure keeps the number

    @Test
    public void anAuthFailureAfterASuccessKeepsTheBalanceAndChangesOnlyTheWords() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW - INTERVAL);
        data.attempt(ACCOUNT_A, failed(UsageStatus.AUTH_REQUIRED, NOW - 1_000L));

        WidgetSlotView view = resolveOne(bound(ACCOUNT_A));

        assertEquals("Spec §39: a failed refresh must never clear the last good value",
                "¥24.94", view.valueLines.get(0));
        assertEquals("15分钟前 · API Key 无效或已失效 · 最后成功数据", view.footer);
        assertEquals(UsageStatus.AUTH_REQUIRED, view.status);
    }

    /**
     * The age belongs to the number, not to the newest attempt.
     *
     * <p>Quoting the failure's timestamp next to a balance retained from hours
     * ago would print "刚刚" beside a number the user cannot act on, which is the
     * one thing Spec §41 exists to prevent.
     */
    @Test
    public void theAgeQuotesTheRetainedBalanceRatherThanTheFailureBesideIt() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW - 3 * 60 * 60_000L);
        data.attempt(ACCOUNT_A, failed(UsageStatus.NETWORK_ERROR, NOW - 30_000L));

        WidgetSlotView view = resolveOne(bound(ACCOUNT_A));

        assertTrue("the failure is three minutes old, the balance is three hours old: "
                        + view.footer,
                view.footer.startsWith("3小时前"));
        assertTrue(view.footer.endsWith("网络连接失败 · 最后成功数据"));
    }

    @Test
    public void aNetworkFailureAfterASuccessReportsTheNetworkNotTheOldSuccess() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW - 2 * INTERVAL);
        data.attempt(ACCOUNT_A, failed(UsageStatus.NETWORK_ERROR, NOW - 500L));

        WidgetSlotView view = resolveOne(bound(ACCOUNT_A));

        assertTrue(view.footer, view.footer.endsWith("网络连接失败 · 最后成功数据"));
        assertEquals("¥24.94", view.valueLines.get(0));
    }

    @Test
    public void aFirstEverFailureHasNoNumberToRetain() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.attempt(ACCOUNT_A, failed(UsageStatus.AUTH_REQUIRED, NOW - 60_000L));

        WidgetSlotView view = resolveOne(bound(ACCOUNT_A));

        assertEquals(Arrays.asList(Money.EMPTY, Money.EMPTY), view.valueLines);
        assertTrue("with no older success there is nothing to mark as retained",
                view.footer.endsWith("API Key 无效或已失效"));
        assertFalse(view.footer.contains(StatusWords.RETAINED_SUFFIX));
    }

    @Test
    public void recoveringFromAFailureDropsTheErrorWording() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("25.00"), NOW - 5_000L);
        data.attempt(ACCOUNT_A, data.lastSuccess.get(ACCOUNT_A));

        WidgetSlotView view = resolveOne(bound(ACCOUNT_A));

        assertEquals("¥25.00", view.valueLines.get(0));
        assertEquals("刚刚 · 账户可用", view.footer);
    }

    @Test
    public void anOldSuccessWithoutAnyFailureIsStaleNotFailed() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW - 10 * INTERVAL);

        WidgetSlotView view = resolveOne(bound(ACCOUNT_A));

        assertEquals(UsageStatus.STALE, view.status);
        assertTrue("the number stays and the words say it is old: " + view.footer,
                view.footer.endsWith("数据已过期"));
        assertFalse("a stale success is not a retained one",
                view.footer.contains(StatusWords.RETAINED_SUFFIX));
    }

    // ------------------------------------------------------------ how old it is

    @Test
    public void ageWordingFollowsTheSpecsFourForms() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));

        assertEquals("刚刚", age(NOW - 59_000L));
        assertEquals("1分钟前", age(NOW - 60_000L));
        assertEquals("5分钟前", age(NOW - 5 * 60_000L));
        assertEquals("59分钟前", age(NOW - (59 * 60_000L + 59_000L)));
        assertEquals("1小时前", age(NOW - 60 * 60_000L));
        assertEquals("5小时前", age(NOW - (5 * 60 * 60_000L + 1_000L)));
    }

    @Test
    public void anAgePastTheRelativeWindowShowsTheClockTimeOfThatDay() {
        Calendar at = atLocalHour(1);
        Calendar now = atLocalHour(11);

        assertEquals("ten hours ago is past the relative window, so the screen says "
                        + "when rather than making the reader do arithmetic",
                clockOf(at.getTimeInMillis()),
                ageAt(at.getTimeInMillis(), now.getTimeInMillis()));
    }

    @Test
    public void aReadingFromAnEarlierDayShowsTheDateNotTheClock() {
        Calendar now = atLocalHour(23);
        Calendar yesterday = atLocalHour(23);
        yesterday.add(Calendar.DAY_OF_MONTH, -1);

        String shown = ageAt(yesterday.getTimeInMillis(), now.getTimeInMillis());

        assertTrue("expected MM-dd, got " + shown, shown.matches("\\d{2}-\\d{2}"));
        assertEquals(DateTimeFormatter.ofPattern("MM-dd")
                        .format(Instant.ofEpochMilli(yesterday.getTimeInMillis())
                                .atZone(ZoneId.systemDefault())),
                shown);
    }

    @Test
    public void aTimestampInTheFutureReadsAsJustNowRatherThanNegative() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));

        assertEquals("刚刚", ageAt(NOW + 5_000L, NOW));
    }

    // ------------------------------------------------------- the account's state

    @Test
    public void aSlotBoundToADeletedAccountSaysSoAndKeepsAskingForNothing() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);

        WidgetConfig config = new WidgetConfig(1, "4x2",
                Collections.singletonList(WidgetSlot.ofAccount(0, ACCOUNT_GONE)),
                INTERVAL, 0, 0L);
        WidgetSlotView view = resolveOne(config);

        assertEquals(WidgetSlotResolver.DELETED_ACCOUNT, view.title);
        assertEquals("请重新配置该 Slot", view.footer);
        assertEquals(Arrays.asList(Money.EMPTY, Money.EMPTY), view.valueLines);
        assertTrue("the row must stay on screen so the user can see why", view.visible);
    }

    @Test
    public void aDisabledAccountIsMarkedInTheHeader() {
        Account disabled = enabled(ACCOUNT_A, "DeepSeekWork").toBuilder().enabled(false).build();
        data.withAccount(disabled);
        data.success(ACCOUNT_A, balance("24.94"), NOW);

        assertEquals("DeepSeekWork（已停用）", resolveOne(bound(ACCOUNT_A)).title);
    }

    @Test
    public void aWidgetWithNoBindingFallsBackToTheFirstEnabledAccount() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.withAccount(enabled(ACCOUNT_B, "DeepSeekWork"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);

        WidgetSlotView view = resolveOne(WidgetConfig.unbound(1, "4x2", INTERVAL));

        assertEquals("Spec §39: upgrading must not leave a blank widget",
                ACCOUNT_A, view.accountId);
        assertEquals("¥24.94", view.valueLines.get(0));
    }

    @Test
    public void aWidgetWithNoBindingAndNoAccountsAtAllSaysUnconfigured() {
        WidgetSlotView view = resolveOne(WidgetConfig.unbound(1, "4x2", INTERVAL));

        assertEquals(WidgetSlotResolver.UNCONFIGURED, view.title);
        assertEquals("", view.accountId);
        assertEquals(StatusWords.NEVER_QUERIED, view.footer);
        assertTrue(view.visible);
    }

    // -------------------------------------------------------------- several slots

    @Test
    public void twoAccountsInOneWidgetEachShowTheirOwnNumbers() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.withAccount(enabled(ACCOUNT_B, "DeepSeekWork"));
        data.success(ACCOUNT_A, balance("24.94"), NOW - 10_000L);
        data.success(ACCOUNT_B, balance("108.50"), NOW - 40_000L);
        data.usage(ACCOUNT_A, "3.00");
        data.usage(ACCOUNT_B, "12.00");

        WidgetConfig config = new WidgetConfig(1, "4x2", Arrays.asList(
                WidgetSlot.ofAccount(0, ACCOUNT_A), WidgetSlot.ofAccount(1, ACCOUNT_B)),
                INTERVAL, 0, 0L);

        List<WidgetSlotView> views = WidgetSlotResolver.resolve(config, data, NOW);

        assertEquals(2, views.size());
        assertEquals(Arrays.asList("¥24.94", "¥3.00"), views.get(0).valueLines);
        assertEquals(Arrays.asList("¥108.50", "¥12.00"), views.get(1).valueLines);
        assertEquals("DeepSeek", views.get(0).title);
        assertEquals("DeepSeekWork", views.get(1).title);
        assertEquals("each view carries its own account so a tap opens the right detail",
                ACCOUNT_B, views.get(1).accountId);
    }

    @Test
    public void oneAccountInTwoSlotsShowsTheSameReadingTwice() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);
        data.usage(ACCOUNT_A, "3.00");

        WidgetConfig config = new WidgetConfig(1, "4x2", Arrays.asList(
                WidgetSlot.ofAccount(0, ACCOUNT_A), WidgetSlot.ofAccount(1, ACCOUNT_A)),
                INTERVAL, 0, 0L);
        List<WidgetSlotView> views = WidgetSlotResolver.resolve(config, data, NOW);

        assertEquals(views.get(0).valueLines, views.get(1).valueLines);
        assertEquals(0, views.get(0).slotIndex);
        assertEquals(1, views.get(1).slotIndex);
    }

    @Test
    public void anEmptySlotInTheMiddleIsHiddenRatherThanComplaining() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);
        data.withAccount(enabled(ACCOUNT_B, "DeepSeekWork"));
        data.success(ACCOUNT_B, balance("1.00"), NOW);

        WidgetConfig config = new WidgetConfig(1, "4x2", Arrays.asList(
                WidgetSlot.ofAccount(0, ACCOUNT_A),
                new WidgetSlot(1, "", WidgetMetricId.defaults()),
                WidgetSlot.ofAccount(2, ACCOUNT_B)),
                INTERVAL, 0, 0L);
        List<WidgetSlotView> views = WidgetSlotResolver.resolve(config, data, NOW);

        assertEquals(3, views.size());
        assertTrue(views.get(0).visible);
        assertFalse("a row the user has not filled is a gap, not an error",
                views.get(1).visible);
        assertEquals("¥1.00", views.get(2).valueLines.get(0));
    }

    // --------------------------------------------------------------- the metrics

    @Test
    public void aMetricTheProviderDoesNotOfferSaysSoInsteadOfShowingZero() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);

        WidgetConfig config = new WidgetConfig(1, "4x2",
                Collections.singletonList(new WidgetSlot(0, ACCOUNT_A,
                        Arrays.asList(WidgetMetricId.BALANCE, "quota_5h_remaining"))),
                INTERVAL, 0, 0L);
        WidgetSlotView view = resolveOne(config);

        assertEquals("¥24.94", view.valueLines.get(0));
        assertEquals(WidgetSlotResolver.UNSUPPORTED_METRIC, view.valueLines.get(1));
    }

    @Test
    public void aMetricLeftInTheStoreByANewerBuildIsReportedNotInvented() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), NOW);

        WidgetConfig config = new WidgetConfig(1, "4x2",
                Collections.singletonList(new WidgetSlot(0, ACCOUNT_A,
                        Collections.singletonList("reset_in"))),
                INTERVAL, 0, 0L);

        assertEquals(Collections.singletonList(WidgetSlotResolver.UNSUPPORTED_METRIC),
                resolveOne(config).valueLines);
    }

    @Test
    public void usageWithoutABalanceToDiffAgainstIsNotShownAsZero() {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        // A successful reading that carries no monetary balance: the accumulator
        // has nothing to subtract, so 0.00 would be a claim about spending.
        data.success(ACCOUNT_A, null, NOW);
        data.usage(ACCOUNT_A, "0.00");

        WidgetConfig config = new WidgetConfig(1, "4x2",
                Collections.singletonList(new WidgetSlot(0, ACCOUNT_A,
                        Collections.singletonList(WidgetMetricId.TODAY_USAGE))),
                INTERVAL, 0, 0L);

        assertEquals(Collections.singletonList(Money.EMPTY), resolveOne(config).valueLines);
    }

    /**
     * The pin for decision D1: the provider's declarations and the widget's ids
     * are two spellings of the same thing, written in packages that are not
     * allowed to import each other.
     */
    @Test
    public void deepSeekDeclaresExactlyTheMetricsTheWidgetKnows() {
        ProviderCapabilities capabilities = new DeepSeekProvider().getCapabilities();

        assertTrue(capabilities.supportsWidgetMetric(WidgetMetricId.BALANCE));
        assertTrue(capabilities.supportsWidgetMetric(WidgetMetricId.TODAY_USAGE));
        assertFalse(capabilities.supportsWidgetMetric("yesterday_usage"));
        assertEquals(Arrays.asList(WidgetMetricId.BALANCE, WidgetMetricId.TODAY_USAGE),
                idsOf(capabilities));
        assertEquals("余额", capabilities.getWidgetMetrics().get(0).getLabel());
    }

    // ------------------------------------------------------------------ fixtures

    private static List<String> idsOf(ProviderCapabilities capabilities) {
        List<String> ids = new ArrayList<>();
        for (com.aiusage.monitor.provider.WidgetMetric metric
                : capabilities.getWidgetMetrics()) {
            ids.add(metric.getId());
        }
        return ids;
    }

    private WidgetSlotView resolveOne(WidgetConfig config) {
        List<WidgetSlotView> views = WidgetSlotResolver.resolve(config, data, NOW);
        assertEquals(1, views.size());
        return views.get(0);
    }

    private WidgetConfig bound(String accountId) {
        return new WidgetConfig(1, "4x2",
                Collections.singletonList(WidgetSlot.ofAccount(0, accountId)),
                INTERVAL, 0, 0L);
    }

    private String age(long updatedAt) {
        return ageAt(updatedAt, NOW);
    }

    /**
     * The age wording for one reading judged at a chosen instant.
     *
     * <p>Takes {@code nowMs} through to the resolver rather than reusing the
     * class constant: the whole point of these cases is a reading and a "now"
     * that are not the same moment.
     */
    private String ageAt(long updatedAt, long nowMs) {
        data.withAccount(enabled(ACCOUNT_A, "DeepSeek"));
        data.success(ACCOUNT_A, balance("24.94"), updatedAt);
        List<WidgetSlotView> views =
                WidgetSlotResolver.resolve(bound(ACCOUNT_A), data, nowMs);
        return views.get(0).footer.split(" · ")[0];
    }

    /** A local hour chosen so a DST shift cannot push the gap under six hours. */
    private static Calendar atLocalHour(int hour) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, 30);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static String clockOf(long atMs) {
        return DateTimeFormatter.ofPattern("HH:mm")
                .format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()));
    }

    private static Account enabled(String accountId, String name) {
        return Account.builder()
                .id(accountId)
                .providerId(DEEPSEEK)
                .displayName(name)
                .authType(AuthType.API_KEY)
                .credentialId("cred_" + accountId)
                .enabled(true)
                .sortOrder(0)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static Balance balance(String raw) {
        return new Balance(Double.parseDouble(raw), "CNY", raw);
    }

    private static UsageResult failed(UsageStatus status, long at) {
        return UsageResult.builder().status(status).updatedAt(at).build();
    }

    /**
     * A {@link WidgetSlotResolver.DataSource} over maps.
     *
     * <p>Deliberately not a fake database: the resolver's contract is "given these
     * two readings and this accumulator, say this", and the pairing of readings is
     * {@code AccountRefreshManager.AccountView}'s own tested behaviour.
     */
    private static final class StubData implements WidgetSlotResolver.DataSource {

        private final Map<String, Account> accounts = new LinkedHashMap<>();
        private final Map<String, UsageResult> lastSuccess = new HashMap<>();
        private final Map<String, UsageResult> lastAttempt = new HashMap<>();
        private final Map<String, BigDecimal> usage = new HashMap<>();
        private final ProviderCapabilities deepseek = new DeepSeekProvider().getCapabilities();

        void withAccount(Account account) {
            accounts.put(account.getId(), account);
        }

        void success(String accountId, Balance balance, long at) {
            UsageResult result = UsageResult.builder()
                    .accountId(accountId)
                    .providerId(DEEPSEEK)
                    .balance(balance)
                    .status(UsageStatus.OK)
                    .updatedAt(at)
                    .build();
            lastSuccess.put(accountId, result);
            lastAttempt.put(accountId, result);
        }

        void attempt(String accountId, UsageResult result) {
            lastAttempt.put(accountId, result);
        }

        void usage(String accountId, String amount) {
            usage.put(accountId, new BigDecimal(amount));
        }

        @Override
        public Account findAccount(String accountId) {
            return accountId == null ? null : accounts.get(accountId);
        }

        @Override
        public Account fallbackAccount() {
            // Insertion order, the way SqliteAccountRepository's
            // ORDER BY sort_order, created_at returns it — the fixture accounts
            // share a createdAt, so a HashMap's order would not be a fallback
            // anyone could predict.
            for (Account account : accounts.values()) {
                if (account.isEnabled()) {
                    return account;
                }
            }
            return null;
        }

        @Override
        public AccountRefreshManager.AccountView view(String accountId) {
            return new AccountRefreshManager.AccountView(
                    lastSuccess.get(accountId), lastAttempt.get(accountId));
        }

        @Override
        public BigDecimal dailyUsage(String accountId) {
            BigDecimal amount = usage.get(accountId);
            return amount == null ? BigDecimal.ZERO : amount;
        }

        @Override
        public ProviderCapabilities capabilities(String providerId) {
            return DEEPSEEK.equals(providerId) ? deepseek : null;
        }

        @Override
        public long refreshIntervalMs() {
            return INTERVAL;
        }
    }

    /** The interval the fixtures assume is one the app actually offers. */
    @Test
    public void theFixturesIntervalIsAChoiceThePolicyRecognises() {
        boolean offered = false;
        for (long candidate : RefreshPolicy.INTERVALS_MS) {
            if (candidate == INTERVAL) {
                offered = true;
            }
        }
        assertTrue("a fixture interval outside the offered list would test staleness "
                + "rules the app never applies", offered);
    }
}
