package com.aiusage.monitor.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An account's {@code bridge_id} is a pointer, and pointing it somewhere must not
 * disturb anything else about the account. Phase 7 step 5.
 *
 * <p>The property worth testing is the one the spec keeps insisting on for keys
 * (§14: change the key, keep the account) applied to the new column: attaching,
 * re-attaching and detaching all leave the id, the history key and the credential
 * generation alone. The generation in particular must not move — it answers "is the
 * secret I opened still the secret stored?", and a bookkeeping edit that advanced it
 * would discard a good in-flight reading for no reason.
 */
public class BridgeAttachmentTest {

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private AccountManager manager;

    @Before
    public void setUp() {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        manager = new AccountManager(accounts, credentials);
    }

    private Account newCodexAccount(String name) throws AuthException {
        Account account = manager.createAccount("codex", name, AuthType.BRIDGE_TOKEN);
        manager.replaceCredential(account.getId(),
                CredentialPayload.forBridge("http://10.0.2.2:38411", "tok-" + name));
        return manager.find(account.getId());
    }

    @Test
    public void attachingLeavesTheAccountIdentityAlone() throws AuthException {
        Account account = newCodexAccount("Pixel");
        long generation = manager.credentialGeneration(account.getId());

        manager.attachBridge(account.getId(), "br_v7vv8u3i2tl0tjtq8j70");

        Account attached = manager.find(account.getId());
        assertEquals("the id is what history and widget bindings refer to",
                account.getId(), attached.getId());
        assertEquals("attaching a Bridge is not a credential change",
                generation, manager.credentialGeneration(account.getId()));
        assertEquals("br_v7vv8u3i2tl0tjtq8j70", attached.getBridgeId());
        assertEquals("the credential row is untouched, so a refresh in flight "
                        + "still describes this account",
                account.getCredentialId(), attached.getCredentialId());
        attached.getCredentialId();
    }

    @Test
    public void reAttachingAndDetachingBothKeepTheAccount() throws AuthException {
        Account account = newCodexAccount("Pixel");
        long generation = manager.credentialGeneration(account.getId());

        manager.attachBridge(account.getId(), "br_first");
        manager.attachBridge(account.getId(), "br_second");
        assertEquals("br_second", manager.bridgeIdOf(account.getId()));

        manager.attachBridge(account.getId(), "");
        assertEquals("detaching returns the account to the hand-typed path",
                "", manager.bridgeIdOf(account.getId()));
        assertEquals(generation, manager.credentialGeneration(account.getId()));
        assertTrue("and the row is still there", manager.find(account.getId()) != null);
    }

    @Test
    public void changingTheBridgeFallbackForAnOptedInDirectAccountInvalidatesItsRefresh()
            throws AuthException {
        Account account = newCodexAccount("Pixel");
        manager.saveDirectOAuthCredential(account.getId(), "oauth-payload", "profile-hash");
        long generation = manager.credentialGeneration(account.getId());

        manager.attachBridge(account.getId(), "br_fallback");

        assertNotEquals("a Direct account's fallback route is part of its refresh state",
                generation, manager.credentialGeneration(account.getId()));
    }

    /**
     * Every account created before this column meant anything reads as unattached,
     * and readers must treat that as "ask the credential payload", not as an error.
     */
    @Test
    public void anAccountThatNeverHadABridgeReadsAsEmptyRatherThanMissing() throws AuthException {
        Account deepSeek = manager.createAccount("deepseek", "Legacy", AuthType.API_KEY);

        assertEquals("", deepSeek.getBridgeId());
        assertEquals("", manager.bridgeIdOf(deepSeek.getId()));
        assertEquals("", manager.bridgeIdOf("acct_never_existed"));
    }

    /**
     * The control: a real credential change does move the generation, so the
     * assertions above are not passing because nothing ever changes it.
     */
    @Test
    public void aKeyChangeStillMovesTheGenerationThatAttachingDoesNot() throws AuthException {
        Account account = newCodexAccount("Pixel");
        manager.attachBridge(account.getId(), "br_first");
        long afterAttach = manager.credentialGeneration(account.getId());

        manager.replaceCredential(account.getId(),
                CredentialPayload.forBridge("https://192.168.1.20:38411", "tok-new"));

        assertNotEquals("a new secret is a new epoch", afterAttach,
                manager.credentialGeneration(account.getId()));
        assertEquals("and the pointer survives it", "br_first",
                manager.bridgeIdOf(account.getId()));
    }

    @Test
    public void attachingToAnUnknownAccountFailsRatherThanCreatingOne() {
        try {
            manager.attachBridge("acct_ghost", "br_x");
            fail("attaching a Bridge to an account that does not exist must say so");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("acct_ghost"));
        }
    }

    // ------------------------------------------------------------- fakes

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
            byId.put(account.getId(), account);
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

    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, String> payloads = new LinkedHashMap<>();
        private int sequence;

        @Override
        public String create(AuthType type, String payload) {
            String id = "cred_" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
            if (credentialId == null || !payloads.containsKey(credentialId)) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            String payload = credentialId == null ? null : payloads.get(credentialId);
            if (payload == null) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            return AuthContext.ofApiKey(payload);
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
}
