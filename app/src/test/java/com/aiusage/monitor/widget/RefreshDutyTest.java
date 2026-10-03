package com.aiusage.monitor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Which alarm fires decides whether the platform gets queried at all.
 *
 * <p>Midnight changes what "today" means, not what was read: the widget's daily
 * figure comes from the stored rows, and a day with no row reads as zero. The
 * receiver used to test {@code midnight || WidgetUpdateManager.hasWidgets(...)}, so
 * a phone with every widget removed still queried all accounts and pruned once a
 * day behind an alarm it re-armed itself (docs/PHASE-0-7-REVIEW.md §2.2).
 *
 * <p>The rule lives in {@link RefreshDuty} because that is the part a host JVM can
 * test; the receiver needs an Android context, so its wiring is pinned by reading
 * its source, the way {@code BridgeQueryPathWiringTest} and the ProviderRegistry
 * guards do.
 */
public class RefreshDutyTest {

    @Test
    public void midnightWithAWidgetRedrawsAndAsksThePlatformForNothing() {
        assertEquals(RefreshDuty.REDRAW_ONLY, RefreshDuty.forAlarm(true, true));
        assertTrue("a redraw must not be spelled as a refresh, or the distinction "
                        + "this class exists for is one enum constant wide",
                RefreshDuty.REDRAW_ONLY != RefreshDuty.REFRESH_AND_REDRAW);
    }

    @Test
    public void midnightWithoutAWidgetHasNoDutyAtAll() {
        assertEquals(RefreshDuty.NOTHING, RefreshDuty.forAlarm(true, false));
    }

    @Test
    public void theIntervalRefreshStillFetchesWhileAWidgetIsShown() {
        assertEquals(RefreshDuty.REFRESH_AND_REDRAW, RefreshDuty.forAlarm(false, true));
    }

    /**
     * The control on the other side of the midnight fix: the interval alarm was
     * already widget-gated, and re-arranging the guard so that only midnight
     * respects it would quietly stop background updates.
     */
    @Test
    public void theIntervalRefreshAlsoStopsWithoutAWidget() {
        assertEquals(RefreshDuty.NOTHING, RefreshDuty.forAlarm(false, false));
    }

    @Test
    public void theReceiverAsksTheDutyInsteadOfOringTheTwoTriggers() throws IOException {
        String source = read("widget/WidgetRefreshReceiver.java", "class WidgetRefreshReceiver");

        assertTrue("the receiver must consult RefreshDuty, the only place the two "
                        + "triggers are told apart",
                source.contains("RefreshDuty.forAlarm(midnight,"));
        assertEquals("the old `midnight || hasWidgets` shape must not come back: it "
                                + "made midnight query the platform whatever the screen held",
                0, count(source, "midnight || WidgetUpdateManager.hasWidgets"));
        assertTrue("and the redraw-only branch has to exist next to the refresh branch",
                source.contains("duty == RefreshDuty.REDRAW_ONLY"));
    }

    @Test
    public void theMidnightAlarmIsArmedOnlyWhileAWidgetExists() throws IOException {
        String source = read("widget/WidgetRefreshScheduler.java", "class WidgetRefreshScheduler");

        int guard = source.indexOf("public static void scheduleMidnight(Context context) {\n"
                + "        // Same guard");
        int widgetCheck = source.indexOf("if (!WidgetUpdateManager.hasWidgets(context))", guard);
        int alarmUse = source.indexOf("getSystemService(Context.ALARM_SERVICE)", guard);
        assertTrue("scheduleMidnight must exist", guard > 0);
        assertTrue("it must refuse to arm when no widget is shown", widgetCheck > guard);
        assertTrue("and the refusal has to come before the alarm manager is touched, "
                        + "so no caller - boot, the app's start-up or the receiver - "
                        + "can re-create the daily chain by forgetting to check",
                widgetCheck < alarmUse);
        assertTrue("the last widget has to take the midnight alarm down with it",
                source.contains("public static void cancelMidnight(Context context)"));
    }

    private static String read(String relative, String marker) throws IOException {
        String path = "app/src/main/java/com/aiusage/monitor/" + relative;
        File fromRoot = new File(System.getProperty("user.dir"), path);
        File fromModule = new File(System.getProperty("user.dir"), path.substring("app/".length()));
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;
        if (!candidate.isFile()) {
            fail("cannot find " + path + " from " + System.getProperty("user.dir")
                    + " - this guard would be scanning nothing");
        }
        String text = new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue("wrong file scanned: " + candidate, text.contains(marker));
        return text;
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
