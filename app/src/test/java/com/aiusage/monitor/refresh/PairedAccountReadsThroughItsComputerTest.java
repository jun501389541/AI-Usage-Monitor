package com.aiusage.monitor.refresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.account.AccountRepository;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.bridge.BridgeRepository;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Bridge;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.usage.SnapshotRetention;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.util.StatusWords;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A paired account is read through its computer, not through the address that
 * happens to be stored beside its token. Phase 7 step 9 (plan §3.2's last row, and
 * decisions A5/A9).
 *
 * <p>The row is the only place the address and the digest can both be right: it is
 * what 「the laptop changed networks」 edits, and its digest is what makes the connection
 * mean "this computer" rather than "whoever answered". So the tests below assert that
 * the provider receives the row's values — and, for the row that is gone, that nothing
 * was sent at all, because a stale address plus a stale token is exactly the request a
 * revoked device should not be able to make.
 */
public class PairedAccountReadsThroughItsComputerTest {

    private static final String PROVIDER_ID = "test-paired-reading";
    private static final String DIGEST =
            "94cfeca524d68047296c70d220f62aa205184ff6beac1e8936e30caaab8b1be0";
    private static final String STALE_URL = "https://10.0.2.2:38411";
    private static final String CURRENT_URL = "https://192.168.1.42:38411";

    private InMemoryBridgeRepository bridges;
    private AccountManager accountManager;
    private RecordingProvider provider;
    private AccountRefreshManager refreshManager;
    private UsageRepository usage;

    /**
     * One provider instance for the class. The registry is a process-wide singleton
     * and rejects a second instance under the same id, so a fresh one per method would
     * leave the refresh path calling the instance the *first* method registered —
     * which is how a green "success" with a null recorded context happens.
     */
    private static RecordingProvider sharedProvider;

    @Before
    public void setUp() {
        bridges = new InMemoryBridgeRepository();
        accountManager = new AccountManager(new InMemoryAccountRepository(),
                new InMemoryCredentialStore());
        usage = new RecordingUsageRepository();
        if (sharedProvider == null) {
            sharedProvider = new RecordingProvider();
            ProviderRegistry.get().register(sharedProvider);
        }
        provider = sharedProvider;
        provider.reset();
        ProviderRegistry registry = ProviderRegistry.get();
        refreshManager = new AccountRefreshManager(accountManager, usage, registry, bridges);
    }

    private Account linkedAccount(String bridgeId, String urlInCredential) throws Exception {
        Account account = accountManager.createAccount(PROVIDER_ID, "Codex", AuthType.BRIDGE_TOKEN);
        accountManager.replaceCredential(account.getId(),
                CredentialPayload.forBridge(urlInCredential, "dt-a-token-value"));
        if (bridgeId != null) {
            accountManager.attachBridge(account.getId(), bridgeId);
        }
        return accountManager.find(account.getId());
    }

    private void pairTheComputer(String id, String url, String digest) {
        bridges.save(Bridge.builder().id(id).name(id).baseUrl(url).fingerprint(digest)
                .addedAt(1_700_000_000_000L).build());
    }

    @Test
    public void theProviderSeesTheComputersAddressAndDigestNotTheOnesInTheSecret()
            throws Exception {
        pairTheComputer("br_alpha", CURRENT_URL, DIGEST);
        Account account = linkedAccount("br_alpha", STALE_URL);

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue(outcome.getMessage(), outcome.isSuccess());
        AuthContext seen = provider.lastContext;
        assertEquals("the row wins: that is the field the device screen edits when the "
                        + "laptop moves networks",
                CURRENT_URL, seen.get(AuthContext.KEY_BRIDGE_URL));
        assertEquals(DIGEST, seen.get(AuthContext.KEY_BRIDGE_PIN));
        assertEquals("dt-a-token-value", seen.get(AuthContext.KEY_DEVICE_TOKEN));
    }

    @Test
    public void aComputerThatWasDeletedSendsNothingAndSaysItNeedsPairing() throws Exception {
        pairTheComputer("br_alpha", CURRENT_URL, DIGEST);
        Account account = linkedAccount("br_alpha", STALE_URL);
        bridges.delete("br_alpha");
        provider.fetchCount = 0;

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertFalse(outcome.isSuccess());
        assertEquals(UsageError.BRIDGE_PAIRING_REQUIRED, outcome.getError());
        assertEquals("no address is even known, so no request may leave the phone",
                0, provider.fetchCount);
    }

    @Test
    public void aReadingThatWorkedStampsWithWhenTheComputerLastAnswered() throws Exception {
        pairTheComputer("br_alpha", CURRENT_URL, DIGEST);
        Account account = linkedAccount("br_alpha", CURRENT_URL);

        assertTrue(refreshManager.refresh(account).isSuccess());

        assertEquals(1, bridges.touched.size());
        assertEquals("br_alpha", bridges.touched.get(0));
        assertTrue("a stamp of zero would render as 「从未联系」 next to a reading that just "
                        + "succeeded",
                bridges.touchedAt.get(0) > 0L);
    }

    @Test
    public void aHandTypedAccountIsReadExactlyAsItWasBeforePairingExisted() throws Exception {
        Account account = linkedAccount(null, STALE_URL);

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertEquals(STALE_URL, provider.lastContext.get(AuthContext.KEY_BRIDGE_URL));
        assertEquals("no digest means the plaintext debug path, unchanged",
                "", provider.lastContext.get(AuthContext.KEY_BRIDGE_PIN));
        assertTrue(bridges.touched.isEmpty());
    }

    @Test
    public void anAccountPointingAtAComputerWithoutStorageIsNotSilentlyDialled() throws Exception {
        // The state a half-wired build would produce: rows exist in the account table
        // and nothing can resolve them. Reading through the older address in the
        // credential would be the silent wrong answer.
        AccountRefreshManager unpowered = new AccountRefreshManager(accountManager, usage,
                ProviderRegistry.get());
        Account account = linkedAccount("br_alpha", STALE_URL);
        provider.fetchCount = 0;

        AccountRefreshManager.RefreshOutcome outcome = unpowered.refresh(account);

        assertFalse(outcome.isSuccess());
        assertEquals(UsageError.BRIDGE_PAIRING_REQUIRED, outcome.getError());
        assertEquals(0, provider.fetchCount);
    }

    /**
     * Spec §53 rule 19, extended: the three Bridge failures have to read as three
     * different instructions, and the generic mapping must not swallow the newest one
     * into "network error".
     */
    @Test
    public void theThreeBridgeFailuresStayThreeDifferentSentences() {
        UsageStatus offline = AccountRefreshManager.statusFor(UsageError.BRIDGE_OFFLINE);
        UsageStatus unauthorized = AccountRefreshManager.statusFor(UsageError.BRIDGE_UNAUTHORIZED);
        UsageStatus unpaired = AccountRefreshManager.statusFor(UsageError.BRIDGE_PAIRING_REQUIRED);

        assertNotEquals(offline, unauthorized);
        assertNotEquals(offline, unpaired);
        assertNotEquals(unauthorized, unpaired);

        String offlineWords = StatusWords.describe(offline);
        String revokedWords = StatusWords.describe(unauthorized);
        String unpairedWords = StatusWords.describe(unpaired);
        assertNotEquals(offlineWords, revokedWords);
        assertNotEquals(revokedWords, unpairedWords);
        assertTrue("the pairing state must name pairing: " + unpairedWords,
                unpairedWords.contains("配对"));
        assertFalse("and must not tell the user to redo a pairing they never made: "
                        + unpairedWords,
                unpairedWords.contains("重新"));
        assertNotNull(StatusWords.describe(UsageStatus.OK));
    }

    // ------------------------------------------------------------------ fakes

    private static final class RecordingProvider implements UsageProvider {

        private AuthContext lastContext;
        private int fetchCount;

        void reset() {
            lastContext = null;
            fetchCount = 0;
        }

        @Override
        public String getId() {
            return PROVIDER_ID;
        }

        @Override
        public String getName() {
            return "Paired Reading Test Provider";
        }

        @Override
        public List<AuthType> getSupportedAuthTypes() {
            return Arrays.asList(AuthType.BRIDGE_TOKEN);
        }

        @Override
        public ProviderCapabilities getCapabilities() {
            return ProviderCapabilities.builder().supports(AuthType.BRIDGE_TOKEN)
                    .reportsBalance(true).build();
        }

        @Override
        public UsageResult fetchUsage(Account account, AuthContext authContext) {
            fetchCount++;
            lastContext = authContext;
            return UsageResult.builder()
                    .status(UsageStatus.OK)
                    .balance(new Balance(1.0d, "CNY", "1.00"))
                    .updatedAt(1_700_000_000_000L)
                    .build();
        }
    }

    private static final class InMemoryBridgeRepository implements BridgeRepository {
        private final Map<String, Bridge> byId = new LinkedHashMap<>();
        private final List<String> touched = new ArrayList<>();
        private final List<Long> touchedAt = new ArrayList<>();

        @Override
        public List<Bridge> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public Bridge findById(String id) {
            return id == null ? null : byId.get(id);
        }

        @Override
        public void save(Bridge bridge) {
            byId.put(bridge.getId(), bridge);
        }

        @Override
        public void updateBaseUrl(String id, String baseUrl) {
            Bridge bridge = byId.get(id);
            if (bridge != null) {
                byId.put(id, bridge.withBaseUrl(baseUrl));
            }
        }

        @Override
        public void touchLastSeen(String id, long atMs) {
            touched.add(id);
            touchedAt.add(atMs);
        }

        @Override
        public void delete(String id) {
            byId.remove(id);
        }
    }

    private static final class InMemoryAccountRepository implements AccountRepository {
        private final Map<String, Account> byId = new LinkedHashMap<>();

        @Override
        public List<Account> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public Account findById(String id) {
            return id == null ? null : byId.get(id);
        }

        @Override
        public List<Account> findEnabled() {
            return findAll();
        }

        @Override
        public void save(Account account) {
            byId.put(account.getId(), account);
        }

        @Override
        public void delete(String id) {
            byId.remove(id);
        }

        @Override
        public void reorder(List<String> orderedIds) {
        }

        @Override
        public int count() {
            return byId.size();
        }
    }

    /** Keeps payloads as plaintext; only the account layer's plumbing is under test. */
    private static final class InMemoryCredentialStore implements CredentialStore {
        private final Map<String, String> payloads = new LinkedHashMap<>();
        private int sequence;

        @Override
        public String create(AuthType type, String payload) {
            String id = "cred_read" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) {
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            String payload = payloads.get(credentialId);
            if (payload == null) {
                throw new com.aiusage.monitor.auth.AuthException(
                        UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put(AuthContext.KEY_DEVICE_TOKEN,
                    CredentialPayload.extractOptionalDeviceToken(payload));
            values.put(AuthContext.KEY_BRIDGE_URL, CredentialPayload.extractBridgeUrl(payload));
            return AuthContext.of(type, values);
        }

        @Override
        public void delete(String credentialId) {
            payloads.remove(credentialId);
        }

        @Override
        public boolean isUsable(String credentialId) {
            return payloads.containsKey(credentialId);
        }
    }

    private static final class RecordingUsageRepository implements UsageRepository {
        @Override
        public void save(String accountId, UsageResult result, boolean success) {
        }

        @Override
        public UsageResult latest(String accountId) {
            return null;
        }

        @Override
        public UsageResult latestAttempt(String accountId) {
            return null;
        }

        @Override
        public List<UsageSnapshot> history(String accountId, long fromInclusive, long toExclusive,
                                           int limit) {
            return new ArrayList<>();
        }

        @Override
        public int prune(SnapshotRetention retention, long nowMs) {
            return 0;
        }

        @Override
        public void deleteForAccount(String accountId) {
        }

        @Override
        public BigDecimal recordDailyUsage(String accountId, String rawBalance) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal dailyUsage(String accountId) {
            return BigDecimal.ZERO;
        }
    }
}
