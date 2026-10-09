package com.aiusage.monitor.ui.account;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.CodexOAuthClient;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.ui.UiKit;
import com.aiusage.monitor.widget.WidgetRefreshScheduler;
import com.aiusage.monitor.widget.WidgetUpdateManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the experimental Codex device-code login entirely on the phone. */
public final class CodexDeviceAuthActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "com.aiusage.monitor.extra.DIRECT_ACCOUNT_ID";
    public static final String EXTRA_ACCOUNT_NAME = "com.aiusage.monitor.extra.DIRECT_ACCOUNT_NAME";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CodexOAuthClient oauthClient = new CodexOAuthClient();
    private AccountManager accountManager;
    private Account targetAccount;
    private String requestedName;
    private CodexOAuthClient.DeviceCode deviceCode;
    private CodexOAuthClient.Tokens pendingTokens;
    private TextView status;
    private TextView codeText;
    private TextView browserButton;
    private TextView startButton;
    private TextView cancelButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppGraph graph = AppGraph.get(this);
        graph.ensureMigrated();
        accountManager = graph.accountManager();
        String targetId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        targetAccount = TextUtils.isEmpty(targetId) ? null : accountManager.find(targetId);
        requestedName = getIntent().getStringExtra(EXTRA_ACCOUNT_NAME);
        if (targetAccount != null && !com.aiusage.monitor.provider.codex.CodexProvider.ID
                .equals(targetAccount.getProviderId())) {
            targetAccount = null;
        }
        buildInterface();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.COLOR_BG);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 28),
                UiKit.dp(this, 20), UiKit.dp(this, 28));
        scroll.addView(content, UiKit.matchWrap(this, 0));

        TextView title = UiKit.text(this, "手机授权 Codex", 28, UiKit.COLOR_TEXT, Typeface.BOLD);
        content.addView(title, UiKit.matchWrap(this, 8));
        TextView explanation = UiKit.text(this,
                "授权由本机直接完成，电脑端 Bridge 不参与。系统会打开 OpenAI 官方页面；如果浏览器已有会话，页面可能直接沿用。ChatGPT App 的登录状态不会被读取或复制。",
                14, UiKit.COLOR_MUTED, Typeface.NORMAL);
        explanation.setLineSpacing(UiKit.dp(this, 3), 1.12f);
        content.addView(explanation, UiKit.matchWrap(this, 20));

        LinearLayout card = UiKit.card(this);
        status = UiKit.text(this, "设备码有效期最多 15 分钟。授权可以随时取消。", 13,
                UiKit.COLOR_HINT, Typeface.NORMAL);
        status.setLineSpacing(UiKit.dp(this, 2), 1.12f);
        card.addView(status, UiKit.matchWrap(this, 0));
        codeText = UiKit.text(this, "", 25, UiKit.COLOR_TEXT, Typeface.BOLD);
        codeText.setGravity(Gravity.CENTER);
        codeText.setTextIsSelectable(true);
        card.addView(codeText, UiKit.matchWrap(this, 14));
        TextView copyButton = UiKit.actionButton(this, "复制一次性设备码", false);
        copyButton.setOnClickListener(view -> copyCode());
        card.addView(copyButton, UiKit.matchHeight(this, 48, 8));
        browserButton = UiKit.actionButton(this, "打开 OpenAI 官方授权页", true);
        browserButton.setVisibility(View.GONE);
        browserButton.setOnClickListener(view -> openVerificationPage());
        card.addView(browserButton, UiKit.matchHeight(this, 50, 8));
        content.addView(card, UiKit.matchWrap(this, 20));

        startButton = UiKit.actionButton(this, "开始授权", true);
        startButton.setOnClickListener(view -> begin());
        content.addView(startButton, UiKit.matchHeight(this, 52, 0));
        cancelButton = UiKit.actionButton(this, "取消", false);
        cancelButton.setOnClickListener(view -> cancel());
        content.addView(cancelButton, UiKit.matchHeight(this, 48, 8));
        setContentView(scroll);
    }

    private void begin() {
        if (deviceCode != null || pendingTokens != null) return;
        cancelled.set(false);
        startButton.setEnabled(false);
        status.setText("正在向 OpenAI 请求设备码…");
        executor.execute(() -> {
            try {
                deviceCode = oauthClient.requestDeviceCode();
                main.post(() -> {
                    if (isFinishing() || cancelled.get()) return;
                    codeText.setText(deviceCode.userCode);
                    browserButton.setVisibility(View.VISIBLE);
                    status.setText("请在官方页面输入设备码。完成后本页会自动继续，最多等待 15 分钟。");
                    openVerificationPage();
                });
                CodexOAuthClient.Tokens tokens = oauthClient.authorize(deviceCode, cancelled::get);
                main.post(() -> confirmAssociation(tokens));
            } catch (AuthException failure) {
                main.post(() -> {
                    if (cancelled.get() || isFinishing()) return;
                    status.setText(failure.getMessage());
                    startButton.setText("重新开始授权");
                    startButton.setEnabled(true);
                    deviceCode = null;
                });
            }
        });
    }

    private void openVerificationPage() {
        if (deviceCode == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CodexOAuthClient.DEVICE_PAGE)));
        } catch (RuntimeException noBrowser) {
            status.setText("无法自动打开浏览器。请复制设备码后，手动访问 "
                    + CodexOAuthClient.DEVICE_PAGE);
        }
    }

    private void copyCode() {
        if (deviceCode == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Codex device code", deviceCode.userCode));
            Toast.makeText(this, "一次性设备码已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAssociation(CodexOAuthClient.Tokens tokens) {
        if (cancelled.get() || isFinishing()) return;
        pendingTokens = tokens;
        String email = tokens.email.isEmpty() ? "邮箱声明未返回" : tokens.email;
        String workspace = tokens.workspaceId.isEmpty() ? "工作区标识未返回"
                : tokens.workspaceId;
        StringBuilder message = new StringBuilder()
                .append("Direct 账号：").append(email)
                .append("\n工作区标识：").append(workspace)
                .append("\n\n这是 OAuth 令牌中的资料，不能证明它与 Bridge 登录身份相同。关联由你手动确认。");
        if (targetAccount == null) {
            String name = TextUtils.isEmpty(requestedName) ? "Codex 手机直连" : requestedName.trim();
            message.insert(0, "将创建独立账户「" + name + "」。\n\n");
        } else {
            message.insert(0, "Bridge 卡片：「" + targetAccount.getDisplayName() + "」\n");
            String oldIdentity = targetAccount.getDirectIdentityHash();
            if (!oldIdentity.isEmpty() && !oldIdentity.equals(tokens.identityHash())) {
                message.insert(0, "检测到账号或工作区与上次授权不同，需重新确认关联。\n");
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("确认手机账号")
                .setMessage(message.toString())
                .setPositiveButton("确认并安全保存", (dialog, which) -> persistTokens())
                .setNegativeButton("取消授权", (dialog, which) -> {
                    pendingTokens = null;
                    cancel();
                })
                .setOnCancelListener(dialog -> {
                    pendingTokens = null;
                    cancel();
                })
                .show();
    }

    private void persistTokens() {
        CodexOAuthClient.Tokens tokens = pendingTokens;
        if (tokens == null) return;
        pendingTokens = null;
        status.setText("正在使用 Android Keystore 保存手机凭据…");
        startButton.setEnabled(false);
        executor.execute(() -> {
            try {
                Account saved;
                String payload = tokens.toCredentialPayload();
                if (targetAccount == null) {
                    String name = TextUtils.isEmpty(requestedName)
                            ? "Codex 手机直连" : requestedName.trim();
                    saved = accountManager.createDirectOAuthAccount(name, payload,
                            tokens.identityHash());
                } else {
                    accountManager.saveDirectOAuthCredential(targetAccount.getId(), payload,
                            tokens.identityHash());
                    saved = accountManager.find(targetAccount.getId());
                }
                String savedId = saved.getId();
                AppGraph.get(this).refreshManager().refresh(savedId);
                main.post(() -> {
                    new WidgetUpdateManager(this).updateAllWidgets();
                    WidgetRefreshScheduler.schedule(this);
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACCOUNT_ID, savedId));
                    finish();
                });
            } catch (AuthException | RuntimeException failure) {
                main.post(() -> {
                    status.setText(failure instanceof AuthException
                            ? failure.getMessage()
                            : "账户已变化，未保存手机授权。请返回列表后重新操作。");
                    startButton.setText("重新开始授权");
                    startButton.setEnabled(true);
                    deviceCode = null;
                });
            }
        });
    }

    private void cancel() {
        cancelled.set(true);
        pendingTokens = null;
        executor.shutdownNow();
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override protected void onDestroy() {
        cancelled.set(true);
        if (!executor.isShutdown()) executor.shutdownNow();
        super.onDestroy();
    }
}
