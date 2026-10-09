package com.aiusage.monitor.bridge;

import com.aiusage.monitor.model.*;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.provider.codex.BridgeCodexDataSource;
import com.aiusage.monitor.usage.UsageSnapshotCodec;
import com.aiusage.monitor.util.Http;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.net.ssl.HttpsURLConnection;

/** Opt-in: uses production Android TLS/client code against a live synthetic Windows bridge. */
public class LiveLauncherInteropTest {
    private Path directory;
    private void command(String value) throws Exception {
        Path done = directory.resolve("command-result.txt");
        Files.deleteIfExists(done);
        Path temp = directory.resolve("command.tmp");
        Files.write(temp, value.getBytes(StandardCharsets.UTF_8));
        Files.move(temp, directory.resolve("command.txt"), StandardCopyOption.REPLACE_EXISTING);
        long deadline = System.currentTimeMillis() + 10000;
        while (!Files.exists(done) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue("Synthetic command did not complete", Files.exists(done));
        assertEquals(value, new String(Files.readAllBytes(done), StandardCharsets.UTF_8));
    }
    private UsageResult fetch(PairingClient.Paired paired) throws Exception {
        return new BridgeCodexDataSource(Http::get).fetch(paired.bridge().getBaseUrl(),
                paired.deviceToken(), paired.bridge().getFingerprint(), "synthetic-local",
                System.currentTimeMillis(), paired.remoteAccountId());
    }
    @Test public void currentWindowsBridgeAndAndroidClientInteroperate() throws Exception {
        String root = System.getenv("CODEX_INTEROP_DIR");
        assumeTrue("Requires running CodexLauncher synthetic interop fixture", root != null && !root.isEmpty());
        directory = Paths.get(root);
        LauncherInvitation invitation = LauncherInvitation.parse(new String(
                Files.readAllBytes(directory.resolve("desktop-invitation.txt")), StandardCharsets.UTF_8));
        try {
            new PinnedBridgeTransport(new FingerprintPin("cert-sha256:" + String.join("", Collections.nCopies(64, "0"))))
                    .get(invitation.endpoint + "/v1/health", Collections.emptyMap(), 3000, 3000);
            fail("Wrong certificate pin was accepted");
        } catch (java.io.IOException expected) { }
        final int[] waiting = {0};
        PairingClient.Paired paired = new LauncherPairingClient(AddressResolver.REAL_DEVICE)
                .pair(invitation, "Live Android JVM fixture", progress -> waiting[0]++);
        assertTrue("Approval wait was not reported", waiting[0] > 0);
        paired.acknowledge();
        UsageResult multiple = fetch(paired);
        assertEquals(UsageStatus.OK, multiple.getStatus());
        assertEquals(78, multiple.getQuotaWindows().get(0).getRemainingPercent(), 0);
        assertEquals(19, multiple.getQuotaWindows().get(1).getRemainingPercent(), 0);
        assertEquals(3, multiple.getLimitResetCredits().getAvailableCount());
        assertEquals(2, multiple.getLimitResetCredits().getCredits().size());
        assertTrue(multiple.getLimitResetCredits().getCredits().get(1).isExpiryKnown());
        assertNull(multiple.getLimitResetCredits().getCredits().get(1).getExpiresAt());
        String cached = UsageSnapshotCodec.encode(multiple);
        assertFalse("Original reset credit identifiers leaked", cached.contains("synthetic-never-export"));
        assertEquals(3, UsageSnapshotCodec.decode(cached).asStale().getLimitResetCredits().getAvailableCount());
        HttpsURLConnection refresh = BridgeTls.openUrl(paired.bridge().getBaseUrl()
                + "/v1/accounts/" + paired.remoteAccountId() + "/refresh", new FingerprintPin(paired.bridge().getFingerprint()));
        try {
            refresh.setRequestMethod("POST"); refresh.setRequestProperty("Authorization", "Bearer " + paired.deviceToken());
            int code = refresh.getResponseCode(); assertTrue("Refresh request rejected", code == 200 || code == 202);
        } finally { refresh.disconnect(); }
        command("zero"); assertEquals(0, fetch(paired).getLimitResetCredits().getAvailableCount());
        command("count-only"); UsageResult count = fetch(paired);
        assertEquals(2, count.getLimitResetCredits().getAvailableCount()); assertNull(count.getLimitResetCredits().getCredits());
        command("missing"); UsageResult missing = fetch(paired);
        assertNull(missing.getLimitResetCredits()); assertEquals(LimitResetCreditsStatus.NOT_RETURNED, missing.getLimitResetCreditsStatus());
        command("invalid"); assertEquals(LimitResetCreditsStatus.INVALID_FORMAT, fetch(paired).getLimitResetCreditsStatus());
        command("multiple"); command("offline"); UsageResult stale = fetch(paired);
        assertEquals(UsageStatus.STALE, stale.getStatus()); assertEquals(3, stale.getLimitResetCredits().getAvailableCount());
        command("switch-account");
        try { fetch(paired); fail("Changed account remained authorized"); }
        catch (UsageException expected) { assertEquals(UsageError.BRIDGE_UNAUTHORIZED, expected.getError()); }
        command("revoke");
        try { fetch(paired); fail("Revoked device remained authorized"); }
        catch (UsageException expected) { assertEquals(UsageError.BRIDGE_UNAUTHORIZED, expected.getError()); }
        command("multiple"); command("renew");
    }
}