package io.github.freeform.bubble;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/** Best-effort adapter for the optional ColorOS Seedling/Fluid Cloud host. */
final class FluidCloudAnimationBridge {
    private static final String MANAGER = "com.oplus.systemui.statusbar.seeding.SeedlingPluginManager";
    private static final String[] TOKENS = {"fluid", "capsule", "seedling", "smartcapsule", "dynamicisland"};

    private FluidCloudAnimationBridge() { }

    static boolean hasFluidCloud(View root) {
        return root != null && findCloud(root);
    }

    static Rect findFluidCloudBounds() {
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = globalClass.getMethod("getInstance");
            Method getWindowViews = globalClass.getMethod("getWindowViews");
            Object global = getInstance.invoke(null);
            Object roots = global == null ? null : getWindowViews.invoke(global);
            Rect best = null;
            if (roots instanceof java.util.List) {
                for (Object root : (java.util.List<?>) roots) {
                    if (!(root instanceof View)) continue;
                    Rect found = findCloudBounds((View) root);
                    if (found != null && (best == null || found.width() * found.height()
                            > best.width() * best.height())) best = found;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean collapse(ClassLoader loader) {
        return invokeManagerCallback(loader, "onSeamlessCollapseEnd");
    }

    static boolean expand(ClassLoader loader) {
        return invokeManagerCallback(loader, "onSeamlessExpandStart");
    }

    private static boolean invokeManagerCallback(ClassLoader loader, String methodName) {
        try {
            Class<?> managerClass = Class.forName(MANAGER, false, loader);
            for (Field field : managerClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object manager = field.get(null);
                if (manager == null || !managerClass.isInstance(manager)) continue;
                Method method = managerClass.getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(manager);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean findCloud(View view) {
        return findCloudBounds(view) != null;
    }

    private static Rect findCloudBounds(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f
                || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        String identity = (view.getClass().getName() + " " + String.valueOf(view.getTag()))
                .toLowerCase(Locale.ROOT);
        try {
            if (view.getId() != View.NO_ID) {
                identity += " " + view.getResources().getResourceEntryName(view.getId())
                        .toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) { }
        Rect best = null;
        for (String token : TOKENS) if (identity.contains(token)) {
            if (view.getHeight() <= 180 && view.getWidth() >= 24) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                best = new Rect(location[0], location[1], location[0] + view.getWidth(),
                        location[1] + view.getHeight());
            }
            break;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Rect child = findCloudBounds(group.getChildAt(i));
                if (child != null && (best == null || child.width() * child.height()
                        > best.width() * best.height())) best = child;
            }
        }
        return best;
    }
}
