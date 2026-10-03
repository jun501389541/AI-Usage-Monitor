package com.aiusage.monitor.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.ui.UiKit;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.StatusWords;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses which accounts a widget shows. Spec §33 and §35.
 *
 * <p>Reached from the launcher while a widget is placed, and from the widget
 * itself afterwards, so it works in both directions: first-time binding and
 * re-binding. The choice is stored as one {@link WidgetSlot} row per account, in
 * the order it was picked, because the spec's dashboard is "多个 Account Slot"
 * and a widget that can only show one account cannot answer "what do both of my
 * keys say".
 *
 * <p>Two shapes, one screen. A size with room for one account commits as soon as
 * one is tapped — that is the Phase 2 flow, and a user who wants one number
 * should not have to press 确定 to get it. A dashboard accumulates picks and
 * commits together, because a half-filled dashboard is a state worth being able
 * to leave.
 *
 * <p>Each slot starts with the default metrics. Per-slot metric choice is the
 * next step of the plan; the ids are already stored per slot, so the picker can
 * grow into that without moving data again.
 */
public final class WidgetConfigActivity extends Activity {

    /** Distinguishes "placing a new widget" from "re-binding an existing one". */
    public static final String EXTRA_WIDGET_ID = "com.aiusage.monitor.extra.WIDGET_ID";
    public static final String EXTRA_WIDGET_TYPE = "com.aiusage.monitor.extra.WIDGET_TYPE";

    private static final int INVALID_WIDGET_ID = AppWidgetManager.INVALID_APPWIDGET_ID;

    private AppGraph graph;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;
    private UsageRepository usageRepository;

    private int widgetId = INVALID_WIDGET_ID;
    private String widgetType = WidgetRenderer.TYPE_4X2;
    private final List<String> selected = new ArrayList<>();

    private LinearLayout choiceContainer;
    private TextView subtitle;
    private TextView confirm;
    private TextView clear;
    private int capacity = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        graph = AppGraph.get(this);
        graph.ensureMigrated();
        accountManager = graph.accountManager();
        refreshManager = graph.refreshManager();
        usageRepository = graph.usageRepository();

        Intent intent = getIntent();
        if (intent != null) {
            widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_WIDGET_ID);
            if (intent.hasExtra(EXTRA_WIDGET_TYPE)) {
                widgetType = intent.getStringExtra(EXTRA_WIDGET_TYPE);
            }
        }
        // Opened from the widget rather than from the launcher: the id comes
        // from our own extra. The launcher's contract is to cancel unless we
        // explicitly return OK with the id, which is what confirms placement.
        if (widgetId == INVALID_WIDGET_ID && intent != null && intent.hasExtra(EXTRA_WIDGET_ID)) {
            widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, INVALID_WIDGET_ID);
        }

        // The launcher passes the app widget id but never says which size was
        // chosen, so the extra is only a hint. The system is asked instead:
        // guessing here would record a 2×1 widget as a 4×2 one and render it
        // with the wrong layout. The type is not written to storage yet —
        // cancelling leaves no widget behind, and a row for one that was never
        // placed would be an orphan. Spec §33.
        String actualType = WidgetUpdateManager.typeOf(this, widgetId);
        if (!actualType.isEmpty()) {
            widgetType = actualType;
        }
        capacity = capacityFor(widgetType);

        // Re-opening a configured widget starts from what it already shows, in
        // its stored order, so the screen matches the home screen.
        WidgetConfig existing = graph.widgetConfigStore().find(widgetId);
        if (existing != null) {
            for (WidgetSlot slot : existing.getSlots()) {
                if (slot.isFilled() && selected.size() < capacity) {
                    selected.add(slot.getAccountId());
                }
            }
        }

        configureWindow();
        buildInterface();
    }

    /** How many accounts one widget of this size has room for. */
    public static int capacityFor(String widgetType) {
        return WidgetRenderer.TYPE_4X2.equals(widgetType) ? WidgetRenderer.MAX_SLOTS : 1;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(UiKit.COLOR_BG);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(UiKit.COLOR_BG);
        }
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setScrollbarFadingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(UiKit.COLOR_BG);
        scroll.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 22),
                UiKit.dp(this, 20), UiKit.dp(this, 22));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, UiKit.matchWrap(this, 0));

        TextView kicker = UiKit.text(this, "WIDGET", 11, UiKit.COLOR_MUTED, Typeface.BOLD);
        kicker.setLetterSpacing(0.18f);
        content.addView(kicker, UiKit.matchWrap(this, 0));

        TextView title = UiKit.text(this, "选择账户", 31, UiKit.COLOR_TEXT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        content.addView(title, UiKit.matchWrap(this, 8));

        subtitle = UiKit.text(this, subtitleText(), 14, UiKit.COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(0f, 1.15f);
        content.addView(subtitle, UiKit.matchWrap(this, 8));

        choiceContainer = new LinearLayout(this);
        choiceContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(choiceContainer, UiKit.matchWrap(this, 12));

        TextView noAccounts = UiKit.text(this,
                "还没有账户。请先在应用里添加一个 DeepSeek API Key，再回来选择。",
                13, UiKit.COLOR_MUTED, Typeface.NORMAL);
        noAccounts.setGravity(Gravity.CENTER);
        noAccounts.setLineSpacing(UiKit.dp(this, 4), 1.2f);
        content.addView(noAccounts, UiKit.matchWrap(this, 18));

        boolean empty = accountManager.list().isEmpty();
        choiceContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
        noAccounts.setVisibility(empty ? View.VISIBLE : View.GONE);

        confirm = UiKit.actionButton(this, "确定", true);
        confirm.setContentDescription("确定");
        confirm.setOnClickListener(view -> confirm());
        content.addView(confirm, UiKit.matchHeight(this, 50, 16));

        clear = UiKit.actionButton(this, "清空选择", false);
        clear.setContentDescription("清空选择");
        clear.setOnClickListener(view -> {
            selected.clear();
            renderChoices();
        });
        content.addView(clear, UiKit.matchHeight(this, 50, 10));

        TextView cancel = UiKit.actionButton(this, "取消", false);
        cancel.setContentDescription("取消");
        cancel.setOnClickListener(view -> cancel());
        content.addView(cancel, UiKit.matchHeight(this, 50, 10));

        TextView privacy = UiKit.text(this,
                "绑定关系只保存在本机。取消不会删除已放置的 Widget。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        content.addView(privacy, UiKit.matchWrap(this, 18));

        // Rendered last: renderChoices() shows and hides the confirm and clear
        // buttons along with the cards, so it cannot run before they exist.
        if (!empty) {
            renderChoices();
        }

        setContentView(scroll);
        applyInsets(scroll);
    }

    private String subtitleText() {
        if (capacity > 1) {
            return "按点选顺序排列，最多 " + capacity + " 个账户。这个 Widget 会显示所选账户的余额与今日用量。";
        }
        return "这个 Widget 将显示所选账户的余额与今日用量。";
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 22) + bars.top,
                        UiKit.dp(this, 20), UiKit.dp(this, 22) + bars.bottom);
                return windowInsets;
            });
            root.requestApplyInsets();
        }
    }

    private void renderChoices() {
        choiceContainer.removeAllViews();
        boolean single = capacity <= 1;
        confirm.setVisibility(single ? View.GONE : View.VISIBLE);
        clear.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        subtitle.setText(subtitleText());

        for (Account account : accountManager.list()) {
            int position = selected.indexOf(account.getId());
            boolean chosen = position >= 0;

            LinearLayout card = UiKit.card(this);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = UiKit.text(this, account.getDisplayName(), 17,
                    chosen ? UiKit.COLOR_TEXT : UiKit.COLOR_MUTED, Typeface.BOLD);
            name.setSingleLine(true);
            titleRow.addView(name, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView balance = UiKit.text(this, balanceText(account), 17,
                    UiKit.COLOR_TEXT, Typeface.BOLD);
            balance.setGravity(Gravity.END);
            titleRow.addView(balance, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(titleRow, UiKit.matchWrap(this, 0));

            TextView status = UiKit.text(this, statusText(account, chosen, position),
                    12, UiKit.COLOR_HINT, Typeface.NORMAL);
            status.setSingleLine(true);
            card.addView(status, UiKit.matchWrap(this, 6));

            if (!account.isEnabled() && !chosen) {
                // Choosing a disabled account is allowed: it still has a last
                // known balance, and the user may enable it later. Saying so
                // avoids the surprise of a widget that never refreshes.
                TextView note = UiKit.text(this, "已停用 · 只在手动刷新时更新", 11,
                        UiKit.COLOR_HINT, Typeface.NORMAL);
                card.addView(note, UiKit.matchWrap(this, 6));
            }

            card.setContentDescription(account.getDisplayName());
            card.setOnClickListener(view -> pick(account));
            choiceContainer.addView(card, UiKit.matchWrap(this, 10));
        }
    }

    /** The line under an account's name: what it shows now, or why it cannot. */
    private String statusText(Account account, boolean chosen, int position) {
        if (chosen) {
            return "已选 · 第 " + (position + 1) + " 个 · 再点一次取消";
        }
        if (!account.isEnabled()) {
            return "已停用";
        }
        if (selected.size() >= capacity) {
            return "已达该尺寸上限（" + capacity + " 个账户）";
        }
        // The same last-success-plus-newest-attempt pairing the list and the
        // widget use, and the same words. This screen used to ask
        // UsageRepository.latest() directly and say "上次读取成功", which for an
        // account whose newest attempt was an auth failure offered a dead key as
        // though it were working -- review finding R4 re-appearing on a new
        // surface, which is exactly how a third opinion gets started.
        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        return StatusWords.describe(view.displayStatus(
                graph.widgetConfigStore().backgroundRefreshIntervalMs(),
                System.currentTimeMillis()), view.showingRetainedData());
    }

    /**
     * Adds or removes one account from the choice.
     *
     * <p>A size with one slot commits on the tap: it has no ordering to get
     * wrong and no half state worth keeping.
     */
    private void pick(Account account) {
        int position = selected.indexOf(account.getId());
        if (position >= 0) {
            selected.remove(position);
            renderChoices();
            return;
        }
        if (selected.size() >= capacity) {
            Toast.makeText(this, "这个尺寸最多显示 " + capacity + " 个账户，请先取消一个",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        selected.add(account.getId());
        if (capacity <= 1) {
            confirm();
            return;
        }
        renderChoices();
    }

    /** Stores the slots and confirms the widget. */
    private void confirm() {
        if (selected.isEmpty()) {
            // Saving an empty choice would un-bind a widget that already showed
            // something and leave it on the fallback account, which reads as the
            // binding having been lost. Cancelling is the way back.
            Toast.makeText(this, "请至少选择一个账户", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            new WidgetUpdateManager(this).bindAccounts(widgetId, widgetType, selected);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法保存选择", Toast.LENGTH_SHORT).show();
            cancel();
            return;
        }

        new WidgetUpdateManager(this).updateWidget(widgetId);
        Toast.makeText(this, "Widget 已绑定 " + selected.size() + " 个账户",
                Toast.LENGTH_SHORT).show();

        // The launcher only keeps a widget whose configuration activity
        // returned OK with its id; anything else and the placement is dropped.
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private String balanceText(Account account) {
        UsageResult result = usageRepository.latest(account.getId());
        if (result == null || result.getBalance() == null) {
            return Money.EMPTY;
        }
        return Money.format(result.getBalance());
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
