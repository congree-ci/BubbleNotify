package io.github.freeform.bubble;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Compact 24dp Material-style glyphs used by immediate suggestions. */
final class MaterialSuggestionIcon extends Drawable {
    static final int FLASHLIGHT = 1;
    static final int MUSIC = 2;
    static final int INSTALL = 3;
    static final int LINK = 4;
    static final int PACKAGE = 5;
    static final int LOCATION = 6;
    static final int KEEP_AWAKE = 7;
    static final int PHONE = 8;

    private final int kind;
    private final int color;
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int alpha = 255;

    MaterialSuggestionIcon(int kind) {
        this.kind = kind;
        color = colorFor(kind);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.9f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        float scale = Math.min(bounds.width(), bounds.height()) / 24f;
        float left = bounds.centerX() - 12f * scale;
        float top = bounds.centerY() - 12f * scale;
        int save = canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);

        backgroundPaint.setColor(withAlpha(color, Math.round(alpha * 0.16f)));
        strokePaint.setColor(withAlpha(color, alpha));
        fillPaint.setColor(withAlpha(color, alpha));
        canvas.drawCircle(12f, 12f, 11.25f, backgroundPaint);

        switch (kind) {
            case FLASHLIGHT:
                drawFlashlight(canvas);
                break;
            case MUSIC:
                drawMusic(canvas);
                break;
            case INSTALL:
                drawInstall(canvas);
                break;
            case PACKAGE:
                drawPackage(canvas);
                break;
            case LOCATION:
                drawLocation(canvas);
                break;
            case KEEP_AWAKE:
                drawKeepAwake(canvas);
                break;
            case PHONE:
                drawPhone(canvas);
                break;
            case LINK:
            default:
                drawLink(canvas);
                break;
        }
        canvas.restoreToCount(save);
    }

    private void drawFlashlight(Canvas canvas) {
        path.reset();
        path.moveTo(8.2f, 5.4f);
        path.lineTo(15.8f, 5.4f);
        path.lineTo(14.6f, 9.1f);
        path.lineTo(14.6f, 18.3f);
        path.quadTo(14.6f, 19.2f, 13.7f, 19.2f);
        path.lineTo(10.3f, 19.2f);
        path.quadTo(9.4f, 19.2f, 9.4f, 18.3f);
        path.lineTo(9.4f, 9.1f);
        path.close();
        canvas.drawPath(path, strokePaint);
        canvas.drawLine(9.4f, 9.1f, 14.6f, 9.1f, strokePaint);
    }

    private void drawMusic(Canvas canvas) {
        canvas.drawLine(10f, 7f, 17f, 5.5f, strokePaint);
        canvas.drawLine(10f, 7f, 10f, 16.1f, strokePaint);
        canvas.drawLine(17f, 5.5f, 17f, 14.4f, strokePaint);
        canvas.drawOval(new RectF(6.3f, 15f, 10.1f, 18.2f), fillPaint);
        canvas.drawOval(new RectF(13.3f, 13.3f, 17.1f, 16.5f), fillPaint);
    }

    private void drawInstall(Canvas canvas) {
        canvas.drawRoundRect(new RectF(5.3f, 5.3f, 18.7f, 18.7f), 2.2f, 2.2f, strokePaint);
        canvas.drawLine(12f, 7.7f, 12f, 14f, strokePaint);
        canvas.drawLine(9.6f, 11.7f, 12f, 14.1f, strokePaint);
        canvas.drawLine(14.4f, 11.7f, 12f, 14.1f, strokePaint);
        canvas.drawLine(9.1f, 16.3f, 14.9f, 16.3f, strokePaint);
    }

    private void drawLink(Canvas canvas) {
        path.reset();
        path.moveTo(10.4f, 14.9f);
        path.lineTo(8.9f, 16.4f);
        path.cubicTo(7.5f, 17.8f, 5.3f, 17.8f, 3.9f, 16.4f);
        path.cubicTo(2.5f, 15f, 2.5f, 12.8f, 3.9f, 11.4f);
        path.lineTo(6.3f, 9f);
        path.cubicTo(7.7f, 7.6f, 9.9f, 7.6f, 11.3f, 9f);
        canvas.drawPath(path, strokePaint);
        path.reset();
        path.moveTo(13.6f, 9.1f);
        path.lineTo(15.1f, 7.6f);
        path.cubicTo(16.5f, 6.2f, 18.7f, 6.2f, 20.1f, 7.6f);
        path.cubicTo(21.5f, 9f, 21.5f, 11.2f, 20.1f, 12.6f);
        path.lineTo(17.7f, 15f);
        path.cubicTo(16.3f, 16.4f, 14.1f, 16.4f, 12.7f, 15f);
        canvas.drawPath(path, strokePaint);
        canvas.drawLine(8.7f, 12f, 15.3f, 12f, strokePaint);
    }

    private void drawPackage(Canvas canvas) {
        path.reset();
        path.moveTo(5.2f, 8.1f);
        path.lineTo(12f, 4.6f);
        path.lineTo(18.8f, 8.1f);
        path.lineTo(18.8f, 16f);
        path.lineTo(12f, 19.4f);
        path.lineTo(5.2f, 16f);
        path.close();
        canvas.drawPath(path, strokePaint);
        canvas.drawLine(5.5f, 8.2f, 12f, 11.5f, strokePaint);
        canvas.drawLine(18.5f, 8.2f, 12f, 11.5f, strokePaint);
        canvas.drawLine(12f, 11.5f, 12f, 19f, strokePaint);
        canvas.drawLine(8.5f, 6.4f, 15.3f, 9.9f, strokePaint);
    }

    private void drawLocation(Canvas canvas) {
        path.reset();
        path.moveTo(12f, 20f);
        path.cubicTo(10.6f, 18.2f, 6.6f, 14.2f, 6.6f, 10.5f);
        path.cubicTo(6.6f, 7.5f, 9f, 5.2f, 12f, 5.2f);
        path.cubicTo(15f, 5.2f, 17.4f, 7.5f, 17.4f, 10.5f);
        path.cubicTo(17.4f, 14.2f, 13.4f, 18.2f, 12f, 20f);
        path.close();
        canvas.drawPath(path, strokePaint);
        canvas.drawCircle(12f, 10.5f, 2f, strokePaint);
    }

    private void drawKeepAwake(Canvas canvas) {
        canvas.drawCircle(12f, 12f, 3.8f, strokePaint);
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45d);
            float x1 = 12f + (float) Math.cos(angle) * 6.1f;
            float y1 = 12f + (float) Math.sin(angle) * 6.1f;
            float x2 = 12f + (float) Math.cos(angle) * 8.1f;
            float y2 = 12f + (float) Math.sin(angle) * 8.1f;
            canvas.drawLine(x1, y1, x2, y2, strokePaint);
        }
    }

    /** Material "call" handset, filled. */
    private void drawPhone(Canvas canvas) {
        path.reset();
        path.moveTo(6.62f, 10.79f);
        path.cubicTo(8.06f, 13.62f, 10.38f, 15.93f, 13.21f, 17.38f);
        path.lineTo(15.41f, 15.18f);
        path.cubicTo(15.68f, 14.91f, 16.08f, 14.82f, 16.43f, 14.94f);
        path.cubicTo(17.55f, 15.31f, 18.76f, 15.51f, 20f, 15.51f);
        path.cubicTo(20.55f, 15.51f, 21f, 15.96f, 21f, 16.51f);
        path.lineTo(21f, 20f);
        path.cubicTo(21f, 20.55f, 20.55f, 21f, 20f, 21f);
        path.cubicTo(10.61f, 21f, 3f, 13.39f, 3f, 4f);
        path.cubicTo(3f, 3.45f, 3.45f, 3f, 4f, 3f);
        path.lineTo(7.5f, 3f);
        path.cubicTo(8.05f, 3f, 8.5f, 3.45f, 8.5f, 4f);
        path.cubicTo(8.5f, 5.25f, 8.7f, 6.45f, 9.07f, 7.57f);
        path.cubicTo(9.18f, 7.92f, 9.1f, 8.31f, 8.82f, 8.59f);
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        strokePaint.setColorFilter(colorFilter);
        fillPaint.setColorFilter(colorFilter);
        backgroundPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static int colorFor(int kind) {
        switch (kind) {
            case FLASHLIGHT:
                return Color.rgb(245, 166, 35);
            case MUSIC:
                return Color.rgb(0, 150, 136);
            case INSTALL:
                return Color.rgb(30, 136, 229);
            case PACKAGE:
                return Color.rgb(239, 108, 0);
            case LOCATION:
                return Color.rgb(229, 57, 53);
            case KEEP_AWAKE:
                return Color.rgb(255, 179, 0);
            case PHONE:
                return Color.rgb(67, 160, 71);
            case LINK:
            default:
                return Color.rgb(25, 118, 210);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
