package com.aiusage.monitor.util;

import com.aiusage.monitor.model.QuotaWindow;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CodexQuotaWindowsTest {
    private QuotaWindow window(String id, long minutes) {
        return new QuotaWindow(id, id, 0, 100, minutes, 12345);
    }
    @Test public void selectsAccountBucketRegardlessOfModelOrder() {
        QuotaWindow main = window("codex:300", 300);
        QuotaWindow model = window("codex_extra:300", 300);
        assertSame(main, CodexQuotaWindows.find(Arrays.asList(model, main), 300));
        assertSame(main, CodexQuotaWindows.find(Arrays.asList(main, model), 300));
    }
    @Test public void doesNotRelabelModelOnlyQuota() {
        assertNull(CodexQuotaWindows.find(Arrays.asList(window("model:10080", 10080)), 10080));
    }
    @Test public void acceptsDirectWindowsByActualDuration() {
        QuotaWindow weekly = window("primary_window", 10080);
        assertSame(weekly, CodexQuotaWindows.find(Arrays.asList(weekly), 10080));
        assertNull(CodexQuotaWindows.find(Arrays.asList(weekly), 300));
    }
    @Test public void keepsLegacyUnscopedWindowSupport() {
        QuotaWindow legacy = window("quota_5h", 300);
        assertSame(legacy, CodexQuotaWindows.find(Arrays.asList(legacy), 300));
    }
}
