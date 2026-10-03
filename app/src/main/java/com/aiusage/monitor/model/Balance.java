package com.aiusage.monitor.model;

/**
 * A monetary balance in a single currency. Spec §10.
 *
 * <p>Deliberately not a bare {@code double}: a balance is meaningless without
 * knowing whether "38.52" is CNY or USD. Spec §9 forbids collapsing a usage
 * result down to one number.
 *
 * <p>Carries the platform's original text alongside the parsed value. Providers
 * format money inconsistently ("38.5" versus "38.50"), and the upstream app
 * echoed the platform string verbatim, so keeping it lets this layer be
 * introduced without changing a single rendered character.
 */
public final class Balance {

    private final double amount;
    private final String currency;
    private final String rawText;

    public Balance(double amount, String currency) {
        this(amount, currency, null);
    }

    public Balance(double amount, String currency, String rawText) {
        this.amount = amount;
        this.currency = currency == null ? "" : currency;
        this.rawText = rawText == null ? "" : rawText.trim();
    }

    public double getAmount() {
        return amount;
    }

    /** ISO 4217 code such as {@code CNY} or {@code USD}; never null. */
    public String getCurrency() {
        return currency;
    }

    /**
     * The exact text the platform reported, or empty when the platform reported
     * only a number. Display code should prefer this over reformatting
     * {@link #getAmount()}.
     */
    public String getRawText() {
        return rawText;
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Balance)) {
            return false;
        }
        Balance other = (Balance) o;
        return Double.compare(amount, other.amount) == 0
                && currency.equals(other.currency)
                && rawText.equals(other.rawText);
    }

    @Override
    public int hashCode() {
        return (Double.hashCode(amount) * 31 + currency.hashCode()) * 31 + rawText.hashCode();
    }
}
