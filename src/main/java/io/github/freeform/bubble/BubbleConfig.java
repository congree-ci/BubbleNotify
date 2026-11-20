package io.github.freeform.bubble;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shared module preferences. The hook reads the same file through libxposed remote prefs. */
public final class BubbleConfig {
    static final String PREFS_NAME = "bubble_notify_config";
    private static final String KEY_DEVICE_STORAGE_MIGRATED = "_device_storage_migrated";
    private static final Object STORAGE_LOCK = new Object();

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_BLACKLIST = "blacklist";
    private static final String KEY_HORIZONTAL = "horizontal_percent";
    private static final String KEY_BOTTOM = "bottom_percent";
    private static final String KEY_PORTRAIT_X = "portrait_x_percent";
    private static final String KEY_PORTRAIT_Y = "portrait_y_percent";
    private static final String KEY_LANDSCAPE_X = "landscape_x_percent";
    private static final String KEY_LANDSCAPE_Y = "landscape_y_percent";
    private static final String KEY_PORTRAIT_DOCK = "portrait_dock_edge";
    private static final String KEY_LANDSCAPE_DOCK = "landscape_dock_edge";
    private static final String KEY_TIMEOUT = "timeout_seconds";
    private static final String KEY_CONTENT_ENABLED = "content_preview_enabled";
    private static final String KEY_CONTENT_SUPPRESS_BANNER = "content_preview_suppress_banner";
    private static final String KEY_CONTENT_EDGE_ONLY = "content_preview_edge_only";
    private static final String KEY_CONTENT_POSITION = "content_preview_position";
    private static final String KEY_CONTENT_ALPHA = "content_preview_alpha";
    private static final String KEY_CONTENT_TIMEOUT = "content_preview_timeout";
    private static final String KEY_CONTENT_DYNAMIC_TIMEOUT = "content_preview_dynamic_timeout";
    private static final String KEY_DECORATION_CONTINUOUS = "decoration_continuous";
    private static final String KEY_BUBBLE_AUTO_DISMISS = "bubble_auto_dismiss";
    private static final String KEY_CONTENT_AUTO_WIDTH = "content_preview_auto_width";
    private static final String KEY_CONTENT_WIDTH = "content_preview_width_dp";
    private static final String KEY_CONTENT_OVERLAY_ALPHA = "content_preview_overlay_alpha";
    /** Percentage of the bubble's default 92dp window size. */
    private static final String KEY_BUBBLE_SIZE = "bubble_size_percent";
    /** Percentage of the notification icon's default radius inside the bubble. */
    private static final String KEY_ICON_SIZE = "bubble_icon_size_percent";
    /** Percentage of the corner app-badge default size. */
    private static final String KEY_BADGE_SIZE = "bubble_badge_size_percent";
    private static final String KEY_STATUS_SCROLL_ENABLED = "status_scroll_enabled";
    private static final String KEY_STATUS_SCROLL_MODE = "status_scroll_mode";
    private static final String KEY_STATUS_SCROLL_ROUNDS = "status_scroll_rounds";
    private static final String KEY_STATUS_SCROLL_WHITELIST = "status_scroll_whitelist";
    private static final String KEY_TIMEOUT_DIM = "bubble_timeout_dim";
    private static final String KEY_AWAKE_PROMPT = "screen_awake_prompt";
    private static final String KEY_CLEAR_STATUS_ON_SWIPE = "clear_status_on_down_swipe";
    private static final String KEY_INCLUDE_FLUID_CLOUD = "include_fluid_cloud_notifications";
    private static final String KEY_IMMEDIATE_SUGGESTIONS = "immediate_suggestions";
    /** Temporary suggestions keep their position instead of docking to the screen edge. */
    private static final String KEY_SUGGESTION_NO_EDGE = "suggestion_no_edge";
    private static final String KEY_SUGGEST_FLASHLIGHT = "suggest_flashlight";
    private static final String KEY_SUGGEST_MUSIC = "suggest_music";
    private static final String KEY_SUGGEST_APP_INSTALL = "suggest_app_install";
    private static final String KEY_SUGGEST_CLIPBOARD = "suggest_clipboard";

    public static final int CONTENT_AUTO = 0;
    public static final int CONTENT_ABOVE = 1;
    public static final int CONTENT_BELOW = 2;
    public static final int CONTENT_INWARD = 3;

    public static final int STATUS_SCROLL_OFF = 0;
    public static final int STATUS_SCROLL_NOTIFICATION_BAR = 1;
    public static final int STATUS_SCROLL_FLUID_CLOUD = 2;

    public static final int DEFAULT_PORTRAIT_X = 88;
    public static final int DEFAULT_PORTRAIT_Y = 88;
    public static final int DEFAULT_LANDSCAPE_X = 92;
    public static final int DEFAULT_LANDSCAPE_Y = 82;
    public static final int DEFAULT_TIMEOUT_SECONDS = 7;

    /** Whole-bubble scale, as a percentage of the original 92dp window. */
    public static final int DEFAULT_BUBBLE_SIZE_PERCENT = 100;
    public static final int MIN_BUBBLE_SIZE_PERCENT = 60;
    public static final int MAX_BUBBLE_SIZE_PERCENT = 140;

    /** Default icon radius inside the bubble, as a percentage of the original 20dp. */
    public static final int DEFAULT_ICON_SIZE_PERCENT = 100;
    public static final int MIN_ICON_SIZE_PERCENT = 60;
    public static final int MAX_ICON_SIZE_PERCENT = 150;

    /** Default corner app-badge size, as a percentage of the original 16dp. */
    public static final int DEFAULT_BADGE_SIZE_PERCENT = 100;
    public static final int MIN_BADGE_SIZE_PERCENT = 50;
    public static final int MAX_BADGE_SIZE_PERCENT = 180;

    private final SharedPreferences preferences;
    private final SharedPreferences mirrorPreferences;

    public BubbleConfig(Context context) {
        this.preferences = preferencesFor(context);
        this.mirrorPreferences = mirrorPreferencesFor(context);
    }

    public BubbleConfig(SharedPreferences preferences) {
        this.preferences = preferences;
        this.mirrorPreferences = null;
    }

    private static SharedPreferences mirrorPreferencesFor(Context context) {
        if (context == null) return null;
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Keep the configuration in device-protected storage so SystemUI can read
     * the user's settings during direct boot, before the first unlock.
     * Existing installs are copied from the old credential-protected file once.
     */
    private static SharedPreferences preferencesFor(Context context) {
        if (context == null) return null;
        synchronized (STORAGE_LOCK) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences device = deviceContext.getSharedPreferences(
                        PREFS_NAME, Context.MODE_PRIVATE);
                Map<String, ?> deviceValues = device.getAll();
                boolean hasDeviceSettings = false;
                for (String key : deviceValues.keySet()) {
                    if (!KEY_DEVICE_STORAGE_MIGRATED.equals(key)) {
                        hasDeviceSettings = true;
                        break;
                    }
                }
                if (!hasDeviceSettings) {
                    try {
                        SharedPreferences credential = context.getSharedPreferences(
                                PREFS_NAME, Context.MODE_PRIVATE);
                        Map<String, ?> values = credential.getAll();
                        if (values != null && !values.isEmpty()) {
                            SharedPreferences.Editor editor = device.edit();
                            for (Map.Entry<String, ?> entry : values.entrySet()) {
                                if (KEY_DEVICE_STORAGE_MIGRATED.equals(entry.getKey())) continue;
                                putValue(editor, entry.getKey(), entry.getValue());
                            }
                            editor.putBoolean(KEY_DEVICE_STORAGE_MIGRATED, true).commit();
                        }
                    } catch (Throwable ignored) {
                        // Credential storage is unavailable during direct boot;
                        // retry migration after ACTION_USER_UNLOCKED.
                    }
                }
                return device;
            } catch (Throwable ignored) {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            }
        }
    }

    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> values = (Set<String>) value;
            editor.putStringSet(key, new HashSet<>(values));
        }
    }

    /** 暴露底层 SharedPreferences，供设置页注册变更监听以实时刷新 SystemUI 等外部进程。 */
    public SharedPreferences rawPreferences() {
        return preferences;
    }

    /**
     * 通过 ContentProvider 读取模块的最新配置（供 SystemUI/system_server 等外部进程使用）。
     *
     * <p>The provider answers with the canonical preference keys (see
     * {@link BubbleConfigProvider}), so the returned configuration behaves exactly like the
     * on-device store. A response that only carries defaults because the credential-protected
     * copy is still unreadable is reported as {@code configurationPending} and yields
     * {@code null} so callers keep retrying instead of silently applying defaults. A locked
     * response that carries real settings (device-protected storage) is accepted, which is what
     * makes the module work while the screen is off / before the first unlock.</p>
     */
    public static BubbleConfig fromProvider(Context context) {
        try {
            return readProvider(context);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Same as {@link #fromProvider(Context)} but lets transport failures propagate, so the
     * injected process can log why the provider was unreachable (ColorOS rejects the cold
     * start of the module process, which otherwise looks like "no settings").
     */
    public static BubbleConfig readProvider(Context context) throws Exception {
        if (context == null) return null;
        android.os.Bundle result = context.getContentResolver().call(
                BubbleConfigProvider.URI,
                BubbleConfigProvider.METHOD_GET_BUBBLE_TIMEOUT, null, null);
        if (result == null || !result.getBoolean(BubbleConfigProvider.EXTRA_SUCCESS)) {
            return null;
        }
        if (result.getBoolean(BubbleConfigProvider.EXTRA_CONFIGURATION_PENDING, false)) {
            return null;
        }
        SharedPreferences prefs = InMemorySharedPreferences.fromBundle(result);
        return new BubbleConfig(prefs);
    }

    /** Copies the whole configuration into another store (used for the boot-time cache). */
    public void copyTo(SharedPreferences target) {
        if (preferences == null || target == null || target == preferences) return;
        Map<String, ?> values = preferences.getAll();
        SharedPreferences.Editor editor = target.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            putValue(editor, entry.getKey(), entry.getValue());
        }
        editor.apply();
    }

    public boolean isEnabled() {
        return preferences == null || preferences.getBoolean(KEY_ENABLED, true);
    }

    /** True when this preference store contains actual persisted user settings. */
    public boolean hasPersistedSettings() {
        if (preferences == null) return false;
        for (String key : preferences.getAll().keySet()) {
            if (!KEY_DEVICE_STORAGE_MIGRATED.equals(key)) return true;
        }
        return false;
    }

    public void setEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit();
        syncMirror();
    }

    /** An empty whitelist intentionally disables notifications for every app. */
    public Set<String> getWhitelist() {
        return copySet(KEY_WHITELIST);
    }

    public void setWhitelist(Set<String> packages) {
        writeSet(KEY_WHITELIST, packages);
    }

    public boolean acceptsNotificationFrom(String packageName) {
        Set<String> whitelist = getWhitelist();
        return packageName != null && !whitelist.isEmpty() && whitelist.contains(packageName);
    }

    public Set<String> getBlacklist() {
        return copySet(KEY_BLACKLIST);
    }

    public void setBlacklist(Set<String> packages) {
        writeSet(KEY_BLACKLIST, packages);
    }

    public boolean suppressesWhileForeground(String packageName) {
        return packageName != null && getBlacklist().contains(packageName);
    }

    /**
     * The application blacklist applies to the notification's source package: a blacklisted
     * app never raises a bubble. ({@link #suppressesWhileForeground(String)} additionally keeps
     * other apps quiet while a blacklisted app owns the screen.)
     */
    public boolean isBlacklisted(String packageName) {
        return packageName != null && getBlacklist().contains(packageName);
    }

    /** An empty whitelist means all applications may use the status ticker. */
    public Set<String> getStatusScrollWhitelist() {
        return copySet(KEY_STATUS_SCROLL_WHITELIST);
    }

    public void setStatusScrollWhitelist(Set<String> packages) {
        writeSet(KEY_STATUS_SCROLL_WHITELIST, packages);
    }

    public boolean acceptsStatusScrollFrom(String packageName) {
        Set<String> whitelist = getStatusScrollWhitelist();
        return packageName != null && (whitelist.isEmpty() || whitelist.contains(packageName));
    }

    public int getPositionX(boolean landscape) {
        if (preferences == null) return landscape ? DEFAULT_LANDSCAPE_X : DEFAULT_PORTRAIT_X;
        String key = landscape ? KEY_LANDSCAPE_X : KEY_PORTRAIT_X;
        int fallback = landscape
                ? DEFAULT_LANDSCAPE_X
                : preferences.getInt(KEY_HORIZONTAL, DEFAULT_PORTRAIT_X);
        return clamp(preferences.getInt(key, fallback), 0, 100);
    }

    public int getPositionY(boolean landscape) {
        if (preferences == null) return landscape ? DEFAULT_LANDSCAPE_Y : DEFAULT_PORTRAIT_Y;
        String key = landscape ? KEY_LANDSCAPE_Y : KEY_PORTRAIT_Y;
        int fallback = landscape
                ? DEFAULT_LANDSCAPE_Y
                : 100 - preferences.getInt(KEY_BOTTOM, 12);
        return clamp(preferences.getInt(key, fallback), 0, 100);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setPosition(boolean landscape, int xPercent, int yPercent) {
        if (preferences == null) return;
        preferences.edit()
                .putInt(landscape ? KEY_LANDSCAPE_X : KEY_PORTRAIT_X,
                        clamp(xPercent, 0, 100))
                .putInt(landscape ? KEY_LANDSCAPE_Y : KEY_PORTRAIT_Y,
                        clamp(yPercent, 0, 100))
                .commit();
        syncMirror();
    }

    /** -1 is left, 0 is undocked, and 1 is right. */
    public int getDockEdge(boolean landscape) {
        if (preferences == null) return 0;
        int value = preferences.getInt(
                landscape ? KEY_LANDSCAPE_DOCK : KEY_PORTRAIT_DOCK, 0);
        return Integer.compare(value, 0);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setDockEdge(boolean landscape, int edge) {
        if (preferences == null) return;
        preferences.edit()
                .putInt(landscape ? KEY_LANDSCAPE_DOCK : KEY_PORTRAIT_DOCK,
                        Integer.compare(edge, 0))
                .commit();
        syncMirror();
    }

    public int getTimeoutSeconds() {
        if (preferences == null) return DEFAULT_TIMEOUT_SECONDS;
        return clamp(preferences.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS), 3, 15);
    }

    public void setTimeoutSeconds(int seconds) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_TIMEOUT, clamp(seconds, 3, 15)).commit();
        syncMirror();
    }

    public boolean isContentPreviewEnabled() {
        return preferences == null || preferences.getBoolean(KEY_CONTENT_ENABLED, true);
    }

    public void setContentPreviewEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_CONTENT_ENABLED, enabled).commit();
        syncMirror();
    }

    public boolean isContentPreviewSuppressBanner() {
        return preferences != null && preferences.getBoolean(KEY_CONTENT_SUPPRESS_BANNER, false);
    }

    public void setContentPreviewSuppressBanner(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_CONTENT_SUPPRESS_BANNER, enabled).commit();
        syncMirror();
    }

    public boolean isContentPreviewEdgeOnly() {
        return preferences != null && preferences.getBoolean(KEY_CONTENT_EDGE_ONLY, false);
    }

    public void setContentPreviewEdgeOnly(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_CONTENT_EDGE_ONLY, enabled).commit();
        syncMirror();
    }

    public int getContentPreviewPosition() {
        return clamp(preferences == null ? CONTENT_AUTO
                : preferences.getInt(KEY_CONTENT_POSITION, CONTENT_AUTO),
                CONTENT_AUTO, CONTENT_INWARD);
    }

    public void setContentPreviewPosition(int position) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_CONTENT_POSITION,
                clamp(position, CONTENT_AUTO, CONTENT_INWARD)).commit();
        syncMirror();
    }

    public int getContentPreviewAlpha() {
        return clamp(preferences == null ? 88
                : preferences.getInt(KEY_CONTENT_ALPHA, 88), 35, 100);
    }

    public void setContentPreviewAlpha(int percent) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_CONTENT_ALPHA, clamp(percent, 35, 100)).commit();
        syncMirror();
    }

    public int getContentPreviewTimeoutSeconds() {
        return clamp(preferences == null ? 5
                : preferences.getInt(KEY_CONTENT_TIMEOUT, 5), 2, 15);
    }

    public void setContentPreviewTimeoutSeconds(int seconds) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_CONTENT_TIMEOUT, clamp(seconds, 2, 15)).commit();
        syncMirror();
    }

    public boolean isContentPreviewDynamicTimeout() {
        return preferences == null || preferences.getBoolean(KEY_CONTENT_DYNAMIC_TIMEOUT, true);
    }

    public void setContentPreviewDynamicTimeout(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_CONTENT_DYNAMIC_TIMEOUT, enabled).commit();
        syncMirror();
    }


    public boolean isDecorationContinuous() {
        return preferences == null || preferences.getBoolean(KEY_DECORATION_CONTINUOUS, true);
    }

    public void setDecorationContinuous(boolean continuous) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_DECORATION_CONTINUOUS, continuous).commit();
        syncMirror();
    }

    public boolean isMainBubbleAnimationContinuous() {
        return isDecorationContinuous();
    }

    public void setMainBubbleAnimationContinuous(boolean continuous) {
        setDecorationContinuous(continuous);
    }

    public boolean isBubbleAutoDismissEnabled() {
        return preferences == null || preferences.getBoolean(KEY_BUBBLE_AUTO_DISMISS, true);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setBubbleAutoDismissEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_BUBBLE_AUTO_DISMISS, enabled).commit();
        syncMirror();
    }

    public boolean shouldDimBubbleOnTimeout() {
        return preferences != null && preferences.getBoolean(KEY_TIMEOUT_DIM, false);
    }

    public void setDimBubbleOnTimeout(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_TIMEOUT_DIM, enabled).commit();
        syncMirror();
    }

    public boolean isScreenAwakePromptEnabled() {
        return preferences == null || preferences.getBoolean(KEY_AWAKE_PROMPT, true);
    }

    public void setScreenAwakePromptEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_AWAKE_PROMPT, enabled).commit();
        syncMirror();
    }

    public boolean isClearStatusOnDownSwipeEnabled() {
        return preferences != null && preferences.getBoolean(KEY_CLEAR_STATUS_ON_SWIPE, false);
    }

    public void setClearStatusOnDownSwipeEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_CLEAR_STATUS_ON_SWIPE, enabled).commit();
        syncMirror();
    }

    public boolean isIncludeFluidCloudNotificationsEnabled() {
        return preferences != null && preferences.getBoolean(KEY_INCLUDE_FLUID_CLOUD, false);
    }

    public void setIncludeFluidCloudNotificationsEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_INCLUDE_FLUID_CLOUD, enabled).commit();
        syncMirror();
    }

    /**
     * 临时通知（即时建议）不贴边：开启后建议气泡停在既设位置，不会吸附/半隐藏在屏幕边缘，
     * 也不会因为贴边而变成半透明。
     */
    public boolean isSuggestionNoEdgeEnabled() {
        return preferences != null && preferences.getBoolean(KEY_SUGGESTION_NO_EDGE, false);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setSuggestionNoEdgeEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_SUGGESTION_NO_EDGE, enabled).commit();
        syncMirror();
    }

    public boolean isImmediateSuggestionsEnabled() {
        return preferences == null || preferences.getBoolean(KEY_IMMEDIATE_SUGGESTIONS, true);
    }

    public void setImmediateSuggestionsEnabled(boolean enabled) {
        writeBoolean(KEY_IMMEDIATE_SUGGESTIONS, enabled);
    }

    public boolean isFlashlightSuggestionEnabled() {
        return preferences == null || preferences.getBoolean(KEY_SUGGEST_FLASHLIGHT, true);
    }

    public void setFlashlightSuggestionEnabled(boolean enabled) {
        writeBoolean(KEY_SUGGEST_FLASHLIGHT, enabled);
    }

    public boolean isMusicSuggestionEnabled() {
        return preferences == null || preferences.getBoolean(KEY_SUGGEST_MUSIC, true);
    }

    public void setMusicSuggestionEnabled(boolean enabled) {
        writeBoolean(KEY_SUGGEST_MUSIC, enabled);
    }

    public boolean isAppInstallSuggestionEnabled() {
        return preferences == null || preferences.getBoolean(KEY_SUGGEST_APP_INSTALL, true);
    }

    public void setAppInstallSuggestionEnabled(boolean enabled) {
        writeBoolean(KEY_SUGGEST_APP_INSTALL, enabled);
    }

    public boolean isClipboardSuggestionEnabled() {
        return preferences != null && preferences.getBoolean(KEY_SUGGEST_CLIPBOARD, false);
    }

    public void setClipboardSuggestionEnabled(boolean enabled) {
        writeBoolean(KEY_SUGGEST_CLIPBOARD, enabled);
    }

    public boolean isContentPreviewAutoWidth() {
        return preferences == null || preferences.getBoolean(KEY_CONTENT_AUTO_WIDTH, true);
    }

    public void setContentPreviewAutoWidth(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean(KEY_CONTENT_AUTO_WIDTH, enabled).commit();
        syncMirror();
    }

    public int getContentPreviewWidthDp() {
        return clamp(preferences == null ? 280
                : preferences.getInt(KEY_CONTENT_WIDTH, 280), 180, 360);
    }

    public void setContentPreviewWidthDp(int widthDp) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_CONTENT_WIDTH, clamp(widthDp, 180, 360)).commit();
        syncMirror();
    }

    public int getContentPreviewOverlayAlpha() {
        return clamp(preferences == null ? 18
                : preferences.getInt(KEY_CONTENT_OVERLAY_ALPHA, 18), 0, 70);
    }

    public void setContentPreviewOverlayAlpha(int percent) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_CONTENT_OVERLAY_ALPHA, clamp(percent, 0, 70)).commit();
        syncMirror();
    }

    /**
     * Whole-bubble scale in percent of the original 92dp window (60..140). All bubble
     * geometry (glass core, notification icon, corner app badge) scales together.
     */
    public int getBubbleSizePercent() {
        return clamp(preferences == null ? DEFAULT_BUBBLE_SIZE_PERCENT
                        : preferences.getInt(KEY_BUBBLE_SIZE, DEFAULT_BUBBLE_SIZE_PERCENT),
                MIN_BUBBLE_SIZE_PERCENT, MAX_BUBBLE_SIZE_PERCENT);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setBubbleSizePercent(int percent) {
        if (preferences == null) return;
        preferences.edit()
                .putInt(KEY_BUBBLE_SIZE,
                        clamp(percent, MIN_BUBBLE_SIZE_PERCENT, MAX_BUBBLE_SIZE_PERCENT))
                .commit();
        syncMirror();
    }

    /**
     * Radius of the notification icon inside the bubble, in percent of the original 20dp.
     * 100 keeps the historical look, lower values leave more glass visible around the icon and
     * higher values let the icon dominate the bubble.
     */
    public int getIconSizePercent() {
        return clamp(preferences == null ? DEFAULT_ICON_SIZE_PERCENT
                        : preferences.getInt(KEY_ICON_SIZE, DEFAULT_ICON_SIZE_PERCENT),
                MIN_ICON_SIZE_PERCENT, MAX_ICON_SIZE_PERCENT);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setIconSizePercent(int percent) {
        if (preferences == null) return;
        preferences.edit()
                .putInt(KEY_ICON_SIZE, clamp(percent, MIN_ICON_SIZE_PERCENT, MAX_ICON_SIZE_PERCENT))
                .commit();
        syncMirror();
    }

    /**
     * Size of the app badge in the bubble's lower corner, in percent of the original 16dp.
     * The badge itself always shows the app's own launcher icon.
     */
    public int getBadgeSizePercent() {
        return clamp(preferences == null ? DEFAULT_BADGE_SIZE_PERCENT
                        : preferences.getInt(KEY_BADGE_SIZE, DEFAULT_BADGE_SIZE_PERCENT),
                MIN_BADGE_SIZE_PERCENT, MAX_BADGE_SIZE_PERCENT);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public void setBadgeSizePercent(int percent) {
        if (preferences == null) return;
        preferences.edit()
                .putInt(KEY_BADGE_SIZE,
                        clamp(percent, MIN_BADGE_SIZE_PERCENT, MAX_BADGE_SIZE_PERCENT))
                .commit();
        syncMirror();
    }

    public boolean isStatusScrollEnabled() {
        return getStatusScrollMode() != STATUS_SCROLL_OFF;
    }

    public void setStatusScrollEnabled(boolean enabled) {
        if (preferences == null) return;
        setStatusScrollMode(enabled ? STATUS_SCROLL_FLUID_CLOUD : STATUS_SCROLL_OFF);
    }

    public int getStatusScrollMode() {
        if (preferences == null) return STATUS_SCROLL_FLUID_CLOUD;
        if (preferences.contains(KEY_STATUS_SCROLL_MODE)) {
            return clamp(preferences.getInt(KEY_STATUS_SCROLL_MODE,
                    STATUS_SCROLL_FLUID_CLOUD), STATUS_SCROLL_OFF, STATUS_SCROLL_FLUID_CLOUD);
        }
        // Migrate the old two-state preference: enabled previously meant the
        // native Fluid Cloud presentation, while disabled meant no ticker.
        return preferences.getBoolean(KEY_STATUS_SCROLL_ENABLED, true)
                ? STATUS_SCROLL_FLUID_CLOUD : STATUS_SCROLL_OFF;
    }

    public void setStatusScrollMode(int mode) {
        if (preferences == null) return;
        int normalized = clamp(mode, STATUS_SCROLL_OFF, STATUS_SCROLL_FLUID_CLOUD);
        preferences.edit()
                .putInt(KEY_STATUS_SCROLL_MODE, normalized)
                // Keep the legacy key in sync for older SystemUI hook builds.
                .putBoolean(KEY_STATUS_SCROLL_ENABLED, normalized != STATUS_SCROLL_OFF)
                .commit();
        syncMirror();
    }

    public int getStatusScrollRounds() {
        return clamp(preferences == null ? 1
                : preferences.getInt(KEY_STATUS_SCROLL_ROUNDS, 1), 1, 5);
    }

    public void setStatusScrollRounds(int rounds) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_STATUS_SCROLL_ROUNDS, clamp(rounds, 1, 5)).commit();
        syncMirror();
    }


    public void resetPlacement() {
        if (preferences == null) return;
        preferences.edit()
                .putInt(KEY_PORTRAIT_X, DEFAULT_PORTRAIT_X)
                .putInt(KEY_PORTRAIT_Y, DEFAULT_PORTRAIT_Y)
                .putInt(KEY_LANDSCAPE_X, DEFAULT_LANDSCAPE_X)
                .putInt(KEY_LANDSCAPE_Y, DEFAULT_LANDSCAPE_Y)
                .putInt(KEY_PORTRAIT_DOCK, 0)
                .putInt(KEY_LANDSCAPE_DOCK, 0)
                .commit();
        syncMirror();
    }

    private Set<String> copySet(String key) {
        if (preferences == null) return new HashSet<>();
        Set<String> stored = preferences.getStringSet(key, Collections.emptySet());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    private void writeSet(String key, Set<String> packages) {
        if (preferences == null) return;
        preferences.edit().putStringSet(key, new HashSet<>(packages)).commit();
        syncMirror();
    }

    private void writeBoolean(String key, boolean value) {
        if (preferences == null) return;
        preferences.edit().putBoolean(key, value).commit();
        syncMirror();
    }

    /** Keep the legacy credential-protected copy current for upgrades and older builds. */
    private void syncMirror() {
        if (preferences == null || mirrorPreferences == null
                || preferences == mirrorPreferences) return;
        try {
            Map<String, ?> values = preferences.getAll();
            SharedPreferences.Editor editor = mirrorPreferences.edit().clear();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                putValue(editor, entry.getKey(), entry.getValue());
            }
            editor.commit();
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
