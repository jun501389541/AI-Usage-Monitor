package com.aiusage.monitor.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.aiusage.monitor.bridge.BridgeRepository;
import com.aiusage.monitor.model.Bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed {@code bridges} rows. Spec §21, schema version 3.
 *
 * <p>Reads are ordered by {@code added_at} rather than by rowid so the device list
 * shows the pairings in the order the user made them even after a row is replaced —
 * a re-pair writes a new row with the same id, and an ordering that moved it to the
 * end of the list would read as the old pairing having been forgotten.
 */
public final class SqliteBridgeRepository implements BridgeRepository {

    private static final String[] COLUMNS = {
            "id", "name", "base_url", "fingerprint", "added_at", "last_seen",
    };

    private final Database database;

    public SqliteBridgeRepository(Context context) {
        this.database = Database.get(context);
    }

    @Override
    public List<Bridge> findAll() {
        List<Bridge> found = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_BRIDGES, COLUMNS, null, null, null, null,
                "added_at ASC, id ASC")) {
            while (cursor.moveToNext()) {
                found.add(read(cursor));
            }
        }
        return found;
    }

    @Override
    public Bridge findById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_BRIDGES, COLUMNS, "id = ?", new String[]{id},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    @Override
    public void save(Bridge bridge) {
        if (bridge == null || bridge.getId().isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("id", bridge.getId());
        values.put("name", bridge.getName());
        values.put("base_url", bridge.getBaseUrl());
        values.put("fingerprint", bridge.getFingerprint());
        values.put("added_at", bridge.getAddedAt());
        values.put("last_seen", bridge.getLastSeen());
        long written = database.getWritableDatabase().insertWithOnConflict(
                Database.TABLE_BRIDGES, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        // -1 is SQLite's own "nothing was written", and it arrives without an
        // exception: discarding it made a pairing look recorded while the computer was
        // never in the table, which is the state PairingStore exists to avoid (review
        // P2, 2026-10-03). Throwing here is what hands that failure to the rollback.
        if (written < 0) {
            throw new IllegalStateException("the bridges table refused the row for "
                    + bridge.getId());
        }
    }

    @Override
    public void updateBaseUrl(String id, String baseUrl) {
        Bridge existing = findById(id);
        if (existing == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("base_url", baseUrl == null ? "" : baseUrl);
        database.getWritableDatabase().update(Database.TABLE_BRIDGES, values,
                "id = ?", new String[]{id});
    }

    @Override
    public void touchLastSeen(String id, long atMs) {
        if (id == null || id.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("last_seen", atMs);
        database.getWritableDatabase().update(Database.TABLE_BRIDGES, values,
                "id = ?", new String[]{id});
    }

    @Override
    public void delete(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        database.getWritableDatabase().delete(Database.TABLE_BRIDGES,
                "id = ?", new String[]{id});
    }

    private static Bridge read(Cursor cursor) {
        return new Bridge(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getLong(4), cursor.getLong(5));
    }
}
