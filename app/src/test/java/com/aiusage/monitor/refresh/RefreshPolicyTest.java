package com.aiusage.monitor.refresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.provider.ProviderCapabilities;

import org.junit.Test;

/**
 * Tests for {@link RefreshPolicy}: interval selection precedence, labels, and
 * staleness.
 *
 * <p>Precedence under test: an explicit user override wins, then the provider's
 * recommendation, then {@link RefreshPolicy#DEFAULT_INTERVAL_MS}. The
 * {@link RefreshPolicy#MANUAL_ONLY} sentinel is a user override and is never
 * displaced by a provider recommendation.
 */
public class RefreshPolicyTest {

    /** A provider that recommends 15 minutes and reports a balance. */
    private static ProviderCapabilities providerRecommending(long intervalMs) {
        return ProviderCapabilities.builder()
                .supports(AuthType.API_KEY)
                .reportsBalance(true)
                .recommendedRefreshIntervalMs(intervalMs)
                .build();
    }

    @Test
    public void intervalForUsesProviderRecommendationWhenUserHasNotChosen() {
        long recommended = 15L * 60L * 1000L;
        assertEquals(recommended, RefreshPolicy.intervalFor(0L, providerRecommending(recommended)));
    }

    @Test
    public void intervalForUsesDefaultWhenCapabilitiesAreNull() {
        assertEquals(RefreshPolicy.DEFAULT_INTERVAL_MS, RefreshPolicy.intervalFor(0L, null));
    }

    @Test
    public void intervalForUsesDefaultWhenProviderHasNoRecommendation() {
        ProviderCapabilities noRecommendation = ProviderCapabilities.builder()
                .recommendedRefreshIntervalMs(0L)
                .build();
        assertEquals(RefreshPolicy.DEFAULT_INTERVAL_MS, RefreshPolicy.intervalFor(0L, noRecommendation));
    }

    @Test
    public void intervalForHonoursManualOnlyEvenWhenProviderRecommendsSomething() {
        ProviderCapabilities capabilities = providerRecommending(15L * 60L * 1000L);
        assertEquals(RefreshPolicy.MANUAL_ONLY, RefreshPolicy.intervalFor(RefreshPolicy.MANUAL_ONLY, capabilities));
    }

    @Test
    public void intervalForLetsUserOverrideWinOverProviderRecommendation() {
        long userChoice = 2L * 60L * 60L * 1000L;
        assertEquals(userChoice, RefreshPolicy.intervalFor(userChoice, providerRecommending(15L * 60L * 1000L)));
    }

    @Test
    public void intervalForUsesUserOverrideWhenCapabilitiesAreNull() {
        long userChoice = 60L * 60L * 1000L;
        assertEquals(userChoice, RefreshPolicy.intervalFor(userChoice, null));
    }

    @Test
    public void labelForNamesTheDefaultInterval() {
        assertEquals("30 分钟", RefreshPolicy.labelFor(RefreshPolicy.DEFAULT_INTERVAL_MS));
    }

    @Test
    public void labelForNamesManualOnly() {
        assertEquals("仅手动", RefreshPolicy.labelFor(RefreshPolicy.MANUAL_ONLY));
    }

    @Test
    public void labelForRendersAnOffMenuIntervalInMinutes() {
        assertEquals("45 分钟", RefreshPolicy.labelFor(45L * 60L * 1000L));
    }

    @Test
    public void labelForNamesTheMenuIntervals() {
        for (int index = 0; index < RefreshPolicy.INTERVALS_MS.length; index++) {
            assertEquals(RefreshPolicy.INTERVAL_LABELS[index],
                    RefreshPolicy.labelFor(RefreshPolicy.INTERVALS_MS[index]));
        }
    }

    @Test
    public void labelForFallsBackToManualOnlyWhenIntervalIsNotPositive() {
        assertEquals("仅手动", RefreshPolicy.labelFor(0L));
    }

    @Test
    public void isStaleIsFalseForFreshData() {
        long now = 1_000_000L;
        long interval = 60_000L;
        assertFalse(RefreshPolicy.isStale(now, interval, now));
    }

    @Test
    public void isStaleIsFalseWithinTwiceTheInterval() {
        long interval = 60_000L;
        long now = 10_000_000L;
        assertFalse(RefreshPolicy.isStale(now - (interval * 2L), interval, now));
    }

    @Test
    public void isStaleIsTrueBeyondTwiceTheInterval() {
        long interval = 60_000L;
        long now = 10_000_000L;
        assertTrue(RefreshPolicy.isStale(now - (interval * 2L) - 1L, interval, now));
    }

    @Test
    public void isStaleIsTrueWhenUpdatedAtIsZero() {
        assertTrue(RefreshPolicy.isStale(0L, RefreshPolicy.DEFAULT_INTERVAL_MS, 10_000_000L));
    }

    @Test
    public void isStaleIsTrueWhenUpdatedAtIsNegative() {
        assertTrue(RefreshPolicy.isStale(-1L, RefreshPolicy.DEFAULT_INTERVAL_MS, 10_000_000L));
    }

    @Test
    public void isStaleFallsBackToDefaultIntervalWhenIntervalIsNotPositive() {
        long now = 10_000_000L;
        long justOverDefault = (RefreshPolicy.DEFAULT_INTERVAL_MS * 2L) + 1L;
        assertTrue(RefreshPolicy.isStale(now - justOverDefault, 0L, now));
    }
}
