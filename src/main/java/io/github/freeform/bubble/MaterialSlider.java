package io.github.freeform.bubble;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * Material 3 Expressive 滑条。
 *
 * 采用竖向胶囊手柄：常态 6×44 dp，按压 10×48 dp；
 * 16 dp 粗活动轨道 + 4 dp 细非活动轨道，按压与位移动画带 Overshoot 弹性。
 * 触摸计算有完整除零 / NaN 保护。
 */
public final class MaterialSlider extends View {
    public interface OnValueChangeListener {
        void onValueChange(MaterialSlider slider, int value);
    }

    private static final float ACTIVE_TRACK_H_DP = 18f;
    private static final float INACTIVE_TRACK_H_DP = 5f;
    private static final float THUMB_W_DP = 4f;
    private static final float THUMB_W_PRESSED_DP = 6f;
    private static final float THUMB_H_DP = 46f;
    private static final float THUMB_H_PRESSED_DP = 50f;
    private static final float THUMB_RADIUS_DP = 4f;
    private static final float TRACK_PADDING_DP = 4f;
    private static final float THUMB_GAP_DP = 10f;
    private static final long PRESS_DURATION_MS = 160L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private MaterialPalette palette;
    private OnValueChangeListener listener;
    private ValueAnimator pressAnimator;
    private int min;
    private int max = 100;
    private int value;
    private boolean dragging;
    private float pressFactor;

    public MaterialSlider(Context context) {
        super(context);
        palette = MaterialPalette.resolve(context);
    }

    public void setPalette(MaterialPalette palette) {
        if (palette == null) return;
        this.palette = palette;
        invalidate();
    }

    public void setOnValueChange(OnValueChangeListener listener) {
        this.listener = listener;
    }

    public void setRange(int min, int max) {
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        this.min = min;
        this.max = max;
        setValue(value);
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = clampInt(value, min, max);
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.38f);
        if (!enabled) {
            dragging = false;
            animatePress(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPressAnimation();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = dp(240f);
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            width = MeasureSpec.getSize(widthSpec);
        }
        int height = dp(THUMB_H_PRESSED_DP + 16f);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                animatePress(true);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                updateValueFromTouch(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) updateValueFromTouch(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                animatePress(false);
                performHaptic();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animatePress(false);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateValueFromTouch(float x) {
        if (getWidth() <= 0) return;
        float trackLeft = dp(TRACK_PADDING_DP);
        float trackRight = getWidth() - dp(TRACK_PADDING_DP);
        float usable = (trackRight - trackLeft) - dp(THUMB_GAP_DP) * 2f;
        if (usable <= 0f || Float.isNaN(usable)) return;

        float fraction = (x - trackLeft - dp(THUMB_GAP_DP)) / usable;
        if (Float.isNaN(fraction)) return;
        fraction = clamp01(fraction);

        int newValue;
        if (max == min) {
            newValue = min;
        } else {
            newValue = Math.round(min + fraction * (max - min));
        }
        newValue = clampInt(newValue, min, max);

        if (newValue != value) {
            value = newValue;
            performHaptic();
            invalidate();
            if (listener != null) {
                try {
                    listener.onValueChange(this, value);
                } catch (Throwable t) {
                    Log.e("MaterialSlider", "listener crashed", t);
                }
            }
        }
    }

    private void animatePress(boolean pressed) {
        cancelPressAnimation();
        float target = pressed ? 1f : 0f;
        if (pressFactor == target) return;
        pressAnimator = ValueAnimator.ofFloat(pressFactor, target);
        pressAnimator.setDuration(PRESS_DURATION_MS);
        pressAnimator.setInterpolator(new OvershootInterpolator(0.5f));
        pressAnimator.addUpdateListener(animation -> {
            pressFactor = clamp01((float) animation.getAnimatedValue());
            invalidate();
        });
        pressAnimator.start();
    }

    private void cancelPressAnimation() {
        if (pressAnimator != null) {
            pressAnimator.cancel();
            pressAnimator = null;
        }
    }

    private void performHaptic() {
        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(6L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(6L);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;

        float cy = getHeight() / 2f;
        float trackLeft = dp(TRACK_PADDING_DP);
        float trackRight = getWidth() - dp(TRACK_PADDING_DP);
        float fraction = max == min ? 0f : (value - min) / (float) (max - min);
        fraction = clamp01(fraction);
        float usable = (trackRight - trackLeft) - dp(THUMB_GAP_DP) * 2f;
        float thumbCx = trackLeft + dp(THUMB_GAP_DP) + fraction * usable;

        // 轨道以纵轴（竖滑块）为分界：左段深色、右段浅色，同色系
        // （primary / primaryContainer），两段与纵轴之间各留开 2 倍纵轴宽度的空隙。
        float baseThumbW = dp(THUMB_W_DP);
        float gap = baseThumbW * 2f;
        float leftEnd = Math.max(trackLeft, thumbCx - gap);
        float rightStart = Math.min(trackRight, thumbCx + gap);
        float trackTop = cy - dp(ACTIVE_TRACK_H_DP) / 2f;
        float trackBottom = cy + dp(ACTIVE_TRACK_H_DP) / 2f;
        float bigR = dp(ACTIVE_TRACK_H_DP) / 2f;
        float smallR = dp(2f);
        android.graphics.Path trackPath = new android.graphics.Path();
        paint.setStyle(Paint.Style.FILL);
        if (leftEnd > trackLeft) {
            // 左段：深色，左端大圆角、靠纵轴侧小圆角
            paint.setColor(palette.primary);
            trackPath.reset();
            trackPath.addRoundRect(trackLeft, trackTop, leftEnd, trackBottom,
                    new float[]{bigR, bigR, smallR, smallR, smallR, smallR, bigR, bigR},
                    android.graphics.Path.Direction.CW);
            canvas.drawPath(trackPath, paint);
        }
        if (rightStart < trackRight) {
            // 右段：浅色，靠纵轴侧小圆角、右端大圆角
            paint.setColor(palette.primaryContainer);
            trackPath.reset();
            trackPath.addRoundRect(rightStart, trackTop, trackRight, trackBottom,
                    new float[]{smallR, smallR, bigR, bigR, bigR, bigR, smallR, smallR},
                    android.graphics.Path.Direction.CW);
            canvas.drawPath(trackPath, paint);
        }

        // Expressive vertical capsule thumb
        float thumbW = lerp(dp(THUMB_W_DP), dp(THUMB_W_PRESSED_DP), pressFactor);
        float thumbH = lerp(dp(THUMB_H_DP), dp(THUMB_H_PRESSED_DP), pressFactor);
        float thumbRadius = dp(THUMB_RADIUS_DP);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(palette.primary);
        rect.set(thumbCx - thumbW / 2f, cy - thumbH / 2f,
                thumbCx + thumbW / 2f, cy + thumbH / 2f);
        canvas.drawRoundRect(rect, thumbRadius, thumbRadius, paint);

        // Soft highlight on thumb
        paint.setColor(palette.onPrimary);
        paint.setAlpha((int) (0.10f * 255));
        rect.set(thumbCx - thumbW / 4f, cy - thumbH / 4f,
                thumbCx + thumbW / 4f, cy + thumbH / 4f);
        canvas.drawRoundRect(rect, thumbRadius, thumbRadius, paint);
        paint.setAlpha(255);
    }

    private static float lerp(float a, float b, float t) {
        t = clamp01(t);
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clampInt(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
