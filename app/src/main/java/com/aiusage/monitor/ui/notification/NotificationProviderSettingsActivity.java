package com.aiusage.monitor.ui.notification;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Switch;
import android.widget.TextView;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.notification.NotificationScheduler;
import com.aiusage.monitor.notification.NotificationSettings;
import com.aiusage.monitor.provider.codex.CodexProvider;
import com.aiusage.monitor.ui.UiKit;

/** Provider-specific and per-account notification preferences. */
public final class NotificationProviderSettingsActivity extends Activity {
    private AppGraph graph;
    private NotificationSettings settings;
    private Account account;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        graph = AppGraph.get(this);
        graph.ensureMigrated();
        settings = new NotificationSettings(graph.settings());
        String accountId = getIntent().getStringExtra(NotificationSettingsActivity.EXTRA_ACCOUNT_ID);
        account = accountId == null ? null : graph.accountManager().find(accountId);
        configureWindow();
        buildInterface();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(UiKit.COLOR_BG);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
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
        TextView title = UiKit.text(this, "平台提醒", 30, UiKit.COLOR_TEXT, Typeface.BOLD);
        content.addView(title, UiKit.matchWrap(this, 8));
        TextView subtitle = UiKit.text(this, "按平台和账户分别设置通知项目", 14,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        content.addView(subtitle, UiKit.matchWrap(this, 8));

        LinearLayout rulesCard = UiKit.card(this);
        content.addView(rulesCard, UiKit.matchWrap(this, 20));
        TextView heading = UiKit.text(this, "提醒项目", 16, UiKit.COLOR_TEXT, Typeface.BOLD);
        rulesCard.addView(heading, UiKit.matchWrap(this, 0));

        addProviderLabel(rulesCard, "DEEPSEEK", 14);
        addToggle(rulesCard, "峰谷切换", "工作日按北京时间的峰谷时段提醒",
                settings.isCategoryEnabled(NotificationSettings.DEEPSEEK), 6,
                checked -> setCategory(NotificationSettings.DEEPSEEK, checked));

        addProviderLabel(rulesCard, "CODEX", 22);
        addToggle(rulesCard, "5 小时额度重置", "使用 Bridge 返回的预计重置时间",
                settings.isCategoryEnabled(NotificationSettings.CODEX_FIVE_HOUR), 6,
                checked -> setCategory(NotificationSettings.CODEX_FIVE_HOUR, checked));
        addToggle(rulesCard, "每周额度重置", "按每周额度的预计重置时间提醒",
                settings.isCategoryEnabled(NotificationSettings.CODEX_WEEKLY), 12,
                checked -> setCategory(NotificationSettings.CODEX_WEEKLY, checked));

        addProviderLabel(rulesCard, "CODEX 账户", 22);
        TextView codexAccountsHelp = UiKit.text(this,
                "可单独暂停某个 Codex 账户的 5 小时和每周重置提醒。", 11,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        codexAccountsHelp.setLineSpacing(UiKit.dp(this, 2), 1.1f);
        rulesCard.addView(codexAccountsHelp, UiKit.matchWrap(this, 4));
        int codexAccountCount = 0;
        for (Account codexAccount : graph.accountManager().list()) {
            if (!CodexProvider.ID.equals(codexAccount.getProviderId())) continue;
            String codexAccountId = codexAccount.getId();
            addToggle(rulesCard, codexAccount.getDisplayName(), "此账户的额度重置提醒",
                    settings.isCodexAccountEnabled(codexAccountId), codexAccountCount == 0 ? 10 : 14,
                    checked -> setCodexAccountEnabled(codexAccountId, checked));
            codexAccountCount++;
        }
        if (codexAccountCount == 0) {
            TextView noCodexAccounts = UiKit.text(this, "添加 Codex 账户后可在这里单独设置。",
                    11, UiKit.COLOR_HINT, Typeface.NORMAL);
            rulesCard.addView(noCodexAccounts, UiKit.matchWrap(this, 10));
        }

        if (account != null && CodexProvider.ID.equals(account.getProviderId())) {
            LinearLayout accountCard = UiKit.card(this);
            content.addView(accountCard, UiKit.matchWrap(this, 14));
            addProviderLabel(accountCard, "CODEX", 0);
            TextView accountHeading = UiKit.text(this, account.getDisplayName() + " · 单账户设置", 16,
                    UiKit.COLOR_TEXT, Typeface.BOLD);
            accountCard.addView(accountHeading, UiKit.matchWrap(this, 8));
            TextView help = UiKit.text(this, "可让此账户跟随全局设置，或单独开启、关闭某类提醒。", 12,
                    UiKit.COLOR_MUTED, Typeface.NORMAL);
            help.setLineSpacing(UiKit.dp(this, 2), 1.15f);
            accountCard.addView(help, UiKit.matchWrap(this, 8));
            addOverrideRow(accountCard, "5 小时额度", NotificationSettings.CODEX_FIVE_HOUR, 14);
            addOverrideRow(accountCard, "每周额度", NotificationSettings.CODEX_WEEKLY, 10);
        }

        TextView note = UiKit.text(this,
                "重置提醒按服务商提供的时间预计触发，不会额外联网轮询；系统省电策略可能造成延迟。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        content.addView(note, UiKit.matchWrap(this, 16));
        setContentView(scroll);
        applyInsets(scroll);
    }

    private void addProviderLabel(LinearLayout card, String label, int topMargin) {
        TextView title = UiKit.text(this, label, 10, UiKit.COLOR_HINT, Typeface.BOLD);
        title.setLetterSpacing(0.16f);
        card.addView(title, UiKit.matchWrap(this, topMargin));
    }

    private Switch addToggle(LinearLayout card, String title, String description, boolean checked,
                             int topMargin, ToggleListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, UiKit.matchWrap(this, topMargin));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setMinimumWidth(0);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView titleView = UiKit.text(this, title, 14, UiKit.COLOR_TEXT, Typeface.BOLD);
        labels.addView(titleView, UiKit.matchWrap(this, 0));
        TextView descriptionView = UiKit.text(this, description, 11, UiKit.COLOR_MUTED, Typeface.NORMAL);
        descriptionView.setLineSpacing(UiKit.dp(this, 2), 1.1f);
        labels.addView(descriptionView, UiKit.matchWrap(this, 3));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        toggle.setOnCheckedChangeListener((button, value) -> listener.changed(value));
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        return toggle;
    }

    private void addOverrideRow(LinearLayout card, String title, String category, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, UiKit.matchWrap(this, topMargin));
        TextView label = UiKit.text(this, title, 14, UiKit.COLOR_TEXT, Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView mode = UiKit.actionButton(this, modeLabel(settings.codexMode(account.getId(), category)), false);
        row.addView(mode, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View.OnClickListener click = view -> chooseOverride(category, mode);
        row.setOnClickListener(click);
        mode.setOnClickListener(click);
    }

    private void chooseOverride(String category, TextView button) {
        String current = settings.codexMode(account.getId(), category);
        String[] choices = {"跟随全局设置", "此账户单独开启", "此账户单独关闭"};
        int selected = "on".equals(current) ? 1 : ("off".equals(current) ? 2 : 0);
        new AlertDialog.Builder(this)
                .setTitle("选择提醒方式")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    String value = which == 1 ? "on" : (which == 2 ? "off" : "follow");
                    settings.setCodexMode(account.getId(), category, value, System.currentTimeMillis());
                    button.setText(modeLabel(value));
                    NotificationScheduler.reconcile(this);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String modeLabel(String mode) {
        if ("on".equals(mode)) return "单独开启";
        if ("off".equals(mode)) return "单独关闭";
        return "跟随全局";
    }

    private void setCategory(String category, boolean enabled) {
        settings.setCategoryEnabled(category, enabled, System.currentTimeMillis());
        NotificationScheduler.reconcile(this);
    }

    private void setCodexAccountEnabled(String accountId, boolean enabled) {
        settings.setCodexAccountEnabled(accountId, enabled, System.currentTimeMillis());
        NotificationScheduler.reconcile(this);
    }

    @Override protected void onResume() {
        super.onResume();
        NotificationScheduler.reconcile(this);
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

    private interface ToggleListener { void changed(boolean checked); }
}
