package com.aiusage.monitor.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.usage.SnapshotRetention;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.usage.UsageSnapshotCodec;
import com.aiusage.monitor.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SQLite-backed usage storage. Spec §25 and §26.
 *
 * <p>This is the only class in the app that reads or writes usage state. The
 * widget path and the app path both end here, which is what makes the two
 * consistent: they cannot drift because there is nothing for them to drift
 * from.
 */
public final class SqliteUsageRepository implements UsageRepository {

    /** At most this many ids per DELETE; SQLite caps the terms in one expression. */
    private static final int DELETE_CHUNK = 500;

    /** What {@code history()} returns when the caller asks for "some" rows. */
    private static final int DEFAULT_HISTORY_LIMIT = 100;

    /**
     * The most rows one call may return, whatever the caller asked for.
     *
     * <p>A history list is read onto a screen, and a screen cannot show ten
     * thousand rows; the cap keeps a mistaken {@code Integer.MAX_VALUE} from
     * turning into a cursor that outlives the activity.
     */
    private static final int MAX_HISTORY_LIMIT = 500;

    private final Database database;

    public SqliteUsageRepository(Context context) {
        this.database = Database.get(context);
    }

    @Override
    public void save(String accountId, UsageResult result, boolean success) {
        if (accountId == null || accountId.isEmpty() || result == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("timestamp", result.getUpdatedAt() > 0L ? result.getUpdatedAt() : System.currentTimeMillis());
        // Even a failed attempt is encoded as a result so the row shape is
        // uniform; the success column is what distinguishes it.
        values.put("usage_data", UsageSnapshotCodec.encode(result));
        values.put("source", result.getSource().name());
        values.put("success", success ? 1 : 0);
        long written = database.getWritableDatabase().insert(Database.TABLE_SNAPSHOTS, null, values);
        if (written < 0) {
            // A history row that SQLite never accepted must not be reported as history;
            // the caller's promise is "this attempt is on record", success or failure.
            throw new IllegalStateException("usage_snapshots refused the row for "
                    + accountId);
        }
    }

    @Override
    public UsageResult latest(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return null;
        }
        // Ordered by timestamp rather than by row id: a late-arriving snapshot
        // with an older timestamp must not win over a newer reading.
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_SNAPSHOTS,
                new String[]{"usage_data"},
                "account_id = ? AND success = 1",
                new String[]{accountId},
                null, null,
                "timestamp DESC, id DESC",
                "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return UsageSnapshotCodec.decode(cursor.getString(0));
        }
    }

    @Override
    public UsageResult latestAttempt(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return null;
        }
        // Same ordering as latest(), but without the success filter: the newest
        // row is the newest attempt whether it succeeded or failed. Failures are
        // stored with their status (see AccountRefreshManager.recordFailure), so
        // this decodes to a result the UI can read a status off directly.
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_SNAPSHOTS,
                new String[]{"usage_data"},
                "account_id = ?",
                new String[]{accountId},
                null, null,
                "timestamp DESC, id DESC",
                "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return UsageSnapshotCodec.decode(cursor.getString(0));
        }
    }

    @Override
    public List<UsageSnapshot> history(String accountId, long fromInclusive, long toExclusive,
                                       int limit) {
        List<UsageSnapshot> snapshots = new ArrayList<>();
        if (accountId == null || accountId.isEmpty()) {
            return snapshots;
        }
        int capped = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        // The range predicate comes first so the existing (account_id,
        // timestamp DESC) index serves both the filter and the ordering; the
        // device acceptance checks that with EXPLAIN QUERY PLAN rather than
        // assuming it.
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_SNAPSHOTS,
                new String[]{"id", "account_id", "timestamp", "usage_data", "source", "success"},
                "account_id = ? AND timestamp >= ? AND timestamp < ?",
                new String[]{accountId, String.valueOf(fromInclusive), String.valueOf(toExclusive)},
                null, null,
                "timestamp DESC, id DESC",
                String.valueOf(capped))) {
            while (cursor.moveToNext()) {
                snapshots.add(new UsageSnapshot(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getInt(5) == 1));
            }
        }
        return snapshots;
    }

    @Override
    public int prune(SnapshotRetention retention, long nowMs) {
        // Read the shape of every row, not its payload: the decision needs
        // account, timestamp and success only, and decoding a year of JSON to
        // work out which rows to throw away is wasted work on the smallest
        // device this runs on.
        List<UsageSnapshot> existing = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_SNAPSHOTS,
                new String[]{"id", "account_id", "timestamp", "success"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                existing.add(new UsageSnapshot(
                        cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                        "", "", cursor.getInt(3) == 1));
            }
        }

        List<Long> doomed = retention.expired(existing, nowMs);
        if (doomed.isEmpty()) {
            return 0;
        }

        // Explicit ids rather than one DELETE with a subquery: the list is what
        // the policy decided, so deleting exactly that list is what can be
        // asserted, and a device check can compare the surviving rows against the
        // same decision without guessing how SQLite chose to evaluate a filter.
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int from = 0; from < doomed.size(); from += DELETE_CHUNK) {
                List<Long> chunk = doomed.subList(from, Math.min(from + DELETE_CHUNK, doomed.size()));
                StringBuilder placeholders = new StringBuilder();
                String[] args = new String[chunk.size()];
                for (int index = 0; index < chunk.size(); index++) {
                    placeholders.append(index == 0 ? "?" : ",?");
                    args[index] = String.valueOf(chunk.get(index));
                }
                db.delete(Database.TABLE_SNAPSHOTS,
                        "id IN (" + placeholders + ")", args);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return doomed.size();
    }

    @Override
    public void deleteForAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return;
        }
        android.database.sqlite.SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(Database.TABLE_SNAPSHOTS, "account_id = ?", new String[]{accountId});
            db.delete(Database.TABLE_DAILY_USAGE, "account_id = ?", new String[]{accountId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public BigDecimal recordDailyUsage(String accountId, String rawBalance) {
        if (accountId == null || accountId.isEmpty()) {
            return null;
        }
        BigDecimal current = Money.parse2(rawBalance);
        if (current == null) {
            return null;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String previousTotal = null;
        String previousBalance = null;
        boolean sameDay = false;

        // R1: the row must be looked up by (account_id, day), not by account_id
        // alone. daily_usage's primary key is (account_id, day) and SQLite walks
        // it in ascending day order, so an unfiltered LIMIT 1 handed back
        // *yesterday's* row as soon as one existed -- which made sameDay false
        // and silently reset today's accumulated usage to 0.00 on every refresh.
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_DAILY_USAGE,
                new String[]{"day", "last_balance", "total_usage"},
                "account_id = ? AND day = ?",
                new String[]{accountId, today},
                null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                sameDay = today.equals(cursor.getString(0));
                previousBalance = cursor.getString(1);
                previousTotal = cursor.getString(2);
            }
        }

        // The rule itself lives in Money so it is unit-testable without a
        // database; this method only supplies the stored previous state.
        BigDecimal total = Money.accumulate(previousTotal, previousBalance, sameDay, current.toPlainString());
        if (total == null) {
            total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("day", today);
        values.put("last_balance", current.toPlainString());
        values.put("total_usage", total.toPlainString());
        values.put("updated_at", System.currentTimeMillis());
        long written = database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_DAILY_USAGE, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        if (written < 0) {
            // Returning `total` after a refused write would tell the caller the day's
            // accumulator advanced when the table says it did not.
            throw new IllegalStateException("daily_usage refused the row for " + accountId);
        }

        return total;
    }

    @Override
    public BigDecimal dailyUsage(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_DAILY_USAGE,
                new String[]{"total_usage"},
                "account_id = ? AND day = ?",
                new String[]{accountId, today},
                null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                BigDecimal stored = Money.parse2(cursor.getString(0));
                if (stored != null) {
                    return stored;
                }
            }
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}
