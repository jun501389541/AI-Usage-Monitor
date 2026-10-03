package com.aiusage.monitor.usage;

import com.aiusage.monitor.model.UsageResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * The single source of usage data. Spec §25.
 *
 * <p>Every reader — the app UI, the widgets, history and the background
 * refresher — goes through this interface. Nothing else is allowed to hold
 * usage state, and nothing is allowed to call a provider directly:
 *
 * <blockquote>禁止 Widget 自己请求 Provider，也禁止 MainActivity 自己请求
 * DeepSeek。</blockquote>
 *
 * <p>Two kinds of data live here, both keyed by account id:
 * <ul>
 *   <li>snapshots — the append-only history required from the first version
 *       (§26), of which the newest successful row is what the widgets draw;</li>
 *   <li>the daily usage accumulator — today's spend, inferred from how far the
 *       balance fell, kept per account rather than globally.</li>
 * </ul>
 */
public interface UsageRepository {

    /**
     * Appends a snapshot.
     *
     * <p>Failed attempts are stored too, with {@code success = false}, so the
     * timeline shows that a fetch was tried and failed. A failure must never
     * remove the previous successful row — that row is the widget's fallback.
     * Spec §39, rule 18.
     */
    void save(String accountId, UsageResult result, boolean success);

    /**
     * The most recent successful result for an account, or null when there is
     * none yet.
     */
    UsageResult latest(String accountId);

    /**
     * The most recent attempt for an account, successful or not.
     *
     * <p>Complements {@link #latest}: {@code latest} is what the widgets draw
     * (the last good reading, per Spec §39 rule 18, which a failure must never
     * erase), while this is what the account list shows as <em>status</em>.
     * Reading them apart is what lets the UI keep displaying a real balance
     * while honestly reporting that the newest attempt failed — the two facts
     * are different and neither should hide the other.
     *
     * <p>Null only when the account has never been fetched at all.
     */
    UsageResult latestAttempt(String accountId);

    /**
     * Saved readings for one account inside a time window, newest first.
     * Spec §26.
     *
     * <p>The window is {@code [fromInclusive, toExclusive)} — closed at the
     * start, open at the end. That is the same day boundary the daily-usage
     * accumulator resets on, so a reading counted into "today" can never also be
     * counted into "tomorrow" by an off-by-one at midnight.
     *
     * <p>Failed attempts are included: "the query failed that afternoon" is a
     * fact about the account's history, and a history that only shows successes
     * would quietly rewrite it. {@link #latest} is the call that filters to
     * successful readings; this one reports what happened.
     *
     * @param fromInclusive oldest timestamp to return, in epoch milliseconds
     * @param toExclusive   upper bound, excluded; pass {@code Long.MAX_VALUE}
     *                      for "up to the newest row"
     * @param limit         most rows to return; {@code 0} means the implementation's
     *                      default cap rather than no limit, so a caller cannot
     *                      ask for an unbounded read by accident
     */
    List<UsageSnapshot> history(String accountId, long fromInclusive, long toExclusive, int limit);

    /**
     * Applies the retention policy and returns how many rows it deleted.
     * Spec §26, §39.
     *
     * <p>The policy decides and this executes: the rows are read without their
     * payloads, the ids to drop come back from {@link SnapshotRetention}, and the
     * delete happens in one transaction. Nothing here chooses on its own, because
     * a wrong choice silently destroys readings that can never be fetched again.
     *
     * <p>Must not be called on a UI thread; the background refresh path owns it.
     */
    int prune(SnapshotRetention retention, long nowMs);

    /** Removes an account's history. Used when an account is deleted. */
    void deleteForAccount(String accountId);

    /**
     * Folds a new balance reading into today's usage total for one account and
     * returns the accumulated total, or null when the reading is unparseable.
     *
     * <p>Implements the upstream rule exactly ({@code UsageTracker.java:20-61}):
     * a fall in the balance counts as spend, a rise is a top-up and counts as
     * nothing, and a new day starts from zero. The difference from upstream is
     * that the accumulator is per account instead of one global counter.
     */
    BigDecimal recordDailyUsage(String accountId, String rawBalance);

    /** Today's accumulated usage for an account, or zero when none recorded. */
    BigDecimal dailyUsage(String accountId);
}
