package io.github.freeform.bubble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 融入状态栏的滚动通知组件（类似 lyricon 的状态栏歌词）。
 * 不创建独立浮层，而是直接以子视图插入状态栏通知区，并替换时钟位置：
 * 使用状态栏时钟本身的字体与颜色，随时钟随状态栏着色而联动变化。
 */
final class StatusScrollOverlay extends LinearLayout {
    private static final PathInterpolator SCROLL_EXIT_INTERPOLATOR =
            new PathInterpolator(0.40f, 0.00f, 1.00f, 1.00f);
    private static final PathInterpolator SCROLL_ENTER_INTERPOLATOR =
            new PathInterpolator(0.05f, 0.70f, 0.10f, 1.00f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView iconView;
    private final FadingTickerView ticker;
    private boolean attached;
    private boolean hasText;
    private ViewGroup host;
    private boolean overlayAttached;
    private TextView clockView;
    private int clockOriginalVisibility = -1;
    private boolean clockSuppressed;
    private Drawable pendingIcon;
    private String pendingText;
    private boolean hasPending;
    private final List<View> hiddenPeers = new ArrayList<>();
    private final List<Integer> hiddenPeerVisibility = new ArrayList<>();
    private ViewGroup notificationIconsContainer;
    private final Runnable removeAction = this::removeImmediately;
    private final Runnable pageAdvanceAction = this::advancePage;
    private final Runnable overlayKeepAliveAction = this::keepOverlayAlive;
    private final List<String> textPages = new ArrayList<>();
    private int pageIndex;
    private int contentWidth;
    private String fullText = "";
    private long shownAt;
    private int displayRounds = 1;
    private int completedRounds;
    private Runnable finishedAction;
    private final Rect fluidCloudBounds = new Rect();

    StatusScrollOverlay(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(5);
        setPadding(pad, 0, pad, 0);
        setClipToPadding(true);
        setClipChildren(false);
        setBackgroundColor(Color.TRANSPARENT);
        setVisibility(View.VISIBLE);
        setAlpha(0f);

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addView(iconView, new LayoutParams(dp(16), dp(16)));

        ticker = new FadingTickerView(context);
        ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ticker.setSingleLine(true);
        ticker.setGravity(Gravity.CENTER_VERTICAL);
        ticker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ticker.setFocusable(false);
        ticker.setIncludeFontPadding(false);
        ticker.setPadding(dp(6), 0, dp(4), 0);
        // 文本用 WRAP_CONTENT 而非权重(0,MATCH,1)，避免在 WRAP_CONTENT 容器里塌缩成 0 宽；
        // 整体也按内容自适应宽度，随状态栏布局自动测量。
        // ViewOverlay does not always resolve LinearLayout weights during its
        // manual measure/layout pass. Keep a concrete text width and update it
        // whenever the status-bar host is attached or remeasured.
        addView(ticker, new LayoutParams(dp(160), LayoutParams.MATCH_PARENT));
    }

    /** Plain ticker text; keep the final glyph fully opaque at the right edge. */
    private final class FadingTickerView extends TextView {
        FadingTickerView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
        }
    }

    /** 把组件插入状态栏容器，定位到状态栏时钟所在位置。 */
    void attachTo(ViewGroup parent) {
        if (parent == null) return;
        View discoveredClock = findClockView(parent);
        if (!(discoveredClock instanceof TextView)) {
            // The measured status_bar_contents/start-side host can be a
            // sibling of the clock. Search the complete SystemUI decor tree
            // so the overlay still gets the clock's real vertical position.
            View root = parent.getRootView();
            if (root instanceof ViewGroup && root != parent) {
                discoveredClock = findClockView((ViewGroup) root);
            }
        }
        if (discoveredClock instanceof TextView) {
            clockView = (TextView) discoveredClock;
        }
        if (host == parent && overlayAttached) return;
        detachOverlay();
        host = parent;
        attachClockStyle(parent);
        // Draw above the existing clock/icon children without participating in
        // ColorOS' container measurement or child-order rules.
        parent.getOverlay().add(this);
        overlayAttached = true;
        int left = clockOffsetInHost(false);
        int height = tickerRowHeight();
        int top = centeredTopInHost(height);
        int right = findRightSystemBoundary(parent);
        int[] tickerBounds = resolveFluidCloudAvoidance(parent, left, right, top, height);
        left = tickerBounds[0];
        right = tickerBounds[1];
        int availableWidth = Math.max(1, right - left);
        int width = availableWidth >= dp(144) ? availableWidth - dp(24) : availableWidth;
        int textWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight() - dp(16));
        LayoutParams tickerParams = (LayoutParams) ticker.getLayoutParams();
        tickerParams.width = textWidth;
        tickerParams.height = LayoutParams.MATCH_PARENT;
        tickerParams.weight = 0f;
        ticker.setLayoutParams(tickerParams);
        ticker.setVisibility(View.VISIBLE);
        contentWidth = Math.max(1, textWidth - ticker.getPaddingLeft() - ticker.getPaddingRight());
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        layout(left, top, left + width, top + height);
        setVisibility(View.VISIBLE);
        if (!hasText) setAlpha(0f);
        if (hasPending) {
            Drawable icon = pendingIcon;
            String text = pendingText;
            hasPending = false;
            pendingIcon = null;
            pendingText = null;
            show(icon, text);
        }
        // SystemUI may finish measuring the status-bar host one frame later.
        // Re-layout then so the text area uses the real host bounds.
        post(this::relayoutOverlay);
        postDelayed(this::relayoutOverlay, 90L);
        postDelayed(this::relayoutOverlay, 260L);
        if (hasText) scheduleOverlayKeepAlive();
    }

    private void scheduleOverlayKeepAlive() {
        handler.removeCallbacks(overlayKeepAliveAction);
        if (overlayAttached && hasText) {
            handler.postDelayed(overlayKeepAliveAction, 180L);
        }
    }

    private void keepOverlayAlive() {
        if (!overlayAttached || !hasText || host == null) return;
        try {
            // SystemUI can clear a ViewGroupOverlay while rebuilding the
            // notification icon row. Re-add the same view so the ticker does
            // not disappear until the normal timeout/removal path runs.
            host.getOverlay().remove(this);
            host.getOverlay().add(this);
            // New notification icon views can be inserted while the ticker is
            // active; hide them on every keep-alive pass to prevent overlap.
            hideStatusBarPeers(host);
            relayoutOverlay();
        } catch (Throwable ignored) {
        }
        scheduleOverlayKeepAlive();
    }

    private void relayoutOverlay() {
        if (!overlayAttached || host == null) return;
        // The collapsed status-bar hierarchy can expose the host before its
        // clock child is attached. Rebind once it appears instead of waiting
        // for a manual status-bar refresh.
        if (clockView == null || clockView.getParent() == null) {
            View found = findClockView(host);
            if (found instanceof TextView) attachClockStyle(host);
        }
        if (host.getMeasuredWidth() <= 0) return;
        int left = clockOffsetInHost(false);
        int height = tickerRowHeight();
        int top = centeredTopInHost(height);
        int right = findRightSystemBoundary(host);
        int[] tickerBounds = resolveFluidCloudAvoidance(host, left, right, top, height);
        left = tickerBounds[0];
        right = tickerBounds[1];
        int availableWidth = Math.max(1, right - left);
        int width = availableWidth >= dp(144) ? availableWidth - dp(24) : availableWidth;
        int textWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight() - dp(16));
        LayoutParams tickerParams = (LayoutParams) ticker.getLayoutParams();
        tickerParams.width = textWidth;
        tickerParams.height = LayoutParams.MATCH_PARENT;
        tickerParams.weight = 0f;
        ticker.setLayoutParams(tickerParams);
        contentWidth = Math.max(1, textWidth - ticker.getPaddingLeft() - ticker.getPaddingRight());
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        layout(left, top, left + width, top + height);
        if (hasText) {
            hideStatusBarPeers(host);
            copyClockStyle();
        }
        if (hasText && !fullText.isEmpty()) {
            prepareTextPages(fullText);
            pageIndex = Math.min(pageIndex, Math.max(0, textPages.size() - 1));
            ticker.setText(textPages.isEmpty() ? fullText : textPages.get(pageIndex));
        }
    }

    private ViewGroup chooseClockAnchor(ViewGroup root, View clock) {
        // Keep the host selected by BubbleNotificationHook. Promoting it to a
        // wider clock ancestor changes the ViewOverlay coordinate space and
        // makes the ticker jump after the first status-bar relayout.
        return root;
    }

    /** 查找状态栏时钟，并让本组件实时跟随其着色变化。 */
    private void attachClockStyle(ViewGroup root) {
        View found = findClockView(root);
        if (!(found instanceof TextView)) return;
        clockView = (TextView) found;
        clockView.getViewTreeObserver().addOnPreDrawListener(() -> {
            // 状态栏每次重绘（颜色随着色变化）时同步本组件的样式与颜色。
            if (hasText) copyClockStyle();
            return true;
        });
    }

    private void placeAtClockPosition(ViewGroup parent) {
        int index = parent.getChildCount();
        if (clockView != null && clockView.getParent() == parent) {
            index = parent.indexOfChild(clockView);
        }
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);

        ViewGroup.LayoutParams clockLp = clockView == null ? null : clockView.getLayoutParams();
        if (clockLp != null) {
            int maxWidth = parent.getMeasuredWidth();
            ticker.setMaxWidth(maxWidth > 0 ? (int) (maxWidth * 0.45f) : dp(160));
        }
        parent.addView(this, index, lp);
        suppressClock();
        android.util.Log.i("BubbleNotify", "overlay placed: parent=" + parent.getClass().getSimpleName()
                + " idx=" + index + " clockFound=" + (clockView != null)
                + " clockSuppressed=" + clockSuppressed);
    }

    private void suppressClock() {
        if (clockView == null) return;
        // hideStatusBarPeers() owns the clock transition. Do not overwrite
        // its original visibility after the exit animation has started.
        if (hiddenPeers.contains(clockView)) return;
        if (clockSuppressed) return;
        clockOriginalVisibility = clockView.getVisibility();
        clockView.setVisibility(View.GONE);
        clockSuppressed = true;
    }

    private void hideStatusBarPeers(ViewGroup parent) {
        if (clockView != null && clockView.getVisibility() != View.GONE) {
            hiddenPeers.add(clockView);
            hiddenPeerVisibility.add(clockView.getVisibility());
            animatePeerOut(clockView);
        }
        View icons = findNotificationIcons(parent);
        if (icons != null && icons != this && icons != clockView
                && icons.getVisibility() != View.GONE) {
            notificationIconsContainer = icons instanceof ViewGroup ? (ViewGroup) icons : null;
            hiddenPeers.add(icons);
            hiddenPeerVisibility.add(icons.getVisibility());
            animatePeerOut(icons);
        }
    }

    private void animatePeerOut(View view) {
        float distance = Math.max(statusBarHeight(), getHeight());
        view.animate().cancel();
        view.setTranslationY(-distance);
        view.animate().alpha(0f).setDuration(150L)
                .withEndAction(() -> view.setVisibility(View.GONE)).start();
    }

    private void hidePeersRecursive(View view) {
        if (view == this) return;
        // The host may itself be named status_bar_start_side/statusIcons. Keep
        // it visible because the ticker is drawn in that host's overlay.
        if (view == host) {
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    hidePeersRecursive(group.getChildAt(i));
                }
            }
            return;
        }
        String name = view.getClass().getName();
        String idName = "";
        try {
            if (view.getId() != View.NO_ID) idName = view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) { }
        // The end-side container owns signal, Wi-Fi, Bluetooth and battery.
        // Never recurse into or hide that region.
        boolean rightSystemArea = idName.contains("end_side")
                || idName.contains("system_icon_area")
                || idName.contains("status_bar_end");
        if (rightSystemArea) return;
        // Hide individual notification icon views only. Do not hide broad
        // containers such as statusIcons/start_side: ColorOS may place signal,
        // battery or the ticker host inside those containers.
        boolean notificationPeer = name.contains("StatusBarIconView")
                || name.contains("NotificationIcon");
        if (notificationPeer && view.getVisibility() != View.GONE) {
            hiddenPeers.add(view);
            hiddenPeerVisibility.add(view.getVisibility());
            view.setVisibility(View.GONE);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hidePeersRecursive(group.getChildAt(i));
        }
    }

    private void restorePeers() {
        for (int i = 0; i < hiddenPeers.size(); i++) {
            View view = hiddenPeers.get(i);
            int visibility = hiddenPeerVisibility.get(i);
            view.animate().cancel();
            view.setVisibility(visibility == View.INVISIBLE ? View.INVISIBLE :
                    (visibility == View.GONE ? View.GONE : View.VISIBLE));
            view.setTranslationY(-Math.max(statusBarHeight(), getHeight()));
            view.setAlpha(0f);
            view.animate().translationY(0f).alpha(1f).setDuration(160L).start();
        }
        hiddenPeers.clear();
        hiddenPeerVisibility.clear();
        notificationIconsContainer = null;
    }

    private View findNotificationIcons(View view) {
        if (view == null || view == this || view == clockView) return null;
        try {
            if (view.getId() != View.NO_ID) {
                String idName = view.getResources().getResourceEntryName(view.getId());
                if ("notificationIcons".equals(idName)
                        || idName.contains("notification_icon_area")) return view;
            }
        } catch (Throwable ignored) { }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findNotificationIcons(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int clockOffsetInHost(boolean vertical) {
        if (clockView == null || host == null) return 0;
        try {
            int[] clock = new int[2];
            int[] parent = new int[2];
            clockView.getLocationInWindow(clock);
            host.getLocationInWindow(parent);
            return Math.max(0, (vertical ? clock[1] - parent[1] : clock[0] - parent[0]));
        } catch (Throwable ignored) {
            return vertical ? clockView.getTop() : clockView.getLeft();
        }
    }

    private int clockCenterOffsetInHost() {
        if (clockView == null || host == null) return statusBarHeight() / 2;
        try {
            int[] clock = new int[2];
            int[] parent = new int[2];
            clockView.getLocationInWindow(clock);
            host.getLocationInWindow(parent);
            return Math.max(0, clock[1] - parent[1] + clockView.getHeight() / 2);
        } catch (Throwable ignored) {
            return clockView.getTop() + clockView.getHeight() / 2;
        }
    }

    private int tickerRowHeight() {
        int hostHeight = host == null ? 0 : host.getHeight();
        if (hostHeight <= 0 && host != null) hostHeight = host.getMeasuredHeight();
        int clockHeight = clockView == null ? 0 : clockView.getHeight();
        return Math.max(dp(18), Math.min(statusBarHeight(),
                hostHeight > 0 ? hostHeight : (clockHeight > 0 ? clockHeight : statusBarHeight())));
    }

    private int centeredTopInHost(int height) {
        int hostHeight = host == null ? 0 : host.getHeight();
        if (hostHeight <= 0 && host != null) hostHeight = host.getMeasuredHeight();
        if (hostHeight > 0) return Math.max(0, (hostHeight - height) / 2 + dp(4));
        // Before the first layout, keep the row at the status-bar origin and
        // let the delayed relayout pass recenter it once the host has height.
        return dp(4);
    }

    private int findRightSystemBoundary(ViewGroup parent) {
        int fallback = parent.getMeasuredWidth();
        View boundary = findRightSystemView(parent);
        if (boundary == null) return fallback;
        try {
            int[] child = new int[2];
            int[] root = new int[2];
            boundary.getLocationInWindow(child);
            parent.getLocationInWindow(root);
            return Math.max(0, child[0] - root[0]);
        } catch (Throwable ignored) {
            return boundary.getLeft();
        }
    }

    /**
     * Keep the ticker inside the portion of the status bar not occupied by a
     * visible ColorOS Fluid Cloud capsule. The capsule is a separate SystemUI
     * window on some releases, so this is resolved in screen coordinates and
     * converted back into the overlay host's local coordinate space.
     */
    private int[] resolveFluidCloudAvoidance(ViewGroup parent, int left, int right,
                                        int top, int height) {
        if (parent == null || right <= left) return new int[]{left, right};
        Rect cloud = findFluidCloudBounds();
        if (cloud == null) return new int[]{left, right};
        try {
            int[] hostLocation = new int[2];
            parent.getLocationOnScreen(hostLocation);
            Rect row = new Rect(hostLocation[0] + left, hostLocation[1] + top,
                    hostLocation[0] + right, hostLocation[1] + top + height);
            if (!Rect.intersects(row, cloud)) return new int[]{left, right};
            int gap = dp(6);
            int cloudLeft = cloud.left - hostLocation[0];
            int cloudRight = cloud.right - hostLocation[0];
            int leftWidth = Math.max(0, Math.min(right, cloudLeft - gap) - left);
            int rightWidth = Math.max(0, right - Math.max(left, cloudRight + gap));
            // Prefer the larger unobstructed segment. If both are narrow, keep
            // the wider one and let the existing text pagination handle it.
            if (rightWidth > leftWidth) {
                int newLeft = Math.min(right, cloudRight + gap);
                return new int[]{newLeft, right};
            }
            int newRight = Math.max(left, cloudLeft - gap);
            return new int[]{left, newRight};
        } catch (Throwable ignored) {
            return new int[]{left, right};
        }
    }

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
                if (found != null && (fluidCloudBounds.isEmpty()
                        || found.width() * found.height()
                        > fluidCloudBounds.width() * fluidCloudBounds.height())) {
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
        String identity = view.getClass().getName().toLowerCase(Locale.ROOT);
        try {
            int id = view.getId();
            if (id != View.NO_ID && id != 0) {
                String entry = view.getResources().getResourceEntryName(id);
                if (entry != null) identity += " " + entry.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) { }
        Object tag = view.getTag();
        if (tag != null) identity += " " + String.valueOf(tag).toLowerCase(Locale.ROOT);
        boolean capsule = identity.contains("fluid") || identity.contains("capsule")
                || identity.contains("seedling") || identity.contains("mini_capsule")
                || identity.contains("smartcapsule") || identity.contains("smart_capsule")
                || identity.contains("fluid_cloud") || identity.contains("fluidcloud")
                || identity.contains("floating_cloud") || identity.contains("notificationcapsule")
                || identity.contains("statusbarcapsule") || identity.contains("dynamicisland");
        Rect own = null;
        if (capsule && view.getHeight() <= dp(180)
                && view.getWidth() >= dp(24)
                && view.getWidth() <= Math.round(getResources().getDisplayMetrics().widthPixels * 0.82f)
                && view.getHeight() >= dp(18)) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            own = new Rect(location[0], location[1],
                    location[0] + view.getWidth(), location[1] + view.getHeight());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Rect child = findFluidCloudInView(group.getChildAt(i));
                if (child != null && (own == null || child.width() * child.height()
                        > own.width() * own.height())) own = child;
            }
        }
        return own;
    }

    private View findRightSystemView(View view) {
        if (view == this) return null;
        String idName = "";
        try {
            if (view.getId() != View.NO_ID) idName = view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) { }
        if (idName.contains("end_side") || idName.contains("system_icon_area")
                || idName.contains("status_bar_end")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRightSystemView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void restoreClock() {
        if (!clockSuppressed || clockView == null) return;
        int visibility = clockOriginalVisibility == View.INVISIBLE ? View.INVISIBLE :
                (clockOriginalVisibility == View.GONE ? View.GONE : View.VISIBLE);
        clockView.setVisibility(visibility);
        clockSuppressed = false;
    }

    /** 从状态栏时钟复制字体与颜色；找不到时钟时退回到状态栏前景色。 */
    private void copyClockStyle() {
        int color = statusForeground();
        if (clockView != null) {
            color = clockView.getCurrentTextColor();
            float clockSize = clockView.getTextSize();
            if (clockSize > 0f) {
                ticker.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        Math.max(dp(11), Math.min(dp(18), clockSize)));
            } else {
                ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            }
            ticker.setTypeface(clockView.getTypeface());
        } else {
            ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        }
        if (Color.alpha(color) == 0) color = statusForeground();
        ticker.setTextColor(color);
        if (iconView.getVisibility() != View.GONE && iconView.getDrawable() != null) {
            // Match the monochrome black/white status-bar icon appearance.
            iconView.getDrawable().mutate().setColorFilter(
                    new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            iconView.invalidate();
        }
    }

    void show(Drawable icon, String text) {
        show(icon, text, 1);
    }

    void show(Drawable icon, String text, int rounds) {
        if (host == null) {
            pendingIcon = icon;
            pendingText = text == null ? "" : text;
            hasPending = true;
            return;
        }
        if (!overlayAttached) attachTo(host);
        setVisibility(View.VISIBLE);
        animate().cancel();
        setAlpha(1f);
        shownAt = android.os.SystemClock.uptimeMillis();
        attached = true;
        displayRounds = Math.max(1, Math.min(5, rounds));
        completedRounds = 0;
        hideStatusBarPeers(host);
        setStatusIcon(icon);
        copyClockStyle();
        final String next = text == null ? "" : text;
        fullText = next;
        prepareTextPages(next);
        ticker.animate().cancel();
        handler.removeCallbacks(pageAdvanceAction);
        int distance = scrollDistance();
        if (!hasText) {
            ticker.setText(textPages.isEmpty() ? "" : textPages.get(0));
            pageIndex = 0;
            ticker.setTranslationY(distance);
            ticker.setAlpha(0f);
            iconView.setTranslationY(distance);
            iconView.setAlpha(0f);
            ticker.animate().translationY(0f).alpha(1f).setDuration(190L)
                    .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start();
            iconView.animate().translationY(0f).alpha(1f).setDuration(190L)
                    .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start();
        } else {
            ticker.animate().translationY(-distance).alpha(0f).setDuration(150L)
                    .setInterpolator(SCROLL_EXIT_INTERPOLATOR)
                    .withEndAction(() -> {
                        ticker.setText(textPages.isEmpty() ? "" : textPages.get(0));
                        pageIndex = 0;
                        ticker.setTranslationY(distance);
                        ticker.animate().translationY(0f).alpha(1f).setDuration(190L)
                                .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start();
                    }).start();
        }
        hasText = true;
        schedulePageAdvance();
        handler.removeCallbacks(removeAction);
        if (textPages.size() <= 1) {
            handler.postDelayed(removeAction, 2_400L);
        }
        scheduleOverlayKeepAlive();
    }

    void updateIcon(Drawable icon) {
        if (!hasText || icon == null) return;
        setStatusIcon(icon);
        copyClockStyle();
    }

    void setFinishedAction(Runnable action) {
        finishedAction = action;
    }

    boolean isShowing() {
        return hasText;
    }

    void finishCurrentRoundSoon() {
        if (!hasText) return;
        displayRounds = Math.min(displayRounds, completedRounds + 1);
    }

    private void setStatusIcon(Drawable icon) {
        if (icon == null) { iconView.setVisibility(View.GONE); return; }
        iconView.setVisibility(View.VISIBLE);
        setImageDrawable(icon);
        copyClockStyle();
    }

    private void setImageDrawable(Drawable icon) {
        Drawable copy = icon.getConstantState() == null ? icon : icon.getConstantState().newDrawable();
        iconView.setImageDrawable(copy.mutate());
    }

    void removeImmediately() {
        long elapsed = android.os.SystemClock.uptimeMillis() - shownAt;
        if (hasText && elapsed >= 0L && elapsed < 1_500L && isScreenInteractive()) {
            // Protect against a transient SystemUI/status-bar rebuild calling
            // the shared cleanup path during the first frame of a ticker.
            // Real screen-off cleanup still proceeds because the display is
            // no longer interactive.
            handler.removeCallbacks(removeAction);
            handler.postDelayed(removeAction, 1_500L - elapsed);
            scheduleOverlayKeepAlive();
            android.util.Log.i("BubbleNotify", "status ticker early remove deferred");
            return;
        }
        handler.removeCallbacks(removeAction);
        handler.removeCallbacks(pageAdvanceAction);
        handler.removeCallbacks(overlayKeepAliveAction);
        attached = false;
        hasText = false;
        completedRounds = 0;
        restorePeers();
        restoreClock();
        ticker.animate().cancel();
        animate().cancel();
        setAlpha(0f);
        ticker.setAlpha(0f);
        ticker.setText("");
        textPages.clear();
        fullText = "";
        pageIndex = 0;
        iconView.setImageDrawable(null);
        iconView.setVisibility(View.GONE);
        shownAt = 0L;
        // Keep the transparent host attached. ColorOS otherwise rebuilds the
        // left status-bar group and makes clock/icon visibility race with us.
        Runnable callback = finishedAction;
        if (callback != null) handler.post(callback);
    }

    private boolean isScreenInteractive() {
        try {
            android.os.PowerManager power = getContext().getSystemService(
                    android.os.PowerManager.class);
            return power == null || power.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void prepareTextPages(String text) {
        textPages.clear();
        if (text == null || text.isEmpty()) return;
        int width = Math.max(dp(90), contentWidth);
        String remaining = text;
        while (!remaining.isEmpty()) {
            int count;
            try {
                count = ticker.getPaint().breakText(remaining, true, width, null);
            } catch (Throwable ignored) {
                count = Math.min(24, remaining.length());
            }
            if (count <= 0) count = Math.min(24, remaining.length());
            int split = Math.min(count, remaining.length());
            int newline = remaining.lastIndexOf('\n', split - 1);
            if (newline > 0) split = newline;
            textPages.add(remaining.substring(0, split).trim());
            remaining = remaining.substring(split).trim();
        }
    }

    private void schedulePageAdvance() {
        if (textPages.size() > 1) handler.postDelayed(pageAdvanceAction, 1800L);
    }

    private void advancePage() {
        if (!hasText || textPages.size() <= 1) return;
        int distance = scrollDistance();
        if (pageIndex >= textPages.size() - 1) {
            completedRounds++;
            if (completedRounds >= displayRounds) {
                removeImmediately();
                return;
            }
            pageIndex = 0;
        } else {
            pageIndex++;
        }
        ticker.animate().cancel();
        ticker.animate().translationY(-distance).alpha(0f).setDuration(150L)
                .setInterpolator(SCROLL_EXIT_INTERPOLATOR)
                .withEndAction(() -> {
                    ticker.setText(textPages.get(pageIndex));
                    ticker.setTranslationY(distance);
                    ticker.animate().translationY(0f).alpha(1f).setDuration(190L)
                            .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start();
                }).start();
        handler.postDelayed(pageAdvanceAction, 2200L);
    }

    /** Scroll only half a status-bar row so each page remains visually anchored. */
    private int scrollDistance() {
        int row = getHeight() > 0 ? getHeight() : statusBarHeight();
        return Math.max(dp(8), Math.round(row * 0.5f));
    }

    private void detachOverlay() {
        handler.removeCallbacks(overlayKeepAliveAction);
        restorePeers();
        restoreClock();
        if (host != null && overlayAttached) {
            try { host.getOverlay().remove(this); } catch (Throwable ignored) { }
        }
        overlayAttached = false;
    }

    private int statusForeground() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return dark ? Color.WHITE : Color.BLACK;
    }

    private View findClockView(ViewGroup root) {
        // 优先按 id 查找状态栏时钟
        int clockId = getResources().getIdentifier("status_bar_clock", "id", "android");
        if (clockId == 0) clockId = getResources().getIdentifier("clock", "id", "com.android.systemui");
        if (clockId == 0) clockId = getResources().getIdentifier("status_bar_clock", "id", "com.android.systemui");
        if (clockId == 0) clockId = getResources().getIdentifier(
                "status_bar_elapsed_time", "id", "android");
        if (clockId != 0) {
            View match = root.findViewById(clockId);
            if (match instanceof TextView) return match;
        }
        View byClass = findClockByClass(root);
        return byClass != null ? byClass : findClockByResourceName(root);
    }

    private View findClockByClass(View view) {
        if (view instanceof TextView) {
            String name = view.getClass().getName();
            if (name.endsWith("Clock") || name.contains(".ClockController")) return view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findClockByClass(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private View findClockByResourceName(View view) {
        if (view == null) return null;
        try {
            if (view.getId() != View.NO_ID) {
                String name = view.getResources().getResourceEntryName(view.getId());
                if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("clock")
                        && view instanceof TextView) return view;
            }
        } catch (Throwable ignored) { }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClockByResourceName(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? dp(24) : getResources().getDimensionPixelSize(id);
    }
}
