package com.aiusage.monitor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.refresh.RefreshPolicy;
import com.aiusage.monitor.storage.Database;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The slot model itself: order, lookup, and the stored spelling of a metric list.
 *
 * <p>Phase 3's acceptance rests on these three properties, and none of them is
 * visible in a screenshot until a renderer uses them:
 *
 * <ul>
 *   <li><em>slot order is the display order</em> — a config assembled from a
 *       query without an ORDER BY, or from an unordered collection, must still
 *       draw account A in row A;</li>
 *   <li><em>the first slot is the only fallback-relevant one</em> — a half-filled
 *       dashboard must not silently present its second account as if the user had
 *       chosen it;</li>
 *   <li><em>the metric ids survive their round trip through the one column that
 *       holds them</em>, including the literal the schema v2 migration writes.</li>
 * </ul>
 */
public class WidgetSlotModelTest {

    private static final int WIDGET = 101;
    private static final String TYPE_4X2 = "4x2";
    private static final String ACCOUNT_A = "acct_aaaaaa";
    private static final String ACCOUNT_B = "acct_bbbbbb";
    private static final String ACCOUNT_C = "acct_cccccc";

    private static List<WidgetSlot> slots(WidgetSlot... filled) {
        return Arrays.asList(filled);
    }

    private static WidgetSlot slot(int index, String accountId) {
        return WidgetSlot.ofAccount(index, accountId);
    }

    // ------------------------------------------------------------- ordering

    @Test
    public void slotsComeBackInIndexOrderHoweverTheyWereBuilt() {
        WidgetConfig config = new WidgetConfig(WIDGET, TYPE_4X2,
                slots(slot(2, ACCOUNT_C), slot(0, ACCOUNT_A), slot(1, ACCOUNT_B)),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);

        assertEquals("the renderer fills pre-built layout rows by index, so the list order "
                        + "is the only thing that can put the right account in the right row",
                Arrays.asList(0, 1, 2), indicesOf(config.getSlots()));
        assertEquals(ACCOUNT_A, slotAt(config, 0).getAccountId());
        assertEquals(ACCOUNT_B, slotAt(config, 1).getAccountId());
        assertEquals(ACCOUNT_C, slotAt(config, 2).getAccountId());
    }

    @Test
    public void accountIdsAreInSlotOrderWithoutDuplicatesOrEmptySlots() {
        WidgetConfig config = new WidgetConfig(WIDGET, TYPE_4X2,
                slots(slot(0, ACCOUNT_A), slot(1, ""), slot(2, ACCOUNT_A), slot(3, ACCOUNT_B)),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);

        assertEquals("an account bound twice in one widget is refreshed once",
                Arrays.asList(ACCOUNT_A, ACCOUNT_B), config.accountIds());
    }

    @Test
    public void lookingUpAnIndexTheWidgetDoesNotHaveReturnsNull() {
        WidgetConfig config = new WidgetConfig(WIDGET, TYPE_4X2,
                slots(slot(0, ACCOUNT_A)), RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);

        assertNotNull(slotAt(config, 0));
        assertNull("a 4×2 grown down to one slot must not report a phantom second row",
                slotAt(config, 1));
        assertNull(slotAt(config, 9));
    }

    // ------------------------------------------------------ the primary slot

    @Test
    public void anUnboundConfigHasNoSlotsAtAll() {
        WidgetConfig unbound = WidgetConfig.unbound(WIDGET, TYPE_4X2,
                RefreshPolicy.DEFAULT_INTERVAL_MS);

        assertTrue(unbound.getSlots().isEmpty());
        assertEquals("", slotZero(unbound));
        assertFalse(isBound(unbound));
        assertTrue(unbound.accountIds().isEmpty());
    }

    @Test
    public void thePrimaryAccountIsSlotZeroEvenWhenALaterSlotIsFilled() {
        WidgetConfig halfConfigured = new WidgetConfig(WIDGET, TYPE_4X2,
                slots(slot(0, ""), slot(1, ACCOUNT_B)), RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);

        assertEquals("reading \"the first non-empty slot\" here would let a half-configured "
                        + "dashboard present account B as if it had been chosen for row one",
                "", slotZero(halfConfigured));
        assertFalse(isBound(halfConfigured));
        assertEquals(Collections.singletonList(ACCOUNT_B), halfConfigured.accountIds());
    }

    @Test
    public void withSlotsReplacesTheWholeSetRatherThanEditingOneRow() {
        WidgetConfig dashboard = new WidgetConfig(WIDGET, TYPE_4X2,
                slots(slot(0, ACCOUNT_A), slot(1, ACCOUNT_B), slot(2, ACCOUNT_C)),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);

        // What WidgetUpdateManager.bindAccounts does when the picker confirms one
        // account: the new list is the whole configuration, so nothing the user
        // removed survives in a row nobody can see.
        WidgetConfig rebound = dashboard.withSlots(slots(slot(0, ACCOUNT_B)));

        assertEquals(1, rebound.getSlots().size());
        assertEquals(0, rebound.getSlots().get(0).getSlotIndex());
        assertEquals(ACCOUNT_B, slotZero(rebound));
        assertEquals("the original must not be mutated in place",
                3, dashboard.getSlots().size());
    }

    @Test
    public void rebindingPreservesEveryOtherFieldAndReTimestamps() {
        WidgetConfig unbound = WidgetConfig.unbound(WIDGET, TYPE_4X2, 15L * 60L * 1000L);
        WidgetConfig withOrder = new WidgetConfig(WIDGET, TYPE_4X2,
                Collections.<WidgetSlot>emptyList(), 15L * 60L * 1000L, 7, 1234L);
        long before = System.currentTimeMillis();

        WidgetConfig bound = withOrder.withSlots(slots(slot(0, ACCOUNT_A)));

        assertEquals(WIDGET, bound.getWidgetId());
        assertEquals(TYPE_4X2, bound.getWidgetType());
        assertEquals(15L * 60L * 1000L, bound.getRefreshIntervalMs());
        assertEquals(7, bound.getSortOrder());
        assertTrue("a binding is a change, so the row must be re-timestamped",
                bound.getUpdatedAt() >= before);
        assertTrue(unbound.getSlots().isEmpty());
    }

    @Test
    public void aSlotCarriesTheMetricsItWasBuiltWith() {
        WidgetSlot filled = new WidgetSlot(1, ACCOUNT_A,
                Collections.singletonList(WidgetMetricId.TODAY_USAGE));

        assertEquals(Collections.singletonList(WidgetMetricId.TODAY_USAGE), filled.getMetricIds());
        assertEquals(1, filled.getSlotIndex());
        assertTrue(filled.isFilled());

        WidgetSlot emptyAccount = new WidgetSlot(1, "", WidgetMetricId.defaults());
        assertFalse("an unfilled slot is a state, not an absent slot", emptyAccount.isFilled());
        assertEquals(WidgetMetricId.defaults(), emptyAccount.getMetricIds());
    }

    @Test
    public void theMetricListASlotHandsOutCannotBeEditedByTheCaller() {
        List<String> chosen = new ArrayList<>();
        chosen.add(WidgetMetricId.BALANCE);
        WidgetSlot filled = new WidgetSlot(0, ACCOUNT_A, chosen);

        chosen.add(WidgetMetricId.TODAY_USAGE);

        assertEquals("the config is shared between the picker and the renderer; a caller "
                + "editing the list it came from would move another widget's display",
                1, filled.getMetricIds().size());
    }

    // ------------------------------------------------------ the stored spelling

    @Test
    public void metricIdsRoundTripThroughTheStoredColumn() {
        assertEquals("", WidgetMetricId.encode(null));
        assertEquals("", WidgetMetricId.encode(Collections.<String>emptyList()));
        assertEquals(WidgetMetricId.BALANCE,
                WidgetMetricId.encode(Collections.singletonList(WidgetMetricId.BALANCE)));
        assertEquals("balance|today_usage", WidgetMetricId.encode(WidgetMetricId.defaults()));

        assertEquals(WidgetMetricId.defaults(), WidgetMetricId.decode("balance|today_usage"));
        assertTrue(WidgetMetricId.decode("").isEmpty());
        assertTrue(WidgetMetricId.decode(null).isEmpty());
        assertEquals(Collections.singletonList(WidgetMetricId.BALANCE),
                WidgetMetricId.decode("balance"));
        assertEquals("an id this build does not know must survive anyway, so a downgrade "
                        + "does not quietly wipe a widget's configuration",
                Arrays.asList("balance", "reset_in"),
                WidgetMetricId.decode("balance|reset_in"));
    }

    @Test
    public void encodeAndDecodeAgreeForAnyList() {
        List<String> ids = Arrays.asList(WidgetMetricId.BALANCE, "quota_5h_remaining",
                WidgetMetricId.TODAY_USAGE);

        assertEquals(ids, WidgetMetricId.decode(WidgetMetricId.encode(ids)));
    }

    /**
     * The one place the Java side and the SQL side have to agree letter for letter.
     *
     * <p>Version 1 had no metric column, so {@code Database.onUpgrade} writes this
     * value as a SQL literal for every existing widget. A drift would not fail to
     * compile: every upgraded widget would decode an id no resolver knows and
     * render as showing nothing.
     */
    @Test
    public void theMigrationsSqlLiteralMatchesTheEncodedDefaults() {
        assertEquals(Database.MIGRATION_DEFAULT_METRICS,
                WidgetMetricId.encode(WidgetMetricId.defaults()));
        assertEquals(WidgetMetricId.defaults(),
                WidgetMetricId.decode(Database.MIGRATION_DEFAULT_METRICS));
    }

    /** The slot with the given index, or null: the config exposes only the rows it has. */
    private static WidgetSlot slotAt(WidgetConfig config, int index) {
        for (WidgetSlot slot : config.getSlots()) {
            if (slot.getSlotIndex() == index) {
                return slot;
            }
        }
        return null;
    }

    private static List<Integer> indicesOf(List<WidgetSlot> slots) {
        List<Integer> indices = new ArrayList<>();
        for (WidgetSlot slot : slots) {
            indices.add(slot.getSlotIndex());
        }
        return indices;
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
