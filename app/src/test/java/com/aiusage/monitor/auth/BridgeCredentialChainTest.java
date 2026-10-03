package com.aiusage.monitor.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Phase 6 credential chain for a Bridge account: payload encoding, the
 * adapter, and the one line in the store that used to answer every non-API key
 * type with "unsupported".
 *
 * <p>The store check is a source assertion rather than a call, because
 * {@code SqliteCredentialStore} needs an Android {@code Context} and this project
 * runs its tests on a plain JVM. Reading the file is the same technique
 * {@code DeepSeekProviderStabilityTest} uses to pin architecture: if the case
 * disappears, this test fails.
 */
public class BridgeCredentialChainTest {

    @Test
    public void payloadRoundTripsBothMembers() throws Exception {
        String payload = CredentialPayload.forBridge("http://10.0.2.2:38411/", "tok-123");

        assertEquals("tok-123", CredentialPayload.extractDeviceToken(payload));
        assertEquals("http://10.0.2.2:38411", CredentialPayload.extractBridgeUrl(payload));
    }

    @Test
    public void httpsIsAccepted() throws Exception {
        String payload = CredentialPayload.forBridge("https://pc.lan:38411", "t");
        assertEquals("https://pc.lan:38411", CredentialPayload.extractBridgeUrl(payload));
    }

    /**
     * The Phase 5 Bridge checks no token, so an address without one is the
     * configuration that works today. The member is dropped rather than written as
     * an empty string, so the payload never claims a blank credential.
     */
    @Test
    public void anAddressWithoutATokenIsStorableAndReadable() throws Exception {
        for (String token : new String[]{"", "   ", null}) {
            String payload = CredentialPayload.forBridge("http://127.0.0.1:38411", token);
            assertFalse("empty token written into the payload: " + payload,
                    payload.contains("deviceToken"));
            assertEquals("http://127.0.0.1:38411", CredentialPayload.extractBridgeUrl(payload));
            assertEquals("", CredentialPayload.extractOptionalDeviceToken(payload));
        }
    }

    @Test
    public void addressWithoutASchemeIsRejected() {
        for (String url : new String[]{"", "10.0.2.2:38411", "ftp://x", "http://"}) {
            try {
                CredentialPayload.forBridge(url, "tok");
                fail("expected AuthException for url " + url);
            } catch (AuthException exception) {
                assertEquals("for " + url, UsageError.UNSUPPORTED, exception.getError());
            }
        }
    }

    /**
     * The Phase 5 Bridge checks no token at all, so a payload that carries only an
     * address is a configuration that genuinely works. Refusing it would make the
     * emulator path impossible to set up.
     */
    @Test
    public void urlIsReadableEvenWhenNoTokenWasConfigured() throws Exception {
        String payload = "{\"bridgeUrl\":\"http://10.0.2.2:38411\"}";
        assertEquals("http://10.0.2.2:38411", CredentialPayload.extractBridgeUrl(payload));
    }

    @Test
    public void tokenOnlyPayloadYieldsAnEmptyAddressNotAFailure() throws Exception {
        // The Spec §7 payload shape is {"deviceToken":"…"}; the address member is
        // this project's extension, so reading a Spec-shaped payload must work.
        String payload = "{\"deviceToken\":\"t\"}";
        assertEquals("t", CredentialPayload.extractDeviceToken(payload));
        assertEquals("", CredentialPayload.extractBridgeUrl(payload));
    }

    @Test
    public void missingTokenIsReportedAsABridgeAuthorisationProblem() {
        try {
            CredentialPayload.extractDeviceToken("{\"bridgeUrl\":\"http://h:1\"}");
            fail("expected AuthException");
        } catch (AuthException exception) {
            assertEquals(UsageError.BRIDGE_UNAUTHORIZED, exception.getError());
        }
    }

    @Test
    public void adapterProducesBothContextValuesAndTheRightType() throws Exception {
        String payload = CredentialPayload.forBridge("http://10.0.2.2:38411", "tok-9");

        AuthContext context = new BridgeAuthAdapter().adapt(payload);

        assertEquals(AuthType.BRIDGE_TOKEN, context.getAuthType());
        assertEquals("tok-9", context.get(AuthContext.KEY_DEVICE_TOKEN));
        assertEquals("http://10.0.2.2:38411", context.get(AuthContext.KEY_BRIDGE_URL));
    }

    @Test
    public void adapterValidatesWithoutTouchingTheNetwork() throws Exception {
        BridgeAuthAdapter adapter = new BridgeAuthAdapter();
        adapter.validate(CredentialPayload.forBridge("http://127.0.0.1:38411", "t"));

        // A payload in the Spec-shaped form (token, no address) is storable and
        // valid: the address is optional in the payload and is enforced where it is
        // used, at fetch time - CodexProviderTest asserts that a missing address
        // stops a request without touching the network. validate() must not reach
        // the network to find that out either. Spec §53 rule 22.
        adapter.validate("{\"deviceToken\":\"t\"}");

        // An empty payload is no longer a storage error - both members are
        // optional at rest, and it is fetch time that refuses to guess a host
        // (CodexProviderTest covers that). What validate must still refuse is an
        // address that cannot be a URL at all: that is a typo the user can fix
        // while looking at the field.
        adapter.validate("{}");
        try {
            adapter.validate("{\"bridgeUrl\":\"not-a-url\"}");
            fail("expected a malformed address to be reported");
        } catch (AuthException exception) {
            assertEquals(UsageError.UNSUPPORTED, exception.getError());
        }
    }

    /**
     * Spec §50: the secret must not ride along in text meant for a log or a toast.
     * The risk is a failure path that echoes the payload it could not read, so the
     * fixtures below put the secret inside the bad payload rather than beside it.
     */
    @Test
    public void errorMessagesNeverQuoteThePayloadTheyRejected() {
        String[] badPayloads = {
                "{\"deviceToken\":\"SUPER-SECRET\",\"bridgeUrl\":\"ftp://h\"}",
                "{\"bridgeUrl\":\"http://\",\"deviceToken\":\"SUPER-SECRET\"}",
                "not json at all",
                "",
        };
        for (String payload : badPayloads) {
            try {
                new BridgeAuthAdapter().adapt(payload);
                fail("expected AuthException for " + payload);
            } catch (AuthException exception) {
                String message = String.valueOf(exception.getMessage())
                        + " | cause=" + exception.getCause();
                assertFalse("payload echoed into the failure text: " + message,
                        message.contains("SUPER-SECRET"));
            }
        }
    }

    @Test
    public void credentialStoreOpensBridgeTokens() throws IOException {
        String source = readRepoFile("app/src/main/java/com/aiusage/monitor/storage/SqliteCredentialStore.java");

        assertTrue("SqliteCredentialStore must handle BRIDGE_TOKEN; without this case a "
                        + "Codex account can be created but never fetched",
                source.contains("case BRIDGE_TOKEN:"));
        assertTrue("the BRIDGE_TOKEN case must go through the bridge adapter, not the API key one",
                source.contains("new com.aiusage.monitor.auth.BridgeAuthAdapter().adapt(plaintext)"));
    }

    /**
     * Resolves a repo-relative path whether Gradle ran the tests from the repo
     * root or from the module directory, and fails loudly if neither works: a
     * source guard that silently scans nothing is worse than no guard at all.
     */
    private static String readRepoFile(String relativePath) throws IOException {
        File fromRoot = new File(System.getProperty("user.dir"), relativePath);
        File fromModule = new File(System.getProperty("user.dir"),
                relativePath.startsWith("app/") ? relativePath.substring("app/".length()) : relativePath);
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;

        assertTrue("cannot find " + relativePath + " from " + System.getProperty("user.dir")
                        + " - this guard would be scanning nothing",
                candidate.isFile());
        String text = new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
        assertTrue("the scanned file has no package declaration; wrong file? " + candidate,
                text.contains("package com.aiusage.monitor.storage;"));
        return text;
    }

    @Test
    public void bridgeAdapterIsItsOwnMechanismNotAnApiKey() {
        AuthAdapter adapter = new BridgeAuthAdapter();
        assertEquals(AuthType.BRIDGE_TOKEN, adapter.getAuthType());
        assertNotNull(new ApiKeyAuthAdapter().getAuthType());
        assertFalse("one adapter per mechanism (Spec §53 rule 22)",
                adapter.getAuthType() == new ApiKeyAuthAdapter().getAuthType());
    }
}
