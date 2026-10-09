package com.aiusage.monitor.notification;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Guards notification controls against fixed heights that clip enlarged text. */
public final class NotificationSettingsLayoutWiringTest {
    @Test public void permissionActionsAndAccountOverridesWrapToTheirContent() throws IOException {
        String source = readActivity();

        assertFalse("permission buttons must grow with text instead of staying 46dp tall",
                source.contains("UiKit.matchHeight(this, 46, 8)"));
        assertTrue("system permission buttons should use their padded content height",
                source.contains("systemCard.addView(notificationSettingsButton, UiKit.matchWrap(this, 8));")
                        && source.contains("systemCard.addView(exactAlarmButton, UiKit.matchWrap(this, 8));"));
        assertFalse("account override buttons must not use a fixed 42dp height",
                source.contains("new LinearLayout.LayoutParams(-2, UiKit.dp(this, 42))"));
    }

    private static String readActivity() throws IOException {
        String relativePath = "app/src/main/java/com/aiusage/monitor/ui/notification/NotificationSettingsActivity.java";
        File fromRoot = new File(System.getProperty("user.dir"), relativePath);
        File fromModule = new File(System.getProperty("user.dir"), relativePath.substring("app/".length()));
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;
        if (!candidate.isFile()) {
            fail("cannot find " + relativePath + " from " + System.getProperty("user.dir"));
        }
        return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
