package com.aiusage.monitor.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * The app's single SQLite database. Spec §45 ({@code storage/Database}).
 *
 * <p>Replaces the upstream project's ten scattered {@code SharedPreferences}
 * keys with real tables. The important consequences are:
 *
 * <ul>
 *   <li>Usage history is kept from the first version, as the spec requires
 *       (§26), so charts and burn-rate estimates become possible later without
 *       a data migration.</li>
 *   <li>Today's usage accumulator is keyed by account, fixing an upstream bug
 *       where one global counter was shared by every account.</li>
 *   <li>Accounts and credentials are separate rows, so changing a key does not
 *       disturb an account's identity or its history. Spec §14.</li>
 * </ul>
 */
public final class Database extends SQLiteOpenHelper {

    private static final String NAME = "ai_usage_monitor.db";
    /**
     * Version 3 adds {@code bridges}: the paired computers, their addresses and
     * their pinned fingerprints. Spec §21/§22, and the debt Phase 6 recorded when
     * it put a Bridge's URL inside the credential payload (docs/PHASE-6-PLAN.md A3).
     * Version 4 adds account pinning while preserving the existing sort order.
     */
    private static final int VERSION = 5;

    static final String TABLE_ACCOUNTS = "accounts";
    static final String TABLE_CREDENTIALS = "credentials";
    static final String TABLE_SNAPSHOTS = "usage_snapshots";
    static final String TABLE_WIDGET_CONFIG = "widget_config";
    static final String TABLE_WIDGET_SLOTS = "widget_slots";
    static final String TABLE_DAILY_USAGE = "daily_usage";
    static final String TABLE_META = "app_meta";
    static final String TABLE_BRIDGES = "bridges";

    /**
     * What a slot shows when nothing was chosen, spelled exactly as
     * {@code WidgetMetricId.encode(WidgetMetricId.defaults())} spells it.
     *
     * <p>Public so the widget layer can pin the two spellings against each other:
     * repeated here as SQL because version 1 had no metric column at all, so the
     * migration has to invent the value, and a drift would decode every upgraded
     * widget as showing nothing.
     */
    public static final String MIGRATION_DEFAULT_METRICS = "balance|today_usage";

    private static volatile Database instance;

    private Database(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    /** Process-wide instance; the helper is thread-safe once opened. */
    public static Database get(Context context) {
        if (instance == null) {
            synchronized (Database.class) {
                if (instance == null) {
                    instance = new Database(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            // Also install on existing v4 databases; this adds no columns or data migration.
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_account_attempt ON "
                    + TABLE_SNAPSHOTS + " (account_id, id DESC)");
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_ACCOUNTS + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "provider_id TEXT NOT NULL,"
                + "display_name TEXT NOT NULL,"
                + "auth_type TEXT NOT NULL,"
                + "credential_id TEXT NOT NULL DEFAULT '',"
                + "bridge_id TEXT NOT NULL DEFAULT '',"
                + "direct_credential_id TEXT NOT NULL DEFAULT '',"
                + "direct_identity_hash TEXT NOT NULL DEFAULT '',"
                + "direct_enabled INTEGER NOT NULL DEFAULT 0,"
                + "direct_needs_auth INTEGER NOT NULL DEFAULT 0,"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "pinned INTEGER NOT NULL DEFAULT 0,"
                + "sort_order INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_CREDENTIALS + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "type TEXT NOT NULL,"
                // Ciphertext only. Spec §7 forbids plaintext credentials in the
                // database, so no code path may write plaintext into this column.
                + "encrypted_payload TEXT NOT NULL,"
                + "protection TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_SNAPSHOTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "account_id TEXT NOT NULL,"
                + "timestamp INTEGER NOT NULL,"
                + "usage_data TEXT NOT NULL,"
                + "source TEXT NOT NULL,"
                + "success INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_snapshots_account_time ON "
                + TABLE_SNAPSHOTS + " (account_id, timestamp DESC)");

        db.execSQL("CREATE TABLE " + TABLE_WIDGET_CONFIG + " ("
                + "widget_id INTEGER PRIMARY KEY NOT NULL,"
                + "widget_type TEXT NOT NULL,"
                // Legacy column, still NOT NULL because SQLite cannot drop a
                // column on every supported API level. Version 2 moved the
                // binding to widget_slots and the migration clears it, so after
                // an upgrade it reads '' and no code path may treat it as a
                // binding.
                + "account_id TEXT NOT NULL DEFAULT '',"
                + "refresh_interval_ms INTEGER NOT NULL DEFAULT 600000,"
                + "sort_order INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL)");

        db.execSQL(createWidgetSlotsTable(false));
        // Which widgets show a given account. Spec §38 asks for the reverse of
        // the usual lookup — refresh one account, repaint every widget that
        // references it in any slot — and that query is this index.
        db.execSQL(createWidgetSlotsIndex(false));

        db.execSQL("CREATE TABLE " + TABLE_DAILY_USAGE + " ("
                + "account_id TEXT NOT NULL,"
                + "day TEXT NOT NULL,"
                + "last_balance TEXT NOT NULL DEFAULT '',"
                + "total_usage TEXT NOT NULL DEFAULT '0.00',"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY (account_id, day))");

        db.execSQL("CREATE TABLE " + TABLE_META + " ("
                + "key TEXT PRIMARY KEY NOT NULL,"
                + "value TEXT NOT NULL)");

        db.execSQL(createBridgesTable(false));
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            migrateWidgetSlots(db);
        }
        if (oldVersion < 3) {
            migrateBridges(db);
        }
        if (oldVersion < 4) {
            migratePinnedAccounts(db);
        }
        if (oldVersion < 5) {
            migrateDirectCodexAccounts(db);
        }
        // The upstream app's own data is imported by LegacyMigration rather than
        // here; this method only carries the new schema forward.
    }

    /** Version 3 to 4: add a default-unpinned flag without rewriting account order. */
    private static void migratePinnedAccounts(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS
                    + " ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Version 4 to 5: an optional, independent phone OAuth source per account. */
    private static void migrateDirectCodexAccounts(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS
                    + " ADD COLUMN direct_credential_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS
                    + " ADD COLUMN direct_identity_hash TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS
                    + " ADD COLUMN direct_enabled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS
                    + " ADD COLUMN direct_needs_auth INTEGER NOT NULL DEFAULT 0");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Version 2 to 3: the {@code bridges} table.
     *
     * <p>Nothing is moved into it by the migration, deliberately. The accounts that
     * already exist were typed by hand and carry their URL inside the credential
     * payload; inventing a bridge row for each would guess an identity ("what is
     * this computer's name and fingerprint?") that only a pairing can answer. Those
     * accounts keep working with {@code accounts.bridge_id = ''}, which is what
     * marks the hand-typed debug path in every reader.
     *
     * <p>One transaction, for the reason {@link #migrateWidgetSlots} gives: a
     * half-applied migration never runs again once the version has advanced.
     */
    private void migrateBridges(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL(createBridgesTable(true));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The bridges table, shared by {@code onCreate} and the migration so the fresh
     * and upgraded shapes cannot drift.
     *
     * <p>{@code id} is the Bridge's own random identifier from its certificate, not
     * an address: Spec §22/§54 forbid treating {@code 192.168.x.x} as a permanent
     * identity, and an address is exactly the thing that changes when a laptop joins
     * a different network. {@code base_url} is therefore a mutable attribute of an
     * identity that outlives it, and {@code fingerprint} is what makes a changed
     * address safe to reconnect to: the phone compares it against the certificate
     * the connection presents.
     */
    private static String createBridgesTable(boolean ifNotExists) {
        return "CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + TABLE_BRIDGES + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "name TEXT NOT NULL,"
                + "base_url TEXT NOT NULL,"
                + "fingerprint TEXT NOT NULL,"
                + "added_at INTEGER NOT NULL,"
                + "last_seen INTEGER NOT NULL DEFAULT 0)";
    }

    /**
     * Version 1 to 2: one row per widget slot.
     *
     * <p>An existing widget held exactly one account, which becomes exactly one
     * slot at index 0 carrying the two metrics it already drew. The upgrade moves
     * where the binding lives, not what the user sees.
     *
     * <p>A row whose {@code account_id} was empty had no binding: it fell back to
     * the first enabled account when it drew. Such a row deliberately gains no
     * slot, because a fabricated binding would be indistinguishable from a choice
     * the user made — and the fallback is only meant to cover "not yet chosen".
     *
     * <p>One transaction. {@code onUpgrade} will not run again once the version
     * has advanced, so a half-applied migration would leave the app reading a
     * table that nobody finished creating.
     */
    private void migrateWidgetSlots(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL(createWidgetSlotsTable(true));
            db.execSQL(createWidgetSlotsIndex(true));
            // OR IGNORE rather than a bare INSERT: the DDL above already concedes
            // the table may exist, and a re-entry that inserted a second row per
            // widget would fail on the (widget_id, slot_index) key and leave the
            // version un-bumped, so every later open would retry the same failure.
            db.execSQL("INSERT OR IGNORE INTO " + TABLE_WIDGET_SLOTS
                    + " (widget_id, slot_index, account_id, metric_ids)"
                    + " SELECT widget_id, 0, account_id, '" + MIGRATION_DEFAULT_METRICS + "'"
                    + " FROM " + TABLE_WIDGET_CONFIG
                    + " WHERE account_id IS NOT NULL AND account_id <> ''");
            // Clear the legacy column so nothing can read a second answer to
            // "which account does this widget show" after the upgrade. The column
            // itself cannot be dropped on every supported API level.
            db.execSQL("UPDATE " + TABLE_WIDGET_CONFIG + " SET account_id = ''");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The slot table, shared by {@code onCreate} and the migration.
     *
     * <p>{@code ifNotExists} is only for the migration path, where a table could
     * in principle already be there; the two DDLs are one function so the fresh
     * and upgraded schemas cannot drift into different shapes.
     */
    private static String createWidgetSlotsTable(boolean ifNotExists) {
        return "CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + TABLE_WIDGET_SLOTS + " ("
                + "widget_id INTEGER NOT NULL,"
                + "slot_index INTEGER NOT NULL,"
                + "account_id TEXT NOT NULL,"
                + "metric_ids TEXT NOT NULL DEFAULT '',"
                + "PRIMARY KEY (widget_id, slot_index))";
    }

    /**
     * The account-to-widgets index, shared by {@code onCreate} and the migration.
     *
     * <p>Which widgets show a given account: Spec §38 asks for the reverse of the
     * usual lookup — refresh one account, repaint every widget referencing it in
     * any slot — and that query is this index.
     */
    private static String createWidgetSlotsIndex(boolean ifNotExists) {
        return "CREATE INDEX " + (ifNotExists ? "IF NOT EXISTS " : "")
                + "idx_widget_slots_account ON " + TABLE_WIDGET_SLOTS + " (account_id)";
    }
}
