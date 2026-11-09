package com.keepasskey.app.security;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 设备侧安全测试专用的最小独立 UID / 独立进程悬浮遮罩服务（instrumented 测试专用，不属于产品代码）。
 *
 * 运行在测试 APK 进程（:test_overlay）中，其 UID 与被测应用进程不同。
 * 需配合 appops 临时授予 SYSTEM_ALERT_WINDOW 权限，用于在真机/模拟器上
 * 产生真实外部窗口覆盖。
 */
public class TestOverlayService extends Service {

    public static final String ACTION_SHOW = "com.keepasskey.test.overlay.SHOW";
    public static final String ACTION_HIDE = "com.keepasskey.test.overlay.HIDE";
    public static final String ACTION_STATUS = "com.keepasskey.test.overlay.STATUS";

    public static final String EXTRA_RECEIVER = "extra.receiver";
    public static final String EXTRA_COLOR = "extra.color";
    public static final String EXTRA_ALPHA = "extra.alpha";
    public static final String EXTRA_HEIGHT = "extra.height";
    public static final String EXTRA_NOT_TOUCHABLE = "extra.not_touchable";

    /** 独立悬浮窗特异颜色：纯品红（Red=255, Green=0, Blue=255） */
    public static final int OVERLAY_COLOR_MAGENTA = 0xFFFF00FF;

    private static View sOverlayView = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel("overlay", "Overlay", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
                Notification notification = new Notification.Builder(this, "overlay")
                    .setContentTitle("Test Overlay")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .build();
                startForeground(1001, notification);
            } catch (Exception ignored) {
            }
        }

        if (intent != null) {
            String action = intent.getAction();
            ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
            if (ACTION_SHOW.equals(action)) {
                int color = intent.getIntExtra(EXTRA_COLOR, OVERLAY_COLOR_MAGENTA);
                float alpha = intent.getFloatExtra(EXTRA_ALPHA, 1.0f);
                int height = intent.getIntExtra(EXTRA_HEIGHT, 400);
                boolean notTouchable = intent.getBooleanExtra(EXTRA_NOT_TOUCHABLE, true);

                boolean canDraw = Settings.canDrawOverlays(this);
                boolean attached = showOverlay(color, alpha, height, notTouchable);

                Intent broadcast = new Intent(ACTION_STATUS);
                broadcast.putExtra("attached", attached);
                broadcast.putExtra("canDrawOverlays", canDraw);
                broadcast.putExtra("uid", Process.myUid());
                broadcast.putExtra("color", color);
                sendBroadcast(broadcast);

                if (receiver != null) {
                    Bundle data = new Bundle();
                    data.putBoolean("canDrawOverlays", canDraw);
                    data.putBoolean("attached", attached);
                    data.putInt("uid", Process.myUid());
                    data.putInt("color", color);
                    receiver.send(0, data);
                }
            } else if (ACTION_HIDE.equals(action)) {
                hideOverlay();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        stopForeground(true);
                    } catch (Exception ignored) {
                    }
                }
                Intent broadcast = new Intent(ACTION_STATUS);
                broadcast.putExtra("attached", false);
                sendBroadcast(broadcast);

                if (receiver != null) {
                    Bundle data = new Bundle();
                    data.putBoolean("attached", false);
                    receiver.send(0, data);
                }
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private boolean showOverlay(int color, float alpha, int height, boolean notTouchable) {
        if (sOverlayView != null) {
            return true;
        }
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return false;
        }
        try {
            sOverlayView = new View(this);
            sOverlayView.setBackgroundColor(color);

            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_PHONE;
            }

            int windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            if (notTouchable) {
                windowFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                type,
                windowFlags,
                PixelFormat.TRANSLUCENT
            );
            params.alpha = alpha;
            params.gravity = Gravity.TOP | Gravity.START;
            wm.addView(sOverlayView, params);
            return true;
        } catch (Exception e) {
            sOverlayView = null;
            return false;
        }
    }

    private void hideOverlay() {
        if (sOverlayView != null) {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                try {
                    wm.removeView(sOverlayView);
                } catch (Exception ignored) {
                }
            }
            sOverlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideOverlay();
    }
}
