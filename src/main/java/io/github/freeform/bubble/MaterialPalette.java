package io.github.freeform.bubble;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/**
 * Material 3 调色板，优先从系统 Monet 动态取色（Android 12+），
 * 取不到或系统版本较低时回退到青绿色静态色板。
 * 同时根据当前昼夜模式决定表面/容器深浅。
 */
final class MaterialPalette {
    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int secondaryContainer;
    final int onSecondaryContainer;
    final int tertiaryContainer;
    final int onTertiaryContainer;
    final int surface;
    final int surfaceContainerLow;
    final int surfaceContainer;
    final int surfaceContainerHigh;
    final int surfaceContainerHighest;
    final int onSurface;
    final int onSurfaceVariant;
    final int outline;
    final int outlineVariant;
    final boolean dark;

    private MaterialPalette(int primary, int onPrimary, int primaryContainer,
                            int onPrimaryContainer, int secondaryContainer,
                            int onSecondaryContainer, int tertiaryContainer,
                            int onTertiaryContainer, int surface, int surfaceContainerLow,
                            int surfaceContainer, int surfaceContainerHigh,
                            int surfaceContainerHighest, int onSurface, int onSurfaceVariant,
                            int outline, int outlineVariant, boolean dark) {
        this.primary = primary;
        this.onPrimary = onPrimary;
        this.primaryContainer = primaryContainer;
        this.onPrimaryContainer = onPrimaryContainer;
        this.secondaryContainer = secondaryContainer;
        this.onSecondaryContainer = onSecondaryContainer;
        this.tertiaryContainer = tertiaryContainer;
        this.onTertiaryContainer = onTertiaryContainer;
        this.surface = surface;
        this.surfaceContainerLow = surfaceContainerLow;
        this.surfaceContainer = surfaceContainer;
        this.surfaceContainerHigh = surfaceContainerHigh;
        this.surfaceContainerHighest = surfaceContainerHighest;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.outline = outline;
        this.outlineVariant = outlineVariant;
        this.dark = dark;
    }

    static MaterialPalette resolve(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = mode == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return fromMonet(context, dark);
        }
        return dark ? darkPalette() : lightPalette();
    }

    /** 从系统 wallpaper 生成的 Monet 调色板取色（S+）。 */
    private static MaterialPalette fromMonet(Context context, boolean dark) {
        int[] colors;
        if (dark) {
            colors = new int[]{
                    systemColor(context, android.R.color.system_accent1_200),   // primary
                    systemColor(context, android.R.color.system_accent1_800),   // onPrimary
                    systemColor(context, android.R.color.system_accent1_700),   // primaryContainer
                    systemColor(context, android.R.color.system_accent1_100),   // onPrimaryContainer
                    systemColor(context, android.R.color.system_accent2_700),   // secondaryContainer
                    systemColor(context, android.R.color.system_accent2_100),   // onSecondaryContainer
                    systemColor(context, android.R.color.system_accent3_700),   // tertiaryContainer
                    systemColor(context, android.R.color.system_accent3_100),   // onTertiaryContainer
                    systemColor(context, android.R.color.system_neutral2_900),  // surface
                    systemColor(context, android.R.color.system_neutral2_900),  // surfaceContainerLow
                    systemColor(context, android.R.color.system_neutral2_800),  // surfaceContainer
                    systemColor(context, android.R.color.system_neutral2_700),  // surfaceContainerHigh
                    systemColor(context, android.R.color.system_neutral2_600),  // surfaceContainerHighest
                    systemColor(context, android.R.color.system_neutral1_100),  // onSurface
                    systemColor(context, android.R.color.system_neutral2_200),  // onSurfaceVariant
                    systemColor(context, android.R.color.system_neutral2_400),  // outline
                    systemColor(context, android.R.color.system_neutral2_700),  // outlineVariant
            };
        } else {
            colors = new int[]{
                    systemColor(context, android.R.color.system_accent1_600),   // primary
                    systemColor(context, android.R.color.system_accent1_0),     // onPrimary
                    systemColor(context, android.R.color.system_accent1_100),   // primaryContainer
                    systemColor(context, android.R.color.system_accent1_900),   // onPrimaryContainer
                    systemColor(context, android.R.color.system_accent2_100),   // secondaryContainer
                    systemColor(context, android.R.color.system_accent2_900),   // onSecondaryContainer
                    systemColor(context, android.R.color.system_accent3_100),   // tertiaryContainer
                    systemColor(context, android.R.color.system_accent3_900),   // onTertiaryContainer
                    systemColor(context, android.R.color.system_neutral2_0),    // surface
                    systemColor(context, android.R.color.system_neutral2_0),    // surfaceContainerLow
                    systemColor(context, android.R.color.system_neutral2_50),   // surfaceContainer
                    systemColor(context, android.R.color.system_neutral2_100),  // surfaceContainerHigh
                    systemColor(context, android.R.color.system_neutral2_200),  // surfaceContainerHighest
                    systemColor(context, android.R.color.system_neutral1_900),  // onSurface
                    systemColor(context, android.R.color.system_neutral2_700),  // onSurfaceVariant
                    systemColor(context, android.R.color.system_neutral2_500),  // outline
                    systemColor(context, android.R.color.system_neutral2_300),  // outlineVariant
            };
        }
        for (int color : colors) {
            if (color == 0) return dark ? darkPalette() : lightPalette();
        }
        return new MaterialPalette(
                colors[0], colors[1], colors[2], colors[3], colors[4], colors[5],
                colors[6], colors[7], colors[8], colors[9], colors[10], colors[11],
                colors[12], colors[13], colors[14], colors[15], colors[16], dark);
    }

    private static int systemColor(Context context, int id) {
        try {
            return context.getColor(id);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static MaterialPalette lightPalette() {
        return new MaterialPalette(
                0xFF00696B, 0xFFFFFFFF, 0xFF9CF1F0, 0xFF002021,
                0xFFCCE8E7, 0xFF051F20, 0xFFFFD8F0, 0xFF31162B,
                0xFFF5FAF9, 0xFFF5FAF9, 0xFFECF2F1, 0xFFE1E7E6,
                0xFFD6DCDC, 0xFF171D1D, 0xFF3F4948, 0xFF6F7979,
                0xFFBEC9C8, false);
    }

    private static MaterialPalette darkPalette() {
        return new MaterialPalette(
                0xFF80D4D3, 0xFF003737, 0xFF004F4F, 0xFF9CF1F0,
                0xFF334B4A, 0xFFCCE8E7, 0xFF5E3D53, 0xFFFAD7EC,
                0xFF0E1414, 0xFF0E1414, 0xFF161C1C, 0xFF202625,
                0xFF2A2F2E, 0xFFDDE4E3, 0xFFBEC9C8, 0xFF889392,
                0xFF3F4948, true);
    }
}
