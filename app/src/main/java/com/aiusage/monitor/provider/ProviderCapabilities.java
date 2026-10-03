package com.aiusage.monitor.provider;

import com.aiusage.monitor.auth.AuthType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a provider can do, declared up front. Spec §4 and §34.
 *
 * <p>The UI reads this instead of hard-coding assumptions about a platform: for
 * example the widget needs to know whether a provider reports a monetary
 * balance before deciding to render one, and the slot picker lists exactly the
 * metrics the account can supply rather than a fixed set the provider may not
 * fill.
 */
public final class ProviderCapabilities {

    private final Set<AuthType> supportedAuthTypes;
    private final boolean reportsBalance;
    private final boolean reportsQuotaWindows;
    private final boolean reportsMetrics;
    private final long recommendedRefreshIntervalMs;
    private final List<WidgetMetric> widgetMetrics;

    private ProviderCapabilities(Builder builder) {
        this.supportedAuthTypes = Collections.unmodifiableSet(
                builder.supportedAuthTypes.isEmpty()
                        ? EnumSet.noneOf(AuthType.class)
                        : EnumSet.copyOf(builder.supportedAuthTypes));
        this.reportsBalance = builder.reportsBalance;
        this.reportsQuotaWindows = builder.reportsQuotaWindows;
        this.reportsMetrics = builder.reportsMetrics;
        this.recommendedRefreshIntervalMs = builder.recommendedRefreshIntervalMs;
        this.widgetMetrics = Collections.unmodifiableList(new ArrayList<>(builder.widgetMetrics));
    }

    public Set<AuthType> getSupportedAuthTypes() {
        return supportedAuthTypes;
    }

    public boolean supportsAuthType(AuthType type) {
        return supportedAuthTypes.contains(type);
    }

    public boolean reportsBalance() {
        return reportsBalance;
    }

    public boolean reportsQuotaWindows() {
        return reportsQuotaWindows;
    }

    public boolean reportsMetrics() {
        return reportsMetrics;
    }

    /**
     * Provider's own suggestion, which the user may still override. Spec §43.
     */
    public long getRecommendedRefreshIntervalMs() {
        return recommendedRefreshIntervalMs;
    }

    /**
     * What this provider lets a widget show, in the order it should be offered.
     * Spec §34. Never null; empty for a provider that publishes nothing.
     */
    public List<WidgetMetric> getWidgetMetrics() {
        return widgetMetrics;
    }

    /**
     * Whether this provider lets a widget show {@code id}. Null-returning
     * lookup is deliberately absent: the widget only ever asks "may I show
     * this", and a second accessor would be a second way to answer it.
     */
    public boolean supportsWidgetMetric(String id) {
        for (WidgetMetric metric : widgetMetrics) {
            if (metric.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Set<AuthType> supportedAuthTypes = EnumSet.noneOf(AuthType.class);
        private final List<WidgetMetric> widgetMetrics = new ArrayList<>();
        private boolean reportsBalance;
        private boolean reportsQuotaWindows;
        private boolean reportsMetrics;
        private long recommendedRefreshIntervalMs = 30 * 60 * 1000L;

        public Builder supports(AuthType type) {
            if (type != null) {
                this.supportedAuthTypes.add(type);
            }
            return this;
        }

        public Builder reportsBalance(boolean value) {
            this.reportsBalance = value;
            return this;
        }

        public Builder reportsQuotaWindows(boolean value) {
            this.reportsQuotaWindows = value;
            return this;
        }

        public Builder reportsMetrics(boolean value) {
            this.reportsMetrics = value;
            return this;
        }

        public Builder recommendedRefreshIntervalMs(long value) {
            this.recommendedRefreshIntervalMs = value;
            return this;
        }

        /**
         * Declares one metric a widget may show for this provider, in the order
         * the picker should offer them.
         */
        public Builder widgetMetric(String id, String label) {
            this.widgetMetrics.add(new WidgetMetric(id, label));
            return this;
        }

        public ProviderCapabilities build() {
            return new ProviderCapabilities(this);
        }
    }
}
