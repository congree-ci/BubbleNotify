package io.github.freeform.bubble;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Pre-sleep keep-awake bubble for ColorOS 16.
 *
 * When the screen enters the half-dim state right before it would sleep, the
 * user can tap the screen to pop a bubble. Tapping the bubble keeps the screen
 * awake (never auto-dims/sleeps). The lock is only released when the user
 * manually turns the screen off or switches to a different app.
 *
 * Two processes collaborate:
 *  - system_server hooks PowerManagerService to detect the imminent-sleep dim
 *    phase and records it in a Settings.Global flag.
 *  - SystemUI watches that flag, shows a transparent tap overlay during the dim
 *    phase, and manages the bubble + a FLAG_KEEP_SCREEN_ON window.
 */
public final class PreSleepKeepAwake extends XposedModule {
    private static final String TAG = "PreSleepKeepAwake";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String KEY_ARMED = "presleep_bubble_armed";
    private static final String MODULE_PACKAGE = "io.github.freeform.bubble";
    private static final long ARM_POLL_MS = 250L;
    private static final long APP_SWITCH_POLL_MS = 1000L;
    private static final long KEEP_ALIVE_TIMEOUT_S = 0; // 0 => no timeout; released on sleep/app switch

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Context sysContext;
    private WindowManager wm;
    private PowerManager power;

    // SystemUI-side state
    private View tapOverlay;
    private BubbleView bubble;
    private View keepWindow;
    private boolean keepAwake;
    private String keepBaselineForeground;
    private long lastForegroundCheck;

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Manual (or any) screen-off releases the lock.
            clearArmed();
            removeOverlay();
            release();
        }
    };

    public PreSleepKeepAwake() {
        super();
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> pms = Class.forName(
                    "com.android.server.power.PowerManagerService", false, loader);
            for (Method m : pms.getDeclaredMethods()) {
                if ("updateUserActivitySummaryLocked".equals(m.getName())) {
                    m.setAccessible(true);
                    hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                detectImminentSleep(chain.getThisObject());
                            } catch (Throwable ignored) {
                            }
                            return result;
                        }
                    });
                    log("system_server pre-sleep detector installed");
                    return;
                }
            }
        } catch (Throwable e) {
            log("system_server pre-sleep detector unavailable: " + e);
        }
    }

    /**
     * In system_server: whenever the user-activity summary is recalculated,
     * a summary of 0 while the screen is on means the device is about to dim
     * and sleep. Mirror that into a Settings.Global flag consumed by SystemUI.
     */
    private void detectImminentSleep(Object pms) throws Throwable {
        Field field = pms.getClass().getDeclaredField("mUserActivitySummary");
        field.setAccessible(true);
        int summary = field.getInt(pms);
        // BRIGHT=1, DIMMED=2, DREAM=4. Arm during the visible half-dim phase
        // (DIMMED bit) as well as the imminent sleep-off (0), so the tap-capture
        // overlay is already up while the screen is still interactive. Clear once
        // full brightness (BRIGHT) resumes.
        // This runs while PowerManagerService holds its lock; never do binder
        // I/O (Settings.Global) inline. Defer the write to the main looper.
        boolean imminentOrDimmed = (summary & 2) != 0 || summary == 0;
        ui.post(() -> setArmed(imminentOrDimmed ? 1 : 0));
    }

    private Context getSystemContext() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method current = threadClass.getDeclaredMethod("currentActivityThread");
            Object thread = current.invoke(null);
            if (thread == null) return null;
            Method getSystemContext = threadClass.getDeclaredMethod("getSystemContext");
            Object value = getSystemContext.invoke(thread);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void setArmed(final int value) {
        final Context context = getSystemContext();
        if (context == null) return;
        try {
            if (value == 0) {
                if (Settings.Global.getInt(context.getContentResolver(), KEY_ARMED, 0) != 0) {
                    Settings.Global.putInt(context.getContentResolver(), KEY_ARMED, 0);
                }
            } else {
                if (Settings.Global.getInt(context.getContentResolver(), KEY_ARMED, 0) == 0) {
                    Settings.Global.putInt(context.getContentResolver(), KEY_ARMED, 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int getArmed() {
        if (sysContext == null) return 0;
        try {
            return Settings.Global.getInt(sysContext.getContentResolver(), KEY_ARMED, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void clearArmed() {
        setArmed(0);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        log("SystemUI keep-awake controller starting");
        try {
            sysContext = getSystemContext();
            if (sysContext == null) return;
            wm = (WindowManager) sysContext.getSystemService(Context.WINDOW_SERVICE);
            power = (PowerManager) sysContext.getSystemService(Context.POWER_SERVICE);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            sysContext.registerReceiver(screenOffReceiver, filter);
            clearArmed();
            ui.post(pollTask);
        } catch (Throwable e) {
            log("SystemUI keep-awake controller init failed: " + e);
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (sysContext == null) return;
            try {
                boolean interactive = power != null && power.isInteractive();
                if (interactive) {
                    if (keepAwake) {
                        checkAppSwitch();
                    } else if (getArmed() == 1) {
                        showOverlay();
                    }
                }
                if (!interactive) {
                    removeOverlay();
                    release();
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, ARM_POLL_MS);
        }
    };

    private void showOverlay() {
        if (tapOverlay != null || keepAwake || wm == null) return;
        try {
            View overlay = new View(sysContext);
            overlay.setBackgroundColor(Color.TRANSPARENT);
            overlay.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    handleDimTap();
                    return true;
                }
                return true; // consume taps during the dim window
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.setTitle("PreSleepTapCapture");
            wm.addView(overlay, params);
            tapOverlay = overlay;
            log("tap-capture overlay shown (screen about to sleep)");
        } catch (Throwable e) {
            log("failed to show tap overlay: " + e);
        }
    }

    private void removeOverlay() {
        View overlay = tapOverlay;
        tapOverlay = null;
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * The user tapped while the screen was half-dim. Cancel the imminent sleep
     * and pop the keep-awake bubble.
     */
    private void handleDimTap() {
        clearArmed();
        removeOverlay();
        pokeUserActivity();
        if (keepAwakeDisabledByConfig()) {
            log("keep-awake prompt disabled by settings");
            return;
        }
        showBubble();
    }

    /** 读取模块配置：设置里关闭「屏幕常亮提示」时不再弹出保常亮气泡。 */
    private boolean keepAwakeDisabledByConfig() {
        if (sysContext == null) return true;
        try {
            android.os.Bundle result = sysContext.getContentResolver().call(
                    BubbleConfigProvider.URI,
                    BubbleConfigProvider.METHOD_GET_BUBBLE_TIMEOUT, null, null);
            if (result != null && result.getBoolean("success", false)) {
                return !result.getBoolean("awakePrompt", true);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void showBubble() {
        if (bubble != null || sysContext == null) return;
        try {
            BubbleConfig loadedConfig = BubbleConfig.fromProvider(sysContext);
            if (loadedConfig == null) loadedConfig = new BubbleConfig(sysContext);
            BubbleView view = new BubbleView(sysContext, loadedConfig);
            view.setIcon(new KeepAwakeIcon());
            view.setOnClickAction(this::engageKeepAwake);
            // Match normal notification bubbles: a downward swipe dismisses only
            // the prompt, while an already engaged keep-awake lock stays active.
            view.setOnSwipeListener(up -> {
                if (!up && bubble != null) bubble.dismiss();
            });
            // BubbleView treats a drag that reaches the bottom edge as a
            // separate dismissal gesture from a regular vertical swipe.
            view.setOnBottomDismissAction(() -> {
                if (bubble != null) bubble.dismiss();
            });
            view.setOnDismissedAction(() -> { bubble = null; });
            boolean ok = view.show((int) KEEP_ALIVE_TIMEOUT_S, false);
            if (!ok) {
                view.removeImmediately();
                return;
            }
            bubble = view;
            log("keep-awake bubble shown");
        } catch (Throwable e) {
            log("failed to show bubble: " + e);
        }
    }

    private void engageKeepAwake() {
        if (keepAwake) return;
        try {
            keepAwake = true;
            keepBaselineForeground = getForegroundPackage();
            pokeUserActivity();
            attachKeepWindow();
            lastForegroundCheck = 0L;
            log("keep-awake engaged (screen held awake)");
        } catch (Throwable e) {
            keepAwake = false;
            log("failed to engage keep-awake: " + e);
        }
    }

    /** A window with FLAG_KEEP_SCREEN_ON holds the screen awake while attached. */
    private void attachKeepWindow() {
        if (keepWindow != null || wm == null) return;
        try {
            View view = new View(sysContext);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.x = -10;
            params.y = -10;
            params.setTitle("PreSleepKeepScreenOn");
            wm.addView(view, params);
            keepWindow = view;
        } catch (Throwable e) {
            log("failed to attach keep-screen-on window: " + e);
        }
    }

    private void releaseKeepWindow() {
        View view = keepWindow;
        keepWindow = null;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Release only on manual sleep or app switch, as required. */
    private void release() {
        if (!keepAwake) return;
        keepAwake = false;
        keepBaselineForeground = null;
        releaseKeepWindow();
        if (bubble != null) {
            bubble.dismiss();
            bubble = null;
        }
        log("keep-awake released");
    }

    private void checkAppSwitch() {
        if (!keepAwake) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastForegroundCheck < APP_SWITCH_POLL_MS) return;
        lastForegroundCheck = now;
        String current = getForegroundPackage();
        if (keepBaselineForeground == null) {
            keepBaselineForeground = current;
            return;
        }
        if (current != null && !current.equals(keepBaselineForeground)) {
            log("foreground app switched " + keepBaselineForeground + " -> " + current);
            release();
        }
    }

    private void pokeUserActivity() {
        if (power == null) return;
        try {
            Method userActivity = PowerManager.class.getDeclaredMethod(
                    "userActivity", long.class, int.class, int.class);
            userActivity.setAccessible(true);
            userActivity.invoke(power, android.os.SystemClock.uptimeMillis(),
                    2 /* USER_ACTIVITY_EVENT_TOUCH */, 0);
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    private String getForegroundPackage() {
        try {
            ActivityManager am = (ActivityManager) sysContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Small Material-style sun icon drawn at runtime for the keep-awake bubble. */
    private static final class KeepAwakeIcon extends Drawable {
        private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        KeepAwakeIcon() {
            corePaint.setColor(Color.argb(235, 255, 224, 130));
            rayPaint.setColor(Color.argb(215, 255, 236, 160));
            rayPaint.setStrokeCap(Paint.Cap.ROUND);
            rayPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(Canvas canvas) {
            float cx = getBounds().centerX();
            float cy = getBounds().centerY();
            float r = Math.min(getBounds().width(), getBounds().height()) / 2f;

            // warm halo
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setShader(new RadialGradient(cx, cy, r,
                    new int[]{Color.argb(90, 255, 224, 130), Color.argb(0, 255, 224, 130)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 0.98f, glow);

            // rays
            rayPaint.setStrokeWidth(r * 0.10f);
            for (int i = 0; i < 8; i++) {
                float start = (float) Math.toRadians(i * 45 + 22.5);
                float end = (float) Math.toRadians(i * 45 - 0 + 22.5);
                float x1 = cx + (float) Math.cos(start) * r * 0.52f;
                float y1 = cy + (float) Math.sin(start) * r * 0.52f;
                float x2 = cx + (float) Math.cos(end) * r * 0.78f;
                float y2 = cy + (float) Math.sin(end) * r * 0.78f;
                canvas.drawLine(x1, y1, x2, y2, rayPaint);
            }

            // sun core
            canvas.drawCircle(cx, cy, r * 0.46f, corePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            corePaint.setAlpha(alpha);
            rayPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            corePaint.setColorFilter(colorFilter);
            rayPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        log(Log.INFO, TAG, msg);
    }
}
