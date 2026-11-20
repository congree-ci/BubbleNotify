package io.github.freeform.bubble;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ColorOS-side rule engine for Flyme-style immediate suggestions. */
final class ImmediateSuggestionController {
    static final String TYPE_FLASHLIGHT = "flashlight";
    static final String TYPE_MUSIC = "music";
    static final String TYPE_APP_INSTALL = "app_install";
    static final String TYPE_CLIPBOARD = "clipboard";

    interface Host {
        boolean isSuggestionEnabled(String type);
        boolean isNativeClipboardSuggestionAvailable();
        String getForegroundPackage();
        void showSuggestion(Suggestion suggestion);
        void dismissSuggestion(String id);
        void logSuggestion(String message, Throwable error);
    }

    static final class Suggestion {
        final String id;
        final String sourcePackage;
        final Drawable icon;
        final Drawable appIcon;
        final String title;
        final String body;
        final Runnable action;
        final int timeoutSeconds;

        Suggestion(String id, String sourcePackage, Drawable icon, Drawable appIcon,
                   String title, String body, Runnable action, int timeoutSeconds) {
            this.id = id;
            this.sourcePackage = sourcePackage;
            this.icon = icon;
            this.appIcon = appIcon;
            this.title = title;
            this.body = body;
            this.action = action;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private static final long TORCH_DELAY_MS = 5_000L;
    private static final long NATIVE_CLIPBOARD_GRACE_MS = 1_800L;
    private static final String[] MUSIC_PACKAGES = {
            "com.heytap.music", "com.oplus.music", "com.netease.cloudmusic",
            "com.tencent.qqmusic", "com.kugou.android",
            "com.google.android.apps.youtube.music", "com.spotify.music"
    };
    private static final Set<Integer> HEADSET_TYPES = new HashSet<>(Arrays.asList(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET));

    private final Context context;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CameraManager cameraManager;
    private final AudioManager audioManager;
    private final ClipboardManager clipboardManager;
    private final PackageManager packageManager;
    private final Set<String> activeTorchIds = new HashSet<>();
    private String lastClipboardText;
    private Runnable pendingClipboardFallback;
    private boolean started;

    private final Runnable torchSuggestionAction = this::showTorchSuggestionIfNeeded;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (enabled) {
                activeTorchIds.add(cameraId);
                handler.removeCallbacks(torchSuggestionAction);
                handler.postDelayed(torchSuggestionAction, TORCH_DELAY_MS);
            } else {
                activeTorchIds.remove(cameraId);
                if (activeTorchIds.isEmpty()) {
                    handler.removeCallbacks(torchSuggestionAction);
                    host.dismissSuggestion(TYPE_FLASHLIGHT);
                }
            }
        }
    };

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            for (AudioDeviceInfo device : addedDevices) {
                if (HEADSET_TYPES.contains(device.getType())) {
                    handler.postDelayed(ImmediateSuggestionController.this::suggestMusicApp, 600L);
                    break;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (!hasConnectedHeadset()) host.dismissSuggestion(TYPE_MUSIC);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    || intent.getData() == null) return;
            String packageName = intent.getData().getSchemeSpecificPart();
            if (!TextUtils.isEmpty(packageName)) {
                handler.postDelayed(() -> suggestInstalledApp(packageName), 1_500L);
            }
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener =
            this::inspectClipboard;

    ImmediateSuggestionController(Context context, Host host) {
        this.context = context;
        this.host = host;
        packageManager = context.getPackageManager();
        cameraManager = context.getSystemService(CameraManager.class);
        audioManager = context.getSystemService(AudioManager.class);
        clipboardManager = context.getSystemService(ClipboardManager.class);
    }

    void start() {
        if (started) return;
        started = true;
        try {
            if (cameraManager != null) cameraManager.registerTorchCallback(torchCallback, handler);
        } catch (Throwable error) {
            host.logSuggestion("torch callback registration failed", error);
        }
        try {
            if (audioManager != null) audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
        } catch (Throwable error) {
            host.logSuggestion("audio device callback registration failed", error);
        }
        try {
            IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addDataScheme("package");
            context.registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            host.logSuggestion("package receiver registration failed", error);
        }
        try {
            if (clipboardManager != null) clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        } catch (Throwable error) {
            host.logSuggestion("clipboard listener registration failed", error);
        }
    }

    private void showTorchSuggestionIfNeeded() {
        if (!host.isSuggestionEnabled(TYPE_FLASHLIGHT) || activeTorchIds.isEmpty()) return;
        String foreground = host.getForegroundPackage();
        if (foreground != null && foreground.toLowerCase(Locale.ROOT).contains("camera")) return;
        Drawable icon = new MaterialSuggestionIcon(MaterialSuggestionIcon.FLASHLIGHT);
        host.showSuggestion(new Suggestion(TYPE_FLASHLIGHT, context.getPackageName(), icon, null,
                "关闭手电筒", "手电筒已持续开启，点击立即关闭",
                this::turnOffTorch, 0));
    }

    private void turnOffTorch() {
        if (cameraManager == null) return;
        for (String cameraId : new HashSet<>(activeTorchIds)) {
            try {
                cameraManager.setTorchMode(cameraId, false);
            } catch (Throwable error) {
                host.logSuggestion("unable to turn off torch " + cameraId, error);
            }
        }
    }

    private void suggestMusicApp() {
        if (!host.isSuggestionEnabled(TYPE_MUSIC) || audioManager == null
                || audioManager.isMusicActive() || !hasConnectedHeadset()) return;
        Intent musicIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC);
        ResolveInfo target = firstLaunchTarget(musicIntent, host.getForegroundPackage());
        Intent launch;
        String packageName;
        String label;
        Drawable icon;
        if (target != null && target.activityInfo != null) {
            packageName = target.activityInfo.packageName;
            launch = new Intent(musicIntent)
                    .setClassName(packageName, target.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            label = String.valueOf(target.loadLabel(packageManager));
            icon = target.loadIcon(packageManager);
        } else {
            packageName = firstInstalledPackage(MUSIC_PACKAGES, host.getForegroundPackage());
            if (packageName == null) return;
            launch = packageManager.getLaunchIntentForPackage(packageName);
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                label = String.valueOf(packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)));
                icon = packageManager.getApplicationIcon(packageName);
            } catch (Throwable ignored) {
                label = "音乐";
                icon = context.getDrawable(android.R.drawable.ic_media_play);
            }
        }
        host.showSuggestion(new Suggestion(TYPE_MUSIC, packageName,
                new MaterialSuggestionIcon(MaterialSuggestionIcon.MUSIC), icon,
                "打开" + label, "已连接耳机，点击开始播放音乐",
                () -> startActivity(launch), 10));
    }

    private boolean hasConnectedHeadset() {
        if (audioManager == null) return false;
        try {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (HEADSET_TYPES.contains(device.getType())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void suggestInstalledApp(String packageName) {
        if (!host.isSuggestionEnabled(TYPE_APP_INSTALL)
                || packageName.equals(host.getForegroundPackage())) return;
        Intent launch = packageManager.getLaunchIntentForPackage(packageName);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0));
            Drawable icon = packageManager.getApplicationIcon(packageName);
            host.showSuggestion(new Suggestion(TYPE_APP_INSTALL, packageName,
                    new MaterialSuggestionIcon(MaterialSuggestionIcon.INSTALL), icon,
                    "打开新应用", "“" + label + "”已安装完成",
                    () -> startActivity(launch), 10));
        } catch (Throwable error) {
            host.logSuggestion("installed app suggestion failed for " + packageName, error);
        }
    }

    private void inspectClipboard() {
        if (!host.isSuggestionEnabled(TYPE_CLIPBOARD) || clipboardManager == null) return;
        try {
            ClipDescription description = clipboardManager.getPrimaryClipDescription();
            if (description != null && description.getExtras() != null
                    && description.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
                return;
            }
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence value = clip.getItemAt(0).coerceToText(context);
            String text = value == null ? "" : value.toString().trim();
            if (text.isEmpty() || text.length() > 4096 || text.equals(lastClipboardText)) return;
            lastClipboardText = text;
            classifyClipboard(text);
        } catch (Throwable error) {
            host.logSuggestion("clipboard inspection failed", error);
        }
    }

    private void classifyClipboard(String text) {
        ClipboardClassifier.Result result = ClipboardClassifier.classify(text);
        if (result == null) {
            host.logSuggestion("clipboard: nothing actionable", null);
            return;
        }
        showClassifiedSuggestion(result);
    }

    /**
     * Builds the bubble for an already classified clipboard item. Both the (rarely delivered)
     * in-process clipboard listener and the system_server clipboard hook funnel through here so
     * all clipboard suggestions behave identically.
     */
    void showClassifiedSuggestion(ClipboardClassifier.Result result) {
        showClassifiedSuggestion(TYPE_CLIPBOARD, result);
    }

    /**
     * Same as {@link #showClassifiedSuggestion(ClipboardClassifier.Result)} but under a custom
     * suggestion id, so several clipboard findings from one copy can live side by side in the
     * bubble queue instead of replacing each other.
     */
    void showClassifiedSuggestion(String suggestionId, ClipboardClassifier.Result result) {
        if (result == null || TextUtils.isEmpty(result.value)) return;
        if (!host.isSuggestionEnabled(TYPE_CLIPBOARD)) return;
        String id = TextUtils.isEmpty(suggestionId) ? TYPE_CLIPBOARD : suggestionId;
        host.logSuggestion("clipboard suggestion: " + kindName(result.kind), null);
        switch (result.kind) {
            case ClipboardClassifier.KIND_PHONE: {
                Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + result.value));
                showClipboardIntentSuggestion(id, dial, "拨打电话", result.value,
                        MaterialSuggestionIcon.PHONE);
                return;
            }
            case ClipboardClassifier.KIND_EXPRESS: {
                String encoded = URLEncoder.encode(result.value, StandardCharsets.UTF_8);
                showIntentSuggestion(id,
                        new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://m.kuaidi100.com/result.jsp?nu=" + encoded)),
                        "查询快递", "快递单号 " + result.value, 10,
                        MaterialSuggestionIcon.PACKAGE);
                return;
            }
            case ClipboardClassifier.KIND_ADDRESS: {
                Uri geo = Uri.parse("geo:0,0?q=" + Uri.encode(result.value));
                showIntentSuggestion(id, new Intent(Intent.ACTION_VIEW, geo),
                        "查看地址", result.value, 10, MaterialSuggestionIcon.LOCATION);
                return;
            }
            case ClipboardClassifier.KIND_COORDINATES: {
                // "lat,lng" straight into the map app, no geocoding needed.
                Uri geo = Uri.parse("geo:" + result.value + "?q=" + result.value);
                showIntentSuggestion(id, new Intent(Intent.ACTION_VIEW, geo),
                        "在地图中查看坐标", result.value, 10, MaterialSuggestionIcon.LOCATION);
                return;
            }
            case ClipboardClassifier.KIND_SHOPPING:
            case ClipboardClassifier.KIND_LINK: {
                boolean shopping = result.kind == ClipboardClassifier.KIND_SHOPPING;
                Uri uri = Uri.parse(result.value);
                Intent preferred = isInstalled(result.appPackage)
                        ? new Intent(Intent.ACTION_VIEW, uri).setPackage(result.appPackage)
                        : preferredViewIntent(uri);
                ResolveInfo target = packageManager.resolveActivity(
                        preferred, PackageManager.MATCH_DEFAULT_ONLY);
                boolean appSpecific = target != null && target.activityInfo != null
                        && !isBrowserPackage(target.activityInfo.packageName);
                String hostName = ClipboardClassifier.hostOf(result.value);
                String body = TextUtils.isEmpty(hostName) ? result.value : hostName;
                String title;
                Intent intent;
                if (appSpecific) {
                    title = shopping ? "打开购物应用"
                            : "用" + target.loadLabel(packageManager) + "打开";
                    intent = new Intent(Intent.ACTION_VIEW, uri)
                            .setClassName(target.activityInfo.packageName,
                                    target.activityInfo.name);
                } else {
                    title = shopping ? "打开购物链接" : "打开链接";
                    intent = new Intent(Intent.ACTION_VIEW, uri);
                }
                showClipboardIntentSuggestion(id, intent, title, body,
                        shopping ? MaterialSuggestionIcon.PACKAGE : MaterialSuggestionIcon.LINK);
                return;
            }
            case ClipboardClassifier.KIND_APP_LINK: {
                Uri uri = Uri.parse(result.value);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                if (isInstalled(result.appPackage)) intent.setPackage(result.appPackage);
                ResolveInfo target = packageManager.resolveActivity(
                        intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (target == null || target.activityInfo == null) return;
                showClipboardIntentSuggestion(id, intent,
                        "用" + target.loadLabel(packageManager) + "打开",
                        result.value, MaterialSuggestionIcon.LINK);
                return;
            }
            default:
        }
    }

    private boolean isInstalled(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        try {
            packageManager.getApplicationInfo(packageName, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String kindName(int kind) {
        switch (kind) {
            case ClipboardClassifier.KIND_PHONE: return "phone";
            case ClipboardClassifier.KIND_EXPRESS: return "express";
            case ClipboardClassifier.KIND_SHOPPING: return "shopping";
            case ClipboardClassifier.KIND_LINK: return "link";
            case ClipboardClassifier.KIND_APP_LINK: return "app_link";
            case ClipboardClassifier.KIND_ADDRESS: return "address";
            case ClipboardClassifier.KIND_COORDINATES: return "coordinates";
            default: return "none";
        }
    }

    private void showClipboardIntentSuggestion(String suggestionId, Intent intent, String title,
                                               String body, int iconKind) {
        String id = TextUtils.isEmpty(suggestionId) ? TYPE_CLIPBOARD : suggestionId;
        Runnable fallback = () -> showIntentSuggestion(id, intent, title, body, 10, iconKind);
        if (pendingClipboardFallback != null) handler.removeCallbacks(pendingClipboardFallback);
        pendingClipboardFallback = null;
        // Only the primary clipboard item defers to the native ColorOS clipboard overlay; the
        // additional findings of the same copy should show up immediately.
        if (!TYPE_CLIPBOARD.equals(id) || !host.isNativeClipboardSuggestionAvailable()) {
            fallback.run();
            return;
        }
        pendingClipboardFallback = fallback;
        handler.postDelayed(fallback, NATIVE_CLIPBOARD_GRACE_MS);
    }

    void onNativeClipboardSuggestionObserved() {
        if (pendingClipboardFallback == null) return;
        handler.removeCallbacks(pendingClipboardFallback);
        pendingClipboardFallback = null;
        host.dismissSuggestion(TYPE_CLIPBOARD);
    }

    private void showIntentSuggestion(String id, Intent intent, String title, String body,
                                      int timeoutSeconds, int iconKind) {
        ResolveInfo target = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        Drawable appIcon = null;
        String sourcePackage = context.getPackageName();
        if (target != null && target.activityInfo != null) {
            sourcePackage = target.activityInfo.packageName;
            appIcon = target.loadIcon(packageManager);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        host.showSuggestion(new Suggestion(id, sourcePackage,
                new MaterialSuggestionIcon(iconKind), appIcon,
                title, body, () -> startActivity(intent), timeoutSeconds));
    }

    private ResolveInfo firstLaunchTarget(Intent intent, String excludedPackage) {
        List<ResolveInfo> targets = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo target : targets) {
            if (target.activityInfo != null
                    && !target.activityInfo.packageName.equals(excludedPackage)) return target;
        }
        return null;
    }

    private String firstInstalledPackage(String[] packages, String excludedPackage) {
        for (String packageName : packages) {
            if (packageName.equals(excludedPackage)) continue;
            try {
                if (packageManager.getLaunchIntentForPackage(packageName) != null) return packageName;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Intent preferredViewIntent(Uri uri) {
        Intent base = new Intent(Intent.ACTION_VIEW, uri);
        try {
            List<ResolveInfo> targets = packageManager.queryIntentActivities(
                    base, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo target : targets) {
                if (target.activityInfo == null
                        || isBrowserPackage(target.activityInfo.packageName)) continue;
                return new Intent(base).setClassName(
                        target.activityInfo.packageName, target.activityInfo.name);
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    private boolean isBrowserPackage(String packageName) {
        if (packageName == null) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.contains("browser") || normalized.contains("chrome")
                || normalized.contains("edge") || normalized.contains("firefox")
                || normalized.contains("opera") || normalized.contains("quark");
    }

    private void startActivity(Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            host.logSuggestion("suggestion activity launch failed: " + intent, error);
        }
    }
}
