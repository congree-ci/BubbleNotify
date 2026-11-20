package io.github.freeform.bubble;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * Material 3 Expressive 开关。
 *
 * 比普通 MD3 开关更饱满：20/28 dp 大滑块、12% 按压形变、Overshoot 弹性位移动画、
 * 更醒目的对勾图标。所有数值计算均带 NaN/越界保护。
 */
public final class MaterialSwitch extends View {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(MaterialSwitch view, boolean checked);
    }

    private static final float TRACK_W_DP = 52f;
    private static final float TRACK_H_DP = 32f;
    private static final float THUMB_UNCHECKED_DP = 20f;
    private static final float THUMB_CHECKED_DP = 28f;
    private static final float PADDING_DP = 2f;
    private static final float ICON_STROKE_DP = 2.5f;
    private static final long ANIM_DURATION_MS = 320L;
    private static final long PRESS_DURATION_MS = 120L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final RectF trackRect = new RectF();

    private MaterialPalette palette;
    private OnCheckedChangeListener listener;
    private ValueAnimator positionAnimator;
    private ValueAnimator pressAnimator;
    private boolean checked;
    private boolean pressed;
    private float position; // 0 = off, 1 = on
    private float pressFactor; // 0 = normal, 1 = pressed

    public MaterialSwitch(Context context) {
        super(context);
        palette = MaterialPalette.resolve(context);
        trackStrokePaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    public void setPalette(MaterialPalette palette) {
        if (palette == null) return;
        this.palette = palette;
        invalidate();
    }

    public void setOnCheckedChanged(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }

    public void setChecked(boolean checked, boolean animate) {
        if (this.checked == checked) return;
        this.checked = checked;
        float target = checked ? 1f : 0f;
        cancelPositionAnimation();
        if (animate) {
            positionAnimator = ValueAnimator.ofFloat(clamp01(position), target);
            positionAnimator.setDuration(ANIM_DURATION_MS);
            positionAnimator.setInterpolator(new OvershootInterpolator(0.8f));
            positionAnimator.addUpdateListener(animation -> {
                position = clamp01((float) animation.getAnimatedValue());
                invalidate();
            });
            positionAnimator.start();
        } else {
            position = target;
            invalidate();
        }
    }

    public void toggle() {
        setChecked(!checked, true);
        performHaptic();
        if (listener != null) {
            try {
                listener.onCheckedChanged(this, checked);
            } catch (Throwable t) {
                Log.e("MaterialSwitch", "listener crashed", t);
            }
        }
    }

    private void performHaptic() {
        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(8L);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.38f);
        if (!enabled) {
            pressed = false;
            animatePress(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPositionAnimation();
        cancelPressAnimation();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = dp(TRACK_W_DP + PADDING_DP * 2f);
        int height = dp(TRACK_H_DP + PADDING_DP * 2f);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                animatePress(true);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean inside = isInside(event.getX(), event.getY());
                if (inside != pressed) {
                    pressed = inside;
                    animatePress(inside);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (pressed) toggle();
                pressed = false;
                animatePress(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                pressed = false;
                animatePress(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        toggle();
        return true;
    }

    private boolean isInside(float x, float y) {
        return x >= 0 && x <= getWidth() && y >= 0 && y <= getHeight();
    }

    private void animatePress(boolean pressed) {
        cancelPressAnimation();
        float target = pressed ? 1f : 0f;
        if (pressFactor == target) return;
        pressAnimator = ValueAnimator.ofFloat(pressFactor, target);
        pressAnimator.setDuration(PRESS_DURATION_MS);
        pressAnimator.setInterpolator(new OvershootInterpolator(0.4f));
        pressAnimator.addUpdateListener(animation -> {
            pressFactor = clamp01((float) animation.getAnimatedValue());
            invalidate();
        });
        pressAnimator.start();
    }

    private void cancelPositionAnimation() {
        if (positionAnimator != null) {
            positionAnimator.cancel();
            positionAnimator = null;
        }
    }

    private void cancelPressAnimation() {
        if (pressAnimator != null) {
            pressAnimator.cancel();
            pressAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;

        float inset = dp(PADDING_DP);
        float trackHeight = dp(TRACK_H_DP);
        float trackTop = (getHeight() - trackHeight) / 2f;
        float trackBottom = trackTop + trackHeight;
        float trackLeft = inset;
        float trackRight = getWidth() - inset;
        float trackRadius = trackHeight / 2f;

        // Track fill: surfaceContainerHighest -> primary
        int trackColor = lerpColor(palette.surfaceContainerHighest, palette.primary, position);
        trackPaint.setColor(trackColor);
        trackRect.set(trackLeft, trackTop, trackRight, trackBottom);
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint);

        // Track outline fades out as switch turns on
        if (position < 0.995f) {
            trackStrokePaint.setColor(palette.outline);
            trackStrokePaint.setAlpha((int) ((1f - position) * 255));
            trackStrokePaint.setStrokeWidth(dp(2f));
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackStrokePaint);
        }

        // Expressive thumb: larger, with overshoot-style press scale
        float baseDiameter = lerp(dp(THUMB_UNCHECKED_DP), dp(THUMB_CHECKED_DP), position);
        float scale = 1f + pressFactor * 0.12f;
        float thumbDiameter = baseDiameter * scale;
        float halfThumb = thumbDiameter / 2f;
        float minThumbCx = trackLeft + dp(3f) + halfThumb;
        float maxThumbCx = trackRight - dp(3f) - halfThumb;
        float thumbCx = lerp(minThumbCx, maxThumbCx, position);
        float thumbCy = getHeight() / 2f;

        int thumbColor = lerpColor(palette.outline, palette.onPrimary, position);
        thumbPaint.setColor(thumbColor);
        canvas.drawCircle(thumbCx, thumbCy, halfThumb, thumbPaint);

        // Bold check icon
        if (position > 0.02f) {
            float iconAlpha = Math.min(1f, position * 2f);
            iconPaint.setColor(palette.primary);
            iconPaint.setAlpha((int) (iconAlpha * 255));
            iconPaint.setStrokeWidth(dp(ICON_STROKE_DP));

            checkPath.reset();
            checkPath.moveTo(thumbCx - dp(6f), thumbCy + dp(0.5f));
            checkPath.lineTo(thumbCx - dp(2.2f), thumbCy + dp(5f));
            checkPath.lineTo(thumbCx + dp(6.2f), thumbCy - dp(5f));
            canvas.drawPath(checkPath, iconPaint);
        }
    }

    private static float lerp(float a, float b, float t) {
        t = clamp01(t);
        return a + (b - a) * t;
    }

    private static int lerpColor(int a, int b, float t) {
        t = clamp01(t);
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int alpha = (int) lerp(Color.alpha(a), Color.alpha(b), t);
        int red = (int) lerp(Color.red(a), Color.red(b), t);
        int green = (int) lerp(Color.green(a), Color.green(b), t);
        int blue = (int) lerp(Color.blue(a), Color.blue(b), t);
        return Color.argb(alpha, red, green, blue);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
