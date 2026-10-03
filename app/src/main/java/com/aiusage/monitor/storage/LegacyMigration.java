package com.aiusage.monitor.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.util.Money;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Imports the upstream app's state into the new schema, once.
 *
 * <p>The upstream release kept everything in one {@code SharedPreferences} file
 * named {@code deepseek_balance} with ten keys and a single global API key.
 * Upgrading must not lose the user's key or their accumulated "today usage", so
 * this class reads those keys, creates one DeepSeek account, and copies the
 * usage counters into the per-account table.
 *
 * <p>Two details matter for correctness:
 * <ul>
 *   <li>the old key is <em>removed</em> from preferences after a successful
 *       import, because leaving plaintext key material on disk after moving it
 *       into encrypted storage would defeat the point of the migration;</li>
 *   <li>the legacy preference file itself is left in place otherwise — the
 *       widget snapshot keys are still read by nothing, but deleting them would
 *       make a rollback lossy for no benefit.</li>
 * </ul>
 *
 * <p>The migration is idempotent: it records a flag in {@code app_meta} and
 * refuses to run twice, so an app that is killed mid-upgrade cannot end up with
 * two copies of the same account.
 */
public final class LegacyMigration {

    /** The upstream preference file. {@code MainActivity.java:56}. */
    public static final String LEGACY_PREFS = "deepseek_balance";

    /** The upstream API key slot. {@code MainActivity.java:57}. */
    public static final String LEGACY_KEY_API_KEY = "api_key";

    private static final String LEGACY_KEY_USAGE_DATE = "usage_date";
    private static final String LEGACY_KEY_LAST_BALANCE = "usage_last_balance";
    private static final String LEGACY_KEY_TOTAL_USAGE = "usage_total";

    private static final String META_FLAG = "legacy_migration_v1";

    private final Context context;
    private final AccountManager accountManager;
    private final Database database;

    public LegacyMigration(Context context, AccountManager accountManager, Database database) {
        this.context = context.getApplicationContext();
        this.accountManager = accountManager;
        this.database = database;
    }

    /** Result of a migration attempt, for logging and for the smoke script. */
    public static final class Result {
        public final boolean ran;
        public final boolean importedAccount;
        public final String accountId;

        Result(boolean ran, boolean importedAccount, String accountId) {
            this.ran = ran;
            this.importedAccount = importedAccount;
            this.accountId = accountId;
        }
    }

    /**
     * Runs the import if it has not run yet and there is something to import.
     *
     * <p>Never throws: a migration failure must not stop the app from starting.
     * If the credential cannot be written the legacy key is left untouched, so
     * the next launch can try again.
     */
    public Result runIfNeeded() {
        if (hasRun()) {
            return new Result(false, false, null);
        }

        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String apiKey = legacy.getString(LEGACY_KEY_API_KEY, "");
        if (TextUtils.isEmpty(apiKey)) {
            // Nothing to import. Mark it done so the check is not repeated on
            // every launch.
            markRun();
            return new Result(true, false, null);
        }

        // Only create the account if the user has none: if they already added
        // one by hand, importing a duplicate would be worse than dropping it.
        if (!accountManager.isEmpty()) {
            markRun();
            return new Result(true, false, null);
        }

        try {
            Account account = accountManager.create(
                    "deepseek",
                    "DeepSeek",
                    AuthType.API_KEY,
                    CredentialPayload.forApiKey(apiKey.trim()));
            importDailyUsage(account.getId(), legacy);
            // The plaintext key has been moved into encrypted storage, so the
            // old copy is removed. A rollback to the old build would require
            // re-entering the key, which is the correct trade for not leaving
            // a plaintext secret on disk.
            legacy.edit().remove(LEGACY_KEY_API_KEY).apply();
            markRun();
            return new Result(true, true, account.getId());
        } catch (AuthException exception) {
            return new Result(true, false, null);
        }
    }

    /** Copies the upstream global usage counters onto the imported account. */
    private void importDailyUsage(String accountId, SharedPreferences legacy) {
        String savedDate = legacy.getString(LEGACY_KEY_USAGE_DATE, "");
        String lastBalance = legacy.getString(LEGACY_KEY_LAST_BALANCE, "");
        String totalUsage = legacy.getString(LEGACY_KEY_TOTAL_USAGE, "");
        if (TextUtils.isEmpty(savedDate)) {
            return;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        // Only today's counter is meaningful: the upstream values carry no
        // history, and carrying yesterday's total into today would invent usage
        // that did not happen.
        String total = today.equals(savedDate) && !TextUtils.isEmpty(totalUsage)
                ? normalized(totalUsage)
                : "0.00";
        String last = today.equals(savedDate) ? normalized(lastBalance) : "";

        android.content.ContentValues values = new android.content.ContentValues();
        values.put("account_id", accountId);
        values.put("day", today);
        values.put("last_balance", last);
        values.put("total_usage", total);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_DAILY_USAGE, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Keeps an unparseable legacy value from poisoning the accumulator. */
    private static String normalized(String raw) {
        BigDecimal parsed = Money.parse2(raw);
        return parsed == null ? "0.00" : parsed.toPlainString();
    }

    private boolean hasRun() {
        try (android.database.Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_META, new String[]{"value"}, "key = ?",
                new String[]{META_FLAG}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    private void markRun() {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("key", META_FLAG);
        values.put("value", "1");
        database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_META, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }
}
