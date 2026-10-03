package com.aiusage.monitor.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;

/**
 * One stored reading, one honest value.
 *
 * <p>The history list read {@code getBalance()} and nothing else, so every
 * successful Codex row — a provider that reports percentages and has no balance at
 * all — rendered as an em dash beside a timestamp, and the quota windows the app had
 * actually stored stayed hidden (docs/PHASE-0-7-REVIEW.md §2.4). The dash is still
 * the right answer for a row that carries neither shape; what is not allowed is a
 * dash for a number that exists.
 */
public class ReadingWordsTest {

    private static QuotaWindow window(String id, String label, double used) {
        return new QuotaWindow(id, label, used, 100d - used, 300L, 0L);
    }

    @Test
    public void aBalanceReadsAsMoneyAsItAlwaysDid() {
        UsageResult result = UsageResult.builder()
                .status(UsageStatus.OK)
                .balance(new Balance(24.94d, "CNY", "24.94"))
                .updatedAt(1_700_000_000_000L)
                .build();

        assertEquals("¥24.94", ReadingWords.value(result));
    }

    @Test
    public void aQuotaOnlyReadingShowsItsWindowsInsteadOfADash() {
        UsageResult result = UsageResult.builder()
                .status(UsageStatus.OK)
                .quotaWindows(Arrays.asList(
                        window("codex:300", "5 小时", 31d),
                        window("codex:10080", "7 天", 50d)))
                .updatedAt(1_700_000_000_000L)
                .build();

        assertEquals("5 小时 31% · 7 天 50%", ReadingWords.value(result));
        assertTrue("the em dash must not survive next to real numbers",
                !ReadingWords.value(result).contains(Money.EMPTY));
    }

    /**
     * The control: with neither a balance nor windows there is genuinely no number,
     * and the dash is the truth. A "fix" that showed 0% here would be inventing a
     * reading.
     */
    @Test
    public void aReadingWithNeitherShapeStillSaysNothing() {
        UsageResult empty = UsageResult.builder()
                .status(UsageStatus.OK)
                .quotaWindows(new ArrayList<QuotaWindow>())
                .updatedAt(1_700_000_000_000L)
                .build();

        assertEquals(Money.EMPTY, ReadingWords.value(empty));
        assertEquals(Money.EMPTY, ReadingWords.value(null));
    }

    /** A balance wins when a source ever reports both: money is the headline. */
    @Test
    public void balanceTakesPrecedenceOverWindows() {
        UsageResult both = UsageResult.builder()
                .status(UsageStatus.OK)
                .balance(new Balance(1d, "CNY", "1.00"))
                .quotaWindows(Arrays.asList(window("codex:300", "5 小时", 12d)))
                .updatedAt(Calendar.getInstance().getTimeInMillis())
                .build();

        assertEquals("¥1.00", ReadingWords.value(both));
    }
}
