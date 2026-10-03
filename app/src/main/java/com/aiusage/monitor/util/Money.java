package com.aiusage.monitor.util;

import com.aiusage.monitor.model.Balance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money formatting and the daily-usage accumulator arithmetic.
 *
 * <p>Pure and Android-free so both can be unit-tested on a plain JVM, which
 * matters because the upstream versions of this logic lived inside an Activity
 * and a preferences-reading helper and therefore could not be tested at all.
 *
 * <p>Formatting reproduces the upstream rules exactly
 * ({@code MainActivity.java:629-640} and {@code WidgetRefreshReceiver.java:63}):
 * CNY renders as {@code ¥38.52}, USD as {@code $17.82}, anything else as
 * {@code 12.00 EUR}, and an empty amount as an em dash.
 */
public final class Money {

    /** Shown when there is no value to display. */
    public static final String EMPTY = "—";

    private Money() {
    }

    /** Formats an amount string with its currency symbol. */
    public static String format(String amount, String currency) {
        if (amount == null || amount.trim().isEmpty()) {
            return EMPTY;
        }
        String value = amount.trim();
        if ("CNY".equalsIgnoreCase(currency)) {
            return "¥" + value;
        }
        if ("USD".equalsIgnoreCase(currency)) {
            return "$" + value;
        }
        if (currency == null || currency.isEmpty()) {
            return value;
        }
        return value + " " + currency;
    }

    /**
     * The 今日用量 value, or the dash when there is nothing to measure against.
     *
     * <p>Today's figure is the difference between balance readings, so an account
     * with no balance (Codex reports quota windows, not money) has no estimate at
     * all. Showing "0.00" there reads as "spent nothing" rather than "not
     * measurable" — the mistake the account list already guarded against with an
     * inline check, which this replaces so the two surfaces cannot drift.
     *
     * @param measurable true when the provider reports a balance
     */
    public static String todayUsage(java.math.BigDecimal amount, String currency, boolean measurable) {
        if (!measurable) {
            return EMPTY;
        }
        return format(scale2(amount.doubleValue()), currency);
    }

    /** Formats a {@link Balance}, preferring the platform's original text. */
    public static String format(Balance balance) {
        if (balance == null) {
            return EMPTY;
        }
        String text = balance.getRawText();
        if (text.isEmpty()) {
            text = scale2(balance.getAmount());
        }
        return format(text, balance.getCurrency());
    }

    /** Formats an amount to exactly two decimals. */
    public static String scale2(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Parses an amount to two decimals, returning null when unparseable. */
    public static BigDecimal parse2(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * The daily-usage rule, ported verbatim from
     * {@code UsageTracker.recordBalance} (upstream {@code UsageTracker.java:20-61}).
     *
     * <p>Today's spend is inferred from how much the balance <em>fell</em> since
     * the previous reading. A balance that went up (a top-up) contributes
     * nothing, and a new day resets the accumulator. The upstream code applied
     * this globally to one account; here the caller supplies the previous state
     * for one account, which is what makes per-account usage possible.
     *
     * @param previousTotal  accumulated usage so far today, or null
     * @param previousBalance last reading, or null
     * @param isSameDay      whether the previous reading belongs to today
     * @param currentBalance the new reading
     * @return the new accumulated total
     */
    public static BigDecimal accumulate(String previousTotal,
                                        String previousBalance,
                                        boolean isSameDay,
                                        String currentBalance) {
        BigDecimal current = parse2(currentBalance);
        if (current == null) {
            return null;
        }

        BigDecimal total = parse2(previousTotal);
        if (total == null) {
            total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        if (!isSameDay || previousBalance == null || previousBalance.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal last = parse2(previousBalance);
        if (last != null && current.compareTo(last) < 0) {
            total = total.add(last.subtract(current)).setScale(2, RoundingMode.HALF_UP);
        }
        return total;
    }
}
