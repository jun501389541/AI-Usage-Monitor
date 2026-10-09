package com.aiusage.monitor.util;

import com.aiusage.monitor.model.LimitResetCredits;
import com.aiusage.monitor.model.LimitResetCreditsStatus;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class LimitResetWords {
    private LimitResetWords() { }
    public static String count(LimitResetCredits resets, boolean stale) {
        if (resets == null) return "暂不可用";
        return "可用 " + resets.getAvailableCount() + " 次" + (stale ? " · 上次读数" : "");
    }
    public static String diagnostic(LimitResetCreditsStatus status) {
        if (status == null) return "本次未返回重置卡信息";
        switch (status) {
            case LEGACY_BRIDGE: return "电脑端 Bridge 未提供重置卡诊断，请更新并重启电脑端";
            case NOT_RETURNED: return "电脑端本次未返回可用次数";
            case INVALID_FORMAT: return "电脑端返回的重置卡格式无法识别";
            case AVAILABLE: return "重置卡信息暂不可用";
            default: return "本次未返回重置卡信息";
        }
    }
    public static String title(LimitResetCredits.Credit credit) {
        String title = credit.getTitle().trim();
        if ("Full reset (Weekly + 5 hr)".equalsIgnoreCase(title)) return "完全重置（每周 + 5 小时）";
        if (!title.isEmpty()) return title;
        return "codexRateLimits".equals(credit.getResetType()) ? "Codex 限额重置" : "重置范围未知";
    }
    public static String status(LimitResetCredits.Credit credit) {
        switch (credit.getStatus()) {
            case "available": return "";
            case "redeeming": return "处理中";
            case "redeemed": return "已使用";
            default: return "状态未知";
        }
    }
    public static String expiry(LimitResetCredits.Credit credit, long now, TimeZone timezone) {
        if (!credit.isExpiryKnown()) return "到期时间未知";
        if (credit.getExpiresAt() == null) return "无到期限制";
        TimeZone zone = timezone == null ? TimeZone.getTimeZone("Asia/Shanghai") : timezone;
        Date date = new Date(credit.getExpiresAt());
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        format.setTimeZone(zone);
        String offset = QuotaWords.utcOffsetAt(zone, credit.getExpiresAt());
        String suffix = "Asia/Shanghai".equals(zone.getID()) ? "" : " (UTC" + offset + ")";
        return (credit.getExpiresAt() <= now ? "已到期 · " : "") + format.format(date)
                + (credit.getExpiresAt() > now ? " 到期" : "") + suffix;
    }
}
