package com.aiusage.monitor.refresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.account.AccountRepository;
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
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.usage.SnapshotRetention;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.usage.UsageSnapshotCodec;
import com.aiusage.monitor.util.Money;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves Spec §39 rule 18: <em>a failed refresh never clears the last
 * successful data</em>.
 *
 * <p>The rule is a property of two pieces working together. The repository's
 * {@code latest()} must consider only successful rows, and the refresh manager
 * must write a failure as a new row rather than as a delete. This test pins both
 * halves: the fake repository re-encodes the SQL predicate
 * {@code account_id = ? AND success = 1} from {@code SqliteUsageRepository}, so
 * if the manager ever started "clearing on failure" by deleting rows, the
 * assertions here would see the last success disappear.
 *
 * <p>The user-visible promise is that a network blip leaves the balance on
 * screen and in the widget exactly as it was, with the failure surfaced
 * separately as a status and a message.
 */
public class FailurePreservesLastSuccessTest {

    private static final String PROVIDER_ID = "test-failing";
    private static final String API_KEY = "sk-failure-preserves-success";
    private static final double GOOD_BALANCE = 38.52d;
    private static final long BASE_TIME = 1_700_000_000_000L;

    /**
     * The registry is a JVM-wide singleton, so the stub registered under
     * {@link #PROVIDER_ID} has to be one instance for the whole class: a fresh
     * stub per test would be rejected by the registry's duplicate-id guard.
     * Its answer is reset before every test instead, so tests stay independent
     * without pretending the registry is per-test.
     */
    private static SwitchableProvider provider;

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private InMemoryUsageRepository usage;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;

    @Before
    public void setUp() {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        usage = new InMemoryUsageRepository();
        accountManager = new AccountManager(accounts, credentials);

        provider = sharedProvider();
        provider.reset();
        refreshManager = new AccountRefreshManager(accountManager, usage, ProviderRegistry.get());
    }

    private static SwitchableProvider sharedProvider() {
        if (provider == null) {
            provider = new SwitchableProvider(PROVIDER_ID);
        }
        ProviderRegistry registry = ProviderRegistry.get();
        if (registry.find(PROVIDER_ID) != provider) {
            registry.register(provider);
        }
        return provider;
    }

    // ---------------------------------------------------------------- helpers

    private Account createAccountWithKey() throws AuthException {
        return accountManager.create(PROVIDER_ID, "DeepSeek个人", AuthType.API_KEY,
                CredentialPayload.forApiKey(API_KEY));
    }

    private static UsageResult okResult(String accountId, double amount) {
        return UsageResult.builder()
                .accountId(accountId)
                .providerId(PROVIDER_ID)
                .balance(new Balance(amount, "CNY", Money.scale2(amount)))
                .status(UsageStatus.OK)
                .updatedAt(BASE_TIME)
                .build();
    }

    private int snapshotCount(String accountId) {
        return usage.history(accountId, 0L, Long.MAX_VALUE, 0).size();
    }

    // ------------------------------------------- the happy path comes first

    @Test
    public void successfulRefreshStoresASnapshotAndLatestReturnsItsBalance() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue("a provider that answers must produce a successful outcome",
                outcome.isSuccess());
        assertNotNull("a successful outcome must carry the fetched result", outcome.getResult());
        assertEquals("the stored balance must be the one the provider reported",
                new Balance(GOOD_BALANCE, "CNY", "38.52"), usage.latest(account.getId()).getBalance());
        assertEquals("a successful refresh must append exactly one snapshot",
                1, snapshotCount(account.getId()));
    }

    @Test
    public void successfulRefreshStampsTheAccountIdOnTheStoredResult() throws Exception {
        Account account = createAccountWithKey();
        // The provider deliberately reports a different account id: the manager
        // owns the identity stamp, so the stored row must be self-describing.
        provider.succeedWith(okResult("some-other-id", GOOD_BALANCE));

        refreshManager.refresh(account);

        assertEquals("the stored snapshot must be stamped with the refreshing account's id",
                account.getId(), usage.latest(account.getId()).getAccountId());
    }

    // ------------------------------------- the rule: failure keeps the data

    @Test
    public void failedRefreshLeavesTheLastSuccessfulBalanceInPlace() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);
        UsageResult afterSuccess = usage.latest(account.getId());

        provider.failWith(new UsageException(UsageError.NETWORK_ERROR, "网络连接失败，请检查网络后重试"));
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertFalse("a provider that throws must produce a failed outcome", outcome.isSuccess());
        UsageResult afterFailure = usage.latest(account.getId());
        assertNotNull("Spec §39 rule 18: a failed refresh must never clear the last successful data",
                afterFailure);
        assertEquals("Spec §39 rule 18: the surviving reading must be the previous successful one",
                new Balance(GOOD_BALANCE, "CNY", "38.52"), afterFailure.getBalance());
        assertEquals("Spec §39 rule 18: the surviving reading must keep its timestamp",
                afterSuccess.getUpdatedAt(), afterFailure.getUpdatedAt());
    }

    @Test
    public void failedRefreshStillAppendsAFailureSnapshot() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        provider.failWith(new UsageException(UsageError.NETWORK_ERROR, "网络连接失败，请检查网络后重试"));
        refreshManager.refresh(account);

        List<UsageSnapshot> history = usage.history(account.getId(), 0L, Long.MAX_VALUE, 0);
        assertEquals("a failed attempt must be recorded as its own row", 2, history.size());
        UsageSnapshot newest = history.get(0);
        assertFalse("the newest row must be marked as a failure", newest.isSuccess());
        assertTrue("the older successful row must still be there and still marked successful",
                history.get(1).isSuccess());
    }

    @Test
    public void failedRefreshReportsAnErrorAndANonEmptyMessage() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        provider.failWith(new UsageException(UsageError.NETWORK_ERROR, "网络连接失败，请检查网络后重试"));
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertFalse("a failed refresh must report isSuccess() == false", outcome.isSuccess());
        assertEquals("the outcome must carry the mapped error",
                UsageError.NETWORK_ERROR, outcome.getError());
        assertNotNull("the outcome must carry a message", outcome.getMessage());
        assertFalse("the message must not be empty — the UI has nothing else to show",
                outcome.getMessage().trim().isEmpty());
        assertNull("a failed outcome must not carry a result", outcome.getResult());
    }

    @Test
    public void failedRefreshWithoutADetailStillReportsTheCategoryMessage() throws Exception {
        Account account = createAccountWithKey();
        provider.failWith(new UsageException(UsageError.SERVICE_UNAVAILABLE, null));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertFalse("a failure with no detail must still fail", outcome.isSuccess());
        assertEquals("a failure with no detail must fall back to the category's own message",
                UsageError.SERVICE_UNAVAILABLE.getMessage(), outcome.getMessage());
    }

    @Test
    public void failedRefreshMapsTheErrorOntoAStatus() throws Exception {
        Account account = createAccountWithKey();
        provider.failWith(new UsageException(UsageError.INVALID_CREDENTIAL, "API Key 无效或已失效"));

        refreshManager.refresh(account);

        List<UsageSnapshot> history = usage.history(account.getId(), 0L, Long.MAX_VALUE, 0);
        assertEquals("a rejected key must be recorded as one failed snapshot", 1, history.size());
        assertEquals("a rejected key must be recorded with the AUTH_REQUIRED status",
                UsageStatus.AUTH_REQUIRED.name(), history.get(0).toUsageResult().getStatus().name());
    }

    // --------------------------------------- an account with no credential

    @Test
    public void accountWithoutACredentialFailsCleanly() throws Exception {
        Account account = accountManager.createAccount(PROVIDER_ID, "未绑定密钥", AuthType.API_KEY);
        assertEquals("the fixture must start from an account with no credential",
                "", account.getCredentialId());

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertFalse("an account with no credential must fail rather than throw", outcome.isSuccess());
        assertNotNull("the failure must carry an error", outcome.getError());
        assertNotNull("the failure must carry a message", outcome.getMessage());
        assertFalse("the failure message must not be empty",
                outcome.getMessage().trim().isEmpty());
    }

    @Test
    public void accountWithoutACredentialDoesNotCreateASuccessfulSnapshot() throws Exception {
        Account account = accountManager.createAccount(PROVIDER_ID, "未绑定密钥", AuthType.API_KEY);
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));

        refreshManager.refresh(account);

        assertNull("an account with no credential must never produce a successful reading",
                usage.latest(account.getId()));
        List<UsageSnapshot> history = usage.history(account.getId(), 0L, Long.MAX_VALUE, 0);
        assertEquals("the failed attempt must still be recorded", 1, history.size());
        assertFalse("the recorded attempt must be marked as a failure", history.get(0).isSuccess());
    }

    @Test
    public void accountWithoutACredentialKeepsAnEarlierSuccess() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        accountManager.clearCredential(account.getId());
        Account cleared = accountManager.find(account.getId());
        assertEquals("clearing the key must leave the account with no credential",
                "", cleared.getCredentialId());

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(cleared);

        assertFalse("refreshing after clearing the key must fail", outcome.isSuccess());
        assertNotNull("Spec §39 rule 18: clearing the key must not clear the last successful data",
                usage.latest(account.getId()));
        assertEquals("Spec §39 rule 18: the last successful balance must survive clearing the key",
                new Balance(GOOD_BALANCE, "CNY", "38.52"), usage.latest(account.getId()).getBalance());
    }

    // ------------------------------------------- an unexpected provider bug

    @Test
    public void providerThrowingARuntimeExceptionIsContainedAsAFailure() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        provider.failWithRuntime(new IllegalStateException("provider bug"));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertFalse("a provider bug must surface as a failure, not as a thrown exception",
                outcome.isSuccess());
        assertEquals("an unclassified provider bug must map to UNKNOWN",
                UsageError.UNKNOWN, outcome.getError());
        assertNotNull("the failure must still carry a message", outcome.getMessage());
    }

    @Test
    public void providerThrowingARuntimeExceptionStillPreservesTheLastSuccess() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        provider.failWithRuntime(new IllegalStateException("provider bug"));
        refreshManager.refresh(account);

        UsageResult latest = usage.latest(account.getId());
        assertNotNull("Spec §39 rule 18: a provider bug must not clear the last successful data",
                latest);
        assertEquals("Spec §39 rule 18: the surviving balance must be the last successful one",
                new Balance(GOOD_BALANCE, "CNY", "38.52"), latest.getBalance());
    }

    @Test
    public void repeatedFailuresNeverClearTheLastSuccess() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        provider.failWith(new UsageException(UsageError.NETWORK_ERROR, "网络连接失败，请检查网络后重试"));
        for (int attempt = 0; attempt < 5; attempt++) {
            AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);
            assertFalse("attempt " + attempt + " must fail", outcome.isSuccess());
        }

        assertNotNull("Spec §39 rule 18: repeated failures must not erode the last successful data",
                usage.latest(account.getId()));
        assertEquals("Spec §39 rule 18: the balance must be unchanged after repeated failures",
                new Balance(GOOD_BALANCE, "CNY", "38.52"), usage.latest(account.getId()).getBalance());
        assertEquals("every attempt must be recorded, successes and failures alike",
                6, snapshotCount(account.getId()));
    }

    @Test
    public void aLaterSuccessReplacesTheEarlierReading() throws Exception {
        Account account = createAccountWithKey();
        provider.succeedWith(okResult(account.getId(), GOOD_BALANCE));
        refreshManager.refresh(account);

        provider.succeedWith(okResult(account.getId(), 12.34d));
        refreshManager.refresh(account);

        assertEquals("a later success must win over the earlier one",
                new Balance(12.34d, "CNY", "12.34"), usage.latest(account.getId()).getBalance());
    }

    @Test
    public void refreshOfAnUnknownAccountIdFailsWithoutThrowing() {
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh("acct_missing");

        assertFalse("refreshing an unknown account must fail rather than throw",
                outcome.isSuccess());
        assertNotNull("the failure must carry a message", outcome.getMessage());
        assertFalse("the failure message must not be empty",
                outcome.getMessage().trim().isEmpty());
    }

    // =====================================================================
    // Fakes and stubs
    // =====================================================================

    /**
     * A {@link UsageProvider} whose next answer the test chooses: a result, a
     * {@link UsageException}, or a raw {@link RuntimeException}. The last case
     * exists because Spec §39 requires a provider bug to be contained rather
     * than to abort the refresh of every other account.
     */
    private static final class SwitchableProvider implements UsageProvider {

        private final String id;
        private UsageResult nextResult;
        private UsageException nextException;
        private RuntimeException nextRuntimeException;

        private SwitchableProvider(String id) {
            this.id = id;
        }

        /** Clears the programmed answer so the next test starts from a known state. */
        void reset() {
            this.nextResult = null;
            this.nextException = null;
            this.nextRuntimeException = null;
        }

        void succeedWith(UsageResult result) {
            this.nextResult = result;
            this.nextException = null;
            this.nextRuntimeException = null;
        }

        void failWith(UsageException exception) {
            this.nextResult = null;
            this.nextException = exception;
            this.nextRuntimeException = null;
        }

        void failWithRuntime(RuntimeException exception) {
            this.nextResult = null;
            this.nextException = null;
            this.nextRuntimeException = exception;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return "Test Failing Provider";
        }

        @Override
        public List<AuthType> getSupportedAuthTypes() {
            return Arrays.asList(AuthType.API_KEY);
        }

        @Override
        public ProviderCapabilities getCapabilities() {
            return ProviderCapabilities.builder()
                    .supports(AuthType.API_KEY)
                    .reportsBalance(true)
                    .build();
        }

        @Override
        public UsageResult fetchUsage(Account account, AuthContext authContext) throws UsageException {
            if (nextRuntimeException != null) {
                throw nextRuntimeException;
            }
            if (nextException != null) {
                throw nextException;
            }
            return nextResult;
        }
    }

    /** In-memory {@link AccountRepository}, mirroring {@code SqliteAccountRepository} rows. */
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
     * In-memory {@link CredentialStore}, mirroring the parts of
     * {@code SqliteCredentialStore} this test depends on: an empty credential id
     * is rejected with {@code INVALID_CREDENTIAL} (never resolved), and
     * {@code delete} drops the row so the account afterwards has no credential.
     * The Keystore layer is deliberately absent — it needs Android.
     */
    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, String> payloads = new LinkedHashMap<>();
        private int sequence;

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            String id = "cred_test" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
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
            // Mirrors SqliteCredentialStore.open: this is the branch an account
            // with no credential takes, and it must fail rather than return an
            // empty context that a provider would then send upstream.
            if (credentialId == null || credentialId.isEmpty()) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "账户未绑定凭据");
            }
            String payload = payloads.get(credentialId);
            if (payload == null) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
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

    /**
     * In-memory {@link UsageRepository}. The predicates mirror the SQL in
     * {@code SqliteUsageRepository} verbatim — {@code latest()} filters on
     * {@code success = 1}, {@code history()} does not, and both order by
     * timestamp then id descending — because that asymmetry <em>is</em> the
     * contract Spec §39 rule 18 relies on. Snapshots round-trip through the real
     * {@link UsageSnapshotCodec}, as the production column does.
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
            // Same ordering as latest() but without the success filter.
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
            BigDecimal previousTotal = dailyTotals.get(accountId);
            BigDecimal total = Money.accumulate(
                    previousTotal == null ? null : previousTotal.toPlainString(),
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
}
