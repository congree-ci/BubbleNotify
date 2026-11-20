package io.github.freeform.bubble;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Translucent full-screen editor that stores independent portrait/landscape positions. */
@SuppressLint("SetTextI18n")
public final class BubblePlacementActivity extends Activity {
    private static final int BUBBLE_DP = 92;

    private final RectF safeBounds = new RectF();
    private BubbleConfig config;
    private MaterialPalette palette;
    private FrameLayout root;
    private PlacementBubble bubble;
    private TextView instructions;
    private Button done;
    private TextView coordinates;
    private boolean landscape;
    private float downRawX;
    private float downRawY;
    private float downViewX;
    private float downViewY;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new BubbleConfig(this);
        palette = MaterialPalette.resolve(this);
        landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        configureWindow();
        setContentView(buildEditor());
        applyBackgroundBlur();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    }

    /**
     * Ask the platform/ColorOS compositor for a strong Gaussian blur behind the
     * translucent placement editor. Reflection keeps the module compatible with
     * older Android releases where these window APIs do not exist.
     */
    private void applyBackgroundBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        final Window window = getWindow();
        window.getDecorView().post(() -> {
            final int radius = Math.max(96, dp(48));
            try {
                Window.class.getMethod("setBackgroundBlurEnabled", boolean.class)
                        .invoke(window, true);
                Window.class.getMethod("setBackgroundBlurRadius", int.class)
                        .invoke(window, radius);
            } catch (Throwable ignored) {
                // Fall through to the public LayoutParams path below.
            }
            try {
                Class<?> lpClass = WindowManager.LayoutParams.class;
                int flag = lpClass.getField("FLAG_BLUR_BEHIND").getInt(null);
                WindowManager.LayoutParams lp = window.getAttributes();
                lpClass.getField("blurBehindRadius").setInt(lp, radius);
                window.setAttributes(lp);
                window.addFlags(flag);
            } catch (Throwable ignored) {
                // Devices without compositor blur simply keep the transparent UI.
            }
        });
    }

    private View buildEditor() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                            | WindowInsets.Type.systemGestures());
            view.post(() -> updateSafeBounds(bars));
            return insets;
        });

        bubble = new PlacementBubble();
        int bubbleSize = Math.round(dp(BUBBLE_DP)
                * config.getBubbleSizePercent() / 100f);
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
                bubbleSize, bubbleSize);
        root.addView(bubble, bubbleParams);
        bubble.setOnTouchListener(this::dragBubble);

        instructions = label(
                landscape ? "横屏位置 · 拖动气泡" : "竖屏位置 · 拖动气泡", 14, true);
        instructions.setTextColor(Color.WHITE);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(dp(16), dp(9), dp(16), dp(9));
        instructions.setBackground(round(Color.argb(220, 35, 41, 49), 8));
        FrameLayout.LayoutParams instructionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        instructionParams.topMargin = dp(18);
        root.addView(instructions, instructionParams);

        done = new Button(this);
        done.setText("完成");
        done.setAllCaps(false);
        done.setTextColor(palette.onPrimary);
        done.setTextSize(14);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setMinWidth(0);
        done.setMinHeight(0);
        done.setStateListAnimator(null);
        done.setPadding(dp(24), dp(12), dp(24), dp(12));
        done.setBackground(round(palette.primary, 24));
        done.setElevation(dp(4));
        done.setOnClickListener(view -> {
            savePosition();
            finish();
        });
        FrameLayout.LayoutParams doneParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        doneParams.topMargin = dp(14);
        doneParams.rightMargin = dp(14);
        root.addView(done, doneParams);

        coordinates = label("", 12, false);
        coordinates.setTextColor(Color.WHITE);
        coordinates.setGravity(Gravity.CENTER);
        coordinates.setPadding(dp(13), dp(7), dp(13), dp(7));
        coordinates.setBackground(round(Color.argb(205, 35, 41, 49), 8));
        FrameLayout.LayoutParams coordinateParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        coordinateParams.bottomMargin = dp(18);
        root.addView(coordinates, coordinateParams);
        // Some ColorOS builds dispatch the first insets before the translucent
        // root has been measured. Refresh once more after layout so controls
        // cannot remain under the status/navigation bars.
        root.post(() -> {
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.systemGestures());
                updateSafeBounds(bars);
            }
        });
        return root;
    }

    private void updateSafeBounds(Insets bars) {
        float margin = dp(8);
        // The editor is edge-to-edge so the preview can use the real display
        // bounds, but its helper controls must stay below the status bar and
        // above the navigation/gesture area.
        if (instructions != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) instructions.getLayoutParams();
            lp.topMargin = bars.top + dp(12);
            instructions.setLayoutParams(lp);
        }
        if (done != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) done.getLayoutParams();
            lp.topMargin = bars.top + dp(10);
            lp.rightMargin = bars.right + dp(12);
            done.setLayoutParams(lp);
        }
        if (coordinates != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) coordinates.getLayoutParams();
            lp.bottomMargin = bars.bottom + dp(14);
            coordinates.setLayoutParams(lp);
        }
        safeBounds.set(
                bars.left + margin,
                bars.top + margin,
                root.getWidth() - bars.right - bubble.getWidth() - margin,
                root.getHeight() - bars.bottom - bubble.getHeight() - margin);
        float rangeX = Math.max(0f, safeBounds.right - safeBounds.left);
        float rangeY = Math.max(0f, safeBounds.bottom - safeBounds.top);
        bubble.setX(safeBounds.left + rangeX * config.getPositionX(landscape) / 100f);
        bubble.setY(safeBounds.top + rangeY * config.getPositionY(landscape) / 100f);
        updateCoordinateLabel();
    }

    private boolean dragBubble(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downViewX = view.getX();
                downViewY = view.getY();
                view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120L).start();
                return true;
            case MotionEvent.ACTION_MOVE:
                float x = downViewX + event.getRawX() - downRawX;
                float y = downViewY + event.getRawY() - downRawY;
                view.setX(clamp(x, safeBounds.left, safeBounds.right));
                view.setY(clamp(y, safeBounds.top, safeBounds.bottom));
                updateCoordinateLabel();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                view.animate().scaleX(1f).scaleY(1f).setDuration(160L).start();
                savePosition();
                view.performClick();
                return true;
            default:
                return false;
        }
    }

    private void savePosition() {
        float rangeX = safeBounds.right - safeBounds.left;
        float rangeY = safeBounds.bottom - safeBounds.top;
        if (rangeX <= 0f || rangeY <= 0f) return;
        int x = Math.round((bubble.getX() - safeBounds.left) / rangeX * 100f);
        int y = Math.round((bubble.getY() - safeBounds.top) / rangeY * 100f);
        config.setPosition(landscape, x, y);
        config.setDockEdge(landscape, 0);
        getContentResolver().notifyChange(BubbleConfigProvider.URI, null);
    }

    private void updateCoordinateLabel() {
        float rangeX = safeBounds.right - safeBounds.left;
        float rangeY = safeBounds.bottom - safeBounds.top;
        if (rangeX <= 0f || rangeY <= 0f) return;
        int x = Math.round((bubble.getX() - safeBounds.left) / rangeX * 100f);
        int y = Math.round((bubble.getY() - safeBounds.top) / rangeY * 100f);
        coordinates.setText("位置  " + x + "% , " + y + "%");
    }

    private TextView label(String value, float size, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTypeface(android.graphics.Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif",
                android.graphics.Typeface.NORMAL));
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("ViewConstructor")
    private final class PlacementBubble extends View {
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refraction = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dispersion = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF highlightArc = new RectF();
        private final Drawable icon = getDrawable(R.drawable.ic_bubble);
        private RadialGradient shadowGradient;
        private RadialGradient coreGradient;
        private SweepGradient edgeGradient;

        PlacementBubble() {
            super(BubblePlacementActivity.this);
            setClickable(true);
            setContentDescription("拖动气泡位置");
            setLayerType(LAYER_TYPE_HARDWARE, null);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(1.5f));
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeWidth(dp(1.2f));
            highlight.setStrokeCap(Paint.Cap.ROUND);
            dispersion.setStyle(Paint.Style.STROKE);
            dispersion.setStrokeWidth(dp(1.1f));
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            // Shaders live in the fixed 92dp design space; onDraw scales the canvas.
            float cx = dp(BUBBLE_DP / 2f);
            float cy = dp(BUBBLE_DP / 2f);
            float radius = dp(29);
            shadowGradient = new RadialGradient(cx, cy + dp(3), radius + dp(7),
                    Color.argb(76, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP);
            coreGradient = new RadialGradient(cx - dp(7), cy - dp(8), radius * 1.35f,
                    Color.argb(122, 255, 255, 255), Color.argb(68, 226, 234, 242),
                    Shader.TileMode.CLAMP);
            edgeGradient = new SweepGradient(cx, cy,
                    new int[]{Color.argb(135, 255, 255, 255),
                            Color.argb(92, 170, 220, 242),
                            Color.argb(78, 225, 192, 220),
                            Color.argb(135, 255, 255, 255)}, null);
            highlightArc.set(cx - radius + dp(4), cy - radius + dp(4),
                    cx + radius - dp(4), cy + radius - dp(4));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float scale = config.getBubbleSizePercent() / 100f;
            canvas.save();
            canvas.scale(scale, scale);
            float cx = dp(BUBBLE_DP / 2f);
            float cy = dp(BUBBLE_DP / 2f);
            float radius = dp(29);
            shadow.setShader(shadowGradient);
            canvas.drawCircle(cx, cy + dp(3), radius + dp(7), shadow);
            core.setShader(coreGradient);
            canvas.drawCircle(cx, cy, radius, core);
            ring.setColor(Color.argb(185, 224, 244, 255));
            canvas.drawCircle(cx, cy, radius - dp(1), ring);

            int save = canvas.save();
            clip.reset();
            clip.addCircle(cx, cy, radius - dp(2), Path.Direction.CW);
            canvas.clipPath(clip);
            refraction.setStyle(Paint.Style.FILL);
            refraction.setShader(new RadialGradient(cx - dp(9), cy - dp(11), radius * 1.28f,
                    Color.argb(66, 255, 255, 255), Color.argb(18, 176, 216, 238),
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, refraction);
            refraction.setShader(null);
            dispersion.setShader(edgeGradient);
            canvas.drawCircle(cx, cy, radius - dp(0.7f), dispersion);
            dispersion.setShader(null);
            canvas.restoreToCount(save);

            BubbleIconRenderer.draw(canvas, icon, cx, cy,
                    dp(20) * config.getIconSizePercent() / 100f, clip);
            highlight.setColor(Color.argb(145, 255, 255, 255));
            canvas.drawArc(highlightArc, 205f, 74f, false, highlight);
            canvas.restore();
        }
    }
}
