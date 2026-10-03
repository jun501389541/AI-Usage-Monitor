package com.aiusage.monitor.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.R;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Metric;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.util.QuotaWords;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.codex.CodexProvider;
import com.aiusage.monitor.provider.deepseek.DeepSeekProvider;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.refresh.HolidayUpdater;
import com.aiusage.monitor.storage.AppSettings;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.PeakTimeUtils;
import com.aiusage.monitor.util.ReadingWords;
import com.aiusage.monitor.widget.WidgetRefreshScheduler;
import com.aiusage.monitor.widget.WidgetUpdateManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The account screen.
 *
 * <p>Visually this is the upstream single screen, unchanged: the same cards, the
 * same strings, the same spacing. What changed is underneath. Upstream this
 * class held the DeepSeek URL, its own {@code HttpURLConnection} call, its own
 * JSON parsing, the API key in a preference and the daily-usage accumulator —
 * five responsibilities, none of them the screen's. Now it renders a
 * {@link UsageResult} that came from {@link AccountRefreshManager} and writes the
 * key through {@link AccountManager}. Spec §25 and §53 rules 7 and 10.
 *
 * <p>The practical consequence is that the app and the widget can no longer
 * disagree: both read the same repository, and both refresh through the same
 * manager. It also means the key never touches a preference, and this class
 * never learns that DeepSeek speaks HTTP.
 *
 * <p>One upstream behaviour is deliberately preserved even though it is
 * unusual: a key typed without ticking "记住密钥" is still used for the request
 * it was typed for. The user asked for a balance, not for a storage decision.
 * A Bridge account has nothing to type, so its query goes through the stored
 * credential instead — the only copy of that account's address and token.
 *
 * <p>Phase 2 demotes this screen: the launcher is now
 * {@link com.aiusage.monitor.ui.account.AccountListActivity} and this class is
 * the <em>detail page for one account</em>, reached with
 * {@link #EXTRA_ACCOUNT_ID}. The layout is still the upstream single-account
 * screen, so nothing here is a regression for an existing user: the extra is
 * optional, and without it the screen falls back to the first account exactly
 * as it did in Phase 1.
 */
public final class MainActivity extends Activity {

    /**
     * The account whose detail is being shown.
     *
     * <p>Optional. Absent means "the first account", which is what a direct
     * launch, a launcher shortcut or the widget used to imply. Spec §51.
     */
    public static final String EXTRA_ACCOUNT_ID = "com.aiusage.monitor.extra.ACCOUNT_ID";

    /** Upstream {@code MainActivity.java:58}. */
    private static final long DEFAULT_REFRESH_MS = 15000L;

    /** Upstream {@code MainActivity.java:60-61}. */
    private static final long[] REFRESH_INTERVALS = {1000L, 5000L, 10000L, 15000L, 30000L, 60000L, 300000L, 600000L, 900000L};
    private static final String[] REFRESH_LABELS = {"1 秒", "5 秒", "10 秒", "15 秒", "30 秒", "1 分钟", "5 分钟", "10 分钟", "15 分钟"};

    /** Upstream {@code MainActivity.java:62-63}. */
    private static final long[] BACKGROUND_REFRESH_INTERVALS = {1000L, 5000L, 10000L, 30000L, 60000L, 300000L, 600000L, 1800000L, 3600000L};
    private static final String[] BACKGROUND_REFRESH_LABELS = {"1 秒", "5 秒", "10 秒", "30 秒", "1 分钟", "5 分钟", "10 分钟", "30 分钟", "1 小时"};

    private static final int COLOR_BG = Color.rgb(11, 11, 12);
    private static final int COLOR_CARD = Color.rgb(20, 20, 22);
    private static final int COLOR_INPUT = Color.rgb(15, 15, 17);
    private static final int COLOR_BORDER = Color.rgb(45, 45, 49);
    private static final int COLOR_TEXT = Color.rgb(245, 245, 247);
    private static final int COLOR_MUTED = Color.rgb(161, 161, 170);
    private static final int COLOR_HINT = Color.rgb(113, 113, 122);
    private static final int COLOR_BUTTON = Color.rgb(229, 231, 235);
    private static final int COLOR_BUTTON_TEXT = Color.rgb(15, 15, 17);
    private static final int COLOR_STATUS = Color.rgb(39, 39, 42);
    private static final int COLOR_PEAK = Color.rgb(220, 38, 38);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            updatePeakCard();
            if (!loading && canQueryNow()) {
                queryBalance(true);
            } else {
                scheduleAutoRefresh(refreshIntervalMs);
            }
        }
    };

    private final Runnable secondTickRunnable = new Runnable() {
        @Override
        public void run() {
            updatePeakCard();
            scheduleSecondTick();
        }
    };

    private AppGraph graph;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;
    private UsageRepository usageRepository;
    private AppSettings settings;

    /** The account this screen is showing. Created on first launch. */
    private Account account;

    private EditText keyInput;
    private CheckBox rememberKey;
    private TextView bridgeAddressView;
    private TextView queryButton;
    private TextView statusView;
    private TextView historyView;

    /**
     * True when this account's stored credential carries a usable Bridge address.
     * Read once with the credential, because asking the Bridge for quota without
     * an address could only fail, and a failure drawn on screen looks like a
     * dead Bridge rather than an unconfigured account.
     */
    private boolean bridgeConfigured;

    /** How far back the "recent readings" list looks, and how many it shows. */
    private static final long HISTORY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int HISTORY_ROWS = 10;
    private TextView balanceView;
    private TextView todayUsageView;
    private TextView detailsView;
    private TextView autoStatusView;
    private TextView peakTitleView;
    private TextView peakRemainingView;
    private TextView peakTimeView;
    private long refreshIntervalMs = DEFAULT_REFRESH_MS;
    private long backgroundRefreshIntervalMs = AppSettings.DEFAULT_BACKGROUND_REFRESH_INTERVAL_MS;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        graph = AppGraph.get(this);
        // Importing the upstream app's saved key before anything reads an
        // account is what makes an upgrade seamless: the user's key is already
        // there, in encrypted storage, behind an account that did not exist a
        // moment ago.
        graph.ensureMigrated();
        accountManager = graph.accountManager();
        refreshManager = graph.refreshManager();
        usageRepository = graph.usageRepository();
        settings = graph.settings();

        account = resolveAccount();
        refreshIntervalMs = settings.getLong(AppSettings.KEY_FOREGROUND_REFRESH_INTERVAL, DEFAULT_REFRESH_MS);
        backgroundRefreshIntervalMs = settings.backgroundRefreshIntervalMs();

        WidgetRefreshScheduler.scheduleMidnight(this);
        configureWindow();
        buildInterface();
        loadStoredCredential();
        updatePeakCard();
        HolidayUpdater.updateIfNeeded(this, changed -> runOnUiThread(this::updatePeakCard));
    }

    /**
     * The account to show.
     *
     * <p>Phase 2 resolves it from {@link #EXTRA_ACCOUNT_ID} first: the account
     * list opens this screen for a specific account, and showing a different
     * one would be a wrong-data bug rather than a cosmetic one. An id that no
     * longer resolves — a stale intent after the account was deleted — falls
     * through rather than crashing.
     *
     * <p>Without an id this is the first enabled account, which is what the
     * screen, the widget's "open app" tap and any direct launch mean. When
     * there is none — a fresh install, or a legacy import that found no key — an
     * account is created rather than deferring it to first use, because the
     * screen, its history and its widget bindings all need an identity to hang
     * from. Spec §6.
     */
    private Account resolveAccount() {
        String requestedId = requestedAccountId();
        if (!requestedId.isEmpty()) {
            Account requested = accountManager.find(requestedId);
            if (requested != null) {
                return requested;
            }
        }
        List<Account> enabled = accountManager.listEnabled();
        if (!enabled.isEmpty()) {
            return enabled.get(0);
        }
        List<Account> all = accountManager.list();
        if (!all.isEmpty()) {
            return all.get(0);
        }
        return accountManager.createAccount(DeepSeekProvider.ID, "DeepSeek", AuthType.API_KEY);
    }

    private String requestedAccountId() {
        Intent intent = getIntent();
        if (intent == null) {
            return "";
        }
        String accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID);
        return accountId == null ? "" : accountId;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(COLOR_BG);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 28) {
            window.setNavigationBarDividerColor(COLOR_BG);
        }
    }

    private void buildInterface() {
        // A rebuild can switch providers: onNewIntent re-targets this instance at
        // another account without recreating it, so the previous account's
        // credential views must not be carried into the new screen. Stale ones
        // would render a Bridge account with a hidden key field still live.
        keyInput = null;
        rememberKey = null;
        bridgeAddressView = null;

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setScrollbarFadingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setPadding(dp(20), dp(22), dp(20), dp(22));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Two providers, two very different credentials, and one screen. The
        // kicker and the subtitle are the first lines a user reads, so saying
        // "DeepSeek" and "账户余额" above a Codex account would describe an
        // account this screen is not showing.
        boolean isBridge = isBridgeAccount();

        TextView kicker = text(isBridge ? "AI USAGE BRIDGE" : "DEEPSEEK API", 11, COLOR_MUTED, Typeface.BOLD);
        kicker.setLetterSpacing(0.18f);
        content.addView(kicker, matchWrap(0));

        // The account's own name is the heading now that more than one account
        // can exist: without it, two accounts would be indistinguishable once
        // the user has scrolled past the list. Upstream hard-coded "余额查询"
        // here, which was correct when there could only ever be one account.
        TextView title = text(account.getDisplayName(), 31, COLOR_TEXT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(title, matchWrap(8));

        TextView subtitle = text(isBridge
                ? "通过本机运行的 AI Usage Bridge 实时读取额度窗口"
                : "通过官方开放平台实时读取账户余额", 14, COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(0f, 1.15f);
        content.addView(subtitle, matchWrap(8));

        LinearLayout credentialCard = card();
        content.addView(credentialCard, matchWrap(28));

        if (isBridge) {
            // A Bridge account has nothing to type here: its address and token were
            // set on the account form, and an empty "API KEY" box would invite a
            // DeepSeek key into an account that cannot use one. The address is
            // shown because it is not a secret and it is the one fact a user needs
            // when a reading looks wrong; the token is never displayed.
            TextView bridgeLabel = text("BRIDGE 地址", 11, COLOR_MUTED, Typeface.BOLD);
            bridgeLabel.setLetterSpacing(0.12f);
            credentialCard.addView(bridgeLabel, matchWrap(0));

            bridgeAddressView = text("尚未配置", 15, COLOR_TEXT, Typeface.BOLD);
            bridgeAddressView.setSingleLine(true);
            bridgeAddressView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            bridgeAddressView.setBackground(roundRect(COLOR_INPUT, COLOR_BORDER, 12, 1));
            bridgeAddressView.setPadding(dp(14), dp(13), dp(14), dp(13));
            credentialCard.addView(bridgeAddressView, matchWrap(12));
        } else {
            TextView keyLabel = text("API KEY", 11, COLOR_MUTED, Typeface.BOLD);
            keyLabel.setLetterSpacing(0.12f);
            credentialCard.addView(keyLabel, matchWrap(0));

            keyInput = new EditText(this);
            keyInput.setSingleLine(true);
            keyInput.setTextSize(15);
            keyInput.setTextColor(COLOR_TEXT);
            keyInput.setHintTextColor(COLOR_HINT);
            keyInput.setHint("sk-...");
            keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            keyInput.setBackground(roundRect(COLOR_INPUT, COLOR_BORDER, 12, 1));
            keyInput.setPadding(dp(14), dp(13), dp(14), dp(13));
            keyInput.setSelectAllOnFocus(false);
            if (Build.VERSION.SDK_INT >= 26) {
                keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            }
            credentialCard.addView(keyInput, matchWrap(12));

            LinearLayout rememberRow = new LinearLayout(this);
            rememberRow.setOrientation(LinearLayout.HORIZONTAL);
            rememberRow.setGravity(Gravity.CENTER_VERTICAL);
            credentialCard.addView(rememberRow, matchWrap(8));

            rememberKey = new CheckBox(this);
            rememberKey.setText("记住密钥");
            rememberKey.setTextSize(13);
            rememberKey.setTextColor(COLOR_MUTED);
            rememberKey.setButtonTintList(ColorStateList.valueOf(COLOR_BUTTON));
            rememberKey.setPadding(0, 0, 0, 0);
            rememberRow.addView(rememberKey, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView localOnly = text("仅存本机", 12, COLOR_HINT, Typeface.NORMAL);
            rememberRow.addView(localOnly, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        queryButton = actionButton(queryLabel(), true);
        credentialCard.addView(queryButton, matchHeight(50, 18));

        autoStatusView = text("进入自动查询 · 每 " + refreshIntervalLabel() + "刷新", 11, COLOR_HINT, Typeface.NORMAL);
        autoStatusView.setGravity(Gravity.CENTER);
        autoStatusView.setClickable(true);
        autoStatusView.setFocusable(true);
        autoStatusView.setContentDescription("点击更换前台刷新频率，长按更换后台刷新频率");
        autoStatusView.setOnClickListener(v -> showRefreshIntervalDialog());
        autoStatusView.setOnLongClickListener(v -> {
            showBackgroundRefreshDialog();
            return true;
        });
        credentialCard.addView(autoStatusView, matchWrap(12));

        LinearLayout resultCard = card();
        content.addView(resultCard, matchWrap(16));

        statusView = text("等待查询", 11, COLOR_MUTED, Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setLetterSpacing(0.08f);
        statusView.setBackground(roundRect(COLOR_STATUS, COLOR_BORDER, 99, 1));
        statusView.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        resultCard.addView(statusView, statusParams);

        LinearLayout balanceRow = new LinearLayout(this);
        balanceRow.setOrientation(LinearLayout.HORIZONTAL);
        balanceRow.setGravity(Gravity.CENTER_VERTICAL);
        resultCard.addView(balanceRow, matchWrap(16));

        LinearLayout balanceColumn = new LinearLayout(this);
        balanceColumn.setOrientation(LinearLayout.VERTICAL);
        balanceRow.addView(balanceColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        balanceView = text("—", 26, COLOR_TEXT, Typeface.BOLD);
        balanceView.setIncludeFontPadding(false);
        balanceView.setLetterSpacing(-0.02f);
        // "CNY" is a DeepSeek fact: the Bridge reports no currency at all, so the
        // caption under a permanent dash would name a currency this account has
        // never had. The dash itself is the honest reading (A6/R5).
        TextView balanceCaption = text(isBridge ? "余额" : "CNY 余额", 11, COLOR_HINT, Typeface.NORMAL);
        balanceColumn.addView(balanceCaption, matchWrap(0));

        balanceColumn.addView(balanceView, matchWrap(4));

        LinearLayout usageColumn = new LinearLayout(this);
        usageColumn.setOrientation(LinearLayout.VERTICAL);
        usageColumn.setGravity(Gravity.END);
        balanceRow.addView(usageColumn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView usageLabel = text("今日用量", 11, COLOR_MUTED, Typeface.NORMAL);
        usageLabel.setGravity(Gravity.END);
        usageColumn.addView(usageLabel, matchWrap(0));

        todayUsageView = text("—", 26, COLOR_TEXT, Typeface.BOLD);
        todayUsageView.setGravity(Gravity.END);
        usageColumn.addView(todayUsageView, matchWrap(4));

        detailsView = text(isBridge ? "等待读取额度窗口" : "等待读取 CNY 余额", 13, COLOR_MUTED, Typeface.NORMAL);
        detailsView.setLineSpacing(dp(4), 1.1f);
        resultCard.addView(detailsView, matchWrap(12));

        // Spec §26 kept every reading from the first version precisely so this
        // question could be answered later, and §44 lists 历史 as a destination.
        // It is a list, not a chart: the spec defers curves to V2, and a stored
        // history nobody can look at is the reason the reading API would
        // otherwise stay test-only.
        LinearLayout historyCard = card();
        content.addView(historyCard, matchWrap(16));

        TextView historyLabel = text("最近读数", 11, COLOR_MUTED, Typeface.BOLD);
        historyLabel.setLetterSpacing(0.12f);
        historyCard.addView(historyLabel, matchWrap(0));

        historyView = text("", 12, COLOR_MUTED, Typeface.NORMAL);
        historyView.setLineSpacing(dp(3), 1.1f);
        historyCard.addView(historyView, matchWrap(8));

        LinearLayout peakCard = card();
        content.addView(peakCard, matchWrap(16));

        TextView peakLabel = text("谷峰时段", 11, COLOR_MUTED, Typeface.BOLD);
        peakLabel.setLetterSpacing(0.12f);
        peakCard.addView(peakLabel, matchWrap(0));

        LinearLayout peakStatusRow = new LinearLayout(this);
        peakStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        peakStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        peakCard.addView(peakStatusRow, matchWrap(10));

        peakTitleView = text("—", 20, COLOR_TEXT, Typeface.BOLD);
        peakStatusRow.addView(peakTitleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout remainingColumn = new LinearLayout(this);
        remainingColumn.setOrientation(LinearLayout.VERTICAL);
        remainingColumn.setGravity(Gravity.END);
        remainingColumn.setMinimumWidth(dp(92));
        peakStatusRow.addView(remainingColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView remainingLabel = text("剩余时间", 11, COLOR_MUTED, Typeface.NORMAL);
        remainingLabel.setGravity(Gravity.END);
        remainingColumn.addView(remainingLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        peakRemainingView = text("", 20, COLOR_MUTED, Typeface.BOLD);
        peakRemainingView.setGravity(Gravity.END);
        peakRemainingView.setSingleLine(true);
        peakRemainingView.setMinWidth(dp(92));
        LinearLayout.LayoutParams remainingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        remainingParams.topMargin = dp(2);
        remainingColumn.addView(peakRemainingView, remainingParams);

        peakTimeView = text("", 11, COLOR_HINT, Typeface.NORMAL);
        peakCard.addView(peakTimeView, matchWrap(4));

        // Both sentences name DeepSeek and a key that only it accepts. Shown as-is
        // on a Codex account they would claim the app talks to api.deepseek.com on
        // this account's behalf, which is false: that account's address and token
        // never leave the machine. The button is hidden rather than relabelled -
        // it opens a page that has nothing to do with this account.

        TextView platformButton = actionButton("进入 DeepSeek 开放平台  ↗", false);
        platformButton.setContentDescription("打开 DeepSeek 开放平台");
        if (isBridge) {
            platformButton.setVisibility(View.GONE);
        }
        content.addView(platformButton, matchHeight(52, 16));

        TextView privacy = text(
                isBridge
                        ? "本账户通过本机运行的 AI Usage Bridge 读取额度；地址与令牌只发给这台电脑，"
                          + "Codex 的登录信息不由本应用保存。"
                        : "密钥仅发送给 api.deepseek.com。勾选“记住密钥”后，密钥只保存在本机应用私有存储中。",
                11,
                COLOR_HINT,
                Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(dp(2), 1.15f);
        content.addView(privacy, matchWrap(18));

        setContentView(scroll);
        applyInsets(scroll);

        queryButton.setOnClickListener(v -> queryBalance(false));
        platformButton.setOnClickListener(v -> openPlatform());
        if (!isBridge) {
            wireKeyField();
        }
    }

    /**
     * Behaviour of the DeepSeek key field: query on Done, store on change while
     * "记住密钥" is ticked, forget on untick.
     *
     * <p>Only built for an account that has the field, which is why it is a
     * method rather than a stretch of code that would have to null-check the
     * same two views four times.
     */
    private void wireKeyField() {
        keyInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                queryBalance(false);
                return true;
            }
            return false;
        });
        keyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (rememberKey.isChecked()) {
                    saveKey(s.toString().trim());
                }
            }
        });
        rememberKey.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                saveKey(keyInput.getText().toString().trim());
            } else {
                // Unticking forgets the key but keeps the account, its history
                // and any widget bound to it. Upstream removed the preference
                // and left the usage counters alone; the same promise holds.
                try {
                    accountManager.clearCredential(account.getId());
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(dp(20), dp(22) + bars.top, dp(20), dp(22) + bars.bottom);
                return windowInsets;
            });
            root.requestApplyInsets();
        }
    }

    /**
     * Shows what the account's stored credential holds.
     *
     * <p>A DeepSeek account's key fills the field so it can be edited or reused;
     * a Bridge account's address fills a label, and only its address — the token
     * is opened by the refresh chain and never rendered. The key, by contrast, is
     * displayed because upstream displayed it and because the field is the only
     * way to change it.
     *
     * <p>A failure is not fatal and is not shown: the field stays empty, which is
     * exactly what it means for an account with no usable credential.
     */
    private void loadStoredCredential() {
        bridgeConfigured = false;
        try {
            AuthContext authContext = accountManager.openCredential(account);
            if (isBridgeAccount()) {
                String bridgeUrl = authContext.get(AuthContext.KEY_BRIDGE_URL);
                bridgeConfigured = !TextUtils.isEmpty(bridgeUrl);
                if (bridgeAddressView != null) {
                    bridgeAddressView.setText(bridgeConfigured ? bridgeUrl : "尚未配置");
                }
                return;
            }
            String savedKey = authContext.get(AuthContext.KEY_API_KEY);
            if (!TextUtils.isEmpty(savedKey)) {
                keyInput.setText(savedKey);
                keyInput.setSelection(savedKey.length());
                rememberKey.setChecked(true);
            }
        } catch (AuthException ignored) {
            // No credential, or one that can no longer be decrypted. Either way
            // the user is asked for the key again rather than shown an error.
            if (bridgeAddressView != null) {
                bridgeAddressView.setText("尚未配置");
            }
        }
    }

    /**
     * True for an account whose reading comes from the Bridge rather than from
     * the platform's own API. The screen branches on it because the two have
     * nothing in common to show: one has a key and a balance, the other an
     * address and quota windows.
     */
    private boolean isBridgeAccount() {
        return CodexProvider.ID.equals(account == null ? "" : account.getProviderId());
    }

    /** The button names the reading this account can produce. */
    private String queryLabel() {
        return isBridgeAccount() ? "查询额度" : "查询余额";
    }

    /** Whether the next refresh has anything to query with. */
    private boolean canQueryNow() {
        if (isBridgeAccount()) {
            return bridgeConfigured;
        }
        return keyInput != null && !TextUtils.isEmpty(keyInput.getText().toString().trim());
    }

    private void queryBalance(boolean automatic) {
        if (loading) {
            return;
        }

        if (isBridgeAccount()) {
            if (!bridgeConfigured) {
                if (automatic) {
                    scheduleAutoRefresh(refreshIntervalMs);
                    return;
                }
                Toast.makeText(this, "请先在「编辑账户」里填写 Bridge 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            // The address and the token were written on the account form and are
            // encrypted there; this screen has no field to type them into, so it
            // asks the manager to open the credential itself. That is also why a
            // Bridge account needs no "remember" tick: what it has is already
            // stored, and storing it again from here would need a plaintext copy.
            runQuery(automatic, queried -> refreshManager.refresh(queried));
            return;
        }

        String apiKey = keyInput.getText().toString().trim();
        if (TextUtils.isEmpty(apiKey)) {
            if (automatic) {
                scheduleAutoRefresh(refreshIntervalMs);
                return;
            }
            Toast.makeText(this, "请先输入 DeepSeek API Key", Toast.LENGTH_SHORT).show();
            keyInput.requestFocus();
            return;
        }

        keyInput.clearFocus();
        // The typed key is used directly, whether or not it is remembered. This
        // is why the refresh manager accepts an AuthContext: the request needs
        // the secret, and storage is a separate decision the user already made.
        final AuthContext authContext = AuthContext.ofApiKey(apiKey);
        runQuery(automatic, queried -> refreshManager.refreshWithAuthContext(queried, authContext));
    }

    /** Runs one refresh off the main thread and renders whatever it answers. */
    private void runQuery(boolean automatic, QueryCall call) {
        // Resolved on the main thread, before the request: the account this
        // screen shows can change while a query is in flight, and a result may
        // only ever land on the account it was started for.
        final Account target = account;
        autoStatusView.setText(automatic ? "正在自动查询…" : "正在查询…");
        setLoading(true);
        scheduleAutoRefresh(refreshIntervalMs);

        executor.execute(() -> {
            AccountRefreshManager.RefreshOutcome outcome = call.run(target);
            runOnUiThread(() -> applyOutcome(target, outcome));
        });
    }

    private void applyOutcome(Account target, AccountRefreshManager.RefreshOutcome outcome) {
        // A widget tap can re-target this screen while the request is still in
        // flight. The result belongs to the account it was started for, so
        // landing it on a different one would show this account's screen with
        // that account's money. Drop it; the switch already queued a query for
        // the account now on screen.
        if (!target.getId().equals(account.getId())) {
            return;
        }
        // An abandoned outcome means the account was deleted, or its credential
        // replaced, while its request ran: nothing was stored and there is no
        // error to show. getMessage() is null here, so falling through to
        // showError() would repaint the screen as 查询失败 and wipe a balance the
        // user can still see. Just leave it alone.
        if (outcome.isAbandoned()) {
            return;
        }
        if (outcome.isSuccess()) {
            showBalance(outcome.getResult());
        } else {
            showError(outcome.getMessage());
        }
    }

    /** One refresh's work, run against the account the query started for. */
    private interface QueryCall {
        AccountRefreshManager.RefreshOutcome run(Account target);
    }

    private String refreshIntervalLabel() {
        for (int index = 0; index < REFRESH_INTERVALS.length; index++) {
            if (REFRESH_INTERVALS[index] == refreshIntervalMs) {
                return REFRESH_LABELS[index];
            }
        }
        return REFRESH_LABELS[3];
    }

    private void showRefreshIntervalDialog() {
        int selected = 3;
        for (int index = 0; index < REFRESH_INTERVALS.length; index++) {
            if (REFRESH_INTERVALS[index] == refreshIntervalMs) {
                selected = index;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("刷新频率")
                .setSingleChoiceItems(REFRESH_LABELS, selected, (dialog, which) -> {
                    refreshIntervalMs = REFRESH_INTERVALS[which];
                    settings.setLong(AppSettings.KEY_FOREGROUND_REFRESH_INTERVAL, refreshIntervalMs);
                    autoStatusView.setText("每 " + refreshIntervalLabel() + "刷新");
                    scheduleAutoRefresh(refreshIntervalMs);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showBackgroundRefreshDialog() {
        int selected = 6;
        for (int index = 0; index < BACKGROUND_REFRESH_INTERVALS.length; index++) {
            if (BACKGROUND_REFRESH_INTERVALS[index] == backgroundRefreshIntervalMs) {
                selected = index;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("后台查询频率")
                .setSingleChoiceItems(BACKGROUND_REFRESH_LABELS, selected, (dialog, which) -> {
                    backgroundRefreshIntervalMs = BACKGROUND_REFRESH_INTERVALS[which];
                    settings.setLong(AppSettings.KEY_BACKGROUND_REFRESH_INTERVAL, backgroundRefreshIntervalMs);
                    // Upstream only showed a toast here and left the already
                    // armed alarm on the old interval, so the change did not
                    // take effect until the next fire. Re-arming makes the
                    // setting mean what it says.
                    WidgetRefreshScheduler.schedule(this);
                    Toast.makeText(this, "后台查询频率：" + BACKGROUND_REFRESH_LABELS[which], Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updatePeakCard() {
        if (peakTitleView == null) {
            return;
        }
        PeakTimeUtils.Status status = PeakTimeUtils.currentStatus(this);
        peakTitleView.setText(status.title);
        if (status.peak) {
            peakTitleView.setTextColor(COLOR_PEAK);
        } else {
            peakTitleView.setTextColor(COLOR_MUTED);
        }
        peakRemainingView.setText(status.remaining);
        peakRemainingView.setVisibility(status.remaining.isEmpty() ? View.GONE : View.VISIBLE);
        peakTimeView.setText(status.currentTime);
    }

    /** Renders a successful refresh. */
    /**
     * The last few readings for the account on screen, newest first.
     *
     * <p>Reads a bounded window rather than the whole table: seven days is wide
     * enough that an account used at all has rows to show, and the range plus
     * the limit keeps the query on the (account_id, timestamp) index.
     *
     * <p>Storage only. Opening this screen must not ask a provider anything —
     * the same reason the account list renders what is already saved: a screen
     * that spends the user's quota just by being looked at is a bug with good
     * manners.
     */
    private void renderRecentReadings() {
        if (account == null || historyView == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<UsageSnapshot> readings = usageRepository.history(account.getId(),
                now - HISTORY_WINDOW_MS, now + 1L, HISTORY_ROWS);
        if (readings.isEmpty()) {
            historyView.setText("还没有读数记录");
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < readings.size(); index++) {
            UsageSnapshot reading = readings.get(index);
            UsageResult result = reading.toUsageResult();
            // The value a row is allowed to show for itself: money for DeepSeek,
            // percentages for Codex. Reading only the balance here put an em dash
            // beside the time of every successful Codex reading.
            String balance = ReadingWords.value(result);
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append(time.format(new Date(reading.getTimestamp()))).append(" · ").append(balance);
            if (!reading.isSuccess()) {
                lines.append(" · 查询失败");
            } else if (index + 1 < readings.size()) {
                // The list is newest first, so the older neighbour is the next
                // row: the change that reading produced.
                UsageSnapshot older = readings.get(index + 1);
                UsageResult olderResult = older.toUsageResult();
                if (older.isSuccess() && olderResult != null && olderResult.getBalance() != null
                        && result != null && result.getBalance() != null) {
                    double delta = result.getBalance().getAmount() - olderResult.getBalance().getAmount();
                    if (delta != 0d) {
                        lines.append(delta > 0 ? " · +" : " · ")
                                .append(Money.scale2(delta));
                    }
                }
            }
        }
        historyView.setText(lines.toString());
    }

    private void showBalance(UsageResult result) {
        setLoading(false);

        // Upstream read is_available with a default of false, so a provider that
        // omits the flag renders as unavailable rather than as available.
        Metric availableMetric = result.findMetric(UsageResult.METRIC_ACCOUNT_AVAILABLE);
        boolean available = availableMetric != null && availableMetric.getValue() != 0d;

        Balance balance = result.getBalance();
        String totalDisplay = Money.format(balance);
        String currency = balance == null ? "" : balance.getCurrency();
        // Codex reports quota windows and no money at all, so there is nothing to
        // difference between; the shared rule renders the dash instead of a "0.00"
        // that would claim the account spent nothing today.
        String todayUsage = Money.todayUsage(
                usageRepository.dailyUsage(result.getAccountId()), currency, balance != null);

        statusView.setText(available ? "账户可用" : "账户不可用");
        statusView.setTextColor(available ? COLOR_TEXT : COLOR_MUTED);
        statusView.setBackground(roundRect(COLOR_STATUS, COLOR_BORDER, 99, 1));
        balanceView.setText(totalDisplay);
        todayUsageView.setText(todayUsage);
        // Quota windows are the only reading a Codex account has, so they go on
        // screen whenever the result carries any - and nowhere when it does not,
        // rather than as a heading over an empty block. The wording itself is
        // decided in QuotaWords so it can be tested without a device.
        String quotaLines = QuotaWords.lines(result.getQuotaWindows(), System.currentTimeMillis());
        if (quotaLines.isEmpty()) {
            detailsView.setVisibility(View.GONE);
        } else {
            detailsView.setVisibility(View.VISIBLE);
            detailsView.setText(getString(R.string.quota_window_block, quotaLines));
        }
        autoStatusView.setText("每 " + refreshIntervalLabel() + "刷新 · 更新于 " + currentTime());
        renderRecentReadings();

        // The manager has already stored the snapshot; the widgets only need to
        // be told to redraw. Upstream pushed display strings into preferences
        // here, which is why a widget could show a stale or differently
        // formatted number than the screen. Phase 2 narrows the redraw to the
        // instances bound to this account, so refreshing one account no longer
        // repaints a widget the user pointed at a different one. Spec §38.
        new WidgetUpdateManager(this).updateWidgetsForAccount(account.getId());
    }

    /** Renders a failed refresh, leaving the last successful data in storage. */
    private void showError(String message) {
        setLoading(false);
        statusView.setText("查询失败");
        statusView.setTextColor(COLOR_TEXT);
        statusView.setBackground(roundRect(COLOR_STATUS, COLOR_BORDER, 99, 1));
        balanceView.setText("—");
        todayUsageView.setText("—");
        detailsView.setVisibility(View.VISIBLE);
        detailsView.setText(message);
        renderRecentReadings();
        autoStatusView.setText("每 " + refreshIntervalLabel() + "刷新 · 本次失败，稍后重试");
    }

    private void setLoading(boolean value) {
        loading = value;
        queryButton.setEnabled(!value);
        queryButton.setAlpha(value ? 0.55f : 1f);
        // Absent on a Bridge screen, which has neither field.
        if (keyInput != null) {
            keyInput.setEnabled(!value);
        }
        if (rememberKey != null) {
            rememberKey.setEnabled(!value);
        }
        if (value) {
            queryButton.setText("查询中…");
            statusView.setText("正在读取");
            statusView.setTextColor(COLOR_MUTED);
            balanceView.setText("…");
            todayUsageView.setText("…");
            detailsView.setVisibility(View.GONE);
            detailsView.setText("");
        } else {
            queryButton.setText(queryLabel());
        }
    }

    /**
     * Stores or forgets the typed key.
     *
     * <p>The account is never recreated and its id never changes, which is what
     * keeps history and widget bindings intact across a key change. Spec §14.
     */
    private void saveKey(String key) {
        try {
            if (TextUtils.isEmpty(key)) {
                accountManager.clearCredential(account.getId());
                return;
            }
            accountManager.replaceCredential(account.getId(), CredentialPayload.forApiKey(key));
        } catch (AuthException ignored) {
            // A key that cannot be encoded is not stored; the user can still use
            // it for the current request.
        } catch (RuntimeException ignored) {
        }
    }

    private void openPlatform() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DeepSeekProvider.PLATFORM_URL));
            startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(this, "未找到可用浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(COLOR_CARD, COLOR_BORDER, 18, 1));
        card.setElevation(dp(1));
        return card;
    }

    private TextView actionButton(String label, boolean primary) {
        TextView button = text(label, 15, primary ? COLOR_BUTTON_TEXT : COLOR_TEXT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setPadding(dp(16), dp(14), dp(16), dp(14));
        button.setBackground(primary
                ? roundRect(COLOR_BUTTON, 0, 14, 0)
                : roundRect(COLOR_INPUT, COLOR_BORDER, 14, 1));
        return button;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    private GradientDrawable roundRect(int fillColor, int borderColor, int radiusDp, int borderDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (borderDp > 0) {
            drawable.setStroke(dp(borderDp), borderColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams matchHeight(int heightDp, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp));
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void scheduleAutoRefresh(long delayMs) {
        handler.removeCallbacks(autoRefreshRunnable);
        handler.postDelayed(autoRefreshRunnable, delayMs);
    }

    private String currentTime() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void scheduleSecondTick() {
        long now = SystemClock.uptimeMillis();
        handler.postAtTime(secondTickRunnable, now + (1000L - (now % 1000L)));
    }

    /**
     * Re-targets an already-running screen at the account the new intent names.
     *
     * <p>Every widget tap launches this activity with
     * {@code FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP} (see
     * {@code WidgetUpdateManager.openAppPendingIntent}), so tapping a second
     * widget reuses the instance the first one opened instead of stacking a
     * second copy on top. Without this override that reuse is where the account
     * id goes to die: {@code onCreate} resolved the account once, the recycled
     * intent is never read again, and the screen keeps rendering the first
     * widget's account. Two widgets pointing at different accounts would then
     * both show the first one — the widget would open the wrong account, which
     * is a wrong-data bug rather than a cosmetic one. Tapping back to the
     * account already on screen is left alone so no needless recreate happens.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // resolveAccount() reads getIntent(), so the fresh intent has to be
        // installed before it runs, otherwise a later launch of this instance
        // would resurrect the account the user has already navigated away from.
        setIntent(intent);
        Account next = resolveAccount();
        if (account != null && account.getId().equals(next.getId())) {
            return;
        }
        // Re-targeted in place rather than by recreate(): this screen is a
        // single programmatically built view tree whose only per-account state
        // is the account itself, the key field and the rendered numbers, and
        // rebuilding here keeps the switch deterministic instead of relying on
        // the framework to replay this intent into a new instance. onResume
        // runs right after this and starts the new account's first query.
        account = next;
        buildInterface();
        loadStoredCredential();
        loading = false;
        updatePeakCard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The foreground owns refreshing while it is visible; the alarm is
        // re-armed in onPause.
        WidgetRefreshScheduler.cancel(this);
        updatePeakCard();
        handler.removeCallbacks(secondTickRunnable);
        scheduleSecondTick();
        if (!loading && canQueryNow()) {
            queryBalance(true);
        } else {
            scheduleAutoRefresh(refreshIntervalMs);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autoRefreshRunnable);
        handler.removeCallbacks(secondTickRunnable);
        WidgetRefreshScheduler.schedule(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
