package com.aiusage.monitor.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Metric;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;

import org.junit.Test;

/**
 * Host-JVM tests for {@link UsageSnapshotCodec}.
 *
 * <p>A round-trip bug here would corrupt every stored snapshot and the widget's
 * cached fallback, so these tests pin the full field set rather than a sample.
 *
 * <p>The fallbacks asserted below are the ones the class actually documents:
 * {@code encode(null)} returns the minimal object {@code "{}"} (never null), an
 * unreadable payload decodes to null rather than throwing, and unknown enum
 * names fall back to {@link UsageStatus#NO_DATA} and
 * {@link UsageResult.Source#CACHE}. Note that {@code decode("{}")} is valid
 * input, not malformed input, so it returns a defaults-filled result rather than
 * null — that distinction is asserted explicitly.
 */
public class UsageSnapshotCodecTest {

    private static final double EPSILON = 1e-9d;
    private static final long FIXED_UPDATED_AT = 1_700_000_123_456L;
    private static final long FIXED_RESET_AT = 1_700_000_500_000L;

    /** A result exercising every field the codec persists. */
    private static UsageResult richResult() {
        return UsageResult.builder()
                .accountId("acct-1")
                .providerId("deepseek")
                .balance(new Balance(38.52d, "CNY", "38.52"))
                .addQuotaWindow(new QuotaWindow(
                        "quota_5h", "5 小时额度", 42.5d, 57.5d, 300L, FIXED_RESET_AT))
                .addMetric(new Metric("is_available", "账户可用", 1d, ""))
                .addMetric(new Metric("today_usage", "今日使用", 12.75d, "CNY"))
                .status(UsageStatus.OK)
                .source(UsageResult.Source.DIRECT_API)
                .updatedAt(FIXED_UPDATED_AT)
                .build();
    }

    @Test
    public void richResultRoundTripsThroughEncodeAndDecode() {
        UsageResult original = richResult();

        String encoded = UsageSnapshotCodec.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.startsWith("{"));
        assertTrue(encoded.contains("CNY"));

        UsageResult decoded = UsageSnapshotCodec.decode(encoded);
        assertNotNull(decoded);

        assertEquals("acct-1", decoded.getAccountId());
        assertEquals("deepseek", decoded.getProviderId());
        assertEquals(UsageStatus.OK, decoded.getStatus());
        assertEquals(UsageResult.Source.DIRECT_API, decoded.getSource());
        assertEquals(FIXED_UPDATED_AT, decoded.getUpdatedAt());

        Balance balance = decoded.getBalance();
        assertNotNull(balance);
        assertEquals(38.52d, balance.getAmount(), EPSILON);
        assertEquals("CNY", balance.getCurrency());
        assertEquals("38.52", balance.getRawText());
        // Balance implements equals; a value-equal balance proves no field drifted.
        assertEquals(original.getBalance(), balance);

        assertEquals(1, decoded.getQuotaWindows().size());
        QuotaWindow window = decoded.findQuotaWindow("quota_5h");
        assertNotNull(window);
        assertEquals("quota_5h", window.getId());
        assertEquals("5 小时额度", window.getLabel());
        assertEquals(42.5d, window.getUsedPercent(), EPSILON);
        assertEquals(57.5d, window.getRemainingPercent(), EPSILON);
        assertEquals(300L, window.getWindowMinutes());
        assertEquals(FIXED_RESET_AT, window.getResetAt());

        assertEquals(2, decoded.getMetrics().size());
        Metric available = decoded.findMetric("is_available");
        assertNotNull(available);
        assertEquals("is_available", available.getKey());
        assertEquals("账户可用", available.getLabel());
        assertEquals(1d, available.getValue(), EPSILON);
        assertEquals("", available.getUnit());

        Metric usage = decoded.findMetric("today_usage");
        assertNotNull(usage);
        assertEquals("今日使用", usage.getLabel());
        assertEquals(12.75d, usage.getValue(), EPSILON);
        assertEquals("CNY", usage.getUnit());
    }

    @Test
    public void resultWithNullBalanceRoundTripsWithoutThrowing() {
        UsageResult original = UsageResult.builder()
                .accountId("acct-2")
                .providerId("codex")
                .addMetric(new Metric("quota_primary", "主额度", 80d, "%"))
                .status(UsageStatus.STALE)
                .source(UsageResult.Source.CACHE)
                .updatedAt(FIXED_UPDATED_AT)
                .build();

        String encoded = UsageSnapshotCodec.encode(original);
        assertNotNull(encoded);

        UsageResult decoded = UsageSnapshotCodec.decode(encoded);
        assertNotNull(decoded);
        assertNull(decoded.getBalance());
        assertEquals("acct-2", decoded.getAccountId());
        assertEquals("codex", decoded.getProviderId());
        assertEquals(UsageStatus.STALE, decoded.getStatus());
        assertEquals(UsageResult.Source.CACHE, decoded.getSource());
        assertEquals(1, decoded.getMetrics().size());
        assertEquals(80d, decoded.getMetrics().get(0).getValue(), EPSILON);
    }

    @Test
    public void encodeOfNullReturnsMinimalJsonObjectRatherThanNull() {
        String encoded = UsageSnapshotCodec.encode(null);

        assertNotNull(encoded);
        assertEquals("{}", encoded);
    }

    @Test
    public void decodeOfMalformedInputReturnsNullInsteadOfThrowing() {
        assertNull(UsageSnapshotCodec.decode("not json"));
        assertNull(UsageSnapshotCodec.decode("[]"));
        assertNull(UsageSnapshotCodec.decode("{\"accountId\":"));
    }

    @Test
    public void decodeOfNullOrBlankInputReturnsNull() {
        assertNull(UsageSnapshotCodec.decode(null));
        assertNull(UsageSnapshotCodec.decode(""));
        assertNull(UsageSnapshotCodec.decode("   "));
    }

    @Test
    public void decodeOfEmptyJsonObjectReturnsDefaultsInsteadOfNull() {
        // "{}" is valid JSON, so the forgiving path yields a defaults-filled
        // result: the status and source fall back, and the lists stay empty.
        UsageResult decoded = UsageSnapshotCodec.decode("{}");

        assertNotNull(decoded);
        assertEquals("", decoded.getAccountId());
        assertEquals("", decoded.getProviderId());
        assertEquals(UsageStatus.NO_DATA, decoded.getStatus());
        assertEquals(UsageResult.Source.CACHE, decoded.getSource());
        assertEquals(0L, decoded.getUpdatedAt());
        assertNull(decoded.getBalance());
        assertTrue(decoded.getQuotaWindows().isEmpty());
        assertTrue(decoded.getMetrics().isEmpty());
    }

    @Test
    public void decodeOfUnknownStatusAndSourceNamesFallsBackToDefaults() {
        String json = "{\"accountId\":\"acct-3\",\"providerId\":\"deepseek\","
                + "\"status\":\"SOMETHING_ELSE\",\"source\":\"NOPE\",\"updatedAt\":7}";

        UsageResult decoded = UsageSnapshotCodec.decode(json);

        assertNotNull(decoded);
        assertEquals("acct-3", decoded.getAccountId());
        assertEquals("deepseek", decoded.getProviderId());
        assertEquals(UsageStatus.NO_DATA, decoded.getStatus());
        assertEquals(UsageResult.Source.CACHE, decoded.getSource());
        assertEquals(7L, decoded.getUpdatedAt());
    }
}
