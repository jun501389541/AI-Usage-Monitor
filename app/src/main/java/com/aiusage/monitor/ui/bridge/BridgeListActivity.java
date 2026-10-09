package com.aiusage.monitor.ui.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.bridge.BridgeRepository;
import com.aiusage.monitor.bridge.ManualTarget;
import com.aiusage.monitor.bridge.PairingClient;
import com.aiusage.monitor.model.Bridge;
import com.aiusage.monitor.ui.UiKit;
import com.aiusage.monitor.ui.pair.PairActivity;

import java.util.List;

/**
 * The paired computers on this phone. Phase 7 step 9, plan §3.2.
 *
 * <p>What belongs here is the pair of facts a user needs when a computer stops
 * answering: which address the phone is dialling, and the digest it expects to meet
 * there. Revocation is not offered — the device token is revoked on the computer, and
 * a phone forgetting a pairing is a different action that must not be labelled the
 * same way. Forgetting one here leaves its accounts pointing at nothing, which the
 * reader reports as 「还没有与这台电脑配对」 rather than as a computer being offline; the
 * dialog says so because the two are otherwise easy to confuse.
 */
public final class BridgeListActivity extends Activity {

    private AppGraph graph;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        graph = AppGraph.get(this);
        graph.ensureMigrated();

        configureWindow();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiKit.COLOR_BG);

        TextView kicker = UiKit.text(this, "AI USAGE MONITOR", 11,
                UiKit.COLOR_MUTED, Typeface.BOLD);
        kicker.setLetterSpacing(0.18f);
        page.addView(kicker, UiKit.matchWrap(this, 0));

        TextView title = UiKit.text(this, "已配对的电脑", 31, UiKit.COLOR_TEXT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        page.addView(title, UiKit.matchWrap(this, 8));

        TextView subtitle = UiKit.text(this,
                "手机靠证书指纹认电脑，不靠 IP；电脑换网段时改地址就好。",
                14, UiKit.COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(0f, 1.15f);
        page.addView(subtitle, UiKit.matchWrap(this, 8));

        TextView add = UiKit.actionButton(this, "配对新电脑", true);
        add.setContentDescription("配对新电脑");
        add.setOnClickListener(view -> startActivity(new Intent(this, PairActivity.class)));
        page.addView(add, UiKit.matchHeight(this, 52, 14));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, UiKit.matchWrap(this, 16));

        TextView empty = UiKit.text(this, "还没有配对过电脑。", 13, UiKit.COLOR_HINT,
                Typeface.NORMAL);
        empty.setVisibility(View.GONE);
        empty.setTag("empty");
        page.addView(empty, UiKit.matchWrap(this, 8));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setScrollbarFadingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(UiKit.COLOR_BG);
        scroll.addView(page, new ScrollView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 22), UiKit.dp(this, 20),
                UiKit.dp(this, 22));
        setContentView(scroll);
        applyInsets(scroll);
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
        render();
    }

    private void render() {
        list.removeAllViews();
        List<Bridge> bridges = graph.bridgeRepository().findAll();
        View tagged = list.getRootView().findViewWithTag("empty");
        if (tagged instanceof TextView) {
            tagged.setVisibility(bridges.isEmpty() ? View.VISIBLE : View.GONE);
        }
        for (Bridge bridge : bridges) {
            list.addView(row(bridge), UiKit.matchWrap(this, 10));
        }
    }

    private View row(Bridge bridge) {
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.text(this, bridge.getName(), 15, UiKit.COLOR_TEXT, Typeface.BOLD),
                UiKit.matchWrap(this, 0));
        card.addView(UiKit.text(this, bridge.getBaseUrl(), 13, UiKit.COLOR_MUTED,
                Typeface.NORMAL), UiKit.matchWrap(this, 4));
        card.addView(UiKit.text(this, "指纹尾号 " + ManualTarget.tailOf(bridge.getFingerprint())
                        + " · " + lastSeenWords(bridge.getLastSeen()),
                12, UiKit.COLOR_HINT, Typeface.NORMAL), UiKit.matchWrap(this, 4));

        TextView forget = UiKit.actionButton(this, "忘记这台电脑", false);
        forget.setContentDescription("忘记这台电脑");
        forget.setOnClickListener(view -> confirmForget(bridge));
        card.addView(forget, UiKit.matchHeight(this, 46, 10));
        return card;
    }

    private String lastSeenWords(long atMs) {
        if (atMs <= 0L) {
            return "还没读到过数据";
        }
        long ago = System.currentTimeMillis() - atMs;
        if (ago < 60_000L) {
            return "上次读到：刚刚";
        }
        if (ago < 3_600_000L) {
            return "上次读到：" + (ago / 60_000L) + " 分钟前";
        }
        return "上次读到：" + (ago / 3_600_000L) + " 小时前";
    }

    private void confirmForget(Bridge bridge) {
        new AlertDialog.Builder(this)
                .setTitle("忘记这台电脑")
                .setMessage("指纹与地址都会被删除，指向它的账户会显示「还没有与这台电脑配对」，"
                        + "直到重新配对。账户的历史记录不会删除。")
                .setPositiveButton("忘记", (dialog, which) -> {
                    graph.bridgeRepository().delete(bridge.getId());
                    render();
                    Toast.makeText(this, "已忘记这台电脑", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
