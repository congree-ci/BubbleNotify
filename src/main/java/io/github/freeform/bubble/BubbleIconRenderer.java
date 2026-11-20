package io.github.freeform.bubble;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Draws notification icons through a subtle convex-bubble lens. */
final class BubbleIconRenderer {
    private static final int MESH = 8;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private static final WeakHashMap<Drawable, Map<Integer, Bitmap>> CACHE = new WeakHashMap<>();

    private BubbleIconRenderer() {
    }

    static void draw(Canvas canvas, Drawable drawable, float centerX, float centerY,
                     float radius, Path clip) {
        if (drawable == null || radius <= 0f) return;
        int size = Math.max(2, Math.round(radius * 2f));
        Bitmap bitmap = bitmapFor(drawable, size);
        float[] vertices = new float[(MESH + 1) * (MESH + 1) * 2];
        int offset = 0;
        for (int row = 0; row <= MESH; row++) {
            float ny = row * 2f / MESH - 1f;
            for (int column = 0; column <= MESH; column++) {
                float nx = column * 2f / MESH - 1f;
                float distanceSquared = Math.min(1f, nx * nx + ny * ny);
                float horizontalLens = 1f + 0.22f * (1f - distanceSquared);
                float verticalLens = 1f + 0.16f * (1f - distanceSquared);
                float centerBias = 1f - distanceSquared;
                vertices[offset++] = centerX + radius * nx * horizontalLens
                        - radius * 0.055f * centerBias;
                vertices[offset++] = centerY + radius * ny * verticalLens
                        - radius * 0.035f * centerBias;
            }
        }

        clip.reset();
        clip.addCircle(centerX, centerY, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmapMesh(bitmap, MESH, MESH, vertices, 0, null, 0, PAINT);
        canvas.restoreToCount(save);
    }

    private static synchronized Bitmap bitmapFor(Drawable drawable, int size) {
        Map<Integer, Bitmap> sizes = CACHE.get(drawable);
        if (sizes == null) {
            sizes = new HashMap<>();
            CACHE.put(drawable, sizes);
        }
        Bitmap cached = sizes.get(size);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        sizes.put(size, bitmap);
        return bitmap;
    }
}
