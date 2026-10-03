package com.aiusage.monitor.provider.deepseek;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.provider.UsageProvider;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proves the acceptance criterion in Spec §14: <em>adding a third DeepSeek
 * account requires no change to {@code DeepSeekProvider}</em>.
 *
 * <p>The claim has two halves, and neither alone is enough.
 *
 * <p>The behavioural half shows that the registry resolves the provider by
 * provider id, so N accounts share one provider instance and adding an account
 * registers nothing new.
 *
 * <p>The structural half is the one that keeps that true over time. A provider
 * that branched on an account id, on a display name or on a particular key
 * would still pass the behavioural checks — the branch would simply take one
 * path — while quietly making every new account a code change. So the source is
 * read and asserted to contain no account-specific branching at all, and to
 * depend on nothing from the account or credential storage layer: the provider
 * sees an {@link Account} and an {@link AuthContext}, and that is the whole of
 * its world. Its SHA-256 is printed so a run leaves behind evidence of exactly
 * which revision was checked.
 *
 * <p>Reading the source from a test is unusual, and it is deliberate here: this
 * is an architectural constraint, not a behaviour, and a constraint that is not
 * asserted is a constraint that decays.
 */
public class DeepSeekProviderStabilityTest {

    private static final String SOURCE_RELATIVE_PATH =
            "src/main/java/com/aiusage/monitor/provider/deepseek/DeepSeekProvider.java";

    private static final String[] FIRST_NAMES = {"DeepSeek个人", "DeepSeek工作", "DeepSeek测试"};

    private static String sourceText;

    @Before
    public void loadSourceOnce() throws IOException {
        if (sourceText == null) {
            sourceText = readProviderSource();
        }
    }

    // =====================================================================
    // Structural half — the provider source carries no account-specific code
    // =====================================================================

    @Test
    public void providerSourceFileIsFoundFromTheWorkingDirectory() throws IOException {
        File source = locateProviderSource();
        assertTrue("the provider source must exist on disk: " + source.getAbsolutePath(),
                source.isFile());
        assertTrue("a provider source of " + source.length() + " bytes is too small to be real",
                source.length() > 1000L);
    }

    @Test
    public void providerSourceDeclaresTheSharedDeepSeekId() {
        assertTrue("the provider must declare its shared id constant",
                sourceText.contains("public static final String ID = \"" + DeepSeekProvider.ID + "\";"));
    }

    @Test
    public void providerSourcePrintsItsHashAsEvidence() throws IOException {
        String hash = sha256(readProviderSourceBytes());
        System.out.println("[DeepSeekProviderStabilityTest] " + locateProviderSource().getAbsolutePath());
        System.out.println("[DeepSeekProviderStabilityTest] SHA-256 = " + hash);
        assertEquals("a SHA-256 hex digest must be 64 characters", 64, hash.length());
    }

    @Test
    public void providerSourceContainsNoAccountIdLiteral() {
        // The id prefix AccountManager.newId() generates. A provider that knew it
        // would be able to special-case one account.
        assertFalse("the provider must not contain the account id prefix \"acct_\"",
                sourceText.contains("acct_"));
    }

    @Test
    public void providerSourceContainsNoApiKeyLiteral() {
        // A key literal would mean a key is compiled into the provider, which is
        // the most direct form of per-account special-casing there is.
        assertFalse("the provider must not contain an API key literal (\"sk-\")",
                sourceText.contains("sk-"));
    }

    @Test
    public void providerSourceNeverMentionsADisplayName() {
        assertFalse("the provider must not branch on a display name: no \"displayName\" may appear",
                sourceText.contains("displayName"));
    }

    @Test
    public void providerSourceDoesNotDependOnTheCredentialStore() {
        assertFalse("the provider must not reference CredentialStore",
                sourceText.contains("CredentialStore"));
        assertFalse("the provider must not reference the SQLite credential store",
                sourceText.contains("SqliteCredentialStore"));
    }

    @Test
    public void providerSourceDoesNotDependOnTheAccountManager() {
        assertFalse("the provider must not reference AccountManager",
                sourceText.contains("AccountManager"));
    }

    @Test
    public void providerSourceDoesNotReadTheCredentialId() {
        assertFalse("the provider must not read an account's credential id: the AuthContext already carries the secret",
                sourceText.contains("getCredentialId"));
    }

    @Test
    public void providerSourceDoesNotReachIntoStorageOrPreferences() {
        String code = stripComments(sourceText);
        assertFalse("the provider must not touch the storage package",
                code.contains("com.aiusage.monitor.storage"));
        assertFalse("the provider must not touch SharedPreferences",
                code.contains("SharedPreferences"));
    }

    @Test
    public void providerSourceOnlyEverUsesTheAccountForItsId() {
        // The single legitimate use of the Account is the identity stamp the
        // result carries. Anything else — a name, an enabled flag, a sort order
        // — would be the beginning of account-specific behaviour.
        String code = stripComments(sourceText);
        Pattern dereference = Pattern.compile("account\\.([A-Za-z_][A-Za-z0-9_]*)");
        Matcher matcher = dereference.matcher(code);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add("account." + matcher.group(1) + "()");
        }
        assertFalse("the provider must still read the account id to stamp its result", found.isEmpty());
        for (String call : found) {
            assertEquals("the provider must use the Account only for its id, but calls " + call,
                    "account.getId()", call);
        }
    }

    @Test
    public void providerSourceAcceptsOnlyAnAccountAndAnAuthContext() {
        String code = stripComments(sourceText);
        assertTrue("fetchUsage must be declared as (Account, AuthContext) — no storage type may appear",
                code.contains("fetchUsage(Account account, AuthContext authContext)"));
    }

    // =====================================================================
    // Behavioural half — many accounts, one provider instance
    // =====================================================================

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private AccountManager accountManager;

    private void setUpAccountManager() {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        accountManager = new AccountManager(accounts, credentials);
    }

    private Account createDeepSeekAccount(String displayName) throws AuthException {
        return accountManager.create(DeepSeekProvider.ID, displayName, AuthType.API_KEY,
                CredentialPayload.forApiKey("sk-stability-" + displayName));
    }

    /** The built-ins may already be registered by an earlier test class in the same JVM. */
    private static ProviderRegistry registryWithBuiltIns() {
        ProviderRegistry registry = ProviderRegistry.get();
        if (registry.find(DeepSeekProvider.ID) == null) {
            registry.registerBuiltIns();
        }
        return registry;
    }

    @Test
    public void threeAccountsResolveToTheSameProviderInstance() throws Exception {
        setUpAccountManager();
        ProviderRegistry registry = registryWithBuiltIns();

        Account first = createDeepSeekAccount(FIRST_NAMES[0]);
        Account second = createDeepSeekAccount(FIRST_NAMES[1]);
        Account third = createDeepSeekAccount(FIRST_NAMES[2]);

        assertEquals("the three fixture accounts must be distinct rows", 3, accounts.count());
        assertFalse("the three fixture accounts must have distinct ids",
                first.getId().equals(second.getId()) || second.getId().equals(third.getId()));

        UsageProvider resolvedFirst = registry.require(first.getProviderId());
        UsageProvider resolvedSecond = registry.require(second.getProviderId());
        UsageProvider resolvedThird = registry.require(third.getProviderId());

        assertSame("Spec §14: a second DeepSeek account must resolve to the very same provider instance",
                resolvedFirst, resolvedSecond);
        assertSame("Spec §14: a third DeepSeek account must resolve to the very same provider instance",
                resolvedFirst, resolvedThird);
    }

    @Test
    public void addingAccountsRegistersNoNewProvider() throws Exception {
        setUpAccountManager();
        ProviderRegistry registry = registryWithBuiltIns();
        int providersBefore = registry.all().size();

        createDeepSeekAccount(FIRST_NAMES[0]);
        createDeepSeekAccount(FIRST_NAMES[1]);
        createDeepSeekAccount(FIRST_NAMES[2]);

        // Asserted as a delta rather than an absolute size: the registry is a
        // JVM-wide singleton and other test classes register their own stubs
        // into it, so only the change caused by this test is meaningful.
        assertEquals("Spec §14: adding accounts must not add a provider — the provider set is per platform, not per account",
                providersBefore, registry.all().size());
    }

    @Test
    public void exactlyOneProviderClaimsTheDeepSeekId() throws Exception {
        setUpAccountManager();
        ProviderRegistry registry = registryWithBuiltIns();

        List<String> deepSeekProviders = new ArrayList<>();
        for (UsageProvider provider : registry.all()) {
            if (DeepSeekProvider.ID.equals(provider.getId())) {
                deepSeekProviders.add(provider.getClass().getName());
            }
        }

        assertEquals("exactly one registered provider may claim the id \"" + DeepSeekProvider.ID
                        + "\", but found " + deepSeekProviders,
                1, deepSeekProviders.size());
    }

    @Test
    public void theRegisteredProviderIsAStatelessDeepSeekProvider() throws Exception {
        setUpAccountManager();
        ProviderRegistry registry = registryWithBuiltIns();

        UsageProvider provider = registry.require(DeepSeekProvider.ID);

        assertNotNull("the deepseek provider must be resolvable", provider);
        assertEquals("the resolved provider must be the DeepSeek implementation",
                DeepSeekProvider.class.getName(), provider.getClass().getName());
        assertEquals("the resolved provider must report the deepseek id",
                DeepSeekProvider.ID, provider.getId());
    }

    @Test
    public void providerDeclaresTheApiKeyAuthTypeOnly() throws Exception {
        setUpAccountManager();
        UsageProvider provider = registryWithBuiltIns().require(DeepSeekProvider.ID);

        List<AuthType> supported = provider.getSupportedAuthTypes();
        assertEquals("DeepSeek supports exactly one auth type, so account count cannot affect it",
                1, supported.size());
        assertEquals("DeepSeek's single auth type must be API_KEY",
                AuthType.API_KEY, supported.get(0));
    }

    @Test
    public void providerCapabilitiesDoNotDependOnWhichAccountAsks() throws Exception {
        setUpAccountManager();
        ProviderRegistry registry = registryWithBuiltIns();

        Account first = createDeepSeekAccount(FIRST_NAMES[0]);
        Account third = createDeepSeekAccount(FIRST_NAMES[2]);

        ProviderCapabilities fromFirst = registry.require(first.getProviderId()).getCapabilities();
        ProviderCapabilities fromThird = registry.require(third.getProviderId()).getCapabilities();

        assertSame("capabilities are a property of the provider, not of the account",
                fromFirst, fromThird);
        assertTrue("DeepSeek reports a balance", fromFirst.reportsBalance());
        assertFalse("DeepSeek reports no quota windows", fromFirst.reportsQuotaWindows());
        assertTrue("DeepSeek's capabilities must advertise API_KEY",
                fromFirst.supportsAuthType(AuthType.API_KEY));
    }

    @Test
    public void threeAccountsOfOneProviderKeepDistinctIdentities() throws Exception {
        setUpAccountManager();

        Account first = createDeepSeekAccount(FIRST_NAMES[0]);
        Account second = createDeepSeekAccount(FIRST_NAMES[1]);
        Account third = createDeepSeekAccount(FIRST_NAMES[2]);

        // The provider is shared, so the identity must live entirely in the
        // account row — which is exactly why the provider needs no code change.
        assertFalse("a third account must get its own credential row",
                first.getCredentialId().equals(third.getCredentialId()));
        assertEquals("each account must keep its own display name",
                FIRST_NAMES[2], accountManager.find(third.getId()).getDisplayName());
        assertEquals("all three accounts must share the one provider id",
                first.getProviderId(), third.getProviderId());
    }

    // =====================================================================
    // Source location, reading and hashing
    // =====================================================================

    /**
     * Walks up from the working directory looking for the provider source.
     *
     * <p>Two layouts are checked at each level because the unit-test task may
     * run with the module directory or the repository root as its working
     * directory, and the answer must not depend on which.
     */
    private static File locateProviderSource() {
        List<String> tried = new ArrayList<>();
        File directory = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (directory != null) {
            for (String prefix : new String[]{"", "app/"}) {
                File candidate = new File(directory, prefix + SOURCE_RELATIVE_PATH);
                tried.add(candidate.getAbsolutePath());
                if (candidate.isFile()) {
                    return candidate;
                }
            }
            directory = directory.getParentFile();
        }
        fail("could not locate DeepSeekProvider.java from user.dir="
                + System.getProperty("user.dir") + "; tried:\n  " + String.join("\n  ", tried));
        return null;
    }

    private static byte[] readProviderSourceBytes() throws IOException {
        return Files.readAllBytes(locateProviderSource().toPath());
    }

    private static String readProviderSource() throws IOException {
        return new String(readProviderSourceBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Removes comments so the structural assertions inspect code rather than
     * prose.
     *
     * <p>A line comment is only stripped when it is not part of a URL scheme:
     * the provider contains {@code https://} literals, and treating the second
     * slash of {@code https://} as a comment would delete the rest of the line
     * and make the assertions pass for the wrong reason.
     */
    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(withoutBlocks.length());
        for (String line : withoutBlocks.split("\n", -1)) {
            int comment = -1;
            for (int index = 0; index + 1 < line.length(); index++) {
                if (line.charAt(index) == '/' && line.charAt(index + 1) == '/'
                        && (index == 0 || line.charAt(index - 1) != ':')) {
                    comment = index;
                    break;
                }
            }
            out.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return out.toString();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is required by every JVM but was not available", exception);
        }
    }

    // =====================================================================
    // Fakes — only what the three-account fixture needs
    // =====================================================================

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
     * In-memory {@link CredentialStore}. Only {@code create} matters to this
     * test — the point is that three accounts each get their own credential row
     * while sharing one provider — but the id and error contract mirrors
     * {@code SqliteCredentialStore} so the fake cannot drift into a shape the
     * production code would reject.
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
}
