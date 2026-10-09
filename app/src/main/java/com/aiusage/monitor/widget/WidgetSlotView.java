package com.aiusage.monitor.widget;

import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.model.QuotaWindow;

import java.util.Collections;
import java.util.List;

/**
 * What one widget slot should display, fully resolved to text.
 *
 * <p>Deliberately dumb — strings, a status and a visibility flag, no
 * {@code Context} and no {@code RemoteViews}. That is what makes the spec's
 * display rules testable on a host JVM: freshness wording, the retained-value
 * marker, a deleted account's row and an unsupported metric are all decisions,
 * and a decision buried inside a {@code RemoteViews} call is a decision nobody
 * can assert on.
 *
 * @see WidgetSlotResolver
 */
public final class WidgetSlotView {

    /** The slot position this view fills, and therefore the layout row it goes in. */
    public final int slotIndex;

    /** The account shown, or empty when nothing could be resolved. */
    public final String accountId;

    /** Header line: the display name, or a state such as 账户已删除. */
    public final String title;

    /** One line per requested metric, in the order the slot asked for them. */
    public final List<String> valueLines;

    /**
     * Time and status, for example {@code 5分钟前 · 数据已过期 · 最后成功数据}.
     *
     * <p>Empty when there is nothing honest to say: a slot with no reading yet
     * has no age to quote.
     */
    public final String footer;

    /** The status this view was drawn from, for callers that colour the row. */
    public final UsageStatus status;

    /** False when the slot has no account and its row should be hidden entirely. */
    public final boolean visible;

    public final boolean quotaAccount;
    public final boolean hasQuotaReading;
    public final QuotaWindow fiveHour;
    public final QuotaWindow weekly;

    WidgetSlotView(int slotIndex, String accountId, String title, List<String> valueLines,
                   String footer, UsageStatus status, boolean visible) {
        this(slotIndex, accountId, title, valueLines, footer, status, visible, false, false, null, null);
    }

    WidgetSlotView(int slotIndex, String accountId, String title, List<String> valueLines,
                   String footer, UsageStatus status, boolean visible, boolean quotaAccount,
                   boolean hasQuotaReading, QuotaWindow fiveHour, QuotaWindow weekly) {
        this.quotaAccount = quotaAccount;
        this.hasQuotaReading = hasQuotaReading;
        this.fiveHour = fiveHour;
        this.weekly = weekly;
        this.slotIndex = slotIndex;
        this.accountId = accountId == null ? "" : accountId;
        this.title = title == null ? "" : title;
        this.valueLines = valueLines == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(valueLines);
        this.footer = footer == null ? "" : footer;
        this.status = status == null ? UsageStatus.NO_DATA : status;
        this.visible = visible;
    }

    @Override
    public String toString() {
        return slotIndex + ":" + title + valueLines + (footer.isEmpty() ? "" : " " + footer);
    }
}
