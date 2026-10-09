package com.aiusage.monitor.util;

import com.aiusage.monitor.model.QuotaWindow;
import java.util.List;

/** Selects the account meter rather than a model-specific bucket with the same duration. */
public final class CodexQuotaWindows {
    private CodexQuotaWindows() { }

    public static QuotaWindow find(List<QuotaWindow> windows, long minutes) {
        if (windows == null) return null;
        QuotaWindow unique = null;
        for (QuotaWindow window : windows) {
            if (window == null || window.getWindowMinutes() != minutes) continue;
            String id = window.getId();
            if (id.startsWith("codex:") || "primary_window".equals(id)
                    || "secondary_window".equals(id)) return window;
            // Legacy payloads used unscoped IDs. Explicit non-Codex buckets are
            // model meters and must not be relabelled as the account quota.
            if (!id.contains(":") && unique == null) unique = window;
        }
        return unique;
    }
}
