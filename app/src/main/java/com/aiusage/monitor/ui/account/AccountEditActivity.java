package com.aiusage.monitor.ui.account;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
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
import com.aiusage.monitor.auth.BridgeAuthAdapter;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.codex.CodexProvider;
import com.aiusage.monitor.provider.deepseek.DeepSeekProvider;
import com.aiusage.monitor.ui.UiKit;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.widget.WidgetRefreshScheduler;
import com.aiusage.monitor.widget.WidgetUpdateManager;

/**
 * Adds a new account or edits an existing one. Spec §51.
 *
 * <p>The screen exists to keep one promise: an account is an identity, not a
 * key. Renaming one, changing its key, enabling or disabling it all leave its
 * id alone, which is what keeps its balance history and any widget pointed at
 * it intact. Nothing here can change an account id.
 *
 * <p>Only DeepSeek is offered in Phase 2. The provider id is read from
 * {@link DeepSeekProvider#ID} rather than typed as a literal, so registering a
 * second provider later is a change here and in the registry, not a search for
 * hard-coded strings.
 */
public final class AccountEditActivity extends Activity {

    /** The account being edited, or absent when adding one. */
    public static final String EXTRA_ACCOUNT_ID = "com.aiusage.monitor.extra.ACCOUNT_ID";

    /** A pairing started from this form; its result is an account that already exists. */
    private static final int REQUEST_PAIR = 41;
    private static final int REQUEST_DIRECT = 42;

    private AppGraph graph;
    private AccountManager accountManager;
    private UsageRepository usageRepository;

    /** Null while adding; set while editing. */
    private Account account;

    private EditText nameInput;
    private EditText keyInput;
    private EditText bridgeUrlInput;
    private EditText bridgeTokenInput;
    private CheckBox rememberKey;
    private CheckBox enabledBox;
    private CheckBox directEnabledBox;
    private LinearLayout warningBox;
    private LinearLayout keyCard;
    private LinearLayout bridgeCard;
    private LinearLayout directCard;
    private TextView directSummary;
    private TextView directUnlinkButton;
    private TextView subtitle;
    private TextView privacy;
    private TextView providerHint;
    private TextView deepSeekChip;
    private TextView codexChip;
    private String generatedDefaultName;
    private boolean settingGeneratedDefaultName;

    /**
     * The provider the form is configured for. Chosen when adding, fixed when
     * editing: an account's provider is part of what its stored history means, so
     * switching one would relabel rows that were written by the other.
     */
    private String providerId = DeepSeekProvider.ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        graph = AppGraph.get(this);
        // Migration runs before the account is looked up: on a fresh upgrade
        // the imported account must be findable, and on an empty install the
        // missing account must mean "add" rather than "error".
        graph.ensureMigrated();

        accountManager = graph.accountManager();
        usageRepository = graph.usageRepository();

        String accountId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        if (!TextUtils.isEmpty(accountId)) {
            account = accountManager.find(accountId);
        }

        configureWindow();
        buildInterface();
        loadAccount();
        applyProviderSelection();
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

    private boolean isEditing() {
        return account != null;
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

        TextView title = UiKit.text(this, isEditing() ? "编辑账户" : "添加账户", 31,
                UiKit.COLOR_TEXT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        content.addView(title, UiKit.matchWrap(this, 8));

        subtitle = UiKit.text(this, "", 14, UiKit.COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(0f, 1.15f);
        content.addView(subtitle, UiKit.matchWrap(this, 8));

        // ---------------------------------------------------------- name
        LinearLayout nameCard = UiKit.card(this);
        nameCard.addView(label("账户名称"), UiKit.matchWrap(this, 0));

        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextSize(15);
        nameInput.setTextColor(UiKit.COLOR_TEXT);
        nameInput.setHint("例如 " + defaultAccountName());
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                if (!settingGeneratedDefaultName) generatedDefaultName = null;
            }
        });
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        nameInput.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13), UiKit.dp(this, 14), UiKit.dp(this, 13));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nameInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        nameCard.addView(nameInput, UiKit.matchWrap(this, 10));
        content.addView(nameCard, UiKit.matchWrap(this, 28));

        // ------------------------------------------------------ provider
        LinearLayout providerCard = UiKit.card(this);
        providerCard.addView(label("服务商"), UiKit.matchWrap(this, 0));

        LinearLayout providerRow = new LinearLayout(this);
        providerRow.setOrientation(LinearLayout.HORIZONTAL);

        deepSeekChip = UiKit.actionButton(this, "DeepSeek", true);
        deepSeekChip.setContentDescription("服务商 DeepSeek");
        deepSeekChip.setOnClickListener(view -> chooseProvider(DeepSeekProvider.ID));
        providerRow.addView(deepSeekChip, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        codexChip = UiKit.actionButton(this, "OpenAI Codex", false);
        codexChip.setContentDescription("服务商 OpenAI Codex");
        codexChip.setOnClickListener(view -> chooseProvider(CodexProvider.ID));
        LinearLayout.LayoutParams codexParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        codexParams.leftMargin = UiKit.dp(this, 12);
        providerRow.addView(codexChip, codexParams);

        providerCard.addView(providerRow, UiKit.matchWrap(this, 12));

        providerHint = UiKit.text(this, "", 11, UiKit.COLOR_HINT, Typeface.NORMAL);
        providerHint.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        providerCard.addView(providerHint, UiKit.matchWrap(this, 8));
        content.addView(providerCard, UiKit.matchWrap(this, 16));

        // ----------------------------------------------------------- key
        keyCard = UiKit.card(this);
        keyCard.addView(label("API KEY"), UiKit.matchWrap(this, 0));

        keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setTextSize(15);
        keyInput.setTextColor(UiKit.COLOR_TEXT);
        keyInput.setHint("sk-...");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keyInput.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        keyInput.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13), UiKit.dp(this, 14), UiKit.dp(this, 13));
        keyInput.setSelectAllOnFocus(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        keyCard.addView(keyInput, UiKit.matchWrap(this, 10));

        LinearLayout rememberRow = new LinearLayout(this);
        rememberRow.setOrientation(LinearLayout.HORIZONTAL);
        rememberRow.setGravity(Gravity.CENTER_VERTICAL);

        rememberKey = new CheckBox(this);
        rememberKey.setText("记住密钥");
        rememberKey.setTextSize(13);
        rememberKey.setTextColor(UiKit.COLOR_MUTED);
        rememberKey.setButtonTintList(ColorStateList.valueOf(UiKit.COLOR_BUTTON));
        rememberKey.setPadding(0, 0, 0, 0);
        rememberRow.addView(rememberKey, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView localOnly = UiKit.text(this, "仅存本机", 12, UiKit.COLOR_HINT, Typeface.NORMAL);
        localOnly.setGravity(Gravity.END);
        rememberRow.addView(localOnly, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        keyCard.addView(rememberRow, UiKit.matchWrap(this, 8));

        warningBox = new LinearLayout(this);
        warningBox.setOrientation(LinearLayout.VERTICAL);
        warningBox.setVisibility(View.GONE);
        keyCard.addView(warningBox, UiKit.matchWrap(this, 8));

        content.addView(keyCard, UiKit.matchWrap(this, 16));

        // --------------------------------------------------------- bridge
        bridgeCard = UiKit.card(this);
        bridgeCard.addView(label("电脑端 Bridge"), UiKit.matchWrap(this, 0));

        // Phase 7's recommended path. Pairing is what makes the address safe to type
        // at all: it pins the certificate, keeps the address in one row that can be
        // edited when the laptop moves networks, and stores the device token in the
        // same encrypted credential this form already writes.
        TextView pairButton = UiKit.actionButton(this, "扫码连接电脑（推荐）", true);
        pairButton.setContentDescription("扫码连接电脑");
        pairButton.setOnClickListener(view -> openPairing(true));
        bridgeCard.addView(pairButton, UiKit.matchHeight(this, 50, 10));
        TextView otherPairing = UiKit.actionButton(this, "其它配对方式", false);
        otherPairing.setOnClickListener(view -> openPairing(false));
        otherPairing.setMinHeight(UiKit.dp(this, 50));
        bridgeCard.addView(otherPairing, UiKit.matchWrap(this, 8));

        bridgeCard.addView(label("或直接手输地址（调试通道）"), UiKit.matchWrap(this, 12));

        bridgeUrlInput = new EditText(this);
        bridgeUrlInput.setSingleLine(true);
        bridgeUrlInput.setTextSize(15);
        bridgeUrlInput.setTextColor(UiKit.COLOR_TEXT);
        bridgeUrlInput.setHint("http://10.0.2.2:38411");
        bridgeUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        bridgeUrlInput.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        bridgeUrlInput.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13), UiKit.dp(this, 14), UiKit.dp(this, 13));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bridgeUrlInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        bridgeCard.addView(bridgeUrlInput, UiKit.matchWrap(this, 10));

        TextView urlHint = UiKit.text(this,
                "手机上的模拟器用 10.0.2.2 访问电脑本机。调试通道不钉证书：地址与令牌照明文发出去，只适合回环或模拟器网段。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        urlHint.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        bridgeCard.addView(urlHint, UiKit.matchWrap(this, 8));

        bridgeTokenInput = new EditText(this);
        bridgeTokenInput.setSingleLine(true);
        bridgeTokenInput.setTextSize(15);
        bridgeTokenInput.setTextColor(UiKit.COLOR_TEXT);
        bridgeTokenInput.setHint("临时令牌（当前版本可留空）");
        bridgeTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        bridgeTokenInput.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        bridgeTokenInput.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13), UiKit.dp(this, 14), UiKit.dp(this, 13));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bridgeTokenInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        bridgeCard.addView(bridgeTokenInput, UiKit.matchWrap(this, 10));
        content.addView(bridgeCard, UiKit.matchWrap(this, 16));

        // ---------------------------------------------------- phone Direct
        directCard = UiKit.card(this);
        directCard.addView(label("实验性手机直连"), UiKit.matchWrap(this, 0));
        TextView directHint = UiKit.text(this,
                "手机独立通过 OpenAI 官方设备码授权并查询额度。Direct 成功优先；失败时使用整份 Bridge 快照。浏览器可能要求登录，本功能不会读取 ChatGPT App 的登录凭据。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        directHint.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        directCard.addView(directHint, UiKit.matchWrap(this, 8));
        directSummary = UiKit.text(this, "尚未授权", 12, UiKit.COLOR_MUTED, Typeface.NORMAL);
        directSummary.setLineSpacing(UiKit.dp(this, 2), 1.12f);
        directCard.addView(directSummary, UiKit.matchWrap(this, 8));
        directEnabledBox = new CheckBox(this);
        directEnabledBox.setText("启用手机 Direct 优先刷新");
        directEnabledBox.setTextSize(14);
        directEnabledBox.setTextColor(UiKit.COLOR_TEXT);
        directEnabledBox.setButtonTintList(ColorStateList.valueOf(UiKit.COLOR_BUTTON));
        directCard.addView(directEnabledBox, UiKit.matchWrap(this, 8));
        TextView directLoginButton = UiKit.actionButton(this, "用手机授权 Codex", true);
        directLoginButton.setOnClickListener(view -> openDirectAuth());
        directCard.addView(directLoginButton, UiKit.matchHeight(this, 50, 8));
        directUnlinkButton = UiKit.actionButton(this, "解除手机授权", false);
        directUnlinkButton.setOnClickListener(view -> confirmUnlinkDirect());
        directCard.addView(directUnlinkButton, UiKit.matchHeight(this, 46, 8));
        content.addView(directCard, UiKit.matchWrap(this, 16));

        // ------------------------------------------------------- enabled
        LinearLayout enabledCard = UiKit.card(this);

        enabledBox = new CheckBox(this);
        enabledBox.setText("启用此账户");
        enabledBox.setTextSize(15);
        enabledBox.setTextColor(UiKit.COLOR_TEXT);
        enabledBox.setButtonTintList(ColorStateList.valueOf(UiKit.COLOR_BUTTON));
        enabledBox.setPadding(0, 0, 0, 0);
        enabledCard.addView(enabledBox, UiKit.matchWrap(this, 0));

        TextView enabledHint = UiKit.text(this,
                "停用后不再参与自动刷新，余额、历史与 Widget 绑定都会保留。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        enabledHint.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        enabledCard.addView(enabledHint, UiKit.matchWrap(this, 6));
        content.addView(enabledCard, UiKit.matchWrap(this, 16));

        // ------------------------------------------------------- actions
        TextView saveButton = UiKit.actionButton(this, isEditing() ? "保存修改" : "添加账户", true);
        saveButton.setContentDescription("保存");
        saveButton.setOnClickListener(view -> save());
        content.addView(saveButton, UiKit.matchHeight(this, 52, 20));

        if (isEditing()) {
            TextView deleteButton = UiKit.actionButton(this, "删除账户", false);
            deleteButton.setContentDescription("删除账户");
            deleteButton.setOnClickListener(view -> confirmDelete());
            content.addView(deleteButton, UiKit.matchHeight(this, 52, 12));
        }

        privacy = UiKit.text(this, "", 11, UiKit.COLOR_HINT, Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        content.addView(privacy, UiKit.matchWrap(this, 18));

        setContentView(scroll);
        applyInsets(scroll);
    }

    /**
     * Switches the form between the two providers. Adding an account may choose;
     * editing keeps its own, because the account's history was written by the
     * provider it already has.
     */
    private void chooseProvider(String id) {
        if (isEditing()) {
            return;
        }
        providerId = id;
        applyProviderSelection();
    }

    /** Shows only the fields the chosen provider has, and says why in its own words. */
    private void applyProviderSelection() {
        boolean codex = CodexProvider.ID.equals(providerId);
        boolean directOnly = isDirectOnlyAccount();

        keyCard.setVisibility(codex ? View.GONE : View.VISIBLE);
        bridgeCard.setVisibility(codex && !directOnly ? View.VISIBLE : View.GONE);
        directCard.setVisibility(codex ? View.VISIBLE : View.GONE);

        styleProviderChip(deepSeekChip, !codex);
        styleProviderChip(codexChip, codex);

        nameInput.setHint("例如 " + defaultAccountName());
        if (!isEditing()) {
            String currentName = nameInput.getText().toString().trim();
            if (currentName.isEmpty() || (generatedDefaultName != null && currentName.equals(generatedDefaultName))) {
                generatedDefaultName = defaultAccountName();
                settingGeneratedDefaultName = true;
                nameInput.setText(generatedDefaultName);
                nameInput.setSelection(nameInput.getText().length());
                settingGeneratedDefaultName = false;
            }
        }

        if (isEditing()) {
            providerHint.setText(codex
                    ? "服务商不可更改：这个账户的历史是由它原来的服务商写入的。"
                    : "服务商不可更改：这个账户的历史是由它原来的服务商写入的。");
        } else {
            providerHint.setText(codex
                    ? "Codex 可继续通过电脑端 Bridge 读取，也可选择实验性手机直连。手机直连只在此开关启用后参与刷新。"
                    : "DeepSeek 使用平台 API Key 直接查询余额。");
        }

        subtitle.setText(isEditing()
                ? "修改名称、密钥或启用状态。账户 ID 与历史记录不会改变。"
                : (codex ? "给这台电脑上的 Codex 账户起个名字，便于在列表中区分。"
                        : "为这个 DeepSeek API Key 起一个名字，便于在列表中区分。"));

        privacy.setText(codex
                ? "手机 Direct 令牌独立保存在本机 Android Keystore；取消授权会保留 Bridge 令牌、账户历史和 Widget 绑定。"
                : "密钥仅发送给 api.deepseek.com。勾选“记住密钥”后，密钥只保存在本机应用私有存储中。");
    }

    private boolean isDirectOnlyAccount() {
        return isEditing() && CodexProvider.ID.equals(account.getProviderId())
                && account.getAuthType() == AuthType.OAUTH;
    }

    private void styleProviderChip(TextView chip, boolean selected) {
        chip.setAlpha(1f);
        chip.setTextColor(selected ? UiKit.COLOR_BUTTON_TEXT : UiKit.COLOR_TEXT);
        chip.setBackground(selected
                ? UiKit.roundRect(this, UiKit.COLOR_BUTTON, 0, 14, 0)
                : UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 14, 1));
    }

    private String defaultAccountName() {
        String displayName;
        if (CodexProvider.ID.equals(providerId)) displayName = "Codex";
        else if (DeepSeekProvider.ID.equals(providerId)) displayName = "DeepSeek";
        else displayName = providerId;
        return displayName + " 个人";
    }

    private TextView label(String value) {
        TextView label = UiKit.text(this, value, 11, UiKit.COLOR_MUTED, Typeface.BOLD);
        label.setLetterSpacing(0.12f);
        return label;
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

    /** Fills the form from the account being edited. */
    private void loadAccount() {
        if (!isEditing()) {
            enabledBox.setChecked(true);
            directEnabledBox.setChecked(false);
            directEnabledBox.setEnabled(false);
            directUnlinkButton.setVisibility(View.GONE);
            return;
        }
        providerId = account.getProviderId();
        nameInput.setText(account.getDisplayName());
        nameInput.setSelection(nameInput.getText().length());
        enabledBox.setChecked(account.isEnabled());
        boolean hasDirect = accountManager.hasDirectCredential(account);
        directEnabledBox.setChecked(account.isDirectEnabled());
        directEnabledBox.setEnabled(hasDirect);
        updateDirectSummary();

        try {
            AuthContext saved = accountManager.openCredential(account);
            if (CodexProvider.ID.equals(providerId)) {
                String url;
                if (!TextUtils.isEmpty(account.getBridgeId())) {
                    com.aiusage.monitor.model.Bridge bridge = AppGraph.get(this).bridgeRepository()
                            .findById(account.getBridgeId());
                    url = bridge == null ? "" : bridge.getBaseUrl();
                    // A paired address is owned by the bridge row. Change it
                    // through re-pairing so its pinned identity stays in sync.
                    bridgeUrlInput.setEnabled(false);
                } else {
                    url = saved.get(AuthContext.KEY_BRIDGE_URL);
                }
                if (!TextUtils.isEmpty(url)) {
                    bridgeUrlInput.setText(url);
                    bridgeUrlInput.setSelection(url.length());
                }
                String token = saved.get(AuthContext.KEY_DEVICE_TOKEN);
                if (!TextUtils.isEmpty(token)) {
                    bridgeTokenInput.setText(token);
                    bridgeTokenInput.setSelection(token.length());
                }
            } else {
                String savedKey = saved.get(AuthContext.KEY_API_KEY);
                if (!TextUtils.isEmpty(savedKey)) {
                    keyInput.setText(savedKey);
                    keyInput.setSelection(savedKey.length());
                    rememberKey.setChecked(true);
                }
            }
        } catch (AuthException ignored) {
            // No usable credential: the fields stay empty, which is the honest
            // representation of an account whose key was never stored.
        }

        if (accountManager.isCredentialDegraded(account)) {
            addWarning("密钥保护降级：当前密钥未能使用硬件加密存储，建议重新输入并保存。",
                    UiKit.COLOR_PEAK);
        }
        if (account.getCredentialId() == null || account.getCredentialId().isEmpty()) {
            addWarning(isDirectOnlyAccount()
                    ? "此账户尚未保存手机 OAuth 授权，可通过上方按钮重新授权。"
                    : CodexProvider.ID.equals(providerId)
                    ? (!TextUtils.isEmpty(account.getBridgeId())
                            ? "配对账户缺少设备令牌，请从账户列表重新配对。"
                            : "此账户尚未保存 Bridge 地址，刷新时会提示没有填写电脑端 Bridge 地址。")
                    : "此账户尚未保存密钥，刷新时会提示 API Key 无效或已失效。",
                    UiKit.COLOR_HINT);
        }
    }

    private void addWarning(String message, int color) {
        TextView warning = UiKit.text(this, message, 11, color, Typeface.NORMAL);
        warning.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        warningBox.addView(warning, UiKit.matchWrap(this, warningBox.getChildCount() == 0 ? 0 : 6));
        warningBox.setVisibility(View.VISIBLE);
    }

    /**
     * Writes the form back.
     *
     * <p>Every branch keeps the account id: adding creates one, editing reuses
     * it. A key change goes through {@code replaceCredential}, which updates the
     * credential row rather than rebuilding the account, so history and widget
     * bindings survive. Spec §14.
     */
    private void save() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "账户名称不能为空", Toast.LENGTH_SHORT).show();
            nameInput.requestFocus();
            return;
        }

        String key = keyInput.getText().toString().trim();
        boolean remember = rememberKey.isChecked();
        boolean enabled = enabledBox.isChecked();
        boolean codex = CodexProvider.ID.equals(providerId);
        boolean directOnly = isDirectOnlyAccount();
        String bridgeUrl = codex && !directOnly ? bridgeUrlInput.getText().toString().trim() : "";
        String bridgeToken = codex && !directOnly ? bridgeTokenInput.getText().toString().trim() : "";
        String bridgeCredentialPayload = "";

        if (codex && !directOnly) {
            // Checked before anything is written, so a typo is reported while the
            // user is still looking at the field. No request is made here: probing
            // the host the user just typed would connect somewhere before anything
            // authorises it. Spec §53 rule 22.
            try {
                String editablePayload = CredentialPayload.forBridge(bridgeUrl, bridgeToken);
                new BridgeAuthAdapter().validate(editablePayload);
                boolean paired = isEditing() && !TextUtils.isEmpty(account.getBridgeId());
                bridgeCredentialPayload = paired
                        ? CredentialPayload.forDeviceToken(bridgeToken)
                        : editablePayload;
            } catch (AuthException exception) {
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
                if (isEditing() && !TextUtils.isEmpty(account.getBridgeId())) {
                    bridgeTokenInput.requestFocus();
                } else {
                    bridgeUrlInput.requestFocus();
                }
                return;
            }
        }

        try {
            if (!isEditing()) {
                // Created without a credential first, then given one: an account
                // with no key is a legitimate state, so a failure to encode the
                // key must not roll back the account the user just named.
                Account created = accountManager.createAccount(
                        providerId, name, codex ? AuthType.BRIDGE_TOKEN : AuthType.API_KEY);
                if (codex) {
                    // A hand-configured account keeps its endpoint with the token.
                    accountManager.replaceCredential(created.getId(),
                            bridgeCredentialPayload);
                } else if (remember && !key.isEmpty()) {
                    accountManager.replaceCredential(created.getId(), CredentialPayload.forApiKey(key));
                }
                if (!enabled) {
                    accountManager.setEnabled(created.getId(), false);
                }
            } else {
                String accountId = account.getId();
                if (!name.equals(account.getDisplayName())) {
                    accountManager.rename(accountId, name);
                }
                if (enabled != account.isEnabled()) {
                    accountManager.setEnabled(accountId, enabled);
                }
                if (codex) {
                    if (!directOnly) {
                        accountManager.replaceCredential(accountId, bridgeCredentialPayload);
                    }
                } else if (remember && !key.isEmpty()) {
                    accountManager.replaceCredential(accountId, CredentialPayload.forApiKey(key));
                } else if (!remember) {
                    // Unticking "remember" is how a user forgets a key without
                    // deleting the account. §14.
                    accountManager.clearCredential(accountId);
                }
                if (codex && accountManager.hasDirectCredential(accountManager.find(accountId))
                        && directEnabledBox.isChecked() != account.isDirectEnabled()) {
                    accountManager.setDirectEnabled(accountId, directEnabledBox.isChecked());
                }
            }
        } catch (AuthException exception) {
            Toast.makeText(this, "无法保存密钥，请检查内容后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        // A key change or an enable/disable changes what every widget bound to
        // this account should show.
        new WidgetUpdateManager(this).updateAllWidgets();
        WidgetRefreshScheduler.schedule(this);
        Toast.makeText(this, isEditing() ? "已保存修改" : "已添加账户", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {        new AlertDialog.Builder(this)
                .setTitle("删除账户")
                .setMessage("将删除「" + account.getDisplayName() + "」及其余额历史。此操作无法撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    // The history cleaner is the usage layer's own delete, so
                    // the account layer never reaches into usage storage. §45.
                    accountManager.delete(account.getId(), usageRepository::deleteForAccount);
                    // Widgets bound to the deleted account fall back to the
                    // first enabled one rather than drawing nothing. Spec §39.
                    new WidgetUpdateManager(this).updateAllWidgets();
                    Toast.makeText(this, "已删除账户", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openPairing(boolean scan) {
        android.content.Intent pairing = new android.content.Intent(this,
                com.aiusage.monitor.ui.pair.PairActivity.class);
        pairing.putExtra(com.aiusage.monitor.ui.pair.PairActivity.EXTRA_START_SCAN, scan);
        pairing.putExtra(com.aiusage.monitor.ui.pair.PairActivity.EXTRA_ACCOUNT_NAME,
                nameInput.getText().toString());
        if (account != null) {
            pairing.putExtra(com.aiusage.monitor.ui.pair.PairActivity.EXTRA_ACCOUNT_ID,
                    account.getId());
        }
        startActivityForResult(pairing, REQUEST_PAIR);
    }

    private void updateDirectSummary() {
        if (account == null || !accountManager.hasDirectCredential(account)) {
            directSummary.setText("尚未授权");
            directEnabledBox.setChecked(false);
            directEnabledBox.setEnabled(false);
            directUnlinkButton.setVisibility(View.GONE);
            return;
        }
        directEnabledBox.setChecked(account.isDirectEnabled());
        directEnabledBox.setEnabled(true);
        directUnlinkButton.setVisibility(View.VISIBLE);
        String email = "";
        String workspace = "";
        try {
            AuthContext direct = accountManager.openDirectOAuthCredential(account);
            email = direct.get(AuthContext.KEY_OAUTH_EMAIL);
            workspace = direct.get(AuthContext.KEY_OAUTH_WORKSPACE);
        } catch (AuthException ignored) { }
        String state = account.isDirectNeedsAuth() ? "需要重新授权"
                : account.isDirectEnabled() ? "已授权并启用" : "已授权但已关闭";
        directSummary.setText(state + (email.isEmpty() ? "" : " · " + email)
                + (workspace.isEmpty() ? "" : "\n工作区标识：" + workspace));
    }

    private void openDirectAuth() {
        Intent intent = new Intent(this, CodexDeviceAuthActivity.class);
        if (isEditing()) {
            intent.putExtra(CodexDeviceAuthActivity.EXTRA_ACCOUNT_ID, account.getId());
        } else {
            intent.putExtra(CodexDeviceAuthActivity.EXTRA_ACCOUNT_NAME,
                    nameInput.getText().toString().trim());
        }
        startActivityForResult(intent, REQUEST_DIRECT);
    }

    private void confirmUnlinkDirect() {
        if (!isEditing() || !accountManager.hasDirectCredential(account)) return;
        new AlertDialog.Builder(this)
                .setTitle("解除手机授权")
                .setMessage("只删除手机上的 OAuth 凭据。Bridge 凭据、账户历史和 Widget 绑定会保留。")
                .setPositiveButton("解除", (dialog, which) -> {
                    accountManager.clearDirectOAuthCredential(account.getId());
                    account = accountManager.find(account.getId());
                    updateDirectSummary();
                    new WidgetUpdateManager(this).updateAllWidgets();
                    WidgetRefreshScheduler.schedule(this);
                    Toast.makeText(this, "已解除手机授权", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * A pairing that worked has already written its account, so this form has nothing
     * left to save: it closes, and the list reloads what the pairing created. Staying
     * here with a half-filled form would invite saving a *second* account for the same
     * computer.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DIRECT) {
            if (resultCode != RESULT_OK || data == null) return;
            String accountId = data.getStringExtra(CodexDeviceAuthActivity.EXTRA_ACCOUNT_ID);
            if (TextUtils.isEmpty(accountId)) return;
            new WidgetUpdateManager(this).updateAllWidgets();
            WidgetRefreshScheduler.schedule(this);
            if (!isEditing()) {
                Toast.makeText(this, "已创建手机直连账户", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            account = accountManager.find(accountId);
            updateDirectSummary();
            Toast.makeText(this, "手机 Direct 授权已保存", Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode != REQUEST_PAIR || resultCode != RESULT_OK || data == null) {
            return;
        }
        if (data.getStringExtra(com.aiusage.monitor.ui.pair.PairActivity.EXTRA_ACCOUNT_ID)
                == null) {
            return;
        }
        new WidgetUpdateManager(this).updateAllWidgets();
        Toast.makeText(this, "已配对，账户已添加", Toast.LENGTH_SHORT).show();
        finish();
    }
}
