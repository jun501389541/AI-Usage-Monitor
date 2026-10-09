package com.aiusage.monitor.ui.notification;

import android.app.Activity;
import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.notification.NotificationScheduler;
import com.aiusage.monitor.notification.NotificationSettings;
import com.aiusage.monitor.ui.UiKit;

/** System permissions and the global notification switch. */
public final class NotificationSettingsActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "com.aiusage.monitor.notification.ACCOUNT_ID";
    private static final int REQUEST_POST_NOTIFICATIONS = 7401;

    private AppGraph graph;
    private NotificationSettings settings;
    private String accountId;
    private TextView notificationPermissionStatus;
    private TextView exactAlarmStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        graph = AppGraph.get(this);
        graph.ensureMigrated();
        settings = new NotificationSettings(graph.settings());
        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        if (accountId == null) accountId = "";
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
        TextView title = UiKit.text(this, "通知设置", 30, UiKit.COLOR_TEXT, Typeface.BOLD);
        content.addView(title, UiKit.matchWrap(this, 8));
        TextView subtitle = UiKit.text(this, "按峰谷切换和额度预计重置时间提醒", 14,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        content.addView(subtitle, UiKit.matchWrap(this, 8));

        LinearLayout systemCard = UiKit.card(this);
        content.addView(systemCard, UiKit.matchWrap(this, 20));
        TextView systemHeading = UiKit.text(this, "系统权限", 16, UiKit.COLOR_TEXT, Typeface.BOLD);
        systemCard.addView(systemHeading, UiKit.matchWrap(this, 0));
        notificationPermissionStatus = UiKit.text(this, "通知权限：检查中", 13,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        notificationPermissionStatus.setLineSpacing(UiKit.dp(this, 2), 1.1f);
        systemCard.addView(notificationPermissionStatus, UiKit.matchWrap(this, 10));
        TextView notificationSettingsButton = UiKit.actionButton(this, "打开系统通知设置", false);
        notificationSettingsButton.setOnClickListener(view -> openNotificationSystemSettings());
        systemCard.addView(notificationSettingsButton, UiKit.matchWrap(this, 8));
        exactAlarmStatus = UiKit.text(this, "精确闹钟：检查中", 13,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        exactAlarmStatus.setLineSpacing(UiKit.dp(this, 2), 1.1f);
        systemCard.addView(exactAlarmStatus, UiKit.matchWrap(this, 14));
        TextView exactAlarmButton = UiKit.actionButton(this, "允许精确提醒", false);
        exactAlarmButton.setOnClickListener(view -> requestExactAlarmAccess());
        systemCard.addView(exactAlarmButton, UiKit.matchWrap(this, 8));

        LinearLayout globalCard = UiKit.card(this);
        content.addView(globalCard, UiKit.matchWrap(this, 14));
        TextView globalHeading = UiKit.text(this, "全局通知", 16, UiKit.COLOR_TEXT, Typeface.BOLD);
        globalCard.addView(globalHeading, UiKit.matchWrap(this, 0));
        addToggle(globalCard, "开启通知", "关闭后暂停所有通知", settings.isEnabled(), 10,
                checked -> setMasterEnabled(checked));
        TextView providerSettingsHelp = UiKit.text(this,
                "分别设置 DeepSeek、Codex 及每个 Codex 账户的提醒。", 11,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        providerSettingsHelp.setLineSpacing(UiKit.dp(this, 2), 1.1f);
        globalCard.addView(providerSettingsHelp, UiKit.matchWrap(this, 8));
        TextView providerSettingsButton = UiKit.actionButton(this, "平台与账户提醒设置", false);
        providerSettingsButton.setOnClickListener(view -> openProviderSettings());
        globalCard.addView(providerSettingsButton, UiKit.matchWrap(this, 12));

        TextView note = UiKit.text(this,
                "重置提醒按服务商提供的时间预计触发，不会额外联网轮询；系统省电策略可能造成延迟。",
                11, UiKit.COLOR_HINT, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(UiKit.dp(this, 2), 1.15f);
        content.addView(note, UiKit.matchWrap(this, 16));
        setContentView(scroll);
        applyInsets(scroll);
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

    private void setMasterEnabled(boolean enabled) {
        settings.setEnabled(enabled, System.currentTimeMillis());
        NotificationScheduler.reconcile(this);
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
            } else if (Build.VERSION.SDK_INT >= 31) {
                requestExactAlarmAccess();
            }
        }
        updatePermissionStatus();
    }

    private void openProviderSettings() {
        Intent intent = new Intent(this, NotificationProviderSettingsActivity.class);
        if (!accountId.isEmpty()) intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        startActivity(intent);
    }

    private void openNotificationSystemSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < 31) {
            Toast.makeText(this, "此 Android 版本不需要单独授权精确闹钟", Toast.LENGTH_SHORT).show();
            return;
        }
        AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (manager != null && manager.canScheduleExactAlarms()) {
            Toast.makeText(this, "精确提醒已允许", Toast.LENGTH_SHORT).show();
            updatePermissionStatus();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void updatePermissionStatus() {
        if (notificationPermissionStatus == null) return;
        boolean notificationGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        notificationPermissionStatus.setText(notificationGranted
                ? "通知权限：已允许" : "通知权限：未允许\n点击上方按钮前往系统设置");
        boolean exactAllowed = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
            exactAllowed = manager != null && manager.canScheduleExactAlarms();
        }
        exactAlarmStatus.setText(exactAllowed
                ? "精确闹钟：已允许或系统不需要单独授权"
                : "精确闹钟：未允许\n将使用可能延迟的普通闹钟");
    }

    @Override protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        NotificationScheduler.reconcile(this);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS && Build.VERSION.SDK_INT >= 31) {
            requestExactAlarmAccess();
        }
        updatePermissionStatus();
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
