package com.aiusage.monitor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Count is authoritative; null details mean only the count is known. */
public final class LimitResetCredits {
    private final long availableCount;
    private final List<Credit> credits;
    public LimitResetCredits(long availableCount, List<Credit> credits) {
        if (availableCount < 0) throw new IllegalArgumentException("Negative reset count");
        this.availableCount = availableCount;
        this.credits = credits == null ? null : Collections.unmodifiableList(new ArrayList<>(credits));
    }
    public long getAvailableCount() { return availableCount; }
    public List<Credit> getCredits() { return credits; }

    public static final class Credit {
        private final String resetType, status, title, description;
        private final Long grantedAt, expiresAt;
        private final boolean expiryKnown;
        public Credit(String resetType, String status, Long grantedAt, Long expiresAt,
                      boolean expiryKnown, String title, String description) {
            this.resetType = resetType == null ? "unknown" : resetType;
            this.status = status == null ? "unknown" : status;
            this.grantedAt = grantedAt;
            this.expiresAt = expiresAt;
            this.expiryKnown = expiryKnown;
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
        }
        public String getResetType() { return resetType; }
        public String getStatus() { return status; }
        public Long getGrantedAt() { return grantedAt; }
        public Long getExpiresAt() { return expiresAt; }
        public boolean isExpiryKnown() { return expiryKnown; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
    }
}
