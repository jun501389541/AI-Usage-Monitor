package com.aiusage.monitor.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.aiusage.monitor.widget.WidgetConfig;
import com.aiusage.monitor.widget.WidgetConfigStore;
import com.aiusage.monitor.widget.WidgetMetricId;
import com.aiusage.monitor.widget.WidgetSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed widget configuration. Spec §28 and §33.
 *
 * <p>Two tables, because two questions get asked of them. The widget row answers
 * "what is this widget" and the slot rows answer "which accounts does it show" —
 * and the second question is also asked backwards, as
 * {@link #widgetIdsUsingAccount}, which is how refreshing one account repaints
 * every widget that shows it.
 *
 * <p>{@code widget_config.account_id} is not read or written any more. It cannot
 * be dropped on every supported API level, so it stays at its default; treating
 * it as a second source of the binding is what this class exists to prevent.
 */
public final class SqliteWidgetConfigStore implements WidgetConfigStore {

    private static final String[] CONFIG_COLUMNS = {
            "widget_id", "widget_type", "refresh_interval_ms", "sort_order", "updated_at",
    };

    private final Database database;
    private final AppSettings settings;

    public SqliteWidgetConfigStore(Context context) {
        this.database = Database.get(context);
        this.settings = new AppSettings(context);
    }

    @Override
    public WidgetConfig find(int widgetId) {
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_WIDGET_CONFIG, CONFIG_COLUMNS,
                "widget_id = ?", new String[]{String.valueOf(widgetId)},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return read(cursor, slotsFor(database.getReadableDatabase(), widgetId));
        }
    }

    @Override
    public void save(WidgetConfig config) {
        if (config == null) {
            return;
        }
        SQLiteDatabase db = database.getWritableDatabase();
        // One transaction: a config written with its slots only half-replaced
        // would show the user one account in a row and another in the same
        // widget's second row, with no way to tell which half is stale.
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("widget_id", config.getWidgetId());
            values.put("widget_type", config.getWidgetType());
            values.put("refresh_interval_ms", config.getRefreshIntervalMs());
            values.put("sort_order", config.getSortOrder());
            values.put("updated_at", System.currentTimeMillis());
            long written = db.insertWithOnConflict(Database.TABLE_WIDGET_CONFIG, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            if (written < 0) {
                // Inside the transaction on purpose: a refused config row must roll the slot
                // rewrite back too, or the widget keeps slots for a configuration that is no
                // longer on record.
                throw new IllegalStateException("widget_config refused the row for widget "
                        + config.getWidgetId());
            }

            db.delete(Database.TABLE_WIDGET_SLOTS, "widget_id = ?",
                    new String[]{String.valueOf(config.getWidgetId())});
            for (WidgetSlot slot : config.getSlots()) {
                ContentValues row = new ContentValues();
                row.put("widget_id", config.getWidgetId());
                row.put("slot_index", slot.getSlotIndex());
                row.put("account_id", slot.getAccountId());
                row.put("metric_ids", WidgetMetricId.encode(slot.getMetricIds()));
                long slotWritten = db.insertWithOnConflict(Database.TABLE_WIDGET_SLOTS, null, row,
                        SQLiteDatabase.CONFLICT_REPLACE);
                if (slotWritten < 0) {
                    throw new IllegalStateException("widget_slots refused slot " + slot.getSlotIndex()
                            + " of widget " + config.getWidgetId());
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void delete(int widgetId) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(Database.TABLE_WIDGET_SLOTS, "widget_id = ?",
                    new String[]{String.valueOf(widgetId)});
            db.delete(Database.TABLE_WIDGET_CONFIG, "widget_id = ?",
                    new String[]{String.valueOf(widgetId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public List<WidgetConfig> all() {
        SQLiteDatabase db = database.getReadableDatabase();
        Map<Integer, List<WidgetSlot>> slotsByWidget = readAllSlots(db);
        List<WidgetConfig> configs = new ArrayList<>();
        try (Cursor cursor = db.query(
                Database.TABLE_WIDGET_CONFIG, CONFIG_COLUMNS,
                null, null, null, null, "sort_order ASC, widget_id ASC")) {
            while (cursor.moveToNext()) {
                WidgetConfig config = read(cursor, slotsByWidget.get(cursor.getInt(0)));
                configs.add(config);
            }
        }
        return configs;
    }

    @Override
    public List<Integer> widgetIdsUsingAccount(String accountId) {
        List<Integer> ids = new ArrayList<>();
        if (accountId == null || accountId.isEmpty()) {
            return ids;
        }
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT DISTINCT widget_id FROM " + Database.TABLE_WIDGET_SLOTS
                        + " WHERE account_id = ? ORDER BY widget_id ASC",
                new String[]{accountId})) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getInt(0));
            }
        }
        return ids;
    }

    @Override
    public long backgroundRefreshIntervalMs() {
        // The user's chosen interval is a global setting; the widget row only
        // records it for the widgets that exist. Reading the setting first keeps
        // the value meaningful even before any widget is configured.
        return settings.backgroundRefreshIntervalMs();
    }

    private WidgetConfig read(Cursor cursor, List<WidgetSlot> slots) {
        return new WidgetConfig(
                cursor.getInt(0),
                cursor.getString(1),
                slots,
                cursor.getLong(2),
                cursor.getInt(3),
                cursor.getLong(4));
    }

    private static List<WidgetSlot> slotsFor(SQLiteDatabase db, int widgetId) {
        List<WidgetSlot> slots = new ArrayList<>();
        try (Cursor cursor = db.query(
                Database.TABLE_WIDGET_SLOTS,
                new String[]{"slot_index", "account_id", "metric_ids"},
                "widget_id = ?", new String[]{String.valueOf(widgetId)},
                null, null, "slot_index ASC")) {
            while (cursor.moveToNext()) {
                slots.add(toSlot(cursor, 0));
            }
        }
        return slots;
    }

    /** Every slot grouped by widget, so listing widgets costs two queries. */
    private static Map<Integer, List<WidgetSlot>> readAllSlots(SQLiteDatabase db) {
        Map<Integer, List<WidgetSlot>> byWidget = new HashMap<>();
        try (Cursor cursor = db.query(
                Database.TABLE_WIDGET_SLOTS,
                new String[]{"widget_id", "slot_index", "account_id", "metric_ids"},
                null, null, null, null, "widget_id ASC, slot_index ASC")) {
            while (cursor.moveToNext()) {
                List<WidgetSlot> slots = byWidget.get(cursor.getInt(0));
                if (slots == null) {
                    slots = new ArrayList<>();
                    byWidget.put(cursor.getInt(0), slots);
                }
                slots.add(toSlot(cursor, 1));
            }
        }
        return byWidget;
    }

    private static WidgetSlot toSlot(Cursor cursor, int columnOffset) {
        return new WidgetSlot(
                cursor.getInt(columnOffset),
                cursor.getString(columnOffset + 1),
                WidgetMetricId.decode(cursor.getString(columnOffset + 2)));
    }
}
