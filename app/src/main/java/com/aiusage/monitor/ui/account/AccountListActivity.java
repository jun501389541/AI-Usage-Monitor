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

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.provider.codex.CodexProvider;
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

    private SwipeAccountAdapter adapter;
    private RecyclerView listRecyclerView;
    private ItemTouchHelper itemTouchHelper;
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

        TextView subtitle = UiKit.text(this, "管理多个 AI 账户，额度与用量彼此独立", 14,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(0f, 1.15f);
        content.addView(subtitle, UiKit.matchWrap(this, 8));

        listRecyclerView = new RecyclerView(this);
        listRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        listRecyclerView.setNestedScrollingEnabled(false);
        listRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new SwipeAccountAdapter(this, new SwipeAccountAdapter.Listener() {
            @Override public void open(String accountId) { openDetail(accountId); }
            @Override public void menu(String accountId) {
                Account account = accountManager.find(accountId);
                if (account != null) showAccountMenu(account);
            }
            @Override public void pin(String accountId, boolean pinned) {
                accountManager.setPinned(accountId, pinned);
                renderAccounts();
            }
            @Override public void delete(String accountId) {
                Account account = accountManager.find(accountId);
                if (account != null) confirmDelete(account);
            }
            @Override public void startDrag(SwipeAccountAdapter.AccountViewHolder holder) {
                if (itemTouchHelper != null) {
                    holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    itemTouchHelper.startDrag(holder);
                }
            }
        });
        listRecyclerView.setAdapter(adapter);
        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            private boolean dragging;

            @Override public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
                int directions = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                return makeMovementFlags(directions, 0);
            }

            @Override public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source,
                    RecyclerView.ViewHolder target) {
                if (!(source instanceof SwipeAccountAdapter.AccountViewHolder)
                        || !(target instanceof SwipeAccountAdapter.AccountViewHolder)) return false;
                return adapter.move(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) { }

            @Override public boolean canDropOver(RecyclerView recyclerView, RecyclerView.ViewHolder source,
                    RecyclerView.ViewHolder target) {
                return adapter.canMove(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override public boolean isItemViewSwipeEnabled() { return false; }
            @Override public boolean isLongPressDragEnabled() { return false; }

            @Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                super.onSelectedChanged(holder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragging = true;
                    adapter.closeRevealed();
                    holder.itemView.setAlpha(0.88f);
                }
            }

            @Override public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
                super.clearView(recyclerView, holder);
                holder.itemView.setAlpha(1f);
                if (dragging) {
                    dragging = false;
                    accountManager.reorder(adapter.orderedIds());
                }
            }
        });
        itemTouchHelper.attachToRecyclerView(listRecyclerView);
        content.addView(listRecyclerView, UiKit.matchWrap(this, 12));

        emptyView = UiKit.text(this, "还没有账户。点击下方按钮添加 AI 账户。", 13,
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

        // Phase 7: the paired computers need their own screen because their state is
        // per computer, not per account — one laptop can back several accounts, and
        // forgetting it affects all of them at once.
        TextView bridgesButton = UiKit.actionButton(this, "已配对的电脑", false);
        bridgesButton.setContentDescription("已配对的电脑");
        bridgesButton.setOnClickListener(view -> startActivity(new android.content.Intent(
                this, com.aiusage.monitor.ui.bridge.BridgeListActivity.class)));
        actions.addView(bridgesButton, UiKit.matchHeight(this, 50, 12));
        content.addView(actions, UiKit.matchWrap(this, 16));

        TextView privacy = UiKit.text(this,
                "每个账户的凭据单独加密保存，只发送给对应的服务商。",
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

        emptyView.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);
        refreshAllButton.setVisibility(accounts.isEmpty() ? View.GONE : View.VISIBLE);
        List<SwipeAccountAdapter.Row> rows = new ArrayList<>();
        for (Account account : accounts) {
            AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
            UsageResult latest = view.lastSuccess;
            rows.add(new SwipeAccountAdapter.Row(account.getId(), account.getDisplayName(),
                    balanceText(account, latest), usageText(account, latest), statusText(view, interval),
                    account.isEnabled(), accountManager.isCredentialDegraded(account), account.isPinned()));
        }
        adapter.setRows(rows);
    }

    private String balanceText(Account account, UsageResult result) {
        if (CodexProvider.ID.equals(account.getProviderId())) {
            QuotaWindow weekly = weeklyWindow(result);
            return weekly == null ? Money.EMPTY
                    : Math.round(Math.max(0d, Math.min(100d, weekly.getRemainingPercent()))) + "%";
        }
        if (result == null || result.getBalance() == null) {
            return Money.EMPTY;
        }
        return Money.format(result.getBalance());
    }

    private String usageText(Account account, UsageResult result) {
        if (CodexProvider.ID.equals(account.getProviderId())) {
            return result != null && weeklyWindow(result) == null ? "未提供每周额度" : "每周剩余";
        }
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

    private static QuotaWindow weeklyWindow(UsageResult result) {
        if (result != null) {
            for (QuotaWindow window : result.getQuotaWindows()) {
                if (window != null && window.getWindowMinutes() == 7L * 24L * 60L) {
                    return window;
                }
            }
        }
        return null;
    }

    /**
     * A one-line state for the row.
     *
     * <p>Deliberately derived from the stored result rather than from a live
     * request: the list is a summary, and a screen that refreshes N accounts
     * just by being opened would burn the user's quota.
     */
    private String statusText(AccountRefreshManager.AccountView view, long intervalMs) {
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

    private void showAccountMenu(Account account) {
        List<String> labels = new ArrayList<>();
        labels.add("重命名");
        labels.add(account.isEnabled() ? "停用" : "启用");
        // A Codex account's secret is a pairing, and the computer can revoke that
        // pairing at any moment. Without an entry here the only way back is to add a
        // second account for the same computer and leave the first one reading
        // 「还没有与这台电脑配对」 with its history and slots stranded on it (review P2,
        // 2026-10-03 - PairingStore.rebind existed with no caller at all).
        if (com.aiusage.monitor.provider.codex.CodexProvider.ID.equals(account.getProviderId())) {
            labels.add("重新配对");
        }
        labels.add("删除账户");

        new AlertDialog.Builder(this)
                .setTitle(account.getDisplayName())
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        handleMenuChoice(labels.get(which), account))
                .setNegativeButton("取消", null)
                .show();
    }

    private void handleMenuChoice(String label, Account account) {
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
            case "重新配对":
                openPairing(account.getId());
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

    /**
     * Re-pairing means this account, and it is the pairing screen that decides how the
     * new introduction arrives (link, paste, or typed address plus code). The id travels
     * so the screen can rebind instead of creating a second account for one computer.
     */
    private void openPairing(String accountId) {
        Intent intent = new Intent(this, com.aiusage.monitor.ui.pair.PairActivity.class);
        intent.putExtra(com.aiusage.monitor.ui.pair.PairActivity.EXTRA_ACCOUNT_ID, accountId);
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
