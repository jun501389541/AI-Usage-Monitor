package com.aiusage.monitor.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.refresh.RefreshPolicy;
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
 * Proves the Phase 2 acceptance criteria in {@code docs/PHASE-0-2-PLAN.md} §2.3
 * that can be settled on the host JVM: <em>two accounts on one platform are
 * isolated from each other at every layer</em>.
 *
 * <p>The plan states the user-visible promise as "两个账户余额互不相同且都对" and
 * "一个账户刷新失败，另一个仍正常显示". Those rows are device smoke tests in the plan;
 * what is provable here is the machinery underneath them, and that is what this
 * class pins:
 *
 * <ul>
 *   <li>criterion 1 — each account is fetched with <em>its own</em> credential, so
 *       two keys on one provider cannot be mixed up;</li>
 *   <li>criterion 2 — one account's failed refresh leaves the other account's
 *       stored bytes, history and daily accumulator exactly as they were;</li>
 *   <li>criterion 3 — {@code dailyUsage} is a per-account accumulator, so a
 *       balance drop in A cannot move B's number;</li>
 *   <li>criterion 4 — {@code refreshAll()} reports one outcome per enabled
 *       account, attributed by account id, and skips disabled accounts;</li>
 *   <li>criterion 5 — changing one account's key changes neither its id nor its
 *       sibling's state.</li>
 * </ul>
 *
 * <p>Every class exercised here is production code and Android-free:
 * {@link AccountManager}, {@link AccountRefreshManager}, {@code UsageSnapshotCodec}
 * and {@link Money}. The three storage classes that would normally carry this
 * promise across a process restart ({@code SqliteAccountRepository},
 * {@code SqliteCredentialStore}, {@code SqliteUsageRepository}) need an Android
 * {@code Context} and a Keystore, so the fakes below re-encode them; each fake
 * names the production statement it mirrors, and in particular the fake usage
 * repository copies the real SQL predicates — {@code latest()} filters on
 * {@code success = 1} while {@code history()} does not — because that asymmetry
 * is what Spec §39 rule 18 relies on.
 *
 * <p>The isolation assertions are written as <em>whole-history fingerprints</em>
 * rather than as individual field checks: "B was not touched" has to mean no row
 * of B changed at all, not just that one number still matches.
 */
public class MultiAccountIsolationTest {

    /**
     * A provider id of this class's own. The registry is a JVM-wide singleton, so
     * reusing {@code deepseek} (or another test's stub id) would either collide
     * with the built-in provider or hide this class's stub behind it.
     */
    private static final String PROVIDER_ID = "test-multi-account-isolation";

    private static final String NAME_A = "账户 A";
    private static final String NAME_B = "账户 B";
    private static final String KEY_A = "sk-isolation-account-a";
    private static final String KEY_B = "sk-isolation-account-b";
    private static final String KEY_A_ROTATED = "sk-isolation-account-a-rotated";

    /**
     * The registry lives for the whole JVM while JUnit builds a new test-class
     * instance per method, so the stub has to be one instance for all tests: a
     * fresh stub per test would be rejected by the duplicate-id guard. Its
     * answers are cleared in {@link #setUp()} instead, which keeps the tests
     * independent without pretending the registry is per-test.
     */
    private static PerAccountProvider provider;

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private InMemoryUsageRepository usage;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;
    private Account accountA;
    private Account accountB;

    @Before
    public void setUp() throws Exception {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        usage = new InMemoryUsageRepository();
        accountManager = new AccountManager(accounts, credentials);

        provider = sharedProvider();
        provider.reset();

        refreshManager = new AccountRefreshManager(accountManager, usage, ProviderRegistry.get());

        accountA = createWithKey(NAME_A, KEY_A);
        accountB = createWithKey(NAME_B, KEY_B);
    }

    private static PerAccountProvider sharedProvider() {
        if (provider == null) {
            provider = new PerAccountProvider(PROVIDER_ID);
        }
        ProviderRegistry registry = ProviderRegistry.get();
        if (registry.find(PROVIDER_ID) != provider) {
            registry.register(provider);
        }
        return provider;
    }

    // ---------------------------------------------------------------- helpers

    private Account createWithKey(String displayName, String apiKey) throws AuthException {
        return accountManager.create(PROVIDER_ID, displayName, AuthType.API_KEY,
                CredentialPayload.forApiKey(apiKey));
    }

    /** Programs this account's next success. Answers are keyed by account id. */
    private void programSuccess(Account account, String rawBalance) {
        programSuccess(account, UsageResult.builder()
                .balance(new Balance(Double.parseDouble(rawBalance), "CNY", rawBalance))
                .status(UsageStatus.OK)
                .build());
    }

    private void programSuccess(Account account, UsageResult result) {
        provider.succeedWith(account.getId(), result);
    }

    private void programFailure(Account account, UsageError error, String message) {
        provider.failWith(account.getId(), new UsageException(error, message));
    }

    private void assertRefreshed(Account account) {
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);
        assertTrue("setup expects a successful refresh for " + account.getDisplayName()
                + " but got " + outcome.getError(), outcome.isSuccess());
    }

    /**
     * Finds an account in a list by id.
     *
     * <p>Deliberately not {@code List.contains}: {@link Account} has no
     * {@code equals} override and {@code AccountManager} stores a rebuilt instance
     * on every edit, so an identity comparison would be false whether or not the
     * account were present — a vacuous assertion that can never fail.
     */
    private static Account findEnabledById(List<Account> accounts, String accountId) {
        for (Account account : accounts) {
            if (accountId.equals(account.getId())) {
                return account;
            }
        }
        return null;
    }

    /**
     * A byte-level fingerprint of one account's stored history. Two fingerprints
     * that are equal mean no row of that account was added, removed or rewritten.
     */
    private List<String> fingerprint(String accountId) {
        List<String> rows = new ArrayList<>();
        for (UsageSnapshot snapshot : usage.history(accountId, 0L, Long.MAX_VALUE, 0)) {
            rows.add(snapshot.getId() + "|" + snapshot.getAccountId() + "|" + snapshot.getTimestamp()
                    + "|" + snapshot.getSource() + "|" + snapshot.isSuccess()
                    + "|" + snapshot.getUsageData());
        }
        return rows;
    }

    private static String rawTextOf(UsageResult result) {
        return result == null || result.getBalance() == null
                ? null
                : result.getBalance().getRawText();
    }

    private String lastRawText(String accountId) {
        return rawTextOf(usage.latest(accountId));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull("daily usage must never be null — the repository promises a zero", actual);
        assertEquals(expected, actual.toPlainString());
    }

    // ------------------------------------------- criterion 1: one key per account

    @Test
    public void eachAccountIsFetchedWithItsOwnCredential() {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");

        assertNotEquals("the fixture must use two different keys, otherwise this proves nothing",
                KEY_A, KEY_B);

        assertRefreshed(accountA);
        assertEquals("account A must be fetched with A's key", KEY_A,
                provider.lastApiKeyFor(accountA.getId()));
        assertNull("fetching A must not fetch B", provider.lastApiKeyFor(accountB.getId()));

        assertRefreshed(accountB);
        assertEquals("account B must be fetched with B's key", KEY_B,
                provider.lastApiKeyFor(accountB.getId()));
        assertNotEquals("A's key must never be offered for B", KEY_A,
                provider.lastApiKeyFor(accountB.getId()));
    }

    @Test
    public void eachAccountKeepsItsOwnLastKnownReading() {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");
        assertRefreshed(accountA);
        assertRefreshed(accountB);

        UsageResult lastA = refreshManager.view(accountA.getId()).lastSuccess;
        UsageResult lastB = refreshManager.view(accountB.getId()).lastSuccess;

        assertNotNull(lastA);
        assertNotNull(lastB);
        assertEquals("100.00", rawTextOf(lastA));
        assertEquals("50.00", rawTextOf(lastB));
        assertEquals(UsageStatus.OK, lastA.getStatus());
        assertEquals(UsageStatus.OK, lastB.getStatus());
    }

    @Test
    public void refreshingOneAccountStampsOnlyThatAccountsIdentity() {
        // A provider that mis-reports the identity it was called with must not be
        // able to write into another account's row — the manager stamps the
        // identity from the account, not from the provider's payload.
        programSuccess(accountA, UsageResult.builder()
                .accountId("somewhere-else")
                .providerId("someone-else")
                .balance(new Balance(100.00d, "CNY", "100.00"))
                .status(UsageStatus.OK)
                .build());

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(accountA);

        assertTrue(outcome.isSuccess());
        assertEquals(accountA.getId(), outcome.getResult().getAccountId());
        assertEquals(PROVIDER_ID, outcome.getResult().getProviderId());

        UsageResult stored = usage.latest(accountA.getId());
        assertNotNull(stored);
        assertEquals(accountA.getId(), stored.getAccountId());
        assertEquals(PROVIDER_ID, stored.getProviderId());
        assertNull("the provider's stray id must not have become an account row",
                usage.latest("somewhere-else"));
        assertNull("the sibling account must not have been written to at all",
                usage.latest(accountB.getId()));
    }

    // --------------------------------- criterion 2: a failure stays inside one account

    @Test
    public void aFailedRefreshLeavesItsOwnEarlierSuccessIntact() {
        programSuccess(accountA, "100.00");
        assertRefreshed(accountA);

        programFailure(accountA, UsageError.NETWORK_ERROR, "模拟网络失败");
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(accountA);

        assertFalse(outcome.isSuccess());
        assertEquals(accountA.getId(), outcome.getAccountId());
        assertEquals(UsageError.NETWORK_ERROR, outcome.getError());
        assertNull("a failure carries no result", outcome.getResult());

        assertEquals("Spec §39 rule 18: a failed refresh must not clear the last success",
                "100.00", lastRawText(accountA.getId()));
    }

    @Test
    public void aFailedRefreshLeavesTheOtherAccountUntouched() {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");
        assertRefreshed(accountA);
        assertRefreshed(accountB);

        List<String> rowsOfB = fingerprint(accountB.getId());
        BigDecimal dailyOfB = usage.dailyUsage(accountB.getId());
        long readingOfB = usage.latest(accountB.getId()).getUpdatedAt();

        programFailure(accountA, UsageError.NETWORK_ERROR, "模拟网络失败");
        assertFalse(refreshManager.refresh(accountA).isSuccess());

        assertEquals("B's rows must be byte-identical after A's failure",
                rowsOfB, fingerprint(accountB.getId()));
        assertEquals("B's last successful reading must not be rewritten",
                readingOfB, usage.latest(accountB.getId()).getUpdatedAt());
        assertEquals("B's reading must not be discarded", "50.00", lastRawText(accountB.getId()));
        assertMoney(dailyOfB.toPlainString(), usage.dailyUsage(accountB.getId()));

        assertEquals("A keeps its own earlier success", "100.00", lastRawText(accountA.getId()));

        // The failed attempt is recorded for A — it must be visible in history
        // without being visible as A's balance.
        List<String> rowsOfA = fingerprint(accountA.getId());
        assertEquals("A must record the attempt", rowsOfB.size() + 1, rowsOfA.size());
        assertTrue("the newest row of A must be the failed one",
                rowsOfA.get(0).contains("|false|"));
    }

    @Test
    public void aFailureInOneAccountForOneProviderIsolatedFromTheOthersSuccess() {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");
        assertRefreshed(accountA);
        assertRefreshed(accountB);

        // A's provider now blows up at the RuntimeException level, the worst case:
        // it must still be contained and must still not reach B's data.
        provider.failWithRuntime(accountA.getId(), new IllegalStateException("provider bug"));
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(accountA);

        assertFalse(outcome.isSuccess());
        assertEquals(UsageError.UNKNOWN, outcome.getError());
        assertEquals("100.00", lastRawText(accountA.getId()));
        assertEquals("50.00", lastRawText(accountB.getId()));
    }

    // ------------------------------------- criterion 3: daily usage is per account

    @Test
    public void dailyUsageAccumulatorsAreSeparatePerAccount() {
        assertMoney("0.00", usage.dailyUsage(accountA.getId()));
        assertMoney("0.00", usage.dailyUsage(accountB.getId()));

        programSuccess(accountA, "100.00");
        assertRefreshed(accountA);
        assertMoney("0.00", usage.dailyUsage(accountA.getId()));

        programSuccess(accountA, "91.50");
        assertRefreshed(accountA);
        assertMoney("8.50", usage.dailyUsage(accountA.getId()));

        // B starts its own accumulator: its first reading is not compared with A's.
        programSuccess(accountB, "50.00");
        assertRefreshed(accountB);
        assertMoney("0.00", usage.dailyUsage(accountB.getId()));
        assertMoney("8.50", usage.dailyUsage(accountA.getId()));

        programSuccess(accountB, "47.30");
        assertRefreshed(accountB);
        assertMoney("2.70", usage.dailyUsage(accountB.getId()));
        assertMoney("8.50", usage.dailyUsage(accountA.getId()));
    }

    @Test
    public void aBalanceDropInOneAccountLeavesTheOtherAccountsDailyUsageAlone() {
        programSuccess(accountA, "100.00");
        assertRefreshed(accountA);
        programSuccess(accountA, "91.50");
        assertRefreshed(accountA);

        programSuccess(accountB, "50.00");
        assertRefreshed(accountB);
        programSuccess(accountB, "47.30");
        assertRefreshed(accountB);

        assertMoney("8.50", usage.dailyUsage(accountA.getId()));
        assertMoney("2.70", usage.dailyUsage(accountB.getId()));

        List<String> rowsOfB = fingerprint(accountB.getId());

        // A spends again. B's accumulator and B's rows must not move.
        programSuccess(accountA, "80.00");
        assertRefreshed(accountA);

        assertMoney("20.00", usage.dailyUsage(accountA.getId()));
        assertMoney("2.70", usage.dailyUsage(accountB.getId()));
        assertEquals(rowsOfB, fingerprint(accountB.getId()));
        assertEquals("47.30", lastRawText(accountB.getId()));
    }

    // -------------------------- criterion 4: refreshAll reports per-account outcomes

    @Test
    public void refreshAllReturnsOneOutcomePerEnabledAccountAttributedById() {
        programSuccess(accountA, "100.00");
        programFailure(accountB, UsageError.NETWORK_ERROR, "模拟网络失败");

        List<AccountRefreshManager.RefreshOutcome> outcomes = refreshManager.refreshAll();

        assertEquals("one outcome per enabled account", 2, outcomes.size());

        AccountRefreshManager.RefreshOutcome forA = outcomeFor(outcomes, accountA.getId());
        AccountRefreshManager.RefreshOutcome forB = outcomeFor(outcomes, accountB.getId());

        assertTrue("A must have succeeded independently of B", forA.isSuccess());
        assertNotNull(forA.getResult());
        assertEquals("100.00", rawTextOf(forA.getResult()));

        assertFalse("B must have failed independently of A", forB.isSuccess());
        assertEquals(UsageError.NETWORK_ERROR, forB.getError());

        assertEquals("A's data survived B's failure", "100.00", lastRawText(accountA.getId()));
        assertNull("B has no successful reading to show", lastRawText(accountB.getId()));
        assertEquals("B's failure is recorded as a failed row, not as A's",
                1, usage.history(accountB.getId(), 0L, Long.MAX_VALUE, 0).size());
        assertEquals(1, usage.history(accountA.getId(), 0L, Long.MAX_VALUE, 0).size());
    }

    @Test
    public void refreshAllSkipsDisabledAccountsAndLeavesTheirReadingsAlone() {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");
        assertRefreshed(accountB);

        List<String> rowsOfB = fingerprint(accountB.getId());
        accountManager.setEnabled(accountB.getId(), false);

        // Compared by id, not by contains(accountB): Account has no equals
        // override and setEnabled stores a rebuilt instance, so an identity
        // check would be false whether or not the account were still enabled.
        assertNull("a disabled account must not be listed as enabled",
                findEnabledById(accountManager.listEnabled(), accountB.getId()));
        assertEquals(2, accountManager.list().size());
        assertNotNull("the disabled account is still listed, just not as enabled",
                accountManager.find(accountB.getId()));

        programSuccess(accountA, "90.00");
        List<AccountRefreshManager.RefreshOutcome> outcomes = refreshManager.refreshAll();

        assertEquals("a disabled account must be skipped", 1, outcomes.size());
        assertEquals(accountA.getId(), outcomes.get(0).getAccountId());
        assertTrue(outcomes.get(0).isSuccess());
        assertEquals("90.00", lastRawText(accountA.getId()));

        assertEquals("disabling must not touch the stored rows", rowsOfB, fingerprint(accountB.getId()));
        assertEquals("the last reading must still be readable while disabled",
                "50.00", lastRawText(accountB.getId()));
        assertNotEquals("the disabled account must not have been refreshed to A's value",
                lastRawText(accountA.getId()), lastRawText(accountB.getId()));
    }

    @Test
    public void refreshAllRefreshesEveryEnabledAccountAndNothingElse() throws Exception {
        Account third = createWithKey("账户 C", "sk-isolation-account-c");
        accountManager.setEnabled(third.getId(), false);
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");

        List<AccountRefreshManager.RefreshOutcome> outcomes = refreshManager.refreshAll();

        assertEquals(2, outcomes.size());
        assertEquals(accountManager.listEnabled().size(), outcomes.size());
        for (AccountRefreshManager.RefreshOutcome outcome : outcomes) {
            assertNotNull("every outcome must name its account",
                    accountManager.find(outcome.getAccountId()));
            assertNotEquals("a disabled account must never appear", third.getId(), outcome.getAccountId());
        }
        assertNull(usage.latest(third.getId()));
    }

    private static AccountRefreshManager.RefreshOutcome outcomeFor(
            List<AccountRefreshManager.RefreshOutcome> outcomes, String accountId) {
        for (AccountRefreshManager.RefreshOutcome outcome : outcomes) {
            if (accountId.equals(outcome.getAccountId())) {
                return outcome;
            }
        }
        throw new AssertionError("no outcome was reported for account " + accountId
                + "; reported: " + describe(outcomes));
    }

    private static String describe(List<AccountRefreshManager.RefreshOutcome> outcomes) {
        List<String> described = new ArrayList<>();
        for (AccountRefreshManager.RefreshOutcome outcome : outcomes) {
            described.add(outcome.getAccountId() + "=" + (outcome.isSuccess() ? "ok" : outcome.getError()));
        }
        return described.toString();
    }

    // ------------------------- criterion 5: changing one key changes no identity

    @Test
    public void replacingOneAccountsKeyKeepsItsIdAndCredentialId() throws Exception {
        // Give A a stored reading first: "history not lost" is only meaningful
        // when there is history, and the stub deliberately has no default answer
        // so an unprogrammed refresh fails loudly instead of passing vacuously.
        programSuccess(accountA, "100.00");
        assertRefreshed(accountA);
        List<String> historyBefore = fingerprint(accountA.getId());
        assertFalse("the fixture must have produced a stored row", historyBefore.isEmpty());

        String idBefore = accountA.getId();
        long createdBefore = accountA.getCreatedAt();
        String credentialBefore = accountA.getCredentialId();

        assertNotNull("the fixture must start with a bound credential", credentialBefore);
        assertFalse(credentialBefore.isEmpty());

        accountManager.replaceCredential(idBefore, CredentialPayload.forApiKey(KEY_A_ROTATED));

        Account reloaded = accountManager.find(idBefore);
        assertNotNull(reloaded);
        assertEquals("Spec §14: the account id must survive a key change", idBefore, reloaded.getId());
        assertEquals("Spec §14: the creation time must survive a key change",
                createdBefore, reloaded.getCreatedAt());
        assertEquals("a key change updates the credential row, it never rebinds the account",
                credentialBefore, reloaded.getCredentialId());

        // The id is stable, the secret is not: the new key must actually be in
        // effect, otherwise this test would also pass on a no-op.
        assertEquals(KEY_A_ROTATED,
                accountManager.openCredential(reloaded).get(AuthContext.KEY_API_KEY));
        assertTrue(credentials.isUsable(credentialBefore));

        // Criterion 5 also says history must not be lost by a key change.
        assertEquals("a key change must not rewrite or drop the stored history",
                historyBefore, fingerprint(accountA.getId()));
    }

    @Test
    public void replacingOneAccountsKeyLeavesTheOtherAccountAndItsHistoryUntouched() throws Exception {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");
        assertRefreshed(accountA);
        assertRefreshed(accountB);

        String idOfB = accountB.getId();
        String credentialOfB = accountB.getCredentialId();
        List<String> rowsOfA = fingerprint(accountA.getId());
        List<String> rowsOfB = fingerprint(idOfB);
        String readingOfA = lastRawText(accountA.getId());

        accountManager.replaceCredential(accountA.getId(), CredentialPayload.forApiKey(KEY_A_ROTATED));

        assertEquals("A's history must survive its own key change",
                rowsOfA, fingerprint(accountA.getId()));
        assertEquals(readingOfA, lastRawText(accountA.getId()));
        assertEquals("B's rows must not move when A's key changes", rowsOfB, fingerprint(idOfB));
        assertEquals("B's credential id must not move", credentialOfB,
                accountManager.find(idOfB).getCredentialId());
        assertEquals("B's key must still be B's", KEY_B,
                accountManager.openCredential(accountManager.find(idOfB)).get(AuthContext.KEY_API_KEY));
    }

    @Test
    public void deletingOneAccountRemovesOnlyItsHistory() {
        programSuccess(accountA, "100.00");
        programSuccess(accountB, "50.00");
        assertRefreshed(accountA);
        assertRefreshed(accountB);

        List<String> rowsOfB = fingerprint(accountB.getId());
        BigDecimal dailyOfB = usage.dailyUsage(accountB.getId());

        accountManager.delete(accountA.getId(), usage::deleteForAccount);

        assertNull("the deleted account is gone", accountManager.find(accountA.getId()));
        assertTrue("the deleted account's history is gone",
                usage.history(accountA.getId(), 0L, Long.MAX_VALUE, 0).isEmpty());
        assertNull(usage.latest(accountA.getId()));
        assertMoney("0.00", usage.dailyUsage(accountA.getId()));

        assertNotNull("the sibling account must survive", accountManager.find(accountB.getId()));
        assertEquals("the sibling's rows must be untouched", rowsOfB, fingerprint(accountB.getId()));
        assertMoney(dailyOfB.toPlainString(), usage.dailyUsage(accountB.getId()));
        assertEquals("50.00", lastRawText(accountB.getId()));
        assertEquals("account A must be the only one removed", 1, accountManager.list().size());
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A {@link UsageProvider} with a separate answer per account id, which is
     * what the sibling suites' single-answer stubs cannot express. It also
     * records the API key it was handed, so the test can prove that two accounts
     * on one provider are fetched with their own credentials rather than with a
     * shared or stale one.
     */
    private static final class PerAccountProvider implements UsageProvider {

        private final String id;
        private final Map<String, UsageResult> results = new LinkedHashMap<>();
        private final Map<String, UsageException> failures = new LinkedHashMap<>();
        private final Map<String, RuntimeException> runtimeFailures = new LinkedHashMap<>();
        private final Map<String, String> lastApiKeys = new LinkedHashMap<>();

        private PerAccountProvider(String id) {
            this.id = id;
        }

        /** Clears every programmed answer so the next test starts from a known state. */
        void reset() {
            results.clear();
            failures.clear();
            runtimeFailures.clear();
            lastApiKeys.clear();
        }

        void succeedWith(String accountId, UsageResult result) {
            results.put(accountId, result);
            failures.remove(accountId);
            runtimeFailures.remove(accountId);
        }

        void failWith(String accountId, UsageException exception) {
            results.remove(accountId);
            failures.put(accountId, exception);
            runtimeFailures.remove(accountId);
        }

        void failWithRuntime(String accountId, RuntimeException exception) {
            results.remove(accountId);
            failures.remove(accountId);
            runtimeFailures.put(accountId, exception);
        }

        /** The API key this account was last fetched with, or null when never fetched. */
        String lastApiKeyFor(String accountId) {
            return lastApiKeys.get(accountId);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return "Test Multi-Account Provider";
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
            String accountId = account.getId();
            lastApiKeys.put(accountId,
                    authContext == null ? null : authContext.get(AuthContext.KEY_API_KEY));

            RuntimeException runtimeFailure = runtimeFailures.get(accountId);
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            UsageException failure = failures.get(accountId);
            if (failure != null) {
                throw failure;
            }
            UsageResult result = results.get(accountId);
            if (result == null) {
                throw new UsageException(UsageError.UNKNOWN,
                        "no answer was programmed for account " + accountId);
            }
            return result;
        }
    }

    /**
     * In-memory {@link AccountRepository} mirroring {@code SqliteAccountRepository}:
     * rows are ordered {@code sort_order ASC, created_at ASC}, {@code findEnabled()}
     * selects only enabled rows, {@code save} replaces the row for an id, and
     * {@code reorder} rewrites {@code sort_order} to the given index.
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
     * In-memory {@link CredentialStore} mirroring {@code SqliteCredentialStore}:
     * credentials are separate rows from accounts, an empty credential id is
     * rejected with {@code INVALID_CREDENTIAL} rather than resolved, and
     * {@code delete} drops the row so the account afterwards has none.
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
     * {@code success = 1} and orders by timestamp then id descending,
     * {@code history()} does not filter, and both the snapshot table and the
     * daily-usage row are keyed by {@code account_id} — because that keying is
     * exactly the isolation this class asserts. Snapshots round-trip through the
     * real {@link UsageSnapshotCodec}, as the production column does.
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
