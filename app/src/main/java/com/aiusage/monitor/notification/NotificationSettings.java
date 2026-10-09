package com.aiusage.monitor.notification;

import com.aiusage.monitor.storage.AppSettings;

/** Notification preferences and event-enable timestamps stored in app_meta. */
public final class NotificationSettings {
    public static final String DEEPSEEK = "deepseek_peak";
    public static final String CODEX_FIVE_HOUR = CodexResetPlanner.FIVE_HOUR;
    public static final String CODEX_WEEKLY = CodexResetPlanner.WEEKLY;

    private static final String MASTER = "notifications.enabled";
    private static final String MASTER_SINCE = "notifications.enabled_since";
    private static final String CATEGORY_PREFIX = "notifications.category.";
    private static final String CATEGORY_SINCE_PREFIX = "notifications.category_since.";
    private static final String ACCOUNT_PREFIX = "notifications.codex_account.";
    private static final String LAST_SENT_PREFIX = "notifications.last_sent.";

    private final Store storage;

    public NotificationSettings(AppSettings storage) {
        this(new Store() {
            @Override public long getLong(String key, long fallback) {
                return storage.getLong(key, fallback);
            }
            @Override public void setLong(String key, long value) {
                storage.setLong(key, value);
            }
            @Override public String getString(String key, String fallback) {
                return storage.getString(key, fallback);
            }
            @Override public void setString(String key, String value) {
                storage.setString(key, value);
            }
        });
    }

    NotificationSettings(Store storage) { this.storage = storage; }

    interface Store {
        long getLong(String key, long fallback);
        void setLong(String key, long value);
        String getString(String key, String fallback);
        void setString(String key, String value);
    }

    public boolean isEnabled() { return storage.getLong(MASTER, 0) == 1; }

    public void setEnabled(boolean enabled, long now) {
        boolean before = isEnabled();
        if (enabled && !before) storage.setLong(MASTER_SINCE, now);
        storage.setLong(MASTER, enabled ? 1 : 0);
    }

    public long enabledSince() { return storage.getLong(MASTER_SINCE, Long.MAX_VALUE); }

    public boolean isCategoryEnabled(String category) {
        return storage.getLong(CATEGORY_PREFIX + category, 1) == 1;
    }

    public void setCategoryEnabled(String category, boolean enabled, long now) {
        if (enabled) storage.setLong(CATEGORY_SINCE_PREFIX + category, now);
        storage.setLong(CATEGORY_PREFIX + category, enabled ? 1 : 0);
    }

    public long categoryEnabledSince(String category) {
        return storage.getLong(CATEGORY_SINCE_PREFIX + category, 0);
    }

    /** Each Codex account has its own notification gate; accounts start enabled. */
    public boolean isCodexAccountEnabled(String accountId) {
        return storage.getLong(accountEnabledKey(accountId), 1) == 1;
    }

    public void setCodexAccountEnabled(String accountId, boolean enabled, long now) {
        boolean before = isCodexAccountEnabled(accountId);
        if (enabled && !before) {
            storage.setLong(accountEnabledSinceKey(accountId), now);
        }
        storage.setLong(accountEnabledKey(accountId), enabled ? 1 : 0);
    }

    public boolean isDeepSeekEnabled() {
        return isEnabled() && isCategoryEnabled(DEEPSEEK);
    }

    public long deepSeekEnabledSince() {
        return Math.max(enabledSince(), categoryEnabledSince(DEEPSEEK));
    }

    /** Returns follow, on, or off. Existing and new accounts inherit global settings. */
    public String codexMode(String accountId, String category) {
        return storage.getString(accountKey(accountId, category, "mode"), "follow");
    }

    public void setCodexMode(String accountId, String category, String mode, long now) {
        String normalized = "on".equals(mode) || "off".equals(mode) ? mode : "follow";
        storage.setLong(accountKey(accountId, category, "mode_since"), now);
        if ("on".equals(normalized)) {
            storage.setLong(accountKey(accountId, category, "enabled_since"), now);
        }
        storage.setString(accountKey(accountId, category, "mode"), normalized);
    }

    public boolean isCodexEnabled(String accountId, String category) {
        return effectiveCodexEnabled(isEnabled(), isCodexAccountEnabled(accountId),
                isCategoryEnabled(category), codexMode(accountId, category));
    }

    static boolean effectiveCodexEnabled(boolean notificationsEnabled, boolean accountEnabled,
                                         boolean categoryEnabled, String mode) {
        if (!notificationsEnabled || !accountEnabled) return false;
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return categoryEnabled;
    }

    public long codexEnabledSince(String accountId, String category) {
        long accountEnabledSince = storage.getLong(accountEnabledSinceKey(accountId), 0);
        if ("on".equals(codexMode(accountId, category))) {
            return Math.max(Math.max(enabledSince(), storage.getLong(
                    accountKey(accountId, category, "enabled_since"), 0)), accountEnabledSince);
        }
        return Math.max(Math.max(Math.max(enabledSince(), categoryEnabledSince(category)),
                        storage.getLong(accountKey(accountId, category, "mode_since"), 0)),
                accountEnabledSince);
    }

    public long lastSent(String stream) {
        return storage.getLong(LAST_SENT_PREFIX + stream, 0);
    }

    public void markSent(String stream, long atMillis) {
        storage.setLong(LAST_SENT_PREFIX + stream, atMillis);
    }

    private static String accountKey(String accountId, String category, String suffix) {
        return ACCOUNT_PREFIX + accountId + "." + category + "." + suffix;
    }

    private static String accountEnabledKey(String accountId) {
        return ACCOUNT_PREFIX + accountId + ".enabled";
    }

    private static String accountEnabledSinceKey(String accountId) {
        return ACCOUNT_PREFIX + accountId + ".enabled_since";
    }
}
