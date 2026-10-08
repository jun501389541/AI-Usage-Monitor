package com.aiusage.monitor.bridge;

import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.auth.BridgeAuthAdapter;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.util.Http;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class LauncherPairingTest {
    static final String ID = "1c187fe2-61c5-4c6d-9fbc-028749d20b76";
    static final String ACCOUNT = new String(new char[64]).replace('\0', 'b');
    static final String TOKEN = new String(new char[43]).replace('\0', 'A');
    static final String PIN = new String(new char[64]).replace('\0', 'a');

    static String invitation(String endpoint) throws Exception {
        JSONObject json = new JSONObject().put("schemaVersion", 1).put("bridgeId", ID)
                .put("endpoint", endpoint).put("certificateSha256", PIN.toUpperCase())
                .put("pairToken", TOKEN).put("expiresAt", "2030-01-01T00:00:00Z");
        return "https://127.0.0.1:38412/v1/device#" + URLEncoder.encode(json.toString(), "UTF-8");
    }

    @Test public void invitationIsExplicitlyCertificatePinned() throws Exception {
        LauncherInvitation offer = LauncherInvitation.parse(invitation("https://127.0.0.1:38412"));
        assertEquals("cert-sha256:" + PIN, offer.certificatePin);
        assertEquals(ID, offer.bridgeId);
    }

    @Test public void endpointCannotMoveTokenToAnotherHost() throws Exception {
        try { LauncherInvitation.parse(invitation("https://192.168.1.6:38412")); fail(); }
        catch (PairingPayload.Invalid expected) { }
    }

    @Test public void credentialsKeepRemoteAccountId() throws Exception {
        AuthContext auth = new BridgeAuthAdapter().adapt(CredentialPayload.forRemoteDevice(TOKEN, ACCOUNT));
        assertEquals(ACCOUNT, auth.get(AuthContext.KEY_REMOTE_ACCOUNT_ID));
        assertEquals(TOKEN, auth.get(AuthContext.KEY_DEVICE_TOKEN));
    }

    @Test public void expiredInvitationNeverSendsPairToken() throws Exception {
        LauncherPairingClient client = new LauncherPairingClient((a,b,c,d,e,f) -> {
            fail("Expired invitation must not contact desktop"); return null;
        }, () -> { }, () -> Long.MAX_VALUE, AddressResolver.REAL_DEVICE);
        try {
            client.pair(LauncherInvitation.parse(invitation("https://127.0.0.1:38412")), "Phone", message -> { });
            fail();
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("过期")); }
    }

    @Test public void approvalIsPolledAndAckFollowsStorage() throws Exception {
        List<String> calls = new ArrayList<>();
        final int[] polls = {0};
        LauncherPairingClient client = new LauncherPairingClient((endpoint, path, method, body, bearer, pin) -> {
            calls.add(method + " " + path);
            assertEquals("cert-sha256:" + PIN, pin.value());
            if ("/v1/pair".equals(path)) return new Http.Response(202,
                    "{\"pairId\":\"" + ID + "\",\"sessionToken\":\"" + TOKEN
                            + "\",\"state\":\"PendingApproval\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}");
            if (path.endsWith("/ack")) return new Http.Response(204, "");
            if ("/v1/accounts".equals(path)) return new Http.Response(200,
                    "[{\"providerId\":\"codex\",\"accountId\":\"" + ACCOUNT + "\",\"displayName\":\"测试\"}]");
            return new Http.Response(200, "{\"pairId\":\"" + ID + "\",\"state\":\""
                    + (++polls[0] == 1 ? "PendingApproval" : "Approved") + "\",\"deviceId\":\""
                    + ID + "\",\"deviceToken\":\"" + TOKEN + "\"}");
        }, () -> { }, () -> 1000, AddressResolver.REAL_DEVICE);
        PairingClient.Paired paired = client.pair(LauncherInvitation.parse(invitation("https://127.0.0.1:38412")),
                "Android", message -> assertTrue(message.contains("批准")));
        assertEquals(ACCOUNT, paired.remoteAccountId());
        assertEquals(2, polls[0]);
        assertFalse(calls.contains("POST /v1/pair/" + ID + "/ack"));
        paired.acknowledge();
        assertEquals("POST /v1/pair/" + ID + "/ack", calls.get(calls.size() - 1));
    }
}
