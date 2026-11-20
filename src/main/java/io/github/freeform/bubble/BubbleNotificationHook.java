package io.github.freeform.bubble;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.database.ContentObserver;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.graphics.PixelFormat;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** libxposed API 101 entry point injected into AOSP and ColorOS SystemUI hosts. */
public final class BubbleNotificationHook extends XposedModule {
    private static final String TAG = "BubbleNotify";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String OP_SYSTEM_UI = "com.oplus.systemui";
    private static final String MODULE_PACKAGE = "io.github.freeform.bubble";
    /**
     * Preference file in SystemUI's own device-protected storage mirroring the module
     * configuration. ColorOS refuses to cold-start the module app for a provider request
     * (OplusAppStartupManager: "prevent start ... by contentprovider ... scenePriority = 0"),
     * so without this cache every reboot runs on defaults until the module app is opened.
     */
    private static final String BOOT_CONFIG_CACHE = "bubble_notify_boot_cache";
    private static final String HEADS_UP_ENABLED_SETTING = "heads_up_notifications_enabled";
    private static final String ACTION_DIM_TOUCH =
            "io.github.freeform.bubble.action.DIM_SCREEN_TOUCHED";
    /**
     * Sent by the system_server clipboard hook with a classified clipboard item (phone, express,
     * link, shopping link, app link or address). SystemUI's own clipboard listener does not fire
     * on Android 10+ (clipboard access is focus-gated), so the clipboard is observed in
     * system_server instead, and only the actionable token is forwarded.
     */
    private static final String ACTION_CLIPBOARD_ITEM =
            "io.github.freeform.bubble.action.CLIPBOARD_ITEM";
    private static final String ACTION_IMMEDIATE_SUGGESTION =
            "io.github.freeform.bubble.action.IMMEDIATE_SUGGESTION";
    /** Swiping the keep-awake notification away in the shade also drops its bubble. */
    private static final String ACTION_IMMEDIATE_SUGGESTION_DISMISS =
            "io.github.freeform.bubble.action.DISMISS_SUGGESTION";
    /**
     * Channel of the "临时常亮" offer notification. The keep-awake offer is the one suggestion
     * that is mirrored into the status bar like a regular notification: it shows up in the shade,
     * can be tapped to engage, and swiping it away drops the bubble too.
     */
    private static final String KEEP_AWAKE_OFFER_CHANNEL_ID = "bubble_keep_awake_offer";
    private static final int KEEP_AWAKE_OFFER_NOTIFICATION_ID = 0x42554b41;
    /** Key of the keep-awake offer inside the bubble queue. */
    private static final String KEEP_AWAKE_SUGGESTION_ID = "keep_awake";
    private static final String KEEP_AWAKE_CHANNEL_ID = "bubble_keep_awake_important";
    private static final int KEEP_AWAKE_NOTIFICATION_ID = 0x4241574b;
    private static final long DUPLICATE_WINDOW_MS = 1200L;
    /**
     * ColorOS multi-app hint read by OplusMultiAppManagerService.getCachedUserId():
     * it pins the launch to one app instance and skips the multi-app chooser.
     */
    private static final String MULTI_APP_CACHED_USER_ID_EXTRA =
            "oplus.intent.extra.cached_userId";
    /** How many queued bubbles the expanded fan shows at once. */
    private static final int MAX_FAN_ITEMS = 6;
    /** ColorOS 17's native Live Alert notification transport. */
    private static final String LIVE_ALERT_NOTIFICATION = "live_alert_notification";
    private static final String LIVE_ALERT_NOTIFICATION_POST = "live_alert_notification_post";
    private static final String LIVE_ALERT_NOTIFICATION_REMOVE = "live_alert_notification_remove";
    private static final String LIVE_ALERT_NOTIFICATIONS_KEY = "notifications";
    private static final long LIVE_ALERT_DUPLICATE_WINDOW_MS = 2_000L;
    private static final int START_SWITCHES_CANCELED = 100;
    private static final int USER_ACTIVITY_EVENT_TOUCH = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> recentNotifications = new HashMap<>();
    private final Map<String, Runnable> immediateSuggestionActions = new HashMap<>();
    private final Map<String, StatusBarNotification> immediateSuggestionTickers = new HashMap<>();
    /** Deduplicates the initial and post batches emitted by the Live Alert provider. */
    private final Map<String, Long> recentNativeLiveAlertNotifications = new HashMap<>();
    private final ArrayDeque<QueuedNotification> notificationQueue = new ArrayDeque<>();
    private boolean statusCloudCollapsed;
    private int lastStatusScrollMode = -1;
    /** SystemUI 的 heads-up 原生决策：notification key -> 是否展示了横幅(true=横幅已展示/被模块气泡接管)。 */
    private final Map<String, Boolean> headsUpByKey = new HashMap<>();
    /** Native SystemUI Heads-up result, kept separate from module suppression. */
    private final Map<String, Boolean> nativeHeadsUpByKey = new HashMap<>();
    /** 当前状态栏滚动正在显示的来源 key，用于避免重复触发。 */
    private String tickingKey;
    private StatusBarNotification pendingTickerSbn;
    private Notification pendingTickerNotification;
    private NotificationPayload pendingTickerPayload;

    private View lastSearchedRoot;
    private int statusBarRetries;
    private Context systemUiContext;
    private Context runtimeLogContext;
    private ClassLoader systemUiClassLoader;
    private String systemUiPackageName = SYSTEM_UI;
    private Object systemUiNotificationListener;
    private BubbleConfig config;
    private BubbleView currentBubble;
    private QueuedNotification currentBubbleEntry;
    private BubbleView transitioningBubble;
    private NotificationPreviewView currentPreview;
    /**
     * While a content preview is visible, keep re-anchoring it to the bubble. The bubble
     * can still move a few dp after the preview window is created (position settle, dock
     * snap, IME, resize), and a stale anchor is visible as a vertical misalignment.
     */
    private final Runnable previewAnchorTick = new Runnable() {
        @Override
        public void run() {
            NotificationPreviewView preview = currentPreview;
            BubbleView bubble = currentBubble;
            if (preview == null || bubble == null || !bubble.isAttached()) return;
            preview.updateAnchor(bubble.getPreviewAnchorBounds());
            mainHandler.postDelayed(this, 150L);
        }
    };
    private StatusScrollOverlay statusScrollOverlay;
    private FluidCloudNotificationOverlay fluidCloudNotificationOverlay;
    private QueuedNotification activeTickerNotification;
    private ViewGroup statusBarRoot;
    private int pendingTickerAttachAttempts;
    private BubbleStackOverlay expandedStack;
    private static final int UP_SWIPE_NONE = 0;
    private static final int UP_SWIPE_STACK = 1;
    private static final int UP_SWIPE_PREVIEW = 2;
    /** Idle time after which a cycled preview returns to the head notification. */
    private static final long UP_SWIPE_TIMEOUT_MS = 4_000L;
    /** Notifications that must never be cleared by the "clear on downward swipe" setting. */
    private static final String[] PROTECTED_NOTIFICATION_PACKAGES = {
            "android",
            "com.android.incallui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.android.contacts",
            "com.oplus.incallui",
            "com.oplus.phone",
            "com.oplus.dialer",
            "com.android.bluetooth",
            "com.android.providers.downloads",
            "com.android.systemui",
            "com.oplus.systemui"
    };
    /** 上滑链状态：NONE=未激活，STACK=多通知第一步已弹堆叠，PREVIEW=正在依次展开内容浮层。 */
    private int upSwipeState = UP_SWIPE_NONE;
    private List<QueuedNotification> upSwipeList;
    private int upSwipeIndex;
    private boolean initialized;
    private boolean bootstrapInstalled;
    private boolean powerHooksInstalled;
    private boolean clipboardHooksInstalled;
    private String lastClipboardItem;
    private long lastClipboardItemAt;
    private boolean notificationPipelineInstalled;
    private boolean nativeFluidCloudPipelineInstalled;
    private boolean statusBarTickerInstalled;
    private boolean headsUpSuppressionInstalled;
    private boolean displayReceiverRegistered;
    private boolean awakePromptReceiverRegistered;
    private boolean clipboardReceiverRegistered;
    private boolean configObserverRegistered;
    private boolean initializationRetryScheduled;
    private int initializationAttempts;
    private int configurationRetryAttempts;
    private boolean lastRefreshWasCached;
    private int headsUpSuppressionAttempts;
    private boolean headsUpModernHookInstalled;
    private boolean headsUpLegacyHookInstalled;
    /** SystemUI-owned device-protected copy of the last successfully read configuration. */
    private SharedPreferences bootConfigCache;
    private boolean configurationFromCache;
    private boolean providerFailureLogged;
    private volatile boolean systemServerScreenDim;
    private volatile long lastSystemServerDimBroadcastAt;
    private Method powerGroupPolicyMethod;
    private Method powerGroupIdMethod;
    private View awakeGuardView;
    private PowerManager.WakeLock awakeWakeLock;
    private ComponentName awakeAnchor;
    private long lastAwakePromptAt;
    private int lastBubbleX;
    private int lastBubbleY;
    private int lastBubbleDock;
    private boolean hasRuntimeBubblePosition;
    private boolean runtimeBubbleLandscape;
    private QueuedNotification suppressPreviewForEntry;
    private boolean screenSuspended;
    private ImmediateSuggestionController immediateSuggestionController;
    private boolean immediateSuggestionReceiverRegistered;
    private boolean runtimeLogProviderAvailable;

    private final BroadcastReceiver immediateSuggestionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_IMMEDIATE_SUGGESTION_DISMISS.equals(intent.getAction())) {
                String dismissedKey = intent.getStringExtra("suggestion_key");
                if (dismissedKey != null) {
                    mainHandler.post(() -> dismissImmediateSuggestionKey(dismissedKey));
                }
                return;
            }
            if (!ACTION_IMMEDIATE_SUGGESTION.equals(intent.getAction())) return;
            String key = intent.getStringExtra("suggestion_key");
            Runnable action = immediateSuggestionActions.get(key);
            if (action == null) return;
            try {
                action.run();
                moduleLog(Log.INFO, "fluid cloud suggestion action executed: " + key);
            } catch (Throwable error) {
                moduleLog(Log.ERROR, "fluid cloud suggestion action failed: " + key, error);
            } finally {
                dismissImmediateSuggestionKey(key);
            }
        }
    };

    private final Runnable awakeActivityCheckAction = this::checkAwakeActivity;

    private final ContentObserver configObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            runtimeLogProviderAvailable = true;
            refreshConfiguration();
            initializeImmediateSuggestions();
            updateClipboardReceiver();
            // A tightened whitelist/blacklist must also apply to notifications that are still
            // waiting in the queue instead of popping up later with the old rules.
            pruneQueuedNotifications();
            int statusScrollMode = readStatusScrollMode();
            if (config != null && activeTickerNotification != null
                    && !config.acceptsStatusScrollFrom(activeTickerNotification.packageName)) {
                removeStatusTickerSurfaces();
            }
            if (config != null && fluidCloudNotificationOverlay != null
                    && !fluidCloudNotificationOverlay.isSourceAllowed(config)) {
                removeStatusTickerSurfaces();
            }
            if (lastStatusScrollMode != -1 && lastStatusScrollMode != statusScrollMode) {
                removeStatusTickerSurfaces();
            }
            lastStatusScrollMode = statusScrollMode;
            BubbleView bubble = currentBubble;
            if (bubble != null) {
                // Apply freshly chosen bubble / icon / badge sizes to the bubble on screen.
                bubble.setBubbleSizePercent(config.getBubbleSizePercent());
                bubble.setIconScalePercent(config.getIconSizePercent());
                bubble.setBadgeScalePercent(config.getBadgeSizePercent());
                if (screenSuspended || isDeviceLocked()) {
                    bubble.updateTimeout(0, false);
                } else {
                    BubbleTimeoutPolicy policy = timeoutPolicyForEntry(notificationQueue.peekFirst());
                    bubble.updateTimeout(policy.seconds, policy.dimOnTimeout);
                }
            }
            if (statusScrollMode == BubbleConfig.STATUS_SCROLL_OFF) {
                removeStatusTickerSurfaces();
            }
            NotificationPreviewView preview = currentPreview;
            if (preview != null) {
                preview.updateFinalAlpha(config.getContentPreviewAlpha());
                preview.updateOverlayAlpha(config.getContentPreviewOverlayAlpha());
            }
        }
    };

    private final BroadcastReceiver displayReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mainHandler.post(() -> {
                    screenSuspended = true;
                    disableKeepScreenAwake();
                    dismissCurrentPreview();
                    if (statusScrollOverlay != null) statusScrollOverlay.removeImmediately();
                    if (fluidCloudNotificationOverlay != null) {
                        fluidCloudNotificationOverlay.removeImmediately();
                    }
                    BubbleStackOverlay stack = expandedStack;
                    expandedStack = null;
                    if (stack != null) stack.dismiss();
                    // The keep-awake offer is only meaningful while the screen is on.
                    dismissKeepAwakeSuggestion();
                    BubbleView bubble = currentBubble;
                    if (bubble != null) {
                        // Do not hide the bubble: a message that arrives while the display is
                        // off must already be on screen the moment the screen wakes up, and
                        // hiding it depended on receiving ACTION_SCREEN_ON again. Only pause
                        // auto-dismiss until the screen becomes interactive.
                        bubble.setStackExpanded(false);
                        bubble.setVisibility(View.VISIBLE);
                        bubble.updateTimeout(0, false);
                    }
                });
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                mainHandler.post(() -> {
                    refreshConfiguration();
                    resumeBubblesAfterUnlock();
                });
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                mainHandler.post(() -> {
                    // During direct boot initialization may have been deferred because the
                    // credential-protected settings were not readable yet. Re-enter the full
                    // initialization path after unlock so hooks and observers are installed.
                    if (!initialized && systemUiContext != null && systemUiClassLoader != null) {
                        initialize(systemUiContext, systemUiClassLoader);
                    } else {
                        refreshConfiguration();
                    }
                    mainHandler.postDelayed(
                            () -> rescanActiveNotifications(systemUiNotificationListener), 500L);
                });
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mainHandler.post(() -> {
                    resumeBubblesAfterScreenOn();
                });
            } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                mainHandler.post(BubbleNotificationHook.this::dismissCurrentPreview);
            }
        }
    };

    private final BroadcastReceiver awakePromptReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_DIM_TOUCH.equals(intent.getAction())
                    || !isScreenAwakePromptEnabled()) return;
            mainHandler.post(() -> {
                long now = android.os.SystemClock.elapsedRealtime();
                // Suppress duplicate touch broadcasts, but let subsequent offers replace the old one.
                if (now - lastAwakePromptAt < 350L) return;
                if (isDeviceLocked() || !isScreenInteractive()) return;
                lastAwakePromptAt = now;
                moduleLog(Log.INFO, "dim-screen touch received; showing keep-awake prompt");
                showKeepScreenAwakePrompt();
            });
        }
    };

    /**
     * Receives classified clipboard items from system_server. Registered only while
     * "识别剪贴板内容" is enabled, so clipboard text cannot reach this process otherwise.
     */
    private final BroadcastReceiver clipboardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_CLIPBOARD_ITEM.equals(intent.getAction())) return;
            int[] kinds = intent.getIntArrayExtra("kinds");
            String[] values = intent.getStringArrayExtra("values");
            String[] displays = intent.getStringArrayExtra("displays");
            String[] apps = intent.getStringArrayExtra("apps");
            if (kinds == null || values == null) {
                int kind = intent.getIntExtra("kind", 0);
                String value = intent.getStringExtra("value");
                String display = intent.getStringExtra("display");
                String appPackage = intent.getStringExtra("appPackage");
                mainHandler.post(() -> showClipboardSuggestion(kind, value, display, appPackage));
                return;
            }
            mainHandler.post(() -> showClipboardSuggestions(kinds, values, displays, apps));
        }
    };

    private boolean isScreenInteractive() {
        try {
            PowerManager powerManager = systemUiContext == null
                    ? null : systemUiContext.getSystemService(PowerManager.class);
            return powerManager == null || powerManager.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * True while an app is really in front of the user. With the display off or the keyguard
     * covering the screen the last foreground task is stale, so it must neither silence
     * bubbles nor swallow the banner decision for incoming messages.
     */
    private boolean isForegroundVisible() {
        if (systemUiContext == null) return false;
        return isScreenInteractive() && !isDeviceLocked();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!isSystemUiPackage(param.getPackageName()) || !param.isFirstPackage()
                || bootstrapInstalled) return;
        try {
            installApplicationBootstrap(param.getDefaultClassLoader());
            bootstrapInstalled = true;
            moduleLog(Log.INFO, "SystemUIApplication bootstrap installed at package load");
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "early bootstrap installation failed", error);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        if (bootstrapInstalled || !isSystemUiPackage(param.getPackageName())) return;

        try {
            installApplicationBootstrap(param.getClassLoader());
            bootstrapInstalled = true;
            moduleLog(Log.INFO, "SystemUIApplication bootstrap installed");
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "bootstrap installation failed", error);
        }
    }

    @Override
    public void onSystemServerStarting(
            XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader loader = param.getClassLoader();
        moduleLog(Log.INFO, "system_server entry received");
        installPowerManagerHooks(loader);
        installClipboardHooks(loader);
    }

    @SuppressLint("PrivateApi")
    private void installApplicationBootstrap(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> applicationClass = null;
        ReflectiveOperationException lastError = null;
        String[] applicationNames = {
                // ColorOS 17: the abstract SystemUIApplication moved under
                // com.android.systemui.application; the concrete implementation
                // owns the real onCreate lifecycle callback.
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.application.SystemUIApplication",
                "com.oplus.systemui.SystemUIApplication",
                "com.oplus.systemui.OplusSystemUIApplication"
        };
        for (String name : applicationNames) {
            try {
                applicationClass = Class.forName(name, false, loader);
                break;
            } catch (ClassNotFoundException error) {
                lastError = error;
            }
        }
        if (applicationClass == null) {
            if (lastError != null) throw lastError;
            throw new ClassNotFoundException("No compatible SystemUIApplication class found");
        }
        Method onCreate = applicationClass.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        hook(onCreate).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                Object application = chain.getThisObject();
                if (application instanceof Context) {
                    mainHandler.post(() -> initialize((Context) application, loader));
                } else {
                    moduleLog(Log.ERROR, "SystemUIApplication hook returned no Context");
                }
                return result;
            }
        });
    }

    private void initialize(Context application, ClassLoader loader) {
        if (initialized) return;
        try {
            systemUiContext = application.getApplicationContext();
            runtimeLogContext = systemUiContext;
            systemUiClassLoader = loader;
            if (isSystemUiPackage(systemUiContext.getPackageName())) {
                systemUiPackageName = systemUiContext.getPackageName();
            }
            cancelKeepScreenAwakeNotification();
            // Register this before reading configuration so unlock and display state changes
            // are never missed during early SystemUI startup.
            if (!displayReceiverRegistered) {
                registerDisplayReceiver();
                displayReceiverRegistered = true;
            }
            config = loadModuleConfig();
            boolean configurationPending = config == null;
            if (config == null) {
                // Keep safe defaults for the first callbacks, but continue
                // retrying until the persisted settings become readable.
                moduleLog(Log.WARN, "module configuration not readable yet; using defaults and "
                        + "retrying in the background (userUnlocked=" + isUserUnlocked() + ")");
                config = new BubbleConfig(new InMemorySharedPreferences());
            } else {
                moduleLog(Log.INFO, "configuration loaded; userUnlocked=" + isUserUnlocked()
                        + ", persisted=" + config.hasPersistedSettings()
                        + ", cached=" + configurationFromCache);
                // Seed the boot cache with whatever live source answered first.
                if (!configurationFromCache) cacheConfiguration(config);
            }
            lastStatusScrollMode = config.getStatusScrollMode();
            PowerManager powerManager = systemUiContext.getSystemService(PowerManager.class);
            screenSuspended = powerManager != null && !powerManager.isInteractive();
            int hookCount = notificationPipelineInstalled ? 1 : hookNotificationPipeline(loader);
            if (hookCount > 0) notificationPipelineInstalled = true;
            int nativeFluidCloudHookCount = nativeFluidCloudPipelineInstalled
                    ? 1 : hookNativeFluidCloudPipeline(loader);
            if (nativeFluidCloudHookCount > 0) nativeFluidCloudPipelineInstalled = true;
            if (!statusBarTickerInstalled) {
                installStatusBarTickerHook(loader);
                statusBarTickerInstalled = true;
            }
            if (!headsUpSuppressionInstalled) {
                // Only mark the suppression pipeline as installed when at least one
                // decision hook was actually placed. On ColorOS the provider classes may
                // appear in a plugin class loader a little later; retrying keeps the
                // "hide the banner while the content preview is shown" option working.
                if (installHeadsUpSuppression(loader) > 0) {
                    headsUpSuppressionInstalled = true;
                } else {
                    scheduleHeadsUpSuppressionRetry(loader);
                }
            }
            if (hookCount == 0) {
                scheduleInitializationRetry(application, loader,
                        new ClassNotFoundException("No compatible SystemUI notification callback found"));
                return;
            }
            if (!configObserverRegistered) {
                systemUiContext.getContentResolver().registerContentObserver(
                        BubbleConfigProvider.URI, false, configObserver);
                configObserverRegistered = true;
            }
            if (!awakePromptReceiverRegistered) {
                registerAwakePromptReceiver();
                awakePromptReceiverRegistered = true;
            }
            updateClipboardReceiver();
            initialized = true;
            initializationAttempts = 0;
            if (configurationPending || configurationFromCache) scheduleConfigurationRetry();
            // Camera, audio and clipboard callbacks are not needed on SystemUI's critical
            // startup path. Register them after the status bar has had time to settle.
            mainHandler.postDelayed(this::initializeImmediateSuggestions, 3_000L);
            mainHandler.postDelayed(() -> rescanActiveNotifications(systemUiNotificationListener),
                    1_200L);
            moduleLog(Log.INFO, "ready; notification hooks=" + hookCount
                    + ", native Live Alert hooks=" + nativeFluidCloudHookCount);
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "initialization failed", error);
            scheduleInitializationRetry(application, loader, error);
        }
    }

    private void scheduleInitializationRetry(Context application, ClassLoader loader,
                                              Throwable error) {
        if (initialized || initializationRetryScheduled) return;
        initializationRetryScheduled = true;
        long delay = Math.min(10_000L, 800L + initializationAttempts * 500L);
        initializationAttempts++;
        mainHandler.postDelayed(() -> {
            initializationRetryScheduled = false;
            initialize(application, loader);
        }, delay);
        moduleLog(Log.WARN, "SystemUI initialization retry scheduled in " + delay + "ms", error);
    }

    private void refreshConfiguration() {
        try {
            BubbleConfig loaded = loadModuleConfig();
            if (loaded == null) {
                moduleLog(Log.DEBUG, "configuration unavailable; keeping current settings");
                scheduleConfigurationRetry();
                return;
            }
            config = loaded;
            // A configuration change may carry a newly stored bubble position (placement
            // editor or a drag persisted through the provider). Drop the absolute runtime
            // position so the next bubble resolves the stored percentage instead of replaying
            // the previous window coordinates, which made position changes look ignored.
            hasRuntimeBubblePosition = false;
            if (!configurationFromCache) {
                configurationRetryAttempts = 0;
                // Keep the boot-time copy in sync with every live read.
                cacheConfiguration(loaded);
            }
            // Report the source only when it changes, so a blocked provider does not fill the
            // log with one "cached=true" line per poll.
            boolean sourceChanged = configurationFromCache != lastRefreshWasCached;
            lastRefreshWasCached = configurationFromCache;
            moduleLog(sourceChanged ? Log.INFO : Log.DEBUG, "configuration refreshed; statusMode="
                    + loaded.getStatusScrollMode() + "; cached=" + configurationFromCache);
            if (configurationFromCache) {
                // The cache is only a bootstrap copy: keep polling (with backoff) so the live
                // provider values replace it as soon as the module app can answer.
                scheduleConfigurationRetry();
            }
        } catch (Throwable error) {
            moduleLog(Log.WARN, "configuration refresh deferred", error);
            scheduleConfigurationRetry();
        }
    }

    private void scheduleConfigurationRetry() {
        if (!initialized || initializationRetryScheduled) return;
        initializationRetryScheduled = true;
        // Keep retrying until the live store becomes readable, but back off so a device whose
        // provider is blocked (ColorOS) is not hammered: while running from the boot cache a
        // one-minute poll is plenty, because any real settings change also notifies the
        // ContentObserver in this process.
        long base = configurationFromCache ? 5_000L : 2_000L;
        long step = configurationFromCache ? 5_000L : 1_000L;
        long cap = configurationFromCache ? 60_000L : 10_000L;
        long delay = Math.min(cap, base + configurationRetryAttempts * step);
        configurationRetryAttempts++;
        mainHandler.postDelayed(() -> {
            initializationRetryScheduled = false;
            refreshConfiguration();
        }, delay);
    }

    /** Insert the ticker into SystemUI's status-bar hierarchy instead of a separate overlay window. */
    private void installStatusBarTickerHook(ClassLoader loader) {
        try {
            String[] candidates = {
                    "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                    "com.oplus.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                    "com.oplus.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentEx",
                    "com.oplus.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentExImpl"
            };
            boolean fragmentHooked = false;
            for (String name : candidates) {
                Class<?> cls;
                try { cls = Class.forName(name, false, loader); } catch (Throwable ignored) { continue; }
                for (Method method : cls.getDeclaredMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (!"onViewCreated".equals(method.getName()) || types.length == 0
                            || !View.class.isAssignableFrom(types[0])) continue;
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && args.get(0) instanceof View) {
                            mainHandler.post(() -> attachStatusBarTicker((View) args.get(0)));
                        }
                    return result;
                    });
                    moduleLog(Log.INFO, "status-bar ticker hook installed: " + name);
                    fragmentHooked = true;
                    break;
                }
            }
            // Some ColorOS revisions create the collapsed status bar through
            // onCreateView and never expose the base fragment callback to hooks.
            for (String name : candidates) try {
                Class<?> cls = Class.forName(name, false, loader);
                for (Method method : cls.getDeclaredMethods()) {
                    if (!"onCreateView".equals(method.getName()) || !View.class.isAssignableFrom(method.getReturnType())) continue;
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof View) mainHandler.post(() -> attachStatusBarTicker((View) result));
                        return result;
                    });
                    moduleLog(Log.INFO, "status-bar onCreateView hook installed: " + name);
                    break;
                }
            } catch (Throwable ignored) { }
            // Always hook the concrete status-bar view too: it may be inflated
            // before the fragment callback when SystemUI restores its view state.
            String[] viewNames = {
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                    "com.oplus.systemui.statusbar.phone.PhoneStatusBarView",
                    "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarView",
                    "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarViewExImpl"
            };
            for (String viewName : viewNames) {
                try {
                    Class<?> phoneView = Class.forName(viewName, false, loader);
                    Method inflate = phoneView.getDeclaredMethod("onFinishInflate");
                    inflate.setAccessible(true);
                    hook(inflate).intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof View) mainHandler.post(() -> attachStatusBarTicker((View) self));
                        return result;
                    });
                    moduleLog(Log.INFO, "status-bar view hook installed: " + viewName);
                } catch (Throwable ignored) { }
            }
            String[] windowNames = {
                    "com.android.systemui.statusbar.window.StatusBarWindowView",
                    "com.oplus.systemui.statusbar.window.StatusBarWindowView",
                    "com.oplus.systemui.statusbar.phone.OplusStatusBarWindowView"
            };
            for (String windowName : windowNames) {
                try {
                    hookStatusBarAttach(Class.forName(windowName, false, loader));
                } catch (Throwable ignored) {
                    moduleLog(Log.DEBUG, "status-bar window class unavailable: " + windowName);
                }
            }
            if (!fragmentHooked) moduleLog(Log.WARN, "no collapsed status-bar fragment hook found");
            // ROM-independent fallback: scan every SystemUI window root for the clock.
            startStatusBarTickerScanner();
        } catch (Throwable error) {
            moduleLog(Log.WARN, "status-bar ticker hook unavailable", error);
        }
    }

    /**
     * ROM-independent fallback: enumerate every view root owned by the SystemUI
     * process and look for the status-bar clock. Works even when the AOSP /
     * ColorOS collapsed-status-bar classes are renamed or missing.
     */
    private void startStatusBarTickerScanner() {
        mainHandler.post(this::scanSystemUiRootsForTicker);
    }

    private void scanSystemUiRootsForTicker() {
        try {
            if (statusBarRoot == null) {
                Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
                java.lang.reflect.Method getInstance = wmg.getMethod("getInstance");
                java.lang.reflect.Method getWindowViews = wmg.getMethod("getWindowViews");
                Object singleton = getInstance.invoke(null);
                Object views = singleton == null ? null : getWindowViews.invoke(singleton);
                if (views instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) views) {
                        if (!(o instanceof View)) continue;
                        // ColorOS 一定有 status_bar_contents（已确认），作为首选锚点；
                        // 失败再退回时钟反查，适配其它 ROM。
                        View target = findStatusBarContents((View) o);
                        if (target == null) target = findClockContainer((View) o);
                        if (target != null) {
                            attachStatusBarTicker(target);
                            moduleLog(Log.INFO, "status ticker attached via window scan "
                                    + ((View) o).getClass().getName());
                            break;
                        }
                    }
                }
            }
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "status ticker window scan failed", error);
        }
        if (statusBarRoot == null) {
            mainHandler.postDelayed(this::scanSystemUiRootsForTicker, 500L);
        }
    }

    private void hookStatusBarAttach(Class<?> cls) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!"onAttachedToWindow".equals(method.getName()) || method.getParameterTypes().length != 0) continue;
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof View) mainHandler.post(() -> findAndAttachStatusBar((View) self));
                return result;
            });
            moduleLog(Log.INFO, "status-bar window attach hook installed");
            break;
        }
    }

    private void findAndAttachStatusBar(View root) {
        if (!(root instanceof ViewGroup)) return;
        lastSearchedRoot = root;
        View target = root.findViewWithTag("status_bar_contents");
        if (!(target instanceof ViewGroup)) {
            int id = systemUiResourceId("status_bar_contents", "id");
            target = id == 0 ? null : root.findViewById(id);
        }
        if (!(target instanceof ViewGroup)) target = findStatusBarView(root);
        if (!(target instanceof ViewGroup)) {
            // AOSP ids/classes differ across ROMs (e.g. ColorOS). Every status bar has a
            // clock though, so anchor on it and use its parent as the contents container.
            target = findClockContainer(root);
        }
        if (target != null) {
            attachStatusBarTicker(target);
            return;
        }
        // The status bar view may only exist after SystemUI finishes inflating its
        // hierarchy. Re-scan a few times so the ticker still attaches automatically.
        if (statusBarRetries < 12) {
            statusBarRetries++;
            int attempt = statusBarRetries;
            mainHandler.postDelayed(() -> {
                if (statusBarRoot == null && lastSearchedRoot != null
                        && lastSearchedRoot.isAttachedToWindow()) {
                    findAndAttachStatusBar(lastSearchedRoot);
                } else if (statusBarRoot == null) {
                    moduleLog(Log.DEBUG, "status ticker retry skipped (attempt " + attempt + ")");
                }
            }, 400L);
        } else {
            moduleLog(Log.WARN, "status ticker: no status-bar container found after retries");
        }
    }

    /** 通过时钟视图反查状态栏通知区容器（跨 ROM 通用）。 */
    private View findClockContainer(View view) {
        View clock = view == null ? null : findClockViewIn(view);
        if (clock != null && clock.getParent() instanceof ViewGroup) {
            return (View) clock.getParent();
        }
        return null;
    }

    /** 按资源 id 定位 status_bar_contents 容器（ColorOS 一定存在该 id）。 */
    private View findStatusBarContents(View root) {
        if (!(root instanceof ViewGroup)) return null;
        try {
            int id = systemUiResourceId("status_bar_contents", "id");
            if (id != View.NO_ID && id != 0) {
                View v = root.findViewById(id);
                if (v instanceof ViewGroup) return v;
            }
        } catch (Throwable ignored) {
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findStatusBarContents(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private View findClockViewIn(View view) {
        if (view == null) return null;
        try {
            if ("ClockView".equals(view.getClass().getSimpleName())) return view;
        } catch (Throwable ignored) {
        }
        if (view instanceof android.widget.TextView) {
            Object tag = view.getTag();
            if ("clock".equals(tag)) return view;
            int id = view.getId();
            if (id != View.NO_ID) {
                try {
                    android.content.res.Resources res = systemUiContext.getResources();
                    String name = res.getResourceEntryName(id);
                    if ("clock".equals(name)) return view;
                } catch (Throwable ignored) {
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClockViewIn(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findStatusBarView(View view) {
        if (view.getClass().getName().contains("PhoneStatusBarView")) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findStatusBarView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void attachStatusBarTicker(View view) {
        if (!(view instanceof ViewGroup) || systemUiContext == null) return;
        ViewGroup root = (ViewGroup) view;
        int startId = systemUiResourceId("status_bar_start_side_content", "id");
        View start = startId == 0 ? null : root.findViewById(startId);
        int contentId = systemUiResourceId("status_bar_contents", "id");
        View content = contentId == 0 ? null : root.findViewById(contentId);
        // The start-side container is sometimes zero-width until the first
        // status-bar relayout. A ViewOverlay attached there is clipped after
        // the initial draw, which explains why swiping the bar makes the
        // ticker reappear. Prefer a measured contents/root host in that case.
        ViewGroup candidate = start instanceof ViewGroup ? (ViewGroup) start : null;
        if (candidate == null || candidate.getMeasuredWidth() < dpForContext(120)) {
            if (content instanceof ViewGroup
                    && ((ViewGroup) content).getMeasuredWidth() >
                    (candidate == null ? 0 : candidate.getMeasuredWidth())) {
                candidate = (ViewGroup) content;
            }
        }
        statusBarRoot = candidate != null ? candidate : root;
        if (statusScrollOverlay == null) {
            statusScrollOverlay = new StatusScrollOverlay(systemUiContext);
            statusScrollOverlay.setFinishedAction(this::onStatusTickerFinished);
        }
        try {
            statusScrollOverlay.attachTo(statusBarRoot);
            pendingTickerAttachAttempts = 0;
            String idName = "none";
            try {
                int id = statusBarRoot.getId();
                if (id != View.NO_ID) idName = systemUiContext.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
            }
            moduleLog(Log.INFO, "status ticker attached: cls=" + statusBarRoot.getClass().getSimpleName()
                    + " id=" + idName + " vis=" + statusBarRoot.getVisibility()
                    + " attachedWin=" + statusBarRoot.isAttachedToWindow()
                    + " w=" + statusBarRoot.getWidth() + " h=" + statusBarRoot.getHeight()
                    + " measuredW=" + statusBarRoot.getMeasuredWidth());
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "status ticker attach failed: " + view.getClass().getName(), error);
        }
    }

    private int dpForContext(float value) {
        return Math.round(value * systemUiContext.getResources().getDisplayMetrics().density);
    }

    private int systemUiResourceId(String name, String type) {
        if (systemUiContext == null) return 0;
        String[] packages = {systemUiPackageName, SYSTEM_UI, OP_SYSTEM_UI};
        for (String packageName : packages) {
            if (packageName == null) continue;
            try {
                int id = systemUiContext.getResources().getIdentifier(name, type, packageName);
                if (id != 0) return id;
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private BubbleConfig loadModuleConfig() {
        configurationFromCache = false;
        BubbleConfig providerConfig = loadProviderConfig();
        if (providerConfig != null) return providerConfig;
        BubbleConfig cached = loadCachedConfiguration();
        if (cached != null) {
            // Silent here: refreshConfiguration() already reports "cached=true" once per read,
            // and the retry loop would otherwise log a warning every few seconds.
            configurationFromCache = true;
            return cached;
        }
        try {
            SharedPreferences remote = getRemotePreferences(BubbleConfig.PREFS_NAME);
            if (remote != null) {
                BubbleConfig remoteConfig = new BubbleConfig(remote);
                if (remoteConfig.hasPersistedSettings()) return remoteConfig;
            }
        } catch (Throwable error) {
            moduleLog(Log.WARN, "remote module preferences unavailable", error);
        }
        return null;
    }

    /**
     * Returns the provider's answer as-is. An empty but successfully read store is a valid
     * configuration (the user simply has not changed anything yet); only a pending/unreadable
     * response yields {@code null}. Transport failures are logged once so the reason (for
     * example ColorOS rejecting the cold start) shows up in the module log.
     */
    private BubbleConfig loadProviderConfig() {
        if (systemUiContext == null) return null;
        try {
            BubbleConfig providerConfig = BubbleConfig.readProvider(systemUiContext);
            if (providerConfig != null) {
                runtimeLogProviderAvailable = true;
                providerFailureLogged = false;
            }
            return providerConfig;
        } catch (Throwable error) {
            moduleLog(providerFailureLogged ? Log.DEBUG : Log.WARN,
                    "provider configuration unavailable", error);
            providerFailureLogged = true;
            return null;
        }
    }

    /**
     * SystemUI's own device-protected preferences. Only this process writes and reads it, so it
     * is available from the first moment of a boot without starting the module app.
     */
    private SharedPreferences bootConfigCache() {
        if (bootConfigCache != null) return bootConfigCache;
        if (systemUiContext == null) return null;
        try {
            Context deviceContext = systemUiContext.createDeviceProtectedStorageContext();
            bootConfigCache = deviceContext.getSharedPreferences(
                    BOOT_CONFIG_CACHE, Context.MODE_PRIVATE);
        } catch (Throwable error) {
            moduleLog(Log.WARN, "boot configuration cache unavailable", error);
        }
        return bootConfigCache;
    }

    private BubbleConfig loadCachedConfiguration() {
        SharedPreferences cache = bootConfigCache();
        if (cache == null) return null;
        try {
            BubbleConfig cached = new BubbleConfig(cache);
            if (cached.hasPersistedSettings()) return cached;
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "cached configuration unreadable", error);
        }
        return null;
    }

    /** Refreshes the boot cache after every successful read of the live configuration. */
    private void cacheConfiguration(BubbleConfig source) {
        if (source == null) return;
        SharedPreferences cache = bootConfigCache();
        if (cache == null) return;
        try {
            source.copyTo(cache);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "unable to cache configuration for boot", error);
        }
    }

    private boolean isUserUnlocked() {
        try {
            UserManager manager = systemUiContext == null ? null
                    : systemUiContext.getSystemService(UserManager.class);
            return manager != null && manager.isUserUnlocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("PrivateApi")
    private void installPowerManagerHooks(ClassLoader loader) {
        if (powerHooksInstalled) return;
        try {
            int powerGroupHooks = installPowerGroupDimTransitionHooks(loader);
            Class<?> serviceClass = findFrameworkClass(
                    "com.android.server.power.PowerManagerService", loader);
            int touchHooks = installDimTouchActivityHooks(serviceClass);
            int summaryHooks = 0;
            // PowerGroup is the cheapest and most precise source on ColorOS 17. Retain the
            // older summary hook only when that class is unavailable on another build.
            if (powerGroupHooks == 0) {
                for (Method method : serviceClass.getDeclaredMethods()) {
                    if ("updateUserActivitySummaryLocked".equals(method.getName())) {
                        method.setAccessible(true);
                        hook(method).intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                updateSystemServerDimState(chain.getThisObject());
                                return result;
                            }
                        });
                        summaryHooks++;
                    }
                }
            }
            if (powerGroupHooks == 0 && summaryHooks == 0 && touchHooks == 0) {
                throw new NoSuchMethodException("No compatible PowerManagerService hooks");
            }
            powerHooksInstalled = true;
            if (touchHooks == 0) installScreenUndimHook(loader);
            moduleLog(Log.INFO, "PowerManager hooks installed; powerGroup=" + powerGroupHooks
                    + ", summary=" + summaryHooks + ", touch=" + touchHooks);
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "PowerManager hook installation failed", error);
        }
    }

    /** Tracks the default display's dim policy for the subsequent touch event. */
    private int installPowerGroupDimTransitionHooks(ClassLoader loader) {
        try {
            Class<?> groupClass = findFrameworkClass(
                    "com.android.server.power.PowerGroup", loader);
            Method getPolicy = groupClass.getDeclaredMethod("getPolicyLocked");
            Method getGroupId = groupClass.getDeclaredMethod("getGroupId");
            getPolicy.setAccessible(true);
            getGroupId.setAccessible(true);
            powerGroupPolicyMethod = getPolicy;
            powerGroupIdMethod = getGroupId;
            int hookCount = 0;
            for (Method method : groupClass.getDeclaredMethods()) {
                if (!"updateLocked".equals(method.getName())) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    Object group = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        int currentPolicy = ((Number) getPolicy.invoke(group)).intValue();
                        int groupId = ((Number) getGroupId.invoke(group)).intValue();
                        if (groupId == 0) {
                            systemServerScreenDim = currentPolicy == 2;
                        }
                    } catch (Throwable error) {
                        moduleLog(Log.DEBUG, "unable to read PowerGroup policy after update", error);
                    }
                    return result;
                });
                hookCount++;
            }
            if (hookCount > 0) {
                moduleLog(Log.INFO, "PowerGroup dim-policy hook installed; count=" + hookCount);
            }
            return hookCount;
        } catch (Throwable error) {
            moduleLog(Log.WARN, "PowerGroup dim-policy hook unavailable", error);
            return 0;
        }
    }

    /** ColorOS 17 input path: userActivityNoUpdateLocked(PowerGroup, time, event, flags, uid). */
    private int installDimTouchActivityHooks(Class<?> serviceClass) {
        int hookCount = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"userActivityNoUpdateLocked".equals(method.getName())
                    || types.length != 5
                    || !"com.android.server.power.PowerGroup".equals(types[0].getName())
                    || types[2] != int.class) {
                continue;
            }
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                Object group = args.isEmpty() ? null : args.get(0);
                int event = args.size() > 2 && args.get(2) instanceof Integer
                        ? (Integer) args.get(2) : -1;
                boolean dimTouch = event == USER_ACTIVITY_EVENT_TOUCH
                        && systemServerScreenDim
                        && isDefaultGroupDim(group);
                Object result = chain.proceed();
                if (dimTouch) sendDimTouchBroadcast(chain.getThisObject());
                return result;
            });
            hookCount++;
        }
        if (hookCount > 0) {
            moduleLog(Log.INFO, "ColorOS dim-touch hook installed; count=" + hookCount);
        }
        return hookCount;
    }

    private boolean isDefaultGroupDim(Object group) {
        if (group == null) return systemServerScreenDim;
        try {
            Method getGroupId = powerGroupIdMethod;
            Method getPolicy = powerGroupPolicyMethod;
            if (getGroupId == null || getPolicy == null) return systemServerScreenDim;
            return ((Number) getGroupId.invoke(group)).intValue() == 0
                    && ((Number) getPolicy.invoke(group)).intValue() == 2;
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "unable to inspect PowerGroup for dim touch", error);
            return systemServerScreenDim;
        }
    }

    private void installScreenUndimHook(ClassLoader loader) {
        try {
            Class<?> detectorClass = findFrameworkClass(
                    "com.android.server.power.ScreenUndimDetector", loader);
            Method method = detectorClass.getDeclaredMethod(
                    "recordScreenPolicy", int.class, int.class);
            Field policyField = detectorClass.getDeclaredField("mCurrentScreenPolicy");
            method.setAccessible(true);
            policyField.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object detector = chain.getThisObject();
                    int previousPolicy = policyField.getInt(detector);
                    List<Object> args = chain.getArgs();
                    int displayGroup = args.size() > 0 && args.get(0) instanceof Integer
                            ? (Integer) args.get(0) : -1;
                    int newPolicy = args.size() > 1 && args.get(1) instanceof Integer
                            ? (Integer) args.get(1) : -1;
                    Object result = chain.proceed();
                    if (displayGroup == 0 && previousPolicy == 2 && newPolicy == 3) {
                        sendDimTouchBroadcast(detector);
                    }
                    return result;
                }
            });
            moduleLog(Log.INFO, "ScreenUndimDetector dim-entry hook installed");
        } catch (Throwable error) {
            moduleLog(Log.WARN, "ScreenUndimDetector hook unavailable", error);
        }
    }

    private void installFreeformLaunchHook(ClassLoader loader) {
        try {
            Class<?> starterClass = Class.forName(
                    "com.android.server.wm.ActivityStarter", false, loader);
            for (Method method : starterClass.getDeclaredMethods()) {
                if (!"executeRequest".equals(method.getName())
                        || method.getParameterTypes().length != 1) continue;
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        applyPendingFreeformLaunch(chain.getThisObject(), chain.getArgs());
                        return chain.proceed();
                    }
                });
                moduleLog(Log.INFO, "ActivityStarter freeform launch hook installed");
                return;
            }
            moduleLog(Log.WARN, "ActivityStarter executeRequest not found");
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "ActivityStarter freeform hook installation failed", error);
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private void applyPendingFreeformLaunch(Object starter, List<Object> args) {
        try {
            if (args.isEmpty() || args.get(0) == null) return;
            Object request = args.get(0);
            Field intentField = request.getClass().getDeclaredField("intent");
            intentField.setAccessible(true);
            Object intentValue = intentField.get(request);
            if (!(intentValue instanceof Intent)) return;
            String packageName = ((Intent) intentValue).getComponent() == null
                    ? ((Intent) intentValue).getPackage()
                    : ((Intent) intentValue).getComponent().getPackageName();
            if (TextUtils.isEmpty(packageName)) {
                Context context = resolveSystemContext(starter);
                android.content.pm.ResolveInfo resolved = context == null ? null
                        : context.getPackageManager().resolveActivity((Intent) intentValue, 0);
                if (resolved != null && resolved.activityInfo != null) {
                    packageName = resolved.activityInfo.packageName;
                }
            }
            if (TextUtils.isEmpty(packageName)) return;
            Context context = resolveSystemContext(starter);
            if (context == null || !takeFreeformTicket(context, packageName)) return;

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                        .invoke(options, 100);
            } catch (Throwable ignored) {
            }
            Bundle optionBundle = options.toBundle();
            optionBundle.putInt("android.activity.windowingMode", 100);
            optionBundle.putInt("android:activity.mZoomLaunchFlags", 4);
            try {
                Method fromBundle = ActivityOptions.class.getDeclaredMethod(
                        "fromBundle", Bundle.class);
                fromBundle.setAccessible(true);
                Object rebuilt = fromBundle.invoke(null, optionBundle);
                if (rebuilt instanceof ActivityOptions) options = (ActivityOptions) rebuilt;
            } catch (Throwable ignored) {
            }
            Class<?> safeOptionsClass = Class.forName(
                    "com.android.server.wm.SafeActivityOptions", false,
                    starter.getClass().getClassLoader());
            Object safeOptions = safeOptionsClass
                    .getConstructor(ActivityOptions.class, int.class, int.class)
                    .newInstance(options, 0, 1000);
            Field optionsField = request.getClass().getDeclaredField("activityOptions");
            optionsField.setAccessible(true);
            optionsField.set(request, safeOptions);
            moduleLog(Log.INFO, "forcing freeform notification launch for " + packageName);
        } catch (Throwable error) {
            moduleLog(Log.WARN, "unable to force freeform notification launch", error);
        }
    }

    private boolean takeFreeformTicket(Context context, String packageName) {
        try {
            Bundle extras = new Bundle();
            extras.putString("package", packageName);
            Bundle result = context.getContentResolver().call(BubbleConfigProvider.URI,
                    BubbleConfigProvider.METHOD_TAKE_FREEFORM_LAUNCH, null, extras);
            return result != null && result.getBoolean("success")
                    && result.getBoolean("forceFreeform");
        } catch (Throwable error) {
            moduleLog(Log.WARN, "freeform launch ticket unavailable", error);
            return false;
        }
    }

    private void updateSystemServerDimState(Object powerManagerService) {
        try {
            Field groupsField = powerManagerService.getClass().getDeclaredField("mPowerGroups");
            groupsField.setAccessible(true);
            Object groups = groupsField.get(powerManagerService);
            Object defaultGroup = null;
            if (groups instanceof SparseArray) {
                defaultGroup = ((SparseArray<?>) groups).get(0);
            }
            if (defaultGroup == null) {
                systemServerScreenDim = false;
                return;
            }
            Method summaryMethod = defaultGroup.getClass()
                    .getDeclaredMethod("getUserActivitySummaryLocked");
            summaryMethod.setAccessible(true);
            Object value = summaryMethod.invoke(defaultGroup);
            systemServerScreenDim = value instanceof Integer && (((Integer) value) & 2) != 0;
        } catch (Throwable error) {
            systemServerScreenDim = false;
            moduleLog(Log.WARN, "unable to read screen dim state", error);
        }
    }

    private void sendDimTouchBroadcast(Object powerManagerService) {
        try {
            Context context = resolveSystemContext(powerManagerService);
            if (context == null) {
                moduleLog(Log.WARN, "unable to resolve system context for dim-screen touch");
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastSystemServerDimBroadcastAt < 2_000L) return;
            lastSystemServerDimBroadcastAt = now;
            runtimeLogContext = context;
            Intent intent = new Intent(ACTION_DIM_TOUCH)
                    .setPackage(systemUiPackageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            moduleLog(Log.INFO, "dim-screen touch sent to SystemUI");
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "unable to send dim-screen touch", error);
        }
    }

    /** Resolve services.jar classes even when the package callback supplies an app loader. */
    private Class<?> findFrameworkClass(String name, ClassLoader preferred)
            throws ClassNotFoundException {
        ClassNotFoundException last = null;
        ClassLoader[] candidates = {
                preferred,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                return Class.forName(name, false, candidate);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException error) {
            if (last != null) error.addSuppressed(last);
            throw error;
        }
    }

    private Context resolveSystemContext(Object source) {
        if (source instanceof Context) return (Context) source;
        if (source != null) {
            try {
                Field field = source.getClass().getDeclaredField("mContext");
                field.setAccessible(true);
                Object value = field.get(source);
                if (value instanceof Context) return (Context) value;
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method current = threadClass.getDeclaredMethod("currentActivityThread");
            Object thread = current.invoke(null);
            Method getSystemContext = threadClass.getDeclaredMethod("getSystemContext");
            Object value = getSystemContext.invoke(thread);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Clipboard access is focus-gated since Android 10, so the injected SystemUI process cannot
     * observe copies reliably. The clipboard is therefore watched in system_server (which owns
     * the clipboard service) and only a normalized phone number is forwarded to SystemUI.
     */
    @SuppressLint("PrivateApi")
    private void installClipboardHooks(ClassLoader loader) {
        if (clipboardHooksInstalled) return;
        try {
            Class<?> serviceClass = findFrameworkClass(
                    "com.android.server.clipboard.ClipboardService", loader);
            int count = 0;
            for (Method method : serviceClass.getDeclaredMethods()) {
                String name = method.getName();
                // Only the public entry points: the *InternalLocked variants run under the
                // clipboard lock and must not do extra work inside it.
                if (!"setPrimaryClip".equals(name) && !"setPrimaryClipAsPackage".equals(name)) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 0 || !ClipData.class.isAssignableFrom(types[0])) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) instanceof ClipData) {
                        onSystemClipboardChanged((ClipData) args.get(0), chain.getThisObject());
                    }
                    return result;
                });
                count++;
            }
            clipboardHooksInstalled = count > 0;
            moduleLog(Log.INFO, "clipboard hooks installed: " + count);
        } catch (Throwable error) {
            moduleLog(Log.WARN, "clipboard hook unavailable", error);
        }
    }

    private void onSystemClipboardChanged(ClipData clip, Object clipboardService) {
        try {
            if (clip == null || clip.getItemCount() == 0) return;
            Context context = resolveSystemContext(clipboardService);
            if (context == null) return;
            CharSequence value = clip.getItemAt(0).coerceToText(context);
            String text = value == null ? "" : value.toString().trim();
            if (text.isEmpty()) return;
            List<ClipboardClassifier.Result> results = ClipboardClassifier.classifyAll(text);
            if (results.isEmpty()) return;
            StringBuilder signature = new StringBuilder();
            for (ClipboardClassifier.Result item : results) signature.append(item.kind).append('=')
                    .append(item.value).append(';');
            String stamp = signature.toString();
            long now = android.os.SystemClock.elapsedRealtime();
            if (stamp.equals(lastClipboardItem) && now - lastClipboardItemAt < 3_000L) {
                return;
            }
            lastClipboardItem = stamp;
            lastClipboardItemAt = now;
            runtimeLogContext = context;
            int count = results.size();
            int[] kinds = new int[count];
            String[] values = new String[count];
            String[] displays = new String[count];
            String[] apps = new String[count];
            for (int index = 0; index < count; index++) {
                ClipboardClassifier.Result item = results.get(index);
                kinds[index] = item.kind;
                values[index] = item.value;
                displays[index] = item.display;
                apps[index] = item.appPackage;
            }
            Intent intent = new Intent(ACTION_CLIPBOARD_ITEM)
                    .setPackage(systemUiPackageName)
                    .putExtra("kinds", kinds)
                    .putExtra("values", values)
                    .putExtra("displays", displays)
                    .putExtra("apps", apps)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            moduleLog(Log.INFO, "clipboard items sent to SystemUI; count=" + count);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "clipboard inspection failed", error);
        }
    }

    private int hookNotificationPipeline(ClassLoader loader) {
        String[] candidates = {
                "com.android.systemui.statusbar.NotificationListener",
                "com.android.systemui.statusbar.phone.NotificationListenerWithPlugins",
                "com.oplus.systemui.statusbar.NotificationListener",
                "com.oplus.systemui.statusbar.phone.NotificationListenerWithPlugins",
                "com.oplus.systemui.statusbar.notification.NotificationListener"
        };
        int count = 0;
        for (String className : candidates) {
            try {
                Class<?> listenerClass = Class.forName(className, false, loader);
                for (Method method : listenerClass.getDeclaredMethods()) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (!"onNotificationPosted".equals(method.getName())
                            || parameterTypes.length == 0
                            || !StatusBarNotification.class.isAssignableFrom(parameterTypes[0])) {
                        continue;
                    }
                    method.setAccessible(true);
                    hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof StatusBarNotification) {
                                StatusBarNotification sbn = (StatusBarNotification) args.get(0);
                                Object listener = chain.getThisObject();
                                mainHandler.post(() -> {
                                    systemUiNotificationListener = listener;
                                    onNotificationPosted(sbn);
                                });
                            }
                            return result;
                        }
                    });
                    count++;
                }
                Method connectedMethod = findMethodInHierarchy(
                        listenerClass, "onListenerConnected", 0);
                if (connectedMethod != null) {
                    connectedMethod.setAccessible(true);
                    hook(connectedMethod).intercept(chain -> {
                        Object result = chain.proceed();
                        Object listener = chain.getThisObject();
                        mainHandler.post(() -> {
                            systemUiNotificationListener = listener;
                            rescanActiveNotifications(listener);
                        });
                        return result;
                    });
                    count++;
                    moduleLog(Log.INFO, "notification listener connection hook installed");
                }
                Method removalMethod = null;
                for (Method method : listenerClass.getDeclaredMethods()) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (!"onNotificationRemoved".equals(method.getName())
                            || parameterTypes.length == 0
                            || !StatusBarNotification.class.isAssignableFrom(parameterTypes[0])) {
                        continue;
                    }
                    if (removalMethod == null
                            || method.getParameterTypes().length
                            > removalMethod.getParameterTypes().length) {
                        removalMethod = method;
                    }
                }
                if (removalMethod != null) {
                    removalMethod.setAccessible(true);
                    hook(removalMethod).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof StatusBarNotification) {
                                StatusBarNotification sbn = (StatusBarNotification) args.get(0);
                                int reason = notificationRemovalReason(args);
                                mainHandler.post(() -> onNotificationRemoved(sbn, reason));
                            }
                            return result;
                        }
                    });
                    count++;
                }
            } catch (Throwable error) {
                moduleLog(Log.WARN, "unavailable pipeline: " + className, error);
            }
            if (count > 0) break;
        }
        return count;
    }

    /**
     * ColorOS 17 suppresses selected source notifications once Fluid Cloud owns their
     * surface, so those entries do not reliably reach NotificationListener. Hook the
     * SystemUI provider that forwards the actual native Live Alert batches instead.
     */
    private int hookNativeFluidCloudPipeline(ClassLoader loader) {
        String[] providerNames = {
                "com.oplus.systemui.statusbar.seeding.SeedlingCardProvider",
                // Fallbacks for builds where the provider is exposed directly from the
                // plugin class loader rather than through SystemUI's Seedling provider.
                "com.oplus.systemui.plugins.seedling.SeedlingCardServerProvider",
                "com.oplus.systemui.livealert.notification.source.NotificationLiveAlertProvider"
        };
        for (String providerName : providerNames) {
            try {
                Class<?> providerClass = Class.forName(providerName, false, loader);
                int count = 0;
                for (Method method : providerClass.getMethods()) {
                    if (!"call".equals(method.getName()) || !isBundleCallMethod(method)) continue;
                    method.setAccessible(true);
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                List<Object> args = chain.getArgs();
                                String action = findNativeLiveAlertAction(args);
                                Bundle extras = findBundleArgument(args);
                                Object result = chain.proceed();
                                if (action != null && extras != null) {
                                    ArrayList<StatusBarNotification> notifications = extras
                                            .getParcelableArrayList(LIVE_ALERT_NOTIFICATIONS_KEY,
                                                    StatusBarNotification.class);
                                    if (notifications != null && !notifications.isEmpty()) {
                                        mainHandler.post(() -> onNativeLiveAlertAction(action,
                                                notifications));
                                    }
                                }
                                return result;
                            });
                    count++;
                }
                if (count > 0) {
                    moduleLog(Log.INFO, "native Live Alert provider hook installed: "
                            + providerName + ", methods=" + count);
                    return count;
                }
            } catch (Throwable error) {
                moduleLog(Log.DEBUG, "native Live Alert provider unavailable: "
                        + providerName, error);
            }
        }
        moduleLog(Log.WARN, "no native Live Alert provider hook found; using notification listener fallback");
        return 0;
    }

    private boolean isBundleCallMethod(Method method) {
        boolean hasString = false;
        boolean hasBundle = false;
        for (Class<?> type : method.getParameterTypes()) {
            if (type == String.class) hasString = true;
            if (Bundle.class.isAssignableFrom(type)) hasBundle = true;
        }
        return hasString && hasBundle;
    }

    private String findNativeLiveAlertAction(List<Object> args) {
        if (args == null) return null;
        for (Object value : args) {
            if (!(value instanceof String)) continue;
            String action = (String) value;
            if (LIVE_ALERT_NOTIFICATION.equals(action)
                    || LIVE_ALERT_NOTIFICATION_POST.equals(action)
                    || LIVE_ALERT_NOTIFICATION_REMOVE.equals(action)) {
                return action;
            }
        }
        return null;
    }

    private Bundle findBundleArgument(List<Object> args) {
        if (args == null) return null;
        for (Object value : args) {
            if (value instanceof Bundle) return (Bundle) value;
        }
        return null;
    }

    private void onNativeLiveAlertAction(String action,
                                         List<StatusBarNotification> notifications) {
        if (notifications == null || notifications.isEmpty()) return;
        if (LIVE_ALERT_NOTIFICATION_REMOVE.equals(action)) {
            for (StatusBarNotification sbn : notifications) {
                if (sbn == null) continue;
                recentNativeLiveAlertNotifications.remove(liveAlertDedupKey(sbn));
                // The provider does not include a NotificationListener removal reason.
                // Preserve the existing bubble-retention policy while immediately closing
                // a matching native ticker/capsule source.
                onNotificationRemoved(sbn, -1);
            }
            moduleLog(Log.INFO, "native Live Alert removed: " + notifications.size());
            return;
        }
        if (!isIncludeFluidCloudNow()) return;
        if (immediateSuggestionController != null) {
            immediateSuggestionController.onNativeClipboardSuggestionObserved();
        }
        int delivered = 0;
        for (StatusBarNotification sbn : notifications) {
            if (sbn == null || isNativeLiveAlertDuplicate(sbn)) continue;
            onNotificationPosted(sbn, true);
            delivered++;
        }
        moduleLog(Log.INFO, "native Live Alert " + action + ": " + delivered
                + "/" + notifications.size());
    }

    private boolean isNativeLiveAlertDuplicate(StatusBarNotification sbn) {
        String key = liveAlertDedupKey(sbn);
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = recentNativeLiveAlertNotifications.put(key, now);
        if (recentNativeLiveAlertNotifications.size() > 64) {
            recentNativeLiveAlertNotifications.entrySet().removeIf(
                    entry -> now - entry.getValue() > LIVE_ALERT_DUPLICATE_WINDOW_MS);
        }
        return last != null && now - last < LIVE_ALERT_DUPLICATE_WINDOW_MS;
    }

    private String liveAlertDedupKey(StatusBarNotification sbn) {
        String key = sbn.getKey();
        if (!TextUtils.isEmpty(key)) return key;
        return String.valueOf(sbn.getPackageName()) + ':' + sbn.getId() + ':'
                + String.valueOf(sbn.getTag()) + ':' + sbn.getPostTime();
    }

    private static boolean isSystemUiPackage(String packageName) {
        return SYSTEM_UI.equals(packageName) || OP_SYSTEM_UI.equals(packageName);
    }

    private Method findMethodInHierarchy(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) return method;
            }
        }
        return null;
    }

    private int notificationRemovalReason(List<Object> args) {
        if (args == null) return -1;
        for (int i = args.size() - 1; i >= 0; i--) {
            Object value = args.get(i);
            if (value instanceof Integer) return (Integer) value;
        }
        return -1;
    }

    private void rescanActiveNotifications(Object listener) {
        if (listener == null || !initialized && systemUiContext == null) return;
        try {
            Method method = findMethodInHierarchy(listener.getClass(),
                    "getActiveNotifications", 0);
            if (method == null) return;
            method.setAccessible(true);
            Object value = method.invoke(listener);
            if (!(value instanceof StatusBarNotification[])) return;
            StatusBarNotification[] active = (StatusBarNotification[]) value;
            systemUiNotificationListener = listener;
            for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
            moduleLog(Log.INFO, "rescanned active notifications: " + active.length);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "active notification rescan unavailable", error);
        }
    }

    /** Prevents a duplicate SystemUI heads-up when the module is showing its content preview. */
    private int installHeadsUpSuppression(ClassLoader loader) {
        int count = 0;
        if (!headsUpModernHookInstalled) {
            try {
                Class<?> providerClass = Class.forName(
                        "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl",
                        false, loader);
                int modernCount = 0;
                for (Method method : providerClass.getDeclaredMethods()) {
                    String name = method.getName();
                    if (!("makeAndLogHeadsUpDecision".equals(name)
                            || "makeUnloggedHeadsUpDecision".equals(name))) continue;
                    method.setAccessible(true);
                    hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            List<Object> args = chain.getArgs();
                            Object entry = findNotificationEntryArgument(args);
                            if (entry != null) {
                                recordNativeHeadsUp(entry, result);
                                if (shouldSuppressHeadsUp(entry)) {
                                    // The module bubble takes over the banner: do not also
                                    // roll this notification in the status bar.
                                    recordHeadsUpShown(entry, true);
                                    result = createSuppressedDecision(chain.getThisObject(), result);
                                }
                            }
                            return result;
                        }
                    });
                    modernCount++;
                }
                if (modernCount > 0) {
                    headsUpModernHookInstalled = true;
                    count += modernCount;
                    moduleLog(Log.INFO, "heads-up suppression hooks installed: " + modernCount);
                }
            } catch (Throwable error) {
                moduleLog(Log.DEBUG, "modern heads-up suppression hook unavailable", error);
            }
        }
        // Keep the legacy provider attempt independent of the modern provider;
        // ColorOS builds often expose only one of the two classes.
        if (!headsUpLegacyHookInstalled) {
            int legacyCount = installLegacyHeadsUpSuppression(loader);
            if (legacyCount > 0) {
                headsUpLegacyHookInstalled = true;
                count += legacyCount;
            }
        }
        if (count == 0) {
            moduleLog(Log.WARN, "no heads-up decision hook installed");
        }
        return count;
    }

    /** Retries the banner-decision hooks while a plugin class loader is still warming up. */
    private void scheduleHeadsUpSuppressionRetry(ClassLoader loader) {
        if (headsUpSuppressionInstalled || headsUpSuppressionAttempts >= 5) return;
        headsUpSuppressionAttempts++;
        long delay = 1_500L + headsUpSuppressionAttempts * 1_000L;
        mainHandler.postDelayed(() -> {
            if (headsUpSuppressionInstalled) return;
            if (installHeadsUpSuppression(loader) > 0) {
                headsUpSuppressionInstalled = true;
                moduleLog(Log.INFO, "heads-up suppression hooks installed on retry");
            } else {
                scheduleHeadsUpSuppressionRetry(loader);
            }
        }, delay);
    }

    /** ColorOS builds may route banner decisions through the legacy interrupt provider. */
    private int installLegacyHeadsUpSuppression(ClassLoader loader) {
        String[] providerNames = {
                "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
                "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider",
                "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider",
                "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl",
                "com.oplus.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
                "com.oplus.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider",
                "com.oplus.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider",
                "com.oplus.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl"
        };
        int count = 0;
        for (String providerName : providerNames) try {
            Class<?> providerClass = Class.forName(providerName, false, loader);
            for (Method method : providerClass.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (method.getReturnType() != boolean.class
                        || (!name.contains("headsup") && !name.contains("interrupt")
                        && !name.contains("interruption"))) {
                    continue;
                }
                boolean acceptsEntry = false;
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (!parameter.isPrimitive() && parameter != String.class
                            && parameter != Bundle.class) {
                        acceptsEntry = true;
                        break;
                    }
                }
                if (!acceptsEntry) continue;
                method.setAccessible(true);
                hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            boolean nativeShown = Boolean.TRUE.equals(result);
                            Object entry = findNotificationEntryArgument(chain.getArgs());
                            if (entry != null) {
                                // Only record the true heads-up decision methods to avoid
                                // misreading generic interrupt helpers as a banner result.
                                if (name.contains("headsup")) {
                                    recordNativeHeadsUpShown(entry, nativeShown);
                                }
                                if (shouldSuppressHeadsUp(entry)) {
                                    recordHeadsUpShown(entry, true);
                                    return false;
                                }
                            }
                            return result;
                        });
                count++;
            }
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "legacy heads-up provider unavailable: " + providerName, error);
        }
        moduleLog(Log.INFO, "heads-up compatibility suppression hooks installed: " + count);
        return count;
    }

    private Object findNotificationEntryArgument(List<Object> args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            // NotificationEntry lives in a SystemUI-internal package and its SBN accessors
            // are package-private, so Class#getMethod can never resolve them from the module
            // package. Resolve them through the declared hierarchy instead; without this the
            // whole heads-up suppression pipeline silently did nothing.
            if (sbnOf(arg) != null) return arg;
        }
        return null;
    }

    private StatusBarNotification sbnOf(Object notificationEntry) {
        if (notificationEntry == null) return null;
        if (notificationEntry instanceof StatusBarNotification) {
            return (StatusBarNotification) notificationEntry;
        }
        for (Class<?> type = notificationEntry.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                String name = method.getName();
                if (!"getSbn".equals(name) && !"getStatusBarNotification".equals(name)) continue;
                if (!StatusBarNotification.class.isAssignableFrom(method.getReturnType())) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(notificationEntry);
                    if (value instanceof StatusBarNotification) return (StatusBarNotification) value;
                } catch (Throwable ignored) {
                }
            }
        }
        for (Class<?> type = notificationEntry.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!StatusBarNotification.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(notificationEntry);
                    if (value instanceof StatusBarNotification) return (StatusBarNotification) value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** Records whether SystemUI natively decided to show a heads-up for this entry. */
    private void recordNativeHeadsUp(Object notificationEntry, Object nativeResult) {
        StatusBarNotification sbn = sbnOf(notificationEntry);
        if (sbn == null || sbn.getKey() == null) return;
        Boolean shown = null;
        if (nativeResult instanceof Boolean) {
            shown = (Boolean) nativeResult;
        } else if (nativeResult != null) {
            Object value = getFieldValue(nativeResult, "shouldInterrupt");
            shown = value instanceof Boolean ? (Boolean) value
                    : value == null ? null : Boolean.valueOf(String.valueOf(value));
        }
        if (shown != null) {
            nativeHeadsUpByKey.put(sbn.getKey(), shown);
            headsUpByKey.put(sbn.getKey(), shown);
        }
    }

    /** Overrides the recorded heads-up state for an entry (used when the bubble takes over). */
    private void recordHeadsUpShown(Object notificationEntry, boolean shown) {
        StatusBarNotification sbn = sbnOf(notificationEntry);
        if (sbn != null && sbn.getKey() != null) headsUpByKey.put(sbn.getKey(), shown);
    }

    private void recordNativeHeadsUpShown(Object notificationEntry, boolean shown) {
        StatusBarNotification sbn = sbnOf(notificationEntry);
        if (sbn != null && sbn.getKey() != null) {
            nativeHeadsUpByKey.put(sbn.getKey(), shown);
            headsUpByKey.put(sbn.getKey(), shown);
        }
    }

    private boolean shouldSuppressHeadsUp(Object notificationEntry) {
        if (systemUiContext == null || (!isBubbleEnabledNow() && !isStatusScrollEnabledNow())) {
            moduleLog(Log.INFO, "heads-up: skipped (module/float/banner toggle off)");
            return false;
        }
        try {
            StatusBarNotification sbn = sbnOf(notificationEntry);
            if (sbn == null) {
                moduleLog(Log.INFO, "heads-up: entry has no StatusBarNotification");
                return false;
            }
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            if (packageName == null || notification == null
                    || shouldIgnorePackage(packageName) || shouldIgnoreNotification(notification)) {
                moduleLog(Log.INFO, "heads-up: ignored package/notification " + packageName);
                return false;
            }
            // Fluid Cloud mode owns the compact notification surface. Suppress
            // the native banner so the notification never expands first.
            if (readStatusScrollMode() == BubbleConfig.STATUS_SCROLL_FLUID_CLOUD
                    && isStatusScrollAllowedNow(sbn)
                    && hasPreviewContent(notification)) {
                moduleLog(Log.INFO, "heads-up: SUPPRESSED for Fluid Cloud " + packageName);
                return true;
            }
            // The content-preview switch owns the decision to replace the native heads-up
            // banner with the module bubble. Apps whose banner is off are handled elsewhere.
            if (!isContentPreviewEnabledNow() || !isContentPreviewSuppressBannerNow()) {
                moduleLog(Log.INFO, "heads-up: float/banner toggle off");
                return false;
            }
            if (!hasPreviewContent(notification)) {
                moduleLog(Log.INFO, "heads-up: no float content " + packageName);
                return false;
            }
            // Only a genuinely visible foreground app replaces the banner with a bubble.
            String foregroundPackage = isForegroundVisible() ? getForegroundPackage() : null;
            if (foregroundPackage != null && packageName.equals(foregroundPackage)) {
                moduleLog(Log.INFO, "heads-up: app on foreground " + packageName);
                return false;
            }
            if (!isNotificationAllowedNow(packageName, foregroundPackage)) {
                moduleLog(Log.INFO, "heads-up: not allowed " + packageName);
                return false;
            }
            if (isDeviceLocked()) {
                // The bubble sits behind the keyguard, so keep the native banner as the only
                // visible surface for this notification.
                moduleLog(Log.INFO, "heads-up: device locked " + packageName);
                return false;
            }
            // A bubble takes over this notification, so the native banner is a duplicate
            // surface. The edge-only switch governs where the *content overlay* is drawn, not
            // whether the banner is replaced: requiring a docked bubble here silently kept the
            // banner for every undocked (default portrait) position.
            moduleLog(Log.INFO, "heads-up: SUPPRESSED " + packageName
                    + " (bubble takes over; edgeOnly=" + isContentPreviewEdgeOnlyNow() + ")");
            return true;
        } catch (Throwable error) {
            moduleLog(Log.INFO, "heads-up: decision inspection failed " + error);
            moduleLog(Log.DEBUG, "heads-up decision inspection failed", error);
            return false;
        }
    }

    private Object createSuppressedDecision(Object provider, Object decision) {
        if (decision == null) return null;
        try {
            for (Constructor<?> constructor : decision.getClass().getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                constructor.setAccessible(true);
                if (types.length == 3 && types[0].isInstance(provider)
                        && types[1] == boolean.class && types[2] == String.class) {
                    return constructor.newInstance(provider, false,
                            "suppressed by BubbleNotify content preview");
                }
                if (types.length == 2 && types[0] == boolean.class
                        && types[1] == String.class) {
                    return constructor.newInstance(false,
                            "suppressed by BubbleNotify content preview");
                }
            }
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "unable to rebuild heads-up decision", error);
        }
        // The decision type nests the flag in a base class on several SystemUI revisions.
        if (setBooleanField(decision, "shouldInterrupt", false)) return decision;
        moduleLog(Log.WARN, "unable to suppress heads-up decision; keeping native result");
        return decision;
    }

    private boolean setBooleanField(Object target, String name, boolean value) {
        if (target == null) return false;
        for (Class<?> type = target.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object getFieldValue(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void onNotificationPosted(StatusBarNotification sbn) {
        onNotificationPosted(sbn, false);
    }

    private void onNotificationPosted(StatusBarNotification sbn, boolean nativeFluidCloud) {
        try {
            if (sbn == null) return;
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            if (config == null || !config.isEnabled()) return;
            boolean fluidCloud = nativeFluidCloud
                    || isFluidCloudNotification(notification, packageName);
            if (packageName == null || (shouldIgnorePackage(packageName)
                    && !fluidCloud)) return;
            moduleLog(Log.DEBUG, "notification posted: " + packageName);

            if (notification == null || shouldIgnoreNotification(notification, packageName,
                    fluidCloud)) return;
            boolean statusScroll = isStatusScrollNow(sbn, notification);
            moduleLog(Log.INFO, "status ticker decision: " + packageName
                    + " scroll=" + statusScroll
                    + " headsUp=" + (sbn.getKey() == null ? "?" : headsUpByKey.get(sbn.getKey())));
            // Status-bar scrolling is independent of the bubble whitelist and the
            // foreground-app check.
            if (statusScroll) {
                NotificationPayload tickerPayload = buildPayload(sbn, notification);
                showStatusScroll(sbn, notification, tickerPayload);
            }
            // The foreground app only suppresses bubbles while it is actually on screen. When
            // the display is off or the keyguard is showing, the last foreground task is stale
            // and used to swallow every message that arrived on a sleeping device.
            String foregroundPackage = isForegroundVisible() ? getForegroundPackage() : null;
            if (foregroundPackage != null && packageName.equals(foregroundPackage)) return;
            if (!isNotificationAllowedNow(packageName, foregroundPackage)) return;
            if (isDuplicate(sbn.getKey())) return;

            NotificationPayload payload = buildPayload(sbn, notification);
            showBubble(payload, notification.contentIntent, packageName, sbn.getKey(),
                    sbn.getUserId());
        } catch (Throwable error) {
            moduleLog(Log.ERROR, "notification handling failed", error);
        }
    }

    private void onNotificationRemoved(StatusBarNotification sbn) {
        onNotificationRemoved(sbn, -1);
    }

    private void onNotificationRemoved(StatusBarNotification sbn, int reason) {
        if (sbn == null) return;
        String key = sbn.getKey();
        if (TextUtils.isEmpty(key)) return;
        headsUpByKey.remove(key);
        nativeHeadsUpByKey.remove(key);
        if (key.equals(tickingKey)) {
            tickingKey = null;
            activeTickerNotification = null;
            if (statusScrollOverlay != null) statusScrollOverlay.removeImmediately();
        }
        if (fluidCloudNotificationOverlay != null) {
            fluidCloudNotificationOverlay.removeIfSource(sbn);
        }
        QueuedNotification head = notificationQueue.peekFirst();
        if (!shouldDropBubbleForRemoval(reason)) {
            moduleLog(Log.INFO, "notification removed but bubble retained: "
                    + sbn.getPackageName() + ", reason=" + reason);
            return;
        }
        boolean removed = notificationQueue.removeIf(item -> key.equals(item.notificationKey));
        if (!removed) return;
        moduleLog(Log.INFO, "notification bubble removed with notification: " + sbn.getPackageName());
        if (head != null && key.equals(head.notificationKey)) {
            dismissCurrentPreview();
            BubbleView bubble = currentBubble;
            QueuedNotification next = notificationQueue.peekFirst();
            if (bubble != null && bubble.isAttached() && transitioningBubble == null
                    && next != null) {
                // Keep the existing Window alive during notification churn. Recreating it
                // for every removed head can race with consecutive SystemUI callbacks.
                bubble.setIconAnimated(next.payload.icon);
                bubble.setAppBadge(next.payload.appIcon);
                updateChildBubbles();
                showNotificationPreview(bubble, next);
                moduleLog(Log.INFO, "promoted queued notification without replacing bubble");
            } else if (bubble != null && transitioningBubble == null) {
                transitioningBubble = bubble;
                rememberBubblePosition(bubble);
                suppressPreviewForEntry = notificationQueue.peekFirst();
                bubble.dismiss();
                mainHandler.postDelayed(() -> {
                    if (transitioningBubble == bubble) transitioningBubble = null;
                    if (currentBubble == bubble) currentBubble = null;
                    showQueueHead();
                }, 240L);
            } else if (bubble == null) {
                suppressPreviewForEntry = notificationQueue.peekFirst();
                mainHandler.post(this::showQueueHead);
            }
        } else {
            updateChildBubbles();
        }
    }

    /** Keep bubbles when an app process cancels its status entry; only explicit
     * user/SystemUI removal should dismiss the corresponding bubble. */
    private boolean shouldDropBubbleForRemoval(int reason) {
        switch (reason) {
            case 1:  // REASON_CLICK
            case 2:  // REASON_CANCEL
            case 3:  // REASON_CANCEL_ALL
            case 6:  // REASON_USER_STOPPED
            case 7:  // REASON_PACKAGE_BANNED
            case 10: // REASON_LISTENER_CANCEL
            case 11: // REASON_LISTENER_CANCEL_ALL
            case 12: // REASON_GROUP_SUMMARY_CANCELED
            case 15: // REASON_CLEAR_DATA
            case 16: // REASON_CHANNEL_BANNED
            case 18: // REASON_SNOOZED
            case 19: // REASON_TIMEOUT
            case 20: // OEM removal variant
            case 21: // OEM user-dismissed variant
                return true;
            default:
                // The native Fluid Cloud provider has no removal reason and
                // reports -1. Its callback is tied to the exact source key.
                return reason < 0;
        }
    }

    private boolean shouldIgnorePackage(String packageName) {
        return "android".equals(packageName)
                || isSystemUiPackage(packageName)
                || MODULE_PACKAGE.equals(packageName);
    }

    private boolean shouldIgnoreNotification(Notification notification) {
        return shouldIgnoreNotification(notification, null);
    }

    private boolean shouldIgnoreNotification(Notification notification, String packageName) {
        return shouldIgnoreNotification(notification, packageName,
                isFluidCloudNotification(notification, packageName));
    }

    private boolean shouldIgnoreNotification(Notification notification, String packageName,
                                             boolean fluidCloud) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0
                && !fluidCloud);
    }

    private boolean isFluidCloudNotification(Notification notification) {
        return isFluidCloudNotification(notification, null);
    }

    private boolean isFluidCloudNotification(Notification notification, String packageName) {
        if (notification == null || !isIncludeFluidCloudNow()) return false;
        Bundle extras = notification.extras;
        if (extras != null) {
            for (String key : extras.keySet()) {
                String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("fluid") || lower.contains("capsule")
                        || lower.contains("cloud")) return true;
                Object value = extras.get(key);
                if (value instanceof String) {
                    String text = ((String) value).toLowerCase(java.util.Locale.ROOT);
                    if (text.contains("fluid_cloud") || text.contains("fluid cloud")
                            || text.contains("capsule")) return true;
                }
            }
        }
        String category = notification.category;
        String channel = notification.getChannelId();
        return (category != null && category.toLowerCase(java.util.Locale.ROOT).contains("fluid"))
                || (channel != null && channel.toLowerCase(java.util.Locale.ROOT).contains("fluid"))
                || (packageName != null && packageName.contains("systemui")
                && category != null && category.toLowerCase(java.util.Locale.ROOT).contains("system"));
    }

    private boolean isIncludeFluidCloudNow() {
        return config != null && config.isIncludeFluidCloudNotificationsEnabled();
    }

    private boolean isDuplicate(String key) {
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = recentNotifications.put(key, now);
        if (recentNotifications.size() > 64) {
            recentNotifications.entrySet().removeIf(entry -> now - entry.getValue() > 30_000L);
        }
        return last != null && now - last < DUPLICATE_WINDOW_MS;
    }

    private void showBubble(NotificationPayload payload, PendingIntent contentIntent,
                            String packageName, String notificationKey, int userId) {
        QueuedNotification queued = new QueuedNotification(
                payload, contentIntent, packageName, notificationKey, userId);
        if (!TextUtils.isEmpty(notificationKey)) {
            notificationQueue.removeIf(item -> notificationKey.equals(item.notificationKey));
        }
        notificationQueue.addFirst(queued);
        if (notificationQueue.size() > 64) {
            notificationQueue.removeLast();
            moduleLog(Log.WARN, "notification queue capped at 64 entries");
        }
        // A notification that arrives while the display is off is queued like any other and
        // the bubble window is attached right away: it is simply not visible until the screen
        // wakes up. Auto-dismiss is paused for that period (see timeoutPolicyForEntry), so the
        // message is still on screen when the user looks at the device.
        if ((currentBubble == null || !currentBubble.isAttached()) && transitioningBubble == null) {
            currentBubble = null;
            showQueueHead();
        } else {
            updateChildBubbles();
            BubbleView bubble = currentBubble;
            if (bubble != null && bubble.isAttached() && transitioningBubble == null) {
                BubbleTimeoutPolicy timeoutPolicy = timeoutPolicyForEntry(queued);
                bubble.updateTimeout(timeoutPolicy.seconds, timeoutPolicy.dimOnTimeout);
                currentBubbleEntry = queued;
                bubble.setIconAnimated(queued.payload.icon);
                bubble.setAppBadge(queued.payload.appIcon);
                showNotificationPreview(bubble, queued);
            }
        }
    }

    private void showQueueHead() {
        if (currentBubble != null || transitioningBubble != null) return;
        QueuedNotification entry = notificationQueue.peekFirst();
        if (entry == null) return;

        BubbleView bubble = new BubbleView(systemUiContext, config);
        boolean landscape = systemUiContext.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean suggestionEntry = entry.notificationKey != null
                && entry.notificationKey.startsWith("suggestion:");
        boolean keepFloating = suggestionEntry && config != null
                && config.isSuggestionNoEdgeEnabled();
        bubble.setDockingEnabled(!keepFloating);
        if (keepFloating) {
            applyPersistedPosition(bubble, true);
            moduleLog(Log.DEBUG, "suggestion bubble kept floating (no edge)");
        } else if (hasRuntimeBubblePosition && runtimeBubbleLandscape == landscape) {
            bubble.applyRuntimePosition(lastBubbleX, lastBubbleY, lastBubbleDock);
        } else {
            applyPersistedPosition(bubble);
        }
        bubble.setIcon(entry.payload.icon);
        bubble.setAppBadge(entry.payload.appIcon);
        bubble.setAutoDismissValidator(this::isAutoDismissCurrentlyEnabled);
        bubble.setOnClickAction(() -> {
            QueuedNotification head = notificationQueue.peekFirst();
            if (head != null) activateQueuedNotification(bubble, head, true);
        });
        bubble.setOnSwipeListener(up -> {
            QueuedNotification head = notificationQueue.peekFirst();
            if (head == null) return;
            if (up) {
                handleUpSwipe(bubble, head);
            } else {
                teardownUpSwipe();
                dismissQueuedNotification(bubble, head);
            }
        });
        bubble.setOnLongPressAction(() -> {
            if (currentBubble == bubble) {
                teardownUpSwipe();
                showExpandedStack(bubble, true);
            }
        });
        // Long press + swipe up: Material fan-out of the queued bubbles.
        bubble.setOnExpandGestureAction(() -> {
            if (currentBubble == bubble) {
                teardownUpSwipe();
                showExpandedStack(bubble, true);
            }
        });
        bubble.setOnDismissedAction(() -> {
            BubbleStackOverlay stack = expandedStack;
            if (stack != null) {
                expandedStack = null;
                stack.dismiss();
            }
            teardownUpSwipe();
            boolean activeBubble = currentBubble == bubble;
            if (activeBubble) currentBubble = null;
            if (transitioningBubble == bubble || !activeBubble) return;
            rememberBubblePosition(bubble);
            QueuedNotification removed = currentBubbleEntry;
            currentBubbleEntry = null;
            if (notificationQueue.remove(removed)) cleanupImmediateSuggestion(removed.notificationKey);
            dismissCurrentPreviewImmediately();
            suppressPreviewForEntry = notificationQueue.peekFirst();
            mainHandler.post(this::showQueueHead);
        });
        bubble.setOnPositionChangedListener((positionLandscape, x, y, dock) -> {
            persistBubblePosition(positionLandscape, x, y, dock);
            QueuedNotification head = notificationQueue.peekFirst();
            if (!isContentPreviewEdgeOnlyNow() || currentBubble != bubble || head == null) return;
            if (dock == 0) {
                dismissCurrentPreview();
            } else if (currentPreview == null) {
                showNotificationPreview(bubble, head);
            }
        });
        bubble.setOnDragStartedAction(() -> {
            BubbleStackOverlay stack = expandedStack;
            expandedStack = null;
            if (stack != null) stack.dismiss();
            bubble.setStackExpanded(false);
            bubble.setVisibility(View.VISIBLE);
            dismissCurrentPreview();
        });
        bubble.setOnBottomDismissAction(this::dismissAllNotificationBubblesForBottomDrop);
        bubble.setOnConfigurationChangedAction(() -> {
            // A preview window keeps absolute coordinates. Recreate it after the
            // bubble has resolved its new portrait/landscape anchor and insets.
            // The old absolute runtime position belongs to the previous
            // orientation; subsequent bubbles must read the editor's persisted
            // default instead of reusing that stale anchor.
            hasRuntimeBubblePosition = false;
            QueuedNotification rotatedHead = notificationQueue.peekFirst();
            dismissCurrentPreviewImmediately();
            if (rotatedHead != null && currentBubble == bubble
                    && !screenSuspended && transitioningBubble == null) {
                mainHandler.postDelayed(() -> {
                    if (currentBubble == bubble && notificationQueue.peekFirst() == rotatedHead
                            && currentPreview == null) {
                        showNotificationPreview(bubble, rotatedHead);
                    }
                }, 110L);
            }
        });
        bubble.setOnLayoutChangedAction(() -> {
            NotificationPreviewView preview = currentPreview;
            if (preview != null && currentBubble == bubble) {
                preview.updateAnchor(bubble.getPreviewAnchorBounds());
            }
        });
        currentBubble = bubble;
        currentBubbleEntry = entry;
        BubbleTimeoutPolicy timeoutPolicy = timeoutPolicyForEntry(entry);
        boolean showInitialPreview = suppressPreviewForEntry != entry;
        if (suppressPreviewForEntry == entry) suppressPreviewForEntry = null;
        updateChildBubbles();
        if (!bubble.show(timeoutPolicy.seconds, timeoutPolicy.dimOnTimeout)) {
            currentBubble = null;
            dismissCurrentPreviewImmediately();
            moduleLog(Log.ERROR, "WindowManager rejected bubble", bubble.getShowError());
            if (screenSuspended || !isScreenInteractive()) {
                // Overlay windows may be refused while the display is off. Keep the entry
                // queued instead of dropping the message; resumeBubblesAfterScreenOn retries.
                return;
            }
            if (notificationQueue.peekFirst() == entry) notificationQueue.removeFirst();
            mainHandler.post(this::showQueueHead);
        } else {
            if (showInitialPreview) {
                mainHandler.postDelayed(() -> {
                    if (currentBubble == bubble && notificationQueue.peekFirst() == entry) {
                        showNotificationPreview(bubble, entry);
                    }
                }, 40L);
            }
            moduleLog(Log.INFO, "bubble shown for " + entry.packageName
                    + "; queued=" + notificationQueue.size()
                    + "; timeout=" + timeoutPolicy.seconds
                    + "; dim=" + timeoutPolicy.dimOnTimeout);
        }
    }

    private void updateChildBubbles() {
        BubbleView bubble = currentBubble;
        if (bubble == null) return;
        int total = notificationQueue.size();
        bubble.setStackCount(total > 1 ? total : 0);
        // Like the platform bubble stack, the peeking bubbles behind the main one show their
        // own icons instead of plain glass rims.
        List<Drawable> stacked = new ArrayList<>();
        boolean skippedHead = false;
        for (QueuedNotification item : notificationQueue) {
            if (!skippedHead) {
                skippedHead = true;
                continue;
            }
            stacked.add(cloneDrawable(item.payload.icon != null
                    ? item.payload.icon : item.payload.appIcon));
            if (stacked.size() == 3) break;
        }
        bubble.setChildIcons(stacked);
    }

    private void activateQueuedNotification(BubbleView bubble, QueuedNotification entry,
                                            boolean burst) {
        if (currentBubble != bubble || transitioningBubble != null
                || notificationQueue.peekFirst() != entry) return;
        transitioningBubble = bubble;
        rememberBubblePosition(bubble);
        teardownUpSwipe();
        notificationQueue.removeFirst();
        cleanupImmediateSuggestion(entry.notificationKey);
        dismissCurrentPreview();
        Runnable completion = () -> {
            if (transitioningBubble == bubble) transitioningBubble = null;
            if (currentBubble == bubble) currentBubble = null;
            suppressPreviewForEntry = notificationQueue.peekFirst();
            showQueueHead();
        };
        if (burst) {
            openNotificationInFreeform(entry);
            bubble.playBurst(completion);
        } else {
            bubble.dismiss();
            mainHandler.postDelayed(() -> {
                openNotificationInFreeform(entry);
                completion.run();
            }, 240L);
        }
    }

    private void activateQueuedNotificationFullscreen(BubbleView bubble, QueuedNotification entry) {
        if (currentBubble != bubble || transitioningBubble != null
                || notificationQueue.peekFirst() != entry) return;
        transitioningBubble = bubble;
        rememberBubblePosition(bubble);
        notificationQueue.removeFirst();
        dismissCurrentPreview();
        bubble.dismiss();
        mainHandler.postDelayed(() -> {
            if (transitioningBubble == bubble) transitioningBubble = null;
            if (currentBubble == bubble) currentBubble = null;
            openNotificationFullscreen(entry.contentIntent, entry.packageName);
            suppressPreviewForEntry = notificationQueue.peekFirst();
            showQueueHead();
        }, 230L);
    }

    private void dismissQueuedNotification(BubbleView bubble, QueuedNotification entry) {
        if (currentBubble != bubble || transitioningBubble != null
                || notificationQueue.peekFirst() != entry) return;
        transitioningBubble = bubble;
        rememberBubblePosition(bubble);
        if (isClearStatusOnDownSwipeNow()) clearStatusBarNotifications();
        notificationQueue.removeFirst();
        cleanupImmediateSuggestion(entry.notificationKey);
        dismissCurrentPreview();
        BubbleStackOverlay stack = expandedStack;
        if (stack != null) {
            expandedStack = null;
            stack.dismiss();
        }
        bubble.dismiss();
        mainHandler.postDelayed(() -> {
            if (transitioningBubble == bubble) transitioningBubble = null;
            if (currentBubble == bubble) currentBubble = null;
            suppressPreviewForEntry = notificationQueue.peekFirst();
            showQueueHead();
        }, 240L);
        moduleLog(Log.INFO, "current notification bubble dismissed by downward swipe");
    }

    private void dismissAllNotificationBubbles() {
        notificationQueue.clear();
        cancelKeepAwakeOfferNotification();
        dismissCurrentPreview();
        teardownUpSwipe();
        BubbleView bubble = currentBubble;
        currentBubble = null;
        transitioningBubble = null;
        if (bubble != null) bubble.dismiss();
        moduleLog(Log.INFO, "all notification bubbles dismissed by upward swipe");
    }

    private void dismissAllNotificationBubblesForBottomDrop() {
        notificationQueue.clear();
        cancelKeepAwakeOfferNotification();
        dismissCurrentPreviewImmediately();
        teardownUpSwipe();
        moduleLog(Log.INFO, "all notification bubbles dismissed by bottom drop");
    }

    private void showExpandedStack(BubbleView bubble, boolean includeHead) {
        if (bubble == null || notificationQueue.size() <= 1) return;
        BubbleStackOverlay previous = expandedStack;
        if (previous != null) {
            expandedStack = null;
            previous.dismiss();
        }
        List<QueuedNotification> queuedChildren = new ArrayList<>();
        boolean skippedHead = false;
        for (QueuedNotification candidate : notificationQueue) {
            if (!includeHead && !skippedHead) {
                skippedHead = true;
                continue;
            }
            queuedChildren.add(candidate);
            // A fan is only readable up to a handful of bubbles; the rest stay queued.
            if (queuedChildren.size() == MAX_FAN_ITEMS) break;
        }
        if (queuedChildren.isEmpty()) return;
        List<Drawable> icons = new ArrayList<>();
        for (QueuedNotification candidate : queuedChildren) {
            icons.add(cloneDrawable(candidate.payload.icon));
        }
        dismissCurrentPreview();
        teardownUpSwipe();
        BubbleStackOverlay overlay = new BubbleStackOverlay(
                systemUiContext, icons, bubble.getCoreBounds(), bubble.getAlpha(), index -> {
                    if (index >= 0 && index < queuedChildren.size()) {
                        openExpandedNotification(queuedChildren.get(index));
                    }
                }, () -> onStackCollapsed(bubble));
        if (overlay.show()) {
            expandedStack = overlay;
            // Freeze the source bubble and hand its place to the overlay, which draws it.
            bubble.setStackExpanded(true);
            bubble.setVisibility(View.INVISIBLE);
            moduleLog(Log.INFO, "notification bubble fan expanded; count=" + icons.size());
        }
    }

    /** Folds the fan back into the stacked bubbles and repaints the new head. */
    private void onStackCollapsed(BubbleView bubble) {
        if (expandedStack != null) expandedStack = null;
        if (bubble == null) return;
        bubble.setStackExpanded(false);
        bubble.setVisibility(View.VISIBLE);
        QueuedNotification head = notificationQueue.peekFirst();
        if (head == null) {
            // Everything was opened from the fan: the source bubble has nothing left to show.
            if (currentBubble == bubble) currentBubble = null;
            dismissCurrentPreviewImmediately();
            bubble.dismiss();
            moduleLog(Log.INFO, "bubble closed after opening the last queued notification");
            return;
        }
        if (currentBubble == bubble) {
            currentBubbleEntry = head;
            bubble.setIconAnimated(head.payload.icon);
            bubble.setAppBadge(head.payload.appIcon);
            updateChildBubbles();
        }
    }

    private void openExpandedNotification(QueuedNotification entry) {
        if (entry == null || !notificationQueue.remove(entry)) return;
        cleanupImmediateSuggestion(entry.notificationKey);
        BubbleStackOverlay stack = expandedStack;
        expandedStack = null;
        if (stack != null) stack.dismiss();
        dismissCurrentPreview();
        teardownUpSwipe();
        updateChildBubbles();
        openNotificationInFreeform(entry);
        moduleLog(Log.INFO, "expanded bubble selected: " + entry.packageName);
    }

    /**
     * 上滑手势：依次展开各条通知的内容浮层，超过末尾则回到第一条循环展示。
     */
    private void handleUpSwipe(BubbleView bubble, QueuedNotification head) {
        if (notificationQueue.isEmpty() || bubble == null || currentBubble != bubble) return;
        if (!isContentPreviewEnabledNow()) {
            moduleLog(Log.DEBUG, "up-swipe ignored; content preview disabled");
            return;
        }
        if (isDeviceLocked()) return;
        // Rebuild the cycle whenever the queue content changed (not just its size), so a
        // replaced notification can never be previewed again from a stale list.
        List<QueuedNotification> snapshot = new ArrayList<>(notificationQueue);
        boolean sameQueue = upSwipeList != null && upSwipeList.size() == snapshot.size()
                && upSwipeList.containsAll(snapshot) && snapshot.containsAll(upSwipeList);
        if (!sameQueue) {
            upSwipeList = snapshot;
            // The head's preview is the resting state, so the first swipe starts at the next one.
            upSwipeIndex = upSwipeList.size() > 1 ? 1 : 0;
        } else {
            upSwipeIndex = (upSwipeIndex + 1) % upSwipeList.size();
        }
        upSwipeState = UP_SWIPE_PREVIEW;
        showPreviewForEntry(bubble, upSwipeList.get(upSwipeIndex));
        armUpSwipeTimeout();
        moduleLog(Log.DEBUG, "up-swipe preview " + (upSwipeIndex + 1) + "/" + upSwipeList.size());
    }

    /** After a few idle seconds the bubble returns to its head notification. */
    private void armUpSwipeTimeout() {
        mainHandler.removeCallbacks(upSwipeTimeoutAction);
        mainHandler.postDelayed(upSwipeTimeoutAction, UP_SWIPE_TIMEOUT_MS);
    }

    private final Runnable upSwipeTimeoutAction = () -> {
        if (upSwipeState == UP_SWIPE_NONE) return;
        BubbleView bubble = currentBubble;
        upSwipeState = UP_SWIPE_NONE;
        upSwipeList = null;
        upSwipeIndex = 0;
        QueuedNotification head = notificationQueue.peekFirst();
        if (bubble == null || head == null || !bubble.isAttached()) return;
        bubble.setIconAnimated(head.payload.icon);
        bubble.setAppBadge(head.payload.appIcon);
        if (currentPreview != null) showNotificationPreview(bubble, head);
        moduleLog(Log.DEBUG, "up-swipe preview restored to the head notification");
    };

    /** Keeps the main bubble icon synchronized with the notification preview. */
    private void showPreviewForEntry(BubbleView bubble, QueuedNotification entry) {
        if (bubble == null || entry == null) return;
        // Keep the head's look when the preview cannot be shown (disabled, edge-only undocked,
        // keyguard …), otherwise the bubble would advertise content it is not previewing.
        if (!showNotificationPreview(bubble, entry)) return;
        bubble.setIconAnimated(entry.payload.icon);
        bubble.setAppBadge(entry.payload.appIcon);
    }

    private void teardownUpSwipe() {
        mainHandler.removeCallbacks(upSwipeTimeoutAction);
        if (upSwipeState == UP_SWIPE_NONE) return;
        if (upSwipeState == UP_SWIPE_STACK) {
            BubbleStackOverlay stack = expandedStack;
            expandedStack = null;
            if (stack != null) stack.dismiss();
        }
        dismissCurrentPreview();
        upSwipeState = UP_SWIPE_NONE;
        upSwipeList = null;
        upSwipeIndex = 0;
        // The cycle borrowed the bubble icon for another entry; put the head back.
        BubbleView bubble = currentBubble;
        QueuedNotification head = notificationQueue.peekFirst();
        if (bubble != null && head != null && bubble.isAttached()) {
            bubble.setIconAnimated(head.payload.icon);
            bubble.setAppBadge(head.payload.appIcon);
        }
    }

    private boolean showNotificationPreview(BubbleView bubble, QueuedNotification entry) {
        NotificationPayload payload = entry.payload;
        if (expandedStack != null) {
            // The fan owns the screen; a content overlay on the hidden source bubble would
            // float next to nothing.
            moduleLog(Log.DEBUG, "notification preview skipped: bubble fan expanded");
            return false;
        }
        if (!isContentPreviewEnabledNow()) {
            moduleLog(Log.DEBUG, "notification preview skipped: disabled");
            return false;
        }
        if (isDeviceLocked()) {
            moduleLog(Log.DEBUG, "notification preview skipped: keyguard showing");
            return false;
        }
        if (isContentPreviewEdgeOnlyNow() && !bubble.isDocked()) {
            moduleLog(Log.DEBUG, "notification preview skipped: bubble is not docked");
            return false;
        }
        if (TextUtils.isEmpty(payload.title) && TextUtils.isEmpty(payload.body)) {
            moduleLog(Log.DEBUG, "notification preview skipped: empty content");
            return false;
        }
        NotificationPreviewView previous = currentPreview;
        currentPreview = null;
        if (previous != null) previous.removeImmediately();
        Drawable previewIcon = cloneDrawable(payload.icon != null ? payload.icon : payload.appIcon);
        NotificationPreviewView preview = new NotificationPreviewView(
                systemUiContext, previewIcon, payload.appName, payload.title, payload.body,
                config.getContentPreviewAlpha(), config.isContentPreviewAutoWidth(),
                config.getContentPreviewWidthDp(), config.getContentPreviewOverlayAlpha());
        preview.setOnPreviewClickListener(() -> {
            preview.dismiss();
            if (notificationQueue.peekFirst() == entry) {
                activateQueuedNotification(bubble, entry, false);
            } else if (notificationQueue.remove(entry)) {
                updateChildBubbles();
                openNotificationInFreeform(entry);
                teardownUpSwipe();
                moduleLog(Log.INFO, "notification preview selected queued entry: "
                        + entry.packageName);
            }
        });
        preview.setOnDismissedListener(() -> {
            if (currentPreview == preview) currentPreview = null;
            if (currentBubble == bubble) {
                bubble.playPreviewAbsorb();
                bubble.setLandingAnimationSuppressed(false);
                QueuedNotification visibleHead = notificationQueue.peekFirst();
                if (visibleHead != null && visibleHead != entry) {
                    bubble.setIconAnimated(visibleHead.payload.icon);
                    bubble.setAppBadge(visibleHead.payload.appIcon);
                }
            }
        });
        currentPreview = preview;
        int previewTimeoutSeconds = calculatePreviewTimeoutSeconds(payload);
        bubble.setLandingAnimationSuppressed(true);
        if (!preview.show(bubble.getPreviewAnchorBounds(), config.getContentPreviewPosition(),
                previewTimeoutSeconds)) {
            currentPreview = null;
            bubble.setLandingAnimationSuppressed(false);
            if (preview.getShowError() != null) {
                moduleLog(Log.WARN, "WindowManager rejected notification preview", preview.getShowError());
            } else {
                moduleLog(Log.DEBUG, "notification preview skipped: no aligned placement fits");
            }
            return false;
        } else {
            mainHandler.removeCallbacks(previewAnchorTick);
            mainHandler.post(previewAnchorTick);
            moduleLog(Log.INFO, "notification preview shown for " + entry.packageName
                    + "; docked=" + bubble.isDocked()
                    + "; edgeOnly=" + isContentPreviewEdgeOnlyNow()
                    + "; timeout=" + previewTimeoutSeconds + "s");
        }
        return true;
    }

    /**
     * Uses the configured timeout as a floor, then adds time for readable content.
     * CJK glyphs take slightly longer to scan than compact Latin text; a generous
     * cap prevents an accidentally huge notification from keeping the panel forever.
     */
    private int calculatePreviewTimeoutSeconds(NotificationPayload payload) {
        int configured = config == null ? BubbleConfig.DEFAULT_TIMEOUT_SECONDS
                : config.getContentPreviewTimeoutSeconds();
        boolean dynamic = config != null && config.isContentPreviewDynamicTimeout();
        if (!dynamic) return configured;

        String title = payload == null || payload.title == null ? "" : payload.title.trim();
        String body = payload == null || payload.body == null ? "" : payload.body.trim();
        int cjk = 0;
        int latin = 0;
        int whitespace = 0;
        String text = title + "\n" + body;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                whitespace++;
            } else if (ch >= 0x2E80 && ch <= 0x9FFF) {
                cjk++;
            } else {
                latin++;
            }
        }
        float seconds = 1.8f + (cjk / 8.5f) + (latin / 15f) + (whitespace / 45f);
        if (title.length() > 0) seconds += 0.8f;
        return Math.max(configured, Math.min(60, Math.max(3, (int) Math.ceil(seconds))));
    }

    private void persistBubblePosition(boolean landscape, int xPercent,
                                       int yPercent, int dockEdge) {
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("landscape", landscape);
            extras.putInt("x", xPercent);
            extras.putInt("y", yPercent);
            extras.putInt("dock", dockEdge);
            Bundle result = systemUiContext.getContentResolver().call(
                    BubbleConfigProvider.URI,
                    BubbleConfigProvider.METHOD_SET_POSITION,
                    null,
                    extras);
            if (result == null || !result.getBoolean("success")) {
                moduleLog(Log.WARN, "position provider rejected update");
            } else {
                moduleLog(Log.INFO, "position saved: " + xPercent + "," + yPercent
                        + " dock=" + dockEdge + " landscape=" + landscape);
            }
        } catch (Throwable error) {
            moduleLog(Log.WARN, "position persistence failed", error);
        }
    }

    private void rememberBubblePosition(BubbleView bubble) {
        if (bubble == null || systemUiContext == null) return;
        Rect bounds = bubble.getWindowBounds();
        lastBubbleX = bounds.left;
        lastBubbleY = bounds.top;
        lastBubbleDock = bubble.getDockEdge();
        runtimeBubbleLandscape = systemUiContext.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        hasRuntimeBubblePosition = true;
    }

    /**
     * Bubbles are only raised for whitelisted apps. The app blacklist removes a source app
     * entirely (blacklisting WeChat must stop WeChat's bubbles, not only mute it while it
     * happens to be in front of the user), and while a blacklisted app is in the foreground
     * the other apps stay quiet as well.
     */
    private boolean isNotificationAllowedNow(String packageName, String foregroundPackage) {
        return config != null && config.acceptsNotificationFrom(packageName)
                && !config.isBlacklisted(packageName)
                && !config.suppressesWhileForeground(foregroundPackage);
    }

    /** Drops queued entries that the current app rules no longer allow. */
    private void pruneQueuedNotifications() {
        if (notificationQueue.size() <= 1) return;
        QueuedNotification visibleHead = notificationQueue.peekFirst();
        boolean removed = false;
        for (QueuedNotification item : new ArrayList<>(notificationQueue)) {
            if (item == visibleHead) continue;
            if (isQueuedEntryAllowed(item)) continue;
            if (notificationQueue.remove(item)) removed = true;
        }
        if (removed) {
            updateChildBubbles();
            moduleLog(Log.INFO, "queued notifications pruned by updated app rules");
        }
    }

    private boolean isQueuedEntryAllowed(QueuedNotification entry) {
        if (entry == null) return false;
        // Immediate suggestions are module features, not app notifications.
        if (entry.notificationKey != null && entry.notificationKey.startsWith("suggestion:")) {
            return true;
        }
        return config == null || isNotificationAllowedNow(entry.packageName, null);
    }

    /**
 * Whether the given notification should be shown via the status-bar ticker.
 * There is no per-app whitelist: a notification ticks whenever its banner
 * (heads-up) is not displayed. That is the case for apps the user has not
 * enabled banner notifications for, and for every app when the system has
 * globally turned banner notifications off.
 */
private boolean isStatusScrollNow(StatusBarNotification sbn, Notification notification) {
    if (sbn == null || readStatusScrollMode() == BubbleConfig.STATUS_SCROLL_OFF) return false;
    if (!isStatusScrollAllowedNow(sbn)) return false;
    // Fluid Cloud owns the compact surface and can replace the native banner.
    if (readStatusScrollMode() == BubbleConfig.STATUS_SCROLL_FLUID_CLOUD
            && hasPreviewContent(notification)) return true;
    String packageName = sbn.getPackageName();
    // System-wide banner/heads-up master switch: if it is off, every app ticks.
    if (isGlobalBannerOff()) return true;
    String key = sbn.getKey();
    Boolean shown = key == null ? null : nativeHeadsUpByKey.get(key);
    if (Boolean.TRUE.equals(shown)) return false;   // native banner shown
    if (Boolean.FALSE.equals(shown)) return true;    // native banner off -> tick
    // Do not show from channel importance before SystemUI completes its native
    // decision: that creates a brief ticker + banner overlap on ColorOS.
    scheduleStatusScrollRecheck(sbn, notification);
    return false;
}

/** True when the system has globally disabled heads-up/banner notifications. */
private boolean isGlobalBannerOff() {
    try {
        Context ctx = systemUiContext;
        if (ctx == null) return false;
        return android.provider.Settings.Secure.getInt(
                ctx.getContentResolver(), HEADS_UP_ENABLED_SETTING, 1) == 0;
    } catch (Throwable error) {
        moduleLog(Log.DEBUG, "global heads-up setting read failed", error);
        return false;
    }
}

/** Per-app banners are keyed to the channel importance: heads-up only happens for
 *  urgent/high importance channels, so anything below that has no banner. */
private boolean isChannelBannerOff(String packageName, Notification notification) {
    try {
        NotificationManager nm = systemUiContext == null ? null
                : (NotificationManager) systemUiContext.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (nm != null && packageName != null && notification.getChannelId() != null) {
            NotificationChannel channel = nm.getNotificationChannel(
                    packageName, notification.getChannelId());
            if (channel != null) {
                return channel.getImportance() < NotificationManager.IMPORTANCE_HIGH;
            }
        }
        // Legacy notifications have no channel. Their priority is the system's
        // remaining heads-up signal and avoids treating a missing channel lookup
        // as an enabled banner by default.
        if (notification != null && notification.getChannelId() == null) {
            return notification.priority < Notification.PRIORITY_HIGH;
        }
    } catch (Throwable error) {
        moduleLog(Log.DEBUG, "notification channel importance lookup failed", error);
    }
    return false;
}

/**
 * The heads-up decision may be recorded slightly after onNotificationPosted fires.
 * Re-check once a short while later so the ticker still appears for banner-off apps.
 */
private void scheduleStatusScrollRecheck(StatusBarNotification sbn, Notification notification) {
    if (sbn == null || sbn.getKey() == null) return;
    String key = sbn.getKey();
    scheduleStatusScrollRecheck(sbn, notification, key, 0);
}

private void scheduleStatusScrollRecheck(StatusBarNotification sbn,
                                         Notification notification,
                                         String key,
                                         int attempt) {
    long delay = attempt == 0 ? 300L : (attempt == 1 ? 700L : 1200L);
    mainHandler.postDelayed(() -> {
        if (sbn == null || key == null || key.equals(tickingKey)
                || !isStatusScrollEnabledNow() || !isStatusScrollAllowedNow(sbn)) return;
        Boolean shown = nativeHeadsUpByKey.get(key);
        if (Boolean.TRUE.equals(shown)) return;
        boolean bannerOff = Boolean.FALSE.equals(shown)
                || isGlobalBannerOff()
                || isChannelBannerOff(sbn.getPackageName(), notification);
        if (bannerOff) {
            NotificationPayload tickerPayload = buildPayload(sbn, notification);
            showStatusScroll(sbn, notification, tickerPayload);
        } else if (attempt < 2) {
            scheduleStatusScrollRecheck(sbn, notification, key, attempt + 1);
        } else if (shown == null && isChannelBannerOff(sbn.getPackageName(), notification)) {
            // If the channel itself is below Heads-up and the ROM did not
            // expose a decision callback, it is safe to show after the full
            // settle window because the native banner cannot be shown.
            NotificationPayload tickerPayload = buildPayload(sbn, notification);
            showStatusScroll(sbn, notification, tickerPayload);
        }
    }, delay);
}

private boolean isStatusScrollEnabledNow() {
    return readStatusScrollMode() != BubbleConfig.STATUS_SCROLL_OFF;
}

private boolean isStatusScrollAllowedNow(StatusBarNotification sbn) {
    if (sbn == null || TextUtils.isEmpty(sbn.getPackageName())) return false;
    return config == null || config.acceptsStatusScrollFrom(sbn.getPackageName());
}

private int readStatusScrollMode() {
    return config == null ? BubbleConfig.STATUS_SCROLL_FLUID_CLOUD : config.getStatusScrollMode();
}

private boolean isChannelBannerOn(String packageName, Notification notification) {
    if (notification == null) return false;
    try {
        if (notification.getChannelId() == null) {
            return notification.priority >= Notification.PRIORITY_HIGH;
        }
        NotificationManager nm = systemUiContext == null ? null
                : (NotificationManager) systemUiContext.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (nm == null || packageName == null) return false;
        NotificationChannel channel = nm.getNotificationChannel(packageName,
                notification.getChannelId());
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    } catch (Throwable ignored) {
        return false;
    }
}

    private void showStatusScroll(StatusBarNotification sbn, Notification notification,
                                  NotificationPayload payload) {
        int statusScrollMode = readStatusScrollMode();
        if (systemUiContext == null || payload == null || sbn == null
                || statusScrollMode == BubbleConfig.STATUS_SCROLL_OFF
                || !isStatusScrollAllowedNow(sbn)) return;
        String key = sbn.getKey();
        String previousKey = tickingKey;
        if (key != null && key.equals(previousKey)) return;
        tickingKey = key;
        if (statusBarRoot == null && lastSearchedRoot != null
                && lastSearchedRoot.isAttachedToWindow()) {
            findAndAttachStatusBar(lastSearchedRoot);
        }
        if (statusScrollOverlay == null) {
            statusScrollOverlay = new StatusScrollOverlay(systemUiContext);
        }
        statusScrollOverlay.setFinishedAction(this::onStatusTickerFinished);
        boolean fluidShowing = fluidCloudNotificationOverlay != null
                && fluidCloudNotificationOverlay.isShowing();
        boolean fallbackShowing = statusScrollOverlay.isShowing();
        if ((fluidShowing || fallbackShowing) && key != null && !key.equals(previousKey)) {
            pendingTickerSbn = sbn;
            pendingTickerNotification = notification;
            pendingTickerPayload = payload;
            if (fluidShowing) fluidCloudNotificationOverlay.finishCurrentRoundSoon();
            if (fallbackShowing) statusScrollOverlay.finishCurrentRoundSoon();
            moduleLog(Log.INFO, "status ticker queued until current notification finishes");
            return;
        }
        StringBuilder text = new StringBuilder();
        if (!TextUtils.isEmpty(payload.appName)) text.append(payload.appName);
        if (!TextUtils.isEmpty(payload.title)) {
            if (text.length() > 0) text.append("·");
            text.append(payload.title);
        }
        if (!TextUtils.isEmpty(payload.body)) {
            if (text.length() > 0) text.append(" ");
            text.append(payload.body);
        }
        Drawable icon = resolveTickerIcon(sbn, notification);
        activeTickerNotification = new QueuedNotification(
                payload, notification.contentIntent, sbn.getPackageName(), key,
                sbn.getUserId());
        boolean shownInFluidCloud = false;
        if (statusScrollMode == BubbleConfig.STATUS_SCROLL_FLUID_CLOUD) {
            if (fluidCloudNotificationOverlay == null) {
                fluidCloudNotificationOverlay = new FluidCloudNotificationOverlay(systemUiContext);
                fluidCloudNotificationOverlay.setOnFinished(this::onStatusTickerFinished);
            }
            moduleLog(Log.INFO, "fluid cloud ticker show: " + sbn.getPackageName()
                    + ", textLength=" + text.length());
            shownInFluidCloud = fluidCloudNotificationOverlay.show(
                    sbn, payload.appName, payload.title, payload.body, readStatusScrollRounds());
        }
        if (!shownInFluidCloud) {
            if (statusScrollMode == BubbleConfig.STATUS_SCROLL_FLUID_CLOUD) {
                moduleLog(Log.WARN, "fluid cloud overlay failed; falling back to status bar ticker");
            } else {
                moduleLog(Log.INFO, "status bar ticker show: " + sbn.getPackageName()
                        + ", textLength=" + text.length());
            }
            if (statusBarRoot != null) statusScrollOverlay.attachTo(statusBarRoot);
            statusScrollOverlay.show(icon, text.toString(), readStatusScrollRounds());
        }
        // The icon-enhancement module may rewrite the status-bar icon after the
        // notification listener callback. Refresh only the icon, not the text.
        // The icon view can be attached after the notification callback. Retry
        // while the same ticker is active so we do not keep an early square
        // fallback icon when SystemUI finishes binding the real status icon.
        scheduleStatusIconRefresh(sbn, key, 220L, 0);
        scheduleStatusIconRefresh(sbn, key, 650L, 1);
        scheduleStatusIconRefresh(sbn, key, 1_200L, 2);
        scheduleStatusIconRefresh(sbn, key, 2_000L, 3);
        if (statusScrollMode != BubbleConfig.STATUS_SCROLL_FLUID_CLOUD
                && statusBarRoot == null && pendingTickerAttachAttempts < 5) {
            int attempt = ++pendingTickerAttachAttempts;
            mainHandler.postDelayed(() -> {
                if (statusBarRoot == null && lastSearchedRoot != null
                        && lastSearchedRoot.isAttachedToWindow()) {
                    findAndAttachStatusBar(lastSearchedRoot);
                }
                if (statusBarRoot == null && key != null && key.equals(tickingKey)) {
                    // Re-submit the pending content after the status-bar
                    // hierarchy has had time to finish inflation/measurement.
                    showStatusScroll(sbn, notification, payload);
                }
            }, 180L + attempt * 220L);
        }
    }

    private void scheduleStatusIconRefresh(StatusBarNotification sbn, String key,
                                            long delay, int attempt) {
        mainHandler.postDelayed(() -> {
            if (key == null || !key.equals(tickingKey)) return;
            Drawable refreshedIcon = resolveTickerIcon(sbn,
                    sbn == null ? null : sbn.getNotification());
            if (refreshedIcon != null) {
                if (fluidCloudNotificationOverlay != null
                        && fluidCloudNotificationOverlay.isShowing()) {
                    fluidCloudNotificationOverlay.updateIcon(refreshedIcon);
                } else if (statusScrollOverlay != null) {
                    statusScrollOverlay.updateIcon(refreshedIcon);
                }
                moduleLog(Log.DEBUG, "status ticker icon refreshed attempt=" + attempt);
            }
        }, delay);
    }

    private void onStatusTickerFinished() {
        if (statusCloudCollapsed) {
            FluidCloudAnimationBridge.expand(systemUiClassLoader);
            statusCloudCollapsed = false;
        }
        tickingKey = null;
        activeTickerNotification = null;
        StatusBarNotification sbn = pendingTickerSbn;
        Notification notification = pendingTickerNotification;
        NotificationPayload payload = pendingTickerPayload;
        pendingTickerSbn = null;
        pendingTickerNotification = null;
        pendingTickerPayload = null;
        if (sbn != null && notification != null && payload != null) {
            showStatusScroll(sbn, notification, payload);
        }
    }

    private void removeStatusTickerSurfaces() {
        tickingKey = null;
        activeTickerNotification = null;
        pendingTickerSbn = null;
        pendingTickerNotification = null;
        pendingTickerPayload = null;
        if (statusScrollOverlay != null) statusScrollOverlay.removeImmediately();
        if (fluidCloudNotificationOverlay != null) fluidCloudNotificationOverlay.removeImmediately();
        if (statusCloudCollapsed) {
            FluidCloudAnimationBridge.expand(systemUiClassLoader);
            statusCloudCollapsed = false;
        }
    }

    /** The status ticker must use the compact monochrome smallIcon, never a large/contact icon. */
    private Drawable resolveTickerIcon(StatusBarNotification sbn, Notification notification) {
        // The icon that SystemUI currently renders in its own status bar is the best
        // source: it has already gone through the host's icon pipeline (including
        // ColorOS 通知图标增强 / host icon replacement and tinting), so it reuses exactly
        // the system's monochrome glyph instead of the raw app icon.
        Drawable icon = resolveStatusBarIconViewIcon(sbn);
        if (icon != null) return monochromeTickerIcon(icon);
        // Older ColorOS 通知图标增强 builds (<= 1.x) exposed a replacement cache to
        // other SystemUI modules. 2.x no longer has that cache class and instead
        // rewrites the StatusBarIconView drawable, which the lookup above already
        // covers; keep this only as a fallback for older module installs.
        icon = resolveLegacyIconEnhancementCache(sbn);
        if (icon == null) icon = resolveStatusBarIcon(sbn, notification);
        return monochromeTickerIcon(icon);
    }

    /**
     * Copies the drawable held by the SystemUI StatusBarIconView that currently shows
     * this notification. The status-bar icon view owns the processed (tinted/themed)
     * small icon: ColorOS 通知图标增强 (com.fankes.coloros.notify 2.x) rewrites exactly
     * this view through StatusBarIconView.setIcon/setImageDrawable, so this is the
     * reliable way to get the same monochrome glyph the system displays. The lookup
     * is keyed by the notification key, so a recycled slot can never lend its icon
     * to another notification.
     */
    private Drawable resolveStatusBarIconViewIcon(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) return null;
        View root = statusBarRoot != null ? statusBarRoot : lastSearchedRoot;
        if (root == null) return null;
        try {
            return findStatusBarIconViewDrawable(root, sbn.getKey(), 0);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "status-bar icon view lookup failed", error);
            return null;
        }
    }

    private Drawable findStatusBarIconViewDrawable(View view, String key, int depth) {
        if (view == null || depth > 12) return null;
        if (view instanceof android.widget.ImageView
                && view.getClass().getName().contains("StatusBarIconView")) {
            try {
                StatusBarNotification shown = statusBarNotificationOf(view);
                if (shown != null && key.equals(shown.getKey())) {
                    Drawable drawable = ((android.widget.ImageView) view).getDrawable();
                    if (drawable != null) return cloneDrawable(drawable);
                }
            } catch (Throwable ignored) {
                // Not a stock StatusBarIconView, or the slot has no notification yet.
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Drawable found = findStatusBarIconViewDrawable(group.getChildAt(i), key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private StatusBarNotification statusBarNotificationOf(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class;
             type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod("getNotification");
                method.setAccessible(true);
                Object value = method.invoke(view);
                if (value instanceof StatusBarNotification) {
                    return (StatusBarNotification) value;
                }
                return null;
            } catch (NoSuchMethodException ignored) {
                // keep walking up to the declaring class
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Notification small icons are allowed to be adaptive icons. Drawing a whole
     * adaptive icon at ticker size paints its background layer as a filled (single
     * colour) square, which is the intermittent "单色方块". Prefer the monochrome
     * layer, then the foreground layer, so only the glyph shape is drawn.
     */
    private Drawable monochromeTickerIcon(Drawable icon) {
        if (!(icon instanceof AdaptiveIconDrawable)) return icon;
        try {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
            Drawable monochrome = adaptive.getMonochrome();
            if (monochrome != null) {
                return monochrome.getConstantState() == null ? monochrome
                        : monochrome.getConstantState().newDrawable().mutate();
            }
            Drawable foreground = adaptive.getForeground();
            if (foreground != null) {
                return foreground.getConstantState() == null ? foreground
                        : foreground.getConstantState().newDrawable().mutate();
            }
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "adaptive ticker icon normalization failed", error);
        }
        return icon;
    }

    /** The final fallback reads the notification's compact monochrome smallIcon. */
    private Drawable resolveStatusBarIcon(StatusBarNotification sbn, Notification notification) {
        if (sbn == null || notification == null || notification.getSmallIcon() == null) return null;
        try {
            Context appContext = systemUiContext.createPackageContext(sbn.getPackageName(), 0);
            Drawable icon = notification.getSmallIcon().loadDrawable(appContext);
            return icon == null ? null : icon.mutate();
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "unable to load status-bar small icon", error);
            return null;
        }
    }

    private Class<?> loadSystemUiClass(String name) {
        ClassLoader loader = systemUiClassLoader;
        if (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        try {
            ClassLoader own = BubbleNotificationHook.class.getClassLoader();
            if (own == null || own == loader) return null;
            return Class.forName(name, false, own);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private int readStatusScrollRounds() {
        BubbleConfig bubbleConfig = this.config;
        if (bubbleConfig == null) {
            return 1;
        }
        return bubbleConfig.getStatusScrollRounds();
    }

    private void applyPersistedPosition(BubbleView bubble) {
        applyPersistedPosition(bubble, false);
    }

    /** @param undocked keep the bubble off the screen edge (临时通知不贴边). */
    private void applyPersistedPosition(BubbleView bubble, boolean undocked) {
        if (bubble == null || config == null || systemUiContext == null) return;
        boolean landscape = systemUiContext.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        bubble.applyPersistedPosition(config.getPositionX(landscape), config.getPositionY(landscape),
                undocked ? 0 : config.getDockEdge(landscape));
    }

        public void dismissCurrentPreview() {
        NotificationPreviewView notificationPreviewView = this.currentPreview;
        this.currentPreview = null;
        if (notificationPreviewView != null) {
            notificationPreviewView.dismiss();
        }
    }

    private void dismissCurrentPreviewImmediately() {
        NotificationPreviewView notificationPreviewView = this.currentPreview;
        this.currentPreview = null;
        if (notificationPreviewView != null) {
            notificationPreviewView.removeImmediately();
        }
    }

    private BubbleTimeoutPolicy readBubbleTimeoutPolicy() {
        BubbleConfig bubbleConfig = this.config;
        boolean z = bubbleConfig != null && bubbleConfig.isBubbleAutoDismissEnabled();
        return new BubbleTimeoutPolicy(z ? this.config.getTimeoutSeconds() : 0, z && this.config.shouldDimBubbleOnTimeout());
    }

        public boolean isAutoDismissCurrentlyEnabled() {
        BubbleConfig bubbleConfig = this.config;
        return bubbleConfig != null && bubbleConfig.isBubbleAutoDismissEnabled();
    }

    private boolean isContentPreviewEdgeOnlyNow() {
        BubbleConfig bubbleConfig = this.config;
        return bubbleConfig != null && bubbleConfig.isContentPreviewEdgeOnly();
    }

    private boolean isContentPreviewEnabledNow() {
        BubbleConfig bubbleConfig = this.config;
        return bubbleConfig != null && bubbleConfig.isContentPreviewEnabled();
    }

    private boolean isBubbleEnabledNow() {
        BubbleConfig bubbleConfig = this.config;
        return bubbleConfig != null && bubbleConfig.isEnabled();
    }

    private boolean isContentPreviewSuppressBannerNow() {
        BubbleConfig bubbleConfig = this.config;
        return bubbleConfig != null && bubbleConfig.isContentPreviewSuppressBanner();
    }

    private boolean isClearStatusOnDownSwipeNow() {
        BubbleConfig bubbleConfig = this.config;
        return bubbleConfig != null && bubbleConfig.isClearStatusOnDownSwipeEnabled();
    }

    /**
     * 下滑关闭气泡时连带清除通知栏里的对应通知。必须跳过不可清除的通知：通话界面、常驻/前台
     * 服务、媒体控制、系统与通话类包，否则会把通话界面（in-call UI）一起清掉，导致电话界面
     * 再也打不开。
     */
    private void clearStatusBarNotifications() {
        Object listener = systemUiNotificationListener;
        if (listener == null) return;
        try {
            int cleared = 0;
            for (Method method : listener.getClass().getMethods()) {
                String name = method.getName();
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!("cancelNotification".equals(name) || "removeNotification".equals(name))
                        || parameterTypes.length != 1
                        || parameterTypes[0] != String.class) {
                    continue;
                }
                for (QueuedNotification queued : new ArrayList<>(notificationQueue)) {
                    if (queued == null || TextUtils.isEmpty(queued.notificationKey)) continue;
                    if (isProtectedNotification(queued.notificationKey, queued.packageName)) {
                        moduleLog(Log.DEBUG, "keep protected notification: " + queued.packageName);
                        continue;
                    }
                    try {
                        method.invoke(listener, queued.notificationKey);
                        cleared++;
                    } catch (Throwable ignored) {
                    }
                }
                if (cleared > 0) break;
            }
            moduleLog(Log.INFO, "status-bar notifications cleared by downward swipe: " + cleared);
        } catch (Throwable error) {
            moduleLog(Log.WARN, "unable to clear status-bar notifications", error);
        }
    }

    /** Call UI, ongoing / foreground-service, media and system notifications are off limits. */
    private boolean isProtectedNotification(String key, String packageName) {
        if (TextUtils.isEmpty(key)) return true;
        if (isProtectedPackage(packageName)) return true;
        try {
            for (StatusBarNotification active : notificationListenerActiveNotifications()) {
                if (active == null || !key.equals(active.getKey())) continue;
                Notification notification = active.getNotification();
                if (notification == null) return true;
                if (active.isOngoing()) return true;
                if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return true;
                if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return true;
                String category = notification.category;
                if (Notification.CATEGORY_CALL.equals(category)
                        || Notification.CATEGORY_TRANSPORT.equals(category)
                        || Notification.CATEGORY_SERVICE.equals(category)
                        || Notification.CATEGORY_SYSTEM.equals(category)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isProtectedPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (isSystemUiPackage(packageName)) return true;
        for (String guarded : PROTECTED_NOTIFICATION_PACKAGES) {
            if (guarded.equals(packageName)) return true;
        }
        return false;
    }

    /** Best effort list of currently posted notifications, used for the clear-protection check. */
    private List<StatusBarNotification> notificationListenerActiveNotifications() {
        List<StatusBarNotification> result = new ArrayList<>();
        Object listener = systemUiNotificationListener;
        if (listener == null) return result;
        try {
            Method method = listener.getClass().getMethod("getActiveNotifications");
            Object value = method.invoke(listener);
            if (value instanceof StatusBarNotification[]) {
                for (StatusBarNotification sbn : (StatusBarNotification[]) value) result.add(sbn);
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item instanceof StatusBarNotification) result.add((StatusBarNotification) item);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private boolean hasPreviewContent(Notification notification) {
        Bundle bundle = notification == null ? null : notification.extras;
        if (bundle == null) {
            return false;
        }
        CharSequence charSequence = bundle.getCharSequence("android.conversationTitle");
        if (TextUtils.isEmpty(charSequence)) {
            charSequence = bundle.getCharSequence("android.title");
        }
        List<Notification.MessagingStyle.Message> messages = getMessages(bundle);
        CharSequence text = messages.isEmpty() ? null : messages.get(messages.size() - 1).getText();
        if (TextUtils.isEmpty(text)) {
            text = bundle.getCharSequence("android.bigText");
        }
        if (TextUtils.isEmpty(text)) {
            text = bundle.getCharSequence("android.text");
        }
        if (TextUtils.isEmpty(text)) {
            text = bundle.getCharSequence("android.subText");
        }
        return (TextUtils.isEmpty(charSequence) && TextUtils.isEmpty(text)) ? false : true;
    }

    private void openNotificationInFreeform(QueuedNotification entry) {
        if (entry == null) return;
        mainHandler.post(() -> {
            // Immediate suggestions carry their own action instead of a notification.
            if (entry.directAction != null) {
                try {
                    entry.directAction.run();
                } catch (Throwable error) {
                    moduleLog(Log.WARN, "suggestion action failed: " + entry.packageName, error);
                }
                return;
            }
            try {
                if (!TextUtils.isEmpty(entry.notificationKey)
                        && performSystemUiNotificationClick(entry.notificationKey)) {
                    moduleLog(Log.INFO, "notification opened through SystemUI: "
                            + entry.packageName);
                    return;
                }
            } catch (Throwable error) {
                moduleLog(Log.DEBUG, "SystemUI notification click unavailable", error);
            }
            // A notification posted by a ColorOS app clone (应用分身) belongs to a
            // secondary Android user. Launching it by package name from SystemUI's
            // user 0 context makes OplusMultiAppManagerService treat the start as an
            // ambiguous multi-app launch and show its "选择应用" resolver. Re-send the
            // notification's own PendingIntent instead: it is bound to the clone user,
            // so the target instance is unambiguous. The freeform ticket still forces
            // the small-window launch in system_server.
            if (isSecondaryUser(entry.userId) && entry.contentIntent != null) {
                prepareFreeformTicket(entry.packageName);
                moduleLog(Log.INFO, "cross-user notification opened through content intent: "
                        + entry.packageName + " user=" + entry.userId);
                openNotificationFullscreen(entry.contentIntent, entry.packageName);
                return;
            }
            if (prepareFreeformTicket(entry.packageName)) {
                launchPackageInFreeform(entry.packageName, createFreeformLaunchOptions(),
                        entry.userId);
                moduleLog(Log.INFO, "freeform launch requested for " + entry.packageName
                        + (entry.userId < 0 ? "" : " user=" + entry.userId));
                return;
            }
            openNotificationFullscreen(entry.contentIntent, entry.packageName);
        });
    }

    private Bundle createFreeformLaunchOptions() {
        ActivityOptions activityOptionsMakeBasic = ActivityOptions.makeBasic();
        activityOptionsMakeBasic.setPendingIntentBackgroundActivityLaunchAllowed(true);
        activityOptionsMakeBasic.setPendingIntentBackgroundActivityStartMode(1);
        try {
            ActivityOptions.class.getMethod("setLaunchWindowingMode", Integer.TYPE).invoke(activityOptionsMakeBasic, 100);
        } catch (Throwable unused) {
        }
        Bundle bundle = activityOptionsMakeBasic.toBundle();
        bundle.putInt("android.activity.windowingMode", 100);
        bundle.putInt("android:activity.mZoomLaunchFlags", 4);
        return bundle;
    }

    private boolean performSystemUiNotificationClick(String str) throws Throwable {
        Object obj = this.systemUiNotificationListener;
        if (obj == null) {
            return false;
        }
        Field declaredField = obj.getClass().getDeclaredField("mNotificationHandlers");
        declaredField.setAccessible(true);
        Object obj2 = declaredField.get(obj);
        if (!(obj2 instanceof Iterable)) {
            return false;
        }
        Iterator it = ((Iterable) obj2).iterator();
        while (it.hasNext()) {
            Object objFindNotificationEntry = findNotificationEntry(it.next(), str);
            if (objFindNotificationEntry != null) {
                Class[] clsArr = new Class[0];
                Object objInvoke = objFindNotificationEntry.getClass().getMethod("getRow", null).invoke(objFindNotificationEntry, null);
                if (objInvoke instanceof View) {
                    return ((View) objInvoke).performClick();
                }
                return false;
            }
        }
        return false;
    }

    private Object findNotificationEntry(Object obj, String str) {
        Object obj2 = null;
        if (obj == null) {
            return null;
        }
        for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
            for (Method method : superclass.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if ("getEntry".equals(method.getName()) && parameterTypes.length == 1 && parameterTypes[0] == String.class) {
                    try {
                        method.setAccessible(true);
                        return method.invoke(obj, str);
                    } catch (Throwable unused) {
                        continue;
                    }
                }
            }
        }
        return obj2;
    }

    private boolean prepareFreeformTicket(String str) {
        try {
            Bundle bundle = new Bundle();
            bundle.putString("package", str);
            Bundle bundleCall = this.systemUiContext.getContentResolver().call(BubbleConfigProvider.URI, BubbleConfigProvider.METHOD_PREPARE_FREEFORM_LAUNCH, (String) null, bundle);
            return bundleCall != null && bundleCall.getBoolean(BubbleConfigProvider.EXTRA_SUCCESS);
        } catch (Throwable th) {
            moduleLog(Log.WARN, "freeform launch ticket preparation failed", th);
            return false;
        }
    }

    private void openNotificationFullscreen(PendingIntent contentIntent, String packageName) {
        mainHandler.post(() -> {
            if (contentIntent == null) {
                moduleLog(Log.WARN, "no content intent for " + packageName);
                return;
            }
            try {
                resumeAppSwitches();
                contentIntent.send(systemUiContext, 0, null, null, null, null,
                        createFreeformLaunchOptions());
                moduleLog(Log.INFO, "notification opened without freeform: " + packageName);
            } catch (Throwable error) {
                moduleLog(Log.WARN, "unable to open notification for " + packageName, error);
            }
        });
    }

    private void resumeAppSwitches() {
        try {
            Class[] clsArr = new Class[0];
            Method declaredMethod = ActivityManager.class.getDeclaredMethod("getService", null);
            declaredMethod.setAccessible(true);
            Object objInvoke = declaredMethod.invoke(null, null);
            Class[] clsArr2 = new Class[0];
            objInvoke.getClass().getMethod("resumeAppSwitches", null).invoke(objInvoke, null);
        } catch (Throwable th) {
            moduleLog(Log.DEBUG, "resumeAppSwitches unavailable", th);
        }
    }

    /**
     * Launches the package's launcher activity in freeform. When the notification was
     * posted by a secondary user (a ColorOS app clone), the launch has to run in that
     * user and carry the multi-app hint, otherwise OplusMultiAppManagerService shows the
     * "选择应用" multi-app resolver before the app starts.
     */
    private void launchPackageInFreeform(String str, Bundle bundle, int userId) {
        try {
            Context launchContext = contextForUser(userId);
            Intent launchIntentForPackage = launchContext.getPackageManager()
                    .getLaunchIntentForPackage(str);
            if (launchIntentForPackage == null) {
                return;
            }
            applyMultiAppLaunchHint(launchIntentForPackage, userId);
            launchIntentForPackage.addFlags(402653184);
            launchContext.startActivity(launchIntentForPackage, bundle);
        } catch (Throwable th) {
            moduleLog(Log.ERROR, "fallback freeform launch failed for " + str, th);
        }
    }

    /**
     * Pins a package launch to the notification's app instance for ColorOS multi-app.
     * <ul>
     *   <li>{@code oplus.intent.extra.cached_userId} carries the instance user for the
     *       resolution step (user 0 = the original app, 999 = a clone).</li>
     *   <li>{@code OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER} stops
     *       {@code OplusMultiAppManagerService.shouldShowResolverActivity} from replacing
     *       the launch with the "选择应用" resolver when the target package has clones.</li>
     * </ul>
     * Both identifiers are OPPO framework internals; every step is best effort so a
     * non-ColorOS host simply ignores the hint.
     */
    private void applyMultiAppLaunchHint(Intent intent, int userId) {
        if (intent == null) return;
        if (userId >= 0) {
            intent.putExtra(MULTI_APP_CACHED_USER_ID_EXTRA, userId);
        }
        try {
            Class<?> extClass = Class.forName("android.content.IIntentExt");
            int skipChooser = extClass.getField("OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER")
                    .getInt(null);
            Object ext = Intent.class.getMethod("getIntentExt").invoke(intent);
            if (ext == null) return;
            ext.getClass().getMethod("addOplusFlags", int.class).invoke(ext, skipChooser);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "multi-app launch hint unavailable", error);
        }
    }

    /** Context for the user that owns the notification; the current user reuses SystemUI's. */
    private Context contextForUser(int userId) {
        if (!isSecondaryUser(userId)) {
            return systemUiContext;
        }
        try {
            Method method = Context.class.getMethod(
                    "createContextAsUser", UserHandle.class, int.class);
            Object context = method.invoke(systemUiContext,
                    UserHandle.getUserHandleForUid(userId * 100000), 0);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable error) {
            moduleLog(Log.WARN, "unable to create context for user " + userId, error);
        }
        return systemUiContext;
    }

    private boolean isSecondaryUser(int userId) {
        return userId >= 0 && userId != currentUserId();
    }

    /** SystemUI runs as the device owner; userId is derived from the process uid. */
    private int currentUserId() {
        return android.os.Process.myUid() / 100000;
    }

    private Drawable resolvePersonIcon(Notification notification, Context context) {
        Drawable drawableLoadDrawable;
        Drawable drawableLoadDrawable2;
        Bundle bundle = notification.extras;
        if (bundle == null) {
            return null;
        }
        List<Notification.MessagingStyle.Message> messages = getMessages(bundle);
        for (int size = messages.size() - 1; size >= 0; size--) {
            Person senderPerson = messages.get(size).getSenderPerson();
            if (senderPerson != null && senderPerson.getIcon() != null && (drawableLoadDrawable2 = senderPerson.getIcon().loadDrawable(context)) != null) {
                return drawableLoadDrawable2;
            }
        }
        ArrayList<Person> parcelableArrayList = bundle.getParcelableArrayList("android.people.list", Person.class);
        if (parcelableArrayList != null) {
            for (Person person : parcelableArrayList) {
                if (person != null && person.getIcon() != null && (drawableLoadDrawable = person.getIcon().loadDrawable(context)) != null) {
                    return drawableLoadDrawable;
                }
            }
        }
        return null;
    }

    private Drawable resolveShortcutIcon(StatusBarNotification statusBarNotification, Notification notification) {
        List<ShortcutInfo> shortcuts;
        String shortcutId = notification.getShortcutId();
        if (TextUtils.isEmpty(shortcutId)) {
            return null;
        }
        try {
            LauncherApps launcherApps = (LauncherApps) this.systemUiContext.getSystemService(LauncherApps.class);
            if (launcherApps != null && (shortcuts = launcherApps.getShortcuts(new LauncherApps.ShortcutQuery().setPackage(statusBarNotification.getPackageName()).setShortcutIds(Collections.singletonList(shortcutId)).setQueryFlags(19), statusBarNotification.getUser())) != null && !shortcuts.isEmpty()) {
                return launcherApps.getShortcutIconDrawable(shortcuts.get(0), getResourcesDensity());
            }
            return null;
        } catch (Throwable th) {
            moduleLog(Log.DEBUG, "conversation shortcut icon unavailable", th);
            return null;
        }
    }

    private List<Notification.MessagingStyle.Message> getMessages(Bundle bundle) {
        try {
            Parcelable[] parcelableArray = bundle.getParcelableArray("android.messages");
            if (parcelableArray == null) {
                return Collections.emptyList();
            }
            return Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcelableArray);
        } catch (Throwable unused) {
            return Collections.emptyList();
        }
    }

    private Drawable cloneDrawable(Drawable drawable) {
        return (drawable == null || drawable.getConstantState() == null) ? drawable : drawable.getConstantState().newDrawable(this.systemUiContext.getResources()).mutate();
    }

        public boolean isDeviceLocked() {
        KeyguardManager keyguardManager = (KeyguardManager) this.systemUiContext.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    private int getResourcesDensity() {
        return this.systemUiContext.getResources().getDisplayMetrics().densityDpi;
    }

    private String stringValue(CharSequence charSequence) {
        return charSequence == null ? "" : charSequence.toString();
    }

        public String getForegroundPackage() {
        try {
            ActivityManager activityManager = (ActivityManager) this.systemUiContext.getSystemService(ActivityManager.class);
            if (activityManager == null) {
                return null;
            }
            List<ActivityManager.RunningTaskInfo> runningTasks = activityManager.getRunningTasks(1);
            if (!runningTasks.isEmpty() && runningTasks.get(0).topActivity != null) {
                return runningTasks.get(0).topActivity.getPackageName();
            }
            return null;
        } catch (Throwable th) {
            moduleLog(Log.WARN, "foreground package lookup failed", th);
            return null;
        }
    }

        public boolean isScreenAwakePromptEnabled() {
        BubbleConfig bubbleConfig = this.config;
        if (bubbleConfig != null) {
            return bubbleConfig.isImmediateSuggestionsEnabled() && this.config.isScreenAwakePromptEnabled();
        }
        return true;
    }

    private Drawable resolveLegacyIconEnhancementCache(StatusBarNotification statusBarNotification) {
        Drawable drawableLoadDrawable;
        if (statusBarNotification != null && this.systemUiContext != null) {
            try {
                Class<?> clsLoadSystemUiClass = loadSystemUiClass("com.fankes.coloros.notify.hook.systemui.StatusBarIconReplacementCache");
                if (clsLoadSystemUiClass == null) {
                    return null;
                }
                Field declaredField = clsLoadSystemUiClass.getDeclaredField("INSTANCE");
                declaredField.setAccessible(true);
                Object obj = declaredField.get(null);
                Method declaredMethod = clsLoadSystemUiClass.getDeclaredMethod("iconFor", String.class, String.class);
                declaredMethod.setAccessible(true);
                Object objInvoke = declaredMethod.invoke(obj, statusBarNotification.getKey(), statusBarNotification.getPackageName());
                if (!(objInvoke instanceof Icon) || (drawableLoadDrawable = ((Icon) objInvoke).loadDrawable(this.systemUiContext)) == null) {
                    return null;
                }
                moduleLog(Log.DEBUG, "using legacy icon-enhancement cache icon for "
                        + statusBarNotification.getPackageName());
                return drawableLoadDrawable.mutate();
            } catch (Throwable th) {
                moduleLog(Log.DEBUG, "legacy icon-enhancement cache lookup failed", th);
            }
        }
        return null;
    }

    private Drawable resolveNotificationIcon(StatusBarNotification statusBarNotification, Notification notification) {
        Drawable drawableLoadDrawable;
        String packageName = statusBarNotification.getPackageName();
        try {
            Context contextCreatePackageContext = this.systemUiContext.createPackageContext(packageName, 0);
            if (notification.getLargeIcon() != null && (drawableLoadDrawable = notification.getLargeIcon().loadDrawable(contextCreatePackageContext)) != null) {
                return drawableLoadDrawable;
            }
            Drawable drawableResolvePersonIcon = resolvePersonIcon(notification, contextCreatePackageContext);
            if (drawableResolvePersonIcon != null) {
                return drawableResolvePersonIcon;
            }
            Drawable drawableResolveShortcutIcon = resolveShortcutIcon(statusBarNotification, notification);
            if (drawableResolveShortcutIcon != null) {
                return drawableResolveShortcutIcon;
            }
            Drawable drawableLoadIcon = this.systemUiContext.getPackageManager().getApplicationInfo(packageName, 0).loadIcon(this.systemUiContext.getPackageManager());
            if (drawableLoadIcon != null) {
                return drawableLoadIcon;
            }
            if (notification.getSmallIcon() != null) {
                return notification.getSmallIcon().loadDrawable(contextCreatePackageContext);
            }
        } catch (Throwable th) {
            moduleLog(Log.WARN, "unable to load icon for " + packageName, th);
        }
        return this.systemUiContext.getDrawable(android.R.drawable.sym_def_app_icon);
    }

    private NotificationPayload buildPayload(StatusBarNotification statusBarNotification, Notification notification) {
        Drawable drawableLoadIcon;
        CharSequence charSequence;
        String packageName = statusBarNotification.getPackageName();
        Drawable drawableResolveNotificationIcon = resolveNotificationIcon(statusBarNotification, notification);
        CharSequence charSequence2 = null;
        try {
            ApplicationInfo applicationInfo = this.systemUiContext.getPackageManager().getApplicationInfo(packageName, 0);
            drawableLoadIcon = applicationInfo.loadIcon(this.systemUiContext.getPackageManager());
            try {
                CharSequence charSequenceLoadLabel = applicationInfo.loadLabel(this.systemUiContext.getPackageManager());
                if (charSequenceLoadLabel != null) {
                    packageName = charSequenceLoadLabel.toString();
                }
            } catch (Throwable unused) {
            }
        } catch (Throwable unused2) {
            drawableLoadIcon = null;
        }
        String str = packageName;
        Bundle bundle = notification.extras;
        if (bundle != null) {
            CharSequence charSequence3 = bundle.getCharSequence("android.conversationTitle");
            if (TextUtils.isEmpty(charSequence3)) {
                charSequence3 = bundle.getCharSequence("android.title");
            }
            List<Notification.MessagingStyle.Message> messages = getMessages(bundle);
            if (!messages.isEmpty()) {
                Notification.MessagingStyle.Message message = messages.get(messages.size() - 1);
                CharSequence text = message.getText();
                if (TextUtils.isEmpty(charSequence3) && message.getSenderPerson() != null) {
                    charSequence3 = message.getSenderPerson().getName();
                }
                charSequence2 = text;
            }
            if (TextUtils.isEmpty(charSequence2)) {
                charSequence2 = bundle.getCharSequence("android.bigText");
            }
            if (TextUtils.isEmpty(charSequence2)) {
                charSequence2 = bundle.getCharSequence("android.text");
            }
            if (TextUtils.isEmpty(charSequence2)) {
                charSequence2 = bundle.getCharSequence("android.subText");
            }
            charSequence = charSequence2;
            charSequence2 = charSequence3;
        } else {
            charSequence = null;
        }
        return new NotificationPayload(drawableResolveNotificationIcon, drawableLoadIcon, str, stringValue(charSequence2), stringValue(charSequence));
    }

    /**
     * 半暗屏幕被点击后的"保持常亮"建议。它和通知/普通即时建议一样进入队列，因此会与其它气泡
     * 堆叠、一起扇出；同一个 key 保证新的建议直接替换旧的，而不会叠出第二个常亮气泡。
     */
    private boolean showKeepScreenAwakePrompt() {
        if (screenSuspended || isDeviceLocked() || !isScreenInteractive()) return false;
        if (awakeGuardView != null || (awakeWakeLock != null && awakeWakeLock.isHeld())) {
            moduleLog(Log.DEBUG, "keep-awake suggestion skipped; already keeping awake");
            return false;
        }
        if (config == null || !config.isEnabled() || !config.isImmediateSuggestionsEnabled()
                || !config.isScreenAwakePromptEnabled()) {
            return false;
        }
        enqueueImmediateSuggestion(new ImmediateSuggestionController.Suggestion(
                KEEP_AWAKE_SUGGESTION_ID,
                systemUiPackageName,
                new MaterialSuggestionIcon(MaterialSuggestionIcon.KEEP_AWAKE),
                null,
                "保持屏幕常亮",
                "点击保持当前界面常亮",
                this::enableKeepScreenAwake,
                0));
        // 同时登记到状态栏，这样它和普通通知一样能在通知栏里看到、点击、滑掉。
        postKeepAwakeOfferNotification();
        moduleLog(Log.INFO, "keep-awake suggestion queued");
        return true;
    }

    /** Removes the keep-awake offer (it must not survive into the lock screen). */
    private void dismissKeepAwakeSuggestion() {
        String key = "suggestion:" + KEEP_AWAKE_SUGGESTION_ID;
        QueuedNotification head = notificationQueue.peekFirst();
        boolean wasHead = head != null && key.equals(head.notificationKey);
        if (!notificationQueue.removeIf(item -> key.equals(item.notificationKey))) return;
        cleanupImmediateSuggestion(key);
        if (!wasHead) {
            updateChildBubbles();
            return;
        }
        BubbleView bubble = currentBubble;
        currentBubble = null;
        dismissCurrentPreviewImmediately();
        if (bubble == null) {
            showQueueHead();
            return;
        }
        transitioningBubble = bubble;
        bubble.dismiss();
        mainHandler.postDelayed(() -> {
            if (transitioningBubble == bubble) transitioningBubble = null;
            if (currentBubble == bubble) currentBubble = null;
            suppressPreviewForEntry = notificationQueue.peekFirst();
            showQueueHead();
        }, 240L);
    }

    private void enableKeepScreenAwake() {
        disableKeepScreenAwake();
        ComponentName anchor = getForegroundComponent();
        if (anchor == null || systemUiContext == null) return;

        boolean wakeLockHeld = false;
        try {
            PowerManager powerManager = systemUiContext.getSystemService(PowerManager.class);
            if (powerManager != null) {
                awakeWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "BubbleNotify:ScreenOnLock");
                awakeWakeLock.setReferenceCounted(false);
                awakeWakeLock.acquire();
                wakeLockHeld = awakeWakeLock.isHeld();
            }
        } catch (Throwable error) {
            awakeWakeLock = null;
            moduleLog(Log.WARN, "bright-screen wake lock unavailable", error);
        }

        boolean guardAdded = false;
        try {
            WindowManager windowManager = systemUiContext.getSystemService(WindowManager.class);
            if (windowManager != null) {
                View guard = new View(systemUiContext);
                guard.setAlpha(0.01f);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        1,
                        1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.alpha = 0.01f;
                params.setTitle("Bubble keep screen awake");
                windowManager.addView(guard, params);
                awakeGuardView = guard;
                guardAdded = true;
            }
        } catch (Throwable error) {
            moduleLog(Log.WARN, "keep-screen-on guard unavailable", error);
        }
        if (!wakeLockHeld && !guardAdded) {
            awakeWakeLock = null;
            moduleLog(Log.ERROR, "unable to enable screen awake");
            return;
        }
        awakeAnchor = anchor;
        showKeepScreenAwakeNotification();
        mainHandler.postDelayed(awakeActivityCheckAction, 500L);
        moduleLog(Log.INFO, "screen awake enabled for " + anchor.flattenToShortString()
                + "; wakeLock=" + wakeLockHeld + ", guard=" + guardAdded);
    }

    private void checkAwakeActivity() {
        if (awakeGuardView == null
                && (awakeWakeLock == null || !awakeWakeLock.isHeld())) return;
        ComponentName current = getForegroundComponent();
        if (current != null && !current.equals(awakeAnchor)) {
            disableKeepScreenAwake();
            return;
        }
        mainHandler.postDelayed(awakeActivityCheckAction, 500L);
    }

    private void disableKeepScreenAwake() {
        mainHandler.removeCallbacks(awakeActivityCheckAction);
        cancelKeepScreenAwakeNotification();
        View guard = awakeGuardView;
        awakeGuardView = null;
        PowerManager.WakeLock wakeLock = awakeWakeLock;
        awakeWakeLock = null;
        awakeAnchor = null;
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
        if (guard != null && systemUiContext != null) {
            try {
                WindowManager windowManager = systemUiContext.getSystemService(WindowManager.class);
                if (windowManager != null) windowManager.removeViewImmediate(guard);
            } catch (Throwable ignored) {
            }
        }
        if (guard != null || wakeLock != null) moduleLog(Log.INFO, "screen awake disabled");
    }

    private void showKeepScreenAwakeNotification() {
        if (systemUiContext == null) return;
        try {
            NotificationManager manager = systemUiContext.getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = manager.getNotificationChannel(KEEP_AWAKE_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(KEEP_AWAKE_CHANNEL_ID, "临时常亮",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("临时保持当前界面常亮的运行状态");
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            Notification notification = new Notification.Builder(systemUiContext,
                    KEEP_AWAKE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle("临时常亮已开启")
                    .setContentText("退出当前界面或锁屏后自动关闭")
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setLocalOnly(true)
                    .setShowWhen(false)
                    .build();
            manager.notify(KEEP_AWAKE_NOTIFICATION_ID, notification);
        } catch (Throwable error) {
            moduleLog(Log.WARN, "keep-awake status notification unavailable", error);
        }
    }

    private void cancelKeepScreenAwakeNotification() {
        if (systemUiContext == null) return;
        try {
            NotificationManager manager = systemUiContext.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(KEEP_AWAKE_NOTIFICATION_ID);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "unable to clear keep-awake status notification", error);
        }
    }

    @SuppressWarnings("deprecation")
    private ComponentName getForegroundComponent() {
        try {
            ActivityManager manager = systemUiContext.getSystemService(ActivityManager.class);
            if (manager == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            return tasks.isEmpty() ? null : tasks.get(0).topActivity;
        } catch (Throwable error) {
            return null;
        }
    }

    private void registerDisplayReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        systemUiContext.registerReceiver(displayReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void resumeBubblesAfterUnlock() {
        screenSuspended = false;
        BubbleView bubble = currentBubble;
        if (bubble != null && bubble.isAttached()) {
            bubble.setVisibility(View.VISIBLE);
            BubbleTimeoutPolicy policy = timeoutPolicyForEntry(notificationQueue.peekFirst());
            bubble.updateTimeout(policy.seconds, policy.dimOnTimeout);
            updateChildBubbles();
            showHeadPreviewWhenVisible(bubble);
            return;
        }
        if (!notificationQueue.isEmpty() && transitioningBubble == null) {
            currentBubble = null;
            suppressPreviewForEntry = notificationQueue.peekFirst();
            showQueueHead();
        }
    }

    private void resumeBubblesAfterScreenOn() {
        screenSuspended = false;
        BubbleView bubble = currentBubble;
        if (bubble != null && bubble.isAttached()) {
            bubble.setVisibility(View.VISIBLE);
            BubbleTimeoutPolicy policy = timeoutPolicyForEntry(notificationQueue.peekFirst());
            bubble.updateTimeout(policy.seconds, policy.dimOnTimeout);
            updateChildBubbles();
            showHeadPreviewWhenVisible(bubble);
            return;
        }
        if (!notificationQueue.isEmpty() && transitioningBubble == null) {
            currentBubble = null;
            suppressPreviewForEntry = notificationQueue.peekFirst();
            showQueueHead();
        }
    }

    /**
     * A notification that arrived while the display was off created the bubble behind the
     * black screen; reveal its content overlay once the device is actually usable again.
     */
    private void showHeadPreviewWhenVisible(BubbleView bubble) {
        if (bubble == null || currentPreview != null) return;
        QueuedNotification head = notificationQueue.peekFirst();
        if (head == null || !isContentPreviewEnabledNow()) return;
        mainHandler.postDelayed(() -> {
            if (currentBubble != bubble || currentPreview != null
                    || notificationQueue.peekFirst() != head
                    || screenSuspended || !isForegroundVisible()) return;
            showNotificationPreview(bubble, head);
        }, 180L);
    }

    private BubbleTimeoutPolicy timeoutPolicyForVisibleState() {
        return isDeviceLocked() || !isScreenInteractive()
                ? new BubbleTimeoutPolicy(0, false) : readBubbleTimeoutPolicy();
    }

    private BubbleTimeoutPolicy timeoutPolicyForEntry(QueuedNotification entry) {
        // Neither a locked keyguard nor a switched-off display may silently expire a bubble
        // that the user has not seen yet.
        if (isDeviceLocked() || !isScreenInteractive()) {
            return new BubbleTimeoutPolicy(0, false);
        }
        if (entry != null && entry.timeoutSeconds >= 0) {
            return new BubbleTimeoutPolicy(entry.timeoutSeconds, false);
        }
        return readBubbleTimeoutPolicy();
    }

    private void initializeImmediateSuggestions() {
        if (immediateSuggestionController != null || systemUiContext == null) return;
        BubbleConfig current = config;
        if (current == null || !current.isEnabled()
                || !current.isImmediateSuggestionsEnabled()) return;
        if (!immediateSuggestionReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_IMMEDIATE_SUGGESTION);
            filter.addAction(ACTION_IMMEDIATE_SUGGESTION_DISMISS);
            systemUiContext.registerReceiver(immediateSuggestionReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
            immediateSuggestionReceiverRegistered = true;
        }
        immediateSuggestionController = new ImmediateSuggestionController(systemUiContext,
                new ImmediateSuggestionController.Host() {
                    @Override
                    public boolean isSuggestionEnabled(String type) {
                        BubbleConfig current = config;
                        if (current == null || !current.isEnabled()
                                || !current.isImmediateSuggestionsEnabled()) return false;
                        switch (type) {
                            case ImmediateSuggestionController.TYPE_FLASHLIGHT:
                                return current.isFlashlightSuggestionEnabled();
                            case ImmediateSuggestionController.TYPE_MUSIC:
                                return current.isMusicSuggestionEnabled();
                            case ImmediateSuggestionController.TYPE_APP_INSTALL:
                                return current.isAppInstallSuggestionEnabled();
                            case ImmediateSuggestionController.TYPE_CLIPBOARD:
                                return current.isClipboardSuggestionEnabled();
                            default:
                                return false;
                        }
                    }

                    @Override
                    public boolean isNativeClipboardSuggestionAvailable() {
                        try {
                            return systemUiContext.getPackageManager().getApplicationInfo(
                                    "com.oplus.pantanal.ums", 0) != null
                                    && systemUiContext.getPackageManager().resolveContentProvider(
                                    "com.oplus.pantanal.ums.IntentProvider", 0) != null;
                        } catch (Throwable ignored) {
                            return false;
                        }
                    }

                    @Override
                    public String getForegroundPackage() {
                        return BubbleNotificationHook.this.getForegroundPackage();
                    }

                    @Override
                    public void showSuggestion(ImmediateSuggestionController.Suggestion suggestion) {
                        mainHandler.post(() -> enqueueImmediateSuggestion(suggestion));
                    }

                    @Override
                    public void dismissSuggestion(String id) {
                        mainHandler.post(() -> dismissImmediateSuggestion(id));
                    }

                    @Override
                    public void logSuggestion(String message, Throwable error) {
                        if (error == null) moduleLog(Log.INFO, message);
                        else moduleLog(Log.WARN, message, error);
                    }
                });
        immediateSuggestionController.start();
        moduleLog(Log.INFO, "ColorOS immediate suggestion controller started");
    }

    /**
     * 剪贴板建议入口（单个）：system_server 的分类结果经广播送到这里，再交给即时建议控制器
     * 统一构建气泡（电话/快递/地址/链接/购物链接/应用深链/坐标）。
     */
    private void showClipboardSuggestion(int kind, String value, String display,
                                         String appPackage) {
        if (kind <= 0 || TextUtils.isEmpty(value)) return;
        if (config == null || !config.isEnabled()
                || !config.isImmediateSuggestionsEnabled()
                || !config.isClipboardSuggestionEnabled()) return;
        if (screenSuspended || isDeviceLocked()) return;
        if (immediateSuggestionController == null) {
            initializeImmediateSuggestions();
        }
        if (immediateSuggestionController == null) return;
        immediateSuggestionController.showClassifiedSuggestion(
                new ClipboardClassifier.Result(kind, value, display, appPackage));
    }

    /**
     * 临时常亮是"和其他通知一样"的通知：除了气泡，还在状态栏/通知栏里放一条真实通知 ——
     * 点击执行保持常亮，下拉通知栏能看到它，滑掉则连同气泡一起消失。开启常亮后的运行状态
     * 通知（showKeepScreenAwakeNotification）保持原样。
     */
    private void postKeepAwakeOfferNotification() {
        if (systemUiContext == null) return;
        try {
            NotificationManager manager = systemUiContext.getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel =
                    manager.getNotificationChannel(KEEP_AWAKE_OFFER_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(KEEP_AWAKE_OFFER_CHANNEL_ID, "临时常亮提示",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("显示临时常亮建议及其状态栏通知");
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            String key = "suggestion:" + KEEP_AWAKE_SUGGESTION_ID;
            PendingIntent open = PendingIntent.getBroadcast(systemUiContext,
                    KEEP_AWAKE_OFFER_NOTIFICATION_ID,
                    new Intent(ACTION_IMMEDIATE_SUGGESTION)
                            .setPackage(systemUiPackageName)
                            .putExtra("suggestion_key", key),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent dismiss = PendingIntent.getBroadcast(systemUiContext,
                    KEEP_AWAKE_OFFER_NOTIFICATION_ID + 1,
                    new Intent(ACTION_IMMEDIATE_SUGGESTION_DISMISS)
                            .setPackage(systemUiPackageName)
                            .putExtra("suggestion_key", key),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(systemUiContext,
                    KEEP_AWAKE_OFFER_CHANNEL_ID)
                    .setSmallIcon(android.graphics.drawable.Icon.createWithResource(
                            "android", android.R.drawable.ic_lock_idle_lock))
                    .setContentTitle("临时保持屏幕常亮")
                    .setContentText("点击保持当前界面常亮，滑掉可忽略")
                    .setCategory(Notification.CATEGORY_RECOMMENDATION)
                    .setContentIntent(open)
                    .setDeleteIntent(dismiss)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setLocalOnly(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .build();
            manager.notify(KEEP_AWAKE_OFFER_NOTIFICATION_ID, notification);
            moduleLog(Log.INFO, "keep-awake offer posted to the status bar");
        } catch (Throwable error) {
            moduleLog(Log.WARN, "unable to post keep-awake notification", error);
        }
    }

    private void cancelKeepAwakeOfferNotification() {
        if (systemUiContext == null) return;
        try {
            NotificationManager manager = systemUiContext.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(KEEP_AWAKE_OFFER_NOTIFICATION_ID);
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "unable to clear keep-awake notification", error);
        }
    }

    /**
     * 剪贴板建议入口（一次复制命中多项）：每一项各自成为一个建议气泡，互不覆盖；超过一项时
     * 直接把展开的一排打开，这样"多个识别结果"是同时可见的多个气泡，而不是只露出队首。
     */
    private void showClipboardSuggestions(int[] kinds, String[] values, String[] displays,
                                          String[] apps) {
        if (kinds == null || values == null || kinds.length == 0) return;
        if (config == null || !config.isEnabled()
                || !config.isImmediateSuggestionsEnabled()
                || !config.isClipboardSuggestionEnabled()) {
            return;
        }
        if (screenSuspended || isDeviceLocked()) return;
        if (immediateSuggestionController == null) initializeImmediateSuggestions();
        if (immediateSuggestionController == null) return;
        // A fresh copy replaces whatever the previous copy produced.
        dropStaleClipboardSuggestions();
        int shown = 0;
        for (int index = 0; index < kinds.length; index++) {
            if (index >= values.length || kinds[index] <= 0) continue;
            if (TextUtils.isEmpty(values[index])) continue;
            String display = displays != null && index < displays.length ? displays[index] : null;
            String appPackage = apps != null && index < apps.length ? apps[index] : null;
            String suggestionId = ImmediateSuggestionController.TYPE_CLIPBOARD
                    + ":" + kindTag(kinds[index]);
            immediateSuggestionController.showClassifiedSuggestion(suggestionId,
                    new ClipboardClassifier.Result(kinds[index], values[index], display,
                            appPackage));
            shown++;
        }
        if (shown <= 1) return;
        moduleLog(Log.INFO, "clipboard produced " + shown + " suggestions; opening the bubble row");
        // The bubble for the first item may still be animating in (or replacing a dismissing
        // one), so retry instead of giving up on the first attempt.
        openBubbleRowWhenReady(0);
    }

    private void openBubbleRowWhenReady(int attempt) {
        mainHandler.postDelayed(() -> {
            if (expandedStack != null) return;
            BubbleView head = currentBubble;
            if (head == null || !head.isAttached() || transitioningBubble != null) {
                if (attempt < 4) openBubbleRowWhenReady(attempt + 1);
                return;
            }
            if (notificationQueue.size() <= 1) return;
            showExpandedStack(head, true);
        }, attempt == 0 ? 420L : 260L);
    }

    private static String kindTag(int kind) {
        switch (kind) {
            case ClipboardClassifier.KIND_PHONE: return "phone";
            case ClipboardClassifier.KIND_EXPRESS: return "express";
            case ClipboardClassifier.KIND_SHOPPING: return "shopping";
            case ClipboardClassifier.KIND_LINK: return "link";
            case ClipboardClassifier.KIND_APP_LINK: return "app_link";
            case ClipboardClassifier.KIND_ADDRESS: return "address";
            case ClipboardClassifier.KIND_COORDINATES: return "coordinates";
            default: return Integer.toString(kind);
        }
    }

    /** Removes the clipboard bubbles left over from the previous copy. */
    private void dropStaleClipboardSuggestions() {
        String prefix = "suggestion:" + ImmediateSuggestionController.TYPE_CLIPBOARD;
        List<String> stale = new ArrayList<>();
        for (QueuedNotification item : notificationQueue) {
            String key = item.notificationKey;
            if (key != null && key.startsWith(prefix)) stale.add(key);
        }
        for (String key : stale) {
            boolean wasHead = notificationQueue.peekFirst() != null
                    && key.equals(notificationQueue.peekFirst().notificationKey);
            notificationQueue.removeIf(item -> key.equals(item.notificationKey));
            cleanupImmediateSuggestion(key);
            if (!wasHead) continue;
            BubbleView bubble = currentBubble;
            currentBubble = null;
            transitioningBubble = null;
            if (bubble != null) bubble.removeImmediately();
        }
        if (!stale.isEmpty()) {
            showQueueHead();
            moduleLog(Log.DEBUG, "previous clipboard suggestions dropped: " + stale.size());
        }
    }

    private void enqueueImmediateSuggestion(ImmediateSuggestionController.Suggestion suggestion) {
        if (suggestion == null || screenSuspended || isDeviceLocked()) return;
        String key = "suggestion:" + suggestion.id;
        NotificationPayload payload = new NotificationPayload(
                suggestion.icon, suggestion.appIcon, "即时建议", suggestion.title, suggestion.body);
        QueuedNotification queued = new QueuedNotification(payload, null,
                suggestion.sourcePackage, key, suggestion.action, suggestion.timeoutSeconds);
        immediateSuggestionActions.put(key, suggestion.action);
        // An already-open fan has a snapshot of the old queue. Fold it before
        // replacing the prompt so the next expansion contains the new item.
        BubbleStackOverlay oldStack = expandedStack;
        if (oldStack != null) {
            expandedStack = null;
            oldStack.dismiss();
        }
        notificationQueue.removeIf(item -> key.equals(item.notificationKey));
        notificationQueue.addFirst(queued);
        if (notificationQueue.size() > 64) notificationQueue.removeLast();

        BubbleView bubble = currentBubble;
        if ((bubble == null || !bubble.isAttached()) && transitioningBubble == null) {
            currentBubble = null;
            showQueueHead();
        } else if (bubble != null && bubble.isAttached() && transitioningBubble == null) {
            BubbleTimeoutPolicy policy = timeoutPolicyForEntry(queued);
            bubble.updateTimeout(policy.seconds, policy.dimOnTimeout);
            currentBubbleEntry = queued;
            bubble.setIconAnimated(payload.icon);
            bubble.setAppBadge(payload.appIcon);
            updateChildBubbles();
            showNotificationPreview(bubble, queued);
        }
        showImmediateSuggestionTicker(suggestion, payload, key);
        moduleLog(Log.INFO, "immediate suggestion shown: " + suggestion.id);
    }

    private void dismissImmediateSuggestion(String id) {
        dismissImmediateSuggestionKey("suggestion:" + id);
    }

    private void dismissImmediateSuggestionKey(String key) {
        if (TextUtils.isEmpty(key)) return;
        QueuedNotification head = notificationQueue.peekFirst();
        if (head != null && key.equals(head.notificationKey)) {
            notificationQueue.remove(head);
            cleanupImmediateSuggestion(key);
            BubbleView bubble = currentBubble;
            if (bubble != null && bubble.isAttached() && transitioningBubble == null) {
                transitioningBubble = bubble;
                bubble.dismiss();
                mainHandler.postDelayed(() -> {
                    if (transitioningBubble == bubble) transitioningBubble = null;
                    if (currentBubble == bubble) currentBubble = null;
                    suppressPreviewForEntry = notificationQueue.peekFirst();
                    showQueueHead();
                }, 240L);
                return;
            }
            if (bubble == null || !bubble.isAttached()) {
                currentBubble = null;
                showQueueHead();
            }
            return;
        }
        notificationQueue.removeIf(item -> key.equals(item.notificationKey));
        cleanupImmediateSuggestion(key);
        updateChildBubbles();
    }

    private void cleanupImmediateSuggestion(String key) {
        if (TextUtils.isEmpty(key) || !key.startsWith("suggestion:")) return;
        immediateSuggestionActions.remove(key);
        if (("suggestion:" + KEEP_AWAKE_SUGGESTION_ID).equals(key)) {
            cancelKeepAwakeOfferNotification();
        }
        StatusBarNotification ticker = immediateSuggestionTickers.remove(key);
        if (ticker != null && fluidCloudNotificationOverlay != null) {
            fluidCloudNotificationOverlay.removeIfSource(ticker);
        }
    }

    private void showImmediateSuggestionTicker(ImmediateSuggestionController.Suggestion suggestion,
                                               NotificationPayload payload, String key) {
        if (readStatusScrollMode() == BubbleConfig.STATUS_SCROLL_OFF) return;
        try {
            Notification notification = new Notification.Builder(systemUiContext,
                    "bubble_immediate_suggestion")
                    .setSmallIcon(android.graphics.drawable.Icon.createWithResource(
                            "android", android.R.drawable.ic_dialog_info))
                    .setContentTitle(suggestion.title)
                    .setContentText(suggestion.body)
                    .setContentIntent(PendingIntent.getBroadcast(systemUiContext, key.hashCode(),
                            new Intent(ACTION_IMMEDIATE_SUGGESTION)
                                    .setPackage(systemUiPackageName)
                                    .putExtra("suggestion_key", key),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build();
            long now = System.currentTimeMillis();
            StatusBarNotification synthetic = new StatusBarNotification(
                    systemUiPackageName, systemUiPackageName, key.hashCode(), key,
                    android.os.Process.myUid(), android.os.Process.myPid(), 0, notification,
                    android.os.Process.myUserHandle(), now);
            immediateSuggestionTickers.put(key, synthetic);
            showStatusScroll(synthetic, notification, payload);
            if (suggestion.timeoutSeconds > 0) {
                mainHandler.postDelayed(() -> {
                    StatusBarNotification current = immediateSuggestionTickers.get(key);
                    if (current == synthetic) cleanupImmediateSuggestion(key);
                }, suggestion.timeoutSeconds * 1_000L);
            }
        } catch (Throwable error) {
            moduleLog(Log.DEBUG, "immediate suggestion ticker unavailable", error);
        }
    }

    private void registerAwakePromptReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_DIM_TOUCH);
        systemUiContext.registerReceiver(
                awakePromptReceiver,
                filter,
                "android.permission.DEVICE_POWER",
                mainHandler,
                Context.RECEIVER_EXPORTED);
    }

    /**
     * Registers the clipboard receiver only while clipboard recognition is on. Unregistering
     * guarantees that clipboard tokens leaving system_server have no receiver at all when the
     * user turned the feature off.
     */
    private void updateClipboardReceiver() {
        if (systemUiContext == null) return;
        boolean wanted = config != null && config.isEnabled()
                && config.isImmediateSuggestionsEnabled()
                && config.isClipboardSuggestionEnabled();
        if (wanted == clipboardReceiverRegistered) return;
        try {
            if (wanted) {
                systemUiContext.registerReceiver(
                        clipboardReceiver,
                        new IntentFilter(ACTION_CLIPBOARD_ITEM),
                        "android.permission.DEVICE_POWER",
                        mainHandler,
                        Context.RECEIVER_EXPORTED);
            } else {
                systemUiContext.unregisterReceiver(clipboardReceiver);
            }
            clipboardReceiverRegistered = wanted;
            moduleLog(Log.INFO, "clipboard receiver " + (wanted ? "registered" : "unregistered"));
        } catch (Throwable error) {
            moduleLog(Log.WARN, "clipboard receiver registration failed", error);
        }
    }

    private void moduleLog(int priority, String message) {
        appendRuntimeLog(priority, message, null);
        Log.println(priority, TAG, message);
        log(priority, TAG, message);
    }

    private void moduleLog(int priority, String message, Throwable error) {
        appendRuntimeLog(priority, message, error);
        Log.println(priority, TAG, message + ": " + error);
        log(priority, TAG, message, error);
    }

    private void appendRuntimeLog(int priority, String message, Throwable error) {
        Context context = runtimeLogContext;
        if (context == null || !runtimeLogProviderAvailable) return;
        try {
            String detail = error == null ? "" : " | " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage());
            Bundle extras = new Bundle();
            extras.putString("line", android.os.SystemClock.elapsedRealtime() + " "
                    + priority + " " + message + detail);
            context.getContentResolver().call(BubbleConfigProvider.URI,
                    BubbleConfigProvider.METHOD_APPEND_RUNTIME_LOG, null, extras);
        } catch (Throwable ignored) {
        }
    }

    private static final class NotificationPayload {
        final Drawable icon;
        final Drawable appIcon;
        final String appName;
        final String title;
        final String body;

        NotificationPayload(Drawable icon, Drawable appIcon, String appName, String title, String body) {
            this.icon = icon;
            this.appIcon = appIcon;
            this.appName = appName;
            this.title = title;
            this.body = body;
        }
    }

    private static final class QueuedNotification {
        final NotificationPayload payload;
        final PendingIntent contentIntent;
        final String packageName;
        final String notificationKey;
        final Runnable directAction;
        final int timeoutSeconds;
        /** Posting user of the notification; differs from the owner for ColorOS app clones. */
        final int userId;

        QueuedNotification(NotificationPayload payload, PendingIntent contentIntent,
                           String packageName, String notificationKey) {
            this(payload, contentIntent, packageName, notificationKey, null, -1, -1);
        }

        QueuedNotification(NotificationPayload payload, PendingIntent contentIntent,
                           String packageName, String notificationKey, int userId) {
            this(payload, contentIntent, packageName, notificationKey, null, -1, userId);
        }

        QueuedNotification(NotificationPayload payload, PendingIntent contentIntent,
                           String packageName, String notificationKey, Runnable directAction,
                           int timeoutSeconds) {
            this(payload, contentIntent, packageName, notificationKey, directAction,
                    timeoutSeconds, -1);
        }

        QueuedNotification(NotificationPayload payload, PendingIntent contentIntent,
                           String packageName, String notificationKey, Runnable directAction,
                           int timeoutSeconds, int userId) {
            this.payload = payload;
            this.contentIntent = contentIntent;
            this.packageName = packageName;
            this.notificationKey = notificationKey;
            this.directAction = directAction;
            this.timeoutSeconds = timeoutSeconds;
            this.userId = userId;
        }
    }

    private static final class BubbleTimeoutPolicy {
        final int seconds;
        final boolean dimOnTimeout;

        BubbleTimeoutPolicy(int seconds, boolean dimOnTimeout) {
            this.seconds = seconds;
            this.dimOnTimeout = dimOnTimeout;
        }
    }
}
