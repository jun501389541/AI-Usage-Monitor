package com.aiusage.monitor;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Phase 0 gate for the {@code org.json} question (plan §7.3).
 *
 * <p>The {@code android.jar} shipped with the SDK contains {@code org.json.*}
 * classes, but every method body throws ({@code Stub!}). A host-side unit test
 * that touches JSON would therefore fail with "Method ... not mocked" unless a
 * real implementation is on the test classpath. The test dependency
 * {@code org.json:json} (testImplementation only, never in the APK) supplies
 * one.
 *
 * <p>If this test passes, {@code SnapshotCodec} and {@code DeepSeekProvider}
 * may use {@code org.json} and {@code SnapshotCodecTest} can be written
 * normally. If it fails, the fallback is a hand-written deterministic codec
 * ({@code storage/JsonText}) and DeepSeek response parsing is covered only by
 * the on-device smoke test.
 */
public class OrgJsonProbeTest {

    @Test
    public void realOrgJsonIsOnTheTestClasspath() throws Exception {
        JSONObject object = new JSONObject("{\"a\":1}");
        assertEquals(1, object.getInt("a"));
    }

    @Test
    public void parsesTheDeepSeekBalanceShape() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"38.52\","
                + "\"granted_balance\":\"0.00\",\"topped_up_balance\":\"38.52\"}]}";

        JSONObject root = new JSONObject(body);
        assertEquals(true, root.optBoolean("is_available", false));

        JSONObject cny = root.getJSONArray("balance_infos").getJSONObject(0);
        assertEquals("CNY", cny.optString("currency", ""));
        assertEquals("38.52", cny.optString("total_balance", "0").trim());
    }
}
