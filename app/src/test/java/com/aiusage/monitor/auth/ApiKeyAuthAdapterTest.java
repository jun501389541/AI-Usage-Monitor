package com.aiusage.monitor.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import org.junit.Test;

/**
 * Tests for {@link ApiKeyAuthAdapter}: payload-to-context adaptation, the
 * plausibility check, and the display mask.
 *
 * <p>Thresholds are taken from the implementation rather than assumed: a key
 * must be at least 8 characters, and it must contain no space and no newline.
 * {@link ApiKeyAuthAdapter#mask} returns {@code ""} for null or empty input and
 * {@code "…"} for anything of length 10 or less.
 */
public class ApiKeyAuthAdapterTest {

    private final ApiKeyAuthAdapter adapter = new ApiKeyAuthAdapter();

    @Test
    public void getAuthTypeIsApiKey() {
        assertEquals(AuthType.API_KEY, adapter.getAuthType());
    }

    @Test
    public void adaptProducesAnApiKeyAuthContextCarryingTheOriginalKey() throws Exception {
        AuthContext context = adapter.adapt(CredentialPayload.forApiKey("sk-abcdefghijklmn"));
        assertEquals(AuthType.API_KEY, context.getAuthType());
        assertEquals("sk-abcdefghijklmn", context.get(AuthContext.KEY_API_KEY));
    }

    @Test
    public void adaptReportsTheKeyAsPresent() throws Exception {
        AuthContext context = adapter.adapt(CredentialPayload.forApiKey("sk-abcdefghijklmn"));
        assertTrue(context.has(AuthContext.KEY_API_KEY));
    }

    @Test
    public void adaptDoesNotRenderTheSecretInToString() throws Exception {
        AuthContext context = adapter.adapt(CredentialPayload.forApiKey("sk-abcdefghijklmn"));
        assertFalse(context.toString().contains("sk-abcdefghijklmn"));
    }

    @Test
    public void adaptRejectsAMalformedPayload() {
        AuthException failure = assertThrows(AuthException.class, () -> adapter.adapt("not json"));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void validateAcceptsAPlausibleKey() throws Exception {
        adapter.validate(CredentialPayload.forApiKey("sk-abcdefghijklmn"));
    }

    @Test
    public void validateAcceptsAKeyOfExactlyTheMinimumLength() throws Exception {
        adapter.validate(CredentialPayload.forApiKey("12345678"));
    }

    @Test
    public void validateRejectsAKeyBelowTheMinimumLength() {
        AuthException failure = assertThrows(AuthException.class,
                () -> adapter.validate(CredentialPayload.forApiKey("1234567")));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void validateRejectsAKeyContainingASpace() {
        AuthException failure = assertThrows(AuthException.class,
                () -> adapter.validate(CredentialPayload.forApiKey("sk-abc defghij")));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void validateRejectsAKeyContainingANewline() {
        AuthException failure = assertThrows(AuthException.class,
                () -> adapter.validate(CredentialPayload.forApiKey("sk-abcdefg\nhij")));
        assertEquals(UsageError.INVALID_CREDENTIAL, failure.getError());
    }

    @Test
    public void validateToleratesATabBecauseOnlySpaceAndNewlineAreRejected() throws Exception {
        // Deliberately permissive: the check is only for " " and "\n", so a tab
        // passes here and the platform remains the authority on the key. The
        // call itself is the assertion -- validate throws on rejection.
        adapter.validate(CredentialPayload.forApiKey("sk-abcdefg\thij"));
    }

    @Test
    public void maskHidesTheWholeKey() {
        String key = "sk-abcdefghijklmn";
        String masked = ApiKeyAuthAdapter.mask(key);
        assertFalse(masked.contains(key));
        assertEquals("sk-abc…klmn", masked);
    }

    @Test
    public void maskReturnsTheDocumentedPlaceholderForShortStrings() {
        assertEquals("…", ApiKeyAuthAdapter.mask("short"));
    }

    @Test
    public void maskReturnsThePlaceholderAtTheLengthBoundary() {
        assertEquals("…", ApiKeyAuthAdapter.mask("1234567890"));
    }

    @Test
    public void maskRevealsOnlyThePrefixAndSuffixJustAboveTheBoundary() {
        assertEquals("123456…8901", ApiKeyAuthAdapter.mask("12345678901"));
    }

    @Test
    public void maskReturnsEmptyStringForNull() {
        assertEquals("", ApiKeyAuthAdapter.mask(null));
    }

    @Test
    public void maskReturnsEmptyStringForAnEmptyKey() {
        assertEquals("", ApiKeyAuthAdapter.mask(""));
    }
}
