package io.github.freeform.bubble;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Native-Android-style bubble expansion.
 *
 * <p>Mirrors what the platform bubble stack does: the collapsed stack behind the main bubble
 * unfolds into a horizontal row (wrapping into a second row when needed) near the top of the
 * screen, over a dimmed background, with staggered Material motion. While it is open nothing
 * keeps animating; a tap on a bubble opens that notification/suggestion, a tap anywhere else
 * folds it back, and doing nothing for a moment folds it back into the stack.</p>
 */
final class BubbleStackOverlay extends View {
    private static final float BUBBLE_DP = 58f;
    private static final float GAP_DP = 10f;
    /** Distance of the row from the bottom edge (above the navigation bar / keyboard). */
    private static final float ROW_BOTTOM_DP = 84f;
    private static final int MAX_PER_ROW = 5;
    private static final long ENTER_DURATION_MS = 400L;
    private static final long EXIT_DURATION_MS = 260L;
    /** No interaction for this long folds the row back into the stack. */
    private static final long IDLE_COLLAPSE_MS = 4_200L;
    private static final PathInterpolator MATERIAL_DECELERATE =
            new PathInterpolator(0.05f, 0.70f, 0.10f, 1.00f);
    private static final PathInterpolator MATERIAL_ACCELERATE =
            new PathInterpolator(0.30f, 0.00f, 0.80f, 0.15f);

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Drawable> icons = new ArrayList<>();
    private final Paint scrim = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF highlightBounds = new RectF();
    private final Path clip = new Path();
    private final float anchorX;
    private final float anchorY;
    private final float mainRadius;
    private final float mainOpacity;
    private final IntConsumer clickListener;
    private final Runnable collapsedListener;
    private final Runnable idleCollapseAction = this::dismiss;

    private ValueAnimator animator;
    private float progress;
    private int imeBottomInset;
    private int navigationBottomInset;
    private final Runnable insetPollAction = this::updateInsetAvoidance;
    private boolean attached;
    private boolean collapsing;
    private int pressedIndex = -1;
    private float downX;
    private float downY;

    BubbleStackOverlay(Context context, List<Drawable> values, Rect anchor,
                       float opacity, IntConsumer clickListener, Runnable collapsedListener) {
        super(context);
        if (values != null) icons.addAll(values);
        this.clickListener = clickListener;
        this.collapsedListener = collapsedListener;
        anchorX = anchor.centerX();
        anchorY = anchor.centerY();
        mainRadius = Math.max(dp(18f), Math.min(anchor.width(), anchor.height()) / 2f);
        mainOpacity = Math.max(0.35f, Math.min(1f, opacity));

        windowManager = context.getSystemService(WindowManager.class);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("Bubble notification row");

        scrim.setColor(Color.BLACK);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.5f));
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(dp(1.2f));
        highlight.setStrokeCap(Paint.Cap.ROUND);
        pressPaint.setStyle(Paint.Style.FILL);
        pressPaint.setColor(Color.argb(40, 255, 255, 255));
    }

    boolean show() {
        if (icons.isEmpty() || windowManager == null) return false;
        try {
            windowManager.addView(this, params);
            attached = true;
        } catch (Throwable error) {
            return false;
        }
        animateTo(1f, ENTER_DURATION_MS);
        armIdleCollapse();
        updateInsetAvoidance();
        return true;
    }

    /** Folds the row back into the stacked bubbles (also used when it is replaced). */
    void dismiss() {
        handler.removeCallbacks(idleCollapseAction);
        if (!attached || collapsing) return;
        collapsing = true;
        pressedIndex = -1;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, 0f);
        animator.setDuration(EXIT_DURATION_MS);
        animator.setInterpolator(MATERIAL_ACCELERATE);
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                removeImmediately();
            }
        });
        animator.start();
    }

    private void animateTo(float target, long duration) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(duration);
        animator.setInterpolator(target > progress ? MATERIAL_DECELERATE : MATERIAL_ACCELERATE);
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void removeImmediately() {
        handler.removeCallbacksAndMessages(null);
        if (!attached) return;
        attached = false;
        try {
            windowManager.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
        if (collapsedListener != null) collapsedListener.run();
    }

    /**
     * 键盘（输入法）和导航条所占的高度。展开的一排气泡贴着屏幕下方，键盘弹出时必须整体上移，
     * 否则会被输入法挡住。
     */
    private void updateInsetAvoidance() {
        if (!attached) return;
        try {
            android.view.WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            WindowInsets insets = metrics.getWindowInsets();
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean imeVisible = insets.isVisible(WindowInsets.Type.ime()) && ime > dp(48f);
            int navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            boolean changed = (imeVisible ? ime : 0) != imeBottomInset
                    || navigation != navigationBottomInset;
            imeBottomInset = imeVisible ? ime : 0;
            navigationBottomInset = navigation;
            if (changed) invalidate();
        } catch (Throwable ignored) {
        }
        handler.postDelayed(insetPollAction, 120L);
    }

    private void armIdleCollapse() {
        handler.removeCallbacks(idleCollapseAction);
        handler.postDelayed(idleCollapseAction, IDLE_COLLAPSE_MS);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float p = smooth(progress);
        // Dimmed background, like the platform bubble expanded view.
        scrim.setAlpha(Math.round(112f * p));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), scrim);

        // The source bubble fades away while the row takes over.
        drawSource(canvas, p);
        for (int index = icons.size() - 1; index >= 0; index--) {
            drawItem(canvas, index);
        }
    }

    private void drawSource(Canvas canvas, float p) {
        int alpha = Math.round(255f * mainOpacity * (1f - p));
        if (alpha <= 4) return;
        float radius = mainRadius * (1f - 0.16f * p);
        int layer = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), alpha);
        fill.setShader(null);
        fill.setColor(surfaceColor());
        canvas.drawCircle(anchorX, anchorY, radius, fill);
        ring.setColor(outlineColor());
        canvas.drawCircle(anchorX, anchorY, radius - dp(1f), ring);
        canvas.restoreToCount(layer);
    }

    private void drawItem(Canvas canvas, int index) {
        float p = itemProgress(index);
        if (p <= 0.001f) return;
        float target = dp(BUBBLE_DP) / 2f;
        float radius = lerp(mainRadius, target, p);
        float x = lerp(anchorX, slotX(index), elastic(p));
        float y = lerp(anchorY, slotY(index), elastic(p));
        boolean pressed = index == pressedIndex;
        int layer = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(),
                Math.round(255f * Math.min(1f, p * 1.4f)));
        int save = canvas.save();
        canvas.scale(pressed ? 0.94f : 1f, pressed ? 0.94f : 1f, x, y);

        shadow.setShader(new RadialGradient(x, y + dp(3f), radius + dp(8f),
                Color.argb(84, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y + dp(3f), radius + dp(8f), shadow);
        shadow.setShader(null);
        fill.setShader(null);
        fill.setColor(surfaceColor());
        canvas.drawCircle(x, y, radius, fill);
        ring.setColor(outlineColor());
        canvas.drawCircle(x, y, radius - dp(1f), ring);
        if (pressed) canvas.drawCircle(x, y, radius, pressPaint);

        Drawable icon = icons.get(index);
        if (icon != null) {
            BubbleIconRenderer.draw(canvas, icon, x, y, radius * 0.62f, clip);
        }
        highlightBounds.set(x - radius + dp(4f), y - radius + dp(4f),
                x + radius - dp(4f), y + radius - dp(4f));
        highlight.setColor(Color.argb(145, 255, 255, 255));
        canvas.drawArc(highlightBounds, 205f, 74f, false, highlight);
        canvas.restoreToCount(save);
        canvas.restoreToCount(layer);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!attached || collapsing) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                pressedIndex = hitIndex(event.getX(), event.getY());
                armIdleCollapse();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > dp(12f)
                        || Math.abs(event.getY() - downY) > dp(12f)) {
                    if (pressedIndex >= 0) {
                        pressedIndex = -1;
                        invalidate();
                    }
                }
                armIdleCollapse();
                return true;
            case MotionEvent.ACTION_UP:
                int hit = hitIndex(event.getX(), event.getY());
                boolean sameTarget = hit >= 0 && hit == pressedIndex;
                pressedIndex = -1;
                invalidate();
                if (sameTarget && clickListener != null) {
                    clickListener.accept(hit);
                }
                dismiss();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedIndex = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int hitIndex(float x, float y) {
        if (progress < 0.85f) return -1;
        float radius = dp(BUBBLE_DP) / 2f + dp(4f);
        for (int index = 0; index < icons.size(); index++) {
            float dx = x - slotX(index);
            float dy = y - slotY(index);
            if (dx * dx + dy * dy <= radius * radius) return index;
        }
        return -1;
    }

    /** Staggered item progress: the row unfolds bubble by bubble from the source. */
    private float itemProgress(int index) {
        float delay = Math.min(0.42f, 0.05f + index * 0.05f);
        float value = Math.max(0f, Math.min(1f, (progress - delay) / (1f - delay)));
        return smooth(value);
    }

    private int perRow() {
        float step = dp(BUBBLE_DP) + dp(GAP_DP);
        int fit = (int) ((getWidth() - dp(20f)) / step);
        return Math.max(1, Math.min(MAX_PER_ROW, fit));
    }

    /** Horizontal position of a row slot, centred like the platform bubble row. */
    private float slotX(int index) {
        int per = perRow();
        int row = index / per;
        int column = index % per;
        int inRow = Math.min(per, icons.size() - row * per);
        float step = dp(BUBBLE_DP) + dp(GAP_DP);
        return getWidth() / 2f + (column - (inRow - 1) / 2f) * step;
    }

    private float slotY(int index) {
        int per = perRow();
        int rows = Math.max(1, (icons.size() + per - 1) / per);
        int row = index / per;
        float step = dp(BUBBLE_DP) + dp(12f);
        float height = getHeight() > 0 ? getHeight() : fallbackHeight();
        // Sit just above the navigation bar, and higher still while the keyboard is open so the
        // row never hides behind the input method.
        float bottom = height - navigationBottomInset - dp(ROW_BOTTOM_DP);
        if (imeBottomInset > 0) {
            bottom = Math.min(bottom, height - imeBottomInset - dp(16f));
        }
        float lastCenter = bottom - dp(BUBBLE_DP) / 2f;
        // Rows grow upwards: the last row stays closest to the bottom edge.
        return lastCenter - (rows - 1 - row) * step;
    }

    private float fallbackHeight() {
        try {
            return windowManager.getCurrentWindowMetrics().getBounds().height();
        } catch (Throwable ignored) {
            return dp(800f);
        }
    }

    private float elastic(float value) {
        if (value <= 0f || value >= 1f) return value;
        float recoil = 0.07f * (float) Math.sin(Math.PI * value)
                * (float) Math.sin(Math.PI * 2.2f * value);
        return Math.max(0f, Math.min(1f, value + recoil));
    }

    private float smooth(float value) {
        float t = Math.max(0f, Math.min(1f, value));
        return t * t * (3f - 2f * t);
    }

    private float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private int surfaceColor() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return dark ? Color.rgb(49, 52, 61) : Color.rgb(247, 248, 252);
    }

    private int outlineColor() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return dark ? Color.rgb(84, 89, 102) : Color.rgb(225, 228, 235);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
