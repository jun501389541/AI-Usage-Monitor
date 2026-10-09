package com.aiusage.monitor.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * The retention rules, asserted where they are decided.
 *
 * <p>Deleting history is the one operation in this app that cannot be undone by
 * re-querying: the readings came from a balance endpoint that never serves the
 * past. So every rule in {@link SnapshotRetention} gets a case here, including
 * the ones that only matter in the rare shape -- an account whose last reading
 * is a year old, a day that rolls over mid-window, a clock that went backwards.
 *
 * <p>{@code nowMs} is a parameter for the same reason the widget resolver takes
 * one: a rule about "today" that reads the wall clock cannot be tested near
 * midnight, and the day-boundary bug this project already fixed (R1) was a bug
 * nobody could reproduce on demand.
 */
public class SnapshotRetentionTest {

    private static final String ACCOUNT = "acct_a";
    private static final String OTHER = "acct_b";
    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long RETENTION = 14 * DAY;

    /** A fixed "now" at local midday, so "today" is unambiguous in any zone. */
    private static final long NOW = midday();

    private static long midday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private final SnapshotRetention retention = new SnapshotRetention(RETENTION);

    private static UsageSnapshot row(long id, String account, long at, boolean success) {
        return new UsageSnapshot(id, account, at, "{}", "DIRECT_API", success);
    }

    private static List<Long> ids(List<UsageSnapshot> rows, long nowMs) {
        return new SnapshotRetention(RETENTION).expired(rows, nowMs);
    }

    private static boolean contains(List<Long> ids, long id) {
        return ids.contains(id);
    }

    // ------------------------------------------------------------ the window

    @Test
    public void aReadingInsideTheWindowIsNeverDeleted() {
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - DAY, true),
                row(2, ACCOUNT, NOW - 13 * DAY, true));

        assertTrue("everything inside 14 days survives: " + ids(rows, NOW),
                ids(rows, NOW).isEmpty());
    }

    @Test
    public void anOrdinaryOldReadingIsDeleted() {
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - 20 * DAY, true),
                row(2, ACCOUNT, NOW - 19 * DAY, true),
                row(3, ACCOUNT, NOW - DAY, true));

        List<Long> doomed = ids(rows, NOW);
        assertEquals(Arrays.asList(1L, 2L), doomed);
    }

    @Test
    public void theWindowBoundaryIsExact() {
        // One millisecond inside the window stays; one outside is eligible.
        List<UsageSnapshot> inside = Collections.singletonList(
                row(1, ACCOUNT, NOW - RETENTION + 1L, true));
        List<UsageSnapshot> outside = Collections.singletonList(
                row(2, ACCOUNT, NOW - RETENTION - 1L, true));

        assertTrue(ids(inside, NOW).isEmpty());
        // The lone row is also the account's last success, which rule 2 protects,
        // so the boundary is checked against a row that is not special.
        List<UsageSnapshot> outsideWithNewer = Arrays.asList(
                row(2, ACCOUNT, NOW - RETENTION - 1L, true),
                row(3, ACCOUNT, NOW - DAY, true));
        assertEquals(Collections.singletonList(2L), ids(outsideWithNewer, NOW));
    }

    // ------------------------------------------- the readings that hold a screen

    /**
     * Spec §39 and rule 18: a widget must never be blanked.
     *
     * <p>The account in this case has been idle for a month -- its only reading
     * is older than the window -- and deleting it would leave the list and the
     * widget showing an em dash for a balance that really was read.
     */
    @Test
    public void theLastSuccessfulReadingSurvivesHoweverOldItIs() {
        List<UsageSnapshot> rows = Collections.singletonList(
                row(7, ACCOUNT, NOW - 40 * DAY, true));

        assertTrue("an account's only reading is the number on screen",
                ids(rows, NOW).isEmpty());
    }

    @Test
    public void theNewestRowSurvivesEvenWhenItIsAFailure() {
        // The status line reads the newest attempt. Dropping it would turn
        // "API Key 无效或已失效" into "账户可用", which is the R4 bug again.
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - 30 * DAY, true),
                row(2, ACCOUNT, NOW - 40 * DAY, true),
                row(3, ACCOUNT, NOW - 20 * DAY, false));

        List<Long> doomed = ids(rows, NOW);
        assertFalse("the newest attempt is kept whatever its age", contains(doomed, 3L));
        assertFalse("the last success is kept", contains(doomed, 1L));
        assertTrue("an older success that is neither is eligible", contains(doomed, 2L));
    }

    @Test
    public void anAccountWhoseOnlyRowsAreFailuresKeepsItsNewestAttempt() {
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - 30 * DAY, false),
                row(2, ACCOUNT, NOW - 29 * DAY, false));

        List<Long> doomed = ids(rows, NOW);
        assertEquals(Collections.singletonList(1L), doomed);
    }

    // ---------------------------------------------------------------- today

    @Test
    public void lastArrivingCacheSurvivesAlongsideTheFreshestSuccessfulData() {
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - 20 * DAY, true),
                row(2, ACCOUNT, NOW - 19 * DAY, false),
                row(3, ACCOUNT, NOW - 21 * DAY, true));
        assertEquals(Collections.singletonList(2L), ids(rows, NOW));
    }

    @Test
    public void everyReadingFromTodayIsKept() {
        // A row from 00:05 today is inside the window anyway; what this pins is
        // that "today" is judged on the calendar, not on a 24-hour span, so a
        // reading from the start of a long day is never half-deleted.
        Calendar startOfToday = Calendar.getInstance();
        startOfToday.setTimeInMillis(NOW);
        startOfToday.set(Calendar.HOUR_OF_DAY, 0);
        startOfToday.set(Calendar.MINUTE, 0);
        startOfToday.set(Calendar.SECOND, 0);
        startOfToday.set(Calendar.MILLISECOND, 1);

        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, startOfToday.getTimeInMillis(), true),
                row(2, ACCOUNT, NOW, true));

        assertTrue(ids(rows, NOW).isEmpty());
    }

    @Test
    public void aReadingFromYesterdayIsNotProtectedByTheTodayRule() {
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - 20 * DAY, true),
                row(2, ACCOUNT, NOW - DAY, true),
                row(3, ACCOUNT, NOW - 30 * DAY, true));

        List<Long> doomed = ids(rows, NOW);
        assertTrue("yesterday is not today", contains(doomed, 1L));
        assertFalse(contains(doomed, 2L));
    }

    // ------------------------------------------------------------- isolation

    @Test
    public void oneAccountsProtectionNeverExtendsToAnother() {
        // acct_a's last success is old and must be kept; acct_b has an equally
        // old row that is neither newest nor a last success, and must go.
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - 40 * DAY, true),
                row(2, OTHER, NOW - 40 * DAY, true),
                row(3, OTHER, NOW - DAY, true));

        List<Long> doomed = ids(rows, NOW);
        assertFalse(contains(doomed, 1L));
        assertEquals(Collections.singletonList(2L), doomed);
    }

    @Test
    public void noRowsMeansNoWork() {
        assertTrue(retention.expired(new ArrayList<UsageSnapshot>(), NOW).isEmpty());
        // A null list must not throw at 3 a.m. inside a background broadcast,
        // where nothing is watching to see the exception.
        assertTrue(retention.expired(null, NOW).isEmpty());
    }

    // ------------------------------------------------------------- the order

    @Test
    public void theDecisionDoesNotDependOnTheOrderRowsArriveIn() {
        List<UsageSnapshot> oldestFirst = new ArrayList<>(Arrays.asList(
                row(1, ACCOUNT, NOW - 30 * DAY, true),
                row(2, ACCOUNT, NOW - 25 * DAY, true),
                row(3, ACCOUNT, NOW - DAY, true)));
        List<UsageSnapshot> newestFirst = new ArrayList<>(Arrays.asList(
                row(3, ACCOUNT, NOW - DAY, true),
                row(2, ACCOUNT, NOW - 25 * DAY, true),
                row(1, ACCOUNT, NOW - 30 * DAY, true)));

        List<Long> doomed = ids(oldestFirst, NOW);
        assertEquals(doomed, ids(newestFirst, NOW));
        assertEquals(Arrays.asList(1L, 2L), doomed);
    }

    @Test
    public void theDefaultWindowIsTheDocumentedFourteenDays() {
        assertEquals(14, SnapshotRetention.DETAIL_RETENTION_DAYS);
        // The no-arg policy must agree with the constant, or the number in this
        // file and the number in force are two different facts.
        List<UsageSnapshot> rows = Arrays.asList(
                row(1, ACCOUNT, NOW - (SnapshotRetention.DETAIL_RETENTION_DAYS + 2L) * DAY, true),
                row(2, ACCOUNT, NOW - DAY, true));
        assertEquals(Collections.singletonList(1L),
                new SnapshotRetention().expired(rows, NOW));
    }

    /**
     * Rows written in the same millisecond are not hypothetical: a manual refresh
     * and the background one it interrupted can both commit inside one tick, and
     * SQLite assigns ids in insert order. The read path calls the largest id the
     * newest row ({@code timestamp DESC, id DESC}), so retention has to protect the
     * same row whatever order the cursor happened to return them in
     * (docs/PHASE-0-7-REVIEW.md §2.3).
     */
    @Test
    public void rowsSharingOneInstantAreJudgedByTheSameRuleTheReadPathUses() {
        long at = NOW - 20 * DAY;
        List<UsageSnapshot> ascending = Arrays.asList(
                row(7, ACCOUNT, at, true),
                row(9, ACCOUNT, at, true),
                row(11, ACCOUNT, at, false));
        List<UsageSnapshot> descending = Arrays.asList(
                row(11, ACCOUNT, at, false),
                row(9, ACCOUNT, at, true),
                row(7, ACCOUNT, at, true));

        // 11 is the newest row and 9 the newest success by the read path's rule;
        // 7 is neither, and only 7 may go.
        assertEquals(Arrays.asList(7L), ids(ascending, NOW));
        assertEquals(Arrays.asList(7L), ids(descending, NOW));
    }

    @Test
    public void aTieBetweenSuccessesKeepsTheNewerIdNotWhicheverRowCameFirst() {
        long at = NOW - 20 * DAY;
        List<UsageSnapshot> smallIdFirst = Arrays.asList(
                row(4, ACCOUNT, at, true),
                row(5, ACCOUNT, at, true));
        List<UsageSnapshot> bigIdFirst = Arrays.asList(
                row(5, ACCOUNT, at, true),
                row(4, ACCOUNT, at, true));

        assertEquals(Collections.singletonList(4L), ids(smallIdFirst, NOW));
        assertEquals("the last success inserted is the one latestSuccess reads back",
                Collections.singletonList(4L), ids(bigIdFirst, NOW));
    }
}
