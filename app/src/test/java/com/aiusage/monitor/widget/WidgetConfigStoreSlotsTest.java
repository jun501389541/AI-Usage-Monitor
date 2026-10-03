package com.aiusage.monitor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.refresh.RefreshPolicy;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The store contract Phase 3 adds: a widget's slots are written and read as a
 * set, and the set can be asked about from the account side.
 *
 * <p>Spec §38 asks for the reverse lookup — refresh one account, repaint every
 * widget that shows it — and §53 rules 16 and 17 allow an account to sit in two
 * widgets, or twice in one. Both are properties of the table, not of a
 * renderer, so this is where they get pinned.
 *
 * <p>The fake mirrors {@code SqliteWidgetConfigStore} statement for statement and
 * keeps the slot rows as raw columns, {@code metric_ids} included. That is the
 * point of it: storing the list on the Java side and reading it back would prove
 * nothing about the encoded form the schema v2 migration writes in SQL.
 */
public class WidgetConfigStoreSlotsTest {

    private static final int WIDGET_ONE = 101;
    private static final int WIDGET_TWO = 202;
    private static final int WIDGET_THREE = 303;
    private static final String TYPE_4X2 = "4x2";
    private static final String TYPE_2X1 = "2x1";
    private static final String ACCOUNT_A = "acct_aaaaaa";
    private static final String ACCOUNT_B = "acct_bbbbbb";
    private static final String ACCOUNT_C = "acct_cccccc";

    private FakeWidgetTables store;

    @Before
    public void setUp() {
        store = new FakeWidgetTables();
    }

    private WidgetConfig dashboard(int widgetId, String... accountIds) {
        List<WidgetSlot> slots = new ArrayList<>();
        for (int index = 0; index < accountIds.length; index++) {
            slots.add(new WidgetSlot(index, accountIds[index], WidgetMetricId.defaults()));
        }
        return new WidgetConfig(widgetId, TYPE_4X2, slots,
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);
    }

    // --------------------------------------------------- written and read as a set

    @Test
    public void savedSlotsComeBackInIndexOrderWithTheirMetrics() {
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A, ACCOUNT_B, ACCOUNT_C));

        WidgetConfig stored = store.find(WIDGET_ONE);

        assertNotNull(stored);
        assertEquals(3, stored.getSlots().size());
        assertEquals(Arrays.asList(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C), stored.accountIds());
        assertEquals(WidgetMetricId.defaults(), stored.getSlots().get(1).getMetricIds());
        assertEquals("slot_index is what the rows are ordered by",
                Arrays.asList(0, 1, 2), indicesOf(stored.getSlots()));
    }

    @Test
    public void reSavingReplacesTheWholeSlotSetAndLeavesNoOlderRow() {
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A, ACCOUNT_B, ACCOUNT_C));

        store.save(dashboard(WIDGET_ONE, ACCOUNT_B));

        WidgetConfig stored = store.find(WIDGET_ONE);
        assertEquals("a shrunk dashboard must not keep the rows it no longer shows",
                1, stored.getSlots().size());
        assertEquals(ACCOUNT_B, slotZero(stored));
        assertEquals("the delete-then-insert left exactly one row behind",
                1, store.slotRowCount(WIDGET_ONE));
    }

    @Test
    public void savingWithoutSlotsIsADistinctStateFromMissingConfiguration() {
        store.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS));

        WidgetConfig stored = store.find(WIDGET_ONE);

        assertNotNull("a widget that exists but has never been picked still has a row", stored);
        assertTrue("and it has no slots, which is what lets the renderer fall back",
                stored.getSlots().isEmpty());
        assertEquals(0, store.slotRowCount(WIDGET_ONE));
        assertNull("a widget that was never placed has no row at all", store.find(WIDGET_TWO));
    }

    @Test
    public void forgettingAWidgetRemovesItsSlotsToo() {
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A, ACCOUNT_B));
        store.save(dashboard(WIDGET_TWO, ACCOUNT_A));

        store.delete(WIDGET_ONE);

        assertNull(store.find(WIDGET_ONE));
        assertEquals("an orphan slot row would keep making a removed widget a refresh target",
                0, store.slotRowCount(WIDGET_ONE));
        assertEquals("the sibling widget keeps its own single slot",
                1, store.slotRowCount(WIDGET_TWO));
        assertEquals(Collections.singletonList(WIDGET_TWO), store.widgetIdsUsingAccount(ACCOUNT_A));
    }

    // ------------------------------------------------------------- reverse lookup

    @Test
    public void oneAccountInTwoWidgetsReturnsBothWidgetIds() {
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A));
        store.save(dashboard(WIDGET_TWO, ACCOUNT_B, ACCOUNT_A));
        store.save(dashboard(WIDGET_THREE, ACCOUNT_C));

        assertEquals("Spec §53 rule 16: an account may appear in more than one widget, "
                        + "and refreshing it must repaint both",
                Arrays.asList(WIDGET_ONE, WIDGET_TWO), store.widgetIdsUsingAccount(ACCOUNT_A));
    }

    @Test
    public void anAccountTwiceInOneWidgetReturnsThatWidgetOnce() {
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A, ACCOUNT_A));

        assertEquals("a duplicate must not make the same widget redraw twice per refresh",
                Collections.singletonList(WIDGET_ONE), store.widgetIdsUsingAccount(ACCOUNT_A));
    }

    @Test
    public void anAccountInAnySlotPositionIsFound() {
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A, ACCOUNT_B, ACCOUNT_C));

        assertEquals(Collections.singletonList(WIDGET_ONE), store.widgetIdsUsingAccount(ACCOUNT_C));
        assertTrue(store.widgetIdsUsingAccount("acct_never_bound").isEmpty());
        assertTrue("a blank id must not match the empty slots a half-configured widget has",
                store.widgetIdsUsingAccount("").isEmpty());
        assertTrue(store.widgetIdsUsingAccount(null).isEmpty());
    }

    @Test
    public void listingWidgetsKeepsEachOnesSlotsApart() {
        store.save(new WidgetConfig(WIDGET_TWO, TYPE_2X1,
                Collections.singletonList(WidgetSlot.ofAccount(0, ACCOUNT_B)),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 1, 0L));
        store.save(dashboard(WIDGET_ONE, ACCOUNT_A, ACCOUNT_C));

        List<WidgetConfig> all = store.all();

        assertEquals(2, all.size());
        assertEquals("ties and order follow sort_order then widget_id, as the SQL does",
                WIDGET_ONE, all.get(0).getWidgetId());
        assertEquals(2, all.get(0).getSlots().size());
        assertEquals(1, all.get(1).getSlots().size());
        assertEquals(ACCOUNT_B, slotZero(all.get(1)));
    }

    // -------------------------------------------------------------- the fake

    private static List<Integer> indicesOf(List<WidgetSlot> slots) {
        List<Integer> indices = new ArrayList<>();
        for (WidgetSlot slot : slots) {
            indices.add(slot.getSlotIndex());
        }
        return indices;
    }

    /**
     * A stand-in for the two SQLite tables, holding the slot rows as raw columns.
     *
     * <p>{@code save} is the store's transaction: replace the widget row, delete
     * its slot rows, insert the new ones. {@code updated_at} is the store's clock,
     * not the object's, as in {@code SqliteWidgetConfigStore}.
     */
    private static final class FakeWidgetTables implements WidgetConfigStore {

        private final Map<Integer, ConfigRow> configs = new LinkedHashMap<>();
        private final List<SlotRow> slots = new ArrayList<>();
        private long clock = System.currentTimeMillis();

        int slotRowCount(int widgetId) {
            int count = 0;
            for (SlotRow row : slots) {
                if (row.widgetId == widgetId) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public WidgetConfig find(int widgetId) {
            ConfigRow row = configs.get(widgetId);
            return row == null ? null : row.toConfig(slotsFor(widgetId));
        }

        @Override
        public void save(WidgetConfig config) {
            if (config == null) {
                return;
            }
            configs.put(config.getWidgetId(),
                    new ConfigRow(config, ++clock));
            final int widgetId = config.getWidgetId();
            slots.removeIf(row -> row.widgetId == widgetId);
            for (WidgetSlot slot : config.getSlots()) {
                slots.add(new SlotRow(widgetId, slot));
            }
        }

        @Override
        public void delete(int widgetId) {
            configs.remove(widgetId);
            final int target = widgetId;
            slots.removeIf(row -> row.widgetId == target);
        }

        @Override
        public List<WidgetConfig> all() {
            List<ConfigRow> rows = new ArrayList<>(configs.values());
            rows.sort((left, right) -> left.sortOrder != right.sortOrder
                    ? Integer.compare(left.sortOrder, right.sortOrder)
                    : Integer.compare(left.widgetId, right.widgetId));
            List<WidgetConfig> results = new ArrayList<>();
            for (ConfigRow row : rows) {
                results.add(row.toConfig(slotsFor(row.widgetId)));
            }
            return results;
        }

        @Override
        public List<Integer> widgetIdsUsingAccount(String accountId) {
            List<Integer> ids = new ArrayList<>();
            if (accountId == null || accountId.isEmpty()) {
                return ids;
            }
            for (SlotRow row : slots) {
                if (accountId.equals(row.accountId) && !ids.contains(row.widgetId)) {
                    ids.add(row.widgetId);
                }
            }
            Collections.sort(ids);
            return ids;
        }

        @Override
        public long backgroundRefreshIntervalMs() {
            return RefreshPolicy.DEFAULT_INTERVAL_MS;
        }

        /** The {@code ORDER BY slot_index ASC} of the store's query. */
        private List<WidgetSlot> slotsFor(int widgetId) {
            List<WidgetSlot> found = new ArrayList<>();
            for (SlotRow row : slots) {
                if (row.widgetId == widgetId) {
                    found.add(row.toSlot());
                }
            }
            found.sort((a, b) -> Integer.compare(a.getSlotIndex(), b.getSlotIndex()));
            return found;
        }
    }

    private static final class ConfigRow {
        private final int widgetId;
        private final String widgetType;
        private final long refreshIntervalMs;
        private final int sortOrder;
        private final long updatedAt;

        ConfigRow(WidgetConfig config, long updatedAt) {
            this.widgetId = config.getWidgetId();
            this.widgetType = config.getWidgetType();
            this.refreshIntervalMs = config.getRefreshIntervalMs();
            this.sortOrder = config.getSortOrder();
            this.updatedAt = updatedAt;
        }

        WidgetConfig toConfig(List<WidgetSlot> slots) {
            return new WidgetConfig(widgetId, widgetType, slots, refreshIntervalMs, sortOrder,
                    updatedAt);
        }
    }

    /** One {@code widget_slots} row, with the metrics kept in their stored spelling. */
    private static final class SlotRow {
        private final int widgetId;
        private final int slotIndex;
        private final String accountId;
        private final String metricIds;

        SlotRow(int widgetId, WidgetSlot slot) {
            this.widgetId = widgetId;
            this.slotIndex = slot.getSlotIndex();
            this.accountId = slot.getAccountId();
            this.metricIds = WidgetMetricId.encode(slot.getMetricIds());
        }

        WidgetSlot toSlot() {
            return new WidgetSlot(slotIndex, accountId, WidgetMetricId.decode(metricIds));
        }
    }
    /** The account slot 0 carries, or empty: the slot list is the only source of the binding. */
    private static String slotZero(WidgetConfig config) {
        for (WidgetSlot slot : config.getSlots()) {
            if (slot.getSlotIndex() == 0) {
                return slot.getAccountId();
            }
        }
        return "";
    }

    private static boolean isBound(WidgetConfig config) {
        return !slotZero(config).isEmpty();
    }
}
