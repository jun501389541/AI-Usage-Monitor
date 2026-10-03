package com.aiusage.monitor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single shape every provider returns. Spec §9.
 *
 * <p>Providers are wildly different — DeepSeek reports a CNY balance, Codex
 * reports 5-hour and weekly percentages, OpenRouter reports a USD balance — so
 * this deliberately is not "a double balance". Monetary values live in
 * {@link #balance}, windowed percentages in {@link #quotaWindows}, and anything
 * else in {@link #metrics}, which means a new provider can report new data
 * without changing this class. Spec §12.
 */
public final class UsageResult {

    /**
     * The well-known metric key a provider uses to report whether the account is
     * currently usable — DeepSeek's {@code is_available}, and the equivalent flag
     * for any future platform.
     *
     * <p>It lives here rather than in a provider so the UI can render "account
     * available" without importing a provider's package, which is what keeps the
     * screen from becoming DeepSeek-specific. A provider that does not report
     * the flag simply leaves the metric out.
     */
    public static final String METRIC_ACCOUNT_AVAILABLE = "is_available";

    /** Where the data came from, so the UI can explain its freshness. Spec §9. */
    public enum Source {
        /** Fetched directly from the provider's public API. */
        DIRECT_API,
        /** Fetched through a local bridge process. */
        BRIDGE,
        /** Restored from a cached snapshot because a refresh failed. */
        CACHE
    }

    private final String accountId;
    private final String providerId;
    private final Balance balance;
    private final List<QuotaWindow> quotaWindows;
    private final List<Metric> metrics;
    private final UsageStatus status;
    private final long updatedAt;
    private final Source source;

    private UsageResult(Builder builder) {
        this.accountId = builder.accountId;
        this.providerId = builder.providerId;
        this.balance = builder.balance;
        this.quotaWindows = Collections.unmodifiableList(new ArrayList<>(builder.quotaWindows));
        this.metrics = Collections.unmodifiableList(new ArrayList<>(builder.metrics));
        this.status = builder.status;
        this.updatedAt = builder.updatedAt;
        this.source = builder.source;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getProviderId() {
        return providerId;
    }

    /** May be null for providers that report no monetary balance. */
    public Balance getBalance() {
        return balance;
    }

    /** Never null; empty when the provider reports no windows. */
    public List<QuotaWindow> getQuotaWindows() {
        return quotaWindows;
    }

    /** Never null; empty when the provider reports nothing else. */
    public List<Metric> getMetrics() {
        return metrics;
    }

    public UsageStatus getStatus() {
        return status;
    }

    /** Epoch millis at which this data was obtained. */
    public long getUpdatedAt() {
        return updatedAt;
    }

    public Source getSource() {
        return source;
    }

    /** Finds a metric by key, or null. */
    public Metric findMetric(String key) {
        for (Metric metric : metrics) {
            if (metric.getKey().equals(key)) {
                return metric;
            }
        }
        return null;
    }

    /** Finds a quota window by id, or null. */
    public QuotaWindow findQuotaWindow(String id) {
        for (QuotaWindow window : quotaWindows) {
            if (window.getId().equals(id)) {
                return window;
            }
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns a copy marked {@link UsageStatus#STALE} without losing any data. */
    public UsageResult asStale() {
        return toBuilder().status(UsageStatus.STALE).build();
    }

    /** Returns a copy carrying a different status but identical payload. */
    public UsageResult withStatus(UsageStatus newStatus) {
        return toBuilder().status(newStatus).build();
    }

    public Builder toBuilder() {
        return new Builder()
                .accountId(accountId)
                .providerId(providerId)
                .balance(balance)
                .quotaWindows(quotaWindows)
                .metrics(metrics)
                .status(status)
                .updatedAt(updatedAt)
                .source(source);
    }

    public static final class Builder {

        private String accountId = "";
        private String providerId = "";
        private Balance balance;
        private List<QuotaWindow> quotaWindows = new ArrayList<>();
        private List<Metric> metrics = new ArrayList<>();
        private UsageStatus status = UsageStatus.OK;
        private long updatedAt = System.currentTimeMillis();
        private Source source = Source.DIRECT_API;

        public Builder accountId(String value) {
            this.accountId = value == null ? "" : value;
            return this;
        }

        public Builder providerId(String value) {
            this.providerId = value == null ? "" : value;
            return this;
        }

        public Builder balance(Balance value) {
            this.balance = value;
            return this;
        }

        public Builder quotaWindows(List<QuotaWindow> value) {
            this.quotaWindows = value == null ? new ArrayList<QuotaWindow>() : value;
            return this;
        }

        public Builder addQuotaWindow(QuotaWindow value) {
            if (value != null) {
                this.quotaWindows.add(value);
            }
            return this;
        }

        public Builder metrics(List<Metric> value) {
            this.metrics = value == null ? new ArrayList<Metric>() : value;
            return this;
        }

        public Builder addMetric(Metric value) {
            if (value != null) {
                this.metrics.add(value);
            }
            return this;
        }

        public Builder status(UsageStatus value) {
            this.status = value == null ? UsageStatus.OK : value;
            return this;
        }

        public Builder updatedAt(long value) {
            this.updatedAt = value;
            return this;
        }

        public Builder source(Source value) {
            this.source = value == null ? Source.DIRECT_API : value;
            return this;
        }

        public UsageResult build() {
            return new UsageResult(this);
        }
    }
}
