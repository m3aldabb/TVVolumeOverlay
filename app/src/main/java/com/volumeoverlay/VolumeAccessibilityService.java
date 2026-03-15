package com.volumeoverlay;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ProgressBar;
import android.widget.TextView;

public class VolumeAccessibilityService extends AccessibilityService {

    private static final long   HIDE_DELAY_MS  = 2500;
    private static final long   FADE_MS        = 250;
    private static final String PREFS_NAME     = "volume_prefs";
    private static final String KEY_VOLUME     = "volume_level";
    private static final String KEY_MAX_VOL    = "max_volume";
    private static final String KEY_STYLE      = "overlay_style";
    private static final int    DEFAULT_MAX    = 100;
    // System always broadcasts on 0-100 scale
    private static final int    SYSTEM_MAX     = 100;

    public static final String ACTION_RELOAD  = "com.volumeoverlay.RELOAD_VOLUME";
    public static final String ACTION_RESTYLE = "com.volumeoverlay.RESTYLE";

    private WindowManager windowManager;
    private View          overlayView;
    private int           currentVolume  = 0;
    private int           lastCecVolume  = -1;

    private final Handler  handler        = new Handler(Looper.getMainLooper());
    private Runnable       hideRunnable;
    private boolean        overlayVisible = false;
    private ObjectAnimator fadeInAnim;

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_RELOAD.equals(action)) {
                loadVolume();
                lastCecVolume = -1;
            } else if (ACTION_RESTYLE.equals(action)) {
                rebuildOverlay();
            }
        }
    };

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) return;
            int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (streamType != AudioManager.STREAM_MUSIC) return;
            int newVol  = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
            int prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -2);
            if (newVol < 0 || newVol == prevVol) return;

            // Scale from system 0-100 to user's chosen max. No hardcoding.
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

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        loadVolume();
        buildOverlay();
        registerReceiver(volumeReceiver,  new IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED);
        IntentFilter cmdFilter = new IntentFilter();
        cmdFilter.addAction(ACTION_RELOAD);
        cmdFilter.addAction(ACTION_RESTYLE);
        registerReceiver(commandReceiver, cmdFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(volumeReceiver);  } catch (Exception ignored) {}
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        removeOverlay();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) { return false; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    // ── Overlay construction ──────────────────────────────────────────────────

    private void rebuildOverlay() {
        handler.post(() -> {
            // Cancel any pending hide so we don't touch a removed view
            if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
            if (fadeInAnim != null) { fadeInAnim.cancel(); fadeInAnim = null; }
            overlayVisible = false;
            removeOverlay();
            buildOverlay();
        });
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    private void buildOverlay() {
        String style = getStyle();
        switch (style) {
            case "H1": overlayView = buildH1(); break;
            case "H2": overlayView = buildH2(); break;
            case "H3": overlayView = buildH3(); break;
            case "H6": overlayView = buildH6(); break;
            case "PILL": overlayView = buildPill(); break;
            case "MINIMAL": overlayView = buildMinimal(); break;
            case "CIRCLE":  overlayView = buildCircle();  break;
            case "STACKED": overlayView = buildStacked(); break;
            case "CAPSULE": overlayView = buildCapsule(); break;
            case "CHIP":    overlayView = buildChip();    break;
            default: overlayView = buildH1(); break;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        params.x = 60;
        params.y = 60;
        overlayView.setAlpha(0f);
        windowManager.addView(overlayView, params);
        overlayVisible = false;
    }

    // ── Style builders ────────────────────────────────────────────────────────

    // H1: Side strip + VOL above + /max below (default)
    private View buildH1() {
        android.widget.LinearLayout root = makeHorizontal();
        root.addView(makeStrip(3, 52));
        android.widget.LinearLayout col = makeVertical();
        col.addView(makeText("VOL", 9, 0.30f, 2f));
        TextView num = makeNumberView(30);
        col.addView(num);
        col.addView(makeMaxView());
        root.addView(col);
        return root;
    }

    // H2: Strip + vol label beside number inline
    private View buildH2() {
        android.widget.LinearLayout root = makeHorizontal();
        root.addView(makeStrip(3, 48));
        android.widget.LinearLayout col = makeVertical();
        android.widget.LinearLayout row = makeHorizontal();
        row.setGravity(android.view.Gravity.BOTTOM);
        row.addView(makeText("vol ", 10, 0.30f, 0.5f));
        row.addView(makeNumberView(28));
        col.addView(row);
        col.addView(makeMaxView());
        root.addView(col);
        return root;
    }

    // H3: Strip + VOL + big number, no /max
    private View buildH3() {
        android.widget.LinearLayout root = makeHorizontal();
        root.addView(makeStrip(4, 60));
        android.widget.LinearLayout col = makeVertical();
        col.addView(makeText("VOL", 9, 0.25f, 2f));
        col.addView(makeNumberView(38));
        root.addView(col);
        return root;
    }

    // H6: Ghost — ultra faded, very thin strip, small number
    private View buildH6() {
        android.widget.LinearLayout root = makeHorizontal();
        View strip = makeStrip(2, 44);
        strip.setAlpha(0.5f);
        root.addView(strip);
        android.widget.LinearLayout col = makeVertical();
        col.addView(makeText("VOL", 8, 0.18f, 2f));
        TextView num = makeNumberView(22);
        num.setTextColor(Color.argb(160, 255, 255, 255));
        col.addView(num);
        root.addView(col);
        return root;
    }

    // PILL: dark rounded box with 🔊 icon
    private View buildPill() {
        android.widget.LinearLayout root = makeHorizontal();
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(204, 26, 26, 26));
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);
        root.setPadding(dp(12), dp(10), dp(16), dp(10));
        root.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView icon = makeText("🔊", 16, 1f, 0f);
        icon.setPadding(0, 0, dp(8), 0);
        root.addView(icon);
        android.widget.LinearLayout col = makeVertical();
        col.setPadding(0, 0, 0, 0);
        col.addView(makeNumberView(32));
        col.addView(makeMaxView());
        root.addView(col);
        return root;
    }

    // MINIMAL: no box, no strip — pure floating number with tiny label
    private View buildMinimal() {
        android.widget.LinearLayout root = makeVertical();
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.addView(makeText("VOL", 8, 0.28f, 2f));
        root.addView(makeNumberView(42));
        return root;
    }

    // J: Circle with arc progress ring
    private View buildCircle() {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        int size = dp(64);
        android.widget.FrameLayout.LayoutParams fp =
            new android.widget.FrameLayout.LayoutParams(size, size);
        frame.setLayoutParams(fp);
        // Draw circle via custom view
        android.view.View circle = new android.view.View(this) {
            @Override protected void onDraw(android.graphics.Canvas c) {
                float cx = getWidth()/2f, cy = getHeight()/2f, r = cx - dp(4);
                android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bg.setColor(Color.argb(224, 20, 20, 20));
                c.drawCircle(cx, cy, cx, bg);
                android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                track.setStyle(android.graphics.Paint.Style.STROKE);
                track.setStrokeWidth(dp(3));
                track.setColor(Color.argb(30, 255, 255, 255));
                c.drawCircle(cx, cy, r, track);
                int mx = getMaxVolume();
                float pct = mx > 0 ? (float)currentVolume / mx : 0f;
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
        circle.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size, size));
        frame.addView(circle);
        android.widget.LinearLayout inner = new android.widget.LinearLayout(this);
        inner.setOrientation(android.widget.LinearLayout.VERTICAL);
        inner.setGravity(android.view.Gravity.CENTER);
        inner.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size, size));
        TextView lbl = makeText("VOL", 8, 0.28f, 1f);
        lbl.setGravity(android.view.Gravity.CENTER);
        inner.addView(lbl);
        TextView num = makeNumberView(20);
        num.setGravity(android.view.Gravity.CENTER);
        inner.addView(num);
        frame.addView(inner);
        frame.setTag("root");
        return frame;
    }

    // K: Right-aligned stacked with big /max inline
    private View buildStacked() {
        android.widget.LinearLayout root = makeVertical();
        root.setGravity(android.view.Gravity.END);
        root.setPadding(0, 0, 0, 0);
        TextView lbl = makeText("VOLUME", 8, 0.22f, 2f);
        lbl.setGravity(android.view.Gravity.END);
        root.addView(lbl);
        android.widget.LinearLayout row = makeHorizontal();
        row.setGravity(android.view.Gravity.BOTTOM);
        TextView num = makeNumberView(44);
        row.addView(num);
        TextView mx = new TextView(this);
        mx.setTag("maxlabel");
        mx.setText("/" + getMaxVolume());
        mx.setTextColor(Color.argb(77, 255, 255, 255));
        mx.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        mx.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        mx.setPadding(dp(3), 0, 0, dp(4));
        row.addView(mx);
        root.addView(row);
        // thin progress line
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        bar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        View track = new View(this);
        track.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        track.setBackgroundColor(Color.argb(30, 255, 255, 255));
        View fill = new View(this);
        android.widget.FrameLayout.LayoutParams flp =
            new android.widget.FrameLayout.LayoutParams(0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        fill.setLayoutParams(flp);
        fill.setBackgroundColor(Color.argb(128, 255, 255, 255));
        fill.setTag("fill");
        bar.addView(track); bar.addView(fill);
        bar.setTag("stripframe");
        bar.setContentDescription("2");
        root.addView(bar);
        return root;
    }

    // N: Horizontal capsule
    private View buildCapsule() {
        android.widget.LinearLayout root = makeHorizontal();
        android.graphics.drawable.GradientDrawable bgc = new android.graphics.drawable.GradientDrawable();
        bgc.setColor(Color.argb(230, 20, 20, 20));
        bgc.setCornerRadius(dp(100));
        root.setBackground(bgc);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setMinimumWidth(dp(110));
        android.widget.LinearLayout left = makeVertical();
        left.setPadding(0, 0, 0, 0);
        left.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(makeText("VOL", 8, 0.25f, 1f));
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        android.widget.LinearLayout.LayoutParams blp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        blp.topMargin = dp(4);
        bar.setLayoutParams(blp);
        View track = new View(this);
        track.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        track.setBackgroundColor(Color.argb(25, 255, 255, 255));
        View fill = new View(this);
        android.widget.FrameLayout.LayoutParams flp =
            new android.widget.FrameLayout.LayoutParams(0,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        fill.setLayoutParams(flp);
        fill.setBackgroundColor(Color.argb(140, 255, 255, 255));
        fill.setTag("fill");
        bar.addView(track); bar.addView(fill);
        bar.setTag("stripframe");
        bar.setContentDescription("2");
        left.addView(bar);
        root.addView(left);
        TextView num = makeNumberView(28);
        num.setPadding(dp(12), 0, 0, 0);
        root.addView(num);
        return root;
    }

    // O: Left border chip
    private View buildChip() {
        android.widget.LinearLayout root = makeVertical();
        root.setPadding(dp(8), dp(4), 0, dp(4));
        android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        root.setBackground(border);
        // Left border via padding + colored line
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(this);
        wrapper.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        View line = new View(this);
        android.widget.LinearLayout.LayoutParams llp =
            new android.widget.LinearLayout.LayoutParams(dp(2), android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        line.setLayoutParams(llp);
        line.setBackgroundColor(Color.argb(90, 255, 255, 255));
        android.widget.LinearLayout col = makeVertical();
        col.setPadding(dp(8), 0, 0, 0);
        col.addView(makeText("VOL", 8, 0.20f, 2f));
        col.addView(makeNumberView(32));
        col.addView(makeMaxView());
        wrapper.addView(line);
        wrapper.addView(col);
        root.addView(wrapper);
        return root;
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private android.widget.LinearLayout makeHorizontal() {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        l.setTag("root");
        return l;
    }

    private android.widget.LinearLayout makeVertical() {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setPadding(dp(5), 0, 0, 0);
        return l;
    }

    private View makeStrip(int widthDp, int heightDp) {
        View strip = new View(this);
        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        strip.setLayoutParams(lp);
        strip.setBackgroundColor(Color.argb(40, 255, 255, 255));
        strip.setTag("strip");
        strip.setScaleY(1f);
        // Wrap in a FrameLayout to simulate fill
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        android.widget.LinearLayout.LayoutParams flp =
            new android.widget.LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        frame.setLayoutParams(flp);
        // Track (background)
        View track = new View(this);
        track.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        track.setBackgroundColor(Color.argb(25, 255, 255, 255));
        // Fill
        View fill = new View(this);
        android.widget.FrameLayout.LayoutParams fillLp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 0);
        fillLp.gravity = android.view.Gravity.BOTTOM;
        fill.setLayoutParams(fillLp);
        fill.setBackgroundColor(Color.argb(165, 255, 255, 255));
        fill.setTag("fill");
        frame.addView(track);
        frame.addView(fill);
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
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-thin",
            android.graphics.Typeface.NORMAL));
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

    // ── Show / hide ───────────────────────────────────────────────────────────

    private void showOverlay() {
        handler.post(() -> {
            if (overlayView == null) return;
            int max = getMaxVolume();
            String style = getStyle();

            // Circle style: just invalidate the custom draw view
            View circleView = overlayView.findViewWithTag("circledraw");
            if (circleView != null) circleView.invalidate();

            // Update number text
            View numView = overlayView.findViewWithTag("number");
            if (numView instanceof TextView) {
                ((TextView) numView).setText(String.valueOf(currentVolume));
            }

            // Update max label
            View maxView = overlayView.findViewWithTag("maxlabel");
            if (maxView instanceof TextView)
                ((TextView) maxView).setText("/ " + max);

            // Update stacked /max inline label
            View stackedMax = overlayView.findViewWithTag("maxlabel");
            if (stackedMax instanceof TextView)
                ((TextView) stackedMax).setText("/" + max);

            // Update strip / bar fill
            View frame = overlayView.findViewWithTag("stripframe");
            if (frame instanceof android.widget.FrameLayout) {
                View fill = ((android.widget.FrameLayout) frame).findViewWithTag("fill");
                if (fill != null) {
                    float pct = max > 0 ? (float) currentVolume / max : 0f;
                    String storedH = ((android.widget.FrameLayout)frame).getContentDescription() != null
                        ? ((android.widget.FrameLayout)frame).getContentDescription().toString() : "52";
                    // For horizontal bars (CAPSULE, STACKED), fill by width not height
                    if ("CAPSULE".equals(style) || "STACKED".equals(style)) {
                        android.widget.FrameLayout.LayoutParams lp =
                            (android.widget.FrameLayout.LayoutParams) fill.getLayoutParams();
                        fill.post(() -> {
                            lp.width = Math.round(frame.getWidth() * pct);
                            fill.setLayoutParams(lp);
                        });
                    } else {
                        int totalH = dp(Integer.parseInt(storedH));
                        android.widget.FrameLayout.LayoutParams lp =
                            (android.widget.FrameLayout.LayoutParams) fill.getLayoutParams();
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

    // ── Persistence ───────────────────────────────────────────────────────────

    private int    getMaxVolume() { return getPrefs().getInt(KEY_MAX_VOL, DEFAULT_MAX); }
    private String getStyle()     { return getPrefs().getString(KEY_STYLE, "H1"); }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveVolume() {
        getPrefs().edit().putInt(KEY_VOLUME, currentVolume).apply();
    }

    private void loadVolume() {
        currentVolume = getPrefs().getInt(KEY_VOLUME, 0);
    }
}
