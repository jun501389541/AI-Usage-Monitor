package com.aiusage.monitor.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.UsageError;

import org.junit.Test;

/**
 * Tests for {@link CredentialPayload}, the plaintext JSON shapes stored inside a
 * {@link Credential}.
 *
 * <p>Contract as implemented: the {@code for*} builders reject blank input with
 * an {@link AuthException}, and every extractor <em>throws</em> rather than
 * returning a sentinel when the payload is empty, malformed, or missing the
 * field it needs. These tests pin that behaviour, including the mapped
 * {@link UsageError}.
 */
public class CredentialPayloadTest {

    @Test
    public void forApiKeyRoundTripsThroughExtractApiKey() throws Exception {
        assertEquals("sk-abc", CredentialPayload.extractApiKey(CredentialPayload.forApiKey("sk-abc")));
    }

    @Test
    public void forApiKeyEmitsTheDocumentedShape() throws Exception {
        assertEquals("{\"apiKey\":\"sk-abc\"}", CredentialPayload.forApiKey("sk-abc"));
    }

    @Test
    public void forApiKeyTrimsTheKeyBeforeEncoding() throws Exception {
        assertEquals("sk-abc", CredentialPayload.extractApiKey(CredentialPayload.forApiKey("  sk-abc  ")));
    }

    @Test
    public void forApiKeyRejectsAnEmptyKey() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.forApiKey(""));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void forApiKeyRejectsAWhitespaceOnlyKey() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.forApiKey("   "));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void forApiKeyRejectsANullKey() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.forApiKey(null));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyThrowsOnMalformedJson() {
        AuthException failure = assertThrows(AuthException.class,
                () -> CredentialPayload.extractApiKey("not json at all"));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyThrowsOnAnEmptyPayload() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.extractApiKey(""));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyThrowsOnANullPayload() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.extractApiKey(null));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyThrowsWhenTheFieldIsMissing() {
        AuthException failure = assertThrows(AuthException.class,
                () -> CredentialPayload.extractApiKey("{\"deviceToken\":\"tok\"}"));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyThrowsWhenTheFieldIsEmpty() {
        AuthException failure = assertThrows(AuthException.class,
                () -> CredentialPayload.extractApiKey("{\"apiKey\":\"\"}"));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyThrowsWhenTheFieldIsBlank() {
        AuthException failure = assertThrows(AuthException.class,
                () -> CredentialPayload.extractApiKey("{\"apiKey\":\"   \"}"));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void extractApiKeyTrimsAStoredKey() throws Exception {
        assertEquals("sk-abc", CredentialPayload.extractApiKey("{\"apiKey\":\"  sk-abc  \"}"));
    }

    @Test
    public void forDeviceTokenRoundTripsThroughTheSameParser() throws Exception {
        String payload = CredentialPayload.forDeviceToken("tok");
        assertEquals("tok", new org.json.JSONObject(payload).optString("deviceToken", ""));
    }

    @Test
    public void forDeviceTokenEmitsTheDocumentedShape() throws Exception {
        assertEquals("{\"deviceToken\":\"tok\"}", CredentialPayload.forDeviceToken("tok"));
    }

    @Test
    public void forDeviceTokenRejectsAnEmptyToken() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.forDeviceToken(""));
        assertEquals(UsageError.BRIDGE_UNAUTHORIZED, failure.getError());
    }

    @Test
    public void forDeviceTokenRejectsANullToken() {
        AuthException failure = assertThrows(AuthException.class, () -> CredentialPayload.forDeviceToken(null));
        assertEquals(UsageError.BRIDGE_UNAUTHORIZED, failure.getError());
    }

    @Test
    public void apiKeyPayloadDoesNotLeakIntoTheDeviceTokenShape() throws Exception {
        String payload = CredentialPayload.forApiKey("sk-abc");
        assertTrue(new org.json.JSONObject(payload).optString("deviceToken", "").isEmpty());
    }
}
