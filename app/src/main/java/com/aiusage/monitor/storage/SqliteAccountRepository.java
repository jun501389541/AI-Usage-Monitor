package com.aiusage.monitor.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.aiusage.monitor.account.AccountRepository;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.Account;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed account storage. Spec §45.
 *
 * <p>Accounts and credentials are separate tables joined by
 * {@code credential_id}. That is what makes the spec's first-phase acceptance
 * criterion hold: changing the key behind an account rewrites a credentials row
 * and leaves the account row — and therefore its id, its history and every
 * widget bound to it — completely untouched. Spec §14.
 */
public final class SqliteAccountRepository implements AccountRepository {

    private final Database database;

    public SqliteAccountRepository(Context context) {
        this.database = Database.get(context);
    }

    @Override
    public List<Account> findAll() {
        return query(null, null);
    }

    @Override
    public List<Account> findEnabled() {
        return query("enabled = 1", null);
    }

    @Override
    public Account findById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        List<Account> found = query("id = ?", new String[]{id});
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public void save(Account account) {
        ContentValues values = new ContentValues();
        values.put("id", account.getId());
        values.put("provider_id", account.getProviderId());
        values.put("display_name", account.getDisplayName());
        values.put("auth_type", account.getAuthType().name());
        values.put("credential_id", account.getCredentialId());
        values.put("bridge_id", account.getBridgeId());
        values.put("enabled", account.isEnabled() ? 1 : 0);
        values.put("sort_order", account.getSortOrder());
        values.put("created_at", account.getCreatedAt());
        values.put("updated_at", account.getUpdatedAt());

        // insertWithOnConflict(REPLACE) keeps the id stable across edits, which is
        // the property the spec's acceptance criterion depends on.
        long written = database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_ACCOUNTS, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        if (written < 0) {
            // SQLite declined the row without throwing; a saved account that is not in the
            // table reads as "it worked until the next launch".
            throw new IllegalStateException("accounts refused the row for " + account.getId());
        }
    }

    @Override
    public void delete(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        database.getWritableDatabase().delete(Database.TABLE_ACCOUNTS, "id = ?", new String[]{id});
    }

    @Override
    public void reorder(List<String> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        android.database.sqlite.SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int index = 0; index < orderedIds.size(); index++) {
                ContentValues values = new ContentValues();
                values.put("sort_order", index);
                values.put("updated_at", System.currentTimeMillis());
                db.update(Database.TABLE_ACCOUNTS, values, "id = ?", new String[]{orderedIds.get(index)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public int count() {
        try (Cursor cursor = database.getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM " + Database.TABLE_ACCOUNTS, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private List<Account> query(String selection, String[] args) {
        List<Account> accounts = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_ACCOUNTS,
                new String[]{"id", "provider_id", "display_name", "auth_type", "credential_id",
                        "bridge_id", "enabled", "sort_order", "created_at", "updated_at"},
                selection,
                args,
                null, null,
                "sort_order ASC, created_at ASC")) {
            while (cursor.moveToNext()) {
                accounts.add(new Account.Builder()
                        .id(cursor.getString(0))
                        .providerId(cursor.getString(1))
                        .displayName(cursor.getString(2))
                        .authType(parseAuthType(cursor.getString(3)))
                        .credentialId(cursor.getString(4))
                        .bridgeId(cursor.getString(5))
                        .enabled(cursor.getInt(6) == 1)
                        .sortOrder(cursor.getInt(7))
                        .createdAt(cursor.getLong(8))
                        .updatedAt(cursor.getLong(9))
                        .build());
            }
        }
        return accounts;
    }

    /** Unknown values fall back to CUSTOM rather than crashing on a bad row. */
    private static AuthType parseAuthType(String raw) {
        if (raw == null) {
            return AuthType.CUSTOM;
        }
        try {
            return AuthType.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return AuthType.CUSTOM;
        }
    }
}
