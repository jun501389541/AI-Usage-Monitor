package com.aiusage.monitor.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.QuotaWindow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * The quota wording is the entire user-visible result of Phase 6, so its edge
 * cases are asserted here rather than discovered on a screen: a missing reset
 * time, a reset that already passed, and a percentage that arrived outside its
 * declared range all have to say something true instead of something formatted.
 */
public class QuotaWordsTest {

    private static final long NOW = millis(2026, Calendar.OCTOBER, 2, 12, 0);

    private static long millis(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static QuotaWindow window(String id, String label, double used, long resetAt) {
        return new QuotaWindow(id, label, used, 100d - used, 300L, resetAt);
    }

    @Test
    public void rendersLabelUsageAndResetTime() {
        String line = QuotaWords.line(window("codex:300", "5 小时", 31d, millis(2026, Calendar.OCTOBER, 2, 18, 22)), NOW);

        assertEquals("5 小时 · 已用 31% · 今天 18:22 重置", line);
    }

    @Test
    public void usesTheDayWhenTheResetIsNotToday() {
        String line = QuotaWords.line(window("codex:10080", "7 天", 49d, millis(2026, Calendar.OCTOBER, 8, 8, 30)), NOW);

        assertTrue(line, line.endsWith("10-08 08:30 重置"));
    }

    /**
     * A window with no reset time is still a real reading. Saying "未知" is the
     * honest answer; inventing a clock time would be worse than saying nothing.
     */
    @Test
    public void missingResetTimeIsAdmitted() {
        String line = QuotaWords.line(window("codex:300", "5 小时", 10d, 0L), NOW);

        assertTrue(line, line.endsWith(QuotaWords.RESET_UNKNOWN));
    }

    /**
     * The deadline being behind us is not evidence the window reset: the app only
     * knows the old one expired.
     */
    @Test
    public void pastDeadlineIsNotClaimedAsReset() {
        String line = QuotaWords.line(window("codex:300", "5 小时", 90d, millis(2026, Calendar.OCTOBER, 2, 9, 0)), NOW);

        assertTrue(line, line.endsWith(QuotaWords.RESET_DUE));
    }

    @Test
    public void fallsBackToTheIdentifierWhenTheSourceGaveNoName() {
        String line = QuotaWords.line(window("codex:300", "", 5d, millis(2026, Calendar.OCTOBER, 2, 13, 0)), NOW);

        assertTrue(line, line.startsWith("codex:300 ·"));
    }

    /**
     * Percentages are clamped for display, not corrected: a source that reports
     * 130% has a problem, and the screen should not amplify it into a claim that
     * looks arithmetically reasonable.
     */
    @Test
    public void outOfRangePercentagesAreClampedForDisplay() {
        assertTrue(QuotaWords.line(window("a", "窗口", 130d, millis(2026, Calendar.OCTOBER, 2, 13, 0)), NOW)
                .contains("已用 100%"));
        assertTrue(QuotaWords.line(window("a", "窗口", -20d, millis(2026, Calendar.OCTOBER, 2, 13, 0)), NOW)
                .contains("已用 0%"));
        assertEquals("窗口 · 已用 62% · 今天 12:30 重置",
                QuotaWords.line(window("a", "窗口", 61.7d, millis(2026, Calendar.OCTOBER, 2, 12, 30)), NOW));
    }

    @Test
    public void joinsWindowsInTheirGivenOrder() {
        List<QuotaWindow> windows = Arrays.asList(
                window("codex:300", "5 小时", 30d, millis(2026, Calendar.OCTOBER, 2, 18, 0)),
                window("codex:10080", "7 天", 70d, millis(2026, Calendar.OCTOBER, 8, 8, 0)));

        String text = QuotaWords.lines(windows, NOW);

        String[] lines = text.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0], lines[0].startsWith("5 小时"));
        assertTrue(lines[1], lines[1].startsWith("7 天"));
    }

    /** Empty means "hide the block", so the screen cannot show a heading over nothing. */
    @Test
    public void noWindowsIsAnEmptyAnswer() {
        assertEquals("", QuotaWords.lines(new ArrayList<QuotaWindow>(), NOW));
        assertEquals("", QuotaWords.lines(null, NOW));
        assertEquals("", QuotaWords.line(null, NOW));
        assertEquals("", QuotaWords.compact(null));
        assertEquals("", QuotaWords.compact(new ArrayList<QuotaWindow>()));
    }

    /**
     * The history form carries the percentages and nothing else: a row written
     * three days ago had its own deadline then, and phrasing it against today's
     * clock would turn a stored fact into a current claim.
     */
    @Test
    public void theCompactFormShowsUsageAndNoResetClause() {
        List<QuotaWindow> windows = Arrays.asList(
                window("codex:300", "5 小时", 31.4d, millis(2026, Calendar.OCTOBER, 2, 18, 22)),
                window("codex:10080", "7 天", 49.6d, millis(2026, Calendar.OCTOBER, 8, 8, 30)));

        assertEquals("5 小时 31% · 7 天 50%", QuotaWords.compact(windows));
        assertTrue("a reset clause in a history row is judged by the wrong clock",
                !QuotaWords.compact(windows).contains("重置"));
    }

    /** A window with no label falls back to its id, the same as the detail page. */
    @Test
    public void theCompactFormFallsBackToTheId() {
        assertEquals("codex:300 5%",
                QuotaWords.compact(Arrays.asList(window("codex:300", "", 5d, 0L))));
    }
}
