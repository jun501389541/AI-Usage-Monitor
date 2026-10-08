package com.aiusage.monitor.ui.pair;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.bridge.ManualSource;
import com.aiusage.monitor.bridge.ManualTarget;
import com.aiusage.monitor.bridge.PairingClient;
import com.aiusage.monitor.bridge.PairingOffer;
import com.aiusage.monitor.bridge.PairingPayload;
import com.aiusage.monitor.bridge.PairingPayloadSource;
import com.aiusage.monitor.bridge.PairingStore;
import com.aiusage.monitor.bridge.TextOfferSource;
import com.aiusage.monitor.provider.codex.CodexProvider;
import com.aiusage.monitor.ui.UiKit;

import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/**
 * The pairing screen. Phase 7 step 9, Spec §20-§22.
 *
 * <p>The channels are separated by more than layout. A scanned, pasted or link-delivered
 * offer carries the certificate digest, so its connection is pinned before anything is
 * sent. A typed address and short code carry no digest, so this screen has to show the
 * one it found and get a person to say 「that is my computer」 before the code leaves
 * (docs/PHASE-7-PLAN.md A11). The confirmation is a checkbox beside a button whose
 * label quotes the digest it approves — and the client refuses the request without it,
 * so a screen cannot produce a pairing that skipped the question by being refactored
 * badly later.
 *
 * <p>Everything network-shaped runs on one background thread with results posted back:
 * a pairing waits seconds per address, and a blocked main thread on a phone is
 * indistinguishable from a crash.
 */
public final class PairActivity extends Activity {

    /**
     * Read on input: the account to point at the new pairing instead of creating one,
     * which is what 「重新配对」 means. Set on RESULT_OK: the account the pairing produced.
     */
    public static final String EXTRA_ACCOUNT_ID = "accountId";
    public static final String EXTRA_BRIDGE_ID = "bridgeId";
    public static final String EXTRA_START_SCAN = "startScan";
    public static final String EXTRA_ACCOUNT_NAME = "accountName";
    private static final int REQUEST_SCAN = 51;

    private final java.util.concurrent.ExecutorService network = Executors.newSingleThreadExecutor();

    private EditText offerInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText codeInput;
    private TextView statusView;
    private TextView digestView;
    private LinearLayout confirmRow;
    private CheckBox digestConfirmed;

    private PairingClient.Discovered discovered;
    private String rebindAccountId;
    private String accountName;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppGraph.get(this).ensureMigrated();
        Intent startedBy = getIntent();
        if (startedBy != null) {
            String candidate = startedBy.getStringExtra(EXTRA_ACCOUNT_ID);
            rebindAccountId = candidate == null || candidate.trim().isEmpty() ? null : candidate;
            accountName = startedBy.getStringExtra(EXTRA_ACCOUNT_NAME);
        }
        buildInterface();
        arriveByLink(getIntent());
        if (savedInstanceState == null && startedBy != null
                && startedBy.getBooleanExtra(EXTRA_START_SCAN, false)
                && !Intent.ACTION_VIEW.equals(startedBy.getAction())) {
            openScanner();
        }
    }

    private void openScanner() {
        if (!busy) {
            startActivityForResult(new Intent(this, QrScanActivity.class), REQUEST_SCAN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCAN && resultCode == RESULT_OK && data != null) {
            pairText(data.getStringExtra(QrScanActivity.EXTRA_QR_CONTENT), true);
        }
    }

    private void pairText(String content, boolean scanned) {
        if (content != null && content.trim().startsWith("https://")) {
            if (busy) return;
            busy = true;
            say("正在向电脑申请配对…");
            network.execute(() -> {
                try {
                    com.aiusage.monitor.bridge.LauncherInvitation invitation =
                            com.aiusage.monitor.bridge.LauncherInvitation.parse(content);
                    com.aiusage.monitor.bridge.LauncherPairingClient client =
                            new com.aiusage.monitor.bridge.LauncherPairingClient(
                                    com.aiusage.monitor.bridge.DeviceProfile.addresses());
                    store(client.pair(invitation, deviceName(),
                            message -> runOnUiThread(() -> { if (!isDestroyed()) say(message); })));
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                } catch (Exception failed) {
                    fail(failed instanceof javax.net.ssl.SSLException
                            ? "电脑证书与二维码指纹不一致，连接已中止。"
                            : failed.getMessage() == null ? "配对失败，请检查电脑共享设置。" : failed.getMessage());
                }
            });
        } else {
            startPairing(scanned ? TextOfferSource.fromQrCode(content) : TextOfferSource.fromPaste(content));
        }
    }

    @Override
    protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        arriveByLink(intent);
    }

    /** The {@code aiusage://pair#…} the system delivered, when opened by a link. */
    private void arriveByLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
                || intent.getData() == null) {
            return;
        }
        offerInput.setText(intent.getData().toString());
        startPairing(TextOfferSource.fromDeepLink(intent.getData().toString()));
    }

    // ------------------------------------------------------------------ layout

    private void buildInterface() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiKit.COLOR_BG);

        page.addView(UiKit.text(this, "与电脑配对", 22, UiKit.COLOR_TEXT, Typeface.BOLD),
                UiKit.matchWrap(this, 0));
        page.addView(UiKit.text(this, "让这台手机认识你的电脑；额度是从它身上读的。",
                13, UiKit.COLOR_MUTED, Typeface.NORMAL), UiKit.matchWrap(this, 6));

        LinearLayout scanCard = UiKit.card(this);
        scanCard.addView(UiKit.text(this, "扫码连接电脑", 18,
                UiKit.COLOR_TEXT, Typeface.BOLD), UiKit.matchWrap(this, 0));
        scanCard.addView(UiKit.text(this,
                "手机和电脑连接同一个 Wi-Fi。在电脑端打开“添加设备”，然后扫描配对二维码。",
                13, UiKit.COLOR_MUTED, Typeface.NORMAL), UiKit.matchWrap(this, 8));
        TextView scanButton = UiKit.actionButton(this, "扫描电脑上的二维码", true);
        scanButton.setOnClickListener(view -> openScanner());
        scanCard.addView(scanButton, UiKit.matchHeight(this, 50, 12));
        statusView = UiKit.text(this, "", 13, UiKit.COLOR_MUTED, Typeface.NORMAL);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        scanCard.addView(statusView, UiKit.matchWrap(this, 12));
        page.addView(scanCard, UiKit.matchWrap(this, 16));

        // -------------------------------------------------- channel: the whole offer
        LinearLayout offerCard = UiKit.card(this);
        offerCard.addView(label("从电脑复制的配对内容"), UiKit.matchWrap(this, 0));
        offerInput = new EditText(this);
        offerInput.setMinLines(2);
        offerInput.setTextSize(14);
        offerInput.setTextColor(UiKit.COLOR_TEXT);
        offerInput.setHint(PairingPayload.PREFIX + "…");
        offerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        offerInput.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        offerInput.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14),
                UiKit.dp(this, 12));
        offerCard.addView(offerInput, UiKit.matchWrap(this, 10));
        TextView offerButton = UiKit.actionButton(this, "用这份内容配对", true);
        offerButton.setContentDescription("用粘贴的配对内容配对");
        offerButton.setOnClickListener(view ->
                pairText(offerInput.getText().toString(), false));
        offerCard.addView(offerButton, UiKit.matchHeight(this, 50, 12));
        offerCard.addView(UiKit.text(this,
                "电脑端执行 --add-device 会打印这串内容，从终端整段复制过来即可。",
                12, UiKit.COLOR_HINT, Typeface.NORMAL), UiKit.matchWrap(this, 8));
        page.addView(offerCard, UiKit.matchWrap(this, 16));

        // --------------------------------------------- channel: typed address and code
        LinearLayout manualCard = UiKit.card(this);
        manualCard.addView(label("手输电脑地址与配对码"), UiKit.matchWrap(this, 0));
        hostInput = field(InputType.TYPE_CLASS_TEXT, "电脑 IP，例如 192.168.1.20");
        manualCard.addView(hostInput, UiKit.matchWrap(this, 10));
        portInput = field(InputType.TYPE_CLASS_NUMBER,
                "端口，默认 " + ManualTarget.DEFAULT_PORT);
        manualCard.addView(portInput, UiKit.matchWrap(this, 8));
        // Password input type: not because the code is a long-lived secret, but because
        // the alternative is an IME that replaces characters while typing it.
        codeInput = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                "电脑终端上的 8 位配对码");
        manualCard.addView(codeInput, UiKit.matchWrap(this, 8));
        TextView discoverButton = UiKit.actionButton(this, "连接并显示指纹", false);
        discoverButton.setContentDescription("连接并显示指纹");
        discoverButton.setOnClickListener(view -> startDiscovery());
        manualCard.addView(discoverButton, UiKit.matchHeight(this, 50, 12));

        digestView = UiKit.text(this, "", 15, UiKit.COLOR_TEXT, Typeface.BOLD);
        digestView.setTextIsSelectable(true);
        manualCard.addView(digestView, UiKit.matchWrap(this, 12));

        confirmRow = new LinearLayout(this);
        confirmRow.setOrientation(LinearLayout.VERTICAL);
        confirmRow.setVisibility(View.GONE);
        digestConfirmed = new CheckBox(this);
        digestConfirmed.setTextSize(13);
        digestConfirmed.setTextColor(UiKit.COLOR_MUTED);
        digestConfirmed.setText("上面这串指纹尾号，和电脑终端上显示的完全一致");
        confirmRow.addView(digestConfirmed, UiKit.matchWrap(this, 0));
        TextView pairButton = UiKit.actionButton(this, "确认无误，发送配对码", true);
        pairButton.setContentDescription("确认无误，发送配对码");
        pairButton.setOnClickListener(view -> startManualExchange());
        confirmRow.addView(pairButton, UiKit.matchHeight(this, 50, 8));
        manualCard.addView(confirmRow, UiKit.matchWrap(this, 4));
        page.addView(manualCard, UiKit.matchWrap(this, 16));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        applyInsets(scroll);
    }

    private TextView label(String value) {
        TextView label = UiKit.text(this, value, 11, UiKit.COLOR_MUTED, Typeface.BOLD);
        label.setLetterSpacing(0.12f);
        return label;
    }

    private EditText field(int inputType, String hint) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setTextSize(15);
        view.setTextColor(UiKit.COLOR_TEXT);
        view.setHint(hint);
        view.setInputType(inputType);
        view.setBackground(UiKit.roundRect(this, UiKit.COLOR_INPUT, UiKit.COLOR_BORDER, 12, 1));
        view.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13), UiKit.dp(this, 14),
                UiKit.dp(this, 13));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        return view;
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                android.graphics.Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 22) + bars.top,
                        UiKit.dp(this, 20), UiKit.dp(this, 22) + bars.bottom);
                return windowInsets;
            });
            root.requestApplyInsets();
        }
    }

    // ----------------------------------------------------------------- pairing

    private void startPairing(final PairingPayloadSource source) {
        if (busy) {
            return;
        }
        busy = true;
        say("正在通过「" + source.label() + "」配对…");
        network.execute(() -> {
            try {
                PairingOffer offer = source.read();
                if (!offer.hasPayload()) {
                    // Reached only if a future channel produces a manual target from
                    // this button; that path needs the confirmation step below.
                    fail("这条通道需要先核对电脑终端上显示的指纹");
                    return;
                }
                store(client().pair(offer.payload(), deviceName()));
            } catch (PairingClient.PairingFailed failed) {
                fail(failed.getMessage());
            } catch (PairingPayload.Invalid invalid) {
                fail(invalid.getMessage());
            }
        });
    }

    private void startDiscovery() {
        if (busy) {
            return;
        }
        final ManualTarget target;
        try {
            target = typedTarget().read().manual();
        } catch (PairingPayload.Invalid invalid) {
            say(invalid.getMessage());
            return;
        }
        busy = true;
        confirmRow.setVisibility(View.GONE);
        digestView.setText("");
        say("正在读取这台电脑的证书…");
        network.execute(() -> {
            try {
                showDiscovered(client().discover(target));
            } catch (PairingClient.PairingFailed failed) {
                fail(failed.getMessage());
            }
        });
    }

    private void showDiscovered(final PairingClient.Discovered found) {
        runOnUiThread(() -> {
            busy = false;
            if (isFinishing()) {
                return;
            }
            discovered = found;
            digestView.setText("指纹尾号 " + found.tail() + "（完整指纹的前 8 位）");
            confirmRow.setVisibility(View.VISIBLE);
            digestConfirmed.setChecked(false);
            say("请与电脑终端上打印的尾号逐个字符比对，一致才继续。");
        });
    }

    private void startManualExchange() {
        if (busy || discovered == null) {
            return;
        }
        if (!digestConfirmed.isChecked()) {
            say("请先勾选确认，配对码不会发出。");
            return;
        }
        final ManualTarget target;
        try {
            target = typedTarget().read().manual();
        } catch (PairingPayload.Invalid invalid) {
            say(invalid.getMessage());
            return;
        }
        busy = true;
        say("正在发送配对码…");
        // Read the discovered digest into a local before leaving the UI thread: the
        // field is written there, and a background read of it could otherwise see the
        // value a later discovery replaced.
        final PairingClient.Discovered shown = discovered;
        network.execute(() -> {
            try {
                store(client().exchangeManual(target, shown, true, deviceName()));
            } catch (PairingClient.PairingFailed failed) {
                fail(failed.getMessage());
            }
        });
    }

    private ManualSource typedTarget() {
        return new ManualSource(hostInput.getText().toString(), typedPort(),
                codeInput.getText().toString());
    }

    /**
     * An empty port box means the port the Bridge listens on by default; anything
     * unparseable becomes -1 and {@code ManualTarget} refuses it with its own message,
     * so the range rule stays in one place instead of being guessed twice.
     */
    private int typedPort() {
        String trimmed = portInput.getText().toString().trim();
        if (trimmed.isEmpty()) {
            return ManualTarget.DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** The pairing worked; now make it survive in storage. */
    private void store(PairingClient.Paired paired) {
        if (Thread.currentThread().isInterrupted() || isDestroyed()) return;
        try {
            PairingStore pairing = AppGraph.get(this).pairingStore();
            // An account came in with this intent, so this is a re-pairing of that
            // account and not a new one: rebind keeps the id, the history and the slots.
            PairingStore.Stored stored = rebindAccountId == null
                    ? pairing.record(paired, CodexProvider.ID,
                            accountName == null || accountName.trim().isEmpty()
                                    ? paired.remoteAccountName().isEmpty() ? paired.bridge().getName()
                                            : paired.remoteAccountName() : accountName.trim())
                    : pairing.rebind(paired, rebindAccountId);
            paired.acknowledge();
            succeeded(stored);
        } catch (java.io.IOException failed) {
            fail("账户已保存，但电脑未收到完成回执，请检查连接后在该账户重新配对。");
        } catch (PairingStore.NotStored notStored) {
            fail(notStored.getMessage());
        } catch (AuthException exception) {
            fail("这台手机没能保存配对结果：" + exception.getMessage());
        } catch (RuntimeException exception) {
            // Deliberately broad: a storage failure on a background thread must show up
            // on screen rather than killing the process with nobody watching.
            fail("这台手机没能保存配对结果：" + exception.getMessage());
        }
    }

    private void succeeded(final PairingStore.Stored stored) {
        runOnUiThread(() -> {
            busy = false;
            if (isFinishing()) {
                return;
            }
            Toast.makeText(this, "配对成功", Toast.LENGTH_SHORT).show();
            Intent data = new Intent();
            data.putExtra(EXTRA_ACCOUNT_ID, stored.account().getId());
            data.putExtra(EXTRA_BRIDGE_ID, stored.bridge().getId());
            setResult(RESULT_OK, data);
            finish();
        });
    }

    private void fail(final String message) {
        runOnUiThread(() -> {
            busy = false;
            if (isFinishing()) {
                return;
            }
            say(message);
        });
    }

    private String deviceName() {
        return Build.MODEL == null ? "Android" : Build.MODEL;
    }

    private PairingClient client() {
        return AppGraph.get(this).pairingClient();
    }

    private void say(String message) {
        statusView.setText(message);
    }
}
