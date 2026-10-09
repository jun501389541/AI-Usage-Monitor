package com.aiusage.monitor.notification;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Guards the split between global notification controls and provider settings. */
public final class NotificationSettingsNavigationWiringTest {
    @Test public void overviewKeepsMasterAndForwardsAccountToSecondarySettings() throws IOException {
        String overview = read("app/src/main/java/com/aiusage/monitor/ui/notification/NotificationSettingsActivity.java");

        assertTrue(overview.contains("addToggle(globalCard, \"开启通知\""));
        assertTrue(overview.contains("平台与账户提醒设置"));
        assertTrue(overview.contains("NotificationProviderSettingsActivity.class"));
        assertTrue(overview.contains("providerSettingsButton.setOnClickListener(view -> openProviderSettings())"));
        assertTrue(overview.contains("startActivity(intent);"));
        assertTrue(overview.contains("putExtra(EXTRA_ACCOUNT_ID, accountId)"));
        assertTrue("provider and per-account switches belong on the secondary page",
                !overview.contains("deepSeekSwitch = addToggle")
                        && !overview.contains("codexFiveHourSwitch = addToggle")
                        && !overview.contains("graph.accountManager().list()"));
    }

    @Test public void secondaryPageGroupsProvidersAndPreservesCodexAccountOverrides() throws IOException {
        String secondary = read("app/src/main/java/com/aiusage/monitor/ui/notification/NotificationProviderSettingsActivity.java");
        String manifest = read("app/src/main/AndroidManifest.xml");

        int deepSeekLabel = secondary.indexOf("addProviderLabel(rulesCard, \"DEEPSEEK\", 14);");
        int deepSeekToggle = secondary.indexOf("addToggle(rulesCard, \"峰谷切换\"");
        int codexLabel = secondary.indexOf("addProviderLabel(rulesCard, \"CODEX\", 22);");
        int codexToggle = secondary.indexOf("addToggle(rulesCard, \"5 小时额度重置\"");
        int codexAccountLabel = secondary.indexOf("addProviderLabel(rulesCard, \"CODEX 账户\", 22);");
        int codexAccountToggle = secondary.indexOf("addToggle(rulesCard, codexAccount.getDisplayName()");
        assertTrue("DeepSeek's small provider label should precede its toggle",
                deepSeekLabel >= 0 && deepSeekLabel < deepSeekToggle);
        assertTrue("Codex's separated provider label should precede its toggles",
                codexLabel > deepSeekToggle && codexLabel < codexToggle);
        assertTrue("Codex account switches should be labeled as a distinct subsection",
                codexAccountLabel > codexToggle && codexAccountLabel < codexAccountToggle);
        assertTrue(secondary.contains("UiKit.text(this, label, 10, UiKit.COLOR_HINT, Typeface.BOLD)"));
        assertTrue(secondary.contains("NotificationSettings.DEEPSEEK"));
        assertTrue(secondary.contains("NotificationSettings.CODEX_FIVE_HOUR"));
        assertTrue(secondary.contains("NotificationSettings.CODEX_WEEKLY"));
        assertTrue(secondary.contains("setCodexAccountEnabled"));
        assertTrue(secondary.contains("addOverrideRow"));
        assertTrue(secondary.contains("EXTRA_ACCOUNT_ID"));
        assertTrue(manifest.contains("NotificationProviderSettingsActivity")
                && manifest.contains("android:exported=\"false\""));
    }

    private static String read(String relativePath) throws IOException {
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
