package io.github.freeform.bubble;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Narrow bridge used by the injected SystemUI process to persist dragged positions. */
public final class BubbleConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.freeform.bubble.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_SET_POSITION = "set_position";
    public static final String METHOD_GET_POSITION = "get_position";
    public static final String METHOD_CHECK_NOTIFICATION = "check_notification";
    public static final String METHOD_PREPARE_FREEFORM_LAUNCH = "prepare_freeform_launch";
    public static final String METHOD_TAKE_FREEFORM_LAUNCH = "take_freeform_launch";
    public static final String METHOD_GET_BUBBLE_TIMEOUT = "get_bubble_timeout";
    public static final String METHOD_APPEND_RUNTIME_LOG = "append_runtime_log";
    public static final String METHOD_READ_RUNTIME_LOG = "read_runtime_log";
    public static final String METHOD_CLEAR_RUNTIME_LOG = "clear_runtime_log";
    /** Shared result keys so the injected process and this provider cannot drift apart. */
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_CONFIGURATION_PENDING = "configurationPending";
    public static final String EXTRA_HAS_PERSISTED_SETTINGS = "hasPersistedSettings";
    /** Nested bundle carrying the preference store with its canonical keys. */
    public static final String EXTRA_PREFERENCES = "preferences";
    private static final String RUNTIME_LOG_FILE = "bubble_notify_runtime.log";
    private static final int MAX_LOG_BYTES = 192 * 1024;
    private static final long FREEFORM_TICKET_TIMEOUT_MS = 3_000L;
    private static String pendingFreeformPackage;
    private static long pendingFreeformExpiry;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        BubbleConfig config = new BubbleConfig(contextOrThrow());
        if (METHOD_GET_BUBBLE_TIMEOUT.equals(method)) {
            boolean hasPersistedSettings = config.hasPersistedSettings();
            // During direct boot, an empty device-protected store means the real
            // credential-protected settings may still be inaccessible. Do not
            // report defaults as a valid configuration to SystemUI.
            if (!hasPersistedSettings && !isUserUnlocked()) {
                result.putBoolean(EXTRA_SUCCESS, false);
                result.putBoolean(EXTRA_CONFIGURATION_PENDING, true);
                return result;
            }
            result.putBoolean(EXTRA_SUCCESS, true);
            result.putBoolean(EXTRA_CONFIGURATION_PENDING, false);
            result.putBoolean(EXTRA_HAS_PERSISTED_SETTINGS, hasPersistedSettings);
            // Publish the exact on-device preference schema. The injected SystemUI process
            // turns this bundle back into a SharedPreferences, so every stored key (bubble
            // position, content-preview switches, timeouts, app lists, ...) has to travel
            // with its original name and type. Earlier revisions exported human-readable
            // aliases only ("timeoutSeconds", "contentPreviewSuppressBanner", ...), which are
            // not the keys BubbleConfig reads, so SystemUI silently fell back to defaults
            // for almost every option (for example the bubble position always at the
            // bottom-right and the banner suppression always off).
            Bundle preferences = new Bundle();
            putAllPreferences(preferences, config.rawPreferences());
            result.putBundle(EXTRA_PREFERENCES, preferences);
            // Keep the legacy aliases for diagnostics and older helper components.
            result.putBoolean("enabled", config.isEnabled());
            result.putBoolean("autoDismiss", config.isBubbleAutoDismissEnabled());
            result.putInt("timeoutSeconds", config.getTimeoutSeconds());
            result.putBoolean("dimOnTimeout", config.shouldDimBubbleOnTimeout());
            result.putBoolean("awakePrompt", config.isImmediateSuggestionsEnabled()
                    && config.isScreenAwakePromptEnabled());
            result.putBoolean("clearStatusOnDownSwipe",
                    config.isClearStatusOnDownSwipeEnabled());
            result.putBoolean("includeFluidCloud",
                    config.isIncludeFluidCloudNotificationsEnabled());
            result.putBoolean("immediate_suggestions", config.isImmediateSuggestionsEnabled());
            result.putBoolean("suggest_flashlight", config.isFlashlightSuggestionEnabled());
            result.putBoolean("suggest_music", config.isMusicSuggestionEnabled());
            result.putBoolean("suggest_app_install", config.isAppInstallSuggestionEnabled());
            result.putBoolean("suggest_clipboard", config.isClipboardSuggestionEnabled());
            result.putBoolean("contentPreviewEnabled", config.isContentPreviewEnabled());
            result.putBoolean("contentPreviewSuppressBanner",
                    config.isContentPreviewSuppressBanner());
            result.putBoolean("previewEdgeOnly", config.isContentPreviewEdgeOnly());
            result.putInt("contentPreviewPosition", config.getContentPreviewPosition());
            result.putInt("content_preview_alpha", config.getContentPreviewAlpha());
            result.putInt("contentPreviewTimeout", config.getContentPreviewTimeoutSeconds());
            result.putBoolean("contentPreviewDynamicTimeout", config.isContentPreviewDynamicTimeout());
            result.putBoolean("contentPreviewAutoWidth", config.isContentPreviewAutoWidth());
            result.putInt("contentPreviewWidthDp", config.getContentPreviewWidthDp());
            result.putInt("content_preview_overlay_alpha", config.getContentPreviewOverlayAlpha());
            result.putBoolean("status_scroll_enabled", config.isStatusScrollEnabled());
            result.putInt("status_scroll_mode", config.getStatusScrollMode());
            result.putInt("status_scroll_rounds", config.getStatusScrollRounds());
            result.putStringArrayList("status_scroll_whitelist",
                    new ArrayList<>(config.getStatusScrollWhitelist()));
            result.putStringArrayList("whitelist", new ArrayList<>(config.getWhitelist()));
            result.putStringArrayList("blacklist", new ArrayList<>(config.getBlacklist()));
            return result;
        }
        if (!isTrustedCaller()) {
            result.putBoolean(EXTRA_SUCCESS, false);
            return result;
        }
        if (METHOD_CHECK_NOTIFICATION.equals(method) && extras != null) {
            String sourcePackage = extras.getString("package");
            String foregroundPackage = extras.getString("foreground");
            boolean allowed = sourcePackage != null
                    && config.acceptsNotificationFrom(sourcePackage)
                    && !config.isBlacklisted(sourcePackage)
                    && !config.suppressesWhileForeground(foregroundPackage);
            result.putBoolean(EXTRA_SUCCESS, true);
            result.putBoolean("allowed", allowed);
            return result;
        }
        if (METHOD_GET_POSITION.equals(method) && extras != null) {
            boolean landscape = extras.getBoolean("landscape");
            result.putBoolean(EXTRA_SUCCESS, true);
            result.putInt("x", config.getPositionX(landscape));
            result.putInt("y", config.getPositionY(landscape));
            result.putInt("dock", config.getDockEdge(landscape));
            return result;
        }
        if (METHOD_PREPARE_FREEFORM_LAUNCH.equals(method) && extras != null) {
            String packageName = extras.getString("package");
            if (packageName == null || packageName.isEmpty()) {
                result.putBoolean(EXTRA_SUCCESS, false);
                return result;
            }
            synchronized (BubbleConfigProvider.class) {
                pendingFreeformPackage = packageName;
                pendingFreeformExpiry = android.os.SystemClock.elapsedRealtime()
                        + FREEFORM_TICKET_TIMEOUT_MS;
            }
            result.putBoolean(EXTRA_SUCCESS, true);
            return result;
        }
        if (METHOD_TAKE_FREEFORM_LAUNCH.equals(method) && extras != null) {
            String targetPackage = extras.getString("package");
            boolean matches;
            synchronized (BubbleConfigProvider.class) {
                long now = android.os.SystemClock.elapsedRealtime();
                matches = targetPackage != null && targetPackage.equals(pendingFreeformPackage)
                        && now <= pendingFreeformExpiry;
                if (matches || now > pendingFreeformExpiry) {
                    pendingFreeformPackage = null;
                    pendingFreeformExpiry = 0L;
                }
            }
            result.putBoolean(EXTRA_SUCCESS, true);
            result.putBoolean("forceFreeform", matches);
            return result;
        }
        if (METHOD_APPEND_RUNTIME_LOG.equals(method) && extras != null) {
            appendRuntimeLog(extras.getString("line", ""));
            result.putBoolean(EXTRA_SUCCESS, true);
            return result;
        }
        if (METHOD_READ_RUNTIME_LOG.equals(method)) {
            result.putBoolean(EXTRA_SUCCESS, true);
            result.putString("log", readRuntimeLog());
            return result;
        }
        if (METHOD_CLEAR_RUNTIME_LOG.equals(method)) {
            File file = new File(contextOrThrow().getFilesDir(), RUNTIME_LOG_FILE);
            if (file.exists()) file.delete();
            result.putBoolean(EXTRA_SUCCESS, true);
            return result;
        }
        if (!METHOD_SET_POSITION.equals(method) || extras == null) {
            result.putBoolean(EXTRA_SUCCESS, false);
            return result;
        }
        boolean landscape = extras.getBoolean("landscape");
        config.setPosition(landscape, extras.getInt("x"), extras.getInt("y"));
        config.setDockEdge(landscape, extras.getInt("dock"));
        // Persisted positions must reach the injected process immediately: it holds an
        // in-memory copy of the configuration and only refreshes it on change notifications.
        notifyConfigurationChanged();
        result.putBoolean(EXTRA_SUCCESS, true);
        return result;
    }

    /** Copies the stored preferences with their canonical keys and value types. */
    private static void putAllPreferences(Bundle out, android.content.SharedPreferences prefs) {
        if (out == null || prefs == null) return;
        Map<String, ?> values;
        try {
            values = prefs.getAll();
        } catch (Throwable ignored) {
            return;
        }
        if (values == null) return;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;
            try {
                if (value instanceof Boolean) {
                    out.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    out.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    out.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    out.putFloat(key, (Float) value);
                } else if (value instanceof String) {
                    out.putString(key, (String) value);
                } else if (value instanceof Set) {
                    @SuppressWarnings("unchecked")
                    Set<String> set = (Set<String>) value;
                    out.putStringArrayList(key, new ArrayList<>(set));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyConfigurationChanged() {
        try {
            contextOrThrow().getContentResolver().notifyChange(URI, null);
        } catch (Throwable ignored) {
        }
    }


    private boolean isTrustedCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid() || callingUid == 1000) return true;
        PackageManager packageManager = contextOrThrow().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String packageName : packages) {
            if ("com.android.systemui".equals(packageName)
                    || "com.oplus.systemui".equals(packageName)) return true;
        }
        return false;
    }

    private boolean isUserUnlocked() {
        try {
            UserManager userManager = contextOrThrow().getSystemService(UserManager.class);
            return userManager == null || userManager.isUserUnlocked();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private synchronized void appendRuntimeLog(String line) {
        if (line == null || line.isEmpty()) return;
        File file = new File(contextOrThrow().getFilesDir(), RUNTIME_LOG_FILE);
        try {
            if (file.length() > MAX_LOG_BYTES) {
                file.delete();
            }
            try (FileOutputStream output = contextOrThrow().openFileOutput(
                    RUNTIME_LOG_FILE, android.content.Context.MODE_APPEND)) {
                output.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized String readRuntimeLog() {
        File file = new File(contextOrThrow().getFilesDir(), RUNTIME_LOG_FILE);
        if (!file.exists()) return "No runtime logs captured yet.\n";
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), MAX_LOG_BYTES)];
            int count = input.read(bytes);
            return count <= 0 ? "" : new String(bytes, 0, count,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "Unable to read runtime log.\n";
        }
    }

    private android.content.Context contextOrThrow() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context unavailable");
        return context;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
