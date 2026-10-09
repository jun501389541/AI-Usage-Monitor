package com.aiusage.monitor.util;

import com.aiusage.monitor.model.LimitResetCredits;
import com.aiusage.monitor.model.LimitResetCreditsStatus;
import com.aiusage.monitor.usage.LimitResetCreditsJson;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class LimitResetWordsTest {
    private static final long NOW = Instant.parse("2026-10-08T08:00:00Z").toEpochMilli();
    private static LimitResetCredits.Credit credit(String expiry) throws Exception {
        return LimitResetCreditsJson.decode(new JSONObject("{\"availableCount\":1,\"credits\":[{"
                + "\"resetType\":\"codexRateLimits\",\"status\":\"available\",\"title\":\"Full reset (Weekly + 5 hr)\"" + expiry + "}]}" )).getCredits().get(0);
    }
    @Test public void screenshotDateUsesChinaFallbackAndCurrentLocalZone() throws Exception {
        LimitResetCredits.Credit row = credit(",\"expiresAt\":\"2026-11-06T20:00:06Z\"");
        assertEquals("完全重置（每周 + 5 小时）", LimitResetWords.title(row));
        assertEquals("2026-11-07 04:00 到期", LimitResetWords.expiry(row, NOW, null));
        assertEquals("2026-11-06 20:00 到期 (UTC+00:00)", LimitResetWords.expiry(row, NOW, TimeZone.getTimeZone("UTC")));
    }
    @Test public void missingNullAndInvalidExpiryStayDifferent() throws Exception {
        assertEquals("到期时间未知", LimitResetWords.expiry(credit(""), NOW, null));
        assertEquals("到期时间未知", LimitResetWords.expiry(credit(",\"expiresAt\":\"invalid\""), NOW, null));
        assertEquals("无到期限制", LimitResetWords.expiry(credit(",\"expiresAt\":null"), NOW, null));
    }
    @Test public void localExpiryUsesDstAtExpiryAndFractionalHourOffsets() throws Exception {
        LimitResetCredits.Credit row = credit(",\"expiresAt\":\"2026-11-06T20:00:06Z\"");
        assertEquals("2026-11-06 15:00 到期 (UTC-05:00)", LimitResetWords.expiry(row, NOW, TimeZone.getTimeZone("America/New_York")));
        assertEquals("2026-11-07 01:30 到期 (UTC+05:30)", LimitResetWords.expiry(row, NOW, TimeZone.getTimeZone("Asia/Kolkata")));
    }
    @Test public void expiredAndUnknownTypesAreNotAdvertisedAsUsableFullResets() throws Exception {
        assertTrue(LimitResetWords.expiry(credit(",\"expiresAt\":\"2026-10-01T00:00:00Z\""), NOW, null).startsWith("已到期"));
        LimitResetCredits.Credit unknown = new LimitResetCredits.Credit("futureType", "unknown", null, null, false, "", "");
        assertEquals("重置范围未知", LimitResetWords.title(unknown));
        assertEquals("状态未知", LimitResetWords.status(unknown));
    }
    @Test public void unknownAndStaleCountsRemainExplicit() {
        assertEquals("暂不可用", LimitResetWords.count(null, false));
        assertEquals("可用 0 次", LimitResetWords.count(new LimitResetCredits(0, null), false));
        assertEquals("可用 1 次 · 上次读数", LimitResetWords.count(new LimitResetCredits(1, null), true));
    }
    @Test public void diagnosisSeparatesOldBridgeMissingDataAndInvalidFormat() {
        assertEquals("电脑端 Bridge 未提供重置卡诊断，请更新并重启电脑端",
                LimitResetWords.diagnostic(LimitResetCreditsStatus.LEGACY_BRIDGE));
        assertEquals("电脑端本次未返回可用次数",
                LimitResetWords.diagnostic(LimitResetCreditsStatus.NOT_RETURNED));
        assertEquals("电脑端返回的重置卡格式无法识别",
                LimitResetWords.diagnostic(LimitResetCreditsStatus.INVALID_FORMAT));
        assertEquals("本次未返回重置卡信息", LimitResetWords.diagnostic(null));
    }
    @Test public void resetTypeAloneDoesNotInventWhichWindowsAreRestored() {
        LimitResetCredits.Credit untitled = new LimitResetCredits.Credit("codexRateLimits", "available", null, null, false, "", "");
        assertEquals("Codex 限额重置", LimitResetWords.title(untitled));
        LimitResetCredits.Credit weekly = new LimitResetCredits.Credit("codexRateLimits", "available", null, null, false, "Weekly reset", "");
        assertEquals("Weekly reset", LimitResetWords.title(weekly));
    }
}
