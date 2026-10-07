package com.aiusage.monitor.refresh;

import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;

/** Display state derived from an account's last success and newest attempt. */
public class AccountRefreshView {

    /** The last successful reading, or null when there has never been one. */
    public final UsageResult lastSuccess;
    /** The newest attempt, successful or not; null only if never fetched. */
    public final UsageResult lastAttempt;

    public AccountRefreshView(UsageResult lastSuccess, UsageResult lastAttempt) {
        this.lastSuccess = lastSuccess;
        this.lastAttempt = lastAttempt;
    }

    /** The newest attempt's status, so a later failure remains visible. */
    public UsageStatus status() {
        if (lastAttempt != null) {
            return lastAttempt.getStatus();
        }
        return lastSuccess == null ? UsageStatus.NO_DATA : lastSuccess.getStatus();
    }

    /** Status for display, including whether the last successful reading is stale. */
    public UsageStatus displayStatus(long intervalMs, long now) {
        if (lastAttempt != null && lastAttempt.getStatus() != UsageStatus.OK) {
            return lastAttempt.getStatus();
        }
        UsageResult display = lastAttempt != null ? lastAttempt : lastSuccess;
        if (display == null) {
            return UsageStatus.NO_DATA;
        }
        if (RefreshPolicy.isStale(display.getUpdatedAt(), intervalMs, now)) {
            return UsageStatus.STALE;
        }
        return display.getStatus();
    }

    /** True when the last attempt failed but an older success is retained. */
    public boolean showingRetainedData() {
        if (lastAttempt == null || lastSuccess == null) {
            return false;
        }
        return lastAttempt.getStatus() != UsageStatus.OK
                && lastAttempt.getUpdatedAt() >= lastSuccess.getUpdatedAt();
    }
}
