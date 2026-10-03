package com.aiusage.monitor.usage;

import com.aiusage.monitor.model.UsageResult;

/**
 * One stored observation of an account's usage. Spec §26.
 *
 * <p>The spec requires the snapshot table to exist from the first version even
 * though nothing draws a chart yet, because history cannot be reconstructed
 * after the fact. Each row records whether the fetch succeeded, so a failed
 * attempt is visible in the timeline rather than silently absent.
 *
 * <p>Field list follows the spec: {@code id / accountId / timestamp / usageData /
 * source / success}.
 */
public final class UsageSnapshot {

    private final long id;
    private final String accountId;
    private final long timestamp;
    private final String usageData;
    private final String source;
    private final boolean success;

    public UsageSnapshot(long id,
                         String accountId,
                         long timestamp,
                         String usageData,
                         String source,
                         boolean success) {
        this.id = id;
        this.accountId = accountId == null ? "" : accountId;
        this.timestamp = timestamp;
        this.usageData = usageData == null ? "" : usageData;
        this.source = source == null ? "" : source;
        this.success = success;
    }

    public long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** Serialised {@link UsageResult}. See {@code UsageSnapshotCodec}. */
    public String getUsageData() {
        return usageData;
    }

    public String getSource() {
        return source;
    }

    public boolean isSuccess() {
        return success;
    }

    /** Decodes the stored payload, or null when it cannot be read. */
    public UsageResult toUsageResult() {
        return UsageSnapshotCodec.decode(usageData);
    }

    @Override
    public String toString() {
        return "UsageSnapshot{account=" + accountId + ", at=" + timestamp + ", success=" + success + "}";
    }
}
