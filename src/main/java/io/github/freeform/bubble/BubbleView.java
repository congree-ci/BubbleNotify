package io.github.freeform.bubble;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Full-screen-overlay hosted notification bubble with a soft landing animation. */
@SuppressLint("ViewConstructor")
public final class BubbleView extends View {
    private static final float VIEW_DP = 92f;
    private static final float CORE_RADIUS_DP = 29f;
    private static final float ICON_RADIUS_DP = 20f;
    /** Upward travel after a long press that turns the gesture into "fan out the stack". */
    private static final float EXPAND_SWIPE_DP = 22f;
    /** Base size of the corner app badge; the user setting scales this. */
    private static final float BADGE_BASE_DP = 16f;
    private static final float BADGE_CENTER_X_DP = 13.5f;
    private static final float BADGE_CENTER_Y_DP = 14.5f;

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final BubbleConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint childShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint childCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint childRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint burstPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refractionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Static chromatic edges approximate glass dispersion without sampling the
    // screen behind the overlay or running a continuous blur effect.
    private final Paint dispersionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint appBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint appBadgeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect iconBounds = new Rect();
    private final RectF iconClipBounds = new RectF();
    private final RectF highlightArc = new RectF();
    private final Path iconClip = new Path();
    private final Path coreClip = new Path();
    private final Path childClip = new Path();
    private final Path badgeClip = new Path();
    private final boolean mainAnimationContinuous;
    private final boolean darkMode;
    private final List<Drawable> childIcons = new ArrayList<>();

    private Drawable icon;
    /** Scale applied to the notification icon radius inside the bubble (1.0 = 20dp). */
    private float iconScale = 1f;
    /** Scale applied to the corner app badge (1.0 = 16dp). */
    private float badgeSizeScale = 1f;
    private Runnable clickAction;
    private Runnable dismissedAction;
    private Runnable dragStartedAction;
    private Runnable bottomDismissAction;
    private Runnable configurationChangedAction;
    private Runnable layoutChangedAction;
    private Runnable longPressCallback;
    private Runnable expandGestureAction;
    private boolean longPressHandled;
    private BooleanSupplier autoDismissValidator;
    private PositionListener positionListener;
    private SwipeListener swipeListener;
    private ValueAnimator landingAnimator;
    private ValueAnimator orbitAnimator;
    private ValueAnimator burstAnimator;
    private ValueAnimator dockAnimator;
    private ValueAnimator dockBreathAnimator;
    private ValueAnimator imeAnimator;
    private int imeTargetY = Integer.MIN_VALUE;
    private ValueAnimator badgeAnimator;
    private ValueAnimator iconAnimator;
    private boolean attached;
    private boolean dismissing;
    private boolean bursting;
    private RadialGradient shadowGradient;
    private RadialGradient coreGradient;
    private SweepGradient glassEdgeGradient;
    private Throwable showError;
    private int viewSize;
    /** Whole-bubble scale (60..140 percent); all geometry is drawn in design space and scaled. */
    private float bubbleScale;
    /** The unscaled 92dp design window; drawing always happens in this coordinate space. */
    private final int designSize;
    private final int touchSlop;
    private int screenWidth;
    private int visibleLeft;
    private int visibleRight;
    private int safeTop;
    private int safeBottom;
    private int dockEdge;
    /** When false the bubble never docks to the screen edge (temporary suggestions). */
    private boolean dockingEnabled = true;
    private int timeoutSeconds;
    private boolean dimOnTimeout;
    private boolean timeoutDimmed;
    private boolean landingSuppressed;
    private boolean landscape;
    private boolean pointerDown;
    private boolean dragging;
    private float downRawX;
    private float downRawY;
    private float orbitPhase;
    private float burstProgress;
    private float landingFrameProgress = -1f;
    private float badgeScale = 1f;
    private float iconTransition = 1f;
    private int stackCount;
    private Drawable appBadgeIcon;
    private float appBadgeScale;
    private ValueAnimator appBadgeAnimator;
    private boolean imeAvoiding;
    private int imeRestoreY;
    private Drawable outgoingIcon;
    private Drawable incomingIcon;
    private int downWindowX;
    private int downWindowY;

    private final Runnable timeoutAction = this::handleTimeout;
    private final Runnable longPressAction = this::handleLongPress;
    private final Runnable repeatLandingAction = this::startLandingAnimation;
    private final Runnable imeAvoidanceAction = this::updateImeAvoidance;

    public BubbleView(Context context, BubbleConfig config) {
        super(context);
        this.config = config;
        iconScale = clampIconPercent(config.getIconSizePercent()) / 100f;
        badgeSizeScale = clampBadgePercent(config.getBadgeSizePercent()) / 100f;
        bubbleScale = clampBubblePercent(config.getBubbleSizePercent()) / 100f;
        designSize = dp(VIEW_DP);
        viewSize = Math.round(designSize * bubbleScale);
        setClickable(true);
        setFocusable(false);
        setContentDescription("打开通知小窗");
        setLayerType(LAYER_TYPE_HARDWARE, null);

        windowManager = context.getSystemService(WindowManager.class);
        viewSize = dp(VIEW_DP);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mainAnimationContinuous = config.isMainBubbleAnimationContinuous();
        darkMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        params = new WindowManager.LayoutParams(
                viewSize,
                viewSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("Bubble notification");
        resolvePosition(config);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        ringPaint.setColor(darkMode ? Color.argb(190, 154, 193, 214)
                : Color.argb(185, 224, 244, 255));
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(dp(1.2f));
        highlightPaint.setStrokeCap(Paint.Cap.ROUND);
        childCorePaint.setColor(darkMode ? Color.argb(215, 68, 88, 103)
                : Color.argb(205, 245, 250, 255));
        childShadowPaint.setColor(darkMode ? Color.argb(75, 0, 0, 0)
                : Color.argb(44, 0, 0, 0));
        childRingPaint.setStyle(Paint.Style.STROKE);
        childRingPaint.setStrokeWidth(dp(1f));
        childRingPaint.setColor(darkMode ? Color.argb(200, 184, 213, 229)
                : Color.argb(190, 255, 255, 255));
        badgeRingPaint.setStyle(Paint.Style.STROKE);
        badgeRingPaint.setStrokeWidth(dp(1f));
        badgeRingPaint.setColor(Color.argb(215, 255, 255, 255));
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        badgeTextPaint.setColor(darkMode ? Color.rgb(238, 246, 250)
                : Color.rgb(37, 48, 58));
        badgeTextOutlinePaint.setTextAlign(Paint.Align.CENTER);
        badgeTextOutlinePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        badgeTextOutlinePaint.setStyle(Paint.Style.STROKE);
        badgeTextOutlinePaint.setStrokeWidth(dp(1.4f));
        badgeTextOutlinePaint.setColor(Color.argb(225, 255, 255, 255));
        appBadgePaint.setColor(darkMode ? Color.argb(235, 12, 20, 27)
                : Color.argb(220, 34, 48, 60));
        appBadgeRingPaint.setStyle(Paint.Style.STROKE);
        appBadgeRingPaint.setStrokeWidth(dp(0.85f));
        appBadgeRingPaint.setColor(darkMode ? Color.argb(230, 18, 24, 30)
                : Color.argb(235, 255, 255, 255));
    }

    public void setIcon(Drawable icon) {
        if (iconAnimator != null) iconAnimator.cancel();
        outgoingIcon = null;
        incomingIcon = null;
        iconTransition = 1f;
        this.icon = icon;
        invalidate();
    }

    /**
     * Resizes the notification icon inside the bubble. {@code percent} is relative to the
     * historical 20dp radius, so 100 keeps the original look.
     */
    public void setIconScalePercent(int percent) {
        float next = clampIconPercent(percent) / 100f;
        if (Math.abs(next - iconScale) < 0.001f) return;
        iconScale = next;
        invalidate();
    }

    private static int clampIconPercent(int percent) {
        return Math.max(BubbleConfig.MIN_ICON_SIZE_PERCENT,
                Math.min(BubbleConfig.MAX_ICON_SIZE_PERCENT, percent));
    }

    private static int clampBubblePercent(int percent) {
        return Math.max(BubbleConfig.MIN_BUBBLE_SIZE_PERCENT,
                Math.min(BubbleConfig.MAX_BUBBLE_SIZE_PERCENT, percent));
    }

    public int getBubbleSizePx() {
        return viewSize;
    }

    /**
     * Resizes the whole bubble (glass core, notification icon and app badge) around its
     * current center. 100 keeps the original 92dp window. Applied live when the bubble is
     * already attached, and used by the settings preview for an unattached view.
     */
    public void setBubbleSizePercent(int percent) {
        float next = clampBubblePercent(percent) / 100f;
        if (Math.abs(next - bubbleScale) < 0.001f) return;
        int oldSize = viewSize;
        bubbleScale = next;
        viewSize = Math.round(designSize * bubbleScale);
        params.width = viewSize;
        params.height = viewSize;
        int delta = Math.round((oldSize - viewSize) / 2f);
        if (dockEdge < 0) {
            params.x = -viewSize / 2;
        } else if (dockEdge > 0) {
            params.x = screenWidth - viewSize / 2;
        } else {
            params.x += delta;
        }
        params.y += delta;
        if (imeAvoiding) imeRestoreY += delta;
        if (attached && windowManager != null) {
            refreshSafeBounds();
            try {
                windowManager.updateViewLayout(this, params);
                if (dockEdge != 0) startDockBreathing();
            } catch (Throwable ignored) {
                // The window may be between recreation passes; the next show re-applies it.
            }
        }
        requestLayout();
        invalidate();
        // Let attached overlays (the notification content panel) re-anchor and re-fit
        // themselves to the new diameter immediately.
        if (layoutChangedAction != null) layoutChangedAction.run();
    }

    /** Resizes the corner app badge; 100 keeps the original 16dp. */
    public void setBadgeScalePercent(int percent) {
        float next = clampBadgePercent(percent) / 100f;
        if (Math.abs(next - badgeSizeScale) < 0.001f) return;
        badgeSizeScale = next;
        invalidate();
    }

    private static int clampBadgePercent(int percent) {
        return Math.max(BubbleConfig.MIN_BADGE_SIZE_PERCENT,
                Math.min(BubbleConfig.MAX_BADGE_SIZE_PERCENT, percent));
    }

    public void setIconAnimated(Drawable nextIcon) {
        if (nextIcon == null) return;
        if (icon == null || !attached) {
            setIcon(nextIcon);
            return;
        }
        if (iconAnimator != null) iconAnimator.cancel();
        outgoingIcon = icon;
        incomingIcon = nextIcon;
        iconTransition = 0f;
        iconAnimator = ValueAnimator.ofFloat(0f, 1f);
        iconAnimator.setDuration(320L);
        iconAnimator.setInterpolator(new DecelerateInterpolator(1.35f));
        iconAnimator.addUpdateListener(animator -> {
            iconTransition = (float) animator.getAnimatedValue();
            invalidate();
        });
        iconAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!canceled) icon = nextIcon;
                outgoingIcon = null;
                incomingIcon = null;
                iconTransition = 1f;
                iconAnimator = null;
                invalidate();
            }
        });
        iconAnimator.start();
    }

    public void setAppBadge(Drawable nextIcon) {
        if (appBadgeAnimator != null) appBadgeAnimator.cancel();
        appBadgeIcon = nextIcon;
        float start = appBadgeScale;
        appBadgeAnimator = ValueAnimator.ofFloat(start, nextIcon == null ? 0f : 1f);
        appBadgeAnimator.setDuration(220L);
        appBadgeAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        appBadgeAnimator.addUpdateListener(a -> { appBadgeScale = (float) a.getAnimatedValue(); invalidate(); });
        appBadgeAnimator.start();
    }


    /** Small inward response as the notification preview is absorbed. */
    public void playPreviewAbsorb() {
        if (!attached || dismissing || bursting) return;
        animate().cancel();
        restoreVisibleAlpha();
        animate().scaleX(0.92f).scaleY(1.07f).setDuration(130L)
                .withEndAction(() -> animate().scaleX(1f).scaleY(1f)
                        .setDuration(190L).start()).start();
    }

    private void restoreVisibleAlpha() {
        if (!timeoutDimmed) {
            setAlpha(dockEdge == 0 ? 1f : 0.5f);
        }
    }

    public void setChildIcons(List<Drawable> icons) {
        childIcons.clear();
        if (icons != null) childIcons.addAll(icons);
        invalidate();
    }

    public void setStackCount(int count) {
        stackCount = Math.max(0, count);
        invalidate();
    }

    public Rect getStackBadgeBounds() {
        int radius = Math.round(dp(12f) * bubbleScale);
        int centerX = params.x + Math.round(stackBadgeCenterX() * bubbleScale);
        int centerY = params.y + Math.round((designSize / 2f - dp(22f)) * bubbleScale);
        return new Rect(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);
    }

    public void setStackExpanded(boolean expanded) {
        if (badgeAnimator != null) badgeAnimator.cancel();
        float start = badgeScale;
        badgeAnimator = ValueAnimator.ofFloat(0f, 1f);
        badgeAnimator.setDuration(expanded ? 190L : 240L);
        badgeAnimator.addUpdateListener(animator -> {
            float progress = (float) animator.getAnimatedValue();
            float curve = expanded ? backIn(progress) : backOut(progress);
            badgeScale = clamp(lerp(start, expanded ? 0f : 1f, curve), 0f, 1.18f);
            invalidate();
        });
        badgeAnimator.start();
        // The expanded fan owns the screen, so the source bubble freezes: no landing bounce,
        // no orbit decoration and no auto-dismiss deadline while it is open.
        if (expanded) {
            stopLandingAnimation();
            stopOrbitAnimation();
            mainHandler.removeCallbacks(timeoutAction);
            return;
        }
        mainHandler.removeCallbacks(timeoutAction);
        if (attached && !dismissing && dockEdge == 0) {
            if (!landingSuppressed) mainHandler.postDelayed(repeatLandingAction, 700L);
            if (timeoutSeconds > 0) {
                mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L);
            }
        }
    }

    public void setOnExpandGestureAction(Runnable action) {
        expandGestureAction = action;
    }

    public void setOnClickAction(Runnable action) {
        clickAction = action;
    }

    public void setOnDismissedAction(Runnable action) {
        dismissedAction = action;
    }

    public void setOnPositionChangedListener(PositionListener listener) {
        positionListener = listener;
    }

    public void setOnDragStartedAction(Runnable action) {
        dragStartedAction = action;
    }

    public void setOnBottomDismissAction(Runnable action) {
        bottomDismissAction = action;
    }

    public void setOnConfigurationChangedAction(Runnable action) {
        configurationChangedAction = action;
    }

    public void setOnLayoutChangedAction(Runnable action) {
        layoutChangedAction = action;
    }

    public void setOnLongPressAction(Runnable action) {
        longPressCallback = action;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Window metrics and system-bar insets change with rotation. Re-resolve the
        // persisted position for the new orientation instead of retaining stale bounds.
        if (!attached || windowManager == null || dismissing) return;
        final int previousDock = dockEdge;
        mainHandler.postDelayed(() -> {
            if (!attached || dismissing) return;
            try {
                resolvePosition(config);
                // A dock is a physical edge choice, so carry it across orientation changes
                // for the currently attached window. Avoid writing remote preferences from
                // SystemUI's configuration callback, which can restart SystemUI on some ROMs.
                if (previousDock != 0) {
                    dockEdge = previousDock;
                    params.x = dockEdge < 0 ? -viewSize / 2 : screenWidth - viewSize / 2;
                }
                updateWindowLayout();
                if (dockEdge != 0) startDockBreathing();
                if (configurationChangedAction != null) configurationChangedAction.run();
            } catch (Throwable ignored) {
                // Rotation can briefly expose invalid WindowMetrics while SystemUI is
                // recreating its display context. Leave the existing window untouched.
            }
        }, 80L);
    }

    public void setAutoDismissValidator(BooleanSupplier validator) {
        autoDismissValidator = validator;
    }

    public void setOnSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    public Rect getWindowBounds() {
        int logicalY = imeAvoiding ? imeRestoreY : params.y;
        return new Rect(params.x, logicalY, params.x + viewSize, logicalY + viewSize);
    }

    /** Bounds of the visible main bubble, excluding the transparent overlay padding. */
    public Rect getCoreBounds() {
        int radius = Math.round(dp(CORE_RADIUS_DP) * bubbleScale);
        int centerX = params.x + viewSize / 2;
        int centerY = params.y + viewSize / 2;
        return new Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    }

    /** Screen-space bounds of the drawn glass circle, not the requested window origin. */
    Rect getPreviewAnchorBounds() {
        if (!isLaidOut() || !isAttachedToWindow()) return getCoreBounds();
        int[] location = new int[2];
        getLocationOnScreen(location);
        float radius = dp(CORE_RADIUS_DP) * bubbleScale;
        float center = designSize * bubbleScale / 2f;
        RectF circle = new RectF(center - radius, center - radius,
                center + radius, center + radius);
        // The screen origin returned above already includes the View transform.
        // Map only the offset from that origin, including any animated scale.
        float[] origin = {0f, 0f};
        getMatrix().mapPoints(origin);
        getMatrix().mapRect(circle);
        circle.offset(location[0] - origin[0], location[1] - origin[1]);
        return new Rect(Math.round(circle.left), Math.round(circle.top),
                Math.round(circle.right), Math.round(circle.bottom));
    }

    public boolean isDocked() {
        return dockEdge != 0;
    }

    public int getDockEdge() {
        return dockEdge;
    }

    public boolean isAttached() {
        return attached;
    }

    public void applyRuntimePosition(int x, int y, int edge) {
        params.x = x;
        params.y = y;
        dockEdge = Integer.compare(edge, 0);
    }

    public List<Drawable> getChildIcons() {
        return new ArrayList<>(childIcons);
    }

    /** Applies the persisted position before the overlay is attached. */
    public void applyPersistedPosition(int xPercent, int yPercent, int edge) {
        int horizontalRange = Math.max(0, visibleRight - visibleLeft);
        int verticalRange = Math.max(0, safeBottom - safeTop);
        dockEdge = Integer.compare(edge, 0);
        params.x = visibleLeft + Math.round(horizontalRange * clamp(xPercent, 0, 100) / 100f);
        params.y = safeTop + Math.round(verticalRange * clamp(yPercent, 0, 100) / 100f);
        if (dockEdge < 0) params.x = -viewSize / 2;
        if (dockEdge > 0) params.x = screenWidth - viewSize / 2;
    }

    /** Recomputes the draggable bounds for the current window size without moving the bubble. */
    private void refreshSafeBounds() {
        Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
        WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
        android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        screenWidth = bounds.width();
        visibleLeft = bars.left + dp(8f);
        visibleRight = bounds.width() - bars.right - viewSize - dp(8f);
        safeTop = bars.top + dp(8f);
        safeBottom = bounds.height() - bars.bottom - viewSize - dp(12f);
    }

    public boolean show(int timeoutSeconds, boolean dimOnTimeout) {
        if (attached || windowManager == null) return false;
        try {
            windowManager.addView(this, params);
            attached = true;
            mainHandler.post(imeAvoidanceAction);
        } catch (Throwable error) {
            showError = error;
            return false;
        }

        this.timeoutSeconds = timeoutSeconds;
        this.dimOnTimeout = dimOnTimeout;
        timeoutDimmed = false;
        setAlpha(0f);
        setScaleX(1f);
        setScaleY(1f);
        setTranslationY(0f);
        animate()
                .alpha(dockEdge == 0 ? 1f : 0.5f)
                .setDuration(180L)
                .start();
        if (dockEdge != 0) mainHandler.postDelayed(this::startDockBreathing, 190L);
        if (dockEdge == 0 && !landingSuppressed) {
            mainHandler.postDelayed(repeatLandingAction, 700L);
        }
        if (dockEdge == 0 && timeoutSeconds > 0) {
            mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L);
        }
        return true;
    }

    public Throwable getShowError() {
        return showError;
    }

    public void updateTimeout(int seconds, boolean dimOnTimeout) {
        timeoutSeconds = Math.max(0, seconds);
        this.dimOnTimeout = dimOnTimeout;
        timeoutDimmed = false;
        mainHandler.removeCallbacks(timeoutAction);
        // Restore the resting alpha for both docked and free bubbles: the entrance animation
        // may never have completed while the display was off, which would otherwise leave the
        // bubble invisible when the screen wakes up.
        if (attached && !dismissing && !bursting) {
            animate().alpha(dockEdge == 0 ? 1f : 0.5f).setDuration(160L).start();
        }
        if (attached && !dismissing && dockEdge == 0 && timeoutSeconds > 0) {
            mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L);
        }
    }

    public void setLandingAnimationSuppressed(boolean suppressed) {
        landingSuppressed = suppressed;
        if (suppressed) {
            animate().cancel();
            stopLandingAnimation();
            stopDockBreathing();
            restoreVisibleAlpha();
        } else if (attached && !dismissing && dockEdge != 0) {
            startDockBreathing();
        } else if (attached && !dismissing && !timeoutDimmed) {
            mainHandler.postDelayed(repeatLandingAction, 700L);
        }
    }

    public void dismiss() {
        dismiss("requested");
    }

    public void playBurst(Runnable completion) {
        if (!attached || dismissing || bursting) {
            if (completion != null) completion.run();
            return;
        }
        bursting = true;
        pointerDown = false;
        mainHandler.removeCallbacks(timeoutAction);
        mainHandler.removeCallbacks(longPressAction);
        mainHandler.removeCallbacks(imeAvoidanceAction);
        stopLandingAnimation();
        animate().cancel();
        burstProgress = 0f;
        burstAnimator = ValueAnimator.ofFloat(0f, 1f);
        burstAnimator.setDuration(430L);
        burstAnimator.addUpdateListener(animator -> {
            burstProgress = (float) animator.getAnimatedValue();
            invalidate();
        });
        burstAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                burstAnimator = null;
                if (canceled) return;
                removeImmediately();
                if (completion != null) completion.run();
            }
        });
        burstAnimator.start();
    }

    private void dismiss(String reason) {
        if (!attached || dismissing || bursting) return;
        android.util.Log.i("BubbleNotify", "bubble dismiss: " + reason
                + "; timeout=" + timeoutSeconds);
        dismissing = true;
        mainHandler.removeCallbacks(timeoutAction);
        mainHandler.removeCallbacks(longPressAction);
        mainHandler.removeCallbacks(imeAvoidanceAction);
        stopLandingAnimation();
        stopOrbitAnimation();
        if (burstAnimator != null) {
            burstAnimator.cancel();
            burstAnimator = null;
        }
        animate().cancel();
        animate()
                .alpha(0f)
                .scaleX(0.78f)
                .scaleY(0.78f)
                .translationY(dp(12f) * bubbleScale)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        removeImmediately();
                    }
                })
                .start();
    }

    public void removeImmediately() {
        mainHandler.removeCallbacks(timeoutAction);
        mainHandler.removeCallbacks(longPressAction);
        mainHandler.removeCallbacks(imeAvoidanceAction);
        pointerDown = false;
        stopLandingAnimation();
        stopOrbitAnimation();
        if (burstAnimator != null) {
            burstAnimator.cancel();
            burstAnimator = null;
        }
        animate().cancel();
        if (attached) {
            try {
                windowManager.removeViewImmediate(this);
            } catch (Throwable ignored) {
            }
            attached = false;
        }
        Runnable callback = dismissedAction;
        dismissedAction = null;
        if (callback != null) callback.run();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bursting) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                restoreFromTimeoutDim();
                pointerDown = true;
                dragging = false;
                longPressHandled = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downWindowX = params.x;
                downWindowY = params.y;
                mainHandler.postDelayed(longPressAction, 450L);
                return true;
            case MotionEvent.ACTION_MOVE:
                float distanceX = event.getRawX() - downRawX;
                float distanceY = event.getRawY() - downRawY;
                if (longPressHandled && !dragging
                        && distanceY < -dp(EXPAND_SWIPE_DP)
                        && Math.abs(distanceY) > Math.abs(distanceX) * 1.15f) {
                    // Long press, then swipe up: fan out the queued bubbles instead of
                    // dragging this one. Any other direction keeps the drag behaviour.
                    longPressHandled = false;
                    pointerDown = false;
                    mainHandler.removeCallbacks(longPressAction);
                    Runnable expand = expandGestureAction;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (expand != null) expand.run();
                    return true;
                }
                if (!dragging && distanceX * distanceX + distanceY * distanceY
                        > touchSlop * touchSlop) {
                    mainHandler.removeCallbacks(longPressAction);
                    if (longPressHandled) beginDrag();
                }
                if (dragging) updateDrag(distanceX, distanceY);
                return true;
            case MotionEvent.ACTION_UP:
                mainHandler.removeCallbacks(longPressAction);
                pointerDown = false;
                if (dragging) {
                    finishDrag();
                } else if (longPressHandled) {
                    longPressHandled = false;
                    Runnable longPress = longPressCallback;
                    if (longPress != null && !dismissing) longPress.run();
                    return true;
                } else if (dispatchSwipe(event.getRawX() - downRawX, event.getRawY() - downRawY)) {
                    return true;
                } else if (isCoreTouch(event)) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(longPressAction);
                pointerDown = false;
                if (dragging) finishDrag();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Runnable action = clickAction;
        if (action != null && !dismissing) action.run();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Everything is laid out in the fixed 92dp design space and scaled once here, so
        // the glass core, notification icon, app badge and decorations stay proportional
        // at every bubble size. The window itself is viewSize = designSize * bubbleScale.
        float cx = designSize / 2f;
        float cy = designSize / 2f;
        float mainAlpha = bursting
                ? clamp(1f - Math.max(0f, burstProgress - 0.18f) / 0.62f, 0f, 1f)
                : 1f;
        float mainScale = bursting
                ? 1f + 0.18f * (float) Math.sin(Math.PI * Math.min(1f, burstProgress / 0.7f))
                : 1f;
        int outer = canvas.save();
        canvas.scale(bubbleScale, bubbleScale);
        int layer = canvas.saveLayerAlpha(0f, 0f, designSize, designSize,
                Math.round(mainAlpha * 255f));
        canvas.scale(mainScale, mainScale, cx, cy);
        drawMainBubble(canvas, cx, cy);
        canvas.restoreToCount(layer);
        drawStackBadge(canvas, cx, cy);
        drawAppBadge(canvas, cx, cy);
        if (bursting) drawBurstFragments(canvas, cx, cy);
        canvas.restoreToCount(outer);
    }


    private void drawStackBadge(Canvas canvas, float cx, float cy) {
        if (stackCount <= 0 || badgeScale <= 0.001f) return;
        float x = stackBadgeCenterX();
        float y = cy - dp(22f);
        float radius = dp(12f);
        int save = canvas.save();
        canvas.scale(badgeScale, badgeScale, x, y);
        badgeShadowPaint.setShader(new RadialGradient(x, y + dp(1.5f), radius + dp(4f),
                Color.argb(70, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        badgeCorePaint.setShader(new RadialGradient(x - dp(3f), y - dp(4f), radius * 1.45f,
                Color.argb(245, 255, 255, 255), Color.argb(220, 212, 235, 247),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y + dp(1.5f), radius + dp(4f), badgeShadowPaint);
        canvas.drawCircle(x, y, radius, badgeCorePaint);
        canvas.drawCircle(x, y, radius - dp(0.8f), badgeRingPaint);
        String label = stackCount > 99 ? "99+" : String.valueOf(stackCount);
        float textSize = dp(label.length() >= 3 ? 8f : label.length() == 2 ? 9f : 10f);
        badgeTextPaint.setTextSize(textSize);
        badgeTextOutlinePaint.setTextSize(textSize);
        float baseline = y - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f;
        canvas.drawText(label, x, baseline, badgeTextOutlinePaint);
        canvas.drawText(label, x, baseline, badgeTextPaint);
        canvas.restoreToCount(save);
    }

    private void drawAppBadge(Canvas canvas, float cx, float cy) {
        if (appBadgeIcon == null || appBadgeScale <= 0.001f) return;
        float size = dp(BADGE_BASE_DP) * badgeSizeScale;
        float half = size / 2f;
        // The app badge's geometric centre sits exactly on the bubble rim instead of
        // floating inside the glass. The diagonal is kept from the historical offset,
        // normalised so half the badge pokes outside the circle at every badge size.
        float rim = dp(CORE_RADIUS_DP);
        float dirLength = (float) Math.hypot(BADGE_CENTER_X_DP, BADGE_CENTER_Y_DP);
        float dirX = BADGE_CENTER_X_DP / dirLength;
        float dirY = BADGE_CENTER_Y_DP / dirLength;
        float x = dockEdge > 0 ? cx - rim * dirX : cx + rim * dirX;
        float y = cy + rim * dirY;
        canvas.save();
        canvas.scale(appBadgeScale, appBadgeScale, x, y);
        // The app's own launcher icon is drawn as-is: no extra circle mask and no background
        // disc, so the badge looks exactly like the icon in the launcher / recent tasks.
        setBadgeBounds(appBadgeIcon, x - half, y - half, x + half, y + half);
        appBadgeIcon.setAlpha(255);
        appBadgeIcon.draw(canvas);
        canvas.restore();
    }

    /** Fits a drawable into the badge square while preserving its intrinsic aspect ratio. */
    private void setBadgeBounds(Drawable drawable, float left, float top,
                                float right, float bottom) {
        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        float boxWidth = right - left;
        float boxHeight = bottom - top;
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            iconBounds.set(Math.round(left), Math.round(top),
                    Math.round(right), Math.round(bottom));
        } else {
            float scale = Math.min(boxWidth / intrinsicWidth, boxHeight / intrinsicHeight);
            float drawWidth = intrinsicWidth * scale;
            float drawHeight = intrinsicHeight * scale;
            float centerX = (left + right) / 2f;
            float centerY = (top + bottom) / 2f;
            iconBounds.set(Math.round(centerX - drawWidth / 2f),
                    Math.round(centerY - drawHeight / 2f),
                    Math.round(centerX + drawWidth / 2f),
                    Math.round(centerY + drawHeight / 2f));
        }
        drawable.setBounds(iconBounds);
    }

    private float stackBadgeCenterX() {
        // Design-space coordinate; the whole canvas is scaled by bubbleScale in onDraw.
        return designSize / 2f + (dockEdge > 0 ? -dp(22f) : dp(22f));
    }

    private void drawMainBubble(Canvas canvas, float cx, float cy) {
        float coreRadius = dp(CORE_RADIUS_DP);

        shadowPaint.setShader(shadowGradient);
        canvas.drawCircle(cx, cy + dp(3f), coreRadius + dp(7f), shadowPaint);

        // Translucent glass core: the radial gradient keeps the bubble see-through.
        corePaint.setShader(coreGradient);
        canvas.drawCircle(cx, cy, coreRadius, corePaint);
        canvas.drawCircle(cx, cy, coreRadius - dp(1f), ringPaint);

        // Frosted refraction / edge highlight, the layer that reads as "glass".
        drawBackgroundRefraction(canvas, cx, cy, coreRadius);

        // The icon may be configured larger than the glass core (up to 150%); clip it to the
        // core so a full-bleed icon can never paint over the bubble rim.
        int iconSave = canvas.save();
        coreClip.reset();
        coreClip.addCircle(cx, cy, coreRadius - dp(0.6f), Path.Direction.CW);
        canvas.clipPath(coreClip);
        drawIcon(canvas, cx, cy);
        canvas.restoreToCount(iconSave);

        highlightPaint.setColor(Color.argb(145, 255, 255, 255));
        canvas.drawArc(highlightArc, 205f, 74f, false, highlightPaint);
    }

    /** Frosted-glass refraction and edge dispersion drawn on top of the translucent core. */
    private void drawBackgroundRefraction(Canvas canvas, float cx, float cy, float radius) {
        int save = canvas.save();
        iconClip.reset();
        iconClip.addCircle(cx, cy, radius - dp(2f), Path.Direction.CW);
        canvas.clipPath(iconClip);
        float bulge = 1.13f;
        canvas.translate(-dp(3.2f), -dp(2.4f));
        canvas.scale(bulge, 0.91f, cx, cy);
        refractionPaint.setShader(new RadialGradient(
                cx - dp(9f), cy - dp(11f), radius * 1.28f,
                Color.argb(66, 255, 255, 255), Color.argb(18, 176, 216, 238),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, refractionPaint);
        refractionPaint.setStyle(Paint.Style.STROKE);
        refractionPaint.setStrokeWidth(dp(3.2f));
        refractionPaint.setShader(null);
        refractionPaint.setColor(Color.argb(34, 255, 255, 255));
        canvas.drawOval(cx - radius * 0.78f, cy - radius * 0.68f,
                cx + radius * 0.82f, cy + radius * 0.72f, refractionPaint);
        refractionPaint.setStyle(Paint.Style.FILL);
        dispersionPaint.setStyle(Paint.Style.STROKE);
        dispersionPaint.setStrokeWidth(dp(1.55f));
        dispersionPaint.setStrokeCap(Paint.Cap.ROUND);
        dispersionPaint.setAntiAlias(true);
        dispersionPaint.setShader(glassEdgeGradient);
        dispersionPaint.setColor(Color.WHITE);
        dispersionPaint.setStrokeWidth(dp(1.15f));
        canvas.drawCircle(cx, cy, radius - dp(0.7f), dispersionPaint);
        dispersionPaint.setShader(null);
        canvas.restoreToCount(save);
    }

    private void drawChildBubbles(Canvas canvas, float cx, float cy) {
        int count = childIcons.size();
        if (count == 0 || landingFrameProgress < 0f) return;
        // At rest there is a single clean bubble. During landing, mostly-covered
        // staggered rims briefly reveal that more notifications are queued.
        for (int index = Math.min(count, 3) - 1; index >= 0; index--) {
            float phase = landingFrameProgress * (float) Math.PI * 4f - index * 0.52f;
            float pulse = Math.max(0f, (float) Math.sin(phase));
            float childRadius = dp(25f - index * 0.8f);
            float side = index % 2 == 0 ? -1f : 1f;
            float childX = cx + side * dp(3.2f + index * 1.1f) * pulse;
            float childY = cy + dp(2.5f + index) - dp(2.4f + index) * pulse;
            int alpha = Math.round((58f + 50f * pulse) * (1f - index * 0.13f));
            childCorePaint.setShader(null);
            childCorePaint.setColor(Color.argb(alpha, 236, 246, 252));
            childRingPaint.setColor(Color.argb(Math.min(180, alpha + 48), 255, 255, 255));
            canvas.drawCircle(childX, childY, childRadius, childCorePaint);
            canvas.drawCircle(childX, childY, childRadius - dp(1f), childRingPaint);
        }
    }

    private void drawBurstFragments(Canvas canvas, float cx, float cy) {
        float fragmentProgress = clamp((burstProgress - 0.12f) / 0.88f, 0f, 1f);
        int alpha = Math.round(210f * (1f - fragmentProgress));
        for (int index = 0; index < 12; index++) {
            double angle = Math.toRadians(index * 30f + (index % 2) * 8f);
            float distance = dp(18f + 24f * fragmentProgress + index % 3 * 2f);
            float x = cx + distance * (float) Math.cos(angle);
            float y = cy + distance * (float) Math.sin(angle);
            burstPaint.setColor(Color.argb(alpha, 236, 248, 255));
            canvas.drawCircle(x, y, dp(1.8f + index % 3), burstPaint);
        }
    }

    private void startOrbitAnimation() {
        if (orbitAnimator != null || !attached) return;
        orbitAnimator = ValueAnimator.ofFloat(orbitPhase, orbitPhase + 360f);
        orbitAnimator.setDuration(7200L);
        orbitAnimator.setRepeatCount(ValueAnimator.INFINITE);
        orbitAnimator.setInterpolator(new LinearInterpolator());
        orbitAnimator.addUpdateListener(animator -> {
            orbitPhase = (float) animator.getAnimatedValue() % 360f;
            invalidate();
        });
        orbitAnimator.start();
    }

    private void stopOrbitAnimation() {
        if (orbitAnimator != null) {
            orbitAnimator.cancel();
            orbitAnimator = null;
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Shaders live in the design space; onDraw applies bubbleScale to the canvas.
        float cx = designSize / 2f;
        float cy = designSize / 2f;
        float coreRadius = dp(CORE_RADIUS_DP);
        shadowGradient = new RadialGradient(
                cx, cy + dp(3f), coreRadius + dp(7f),
                darkMode ? Color.argb(105, 0, 0, 0) : Color.argb(76, 0, 0, 0),
                Color.TRANSPARENT, Shader.TileMode.CLAMP);
        coreGradient = new RadialGradient(
                cx - dp(7f), cy - dp(8f), coreRadius * 1.35f,
                darkMode ? Color.argb(125, 133, 171, 194) : Color.argb(122, 255, 255, 255),
                darkMode ? Color.argb(90, 45, 65, 79) : Color.argb(68, 226, 234, 242),
                Shader.TileMode.CLAMP);
        highlightArc.set(
                cx - coreRadius + dp(4f), cy - coreRadius + dp(4f),
                cx + coreRadius - dp(4f), cy + coreRadius - dp(4f));
        glassEdgeGradient = new SweepGradient(cx, cy,
                new int[]{Color.argb(darkMode ? 110 : 135, 255, 255, 255),
                        Color.argb(darkMode ? 72 : 92, 170, 220, 242),
                        Color.argb(darkMode ? 58 : 78, 225, 192, 220),
                        Color.argb(darkMode ? 110 : 135, 255, 255, 255)}, null);
    }

    private void drawIcon(Canvas canvas, float cx, float cy) {
        float radius = dp(ICON_RADIUS_DP) * iconScale;
        if (incomingIcon == null || outgoingIcon == null) {
            if (icon != null) BubbleIconRenderer.draw(canvas, icon, cx, cy, radius, iconClip);
            return;
        }
        float progress = smooth(iconTransition);
        drawTransitionIcon(canvas, outgoingIcon, cx, cy, radius,
                1f - progress, lerp(1f, 0.78f, progress));
        drawTransitionIcon(canvas, incomingIcon, cx, cy, radius,
                progress, lerp(0.78f, 1f, progress));
    }

    private void drawTransitionIcon(Canvas canvas, Drawable drawable, float cx, float cy,
                                    float radius, float alpha, float scale) {
        if (drawable == null || alpha <= 0.001f) return;
        int layer = canvas.saveLayerAlpha(cx - radius, cy - radius,
                cx + radius, cy + radius, Math.round(alpha * 255f));
        canvas.scale(scale, scale, cx, cy);
        BubbleIconRenderer.draw(canvas, drawable, cx, cy, radius, iconClip);
        canvas.restoreToCount(layer);
    }

    private void startLandingAnimation() {
        stopLandingAnimation();
        if (!attached || dockEdge != 0 || dismissing || landingSuppressed) return;
        landingAnimator = ValueAnimator.ofFloat(0f, 1f);
        landingAnimator.setDuration(1480L);
        landingAnimator.addUpdateListener(animator -> {
            float progress = (float) animator.getAnimatedValue();
            landingFrameProgress = progress;
            invalidate();
            if (progress < 0.45f) {
                applyBounceFrame(progress / 0.45f, 1f);
            } else if (progress < 0.52f) {
                applyRestFrame();
            } else if (progress < 0.91f) {
                applyBounceFrame((progress - 0.52f) / 0.39f, 0.68f);
            } else {
                applyRestFrame();
            }
        });
        landingAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                landingAnimator = null;
                landingFrameProgress = -1f;
                setScaleX(1f);
                setScaleY(1f);
                setTranslationY(0f);
                if (!canceled && mainAnimationContinuous && attached
                        && dockEdge == 0 && !dismissing && !timeoutDimmed
                        && !landingSuppressed) {
                    mainHandler.postDelayed(repeatLandingAction, 2600L);
                }
            }
        });
        landingAnimator.start();
    }

    private void applyBounceFrame(float progress, float amplitude) {
        if (progress < 0.16f) {
            float local = smooth(progress / 0.16f);
            setTranslationY(lerp(0f, dp(0.8f) * bubbleScale * amplitude, local));
            setScaleX(lerp(1f, deform(1.035f, amplitude), local));
            setScaleY(lerp(1f, deform(0.965f, amplitude), local));
        } else if (progress < 0.46f) {
            float local = smooth((progress - 0.16f) / 0.30f);
            setTranslationY(lerp(dp(0.8f) * bubbleScale * amplitude,
                    -dp(4f) * bubbleScale * amplitude, local));
            setScaleX(lerp(deform(1.035f, amplitude), deform(0.975f, amplitude), local));
            setScaleY(lerp(deform(0.965f, amplitude), deform(1.04f, amplitude), local));
        } else if (progress < 0.72f) {
            float local = smooth((progress - 0.46f) / 0.26f);
            setTranslationY(lerp(-dp(4f) * bubbleScale * amplitude,
                    dp(2f) * bubbleScale * amplitude, local));
            setScaleX(lerp(deform(0.975f, amplitude), deform(1.075f, amplitude), local));
            setScaleY(lerp(deform(1.04f, amplitude), deform(0.92f, amplitude), local));
        } else if (progress < 0.88f) {
            float local = smooth((progress - 0.72f) / 0.16f);
            setTranslationY(lerp(dp(2f) * bubbleScale * amplitude,
                    -dp(0.8f) * bubbleScale * amplitude, local));
            setScaleX(lerp(deform(1.075f, amplitude), deform(0.99f, amplitude), local));
            setScaleY(lerp(deform(0.92f, amplitude), deform(1.015f, amplitude), local));
        } else {
            float local = smooth((progress - 0.88f) / 0.12f);
            setTranslationY(lerp(-dp(0.8f) * bubbleScale * amplitude, 0f, local));
            setScaleX(lerp(deform(0.99f, amplitude), 1f, local));
            setScaleY(lerp(deform(1.015f, amplitude), 1f, local));
        }
    }

    private void applyRestFrame() {
        setTranslationY(0f);
        setScaleX(1f);
        setScaleY(1f);
    }

    private void stopLandingAnimation() {
        mainHandler.removeCallbacks(repeatLandingAction);
        if (landingAnimator != null) {
            landingAnimator.cancel();
            landingAnimator = null;
        }
        landingFrameProgress = -1f;
        setScaleX(1f);
        setScaleY(1f);
        setTranslationY(0f);
    }

    private void handleTimeout() {
        if (!attached || dismissing) return;
        if (autoDismissValidator != null) {
            try {
                if (!autoDismissValidator.getAsBoolean()) {
                    timeoutSeconds = 0;
                    android.util.Log.i("BubbleNotify",
                            "ignored timeout; auto-dismiss is currently disabled");
                    return;
                }
            } catch (Throwable error) {
                android.util.Log.w("BubbleNotify",
                        "unable to verify timeout policy; preserving bubble", error);
                return;
            }
        }
        if (timeoutSeconds <= 0) {
            android.util.Log.i("BubbleNotify", "ignored stale timeout; auto-dismiss disabled");
            return;
        }
        if (!dimOnTimeout) {
            dismiss("timeout");
            return;
        }
        timeoutDimmed = true;
        stopLandingAnimation();
        animate().cancel();
        animate().alpha(0.35f).setDuration(360L)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void restoreFromTimeoutDim() {
        if (!timeoutDimmed || dockEdge != 0) return;
        timeoutDimmed = false;
        animate().cancel();
        animate().alpha(1f).setDuration(150L).start();
        if (timeoutSeconds > 0) {
            mainHandler.removeCallbacks(timeoutAction);
            mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L);
        }
    }

    private void beginDrag() {
        if (!pointerDown || dismissing || !attached) return;
        dragging = true;
        // A long-press that turns into movement is a drag, never a deferred
        // expand action when the finger is released.
        longPressHandled = false;
        mainHandler.removeCallbacks(timeoutAction);
        stopLandingAnimation();
        stopDockBreathing();
        setAlpha(1f);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (dragStartedAction != null) dragStartedAction.run();
        animate().cancel();
        animate().scaleX(1.08f).scaleY(1.08f).setDuration(140L).start();
    }

    private void handleLongPress() {
        if (!pointerDown || dismissing || !attached) return;
        Runnable callback = longPressCallback;
        if (callback != null) {
            longPressHandled = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return;
        }
        beginDrag();
    }

    private void updateDrag(float distanceX, float distanceY) {
        params.x = Math.round(clamp(
                downWindowX + distanceX, -viewSize / 2f, screenWidth - viewSize / 2f));
        params.y = Math.round(clamp(downWindowY + distanceY, safeTop, safeBottom));
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Throwable ignored) {
        }
    }

    private void finishDrag() {
        dragging = false;
        params.y = Math.round(clamp(params.y, safeTop, safeBottom));
        if (params.y >= safeBottom - dp(52f)) {
            dismissToBottom();
            return;
        }
        float centerX = params.x + viewSize / 2f;
        int snapDistance = dp(52f);
        if (centerX <= snapDistance) {
            animateToDock(-1);
        } else if (centerX >= screenWidth - snapDistance) {
            animateToDock(1);
        } else {
            stopDockBreathing();
            dockEdge = 0;
            restoreVisibleAlpha();
            params.x = Math.round(clamp(params.x, visibleLeft, visibleRight));
            updateWindowLayout();
            notifyPositionChanged();
        }
        animate().cancel();
        animate()
                .alpha(dockEdge == 0 ? 1f : 0.5f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        if (dockEdge == 0) {
            if (!landingSuppressed) {
                mainHandler.postDelayed(repeatLandingAction, 700L);
            }
            if (timeoutSeconds > 0) {
                mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L);
            }
        }
    }

    private void dismissToBottom() {
        if (!attached || dismissing) return;
        dismissing = true;
        Runnable action = bottomDismissAction;
        if (action != null) action.run();
        mainHandler.removeCallbacks(timeoutAction);
        mainHandler.removeCallbacks(longPressAction);
        stopLandingAnimation();
        stopDockBreathing();
        animate().cancel();
        animate().alpha(0f).scaleX(0.38f).scaleY(0.38f)
                .translationY(dp(76f) * bubbleScale).setDuration(260L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        removeImmediately();
                    }
                }).start();
    }

    private boolean dispatchSwipe(float distanceX, float distanceY) {
        float threshold = dp(38f);
        if (Math.abs(distanceX) < threshold && Math.abs(distanceY) < threshold) return false;
        if (Math.abs(distanceX) > Math.abs(distanceY)) {
            if (dockingEnabled) animateToDock(distanceX < 0f ? -1 : 1);
            return true;
        }
        SwipeListener listener = swipeListener;
        if (listener != null) {
            listener.onVerticalSwipe(distanceY < 0f);
        }
        return true;
    }

    /**
     * Temporary-notification setting: keep the bubble floating instead of docking to an edge.
     * Must be safe to call before the window is attached (showQueueHead configures the bubble
     * before show()), otherwise WindowManager.updateViewLayout throws and takes SystemUI down.
     */
    public void setDockingEnabled(boolean enabled) {
        dockingEnabled = enabled;
        if (enabled || dockEdge == 0) return;
        dockEdge = 0;
        stopDockBreathing();
        setAlpha(1f);
        params.x = Math.round(clamp(params.x, 0, Math.max(0, screenWidth - viewSize)));
        if (!attached || dismissing) return;
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Throwable error) {
            showError = error;
        }
    }

    private void animateToDock(int edge) {
        if (!attached || !dockingEnabled) return;
        dockEdge = Integer.compare(edge, 0);
        stopDockBreathing();
        int startX = params.x;
        int targetX = dockEdge < 0 ? -viewSize / 2 : screenWidth - viewSize / 2;
        if (dockAnimator != null) dockAnimator.cancel();
        dockAnimator = ValueAnimator.ofInt(startX, targetX);
        dockAnimator.setDuration(260L);
        dockAnimator.setInterpolator(new DecelerateInterpolator(1.7f));
        dockAnimator.addUpdateListener(animator -> {
            params.x = (Integer) animator.getAnimatedValue();
            updateWindowLayout();
        });
        dockAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override public void onAnimationCancel(Animator animation) { canceled = true; }

            @Override public void onAnimationEnd(Animator animation) {
                dockAnimator = null;
                if (!canceled) {
                    notifyPositionChanged();
                    startDockBreathing();
                }
            }
        });
        animate().cancel();
        animate().alpha(0.5f).scaleX(1f).scaleY(1f).setDuration(180L).start();
        dockAnimator.start();
    }

    private void updateWindowLayout() {
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Throwable ignored) {
        }
    }

    private void startDockBreathing() {
        if (!attached || dockEdge == 0 || dismissing || landingSuppressed) return;
        if (dockBreathAnimator != null) return;
        dockBreathAnimator = ValueAnimator.ofFloat(0f, 1f);
        dockBreathAnimator.setDuration(2600L);
        dockBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dockBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
        dockBreathAnimator.setInterpolator(new LinearInterpolator());
        dockBreathAnimator.addUpdateListener(animation -> {
            if (!attached || dockEdge == 0 || dismissing) return;
            float progress = (float) animation.getAnimatedValue();
            float wave = (float) Math.sin(progress * Math.PI);
            float baseAlpha = timeoutDimmed ? 0.35f : 0.5f;
            setAlpha(baseAlpha - wave * 0.15f);
            float scale = 1f - wave * 0.055f;
            setScaleX(scale);
            setScaleY(scale + wave * 0.018f);
        });
        dockBreathAnimator.start();
    }

    private void stopDockBreathing() {
        if (dockBreathAnimator != null) {
            dockBreathAnimator.cancel();
            dockBreathAnimator = null;
        }
        if (!dismissing) {
            setScaleX(1f);
            setScaleY(1f);
            if (dockEdge == 0) setAlpha(timeoutDimmed ? 0.35f : 1f);
        }
    }

    private void updateImeAvoidance() {
        if (!attached || windowManager == null) return;
        if (dragging) {
            if (imeAvoiding) imeRestoreY = params.y;
            mainHandler.postDelayed(imeAvoidanceAction, 100L);
            return;
        }
        try {
            android.view.WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            WindowInsets windowInsets = metrics.getWindowInsets();
            android.graphics.Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
            boolean visible = windowInsets.isVisible(WindowInsets.Type.ime())
                    && ime.bottom > dp(48f);
            int targetY = params.y;
            if (visible) {
                if (!imeAvoiding) {
                    imeAvoiding = true;
                    imeRestoreY = params.y;
                }
                int imeTop = bounds.bottom - ime.bottom;
                targetY = Math.min(imeRestoreY, imeTop - viewSize - dp(12f));
                targetY = Math.max(safeTop, targetY);
            } else if (imeAvoiding) {
                targetY = imeRestoreY;
            }
            animateImeTo(targetY, !visible && imeAvoiding);
        } catch (Throwable ignored) {
        }
        // Visibility changes are polled, while the actual movement is frame-paced
        // by ValueAnimator to avoid the old 25fps stepped motion.
        if (attached) mainHandler.postDelayed(imeAvoidanceAction, 100L);
    }

    private void animateImeTo(int targetY, boolean restoring) {
        targetY = Math.round(clamp(targetY, safeTop, safeBottom));
        if (imeAnimator != null && imeAnimator.isRunning() && imeTargetY == targetY) return;
        if (params.y == targetY) {
            if (restoring) imeAvoiding = false;
            return;
        }
        if (imeAnimator != null) imeAnimator.cancel();
        int startY = params.y;
        imeTargetY = targetY;
        imeAnimator = ValueAnimator.ofInt(startY, targetY);
        imeAnimator.setDuration(restoring ? 230L : 180L);
        imeAnimator.setInterpolator(new DecelerateInterpolator(1.35f));
        imeAnimator.addUpdateListener(animation -> {
            if (!attached) return;
            params.y = (Integer) animation.getAnimatedValue();
            updateWindowLayout();
            if (layoutChangedAction != null) layoutChangedAction.run();
        });
        imeAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;
            @Override public void onAnimationCancel(Animator animation) { canceled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!canceled && restoring && Math.abs(params.y - imeRestoreY) <= 2) {
                    params.y = imeRestoreY;
                    imeAvoiding = false;
                }
                if (imeAnimator == animation) imeAnimator = null;
            }
        });
        imeAnimator.start();
    }

    private void notifyPositionChanged() {
        float rangeX = Math.max(1f, visibleRight - visibleLeft);
        float rangeY = Math.max(1f, safeBottom - safeTop);
        int x = Math.round((clamp(params.x, visibleLeft, visibleRight) - visibleLeft)
                / rangeX * 100f);
        int y = Math.round((params.y - safeTop) / rangeY * 100f);
        PositionListener listener = positionListener;
        if (listener != null) listener.onPositionChanged(landscape, x, y, dockEdge);
    }

    private boolean isCoreTouch(MotionEvent event) {
        float dx = event.getX() - getWidth() / 2f;
        float dy = event.getY() - getHeight() / 2f;
        float touchRadius = dp(CORE_RADIUS_DP) * bubbleScale + dp(8f);
        return dx * dx + dy * dy <= touchRadius * touchRadius;
    }

    private void resolvePosition(BubbleConfig config) {
        refreshSafeBounds();

        landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int horizontalRange = Math.max(0, visibleRight - visibleLeft);
        int verticalRange = Math.max(0, safeBottom - safeTop);
        params.x = visibleLeft
                + Math.round(horizontalRange * config.getPositionX(landscape) / 100f);
        params.y = safeTop + Math.round(verticalRange * config.getPositionY(landscape) / 100f);
        dockEdge = config.getDockEdge(landscape);
        if (dockEdge < 0) params.x = -viewSize / 2;
        if (dockEdge > 0) params.x = screenWidth - viewSize / 2;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private float smooth(float progress) {
        float value = clamp(progress, 0f, 1f);
        return value * value * (3f - 2f * value);
    }

    private float backIn(float value) {
        float tension = 1.35f;
        return value * value * ((tension + 1f) * value - tension);
    }

    private float backOut(float value) {
        float tension = 1.55f;
        float shifted = value - 1f;
        return 1f + shifted * shifted * ((tension + 1f) * shifted + tension);
    }

    private float deform(float target, float amplitude) {
        return 1f + (target - 1f) * amplitude;
    }

    public interface PositionListener {
        void onPositionChanged(boolean landscape, int xPercent, int yPercent, int dockEdge);
    }

    public interface SwipeListener {
        /** true for an upward swipe; false for a downward swipe. */
        void onVerticalSwipe(boolean up);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
