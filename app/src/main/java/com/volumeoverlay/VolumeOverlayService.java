package com.volumeoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class VolumeOverlayService extends Service {

    private static final String CHANNEL_ID    = "volume_overlay";
    private static final String PREFS_NAME    = "volume_prefs";
    private static final String KEY_VOLUME    = "volume_level";
    private static final String KEY_MAX_VOL   = "max_volume";
    private static final String KEY_STYLE     = "overlay_style";
    private static final int    DEFAULT_MAX   = 100;
    private static final int    SYSTEM_MAX    = 100;
    private static final long   HIDE_DELAY_MS = 2500;
    private static final long   FADE_MS       = 250;

    public static final String ACTION_RELOAD  = "com.volumeoverlay.RELOAD_VOLUME";
    public static final String ACTION_RESTYLE = "com.volumeoverlay.RESTYLE";
    public static final String ACTION_STOP    = "com.volumeoverlay.STOP";

    private WindowManager   windowManager;
    private android.view.View overlayView;
    private int             currentVolume  = 0;
    private int             lastCecVolume  = -1;
    private boolean         overlayVisible = false;
    private ObjectAnimator  fadeInAnim;

    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private Runnable       hideRunnable;

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) return;
            int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (streamType != AudioManager.STREAM_MUSIC) return;
            int newVol  = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
            int prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -2);
            if (newVol < 0 || newVol == prevVol) return;

            int userMax = getMaxVolume();
            int scaled  = Math.round((float) newVol * userMax / SYSTEM_MAX);
            scaled = Math.min(userMax, Math.max(0, scaled));
            if (scaled == lastCecVolume) return;
            lastCecVolume = scaled;
            currentVolume = scaled;
            saveVolume();
            showOverlay();
        }
    };

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_RELOAD.equals(action)) {
                loadVolume();
                lastCecVolume = -1;
            } else if (ACTION_RESTYLE.equals(action)) {
                handler.post(() -> { removeOverlay(); buildOverlay(); });
            } else if (ACTION_STOP.equals(action)) {
                stopSelf();
            }
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadVolume();
        createNotificationChannel();
        startForeground(1, buildNotification());
        buildOverlay();
        registerReceiver(volumeReceiver,
            new IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            Context.RECEIVER_EXPORTED);
        IntentFilter cmdFilter = new IntentFilter();
        cmdFilter.addAction(ACTION_RELOAD);
        cmdFilter.addAction(ACTION_RESTYLE);
        cmdFilter.addAction(ACTION_STOP);
        registerReceiver(commandReceiver, cmdFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart automatically if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(volumeReceiver);  } catch (Exception ignored) {}
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        removeOverlay();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Notification (required for foreground service) ────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Volume Overlay", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Running in background to show volume overlay");
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(ACTION_STOP).setPackage(getPackageName());
        PendingIntent stopPending = PendingIntent.getBroadcast(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TV Volume Overlay")
            .setContentText("Listening for volume changes")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setOngoing(true)
            .build();
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private void removeOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    private void buildOverlay() {
        String style = getStyle();
        switch (style) {
            case "H2":      overlayView = buildH2();      break;
            case "H3":      overlayView = buildH3();      break;
            case "H6":      overlayView = buildH6();      break;
            case "PILL":    overlayView = buildPill();    break;
            case "MINIMAL": overlayView = buildMinimal(); break;
            case "CIRCLE":  overlayView = buildCircle();  break;
            case "STACKED": overlayView = buildStacked(); break;
            case "CAPSULE": overlayView = buildCapsule(); break;
            case "CHIP":    overlayView = buildChip();    break;
            default:        overlayView = buildH1();      break;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 60;
        params.y = 60;
        overlayView.setAlpha(0f);
        windowManager.addView(overlayView, params);
        overlayVisible = false;
    }

    private void showOverlay() {
        handler.post(() -> {
            if (overlayView == null) return;
            int max = getMaxVolume();
            String style = getStyle();

            android.view.View circleView = overlayView.findViewWithTag("circledraw");
            if (circleView != null) circleView.invalidate();

            android.view.View numView = overlayView.findViewWithTag("number");
            if (numView instanceof TextView)
                ((TextView) numView).setText(String.valueOf(currentVolume));

            android.view.View maxView = overlayView.findViewWithTag("maxlabel");
            if (maxView instanceof TextView)
                ((TextView) maxView).setText("/ " + max);

            android.view.View frame = overlayView.findViewWithTag("stripframe");
            if (frame instanceof FrameLayout) {
                android.view.View fill = ((FrameLayout) frame).findViewWithTag("fill");
                if (fill != null) {
                    float pct = max > 0 ? (float) currentVolume / max : 0f;
                    String storedH = ((FrameLayout) frame).getContentDescription() != null
                        ? ((FrameLayout) frame).getContentDescription().toString() : "52";
                    if ("CAPSULE".equals(style) || "STACKED".equals(style)) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                        fill.post(() -> { lp.width = Math.round(frame.getWidth() * pct); fill.setLayoutParams(lp); });
                    } else {
                        int totalH = dp(Integer.parseInt(storedH));
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                        lp.height = Math.round(totalH * pct);
                        fill.setLayoutParams(lp);
                    }
                }
            }

            if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
            if (!overlayVisible) {
                overlayVisible = true;
                if (fadeInAnim != null) fadeInAnim.cancel();
                fadeInAnim = ObjectAnimator.ofFloat(overlayView, "alpha", 0f, 1f);
                fadeInAnim.setDuration(80);
                fadeInAnim.start();
            }
            hideRunnable = this::hideOverlay;
            handler.postDelayed(hideRunnable, HIDE_DELAY_MS);
        });
    }

    private void hideOverlay() {
        if (overlayView == null) return;
        ObjectAnimator anim = ObjectAnimator.ofFloat(overlayView, "alpha", 1f, 0f);
        anim.setDuration(FADE_MS);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { overlayVisible = false; }
        });
        anim.start();
    }

    // ── Style builders (same as before) ──────────────────────────────────────

    private LinearLayout buildH1() {
        LinearLayout root = makeHorizontal();
        root.addView(makeStrip(3, 52));
        LinearLayout col = makeVertical();
        col.addView(makeText("VOL", 9, 0.30f, 2f));
        col.addView(makeNumberView(30));
        col.addView(makeMaxView());
        root.addView(col);
        return root;
    }

    private LinearLayout buildH2() {
        LinearLayout root = makeHorizontal();
        root.addView(makeStrip(3, 48));
        LinearLayout col = makeVertical();
        LinearLayout row = makeHorizontal();
        row.setGravity(Gravity.BOTTOM);
        row.addView(makeText("vol ", 10, 0.30f, 0.5f));
        row.addView(makeNumberView(28));
        col.addView(row);
        col.addView(makeMaxView());
        root.addView(col);
        return root;
    }

    private LinearLayout buildH3() {
        LinearLayout root = makeHorizontal();
        root.addView(makeStrip(4, 60));
        LinearLayout col = makeVertical();
        col.addView(makeText("VOL", 9, 0.25f, 2f));
        col.addView(makeNumberView(38));
        root.addView(col);
        return root;
    }

    private LinearLayout buildH6() {
        LinearLayout root = makeHorizontal();
        android.view.View strip = makeStrip(2, 44);
        strip.setAlpha(0.5f);
        root.addView(strip);
        LinearLayout col = makeVertical();
        col.addView(makeText("VOL", 8, 0.18f, 2f));
        TextView num = makeNumberView(22);
        num.setTextColor(Color.argb(160, 255, 255, 255));
        col.addView(num);
        root.addView(col);
        return root;
    }

    private LinearLayout buildPill() {
        LinearLayout root = makeHorizontal();
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(204, 26, 26, 26));
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);
        root.setPadding(dp(12), dp(10), dp(16), dp(10));
        root.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = makeText("🔊", 16, 1f, 0f);
        icon.setPadding(0, 0, dp(8), 0);
        root.addView(icon);
        LinearLayout col = makeVertical();
        col.addView(makeNumberView(32));
        col.addView(makeMaxView());
        root.addView(col);
        return root;
    }

    private LinearLayout buildMinimal() {
        LinearLayout root = makeVertical();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(makeText("VOL", 8, 0.28f, 2f));
        root.addView(makeNumberView(42));
        return root;
    }

    private android.view.View buildCircle() {
        FrameLayout frame = new FrameLayout(this);
        int size = dp(64);
        frame.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        android.view.View circle = new android.view.View(this) {
            @Override protected void onDraw(android.graphics.Canvas c) {
                float cx = getWidth()/2f, cy = getHeight()/2f, r = cx - dp(4);
                android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bg.setColor(Color.argb(224, 20, 20, 20));
                c.drawCircle(cx, cy, cx, bg);
                android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                track.setStyle(android.graphics.Paint.Style.STROKE);
                track.setStrokeWidth(dp(3)); track.setColor(Color.argb(30, 255, 255, 255));
                c.drawCircle(cx, cy, r, track);
                int mx = getMaxVolume();
                float pct = mx > 0 ? (float) currentVolume / mx : 0f;
                android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                arc.setStyle(android.graphics.Paint.Style.STROKE);
                arc.setStrokeWidth(dp(3));
                arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                arc.setColor(Color.argb(178, 255, 255, 255));
                android.graphics.RectF oval = new android.graphics.RectF(cx-r, cy-r, cx+r, cy+r);
                c.drawArc(oval, -90, 360*pct, false, arc);
            }
        };
        circle.setTag("circledraw");
        circle.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        frame.addView(circle);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        TextView lbl = makeText("VOL", 8, 0.28f, 1f);
        lbl.setGravity(Gravity.CENTER); inner.addView(lbl);
        TextView num = makeNumberView(20);
        num.setGravity(Gravity.CENTER); inner.addView(num);
        frame.addView(inner);
        return frame;
    }

    private LinearLayout buildStacked() {
        LinearLayout root = makeVertical();
        root.setGravity(Gravity.END);
        TextView lbl = makeText("VOLUME", 8, 0.22f, 2f);
        lbl.setGravity(Gravity.END); root.addView(lbl);
        LinearLayout row = makeHorizontal();
        row.setGravity(Gravity.BOTTOM);
        row.addView(makeNumberView(44));
        TextView mx = new TextView(this);
        mx.setTag("maxlabel");
        mx.setText("/" + getMaxVolume());
        mx.setTextColor(Color.argb(77, 255, 255, 255));
        mx.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        mx.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        mx.setPadding(dp(3), 0, 0, dp(4));
        row.addView(mx);
        root.addView(row);
        FrameLayout bar = new FrameLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        android.view.View track = new android.view.View(this);
        track.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        track.setBackgroundColor(Color.argb(30, 255, 255, 255));
        android.view.View fill = new android.view.View(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT);
        fill.setLayoutParams(flp); fill.setBackgroundColor(Color.argb(128, 255, 255, 255));
        fill.setTag("fill"); bar.addView(track); bar.addView(fill);
        bar.setTag("stripframe"); bar.setContentDescription("2");
        root.addView(bar);
        return root;
    }

    private LinearLayout buildCapsule() {
        LinearLayout root = makeHorizontal();
        android.graphics.drawable.GradientDrawable bgc = new android.graphics.drawable.GradientDrawable();
        bgc.setColor(Color.argb(230, 20, 20, 20)); bgc.setCornerRadius(dp(100));
        root.setBackground(bgc);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setMinimumWidth(dp(110));
        LinearLayout left = makeVertical();
        left.setPadding(0,0,0,0);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(makeText("VOL", 8, 0.25f, 1f));
        FrameLayout bar = new FrameLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        blp.topMargin = dp(4); bar.setLayoutParams(blp);
        android.view.View track = new android.view.View(this);
        track.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        track.setBackgroundColor(Color.argb(25, 255, 255, 255));
        android.view.View fill = new android.view.View(this);
        fill.setLayoutParams(new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        fill.setBackgroundColor(Color.argb(140, 255, 255, 255));
        fill.setTag("fill"); bar.addView(track); bar.addView(fill);
        bar.setTag("stripframe"); bar.setContentDescription("2");
        left.addView(bar); root.addView(left);
        TextView num = makeNumberView(28);
        num.setPadding(dp(12), 0, 0, 0); root.addView(num);
        return root;
    }

    private LinearLayout buildChip() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        android.view.View line = new android.view.View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT));
        line.setBackgroundColor(Color.argb(90, 255, 255, 255));
        LinearLayout col = makeVertical();
        col.setPadding(dp(8), 0, 0, 0);
        col.addView(makeText("VOL", 8, 0.20f, 2f));
        col.addView(makeNumberView(32));
        col.addView(makeMaxView());
        wrapper.addView(line); wrapper.addView(col);
        return wrapper;
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private LinearLayout makeHorizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout makeVertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(5), 0, 0, 0);
        return l;
    }

    private android.view.View makeStrip(int widthDp, int heightDp) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)));
        android.view.View track = new android.view.View(this);
        track.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        track.setBackgroundColor(Color.argb(25, 255, 255, 255));
        android.view.View fill = new android.view.View(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0);
        flp.gravity = Gravity.BOTTOM;
        fill.setLayoutParams(flp);
        fill.setBackgroundColor(Color.argb(165, 255, 255, 255));
        fill.setTag("fill");
        frame.addView(track); frame.addView(fill);
        frame.setTag("stripframe");
        frame.setContentDescription(String.valueOf(heightDp));
        return frame;
    }

    private TextView makeNumberView(float sizeSp) {
        TextView tv = new TextView(this);
        tv.setTag("number");
        tv.setText(String.valueOf(currentVolume));
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        tv.setLineSpacing(0, 0.9f);
        return tv;
    }

    private TextView makeMaxView() {
        TextView tv = new TextView(this);
        tv.setTag("maxlabel");
        tv.setText("/ " + getMaxVolume());
        tv.setTextColor(Color.argb(51, 255, 255, 255));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9);
        return tv;
    }

    private TextView makeText(String text, float sizeSp, float alpha, float letterSpacing) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.argb((int)(255 * alpha), 255, 255, 255));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (letterSpacing > 0) tv.setLetterSpacing(letterSpacing / 10f);
        return tv;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private SharedPreferences getPrefs() { return getSharedPreferences(PREFS_NAME, MODE_PRIVATE); }
    private int    getMaxVolume() { return getPrefs().getInt(KEY_MAX_VOL, DEFAULT_MAX); }
    private String getStyle()     { return getPrefs().getString(KEY_STYLE, "H1"); }
    private void saveVolume()     { getPrefs().edit().putInt(KEY_VOLUME, currentVolume).apply(); }
    private void loadVolume()     { currentVolume = getPrefs().getInt(KEY_VOLUME, 0); }
}
