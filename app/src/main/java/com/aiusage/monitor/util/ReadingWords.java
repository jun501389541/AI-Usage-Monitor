package com.aiusage.monitor.util;

import com.aiusage.monitor.model.UsageResult;

/**
 * The one number a stored reading is allowed to show for itself.
 *
 * <p>Two providers, two shapes: DeepSeek answers with a balance, Codex with quota
 * windows and no balance at all (Spec §11, and {@code UsageResult}'s own comment on
 * the two). A surface that reads only the balance renders every Codex history row
 * as an em dash beside a timestamp — the reading succeeded, the number was simply
 * not money (docs/PHASE-0-7-REVIEW.md §2.4).
 *
 * <p>Decided here rather than inline so the rule is one function with tests instead
 * of a ternary per call site.
 */
public final class ReadingWords {

    private ReadingWords() {
    }

    /**
     * @param result a decoded snapshot, or null when the row could not be decoded
     * @return the balance, else the quota windows, else {@link Money#EMPTY} — which
     *         is now the only case where the dash is the honest answer
     */
    public static String value(UsageResult result) {
        if (result == null) {
            return Money.EMPTY;
        }
        if (result.getBalance() != null) {
            return Money.format(result.getBalance());
        }
        String windows = QuotaWords.compact(result.getQuotaWindows());
        return windows.isEmpty() ? Money.EMPTY : windows;
    }
}
