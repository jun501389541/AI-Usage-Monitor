package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.account.AccountRepository;
import com.aiusage.monitor.auth.ApiKeyAuthAdapter;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.BridgeAuthAdapter;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Bridge;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a finished pairing leaves in the databases, and what it refuses to leave.
 * Phase 7 step 9.
 *
 * <p>The pair of outcomes that matter are both silent if the order of writes is
 * wrong: an account whose {@code bridge_id} points at nothing reads as
 * 「需要重新配对」 forever, and a Bridge row written before the fingerprint was checked
 * re-pins a computer the user never agreed to trust again. So the cases below assert
 * on storage state — the row, the account, the decrypted credential — rather than on
 * a returned object, and the two rollback cases pin that a row this call created is
 * the only row it may delete.
 */
public class PairingStoreTest {

    private static final String PROVIDER_ID = "codex";
    private static final long PAIRED_AT = 1_700_000_000_000L;

    private InMemoryBridgeRepository bridges;
    private InMemoryCredentialStore credentials;
    private InMemoryAccountRepository accounts;
    private AccountManager accountManager;
    private PairingStore store;

    @Before
    public void setUp() {
        bridges = new InMemoryBridgeRepository();
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        accountManager = new AccountManager(accounts, credentials);
        store = new PairingStore(bridges, accountManager);
    }

    private static PairingClient.Paired paired(String bridgeId, String fingerprint, String baseUrl,
                                               String token, long atMs) {
        Bridge bridge = Bridge.builder()
                .id(bridgeId)
                .name(baseUrl)
                .baseUrl(baseUrl)
                .fingerprint(fingerprint)
                .addedAt(atMs)
                .lastSeen(atMs)
                .build();
        return new PairingClient.Paired(bridge, "dev_1", token);
    }

    private static final String ALPHA = "94cfeca524d68047296c70d220f62aa205184ff6beac1e8936e30caaab8b1be0";
    private static final String BETA = "bffd5b6c2d90580e604361f10ba5b8dc8a948fc4d74adf8282e097a332c601e3";

    // ------------------------------------------------------------- the happy path

    @Test
    public void aPairedComputerBecomesABridgeRowAndABridgeTokenAccount() throws Exception {
        PairingClient.Paired result = paired("br_alphaalpha", ALPHA,
                "https://192.168.1.20:38411", "dt-secret-token-value", PAIRED_AT);

        PairingStore.Stored stored = store.record(result, PROVIDER_ID, "我的电脑");

        assertEquals("https://192.168.1.20:38411", stored.bridge().getBaseUrl());
        Bridge row = bridges.findById("br_alphaalpha");
        assertNotNull("the computer has to be findable by its own id", row);
        assertEquals(ALPHA, row.getFingerprint());

        Account account = accountManager.find(stored.account().getId());
        assertEquals(PROVIDER_ID, account.getProviderId());
        assertEquals("我的电脑", account.getDisplayName());
        assertEquals(AuthType.BRIDGE_TOKEN, account.getAuthType());
        assertEquals("the account points at the row rather than repeating an address as identity",
                "br_alphaalpha", account.getBridgeId());

        AuthContext opened = accountManager.openCredential(account);
        assertEquals("dt-secret-token-value", opened.get(AuthContext.KEY_DEVICE_TOKEN));
        assertFalse("paired credentials must not duplicate the bridge endpoint",
                opened.has(AuthContext.KEY_BRIDGE_URL));
        assertEquals("https://192.168.1.20:38411", row.getBaseUrl());
    }

    @Test
    public void rePairingKeepsWhenTheComputerWasFirstAdded() throws Exception {
        store.record(paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one",
                PAIRED_AT), PROVIDER_ID, "电脑");

        PairingClient.Paired again = paired("br_alphaalpha", ALPHA, "https://192.168.1.9:38411",
                "dt-two", PAIRED_AT + 86_400_000L);
        PairingStore.Stored stored = store.record(again, PROVIDER_ID, "电脑二");

        assertEquals("a fresh token is not a new computer, so the pairing date stands",
                PAIRED_AT, stored.bridge().getAddedAt());
        assertEquals("the address is the one that answered this time",
                "https://192.168.1.9:38411", bridges.findById("br_alphaalpha").getBaseUrl());
    }

    // ------------------------------------------------------------- the refusals

    @Test
    public void theSameIdWithADifferentFingerprintIsRefusedBeforeAnythingIsWritten()
            throws Exception {
        store.record(paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one", PAIRED_AT),
                PROVIDER_ID, "电脑");
        int accountsBefore = accounts.count();

        try {
            store.record(paired("br_alphaalpha", BETA, "https://10.0.2.2:38411", "dt-two",
                    PAIRED_AT + 1000L), PROVIDER_ID, "另一台");
            fail("a second key under one Bridge ID was pinned");
        } catch (PairingStore.NotStored expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("指纹"));
        }

        assertEquals("the recorded digest did not move", ALPHA,
                bridges.findById("br_alphaalpha").getFingerprint());
        assertEquals("and no account appeared for the pairing that was refused",
                accountsBefore, accounts.count());
    }

    @Test
    public void aPairingWhoseSecretCannotBeStoredLeavesNeitherRowNorAccount() throws Exception {
        credentials.failOnNextWrite();

        try {
            store.record(paired("br_brandnew", ALPHA, "https://10.0.2.2:38411", "dt-one",
                    PAIRED_AT), PROVIDER_ID, "电脑");
            fail("the pairing was recorded anyway");
        } catch (IllegalStateException expected) {
            // The keystore's own complaint; the state below is the assertion.
        }

        assertNull("a row nobody points at would sit on the device screen forever",
                bridges.findById("br_brandnew"));
        assertEquals("and the account made on the way must not be left without a secret",
                0, accounts.count());
    }

    @Test
    public void aFailureAtAccountCreationWritesNothingAnywhereElse() throws Exception {
        accounts.failOnNextSave();

        try {
            store.record(paired("br_brandnew", ALPHA, "https://10.0.2.2:38411", "dt-one",
                    PAIRED_AT), PROVIDER_ID, "电脑");
            fail("the account was created anyway");
        } catch (IllegalStateException expected) {
            // The repository's own complaint.
        }

        assertEquals(0, accounts.count());
        assertNull(bridges.findById("br_brandnew"));
    }

    @Test
    public void aRowThatAlreadyExistedSurvivesAPairingThatCouldNotFinish() throws Exception {        store.record(paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one", PAIRED_AT),
                PROVIDER_ID, "电脑");
        credentials.failOnNextWrite();

        try {
            store.record(paired("br_alphaalpha", ALPHA, "https://192.168.1.9:38411", "dt-two",
                    PAIRED_AT + 1000L), PROVIDER_ID, "电脑二");
            fail("the second account was created anyway");
        } catch (IllegalStateException expected) {
            // The first pairing's account still reads through this row, so its address
            // and its digest have to be exactly what they were.
        }

        Bridge row = bridges.findById("br_alphaalpha");
        assertNotNull(row);
        assertEquals("https://10.0.2.2:38411", row.getBaseUrl());
        assertEquals(ALPHA, row.getFingerprint());
        assertEquals("one account rolled back, the other untouched", 1, accounts.count());
    }

    /**
     * The write the pairing cannot see coming: the bridges row. It used to happen
     * <em>after</em> the rollback region, so a locked table left an account holding a
     * fresh device token and pointing at nothing - the state this class's own contract
     * says must not exist, and which reads as 「还没有与这台电脑配对」 forever while a valid
     * secret sits in the keystore. Found by review, 2026-10-03.
     */
    @Test
    public void aBridgeRowThatCouldNotBeSavedLeavesNoAccountBehind() throws Exception {
        bridges.failOnNextSave();

        try {
            store.record(paired("br_brandnew", ALPHA, "https://10.0.2.2:38411", "dt-one",
                    PAIRED_AT), PROVIDER_ID, "电脑");
            fail("the pairing reported success although the row was never written");
        } catch (PairingStore.NotStored expected) {
            // Named in the message, so a reader learns which half failed.
            assertTrue(expected.getMessage(), expected.getMessage().contains("电脑"));
        } catch (IllegalStateException expected) {
            // The repository's own complaint; the state below is the assertion.
        }

        assertNull("no row was written", bridges.findById("br_brandnew"));
        assertEquals("and the account made on the way did not stay behind pointing at it",
                0, accounts.count());
    }

    /**
     * Re-pairing rewrites the row and then the credential. If the row is refused, the
     * account must keep both its old row and its old secret - not a new secret attached
     * to an old address, which is what the previous ordering could leave if it threw.
     */
    @Test
    public void aRebindWhoseRowCouldNotBeSavedTouchesNothing() throws Exception {
        PairingStore.Stored first = store.record(
                paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one", PAIRED_AT),
                PROVIDER_ID, "电脑");
        String tokenBefore = accountManager.openCredential(first.account())
                .get(AuthContext.KEY_DEVICE_TOKEN);

        bridges.failOnNextSave();
        try {
            store.rebind(paired("br_alphaalpha", ALPHA, "https://192.168.1.30:38411", "dt-two",
                    PAIRED_AT + 4000L), first.account().getId());
            fail("the rebind reported success although the row was never written");
        } catch (PairingStore.NotStored expected) {
            // The row keeps the address it had.
        } catch (IllegalStateException expected) {
            // The repository's own complaint.
        }

        Bridge row = bridges.findById("br_alphaalpha");
        assertNotNull(row);
        assertEquals("https://10.0.2.2:38411", row.getBaseUrl());
        assertEquals("the old secret is still the one on file", tokenBefore,
                accountManager.openCredential(first.account()).get(AuthContext.KEY_DEVICE_TOKEN));
    }

    // ------------------------------------------------------------------- rebinding

    @Test
    public void rebindingKeepsTheAccountItsIdAndItsHistory() throws Exception {
        PairingStore.Stored first = store.record(
                paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one", PAIRED_AT),
                PROVIDER_ID, "电脑");
        String accountId = first.account().getId();

        PairingStore.Stored rebound = store.rebind(
                paired("br_alphaalpha", ALPHA, "https://192.168.1.30:38411", "dt-new",
                        PAIRED_AT + 5000L), accountId);

        assertEquals(accountId, rebound.account().getId());
        assertEquals("电脑", rebound.account().getDisplayName());
        assertEquals("dt-new", accountManager.openCredential(rebound.account())
                .get(AuthContext.KEY_DEVICE_TOKEN));
        assertFalse("rebinding keeps the endpoint exclusively in the bridge row",
                accountManager.openCredential(rebound.account()).has(AuthContext.KEY_BRIDGE_URL));
        assertEquals("https://192.168.1.30:38411", bridges.findById("br_alphaalpha").getBaseUrl());
        assertEquals(1, bridges.count());
    }

    @Test
    public void rebindingAnAccountThatIsGoneSaysSo() throws Exception {
        try {
            store.rebind(paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one",
                    PAIRED_AT), "acct_missing");
            fail("a pairing was recorded onto an account that no longer exists");
        } catch (PairingStore.NotStored expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不在了"));
        }
        assertFalse(bridges.byId.containsKey("br_alphaalpha"));
    }

    /**
     * The rollback rule on the rebind path, which is the mirror of the record path's
     * and fails in the opposite direction: a row this call recreated must not exist
     * when the credential could not be written, and a row that predates this call must
     * not move — another account is still reading through it.
     */
    @Test
    public void aRebindThatCannotWriteItsCredentialWritesNothingAtAll() throws Exception {
        PairingStore.Stored first = store.record(
                paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one", PAIRED_AT),
                PROVIDER_ID, "电脑");
        // What the device screen's delete does to a live account: the row goes, the
        // account stays, and every later read says "需要重新配对".
        bridges.delete("br_alphaalpha");
        credentials.failOnNextWrite();

        try {
            store.rebind(paired("br_alphaalpha", ALPHA, "https://192.168.1.30:38411", "dt-two",
                    PAIRED_AT + 5000L), first.account().getId());
            fail("the rebind completed without a credential");
        } catch (IllegalStateException expected) {
            // The keystore's own complaint.
        }

        assertNull("no row may exist for an account that holds no token for it",
                bridges.findById("br_alphaalpha"));
        assertNotNull("and the account is still there to retry from",
                accountManager.find(first.account().getId()));
    }

    @Test
    public void aRebindThatCannotWriteItsCredentialLeavesAPreexistingRowAlone() throws Exception {
        store.record(paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411", "dt-one", PAIRED_AT),
                PROVIDER_ID, "共用一台电脑的账户");
        PairingClient.Paired second = paired("br_alphaalpha", ALPHA, "https://10.0.2.2:38411",
                "dt-two", PAIRED_AT);
        String sharedAccount = store.record(second, PROVIDER_ID, "第二个账户").account().getId();
        credentials.failOnNextWrite();

        try {
            store.rebind(paired("br_alphaalpha", ALPHA, "https://192.168.1.30:38411", "dt-three",
                    PAIRED_AT + 5000L), sharedAccount);
            fail("the rebind completed without a credential");
        } catch (IllegalStateException expected) {
            // The store's own complaint.
        }

        Bridge row = bridges.findById("br_alphaalpha");
        assertNotNull("the other account still reads through this row", row);
        assertEquals("and it kept the address it was last working at",
                "https://10.0.2.2:38411", row.getBaseUrl());
    }

    // ----------------------------------------------------------------------- fakes

    private static final class InMemoryBridgeRepository implements BridgeRepository {
        private final Map<String, Bridge> byId = new LinkedHashMap<>();
        private boolean failNextSave;

        /** The one write a pairing cannot see: the phone's own row for the computer. */
        void failOnNextSave() {
            failNextSave = true;
        }

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
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("the bridges table is locked");
            }
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
            Bridge bridge = byId.get(id);
            if (bridge != null) {
                byId.put(id, bridge.withLastSeen(atMs));
            }
        }

        @Override
        public void delete(String id) {
            byId.remove(id);
        }

        int count() {
            return byId.size();
        }
    }

    /** An account repository that can be told to fail once, to test the rollback. */
    private static final class InMemoryAccountRepository implements AccountRepository {
        private final Map<String, Account> byId = new LinkedHashMap<>();
        private final List<String> saved = new ArrayList<>();
        private boolean failNextSave;

        void failOnNextSave() {
            failNextSave = true;
        }

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
            List<Account> enabled = new ArrayList<>();
            for (Account account : findAll()) {
                if (account.isEnabled()) {
                    enabled.add(account);
                }
            }
            return enabled;
        }

        @Override
        public void save(Account account) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("the account store is full");
            }
            byId.put(account.getId(), account);
            saved.add(account.getId());
        }

        @Override
        public void delete(String id) {
            byId.remove(id);
        }

        @Override
        public void reorder(List<String> orderedIds) {
            int order = 0;
            for (String id : orderedIds) {
                Account account = byId.get(id);
                if (account != null) {
                    byId.put(id, account.toBuilder().sortOrder(order++).build());
                }
            }
        }

        @Override
        public int count() {
            return byId.size();
        }
    }

    /** Opens a BRIDGE_TOKEN payload through the real adapter, so the wiring is tested. */
    private static final class InMemoryCredentialStore implements CredentialStore {
        private final Map<String, String> payloads = new LinkedHashMap<>();
        private int sequence;
        private boolean failNextWrite;

        void failOnNextWrite() {
            failNextWrite = true;
        }

        private void failIfAsked() {
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException("the keystore is busy");
            }
        }

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            failIfAsked();
            String id = "cred_pair" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
            failIfAsked();
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            if (credentialId == null || !payloads.containsKey(credentialId)) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            String payload = payloads.get(credentialId);
            return type == AuthType.BRIDGE_TOKEN
                    ? new BridgeAuthAdapter().adapt(payload)
                    : new ApiKeyAuthAdapter().adapt(payload);
        }

        @Override
        public void delete(String credentialId) {
            payloads.remove(credentialId);
        }

        @Override
        public boolean isUsable(String credentialId) {
            return credentialId != null && payloads.containsKey(credentialId);
        }
    }
}
