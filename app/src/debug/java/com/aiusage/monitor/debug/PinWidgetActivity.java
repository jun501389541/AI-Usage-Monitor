package com.aiusage.monitor.debug;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.aiusage.monitor.widget.BalanceWidget2x1Provider;
import com.aiusage.monitor.widget.BalanceWidget2x2Provider;
import com.aiusage.monitor.widget.BalanceWidget4x2Provider;

/**
 * Debug-variant-only test affordance: asks the launcher to pin one of this app's
 * widgets, then finishes.
 *
 * <p>It exists because the device smoke test needs a real widget instance on the
 * home screen, and the launcher's long-press / drag-and-drop gesture is not
 * reliably scriptable over adb. {@link AppWidgetManager#requestPinAppWidget} is
 * the supported public API for "the app that owns the provider asks the launcher
 * to pin it", so the whole flow becomes a system dialog with a button.
 *
 * <p>This class lives in {@code app/src/debug} and is therefore compiled into
 * debug builds only; it never reaches a release APK and does not touch any
 * production code path.
 *
 * <p>Usage: {@code adb shell am start -n com.aiusage.monitor.debug/com.aiusage.monitor.debug.PinWidgetActivity --es size 4x2}
 * where {@code size} is one of {@code 4x2} (default), {@code 2x2}, {@code 2x1}.
 */
public final class PinWidgetActivity extends Activity {

    private static final String TAG = "PinWidget";

    /** Intent extra naming which widget size to pin: {@code 4x2}, {@code 2x2} or {@code 2x1}. */
    public static final String EXTRA_SIZE = "size";

    private static final String DEFAULT_SIZE = "4x2";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "PIN-UNSUPPORTED: requestPinAppWidget needs API 26+");
            finish();
            return;
        }

        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (!manager.isRequestPinAppWidgetSupported()) {
            Log.i(TAG, "PIN-UNSUPPORTED: launcher does not support requestPinAppWidget");
            finish();
            return;
        }

        String size = getIntent().getStringExtra(EXTRA_SIZE);
        ComponentName provider = providerFor(size == null ? DEFAULT_SIZE : size);
        Log.i(TAG, "PIN-PROVIDER: " + provider.getClassName() + " requestedSize=" + size);

        Intent callback = new Intent(this, PinWidgetActivity.class)
                .setAction(getPackageName() + ".PINNED");
        PendingIntent success = PendingIntent.getActivity(this, 0, callback,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean accepted = manager.requestPinAppWidget(provider, null, success);
        Log.i(TAG, "PIN-REQUESTED: accepted=" + accepted);
        finish();
    }

    private ComponentName providerFor(String size) {
        if ("2x1".equals(size)) {
            return new ComponentName(getPackageName(), BalanceWidget2x1Provider.class.getName());
        }
        if ("2x2".equals(size)) {
            return new ComponentName(getPackageName(), BalanceWidget2x2Provider.class.getName());
        }
        return new ComponentName(getPackageName(), BalanceWidget4x2Provider.class.getName());
    }
}
