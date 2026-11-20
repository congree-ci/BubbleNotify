package io.github.freeform.bubble;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.List;

/** A compact Material notification panel that expands from and collapses into its bubble. */
@SuppressLint("ViewConstructor")
public final class NotificationPreviewView extends LinearLayout {
    private static final int SIDE_LEFT = 0;
    private static final int SIDE_RIGHT = 1;
    private static final int SIDE_ABOVE = 2;
    private static final int SIDE_BELOW = 3;
    private static final float PANEL_RADIUS_DP = 18f;

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GradientDrawable panelDrawable;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shapeClip = new Path();
    private final TextView appView;
    private final TextView contentView;
    private final boolean autoWidth;
    private final int configuredWidthDp;
    private int overlayAlphaPercent;
    private final int horizontalPadding;
    private ImageView iconView;
    /** Icon column width; recalculated per show so it follows the bubble-sized panel. */
    private int iconSlotWidth;
    private final boolean darkMode;
    private final int overlayBaseColor;
    private float finalAlpha;
    private boolean attached;
    private boolean dismissing;
    private int preferredPosition = BubbleConfig.CONTENT_AUTO;
    private ValueAnimator entranceAnimator;
    private ValueAnimator collapseAnimator;
    private int anchorX;
    private int anchorY;
    private int anchorSide;
    private final Rect lastBubbleBounds = new Rect();
    private final Rect fluidCloudBounds = new Rect();
    private final Runnable fluidCloudPollAction = this::pollFluidCloudPosition;
    private float currentCornerRadius;
    private float shapeProgress = 1f;
    private float particleProgress;
    private boolean particleBurst;
    private Runnable dismissedAction;
    private Throwable showError;

    private final Runnable timeoutAction = this::dismiss;

    public NotificationPreviewView(Context context, Drawable icon, String appName,
                                   String title, String body, int alphaPercent,
                                   boolean autoWidth, int configuredWidthDp,
                                   int overlayAlphaPercent) {
        super(context);
        this.autoWidth = autoWidth;
        this.configuredWidthDp = configuredWidthDp;
        this.overlayAlphaPercent = Math.max(0, Math.min(70, overlayAlphaPercent));
        darkMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        MaterialPalette palette = MaterialPalette.resolve(context);
        overlayBaseColor = palette.surfaceContainerHigh;
        horizontalPadding = dp(10);
        iconSlotWidth = icon == null ? 0 : dp(44);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(horizontalPadding, dp(7), horizontalPadding, dp(7));
        setElevation(dp(8));
        setClickable(true);
        setFocusable(false);
        this.finalAlpha = clamp(alphaPercent, 35, 100) / 100f;
        panelDrawable = panelBackground();
        setBackground(panelDrawable);
        highlightPaint.setColor(darkMode ? Color.argb(34, 255, 255, 255)
                : Color.argb(62, 255, 255, 255));
        glassEdgePaint.setStyle(Paint.Style.STROKE);
        glassEdgePaint.setStrokeWidth(dp(1.05f));
        glassEdgePaint.setColor(darkMode ? Color.argb(86, 205, 231, 242)
                : Color.argb(108, 255, 255, 255));
        particlePaint.setColor(darkMode ? Color.argb(168, 153, 216, 242)
                : Color.argb(182, 230, 248, 255));

        if (icon != null) {
            iconView = new ImageView(context);
            Drawable previewDrawable = icon;
            try {
                if (icon.getConstantState() != null) {
                    previewDrawable = icon.getConstantState().newDrawable().mutate();
                }
            } catch (Throwable ignored) { }
            previewDrawable.setAlpha(255);
            iconView.setImageDrawable(previewDrawable);
            iconView.setAlpha(1f);
            iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LayoutParams iconParams = new LayoutParams(dp(34), dp(34));
            iconParams.rightMargin = dp(10);
            addView(iconView, iconParams);
        }

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        appView = text(joinSenderAndApp(title, appName), 10f,
                darkMode ? Color.rgb(183, 198, 210) : Color.rgb(80, 88, 96), true);
        contentView = text(body == null ? "" : body, 13f,
                darkMode ? Color.rgb(241, 245, 248) : Color.rgb(24, 28, 32), true);
        LayoutParams appParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textColumn.addView(appView, appParams);
        LayoutParams contentParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(2);
        textColumn.addView(contentView, contentParams);
        addView(textColumn, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        windowManager = context.getSystemService(WindowManager.class);
        params = new WindowManager.LayoutParams(
                dp(54), dp(54),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        // Position in display coordinates, like the bubble. Apply safe insets once,
        // in the placement solver, rather than letting WM offset this window again.
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("Bubble notification content");
    }

    /** 配置变化时实时更新已显示浮层的不透明度（不透明度更低则更透出下层）。 */
    void updateFinalAlpha(int percent) {
        this.finalAlpha = clamp(percent, 35, 100) / 100f;
        if (dismissing || !attached) return;
        params.alpha = finalAlpha;
        updateLayout();
        if (entranceAnimator != null && entranceAnimator.isRunning()) return; // 入场动画会自然收敛到新值
    }

    void updateOverlayAlpha(int percent) {
        overlayAlphaPercent = Math.max(0, Math.min(70, percent));
        if (attached) invalidate();
    }

    /** Fit the contents without changing the height chosen by the placement solver. */
    private void applyPanelMetrics(int finalWidth, int finalHeight) {
        int iconSize = iconView == null ? 0
                : Math.min(dp(34), Math.max(dp(16), finalHeight - dp(10)));
        if (iconView != null) {
            LayoutParams lp = (LayoutParams) iconView.getLayoutParams();
            if (lp.width != iconSize || lp.height != iconSize) {
                lp.width = iconSize;
                lp.height = iconSize;
                iconView.setLayoutParams(lp);
            }
        }
        iconSlotWidth = iconView == null ? 0 : iconSize + dp(10);
        int available = Math.max(1,
                finalWidth - horizontalPadding * 2 - iconSlotWidth - dp(2));
        int appHeight = configureText(appView, appView.getText().toString(),
                10f, 10f, available, false);
        int contentHeight = configureText(contentView, contentView.getText().toString(),
                13f, 12f, available, true);
        // Keep the text block inside the bubble-sized panel: fall back to one line,
        // then proportionally shrink both lines if the content is still too tall.
        int innerHeight = Math.max(dp(18), finalHeight - dp(4));
        if (appHeight + dp(2) + contentHeight > innerHeight) {
            contentHeight = configureText(contentView, contentView.getText().toString(),
                    13f, 11f, available, false);
        }
        if (appHeight + dp(2) + contentHeight > innerHeight) {
            float ratio = Math.max(0.78f,
                    (float) innerHeight / (appHeight + dp(2) + contentHeight));
            appView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * ratio);
            contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * ratio);
            appView.setSingleLine(true);
            contentView.setSingleLine(true);
            contentView.setMaxLines(1);
            contentView.setEllipsize(TextUtils.TruncateAt.END);
            appHeight = appView.getLineHeight();
            contentHeight = contentView.getLineHeight();
        }
        int verticalPadding = Math.max(0, Math.min(dp(7),
                (finalHeight - appHeight - dp(2) - contentHeight) / 2));
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
    }

    public boolean show(Rect bubbleBounds, int preferredPosition, int timeoutSeconds) {
        if (attached || windowManager == null) return false;
        this.preferredPosition = preferredPosition;
        Placement placement = resolvePlacement(bubbleBounds);
        if (placement == null) return false;
        Rect finalRect = placement.bounds;
        applyPanelMetrics(finalRect.width(), finalRect.height());
        lastBubbleBounds.set(bubbleBounds);
        int startSize = dp(6);
        anchorX = bubbleBounds.centerX();
        anchorY = bubbleBounds.centerY();
        anchorSide = placement.side;
        params.x = anchorX - startSize / 2;
        params.y = anchorY - startSize / 2;
        params.width = startSize;
        params.height = startSize;
        currentCornerRadius = startSize / 2f;
        panelDrawable.setCornerRadius(currentCornerRadius);
        setChildrenAlpha(0f);
        setAlpha(0f);
        params.alpha = 1f;
        try {
            windowManager.addView(this, params);
            attached = true;
        } catch (Throwable error) {
            showError = error;
            return false;
        }

        entranceAnimator = ValueAnimator.ofFloat(0f, 1f);
        entranceAnimator.setDuration(330L);
        entranceAnimator.addUpdateListener(animator -> {
            float progress = (float) animator.getAnimatedValue();
            float farProgress = easeOutCubic(clamp(progress / 0.72f, 0f, 1f));
            float nearProgress = easeOutCubic(clamp((progress - 0.16f) / 0.84f, 0f, 1f));
            float secondaryProgress = easeOutCubic(clamp(progress / 0.86f, 0f, 1f));
            applyAnchoredRect(finalRect, farProgress, nearProgress, secondaryProgress, true);
            float heightProgress = easeOutCubic(clamp(progress / 0.82f, 0f, 1f));
            currentCornerRadius = lerp(
                    startSize / 2f, dp(PANEL_RADIUS_DP), heightProgress);
            shapeProgress = clamp(progress / 0.34f, 0f, 1f);
            panelDrawable.setCornerRadius(currentCornerRadius);
            float stretch = (float) Math.sin(Math.PI * Math.min(1f, progress * 1.12f));
            setScaleX(1f + 0.045f * stretch);
            setScaleY(1f - 0.035f * stretch);
            setAlpha(finalAlpha * clamp(progress / 0.38f, 0f, 1f));
            params.alpha = 1f;
            setChildrenAlpha(clamp((progress - 0.2f) / 0.58f, 0f, 1f));
            updateLayout();
        });
        entranceAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                entranceAnimator = null;
                if (!attached || dismissing) return;
                setScaleX(1f);
                setScaleY(1f);
                updateRestingPlacement();
            }
        });
        entranceAnimator.start();
        if (timeoutSeconds > 0) {
            handler.postDelayed(timeoutAction, timeoutSeconds * 1000L);
        }
        handler.removeCallbacks(fluidCloudPollAction);
        handler.postDelayed(fluidCloudPollAction, 320L);
        return true;
    }

    /** Keeps the panel anchored to a moving bubble while the IME changes its Y. */
    public void updateAnchor(Rect bubbleBounds) {
        if (!attached || dismissing || bubbleBounds == null) return;
        boolean changed = !lastBubbleBounds.equals(bubbleBounds);
        lastBubbleBounds.set(bubbleBounds);
        if (changed && entranceAnimator == null) updateRestingPlacement();
    }

    private void updateRestingPlacement() {
        Placement placement = resolvePlacement(lastBubbleBounds);
        setVisibility(placement == null ? View.INVISIBLE : View.VISIBLE);
        if (placement == null) return;
        Rect bounds = placement.bounds;
        anchorSide = placement.side;
        anchorX = lastBubbleBounds.centerX();
        anchorY = lastBubbleBounds.centerY();
        boolean resized = params.width != bounds.width() || params.height != bounds.height();
        if (!resized && params.x == bounds.left && params.y == bounds.top) {
            // The entrance animation already delivered the final window bounds.
            currentCornerRadius = Math.min(dp(PANEL_RADIUS_DP), bounds.height() / 2f);
            panelDrawable.setCornerRadius(currentCornerRadius);
            return;
        }
        if (resized) applyPanelMetrics(bounds.width(), bounds.height());
        currentCornerRadius = Math.min(dp(PANEL_RADIUS_DP), bounds.height() / 2f);
        panelDrawable.setCornerRadius(currentCornerRadius);
        params.x = bounds.left;
        params.y = bounds.top;
        params.width = bounds.width();
        params.height = bounds.height();
        updateLayout();
    }

    public Throwable getShowError() {
        return showError;
    }

    public void setOnPreviewClickListener(Runnable action) {
        setOnClickListener(view -> {
            if (action != null) action.run();
        });
    }

    public void setOnDismissedListener(Runnable action) {
        dismissedAction = action;
    }

    public void dismiss() {
        if (!attached || dismissing) return;
        dismissing = true;
        handler.removeCallbacks(timeoutAction);
        if (entranceAnimator != null) entranceAnimator.cancel();
        float startChildrenAlpha = getChildCount() == 0 ? 1f : getChildAt(0).getAlpha();
        float startAlpha = getAlpha();
        Rect startRect = new Rect(params.x, params.y,
                params.x + params.width, params.y + params.height);
        float startRadius = currentCornerRadius;

        collapseAnimator = ValueAnimator.ofFloat(0f, 1f);
        collapseAnimator.setDuration(270L);
        collapseAnimator.addUpdateListener(animator -> {
            float progress = (float) animator.getAnimatedValue();
            float nearSuction = easeInOut(clamp(progress / 0.60f, 0f, 1f));
            float farSuction = easeInOut(clamp((progress - 0.16f) / 0.84f, 0f, 1f));
            float secondarySuction = easeInOut(progress);
            applyAnchoredRect(startRect, farSuction, nearSuction, secondarySuction, false);
            float shape = (float) Math.pow(progress, 1.12f);
            currentCornerRadius = lerp(startRadius, dp(6f) / 2f, shape);
            shapeProgress = 1f - clamp(progress / 0.32f, 0f, 1f);
            panelDrawable.setCornerRadius(currentCornerRadius);
            float recoil = (float) Math.sin(Math.PI * Math.min(1f, progress * 1.15f));
            setScaleX(1f - 0.04f * recoil);
            setScaleY(1f + 0.05f * recoil);
            setChildrenAlpha(startChildrenAlpha * (1f - clamp(progress / 0.56f, 0f, 1f)));
            setAlpha(startAlpha * (1f - clamp((progress - 0.18f) / 0.82f, 0f, 1f)));
            updateLayout();
        });
        collapseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setScaleX(1f);
                setScaleY(1f);
                removeImmediately();
            }
        });
        collapseAnimator.start();
    }

    public void removeImmediately() {
        handler.removeCallbacks(timeoutAction);
        handler.removeCallbacks(fluidCloudPollAction);
        if (entranceAnimator != null) {
            entranceAnimator.removeAllListeners();
            entranceAnimator.cancel();
            entranceAnimator = null;
        }
        if (collapseAnimator != null) {
            collapseAnimator.removeAllListeners();
            collapseAnimator.cancel();
            collapseAnimator = null;
        }
        if (!attached) return;
        try {
            windowManager.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
        attached = false;
        setScaleX(1f);
        setScaleY(1f);
        Runnable callback = dismissedAction;
        dismissedAction = null;
        if (callback != null) callback.run();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        applyMorphClip(canvas);
        if (overlayAlphaPercent > 0 && getWidth() > 1 && getHeight() > 1) {
            overlayPaint.setStyle(Paint.Style.FILL);
            int color = overlayBaseColor;
            overlayPaint.setColor(Color.argb(Math.round(255f * overlayAlphaPercent / 100f),
                    Color.red(color), Color.green(color), Color.blue(color)));
            float inset = dp(1.2f);
            float radius = Math.max(1f, currentCornerRadius - inset);
            canvas.drawRoundRect(inset, inset, getWidth() - inset,
                    getHeight() - inset, radius, radius, overlayPaint);
        }
        super.dispatchDraw(canvas);
        if (getWidth() > dp(24) && getHeight() > dp(20)) {
            float inset = dp(1.05f);
            float radius = Math.max(1f, currentCornerRadius - inset);
            // One restrained glass edge reflection replaces the old separated
            // red/cyan strokes and the distant white sheen.
            canvas.drawRoundRect(inset, inset, getWidth() - inset,
                    getHeight() - inset, radius, radius, glassEdgePaint);
        }
        canvas.restoreToCount(save);
    }

    /** Morphs the near edge from a pointed, horizontal wedge into the full panel. */
    private void applyMorphClip(Canvas canvas) {
        if (shapeProgress >= 0.999f || getWidth() <= 1 || getHeight() <= 1) return;
        float halfW = getWidth() * 0.5f * shapeProgress;
        float halfH = getHeight() * 0.5f * shapeProgress;
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        shapeClip.reset();
        if (anchorSide == SIDE_LEFT) {
            shapeClip.moveTo(0f, 0f);
            shapeClip.lineTo(0f, getHeight());
            shapeClip.lineTo(getWidth() - halfH, cy);
        } else if (anchorSide == SIDE_RIGHT) {
            shapeClip.moveTo(getWidth(), 0f);
            shapeClip.lineTo(getWidth(), getHeight());
            shapeClip.lineTo(halfH, cy);
        } else if (anchorSide == SIDE_ABOVE) {
            shapeClip.moveTo(0f, 0f);
            shapeClip.lineTo(getWidth(), 0f);
            shapeClip.lineTo(cx, getHeight() - halfW);
        } else {
            shapeClip.moveTo(0f, getHeight());
            shapeClip.lineTo(cx, halfW);
            shapeClip.lineTo(getWidth(), getHeight());
        }
        shapeClip.close();
        canvas.clipPath(shapeClip);
    }

    private void drawMicroBubbles(Canvas canvas) {
        if (particleProgress <= 0f || particleProgress >= 1f && !particleBurst) return;
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        for (int i = 0; i < 4; i++) {
            float angle = (float) (i * Math.PI * 0.5 + 0.35);
            float startRadius = Math.min(getWidth(), getHeight()) * 0.28f;
            float t = particleBurst ? particleProgress : 1f - particleProgress;
            float distance = particleBurst ? startRadius * t : startRadius * t;
            float x = cx + (float) Math.cos(angle) * distance;
            float y = cy + (float) Math.sin(angle) * distance;
            float radius = dp(4.2f + i * 0.9f) * (particleBurst
                    ? (1f - particleProgress * 0.35f) : (0.72f + particleProgress * 0.45f));
            particlePaint.setAlpha(Math.round((particleBurst ? 235f * (1f - particleProgress)
                    : 220f * (1f - particleProgress))));
            canvas.drawCircle(x, y, radius, particlePaint);
        }
    }

    private int desiredPanelWidth() {
        if (!autoWidth) return dp(configuredWidthDp);
        // Measure at the original type sizes, independent of the previous fitted layout.
        Paint textPaint = new Paint(appView.getPaint());
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                10f, getResources().getDisplayMetrics()));
        float appWidth = textPaint.measureText(appView.getText().toString());
        textPaint.set(contentView.getPaint());
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                13f, getResources().getDisplayMetrics()));
        float bodyWidth = textPaint.measureText(contentView.getText().toString());
        return clamp(Math.round(Math.max(appWidth, bodyWidth)) + horizontalPadding * 2
                + (iconView == null ? 0 : dp(44)) + dp(8), dp(180), dp(360));
    }

    /**
     * 配置浮层文字：
     * 1. 字号不会小于 minimumSp，避免过小；
     * 2. 一行放不下且允许扩展时，自动增加至两行；
     * 3. 仍放不下时使用省略号；
     * 4. 返回实际需要的行高（dp）。
     */
    private int configureText(TextView view, String text, float preferredSp,
                              float minimumSp, int availableWidth, boolean canExpand) {
        view.setText(text);
        view.setTextScaleX(1f);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferredSp);
        view.setSingleLine(true);
        view.setMaxLines(1);
        view.setEllipsize(null);

        float measured = view.getPaint().measureText(text);
        int oneLineHeight = view.getLineHeight();

        if (measured <= availableWidth || measured <= 0f) {
            return oneLineHeight;
        }

        if (canExpand) {
            // 尝试两行显示，字号不压缩
            view.setSingleLine(false);
            view.setMaxLines(2);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return view.getMeasuredHeight();
        }

        // 不允许扩展：仅按最小字号压缩，仍超长则省略
        float scaledSp = Math.max(minimumSp, preferredSp * availableWidth / measured);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp);
        measured = view.getPaint().measureText(text);
        if (measured > availableWidth) {
            view.setEllipsize(TextUtils.TruncateAt.END);
        }
        return view.getLineHeight();
    }

    private static final class Placement {
        final Rect bounds;
        final int side;

        Placement(Rect bounds, int side) {
            this.bounds = bounds;
            this.side = side;
        }
    }

    /** Choose a complete candidate; never clamp away alignment or the rim gap. */
    private Placement resolvePlacement(Rect anchor) {
        if (anchor.isEmpty()) return null;
        android.view.WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
        Rect screen = metrics.getBounds();
        WindowInsets insets = metrics.getWindowInsets();
        Insets bars = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
        int margin = dp(10);
        Rect safe = new Rect(screen.left + bars.left + margin, screen.top + bars.top + margin,
                screen.right - bars.right - margin, screen.bottom - bottom - margin);
        if (safe.isEmpty()) return null;
        int height = anchor.height();
        int gap = Math.round(height / 4f);
        int inward = anchor.centerX() < safe.centerX() ? SIDE_RIGHT : SIDE_LEFT;
        int outward = inward == SIDE_RIGHT ? SIDE_LEFT : SIDE_RIGHT;
        int[] order = preferredPosition == BubbleConfig.CONTENT_ABOVE
                ? new int[]{SIDE_ABOVE, SIDE_BELOW, inward, outward}
                : preferredPosition == BubbleConfig.CONTENT_BELOW
                ? new int[]{SIDE_BELOW, SIDE_ABOVE, inward, outward}
                : new int[]{inward, outward, SIDE_ABOVE, SIDE_BELOW};
        Rect cloud = findFluidCloudBounds();
        int desiredWidth = desiredPanelWidth();
        int minimumWidth = dp(80);
        for (int side : order) {
            int room;
            int y;
            boolean sideBySide = side == SIDE_LEFT || side == SIDE_RIGHT;
            if (sideBySide) {
                y = anchor.top;
                room = side == SIDE_RIGHT ? safe.right - anchor.right - gap
                        : anchor.left - gap - safe.left;
            } else {
                y = side == SIDE_ABOVE ? anchor.top - gap - height : anchor.bottom + gap;
                room = safe.width();
            }
            if (room < minimumWidth || y < safe.top || y + height > safe.bottom) continue;
            int width = Math.min(desiredWidth, room);
            int x = side == SIDE_RIGHT ? anchor.right + gap
                    : side == SIDE_LEFT ? anchor.left - gap - width
                    : clamp(anchor.centerX() - width / 2, safe.left, safe.right - width);
            Rect bounds = new Rect(x, y, x + width, y + height);
            if (!safe.contains(bounds) || (cloud != null && Rect.intersects(bounds, cloud))) continue;
            return new Placement(bounds, side);
        }
        return null;
    }

    /** Fluid Cloud or the IME can change without moving the bubble itself. */
    private void pollFluidCloudPosition() {
        if (!attached || dismissing) return;
        if (entranceAnimator == null) updateRestingPlacement();
        handler.postDelayed(fluidCloudPollAction, 360L);
    }

    /**
     * ColorOS fluid-cloud implementations vary by release and plugin. They
     * are all hosted as visible SystemUI views with capsule/fluid/seedling
     * names, so use their real screen bounds instead of a hard-coded notch
     * height. Hidden or tiny helper views are ignored.
     */
    private Rect findFluidCloudBounds() {
        fluidCloudBounds.setEmpty();
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = globalClass.getMethod("getInstance");
            Method getWindowViews = globalClass.getMethod("getWindowViews");
            Object global = getInstance.invoke(null);
            Object roots = global == null ? null : getWindowViews.invoke(global);
            if (!(roots instanceof List)) return null;
            for (Object root : (List<?>) roots) {
                if (!(root instanceof View)) continue;
                Rect found = findFluidCloudInView((View) root);
                if (found != null && found.width() * found.height()
                        > fluidCloudBounds.width() * fluidCloudBounds.height()) {
                    fluidCloudBounds.set(found);
                }
            }
        } catch (Throwable ignored) {
        }
        return fluidCloudBounds.isEmpty() ? null : new Rect(fluidCloudBounds);
    }

    private Rect findFluidCloudInView(View view) {
        if (view == null || view == this || view.getVisibility() != View.VISIBLE
                || view.getAlpha() < 0.05f || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        Rect own = null;
        String identity = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        try {
            int id = view.getId();
            if (id != View.NO_ID && id != 0) {
                String entry = getResources().getResourceEntryName(id);
                if (entry != null) identity += " " + entry.toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) { }
        Object tag = view.getTag();
        if (tag != null) identity += " " + String.valueOf(tag).toLowerCase(java.util.Locale.ROOT);
        CharSequence description = view.getContentDescription();
        if (description != null) identity += " " + description.toString().toLowerCase(java.util.Locale.ROOT);
        boolean capsuleName = identity.contains("fluid") || identity.contains("capsule")
                || identity.contains("seedling") || identity.contains("mini_capsule")
                || identity.contains("smartcapsule");
        if (capsuleName && view.getHeight() <= dp(180)) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            own = new Rect(location[0], location[1],
                    location[0] + view.getWidth(), location[1] + view.getHeight());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Rect child = findFluidCloudInView(group.getChildAt(i));
                if (child != null && (own == null
                        || child.width() * child.height() > own.width() * own.height())) {
                    own = child;
                }
            }
        }
        return own;
    }

    private void applyAnchoredRect(Rect target, float farProgress, float nearProgress,
                                   float secondaryProgress, boolean releasing) {
        int left;
        int right;
        int top;
        int bottom;
        if (releasing) {
            left = lerp(anchorX, target.left,
                    anchorSide == SIDE_LEFT ? farProgress
                            : anchorSide == SIDE_RIGHT ? nearProgress : secondaryProgress);
            right = lerp(anchorX, target.right,
                    anchorSide == SIDE_RIGHT ? farProgress
                            : anchorSide == SIDE_LEFT ? nearProgress : secondaryProgress);
            top = lerp(anchorY, target.top,
                    anchorSide == SIDE_ABOVE ? farProgress
                            : anchorSide == SIDE_BELOW ? nearProgress : secondaryProgress);
            bottom = lerp(anchorY, target.bottom,
                    anchorSide == SIDE_BELOW ? farProgress
                            : anchorSide == SIDE_ABOVE ? nearProgress : secondaryProgress);
        } else {
            left = lerp(target.left, anchorX,
                    anchorSide == SIDE_LEFT ? farProgress
                            : anchorSide == SIDE_RIGHT ? nearProgress : secondaryProgress);
            right = lerp(target.right, anchorX,
                    anchorSide == SIDE_RIGHT ? farProgress
                            : anchorSide == SIDE_LEFT ? nearProgress : secondaryProgress);
            top = lerp(target.top, anchorY,
                    anchorSide == SIDE_ABOVE ? farProgress
                            : anchorSide == SIDE_BELOW ? nearProgress : secondaryProgress);
            bottom = lerp(target.bottom, anchorY,
                    anchorSide == SIDE_BELOW ? farProgress
                            : anchorSide == SIDE_ABOVE ? nearProgress : secondaryProgress);
        }
        params.x = Math.min(left, right);
        params.y = Math.min(top, bottom);
        params.width = Math.max(1, Math.abs(right - left));
        params.height = Math.max(1, Math.abs(bottom - top));
    }

    private void updateLayout() {
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Throwable ignored) {
        }
    }

    private void setChildrenAlpha(float alpha) {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setAlpha(alpha);
            if (getChildAt(i) instanceof ImageView) {
                Drawable drawable = ((ImageView) getChildAt(i)).getDrawable();
                if (drawable != null) drawable.setAlpha(255);
            }
        }
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(getContext());
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setSingleLine(true);
        view.setIncludeFontPadding(false);
        return view;
    }

    private String joinSenderAndApp(String sender, String appName) {
        if (TextUtils.isEmpty(sender)) return appName == null ? "" : appName;
        if (TextUtils.isEmpty(appName)) return sender;
        return sender + " · " + appName;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        if (darkMode) {
            background.setColors(new int[]{
                    Color.rgb(57, 72, 84),
                    Color.rgb(38, 52, 64),
                    Color.rgb(27, 39, 49)});
        } else {
            background.setColors(new int[]{
                    Color.rgb(255, 255, 255),
                    Color.rgb(235, 247, 252),
                    Color.rgb(196, 222, 240)});
        }
        background.setCornerRadius(dp(PANEL_RADIUS_DP));
        background.setStroke(dp(1), darkMode
                ? Color.argb(200, 104, 133, 151)
                : Color.argb(210, 205, 225, 235));
        return background;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int lerp(int start, int end, float progress) {
        return Math.round(start + (end - start) * progress);
    }

    private float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private float easeOutCubic(float progress) {
        float inverse = 1f - progress;
        return 1f - inverse * inverse * inverse;
    }

    private float easeInOut(float progress) {
        return progress < 0.5f
                ? 4f * progress * progress * progress
                : 1f - (float) Math.pow(-2f * progress + 2f, 3d) / 2f;
    }

    private float overshoot(float progress) {
        float tension = 0.72f;
        float shifted = progress - 1f;
        return 1f + (tension + 1f) * shifted * shifted * shifted
                + tension * shifted * shifted;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
