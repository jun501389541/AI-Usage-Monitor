package com.aiusage.monitor.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.aiusage.monitor.model.Balance;

import org.junit.Test;

import java.math.BigDecimal;

/**
 * Tests for {@link Money}: currency formatting and the daily-usage accumulator.
 *
 * <p>Pure JVM, no Android and no mocking. The accumulator cases mirror the four
 * branches of the upstream {@code UsageTracker.recordBalance} rule.
 */
public class MoneyTest {

    @Test
    public void formatUsesCnySymbolForCny() {
        assertEquals("¥38.52", Money.format("38.52", "CNY"));
    }

    @Test
    public void formatUsesDollarSymbolForUsd() {
        assertEquals("$17.82", Money.format("17.82", "USD"));
    }

    @Test
    public void formatAppendsCodeForOtherCurrencies() {
        assertEquals("12.00 EUR", Money.format("12.00", "EUR"));
    }

    @Test
    public void formatTrimsSurroundingWhitespace() {
        assertEquals("¥38.52", Money.format("  38.52  ", "CNY"));
    }

    @Test
    public void formatReturnsEmDashForEmptyAmount() {
        assertEquals("—", Money.format("", "CNY"));
    }

    @Test
    public void formatReturnsEmDashForNullAmount() {
        assertEquals("—", Money.format(null, "CNY"));
    }

    @Test
    public void formatReturnsEmDashForWhitespaceOnlyAmount() {
        assertEquals("—", Money.format("   ", "CNY"));
    }

    @Test
    public void formatReturnsBareAmountWhenCurrencyIsEmpty() {
        assertEquals("5.00", Money.format("5.00", ""));
    }

    @Test
    public void formatReturnsBareAmountWhenCurrencyIsNull() {
        assertEquals("5.00", Money.format("5.00", null));
    }

    @Test
    public void formatCurrencyMatchIsCaseInsensitive() {
        assertEquals("¥38.52", Money.format("38.52", "cny"));
        assertEquals("$17.82", Money.format("17.82", "usd"));
    }

    @Test
    public void formatBalancePrefersRawText() {
        assertEquals("¥38.50", Money.format(new Balance(38.5, "CNY", "38.50")));
    }

    @Test
    public void formatBalanceFallsBackToScaledAmountWhenRawTextIsEmpty() {
        assertEquals("¥38.50", Money.format(new Balance(38.5, "CNY", "")));
    }

    @Test
    public void formatBalanceFallsBackToScaledAmountWhenRawTextIsNull() {
        assertEquals("$17.82", Money.format(new Balance(17.82, "USD", null)));
    }

    @Test
    public void formatBalanceReturnsEmDashForNullBalance() {
        assertEquals("—", Money.format((Balance) null));
    }

    @Test
    public void parse2ReturnsValueScaledToTwoDecimals() {
        BigDecimal parsed = Money.parse2("38.52");
        assertNotNull(parsed);
        assertEquals("38.52", parsed.toPlainString());
        assertEquals(2, parsed.scale());
    }

    @Test
    public void parse2RoundsHalfUpToTwoDecimals() {
        BigDecimal parsed = Money.parse2("38.555");
        assertNotNull(parsed);
        assertEquals("38.56", parsed.toPlainString());
        assertEquals(2, parsed.scale());
    }

    @Test
    public void parse2TrimsBeforeParsing() {
        BigDecimal parsed = Money.parse2("  38.52  ");
        assertNotNull(parsed);
        assertEquals("38.52", parsed.toPlainString());
    }

    @Test
    public void parse2ReturnsNullForNonNumericText() {
        assertNull(Money.parse2("abc"));
    }

    @Test
    public void parse2ReturnsNullForEmptyString() {
        assertNull(Money.parse2(""));
    }

    @Test
    public void parse2ReturnsNullForWhitespaceOnlyString() {
        assertNull(Money.parse2("   "));
    }

    @Test
    public void parse2ReturnsNullForNull() {
        assertNull(Money.parse2(null));
    }

    @Test
    public void scale2RendersTwoDecimals() {
        assertEquals("38.50", Money.scale2(38.5));
    }

    @Test
    public void scale2RoundsHalfUp() {
        assertEquals("38.56", Money.scale2(38.555));
    }

    @Test
    public void scale2RendersZeroAsTwoDecimals() {
        assertEquals("0.00", Money.scale2(0.0));
    }

    @Test
    public void accumulateAddsTheFallWhenBalanceDropped() {
        assertMoney("2.48", Money.accumulate("1.00", "40.00", true, "38.52"));
    }

    @Test
    public void accumulateIgnoresTopUpWhenBalanceRose() {
        assertMoney("1.00", Money.accumulate("1.00", "40.00", true, "50.00"));
    }

    @Test
    public void accumulateIgnoresUnchangedBalance() {
        assertMoney("1.00", Money.accumulate("1.00", "40.00", true, "40.00"));
    }

    @Test
    public void accumulateResetsOnANewDay() {
        assertMoney("0.00", Money.accumulate("1.00", "40.00", false, "38.52"));
    }

    @Test
    public void accumulateStartsAtZeroWithoutPreviousReading() {
        assertMoney("0.00", Money.accumulate(null, null, true, "38.52"));
    }

    @Test
    public void accumulateReturnsNullForUnparseableCurrentReading() {
        assertNull(Money.accumulate("1.00", "40.00", true, "not-a-number"));
    }

    @Test
    public void accumulateReturnsNullForNullCurrentReading() {
        assertNull(Money.accumulate("1.00", "40.00", true, null));
    }

    @Test
    public void accumulateTreatsUnparseablePreviousTotalAsZero() {
        // The unparseable total becomes 0.00, and the 1.48 fall is then added to
        // it: 0.00 + (40.00 - 38.52) = 1.48. (The task brief predicted 2.48 here,
        // which is the value of the case where the total really is "1.00"; the
        // implementation is the authority, so 1.48 is asserted.)
        assertMoney("1.48", Money.accumulate("junk", "40.00", true, "38.52"));
    }

    @Test
    public void accumulateTreatsUnparseablePreviousBalanceAsNoComparison() {
        assertMoney("1.00", Money.accumulate("1.00", "junk", true, "38.52"));
    }

    @Test
    public void accumulateTreatsEmptyPreviousBalanceAsReset() {
        assertMoney("0.00", Money.accumulate("1.00", "", true, "38.52"));
    }

    /** Asserts the numeric value, its two-decimal scale, and its rendering. */
    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull("expected " + expected + " but got null", actual);
        assertEquals(expected, actual.toPlainString());
        assertEquals(2, actual.scale());
    }

    /**
     * A Codex account has no balance, so today's figure has nothing to be the
     * difference between. "0.00" would be a claim about spending; the dash is the
     * honest answer, and it is the same rule the account list and the detail
     * screen now share rather than each re-implementing.
     */
    @Test
    public void todayUsageNeedsSomethingToMeasureAgainst() {
        assertEquals(Money.EMPTY, Money.todayUsage(new java.math.BigDecimal("12.34"), "CNY", false));
        assertEquals(Money.EMPTY, Money.todayUsage(java.math.BigDecimal.ZERO, "", false));
    }

    @Test
    public void todayUsageFormatsWhenItCan() {
        assertEquals("¥12.35", Money.todayUsage(new java.math.BigDecimal("12.345"), "CNY", true));
        // No currency to symbolise: the number stands alone, which is what the
        // detail screen does for a provider that reports no currency at all.
        assertEquals("12.35", Money.todayUsage(new java.math.BigDecimal("12.345"), "", true));
        assertEquals("¥0.00", Money.todayUsage(java.math.BigDecimal.ZERO, "CNY", true));
    }
}
