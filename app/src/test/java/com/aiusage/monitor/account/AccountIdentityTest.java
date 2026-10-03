package com.aiusage.monitor.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.auth.ApiKeyAuthAdapter;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.refresh.RefreshPolicy;
import com.aiusage.monitor.usage.SnapshotRetention;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.usage.UsageSnapshotCodec;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.widget.WidgetConfig;
import com.aiusage.monitor.widget.WidgetConfigStore;
import com.aiusage.monitor.widget.WidgetSlot;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves the acceptance criterion in Spec §14: <em>changing an account's key
 * leaves the account id, its history and its widget configuration untouched</em>.
 *
 * <p>The production storage classes that normally carry this promise
 * ({@code SqliteAccountRepository}, {@code SqliteCredentialStore},
 * {@code SqliteUsageRepository}, {@code SqliteWidgetConfigStore}) all need an
 * Android {@code Context} and a Keystore, so they cannot run on the host JVM.
 * The fakes below re-encode the same contracts; each one names the production
 * statement it mirrors, so a reader can tell a real contract from an invented
 * one. The decisive contract is the one in {@code SqliteCredentialStore}'s class
 * javadoc: credentials are separate rows from accounts, so replacing a key
 * updates that row and the account row keeps its identity.
 *
 * <p>This is what makes "replace the key" safe: history rows and widget rows
 * store the account id, and nothing else, so as long as the id survives the key
 * change, every reference to it survives too.
 */
public class AccountIdentityTest {

    private static final String PROVIDER_ID = "deepseek";
    private static final String API_KEY_OLD = "sk-account-identity-old-key";
    private static final String API_KEY_NEW = "sk-account-identity-new-key";
    private static final long BASE_TIME = 1_700_000_000_000L;
    private static final int WIDGET_ID = 42;

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private InMemoryUsageRepository usage;
    private InMemoryWidgetConfigStore widgets;
    private AccountManager manager;

    @Before
    public void setUp() {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        usage = new InMemoryUsageRepository();
        widgets = new InMemoryWidgetConfigStore();
        manager = new AccountManager(accounts, credentials);
    }

    // ---------------------------------------------------------------- helpers

    private Account createWithKey(String displayName, String apiKey) throws AuthException {
        return manager.create(PROVIDER_ID, displayName, AuthType.API_KEY,
                CredentialPayload.forApiKey(apiKey));
    }

    /** Records one snapshot; {@code success} decides whether {@code latest()} can see it. */
    private void recordSnapshot(String accountId, double amount, boolean success, long updatedAt) {
        UsageResult result = UsageResult.builder()
                .accountId(accountId)
                .providerId(PROVIDER_ID)
                .balance(new Balance(amount, "CNY", Money.scale2(amount)))
                .status(success ? UsageStatus.OK : UsageStatus.NETWORK_ERROR)
                .updatedAt(updatedAt)
                .build();
        usage.save(accountId, result, success);
    }

    private void bindWidget(String accountId) {
        widgets.save(new WidgetConfig(WIDGET_ID, "balance",
                Collections.singletonList(WidgetSlot.ofAccount(0, accountId)),
                RefreshPolicy.DEFAULT_INTERVAL_MS, 0, BASE_TIME));
    }

    private List<String> historyPayloads(String accountId) {
        List<String> payloads = new ArrayList<>();
        for (UsageSnapshot snapshot : usage.history(accountId, 0L, Long.MAX_VALUE, 0)) {
            payloads.add(snapshot.getUsageData());
        }
        return payloads;
    }

    // ------------------------------------------------------ creating accounts

    @Test
    public void createWithKeyAssignsAnAccountIdAndABoundCredential() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);

        assertNotNull("create must return an account", account);
        assertFalse("create must assign an account id", account.getId().isEmpty());
        assertFalse("create must bind a credential id",
                account.getCredentialId() == null || account.getCredentialId().isEmpty());
        assertEquals("create must store the account under the id it returned",
                account.getId(), manager.find(account.getId()).getId());
    }

    // ------------------------------------- replaceCredential keeps identity

    @Test
    public void replaceCredentialKeepsTheAccountId() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        String accountId = account.getId();

        manager.replaceCredential(accountId, CredentialPayload.forApiKey(API_KEY_NEW));

        assertEquals("Spec §14: replacing the key must not change the account id",
                accountId, manager.find(accountId).getId());
        assertEquals("replacing the key must not add or drop account rows",
                1, accounts.count());
    }

    @Test
    public void replaceCredentialKeepsTheCreationTime() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        long createdAt = account.getCreatedAt();

        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        assertEquals("Spec §14: replacing the key must not rewrite the account's creation time",
                createdAt, manager.find(account.getId()).getCreatedAt());
    }

    @Test
    public void replaceCredentialUpdatesTheCredentialRowInPlace() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        String credentialId = account.getCredentialId();
        int rowsBefore = credentials.size();
        long createdAtBefore = credentials.createdAt(credentialId);

        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        assertEquals("replaceCredential must UPDATE the existing credential row, not create a new one",
                credentialId, manager.find(account.getId()).getCredentialId());
        assertEquals("replaceCredential must not leave an orphan credential row behind",
                rowsBefore, credentials.size());
        assertEquals("replaceCredential must not reset the credential row's creation time",
                createdAtBefore, credentials.createdAt(credentialId));
    }

    @Test
    public void replaceCredentialChangesTheStoredSecret() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        String credentialId = account.getCredentialId();

        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        assertEquals("the new key must actually replace the old one in the credential row",
                API_KEY_NEW, CredentialPayload.extractApiKey(credentials.storedPayload(credentialId)));
    }

    @Test
    public void replaceCredentialKeepsTheSnapshotHistory() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        recordSnapshot(account.getId(), 38.52d, true, BASE_TIME + 1);
        recordSnapshot(account.getId(), 37.10d, true, BASE_TIME + 2);
        recordSnapshot(account.getId(), 0d, false, BASE_TIME + 3);
        List<String> before = historyPayloads(account.getId());

        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        List<String> after = historyPayloads(account.getId());
        assertEquals("Spec §14: replacing the key must lose no history rows",
                before.size(), after.size());
        assertEquals("Spec §14: replacing the key must not rewrite the stored snapshots",
                before, after);
    }

    @Test
    public void replaceCredentialKeepsTheLastSuccessfulReading() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        recordSnapshot(account.getId(), 38.52d, true, BASE_TIME + 1);

        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        UsageResult latest = usage.latest(account.getId());
        assertNotNull("the last successful reading must survive a key change", latest);
        assertEquals("the last successful reading must keep its balance across a key change",
                new Balance(38.52d, "CNY", "38.52"), latest.getBalance());
    }

    @Test
    public void replaceCredentialKeepsTheWidgetBinding() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        bindWidget(account.getId());

        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        WidgetConfig stored = widgets.find(WIDGET_ID);
        assertNotNull("the widget configuration must still exist after a key change", stored);
        assertEquals("Spec §14: a widget bound to the account must still point at the same account id",
                account.getId(), slotZero(stored));
    }

    // --------------------------------------- clearCredential keeps identity

    @Test
    public void clearCredentialKeepsTheAccountId() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);

        manager.clearCredential(account.getId());

        assertEquals("Spec §14: clearing the key must not change the account id",
                account.getId(), manager.find(account.getId()).getId());
        assertEquals("clearing the key must not delete the account", 1, accounts.count());
    }

    @Test
    public void clearCredentialUnbindsTheSecretButKeepsTheAccount() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);

        manager.clearCredential(account.getId());

        Account reloaded = manager.find(account.getId());
        assertTrue("clearing the key must leave the account with no credential id",
                reloaded.getCredentialId() == null || reloaded.getCredentialId().isEmpty());
        assertEquals("clearing the key must delete the credential row",
                0, credentials.size());
    }

    @Test
    public void replaceCredentialAfterClearRecreatesTheCredentialButKeepsTheAccountId() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        String accountId = account.getId();
        String originalCredentialId = account.getCredentialId();
        manager.clearCredential(accountId);

        manager.replaceCredential(accountId, CredentialPayload.forApiKey(API_KEY_NEW));

        Account reloaded = manager.find(accountId);
        assertEquals("Spec §14: the account id must survive clear + replace",
                accountId, reloaded.getId());
        assertFalse("replaceCredential must bind a credential when the account had none",
                reloaded.getCredentialId() == null || reloaded.getCredentialId().isEmpty());
        assertNotEquals("a cleared credential cannot come back, so a new row must have been created",
                originalCredentialId, reloaded.getCredentialId());
    }

    @Test
    public void replaceCredentialAfterClearKeepsTheSnapshotHistory() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        recordSnapshot(account.getId(), 38.52d, true, BASE_TIME + 1);
        recordSnapshot(account.getId(), 0d, false, BASE_TIME + 2);
        int before = usage.history(account.getId(), 0L, Long.MAX_VALUE, 0).size();

        manager.clearCredential(account.getId());
        manager.replaceCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        assertEquals("Spec §14: clear + replace must lose no history rows",
                before, usage.history(account.getId(), 0L, Long.MAX_VALUE, 0).size());
        assertNotNull("Spec §14: clear + replace must not clear the last successful reading",
                usage.latest(account.getId()));
    }

    // -------------------------------------------- the other two key writers

    @Test
    public void updateCredentialKeepsTheAccountIdAndTheCredentialId() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);
        String credentialId = account.getCredentialId();

        manager.updateCredential(account.getId(), CredentialPayload.forApiKey(API_KEY_NEW));

        Account reloaded = manager.find(account.getId());
        assertEquals("updateCredential must not change the account id",
                account.getId(), reloaded.getId());
        assertEquals("updateCredential must not change the credential id",
                credentialId, reloaded.getCredentialId());
        assertEquals("updateCredential must not rewrite the account's creation time",
                account.getCreatedAt(), reloaded.getCreatedAt());
    }

    @Test
    public void renameKeepsTheAccountId() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);

        manager.rename(account.getId(), "DeepSeek工作");

        assertEquals("renaming an account must not change its id",
                account.getId(), manager.find(account.getId()).getId());
        assertEquals("renaming an account must change its display name",
                "DeepSeek工作", manager.find(account.getId()).getDisplayName());
    }

    @Test
    public void setEnabledKeepsTheAccountId() throws Exception {
        Account account = createWithKey("DeepSeek个人", API_KEY_OLD);

        manager.setEnabled(account.getId(), false);

        assertEquals("disabling an account must not change its id",
                account.getId(), manager.find(account.getId()).getId());
        assertFalse("disabling an account must persist the flag",
                manager.find(account.getId()).isEnabled());
    }

    @Test
    public void replaceCredentialOnAnUnknownAccountFailsInsteadOfCreatingOne() throws Exception {
        try {
            manager.replaceCredential("acct_missing", CredentialPayload.forApiKey(API_KEY_NEW));
            fail("replaceCredential must reject an unknown account rather than silently create one");
        } catch (IllegalArgumentException expected) {
            assertTrue("the failure must name the account id that was not found: "
                            + expected.getMessage(),
                    expected.getMessage().contains("acct_missing"));
        }
        assertEquals("a rejected replaceCredential must not write an account row", 0, accounts.count());
    }

    // =====================================================================
    // Fakes. Each mirrors a production contract named in its comment.
    // =====================================================================

    /**
     * In-memory {@link AccountRepository}. Mirrors the row semantics of
     * {@code SqliteAccountRepository}: save inserts or replaces by id, and
     * findAll orders by sort order and then by creation time.
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
            return id == null ? null : byId.get(id);
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
            if (id == null) {
                return;
            }
            byId.remove(id);
        }

        @Override
        public void reorder(List<String> orderedIds) {
            if (orderedIds == null) {
                return;
            }
            int order = 0;
            for (String id : orderedIds) {
                Account account = byId.get(id);
                if (account == null) {
                    continue;
                }
                byId.put(id, account.toBuilder().sortOrder(order++).build());
            }
        }

        @Override
        public int count() {
            return byId.size();
        }
    }

    /**
     * In-memory {@link CredentialStore}. Mirrors {@code SqliteCredentialStore}'s
     * id and error contract exactly; it deliberately skips the Keystore layer,
     * which needs Android and therefore cannot run on the host JVM. What matters
     * here is the structural promise: a credential is its own row, so
     * {@code update} keeps the id and the creation time, and an empty credential
     * id is rejected rather than treated as a row.
     */
    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, String> payloads = new LinkedHashMap<>();
        private final Map<String, Long> createdAts = new LinkedHashMap<>();
        private int sequence;

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            String id = "cred_test" + (++sequence);
            payloads.put(id, payload);
            createdAts.put(id, System.currentTimeMillis());
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
            // Mirrors SqliteCredentialStore.update: an empty id and an unknown id
            // are both rejected, and only the payload column is touched.
            if (credentialId == null || credentialId.isEmpty()) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据 ID 为空");
            }
            if (!payloads.containsKey(credentialId)) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在：" + credentialId);
            }
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            // Mirrors SqliteCredentialStore.open: an account with no credential
            // fails with INVALID_CREDENTIAL instead of resolving to a row.
            if (credentialId == null || credentialId.isEmpty()) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "账户未绑定凭据");
            }
            String payload = payloads.get(credentialId);
            if (payload == null) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            AuthType effective = type == null ? AuthType.API_KEY : type;
            if (effective == AuthType.API_KEY) {
                return new ApiKeyAuthAdapter().adapt(payload);
            }
            throw new AuthException(UsageError.UNSUPPORTED, "暂不支持该认证方式：" + effective);
        }

        @Override
        public void delete(String credentialId) {
            // Mirrors SqliteCredentialStore.delete: a missing id is ignored.
            if (credentialId == null || credentialId.isEmpty()) {
                return;
            }
            payloads.remove(credentialId);
            createdAts.remove(credentialId);
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

        String storedPayload(String credentialId) {
            return payloads.get(credentialId);
        }

        long createdAt(String credentialId) {
            Long value = createdAts.get(credentialId);
            return value == null ? 0L : value;
        }

        int size() {
            return payloads.size();
        }
    }

    /**
     * In-memory {@link UsageRepository}. The row model and the ordering rules
     * mirror the SQL in {@code SqliteUsageRepository} verbatim, because the
     * contract they encode — {@code latest()} sees only {@code success = 1} rows
     * — is exactly what Spec §39 rule 18 protects. Snapshots round-trip through
     * the real {@link UsageSnapshotCodec}, as the production column does.
     */
    private static final class InMemoryUsageRepository implements UsageRepository {

        private static final class Row {
            private final long id;
            private final String accountId;
            private final long timestamp;
            private final String usageData;
            private final String source;
            private final boolean success;

            private Row(long id, String accountId, long timestamp, String usageData,
                        String source, boolean success) {
                this.id = id;
                this.accountId = accountId;
                this.timestamp = timestamp;
                this.usageData = usageData;
                this.source = source;
                this.success = success;
            }
        }

        private final List<Row> rows = new ArrayList<>();
        private final Map<String, String> lastBalance = new LinkedHashMap<>();
        private final Map<String, BigDecimal> dailyTotals = new LinkedHashMap<>();
        private long nextId;

        @Override
        public void save(String accountId, UsageResult result, boolean success) {
            // Mirrors SqliteUsageRepository.save: a row with no account or no
            // result is dropped, and a failure is written as an ordinary row
            // with success = 0 — never as a delete of the previous good row.
            if (accountId == null || accountId.isEmpty() || result == null) {
                return;
            }
            long timestamp = result.getUpdatedAt() > 0L
                    ? result.getUpdatedAt()
                    : System.currentTimeMillis();
            rows.add(new Row(++nextId, accountId, timestamp,
                    UsageSnapshotCodec.encode(result), result.getSource().name(), success));
        }

        @Override
        public UsageResult latest(String accountId) {
            // Mirrors the SQL in SqliteUsageRepository.latest:
            //   WHERE account_id = ? AND success = 1 ORDER BY timestamp DESC, id DESC LIMIT 1
            // Only successful rows compete, which is why a failed refresh can
            // never clear the last successful data. Spec §39 rule 18.
            if (accountId == null || accountId.isEmpty()) {
                return null;
            }
            Row newest = null;
            for (Row row : rows) {
                if (!row.success || !accountId.equals(row.accountId)) {
                    continue;
                }
                if (newest == null || isNewer(row, newest)) {
                    newest = row;
                }
            }
            return newest == null ? null : UsageSnapshotCodec.decode(newest.usageData);
        }

        @Override
        public UsageResult latestAttempt(String accountId) {
            // Mirrors SqliteUsageRepository.latestAttempt: no success filter, so
            // the newest row wins whether it succeeded or failed.
            if (accountId == null || accountId.isEmpty()) {
                return null;
            }
            Row newest = null;
            for (Row row : rows) {
                if (!accountId.equals(row.accountId)) {
                    continue;
                }
                if (newest == null || isNewer(row, newest)) {
                    newest = row;
                }
            }
            return newest == null ? null : UsageSnapshotCodec.decode(newest.usageData);
        }

        @Override
        public List<UsageSnapshot> history(String accountId, long fromInclusive, long toExclusive,
                                       int limit) {
            // Mirrors SqliteUsageRepository.history: the filter is the account
            // only, so failed rows are included, and a non-positive limit means
            // the default page size of 100.
            List<UsageSnapshot> snapshots = new ArrayList<>();
            if (accountId == null || accountId.isEmpty()) {
                return snapshots;
            }
            int capped = limit <= 0 ? 100 : limit;
            List<Row> matching = new ArrayList<>();
            for (Row row : rows) {
                if (accountId.equals(row.accountId) && row.timestamp >= fromInclusive
                        && row.timestamp < toExclusive) {
                    matching.add(row);
                }
            }
            matching.sort(new Comparator<Row>() {
                @Override
                public int compare(Row left, Row right) {
                    if (left.timestamp != right.timestamp) {
                        return Long.compare(right.timestamp, left.timestamp);
                    }
                    return Long.compare(right.id, left.id);
                }
            });
            for (int index = 0; index < matching.size() && index < capped; index++) {
                Row row = matching.get(index);
                snapshots.add(new UsageSnapshot(row.id, row.accountId, row.timestamp,
                        row.usageData, row.source, row.success));
            }
            return snapshots;
        }

    /** Mirrors SqliteUsageRepository.prune: the policy decides, this deletes exactly that list. */
        @Override
        public int prune(SnapshotRetention retention, long nowMs) {
            List<UsageSnapshot> light = new ArrayList<>();
            for (Row row : rows) {
                light.add(new UsageSnapshot(row.id, row.accountId, row.timestamp, "", "", row.success));
            }
            List<Long> doomed = retention.expired(light, nowMs);
            rows.removeIf(row -> doomed.contains(row.id));
            return doomed.size();
        }

        @Override
        public void deleteForAccount(String accountId) {
            if (accountId == null || accountId.isEmpty()) {
                return;
            }
            List<Row> remaining = new ArrayList<>();
            for (Row row : rows) {
                if (!accountId.equals(row.accountId)) {
                    remaining.add(row);
                }
            }
            rows.clear();
            rows.addAll(remaining);
            lastBalance.remove(accountId);
            dailyTotals.remove(accountId);
        }

        @Override
        public BigDecimal recordDailyUsage(String accountId, String rawBalance) {
            if (accountId == null || accountId.isEmpty() || Money.parse2(rawBalance) == null) {
                return null;
            }
            // Mirrors SqliteUsageRepository.recordDailyUsage for the same-day
            // case, which is the only case a single test can reach; the day
            // rollover branch is covered by MoneyTest.
            BigDecimal total = Money.accumulate(
                    dailyTotals.get(accountId) == null ? null : dailyTotals.get(accountId).toPlainString(),
                    lastBalance.get(accountId), true, rawBalance);
            if (total == null) {
                total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            lastBalance.put(accountId, rawBalance);
            dailyTotals.put(accountId, total);
            return total;
        }

        @Override
        public BigDecimal dailyUsage(String accountId) {
            if (accountId == null || accountId.isEmpty()) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal total = dailyTotals.get(accountId);
            return total == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : total;
        }

        private static boolean isNewer(Row candidate, Row current) {
            if (candidate.timestamp != current.timestamp) {
                return candidate.timestamp > current.timestamp;
            }
            return candidate.id > current.id;
        }
    }

    /** In-memory {@link WidgetConfigStore}, keyed by widget id like the SQL table. */
    private static final class InMemoryWidgetConfigStore implements WidgetConfigStore {

        private final Map<Integer, WidgetConfig> byId = new LinkedHashMap<>();

        @Override
        public WidgetConfig find(int widgetId) {
            return byId.get(widgetId);
        }

        @Override
        public void save(WidgetConfig config) {
            if (config == null) {
                return;
            }
            byId.put(config.getWidgetId(), config);
        }

        @Override
        public void delete(int widgetId) {
            byId.remove(widgetId);
        }

        @Override
        public List<WidgetConfig> all() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<Integer> widgetIdsUsingAccount(String accountId) {
            List<Integer> ids = new ArrayList<>();
            for (WidgetConfig config : all()) {
                if (accountId != null && config.accountIds().contains(accountId)) {
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
