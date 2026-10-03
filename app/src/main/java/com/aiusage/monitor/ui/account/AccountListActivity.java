package com.aiusage.monitor.ui.account;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.ui.MainActivity;
import com.aiusage.monitor.ui.UiKit;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.StatusWords;
import com.aiusage.monitor.widget.WidgetUpdateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The account list, and the app's launcher. Spec §51.
 *
 * <p>Phase 1 had one implicit account and {@code MainActivity} was the only
 * screen. Phase 2 makes accounts first-class: this screen owns the list, the
 * ordering, and the add/rename/delete actions, while {@code MainActivity}
 * becomes the detail page for one account.
 *
 * <p>Like every other screen, it renders from storage and never calls a
 * provider directly: the balance shown per row is the last successful reading
 * from {@link UsageRepository}. Refreshing goes through
 * {@link AccountRefreshManager}, the single refresh chain Spec §25 permits.
 */
public final class AccountListActivity extends Activity {

    private AppGraph graph;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;
    private UsageRepository usageRepository;
    private WidgetUpdateManager widgetUpdateManager;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AccountAdapter adapter;
    private LinearLayout listContainer;
    private TextView emptyView;
    private TextView refreshAllButton;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        graph = AppGraph.get(this);
        // Runs before anything reads accounts, so an upgrade shows the imported
        // account rather than an empty list. Spec §16.
        graph.ensureMigrated();

        accountManager = graph.accountManager();
        refreshManager = graph.refreshManager();
        usageRepository = graph.usageRepository();
        widgetUpdateManager = new WidgetUpdateManager(this);

        configureWindow();
        buildInterface();
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

        TextView kicker = UiKit.text(this, "AI USAGE MONITOR", 11, UiKit.COLOR_MUTED, Typeface.BOLD);
        kicker.setLetterSpacing(0.18f);
        content.addView(kicker, UiKit.matchWrap(this, 0));

        TextView title = UiKit.text(this, "账户", 31, UiKit.COLOR_TEXT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        content.addView(title, UiKit.matchWrap(this, 8));

        TextView subtitle = UiKit.text(this, "管理多个 DeepSeek 账户，余额与用量彼此独立", 14,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(0f, 1.15f);
        content.addView(subtitle, UiKit.matchWrap(this, 8));

        // The list lives inside the scroll view rather than being its own
        // scrolling child: with a handful of accounts a nested ListView only
        // adds a second scrollbar and a measurement bug.
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(listContainer, UiKit.matchWrap(this, 12));

        emptyView = UiKit.text(this, "还没有账户。点击下方按钮添加一个 DeepSeek API Key。", 13,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setLineSpacing(UiKit.dp(this, 4), 1.2f);
        content.addView(emptyView, UiKit.matchWrap(this, 18));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);

        refreshAllButton = UiKit.actionButton(this, "刷新全部账户", false);
        refreshAllButton.setContentDescription("刷新全部账户");
        refreshAllButton.setOnClickListener(view -> refreshAll());
        actions.addView(refreshAllButton, UiKit.matchHeight(this, 50, 0));

        TextView addButton = UiKit.actionButton(this, "添加账户", true);
        addButton.setContentDescription("添加账户");
        addButton.setOnClickListener(view -> openEditor(null));
        actions.addView(addButton, UiKit.matchHeight(this, 52, 12));
        content.addView(actions, UiKit.matchWrap(this, 16));

        TextView privacy = UiKit.text(this,
                "每个账户的 API Key 单独加密保存，只发送给对应的服务商。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        content.addView(privacy, UiKit.matchWrap(this, 18));

        setContentView(scroll);
        applyInsets(scroll);
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

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuilt on resume rather than only on change: a refresh kicked off
        // from the detail page or from a widget must be visible on return
        // without this screen having to be told about it.
        renderAccounts();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    /** Rebuilds the list from storage. Never performs I/O on a network. */
    private void renderAccounts() {
        List<Account> accounts = accountManager.list();
        long interval = graph.settings().backgroundRefreshIntervalMs();

        listContainer.removeAllViews();
        emptyView.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);
        refreshAllButton.setVisibility(accounts.isEmpty() ? View.GONE : View.VISIBLE);

        for (int index = 0; index < accounts.size(); index++) {
            final Account account = accounts.get(index);
            final int position = index;

            LinearLayout card = UiKit.card(this);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = UiKit.text(this, account.getDisplayName(), 17, UiKit.COLOR_TEXT, Typeface.BOLD);
            name.setSingleLine(true);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            titleRow.addView(name, nameParams);

            TextView balance = UiKit.text(this, balanceText(account), 17,
                    UiKit.COLOR_TEXT, Typeface.BOLD);
            balance.setGravity(Gravity.END);
            titleRow.addView(balance, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(titleRow, UiKit.matchWrap(this, 0));

            LinearLayout detailRow = new LinearLayout(this);
            detailRow.setOrientation(LinearLayout.HORIZONTAL);
            detailRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView status = UiKit.text(this, statusText(account, interval), 12,
                    UiKit.COLOR_MUTED, Typeface.NORMAL);
            status.setSingleLine(true);
            detailRow.addView(status, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView usage = UiKit.text(this, usageText(account), 12, UiKit.COLOR_HINT, Typeface.NORMAL);
            usage.setGravity(Gravity.END);
            detailRow.addView(usage, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(detailRow, UiKit.matchWrap(this, 6));

            if (!account.isEnabled()) {
                TextView disabled = UiKit.text(this, "已停用 · 不计入自动刷新", 11,
                        UiKit.COLOR_HINT, Typeface.NORMAL);
                card.addView(disabled, UiKit.matchWrap(this, 8));
            }
            if (accountManager.isCredentialDegraded(account)) {
                TextView degraded = UiKit.text(this, "密钥保护降级，建议重新保存", 11,
                        UiKit.COLOR_PEAK, Typeface.NORMAL);
                card.addView(degraded, UiKit.matchWrap(this, 8));
            }

            card.setAlpha(account.isEnabled() ? 1f : 0.6f);
            card.setContentDescription(account.getDisplayName());
            card.setOnClickListener(view -> openDetail(account.getId()));
            card.setOnLongClickListener(view -> {
                showAccountMenu(account, position, accounts.size());
                return true;
            });

            listContainer.addView(card, UiKit.matchWrap(this, 10));
        }
    }

    private String balanceText(Account account) {
        UsageResult result = usageRepository.latest(account.getId());
        if (result == null || result.getBalance() == null) {
            return Money.EMPTY;
        }
        return Money.format(result.getBalance());
    }

    private String usageText(Account account) {
        UsageResult result = usageRepository.latest(account.getId());
        if (result == null) {
            return "今日 " + Money.EMPTY;
        }
        // The same rule the detail screen applies, now expressed once in Money:
        // two inline copies of this wording is how the list and the widget ended
        // up describing one stored row differently before.
        Balance balance = result.getBalance();
        return "今日 " + Money.todayUsage(
                usageRepository.dailyUsage(account.getId()),
                balance == null ? "" : balance.getCurrency(),
                balance != null);
    }

    /**
     * A one-line state for the row.
     *
     * <p>Deliberately derived from the stored result rather than from a live
     * request: the list is a summary, and a screen that refreshes N accounts
     * just by being opened would burn the user's quota.
     */
    private String statusText(Account account, long intervalMs) {
        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        // The staleness judgement lives in AccountView.displayStatus and the
        // wording in StatusWords, both shared with the widget path: the two
        // surfaces must not describe the same stored row differently.
        UsageResult latest = view.lastAttempt != null ? view.lastAttempt : view.lastSuccess;
        if (latest == null) {
            return StatusWords.NEVER_QUERIED;
        }
        // A retained old balance must never be presented as current: when the
        // newest attempt failed, say so next to the value the row still shows.
        return StatusWords.describe(
                view.displayStatus(intervalMs, System.currentTimeMillis()),
                view.showingRetainedData());
    }

    private void showAccountMenu(Account account, int position, int total) {
        List<String> labels = new ArrayList<>();
        labels.add("重命名");
        labels.add(account.isEnabled() ? "停用" : "启用");
        if (position > 0) {
            labels.add("上移");
        }
        if (position < total - 1) {
            labels.add("下移");
        }
        labels.add("删除账户");

        new AlertDialog.Builder(this)
                .setTitle(account.getDisplayName())
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        handleMenuChoice(labels.get(which), account, position, total))
                .setNegativeButton("取消", null)
                .show();
    }

    private void handleMenuChoice(String label, Account account, int position, int total) {
        switch (label) {
            case "重命名":
                promptRename(account);
                break;
            case "停用":
                accountManager.setEnabled(account.getId(), false);
                renderAccounts();
                break;
            case "启用":
                accountManager.setEnabled(account.getId(), true);
                renderAccounts();
                break;
            case "上移":
                moveAccount(position, position - 1);
                break;
            case "下移":
                moveAccount(position, position + 1);
                break;
            case "删除账户":
                confirmDelete(account);
                break;
            default:
                break;
        }
    }

    private void promptRename(Account account) {
        EditText input = new EditText(this);
        input.setText(account.getDisplayName());
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(UiKit.COLOR_TEXT);
        input.setHint("账户名称");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        input.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13), UiKit.dp(this, 14), UiKit.dp(this, 13));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 8), UiKit.dp(this, 20), UiKit.dp(this, 0));
        wrapper.addView(input, UiKit.matchWrap(this, 0));

        new AlertDialog.Builder(this)
                .setTitle("重命名账户")
                .setView(wrapper)
                .setPositiveButton("保存", (dialog, which) -> {
                    String typed = input.getText().toString().trim();
                    if (typed.isEmpty()) {
                        Toast.makeText(this, "账户名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Renaming never changes the id, so history and widget
                    // bindings survive. Spec §14.
                    accountManager.rename(account.getId(), typed);
                    renderAccounts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void moveAccount(int from, int to) {
        List<Account> accounts = accountManager.list();
        if (from < 0 || to < 0 || from >= accounts.size() || to >= accounts.size()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Account account : accounts) {
            ids.add(account.getId());
        }
        String moved = ids.remove(from);
        ids.add(to, moved);
        accountManager.reorder(ids);
        renderAccounts();
    }

    private void confirmDelete(Account account) {
        new AlertDialog.Builder(this)
                .setTitle("删除账户")
                .setMessage("将删除「" + account.getDisplayName() + "」及其余额历史。此操作无法撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    // The history cleaner is the usage layer's own delete, so
                    // the account layer never reaches into usage storage. §45.
                    accountManager.delete(account.getId(), usageRepository::deleteForAccount);
                    // A widget bound to the deleted account falls back to the
                    // first enabled one rather than drawing nothing. Spec §39.
                    widgetUpdateManager.updateAllWidgets();
                    renderAccounts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openDetail(String accountId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ACCOUNT_ID, accountId);
        startActivity(intent);
    }

    private void openEditor(String accountId) {
        Intent intent = new Intent(this, AccountEditActivity.class);
        if (accountId != null) {
            intent.putExtra(AccountEditActivity.EXTRA_ACCOUNT_ID, accountId);
        }
        startActivity(intent);
    }

    /** Refreshes every enabled account through the one permitted chain. */
    private void refreshAll() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        refreshAllButton.setText("正在刷新…");
        refreshAllButton.setAlpha(0.55f);

        executor.execute(() -> {
            List<AccountRefreshManager.RefreshOutcome> outcomes = refreshManager.refreshAll();
            int succeeded = 0;
            for (AccountRefreshManager.RefreshOutcome outcome : outcomes) {
                if (outcome.isSuccess()) {
                    succeeded++;
                }
            }
            final int ok = succeeded;
            final int total = outcomes.size();
            runOnUiThread(() -> {
                refreshing = false;
                refreshAllButton.setText("刷新全部账户");
                refreshAllButton.setAlpha(1f);
                // Only the widgets bound to the accounts that were refreshed
                // are redrawn, so an unrelated widget does not flicker. §38.
                widgetUpdateManager.updateAllWidgets();
                renderAccounts();
                Toast.makeText(this, "刷新完成：" + ok + "/" + total + " 个账户成功",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }
}
