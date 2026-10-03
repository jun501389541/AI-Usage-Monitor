package com.aiusage.monitor.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The detail screen has to actually ask for the quota wording. MainActivity needs
 * an Android context so it cannot be instantiated on a host JVM; the wiring is
 * therefore pinned by reading its source, the way the ProviderRegistry and
 * SqliteCredentialStore guards do.
 *
 * <p>What matters is not only that the call exists but that an empty window list
 * hides the block: a heading over nothing is the failure mode this asserts
 * against, and the device screenshot in step 6 is what proves it renders.
 */
public class QuotaRenderingWiringTest {

    @Test
    public void detailScreenRendersQuotaWindowsFromTheSharedWording() throws IOException {
        String source = read("app/src/main/java/com/aiusage/monitor/ui/MainActivity.java");

        assertTrue("MainActivity must render quota windows through QuotaWords",
                source.contains("QuotaWords.lines(result.getQuotaWindows()"));
        assertTrue("an empty window list must hide the block rather than head it",
                source.contains("quotaLines.isEmpty()"));
    }

    /**
     * The history list is the second surface that renders a stored reading, and it
     * was the one that never learned a reading can be percentages: it asked for
     * {@code getBalance()} and printed an em dash for every Codex row.
     */
    @Test
    public void historyRowsAskWhatAReadingIsAllowedToShow() throws IOException {
        String source = read("app/src/main/java/com/aiusage/monitor/ui/MainActivity.java");

        int historyMethod = source.indexOf("private void renderRecentReadings()");
        int sharedDecision = source.indexOf("ReadingWords.value(result)", historyMethod);
        assertTrue("renderRecentReadings must exist", historyMethod > 0);
        assertTrue("each row's value comes from the shared decision, not a local guess",
                sharedDecision > historyMethod);
        assertTrue("the balance-only read must not come back: it is what made a "
                        + "successful Codex reading look like a missing one",
                !source.contains("result.getBalance() == null\n                    ? Money.EMPTY"));
    }

    private static String read(String relativePath) throws IOException {
        File fromRoot = new File(System.getProperty("user.dir"), relativePath);
        File fromModule = new File(System.getProperty("user.dir"),
                relativePath.startsWith("app/") ? relativePath.substring("app/".length()) : relativePath);
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;
        if (!candidate.isFile()) {
            fail("cannot find " + relativePath + " from " + System.getProperty("user.dir")
                    + " - this guard would be scanning nothing");
        }
        String text = new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
        assertTrue("wrong file scanned: " + candidate, text.contains("class MainActivity"));
        return text;
    }
}
