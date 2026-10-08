package com.aiusage.monitor.ui.pair;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aiusage.monitor.bridge.PairingPayload;
import com.aiusage.monitor.ui.UiKit;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline scanner. Only validated pairing offers leave this non-exported activity. */
public final class QrScanActivity extends Activity {
    public static final String EXTRA_QR_CONTENT = "qrContent";
    private static final int REQUEST_CAMERA = 61;
    private static final int REQUEST_IMAGE = 62;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private DecoratedBarcodeView scanner;
    private TextView status;
    private TextView permissionButton;
    private boolean permissionAsked;
    private boolean resumed;
    private boolean decodingImage;
    private boolean completed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        permissionAsked = state != null && state.getBoolean("permissionAsked");
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiKit.COLOR_BG);
        page.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 24),
                UiKit.dp(this, 20), UiKit.dp(this, 24));
        page.addView(UiKit.text(this, "扫码连接电脑", 22, UiKit.COLOR_TEXT, Typeface.BOLD),
                UiKit.matchWrap(this, 0));
        page.addView(UiKit.text(this,
                "手机和电脑连接同一个 Wi-Fi，将电脑端“添加设备”的二维码放入框内。",
                14, UiKit.COLOR_MUTED, Typeface.NORMAL), UiKit.matchWrap(this, 10));
        scanner = new DecoratedBarcodeView(this);
        scanner.setStatusText("对准电脑上的配对二维码");
        scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(
                Collections.singletonList(BarcodeFormat.QR_CODE)));
        scanner.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (!decodingImage) accept(result.getText());
            }
        });
        scanner.getBarcodeView().addStateListener(new CameraPreview.StateListener() {
            public void previewSized() { }
            public void previewStarted() { }
            public void previewStopped() { }
            public void cameraClosed() { }
            public void cameraError(Exception error) {
                say("相机暂时无法打开，请关闭其它使用相机的应用，或从相册选择二维码。");
            }
        });
        LinearLayout.LayoutParams preview = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        preview.topMargin = UiKit.dp(this, 18);
        page.addView(scanner, preview);
        status = UiKit.text(this, "识别后自动配对，无需输入地址。", 13,
                UiKit.COLOR_MUTED, Typeface.NORMAL);
        page.addView(status, UiKit.matchWrap(this, 12));
        permissionButton = UiKit.actionButton(this, "允许使用相机", false);
        permissionButton.setVisibility(View.GONE);
        permissionButton.setOnClickListener(view -> {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requestCamera();
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        page.addView(permissionButton, UiKit.matchHeight(this, 48, 8));
        TextView gallery = UiKit.actionButton(this, "从相册选择二维码", true);
        gallery.setOnClickListener(view -> {
            if (decodingImage || completed) return;
            Intent image = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            image.addCategory(Intent.CATEGORY_OPENABLE);
            image.setType("image/*");
            try {
                startActivityForResult(image, REQUEST_IMAGE);
            } catch (android.content.ActivityNotFoundException unavailable) {
                say("这台设备没有图片选择器，请使用相机扫码。");
            }
        });
        page.addView(gallery, UiKit.matchHeight(this, 50, 12));
        TextView cancel = UiKit.actionButton(this, "返回", false);
        cancel.setOnClickListener(view -> finish());
        page.addView(cancel, UiKit.matchHeight(this, 46, 8));
        setContentView(page);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 24) + bars.top,
                        UiKit.dp(this, 20), UiKit.dp(this, 24) + bars.bottom);
                return insets;
            });
            page.requestApplyInsets();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraAuthorized();
            resumeCamera();
        } else if (!permissionAsked) {
            requestCamera();
        } else {
            showPermissionHelp();
        }
    }

    private void requestCamera() {
        permissionAsked = true;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    private void resumeCamera() {
        if (resumed && !completed && !decodingImage
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.resume();
        }
    }

    private void cameraAuthorized() {
        if (permissionButton.getVisibility() == View.VISIBLE) {
            say("识别后自动配对，无需输入地址。");
        }
        permissionButton.setVisibility(View.GONE);
    }

    private void showPermissionHelp() {
        say("扫码需要相机权限；你也可以从相册选择二维码，无需相机权限。");
        permissionButton.setText(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                ? "允许使用相机" : "前往设置开启相机权限");
        permissionButton.setVisibility(View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_CAMERA) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            cameraAuthorized();
            resumeCamera();
        } else {
            showPermissionHelp();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        scanner.pause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permissionAsked", permissionAsked);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        images.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null
                || data.getData() == null || decodingImage || completed) return;
        decodingImage = true;
        scanner.pause();
        say("正在识别图片中的二维码…");
        Uri image = data.getData();
        images.execute(() -> {
            try {
                String decoded = readImage(image);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    decodingImage = false;
                    accept(decoded);
                    resumeCamera();
                });
            } catch (Exception failed) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    decodingImage = false;
                    say("没有识别到清晰的二维码，请选择原图或靠近电脑重新扫码。");
                    resumeCamera();
                });
            }
        });
    }

    private String readImage(Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IllegalArgumentException("Unreadable image");
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2048) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("Unreadable image");
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource luminance = new RGBLuminanceSource(width, height, pixels);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            MultiFormatReader reader = new MultiFormatReader();
            try {
                return reader.decode(new BinaryBitmap(new HybridBinarizer(luminance)), hints).getText();
            } catch (com.google.zxing.NotFoundException firstAttempt) {
                return reader.decode(new BinaryBitmap(new HybridBinarizer(luminance.invert())), hints)
                        .getText();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private void accept(String content) {
        if (completed || isFinishing()) return;
        String trimmed = content == null ? "" : content.trim();
        if (!trimmed.startsWith(PairingPayload.PREFIX) && !trimmed.startsWith("https://")) {
            say("这不是 AI Usage Bridge 的配对二维码，请在电脑端打开“添加设备”。");
            return;
        }
        try {
            if (trimmed.startsWith(PairingPayload.PREFIX)) PairingPayload.parse(trimmed);
            else com.aiusage.monitor.bridge.LauncherInvitation.parse(trimmed);
        } catch (PairingPayload.Invalid invalid) {
            say("二维码配对内容不完整，请在电脑端重新生成二维码。");
            return;
        }
        completed = true;
        scanner.pause();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_QR_CONTENT, trimmed));
        finish();
    }

    private void say(String message) {
        if (!isFinishing() && !isDestroyed()) status.setText(message);
    }
}
