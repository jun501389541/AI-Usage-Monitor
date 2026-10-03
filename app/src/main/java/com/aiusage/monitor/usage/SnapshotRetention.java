package com.aiusage.monitor.usage;

import com.aiusage.monitor.util.Freshness;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which stored readings retention may delete. Spec §26 and §39.
 *
 * <p>Spec §26 asks for history from the first version; nothing in the spec asks
 * for it to grow without limit, and at the measured rate of roughly 52 KB a day
 * for two accounts an unbounded table is a year of writing to the same few
 * hundred kilobytes of a phone's storage. Retention is the part that can destroy
 * data the app is still displaying, so the decision lives here as plain logic:
 * the storage layer executes a list of ids this class produced, and every rule
 * below is asserted without a database in sight.
 *
 * <p>The rules, in the order they are applied per account:
 *
 * <ol>
 *   <li>a reading newer than the retention window stays;</li>
 *   <li>the account's <em>last successful</em> reading stays however old it is —
 *     deleting it would blank the widget and the list, which is the one thing
 *     Spec §39 forbids;</li>
 *   <li>the account's newest row stays even when it is a failure, because that
 *     is what the status line shows;</li>
 *   <li>every reading from today stays, so the day the user is looking at is
 *     never half missing;</li>
 *   <li>everything else goes. Failures are not second-class: "the query failed
 *     that afternoon" is part of the account's history.</li>
 * </ol>
 */
public final class SnapshotRetention {

    /**
     * How long individual readings are kept.
     *
     * <p>Fixed rather than configurable on purpose: a setting implies a user who
     * can predict what they will want to look at in a year, while the day-grain
     * facts in {@code daily_usage} are kept indefinitely regardless, so the
     * window only decides the resolution of the recent past.
     */
    public static final int DETAIL_RETENTION_DAYS = 14;

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private final long retentionMs;

    public SnapshotRetention() {
        this(DETAIL_RETENTION_DAYS * DAY_MS);
    }

    /** Package-visible window override so the boundary cases are testable. */
    SnapshotRetention(long retentionMs) {
        this.retentionMs = retentionMs;
    }

    /**
     * The ids of the rows retention may delete.
     *
     * @param rows every stored reading for one or more accounts, in any order —
     *             "any" is a promise this class keeps, including for rows that
     *             share an instant, which are ordered by id the way the read path
     *             orders them
     * @param nowMs the instant age is judged against, passed in rather than read
     *              from the clock: a rule about "today" that reads the wall
     *              clock cannot be tested near midnight, and this project has
     *              already been burned by a day-boundary rule it could not pin
     */
    public List<Long> expired(List<UsageSnapshot> rows, long nowMs) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        long cutoff = nowMs - retentionMs;

        // Newest row and newest success per account, in one pass over a copy
        // sorted oldest-first so "newest" is simply the last one seen.
        List<UsageSnapshot> ordered = new ArrayList<>(rows);
        // Collections.sort with an explicit comparator: List#sort and
        // Comparator.comparingLong are both API 24 and this app supports 23.
        Collections.sort(ordered, new Comparator<UsageSnapshot>() {
            @Override
            public int compare(UsageSnapshot left, UsageSnapshot right) {
                int byTime = Long.compare(left.getTimestamp(), right.getTimestamp());
                if (byTime != 0) {
                    return byTime;
                }
                // Same instant: agree with the read path, which calls the largest
                // id the newest row (`timestamp DESC, id DESC`). Sorting by time
                // alone leaves the tie to the order the cursor returned, so
                // retention could protect a different row than the one the UI
                // reads back (docs/PHASE-0-7-REVIEW.md §2.3).
                return Long.compare(left.getId(), right.getId());
            }
        });
        Map<String, Long> newestRow = new HashMap<>();
        Map<String, Long> newestSuccess = new HashMap<>();
        for (UsageSnapshot row : ordered) {
            String accountId = row.getAccountId();
            newestRow.put(accountId, row.getId());
            if (row.isSuccess()) {
                newestSuccess.put(accountId, row.getId());
            }
        }

        Set<Long> keep = new HashSet<>();
        for (UsageSnapshot row : rows) {
            boolean tooOld = row.getTimestamp() < cutoff;
            if (!tooOld) {
                keep.add(row.getId());
                continue;
            }
            Long lastSuccess = newestSuccess.get(row.getAccountId());
            if (lastSuccess != null && lastSuccess == row.getId()) {
                keep.add(row.getId());
                continue;
            }
            Long newest = newestRow.get(row.getAccountId());
            if (newest != null && newest == row.getId()) {
                keep.add(row.getId());
                continue;
            }
            Calendar at = Calendar.getInstance();
            at.setTimeInMillis(row.getTimestamp());
            if (Freshness.isSameDay(at, nowMs)) {
                keep.add(row.getId());
            }
        }

        List<Long> doomed = new ArrayList<>();
        for (UsageSnapshot row : rows) {
            if (!keep.contains(row.getId())) {
                doomed.add(row.getId());
            }
        }
        // Sorted so the answer depends on the data and not on the order the
        // cursor happened to return it in. The storage layer pastes these into an
        // IN (...) list, and a stable list is one a device check can compare.
        Collections.sort(doomed);
        return doomed;
    }
}
