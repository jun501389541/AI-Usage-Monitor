package com.aiusage.monitor.util;

import com.aiusage.monitor.model.UsageStatus;

/**
 * The one wording for a {@link UsageStatus}, shared by the account list and the
 * widgets. Spec §40.
 *
 * <p>Two surfaces describing the same stored reading in two different phrasings
 * is a bug with a name — the list once said "账户可用" while its own widget said
 * "数据已过期" about the same row. The staleness <em>judgement</em> already lives
 * in one place ({@code AccountView.displayStatus}); this is the wording half of
 * the same rule.
 */
public final class StatusWords {

    /** Shown when an account has never been fetched at all. */
    public static final String NEVER_QUERIED = "尚未查询";

    /**
     * Marks a value that is real but not current: the newest attempt failed and
     * what is displayed is an older success. Spec §39 forbids clearing it, so the
     * wording has to say the same thing the screen means.
     */
    public static final String RETAINED_SUFFIX = " · 最后成功数据";

    private StatusWords() {
    }

    /** The label for a status, with no retained-data marker. */
    public static String describe(UsageStatus status) {
        return describe(status, false);
    }

    public static String describe(UsageStatus status, boolean retained) {
        String suffix = retained ? RETAINED_SUFFIX : "";
        if (status == null) {
            return NEVER_QUERIED;
        }
        switch (status) {
            case OK:
                return "账户可用" + suffix;
            case STALE:
                return "数据已过期" + suffix;
            case NETWORK_ERROR:
                return "网络连接失败" + suffix;
            case AUTH_REQUIRED:
                return "API Key 无效或已失效" + suffix;
            case BRIDGE_OFFLINE:
                return "Bridge 未连接" + suffix;
            case BRIDGE_AUTH_REQUIRED:
                return "电脑端授权已失效" + suffix;
            case BRIDGE_PAIRING_REQUIRED:
                // No "重新" here: this state means there is no pairing to redo, which
                // is a different instruction than the one above, and the two must not
                // collapse into the same sentence (Spec §53 rule 19).
                return "尚未与这台电脑配对";
            case REFRESHING:
                return "正在刷新" + suffix;
            case NO_DATA:
            default:
                // NO_DATA never carries the suffix: there is no older value on
                // screen to retain, so claiming one would be the opposite of true.
                return NEVER_QUERIED;
        }
    }
}
