package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.util.Http;
import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherUsageTest {
    private static final String ACCOUNT = new String(new char[64]).replace('\0', 'c');
    private static final String BODY = "{\"schemaVersion\":1,\"providerId\":\"codex\",\"status\":\"OK\",\"accountId\":\"" + ACCOUNT + "\","
            + "\"updatedAt\":\"2026-10-08T08:00:00Z\",\"quotaWindows\":[{\"id\":\"weekly\","
            + "\"label\":\"每周限额\",\"usedPercent\":14,\"remainingPercent\":86,\"windowMinutes\":10080,"
            + "\"resetAt\":\"2026-10-14T05:12:00Z\"}]}";

    @Test public void launcherWindowsAndIsoResetAreMapped() throws Exception {
        UsageResult usage = BridgeUsageParser.parse(BODY, "local-id", 0);
        assertEquals(UsageStatus.OK, usage.getStatus());
        assertEquals(86, usage.getQuotaWindows().get(0).getRemainingPercent(), 0);
        assertEquals(10080, usage.getQuotaWindows().get(0).getWindowMinutes());
        assertTrue(usage.getQuotaWindows().get(0).getResetAt() > 0);
    }

    @Test public void readsOnlyPersistedAuthorizedRemoteAccount() throws Exception {
        BridgeCodexDataSource source = new BridgeCodexDataSource((url, headers, c, r) -> {
            fail("must use TLS transport"); return null;
        }, pin -> (url, headers, c, r) -> {
            assertEquals("https://192.168.1.2:38411/v1/accounts/" + ACCOUNT + "/usage", url);
            assertEquals("Bearer device", headers.get("Authorization"));
            return new Http.Response(200, BODY);
        });
        source.fetch("https://192.168.1.2:38411", "device", "cert-sha256:"
                + new String(new char[64]).replace('\0', 'a'), "local-id", 0, ACCOUNT);
    }

    @Test public void responseForAnotherAccountIsRejected() throws Exception {
        BridgeCodexDataSource source = new BridgeCodexDataSource((u,h,c,r) -> null,
                pin -> (u,h,c,r) -> new Http.Response(200, BODY.replace(ACCOUNT,
                        new String(new char[64]).replace('\0', 'd'))));
        try {
            source.fetch("https://192.168.1.2:38411", "device", "cert-sha256:"
                    + new String(new char[64]).replace('\0', 'a'), "local-id", 0, ACCOUNT);
            fail();
        } catch (com.aiusage.monitor.provider.UsageException expected) {
            assertTrue(expected.getMessage().contains("账户"));
        }
    }
}
