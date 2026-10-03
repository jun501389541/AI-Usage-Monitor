package com.aiusage.monitor.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.provider.deepseek.DeepSeekProvider;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Host-JVM tests for {@link ProviderCapabilities} and for the capabilities
 * {@link DeepSeekProvider} declares.
 *
 * <p>The defaults asserted here are the ones the builder actually sets: every
 * {@code reports*} flag starts false and the recommended interval starts at
 * 30 minutes. DeepSeek then overrides balance and metrics to true while leaving
 * quota windows false.
 *
 * <p>Instantiating {@link DeepSeekProvider} is safe on a plain JVM: the class
 * builds its static capability object in a static initialiser and touches the
 * network only inside {@code fetchUsage}, which these tests never call.
 */
public class ProviderCapabilitiesTest {

    private static final long THIRTY_MINUTES_MS = 30L * 60L * 1000L;

    @Test
    public void builderDefaultsAreAllFalseWithThirtyMinuteInterval() {
        ProviderCapabilities capabilities = ProviderCapabilities.builder().build();

        assertFalse(capabilities.reportsBalance());
        assertFalse(capabilities.reportsQuotaWindows());
        assertFalse(capabilities.reportsMetrics());
        assertEquals(THIRTY_MINUTES_MS, capabilities.getRecommendedRefreshIntervalMs());
        assertTrue(capabilities.getSupportedAuthTypes().isEmpty());
    }

    @Test
    public void supportsAuthTypeIsTrueOnlyForTypesAddedViaSupports() {
        ProviderCapabilities capabilities = ProviderCapabilities.builder()
                .supports(AuthType.API_KEY)
                .supports(AuthType.OAUTH)
                .build();

        assertTrue(capabilities.supportsAuthType(AuthType.API_KEY));
        assertTrue(capabilities.supportsAuthType(AuthType.OAUTH));

        assertFalse(capabilities.supportsAuthType(AuthType.BRIDGE_TOKEN));
        assertFalse(capabilities.supportsAuthType(AuthType.COOKIE));
        assertFalse(capabilities.supportsAuthType(AuthType.CUSTOM));
        assertFalse(capabilities.supportsAuthType(null));
    }

    @Test
    public void supportsOfNullIsIgnoredRatherThanStored() {
        ProviderCapabilities capabilities = ProviderCapabilities.builder()
                .supports(null)
                .build();

        assertTrue(capabilities.getSupportedAuthTypes().isEmpty());
    }

    @Test
    public void supportedAuthTypesSetIsUnmodifiable() {
        ProviderCapabilities capabilities = ProviderCapabilities.builder()
                .supports(AuthType.API_KEY)
                .build();

        Set<AuthType> types = capabilities.getSupportedAuthTypes();
        assertEquals(1, types.size());
        assertTrue(types.contains(AuthType.API_KEY));

        try {
            types.add(AuthType.OAUTH);
            fail("expected getSupportedAuthTypes() to be unmodifiable");
        } catch (UnsupportedOperationException exception) {
            // Documented: Collections.unmodifiableSet.
            assertNotNull(exception);
        }
    }

    @Test
    public void builderFlagsAndIntervalRoundTrip() {
        ProviderCapabilities capabilities = ProviderCapabilities.builder()
                .supports(AuthType.COOKIE)
                .reportsBalance(true)
                .reportsQuotaWindows(true)
                .reportsMetrics(false)
                .recommendedRefreshIntervalMs(90_000L)
                .build();

        assertTrue(capabilities.reportsBalance());
        assertTrue(capabilities.reportsQuotaWindows());
        assertFalse(capabilities.reportsMetrics());
        assertEquals(90_000L, capabilities.getRecommendedRefreshIntervalMs());
    }

    @Test
    public void deepSeekReportsBalanceAndMetricsButNoQuotaWindows() {
        DeepSeekProvider provider = new DeepSeekProvider();
        ProviderCapabilities capabilities = provider.getCapabilities();

        assertNotNull(capabilities);
        assertTrue(capabilities.reportsBalance());
        assertFalse(capabilities.reportsQuotaWindows());
        assertTrue(capabilities.reportsMetrics());
        assertEquals(THIRTY_MINUTES_MS, capabilities.getRecommendedRefreshIntervalMs());
        assertTrue(capabilities.supportsAuthType(AuthType.API_KEY));
        assertFalse(capabilities.supportsAuthType(AuthType.OAUTH));
    }

    @Test
    public void deepSeekAdvertisesExactlyTheApiKeyAuthType() {
        DeepSeekProvider provider = new DeepSeekProvider();

        List<AuthType> supported = provider.getSupportedAuthTypes();
        assertNotNull(supported);
        assertEquals(1, supported.size());
        assertEquals(AuthType.API_KEY, supported.get(0));
        assertEquals(Arrays.asList(AuthType.API_KEY), supported);

        // The provider's list and its declared capability must agree.
        Set<AuthType> declared = provider.getCapabilities().getSupportedAuthTypes();
        assertEquals(1, declared.size());
        assertTrue(declared.contains(AuthType.API_KEY));
    }

    @Test
    public void deepSeekIdentityIsStableAndInstantiationNeedsNoNetwork() {
        DeepSeekProvider provider = new DeepSeekProvider();

        assertEquals("deepseek", provider.getId());
        assertEquals(DeepSeekProvider.ID, provider.getId());
        assertEquals("DeepSeek", provider.getName());
        assertNotNull(provider.getName());
        assertFalse(provider.getName().isEmpty());
        assertEquals("https://api.deepseek.com/user/balance", DeepSeekProvider.BALANCE_URL);
        assertEquals("CNY", DeepSeekProvider.CURRENCY);
    }

    @Test
    public void deepSeekCapabilitiesAreSharedAcrossInstances() {
        // The capability object is a static constant, so two providers agree.
        ProviderCapabilities first = new DeepSeekProvider().getCapabilities();
        ProviderCapabilities second = new DeepSeekProvider().getCapabilities();

        assertNotNull(first);
        assertEquals(first.reportsBalance(), second.reportsBalance());
        assertEquals(first.reportsQuotaWindows(), second.reportsQuotaWindows());
        assertEquals(first.reportsMetrics(), second.reportsMetrics());
        assertEquals(first.getRecommendedRefreshIntervalMs(), second.getRecommendedRefreshIntervalMs());
        assertEquals(first.getSupportedAuthTypes(), second.getSupportedAuthTypes());
    }
}
