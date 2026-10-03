package com.aiusage.monitor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.account.AccountRepository;
import com.aiusage.monitor.auth.ApiKeyAuthAdapter;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.refresh.RefreshPolicy;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves the Phase 2 acceptance criteria in {@code docs/PHASE-0-2-PLAN.md} §2.3
 * that concern widgets: <em>two widgets on the same home screen may show two
 * different accounts, and a widget with no binding falls back to a deterministic
 * default instead of showing nothing</em>.
 *
 * <p>The plan words these as "两个 widget 各自绑定账户，分别显示不同余额" and
 * "Widget 未绑定时回退到第一个启用账户". Both are device smoke tests on the plan's
 * own list (row 4 asks for a screenshot); what a host JVM can pin is the binding
 * contract underneath them.
 *
 * <p>{@code WidgetUpdateManager} itself cannot be constructed here — it wants an
 * Android {@code Context}, an {@code AppWidgetManager} and a {@code RemoteViews}
 * factory — so this class asserts the same contract through the pieces it is
 * built from, which <em>are</em> Android-free:
 *
 * <ul>
 *   <li>{@link WidgetConfig} — the binding itself, and the exact field set
 *       {@code withSingleAccount} must preserve;</li>
 *   <li>{@link WidgetConfigStore} — the row that makes a binding outlive a
 *       process restart, and the reason a second widget cannot disturb the
 *       first: {@code save} is keyed by {@code widget_id};</li>
 *   <li>{@link AccountManager#listEnabled()} — the ordered list
 *       {@code WidgetUpdateManager.resolveAccount} takes its fallback from, and
 *       the reason the fallback is deterministic: the repository orders by
 *       {@code sort_order}, then {@code created_at}, and {@code reorder}
 *       rewrites that order.</li>
 * </ul>
 *
 * <p>The store fake mirrors {@code SqliteWidgetConfigStore} statement for
 * statement — {@code find} by {@code widget_id}, {@code save} as
 * {@code INSERT OR REPLACE} (so a re-bind updates the row instead of adding a
 * second one), {@code all()} ordered {@code sort_order ASC, widget_id ASC}, and
 * {@code updated_at} stamped by the store rather than read from the object.
 */
public class WidgetBindingTest {

    private static final int WIDGET_ONE = 101;
    private static final int WIDGET_TWO = 202;
    private static final String TYPE_4X2 = "4x2";
    private static final String TYPE_2X1 = "2x1";
    private static final String PROVIDER_ID = "deepseek";
    private static final String KEY_A = "sk-widget-account-a";
    private static final String KEY_B = "sk-widget-account-b";

    private InMemoryAccountRepository accounts;
    private AccountManager accountManager;
    private InMemoryWidgetConfigStore configStore;
    private Account accountA;
    private Account accountB;

    @Before
    public void setUp() throws Exception {
        accounts = new InMemoryAccountRepository();
        accountManager = new AccountManager(accounts, new InMemoryCredentialStore());
        configStore = new InMemoryWidgetConfigStore();

        accountA = accountManager.create(PROVIDER_ID, "账户 A", AuthType.API_KEY,
                CredentialPayload.forApiKey(KEY_A));
        accountB = accountManager.create(PROVIDER_ID, "账户 B", AuthType.API_KEY,
                CredentialPayload.forApiKey(KEY_B));
    }

    // --------------------------------------------- the binding is per widget id

    @Test
    public void twoWidgetsBoundToDifferentAccountsReportTheirOwnAccountId() {
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountA.getId())));
        configStore.save(WidgetConfig.unbound(WIDGET_TWO, TYPE_2X1, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountB.getId())));

        WidgetConfig first = configStore.find(WIDGET_ONE);
        WidgetConfig second = configStore.find(WIDGET_TWO);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("widget one must point at account A", accountA.getId(), slotZero(first));
        assertEquals("widget two must point at account B", accountB.getId(), slotZero(second));
        assertFalse("two widgets on one screen must not share a binding — that is the whole point of binding to an account id rather than to a provider",
                slotZero(first).equals(slotZero(second)));
        assertTrue(isBound(first));
        assertTrue(isBound(second));

        assertEquals("the widget id is the row key", WIDGET_ONE, first.getWidgetId());
        assertEquals(WIDGET_TWO, second.getWidgetId());
        assertEquals(TYPE_4X2, first.getWidgetType());
        assertEquals(TYPE_2X1, second.getWidgetType());
    }

    @Test
    public void bindingASecondWidgetDoesNotDisturbTheFirst() {
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountA.getId())));
        WidgetConfig before = configStore.find(WIDGET_ONE);

        // Bind the second widget, the way WidgetUpdateManager.bindAccount does.
        configStore.save(WidgetConfig.unbound(WIDGET_TWO, TYPE_2X1, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountB.getId())));

        WidgetConfig after = configStore.find(WIDGET_ONE);
        assertNotNull(after);
        assertEquals("the first widget's binding must be untouched by the second widget's",
                accountA.getId(), slotZero(after));
        assertEquals(before.getWidgetId(), after.getWidgetId());
        assertEquals(before.getWidgetType(), after.getWidgetType());
        assertEquals(before.getRefreshIntervalMs(), after.getRefreshIntervalMs());
        assertEquals("a save keyed by widget_id must not add a second row", 2, configStore.all().size());
    }

    @Test
    public void rebindingOneWidgetReplacesOnlyThatWidgetsRow() {
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountA.getId())));
        configStore.save(WidgetConfig.unbound(WIDGET_TWO, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountB.getId())));

        // The user re-points the first widget at account B.
        configStore.save(configStore.find(WIDGET_ONE).withSlots(singleSlot(accountB.getId())));

        assertEquals(accountB.getId(), slotZero(configStore.find(WIDGET_ONE)));
        assertEquals("the second widget must still point where it did",
                accountB.getId(), slotZero(configStore.find(WIDGET_TWO)));
        assertEquals("re-binding must replace the row, not append one", 2, configStore.all().size());
    }

    // --------------------------------- the unbound config is what enables fallback

    @Test
    public void anUnboundConfigHasAnEmptyAccountId() {
        WidgetConfig unbound = WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS);

        assertEquals("an unbound widget is identified by the empty account id — that is exactly what makes WidgetUpdateManager.resolveAccount fall through to the first enabled account",
                "", slotZero(unbound));
        assertTrue("the empty account id must be empty, not a placeholder",
                slotZero(unbound).isEmpty());
        assertFalse("an unbound config must report itself as unbound", isBound(unbound));

        assertEquals(WIDGET_ONE, unbound.getWidgetId());
        assertEquals(TYPE_4X2, unbound.getWidgetType());
        assertEquals(RefreshPolicy.DEFAULT_INTERVAL_MS, unbound.getRefreshIntervalMs());
        assertEquals(0, unbound.getSortOrder());
        assertEquals("an unbound config has never been saved", 0L, unbound.getUpdatedAt());
    }

    @Test
    public void withSingleAccountBindsAndPreservesEveryOtherField() {
        WidgetConfig unbound = WidgetConfig.unbound(WIDGET_ONE, TYPE_2X1, 15L * 60L * 1000L);
        long before = System.currentTimeMillis();

        WidgetConfig bound = unbound.withSlots(singleSlot(accountA.getId()));

        assertTrue(isBound(bound));
        assertEquals(accountA.getId(), slotZero(bound));
        assertEquals("withSingleAccount must keep the widget id", unbound.getWidgetId(), bound.getWidgetId());
        assertEquals("withSingleAccount must keep the widget type", unbound.getWidgetType(), bound.getWidgetType());
        assertEquals("withSingleAccount must keep the refresh interval",
                unbound.getRefreshIntervalMs(), bound.getRefreshIntervalMs());
        assertEquals("withSingleAccount must keep the layout order", unbound.getSortOrder(), bound.getSortOrder());
        assertTrue("a binding is a change, so the row must be re-timestamped",
                bound.getUpdatedAt() >= before);
        assertNotSame("WidgetConfig is immutable — binding returns a new instance",
                unbound, bound);
        assertFalse("the original must not have been mutated in place", isBound(unbound));
    }

    @Test
    public void withSingleAccountOnAnAlreadyBoundConfigRebindsWithoutLosingFields() {
        WidgetConfig bound = new WidgetConfig(WIDGET_ONE, TYPE_4X2, singleSlot(accountA.getId()),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 7, 1234L);

        WidgetConfig rebound = bound.withSlots(singleSlot(accountB.getId()));

        assertEquals(accountB.getId(), slotZero(rebound));
        assertEquals(7, rebound.getSortOrder());
        assertEquals(RefreshPolicy.DEFAULT_INTERVAL_MS, rebound.getRefreshIntervalMs());
        assertEquals(WIDGET_ONE, rebound.getWidgetId());
        assertEquals(TYPE_4X2, rebound.getWidgetType());
        assertEquals("the rebound copy is a new instance, so the old row's value survives until it is saved",
                accountA.getId(), slotZero(bound));
    }

    @Test
    public void nullTypeAndNullSlotsReadAsUnboundRatherThanNpe() {
        WidgetConfig config = new WidgetConfig(WIDGET_ONE, null, null,
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 0L);

        assertEquals("a widget with no slot must read as unbound, not blow up the renderer",
                "", slotZero(config));
        assertFalse("an unbound config must report itself as unbound", isBound(config));
        assertTrue("the slot list is empty rather than null", config.getSlots().isEmpty());
        assertEquals("", config.getWidgetType());
    }

    // --------------------------- the fallback target: first enabled account, stably

    @Test
    public void listEnabledExcludesDisabledAccountsAndKeepsTheStoredOrder() {
        assertEquals("both fixture accounts start enabled", 2, accountManager.listEnabled().size());

        accountManager.setEnabled(accountA.getId(), false);

        List<Account> enabled = accountManager.listEnabled();
        assertEquals("a disabled account must not be offered as a fallback target", 1, enabled.size());
        assertEquals(accountB.getId(), enabled.get(0).getId());
        assertTrue("whatever listEnabled returns must itself be enabled, or the fallback would target a disabled account",
                enabled.get(0).isEnabled());
        assertNull("the disabled account must be the one that dropped out",
                findById(enabled, accountA.getId()));

        assertEquals("disabling must not delete the account", 2, accountManager.list().size());
        assertNotNull(accountManager.find(accountA.getId()));
        assertFalse(accountManager.find(accountA.getId()).isEnabled());
    }

    @Test
    public void theFirstEnabledAccountIsDeterministicBySortOrder() {
        // Creation order gives A then B.
        assertEquals("a fresh account is appended to the end of the display order",
                0, accountManager.find(accountA.getId()).getSortOrder());
        assertEquals(1, accountManager.find(accountB.getId()).getSortOrder());
        assertEquals("WidgetUpdateManager.resolveAccount takes enabled.get(0), so this is the fallback target",
                accountA.getId(), accountManager.listEnabled().get(0).getId());

        // The user drags B above A.
        accountManager.reorder(java.util.Arrays.asList(accountB.getId(), accountA.getId()));

        assertEquals(0, accountManager.find(accountB.getId()).getSortOrder());
        assertEquals(1, accountManager.find(accountA.getId()).getSortOrder());
        assertEquals("after a reorder the fallback must follow the stored order, not creation order",
                accountB.getId(), accountManager.listEnabled().get(0).getId());
        assertEquals("a reorder must not change any account id",
                accountB.getId(), accountManager.list().get(0).getId());

        // And the unbound widget itself still has no opinion about which it is.
        assertFalse(isBound(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2,
                RefreshPolicy.DEFAULT_INTERVAL_MS)));
    }

    @Test
    public void anUnboundWidgetStillResolvesThroughTheStoresRowIndirection() {
        // The store returns the config a widget is rendered from; an unbound row
        // is present but empty, which is the state resolveAccount falls through.
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS));

        WidgetConfig stored = configStore.find(WIDGET_ONE);
        assertNotNull("an unbound widget still has a row", stored);
        assertFalse(isBound(stored));

        // WidgetUpdateManager looks the bound account up only when hasAccount();
        // here the fallback path is the one that runs.
        Account bound = isBound(stored) ? accountManager.find(slotZero(stored)) : null;
        assertNull(bound);
        List<Account> enabled = accountManager.listEnabled();
        assertEquals(accountA.getId(), enabled.get(0).getId());

        // A stale binding must fall back rather than render a blank widget: the
        // lookup by id returns null once the account is gone.
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot("acct_deleted_long_ago")));
        WidgetConfig stale = configStore.find(WIDGET_ONE);
        assertTrue("the row still claims a binding", isBound(stale));
        assertNull("but the account no longer resolves, so the fallback runs",
                accountManager.find(slotZero(stale)));
        assertEquals(accountA.getId(), accountManager.listEnabled().get(0).getId());
    }

    @Test
    public void forgettingAWidgetRemovesOnlyItsRow() {
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountA.getId())));
        configStore.save(WidgetConfig.unbound(WIDGET_TWO, TYPE_2X1, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountB.getId())));

        configStore.delete(WIDGET_ONE);

        assertNull("the removed widget's binding is gone", configStore.find(WIDGET_ONE));
        assertNotNull("the surviving widget keeps its binding", configStore.find(WIDGET_TWO));
        assertEquals(accountB.getId(), slotZero(configStore.find(WIDGET_TWO)));
        assertEquals(1, configStore.all().size());
    }

    @Test
    public void deletingOneAccountLeavesTheOtherWidgetsBindingResolvable() {
        configStore.save(WidgetConfig.unbound(WIDGET_ONE, TYPE_4X2, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountA.getId())));
        configStore.save(WidgetConfig.unbound(WIDGET_TWO, TYPE_2X1, RefreshPolicy.DEFAULT_INTERVAL_MS)
                .withSlots(singleSlot(accountB.getId())));

        // Widgets are not cleaned up by AccountManager; the binding row is the
        // widget layer's, and it survives the account's deletion. What must hold
        // is that the surviving widget is unaffected and the orphaned one falls back.
        accountManager.delete(accountA.getId(), null);

        assertNull(accountManager.find(accountA.getId()));
        assertEquals("deleting one account must not delete the sibling",
                accountB.getId(), accountManager.find(accountB.getId()).getId());
        assertEquals("the sibling widget still resolves to its own account",
                accountB.getId(), slotZero(configStore.find(WIDGET_TWO)));
        assertEquals(accountB.getId(), accountManager.find(
                slotZero(configStore.find(WIDGET_TWO))).getId());

        assertEquals("the orphaned widget falls back to the remaining enabled account",
                accountB.getId(), accountManager.listEnabled().get(0).getId());
        assertEquals("and the store still holds both rows — prune is the widget layer's job",
                2, configStore.all().size());
    }

    @Test
    public void theStoreStampsUpdatedAtAndOrdersAllBySortOrderThenWidgetId() {
        configStore.save(new WidgetConfig(WIDGET_TWO, TYPE_2X1, singleSlot(accountB.getId()),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 999L));
        configStore.save(new WidgetConfig(WIDGET_ONE, TYPE_4X2, singleSlot(accountA.getId()),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, 999L));

        List<WidgetConfig> all = configStore.all();
        assertEquals(2, all.size());
        assertEquals("ties are broken by widget id, as the SQL does",
                WIDGET_ONE, all.get(0).getWidgetId());
        assertEquals(WIDGET_TWO, all.get(1).getWidgetId());
        assertTrue("the store stamps updated_at itself rather than trusting the object",
                all.get(0).getUpdatedAt() > 999L);

        // A higher refresh_interval_ms must survive the round trip through the store.
        configStore.save(new WidgetConfig(WIDGET_ONE, TYPE_4X2, singleSlot(accountB.getId()),
                15L * 60L * 1000L, 3, 0L));
        WidgetConfig reloaded = configStore.find(WIDGET_ONE);
        assertEquals(15L * 60L * 1000L, reloaded.getRefreshIntervalMs());
        assertEquals(3, reloaded.getSortOrder());
        assertEquals(accountB.getId(), slotZero(reloaded));
        assertEquals("the row is replaced, not duplicated", 2, configStore.all().size());
    }

    // ---------------------------------------------------------------- fixtures

    /** One slot bound to {@code accountId} — what a Phase 2 binding became in v2. */
    private static List<WidgetSlot> singleSlot(String accountId) {
        return Collections.singletonList(WidgetSlot.ofAccount(0, accountId));
    }

    /**
     * The account slot 0 carries, or empty when the widget has no binding.
     *
     * <p>A test-side probe rather than a {@code WidgetConfig} accessor on purpose:
     * the slot list is the only source of the binding and the display decides
     * per slot, so a whole-widget "primary account" getter would be a second
     * answer to a question production no longer asks — which is how the list and
     * a widget once disagreed about the same row.
     */
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

    /**
     * Looks an account up in a list by id.
     *
     * <p>Not {@code List.contains}: {@link Account} has no {@code equals}
     * override and {@code AccountManager} rebuilds the instance on every edit, so
     * an identity check would be false regardless of the account's state.
     */
    private static Account findById(List<Account> accounts, String accountId) {
        for (Account account : accounts) {
            if (accountId.equals(account.getId())) {
                return account;
            }
        }
        return null;
    }

    /**
     * In-memory {@link WidgetConfigStore} mirroring {@code SqliteWidgetConfigStore}:
     * rows are keyed by {@code widget_id} and replaced on save together with their
     * whole slot list, {@code all()} is ordered {@code sort_order ASC, widget_id
     * ASC}, and {@code updated_at} is stamped by the store.
     */
    private static final class InMemoryWidgetConfigStore implements WidgetConfigStore {

        private final Map<Integer, WidgetConfig> byId = new LinkedHashMap<>();
        private long clock = System.currentTimeMillis();

        @Override
        public WidgetConfig find(int widgetId) {
            return byId.get(widgetId);
        }

        @Override
        public void save(WidgetConfig config) {
            if (config == null) {
                return;
            }
            // INSERT OR REPLACE on the widget row plus delete-and-insert on its
            // slots: the whole row is rewritten, and updated_at is the store's
            // clock rather than whatever the caller passed in.
            byId.put(config.getWidgetId(), new WidgetConfig(
                    config.getWidgetId(),
                    config.getWidgetType(),
                    config.getSlots(),
                    config.getRefreshIntervalMs(),
                    config.getSortOrder(),
                    ++clock));
        }

        @Override
        public void delete(int widgetId) {
            byId.remove(widgetId);
        }

        @Override
        public List<WidgetConfig> all() {
            List<WidgetConfig> configs = new ArrayList<>(byId.values());
            configs.sort(new Comparator<WidgetConfig>() {
                @Override
                public int compare(WidgetConfig left, WidgetConfig right) {
                    if (left.getSortOrder() != right.getSortOrder()) {
                        return Integer.compare(left.getSortOrder(), right.getSortOrder());
                    }
                    return Integer.compare(left.getWidgetId(), right.getWidgetId());
                }
            });
            return configs;
        }

        @Override
        public List<Integer> widgetIdsUsingAccount(String accountId) {
            List<Integer> ids = new ArrayList<>();
            if (accountId == null || accountId.isEmpty()) {
                return ids;
            }
            for (WidgetConfig config : all()) {
                if (config.accountIds().contains(accountId)) {
                    ids.add(config.getWidgetId());
                }
            }
            return ids;
        }

        @Override
        public long backgroundRefreshIntervalMs() {
            return RefreshPolicy.DEFAULT_INTERVAL_MS;
        }
    }

    /**
     * In-memory {@link AccountRepository} mirroring {@code SqliteAccountRepository}:
     * ordered {@code sort_order ASC, created_at ASC}, {@code findEnabled()} selects
     * only enabled rows, and {@code reorder} rewrites {@code sort_order} to the
     * given index.
     */
    private static final class InMemoryAccountRepository implements AccountRepository {

        private final Map<String, Account> byId = new LinkedHashMap<>();

        @Override
        public List<Account> findAll() {
            List<Account> all = new ArrayList<>(byId.values());
            all.sort(new Comparator<Account>() {
                @Override
                public int compare(Account left, Account right) {
                    if (left.getSortOrder() != right.getSortOrder()) {
                        return Integer.compare(left.getSortOrder(), right.getSortOrder());
                    }
                    return Long.compare(left.getCreatedAt(), right.getCreatedAt());
                }
            });
            return all;
        }

        @Override
        public List<Account> findEnabled() {
            List<Account> enabled = new ArrayList<>();
            for (Account account : findAll()) {
                if (account.isEnabled()) {
                    enabled.add(account);
                }
            }
            return enabled;
        }

        @Override
        public Account findById(String id) {
            if (id == null || id.isEmpty()) {
                return null;
            }
            return byId.get(id);
        }

        @Override
        public void save(Account account) {
            if (account == null || account.getId() == null || account.getId().isEmpty()) {
                return;
            }
            byId.put(account.getId(), account);
        }

        @Override
        public void delete(String id) {
            if (id == null || id.isEmpty()) {
                return;
            }
            byId.remove(id);
        }

        @Override
        public void reorder(List<String> orderedIds) {
            if (orderedIds == null) {
                return;
            }
            for (int index = 0; index < orderedIds.size(); index++) {
                Account account = byId.get(orderedIds.get(index));
                if (account != null) {
                    byId.put(account.getId(), account.toBuilder().sortOrder(index).build());
                }
            }
        }

        @Override
        public int count() {
            return byId.size();
        }
    }

    /**
     * In-memory {@link CredentialStore}. The binding tests never read a secret,
     * but {@code AccountManager.create} writes one, so this has to behave enough
     * like {@code SqliteCredentialStore} for an account to be creatable and
     * resolvable.
     */
    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, String> payloads = new LinkedHashMap<>();
        private int sequence;

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            String id = "cred_widget" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
            if (credentialId == null || credentialId.isEmpty() || !payloads.containsKey(credentialId)) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            String payload = credentialId == null ? null : payloads.get(credentialId);
            if (payload == null) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "账户未绑定凭据");
            }
            return new ApiKeyAuthAdapter().adapt(payload);
        }

        @Override
        public void delete(String credentialId) {
            if (credentialId == null || credentialId.isEmpty()) {
                return;
            }
            payloads.remove(credentialId);
        }

        @Override
        public boolean isUsable(String credentialId) {
            try {
                open(credentialId, AuthType.API_KEY);
                return true;
            } catch (AuthException exception) {
                return false;
            }
        }
    }
}
