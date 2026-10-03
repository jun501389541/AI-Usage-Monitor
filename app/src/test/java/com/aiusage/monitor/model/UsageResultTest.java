package com.aiusage.monitor.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link UsageResult} and its builder.
 *
 * <p>Covers the documented defaults, the lookup helpers, the copy helpers
 * ({@code asStale} / {@code withStatus}), and the {@code toBuilder}
 * round-trip. {@link QuotaWindow} and {@link Metric} define no {@code equals},
 * so payload identity is asserted with {@code assertSame} rather than
 * {@code assertEquals}.
 */
public class UsageResultTest {

    private static QuotaWindow quota5h() {
        return new QuotaWindow("quota_5h", "5 小时额度", 12.5, 87.5, 300L, 1_700_000_000_000L);
    }

    private static QuotaWindow quotaWeek() {
        return new QuotaWindow("quota_week", "每周额度", 40.0, 60.0, 10_080L, 1_700_100_000_000L);
    }

    private static Metric todayUsage() {
        return new Metric("today_usage", "今日使用", 2.48, "CNY");
    }

    private static UsageResult.Builder fullBuilder() {
        return UsageResult.builder()
                .accountId("acc-1")
                .providerId("deepseek")
                .balance(new Balance(38.52, "CNY", "38.52"))
                .addQuotaWindow(quota5h())
                .addMetric(todayUsage())
                .status(UsageStatus.OK)
                .updatedAt(1_700_000_000_000L)
                .source(UsageResult.Source.DIRECT_API);
    }

    @Test
    public void builderDefaultsStatusToOk() {
        assertEquals(UsageStatus.OK, UsageResult.builder().build().getStatus());
    }

    @Test
    public void builderDefaultsSourceToDirectApi() {
        assertEquals(UsageResult.Source.DIRECT_API, UsageResult.builder().build().getSource());
    }

    @Test
    public void builderDefaultsQuotaWindowsToAnEmptyList() {
        List<QuotaWindow> windows = UsageResult.builder().build().getQuotaWindows();
        assertNotNull(windows);
        assertTrue(windows.isEmpty());
    }

    @Test
    public void builderDefaultsMetricsToAnEmptyList() {
        List<Metric> metrics = UsageResult.builder().build().getMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    public void builderDefaultsTextFieldsToEmptyStrings() {
        UsageResult result = UsageResult.builder().build();
        assertEquals("", result.getAccountId());
        assertEquals("", result.getProviderId());
    }

    @Test
    public void builderDefaultsBalanceToNull() {
        assertNull(UsageResult.builder().build().getBalance());
    }

    @Test
    public void builderDefaultsUpdatedAtToNow() {
        long before = System.currentTimeMillis();
        long updatedAt = UsageResult.builder().build().getUpdatedAt();
        long after = System.currentTimeMillis();
        assertTrue(updatedAt >= before && updatedAt <= after);
    }

    @Test
    public void builderIgnoresNullTextFields() {
        UsageResult result = UsageResult.builder().accountId(null).providerId(null).build();
        assertEquals("", result.getAccountId());
        assertEquals("", result.getProviderId());
    }

    @Test
    public void builderIgnoresNullCollections() {
        UsageResult result = UsageResult.builder().quotaWindows(null).metrics(null).build();
        assertNotNull(result.getQuotaWindows());
        assertNotNull(result.getMetrics());
        assertTrue(result.getQuotaWindows().isEmpty());
        assertTrue(result.getMetrics().isEmpty());
    }

    @Test
    public void builderFallsBackToOkAndDirectApiForNullEnums() {
        UsageResult result = UsageResult.builder().status(null).source(null).build();
        assertEquals(UsageStatus.OK, result.getStatus());
        assertEquals(UsageResult.Source.DIRECT_API, result.getSource());
    }

    @Test
    public void addQuotaWindowSkipsNullEntries() {
        UsageResult result = UsageResult.builder().addQuotaWindow(null).build();
        assertTrue(result.getQuotaWindows().isEmpty());
    }

    @Test
    public void addMetricSkipsNullEntries() {
        UsageResult result = UsageResult.builder().addMetric(null).build();
        assertTrue(result.getMetrics().isEmpty());
    }

    @Test
    public void builderKeepsInsertionOrder() {
        UsageResult result = UsageResult.builder()
                .addQuotaWindow(quota5h())
                .addQuotaWindow(quotaWeek())
                .build();
        assertEquals(2, result.getQuotaWindows().size());
        assertEquals("quota_5h", result.getQuotaWindows().get(0).getId());
        assertEquals("quota_week", result.getQuotaWindows().get(1).getId());
    }

    @Test
    public void theExposedCollectionsAreUnmodifiable() {
        UsageResult result = fullBuilder().build();
        assertThrows(UnsupportedOperationException.class,
                () -> result.getMetrics().add(new Metric("x", "x", 1.0, "")));
    }

    @Test
    public void theBuilderDoesNotAliasTheCallersList() {
        List<Metric> callerList = new java.util.ArrayList<>();
        callerList.add(todayUsage());
        UsageResult result = UsageResult.builder().metrics(callerList).build();
        callerList.add(new Metric("extra", "extra", 1.0, ""));
        assertEquals(1, result.getMetrics().size());
    }

    @Test
    public void findMetricReturnsTheMatchingMetric() {
        Metric found = fullBuilder().build().findMetric("today_usage");
        assertNotNull(found);
        assertEquals("today_usage", found.getKey());
        assertEquals("今日使用", found.getLabel());
        assertEquals(2.48, found.getValue(), 0.0001);
        assertEquals("CNY", found.getUnit());
    }

    @Test
    public void findMetricReturnsNullForAMiss() {
        assertNull(fullBuilder().build().findMetric("missing"));
    }

    @Test
    public void findQuotaWindowReturnsTheMatchingWindow() {
        UsageResult result = UsageResult.builder().addQuotaWindow(quota5h()).build();
        assertEquals("quota_5h", result.findQuotaWindow("quota_5h").getId());
    }

    @Test
    public void findQuotaWindowReturnsNullForAMiss() {
        assertNull(fullBuilder().build().findQuotaWindow("missing"));
    }

    @Test
    public void asStaleSetsStatusToStale() {
        assertEquals(UsageStatus.STALE, fullBuilder().build().asStale().getStatus());
    }

    @Test
    public void asStalePreservesTheWholePayload() {
        UsageResult original = fullBuilder().build();
        UsageResult stale = original.asStale();
        assertEquals(original.getAccountId(), stale.getAccountId());
        assertEquals(original.getProviderId(), stale.getProviderId());
        assertEquals(original.getUpdatedAt(), stale.getUpdatedAt());
        assertEquals(original.getSource(), stale.getSource());
        assertEquals(original.getBalance(), stale.getBalance());
        assertEquals(original.getMetrics(), stale.getMetrics());
        assertEquals(original.getQuotaWindows(), stale.getQuotaWindows());
        assertSame(original.getBalance(), stale.getBalance());
        assertSame(original.getMetrics().get(0), stale.getMetrics().get(0));
        assertSame(original.getQuotaWindows().get(0), stale.getQuotaWindows().get(0));
    }

    @Test
    public void asStaleDoesNotMutateTheOriginal() {
        UsageResult original = fullBuilder().build();
        original.asStale();
        assertEquals(UsageStatus.OK, original.getStatus());
    }

    @Test
    public void withStatusChangesOnlyTheStatus() {
        UsageResult original = fullBuilder().build();
        UsageResult changed = original.withStatus(UsageStatus.AUTH_REQUIRED);
        assertEquals(UsageStatus.AUTH_REQUIRED, changed.getStatus());
        assertEquals(original.getAccountId(), changed.getAccountId());
        assertEquals(original.getProviderId(), changed.getProviderId());
        assertEquals(original.getUpdatedAt(), changed.getUpdatedAt());
        assertEquals(original.getSource(), changed.getSource());
        assertEquals(original.getBalance(), changed.getBalance());
        assertEquals(original.getMetrics(), changed.getMetrics());
        assertEquals(original.getQuotaWindows(), changed.getQuotaWindows());
        assertEquals(UsageStatus.OK, original.getStatus());
    }

    @Test
    public void withStatusAcceptsEveryLifecycleState() {
        UsageResult original = fullBuilder().build();
        for (UsageStatus status : UsageStatus.values()) {
            assertEquals(status, original.withStatus(status).getStatus());
        }
    }

    @Test
    public void toBuilderRoundTripsEveryField() {
        UsageResult original = fullBuilder().build();
        UsageResult copy = original.toBuilder().build();
        assertEquals(original.getAccountId(), copy.getAccountId());
        assertEquals(original.getProviderId(), copy.getProviderId());
        assertEquals(original.getBalance(), copy.getBalance());
        assertEquals(original.getQuotaWindows(), copy.getQuotaWindows());
        assertEquals(original.getMetrics(), copy.getMetrics());
        assertEquals(original.getStatus(), copy.getStatus());
        assertEquals(original.getUpdatedAt(), copy.getUpdatedAt());
        assertEquals(original.getSource(), copy.getSource());
        assertSame(original.getBalance(), copy.getBalance());
        assertSame(original.getMetrics().get(0), copy.getMetrics().get(0));
        assertSame(original.getQuotaWindows().get(0), copy.getQuotaWindows().get(0));
    }

    @Test
    public void toBuilderAllowsOverridingOneField() {
        UsageResult original = fullBuilder().build();
        UsageResult edited = original.toBuilder()
                .balance(new Balance(50.0, "USD", "50.00"))
                .build();
        assertEquals(new Balance(50.0, "USD", "50.00"), edited.getBalance());
        assertEquals(original.getMetrics(), edited.getMetrics());
        assertEquals(original.getUpdatedAt(), edited.getUpdatedAt());
    }

    @Test
    public void toBuilderPreservesListsGivenAsAWhole() {
        UsageResult original = UsageResult.builder()
                .metrics(Arrays.asList(todayUsage(), new Metric("tokens", "令牌", 1234.0, "tokens")))
                .build();
        assertEquals(2, original.toBuilder().build().getMetrics().size());
    }
}
