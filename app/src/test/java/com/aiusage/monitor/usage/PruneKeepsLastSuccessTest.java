package com.aiusage.monitor.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Pruning must never take the number off the screen with it.
 *
 * <p>Spec §26 asks for history to be kept; Spec §39 and rule 18 ask that a
 * widget never lose its last good reading. Retention is where those two
 * requirements can collide, and the collision is silent: the app keeps working,
 * the widget just shows an em dash for an account whose balance really was read
 * three weeks ago.
 *
 * <p>The repository here is the usual mirror of {@code SqliteUsageRepository} —
 * rows keyed by id, the same filters, the same ordering. What it can pin on a
 * host is the <em>interaction</em>: whatever the delete list contains, the two
 * reads the app depends on still answer. That the SQL deletes exactly those ids
 * is device acceptance H1 and H2, not claimed from here.
 */
public class PruneKeepsLastSuccessTest {

    private static final String ACCOUNT = "acct_keep";
    private static final String OTHER = "acct_other";
    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long RETENTION = 14 * DAY;

    /** Local midday, so "today" is unambiguous in any time zone. */
    private static final long NOW = midday();

    private static long midday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private final FakeRepository usage = new FakeRepository();

    private void record(String accountId, long at, double balance, boolean success) {
        UsageResult result = UsageResult.builder()
                .accountId(accountId)
                .providerId("deepseek")
                .balance(success ? new Balance(balance, "CNY", String.valueOf(balance)) : null)
                .status(success ? UsageStatus.OK : UsageStatus.NETWORK_ERROR)
                .updatedAt(at)
                .build();
        usage.save(accountId, result, success);
    }

    @Test
    public void pruningLeavesTheLastGoodReadingReadable() {
        // An account idle for six weeks. Its only success is the oldest row of
        // the three and far outside the window, and it is the number the widget
        // is showing; the two failures after it are the newest row and one that
        // nothing protects.
        record(ACCOUNT, NOW - 42 * DAY, 24.94d, true);
        record(ACCOUNT, NOW - 41 * DAY, 0d, false);
        record(ACCOUNT, NOW - 40 * DAY, 0d, false);

        int deleted = usage.prune(new SnapshotRetention(RETENTION), NOW);

        assertEquals("only the middle failure is neither the last success nor the newest",
                1, deleted);
        UsageResult latest = usage.latest(ACCOUNT);
        assertNotNull("the widget's number survives retention", latest);
        assertEquals(24.94d, latest.getBalance().getAmount(), 0.0001d);
        assertNotNull("and so does the status line's newest attempt", usage.latestAttempt(ACCOUNT));
    }

    @Test
    public void pruningIsIdempotent() {
        record(ACCOUNT, NOW - 30 * DAY, 10d, true);
        record(ACCOUNT, NOW - 20 * DAY, 9d, true);
        record(ACCOUNT, NOW - DAY, 8d, true);

        SnapshotRetention policy = new SnapshotRetention(RETENTION);
        int first = usage.prune(policy, NOW);
        int second = usage.prune(policy, NOW);

        // The newest success is protected; the two older ones are not.
        assertEquals(2, first);
        assertEquals("a second pass has nothing left to decide", 0, second);
    }

    @Test
    public void pruningNeverTouchesAnotherAccountsHistory() {
        record(ACCOUNT, NOW - 40 * DAY, 5d, true);
        record(OTHER, NOW - 40 * DAY, 6d, true);
        record(OTHER, NOW - 39 * DAY, 7d, true);
        record(OTHER, NOW - DAY, 8d, true);

        usage.prune(new SnapshotRetention(RETENTION), NOW);

        assertNotNull("a bystander's only reading is its last success", usage.latest(ACCOUNT));
        assertEquals(5d, usage.latest(ACCOUNT).getBalance().getAmount(), 0.0001d);
        assertEquals("the other account keeps only its protected rows",
                1, usage.rowCount(OTHER));
        assertEquals(8d, usage.latest(OTHER).getBalance().getAmount(), 0.0001d);
    }

    @Test
    public void anAccountWithNothingInsideTheWindowStillHasBothReads() {
        // The newest row is a failure and the last success is older still; both
        // are protected, so nothing goes even though everything is stale. The two
        // reads the app depends on still answer, which is the point.
        record(ACCOUNT, NOW - 100 * DAY, 2d, true);
        record(ACCOUNT, NOW - 99 * DAY, 0d, false);

        int deleted = usage.prune(new SnapshotRetention(RETENTION), NOW);

        assertEquals(0, deleted);
        assertEquals(2d, usage.latest(ACCOUNT).getBalance().getAmount(), 0.0001d);
        assertEquals(UsageStatus.NETWORK_ERROR, usage.latestAttempt(ACCOUNT).getStatus());
    }

    @Test
    public void anEmptyTablePrunesToNothing() {
        assertEquals(0, usage.prune(new SnapshotRetention(RETENTION), NOW));
        assertTrue(usage.rows.isEmpty());
    }

    /** A mirror of {@code SqliteUsageRepository}, rows and filters included. */
    private static final class FakeRepository implements UsageRepository {

        private final List<FakeRow> rows = new ArrayList<>();
        private long sequence;

        int rowCount(String accountId) {
            int count = 0;
            for (FakeRow row : rows) {
                if (accountId.equals(row.accountId)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void save(String accountId, UsageResult result, boolean success) {
            rows.add(new FakeRow(++sequence, accountId, result.getUpdatedAt(),
                    UsageSnapshotCodec.encode(result), success));
        }

        @Override
        public UsageResult latest(String accountId) {
            FakeRow newest = null;
            for (FakeRow row : rows) {
                if (accountId.equals(row.accountId) && row.success
                        && (newest == null || row.timestamp > newest.timestamp)) {
                    newest = row;
                }
            }
            return newest == null ? null : UsageSnapshotCodec.decode(newest.usageData);
        }

        @Override
        public UsageResult latestAttempt(String accountId) {
            FakeRow newest = null;
            for (FakeRow row : rows) {
                if (accountId.equals(row.accountId)
                        && (newest == null || row.timestamp > newest.timestamp)) {
                    newest = row;
                }
            }
            return newest == null ? null : UsageSnapshotCodec.decode(newest.usageData);
        }

        @Override
        public List<UsageSnapshot> history(String accountId, long fromInclusive,
                                           long toExclusive, int limit) {
            List<UsageSnapshot> found = new ArrayList<>();
            for (FakeRow row : rows) {
                if (accountId.equals(row.accountId) && row.timestamp >= fromInclusive
                        && row.timestamp < toExclusive) {
                    found.add(new UsageSnapshot(row.id, row.accountId, row.timestamp,
                            row.usageData, "", row.success));
                }
            }
            return found;
        }

        @Override
        public int prune(SnapshotRetention retention, long nowMs) {
            List<UsageSnapshot> light = new ArrayList<>();
            for (FakeRow row : rows) {
                light.add(new UsageSnapshot(row.id, row.accountId, row.timestamp, "", "",
                        row.success));
            }
            List<Long> doomed = retention.expired(light, nowMs);
            for (Long id : new ArrayList<>(doomed)) {
                for (FakeRow row : new ArrayList<>(rows)) {
                    if (row.id == id) {
                        rows.remove(row);
                    }
                }
            }
            return doomed.size();
        }

        @Override
        public void deleteForAccount(String accountId) {
            rows.removeIf(row -> accountId.equals(row.accountId));
        }

        @Override
        public BigDecimal recordDailyUsage(String accountId, String rawBalance) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal dailyUsage(String accountId) {
            return BigDecimal.ZERO;
        }
    }

    private static final class FakeRow {
        final long id;
        final String accountId;
        final long timestamp;
        final String usageData;
        final boolean success;

        FakeRow(long id, String accountId, long timestamp, String usageData, boolean success) {
            this.id = id;
            this.accountId = accountId;
            this.timestamp = timestamp;
            this.usageData = usageData;
            this.success = success;
        }
    }
}
