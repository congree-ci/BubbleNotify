package io.github.freeform.bubble;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native ColorOS Live Alert adapter used by the Fluid Cloud status-bar mode.
 *
 * ColorOS renders standard and big-text mini cards with LiveAlertTextView, whose
 * native marquee is started when the full notification text is bound. Keep the
 * text intact here instead of replacing it with artificial fixed-size pages.
 */
final class FluidCloudNotificationOverlay {
    private static final String TAG = "BubbleNotify";
    private static final String PROVIDER = "com.oplus.card.server.systemui.provider";
    private static final String SEND_LIVE_ALERT = "sendLiveAlert";
    private static final String CANCEL_LIVE_ALERT = "cancelLiveAlert";
    private static final String LIVE_ALERT_CHANNEL = "live_alert";
    private static final String EXTRA_LIVE_ALERT_CARD = "oplus.livealert.card";
    private static final String EXTRA_LIVE_ALERT_CAPSULE = "oplus.livealert.capsule";
    private static final String EXTRA_SOURCE_SMALL_ICON = "bubbleNotifySourceSmallIcon";
    private static final int HOST_STATUS_BAR = 8;
    private static final int REMIND_TYPE_STRONG_SHORT = 11;

    private static final long SINGLE_PAGE_SHOW_MS = 2_400L;
    private static final long QUEUED_FINISH_DELAY_MS = 1_800L;
    private static final long MARQUEE_BASE_DURATION_MS = 2_600L;
    private static final long MARQUEE_CHAR_DURATION_MS = 260L;
    private static final long MAX_MARQUEE_ROUND_MS = 18_000L;
    private static final int APPROX_VISIBLE_CHARS = 18;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advanceAction = this::advanceRound;
    private final Runnable finishAction = this::finishPaging;
    private final Runnable freeformTicketAction = this::refreshFreeformTicket;

    private StatusBarNotification sourceNotification;
    private StatusBarNotification activeLiveAlert;
    private String appName = "";
    private String title = "";
    private String body = "";
    private int rounds = 1;
    private int completedRounds;
    private boolean paging;
    private Runnable onFinished;

    FluidCloudNotificationOverlay(Context context) {
        this.context = context;
    }

    void setOnFinished(Runnable action) {
        onFinished = action;
    }

    boolean isShowing() {
        return paging;
    }

    boolean isSourceAllowed(BubbleConfig config) {
        return sourceNotification != null
                && config != null
                && config.acceptsStatusScrollFrom(sourceNotification.getPackageName());
    }

    /** Native Live Alert owns the icon and ignores replacement drawables. */
    void updateIcon(Drawable icon) {
        // The provider renders the source notification's smallIcon itself.
    }

    boolean show(StatusBarNotification source, String appName, String title, String body,
                 int requestedRounds) {
        if (context == null || source == null) return false;

        if (activeLiveAlert != null) {
            cancelActiveAlert();
        } else {
            stopPagingCallbacks();
        }

        sourceNotification = source;
        this.appName = safe(appName);
        this.title = safe(title);
        this.body = safe(body);
        rounds = Math.max(1, Math.min(5, requestedRounds));
        completedRounds = 0;

        try {
            StatusBarNotification first = buildLiveAlert(0);
            if (!sendToSystem(SEND_LIVE_ALERT, first)) {
                activeLiveAlert = null;
                clearSource();
                return false;
            }
            activeLiveAlert = first;
            paging = true;
            refreshFreeformTicket();
            if (needsNativeMarquee()) {
                handler.postDelayed(advanceAction, nativeRoundDuration());
            } else {
                handler.postDelayed(finishAction, SINGLE_PAGE_SHOW_MS);
            }
            return true;
        } catch (Throwable error) {
            android.util.Log.w(TAG, "native Fluid Cloud Live Alert unavailable", error);
            activeLiveAlert = null;
            paging = false;
            clearSource();
            return false;
        }
    }

    /** Finish the current notification soon so a queued source can take over. */
    void finishCurrentRoundSoon() {
        if (!paging) return;
        rounds = Math.min(rounds, completedRounds + 1);
        handler.removeCallbacks(advanceAction);
        handler.removeCallbacks(finishAction);
        handler.postDelayed(finishAction, QUEUED_FINISH_DELAY_MS);
    }

    private void advanceRound() {
        if (!paging) return;
        completedRounds++;
        if (completedRounds >= rounds) {
            finishPaging();
            return;
        }
        try {
            // ColorOS sets marqueeRepeatLimit=1 for mini-card text. Updating a
            // harmless round marker makes SystemUI bind the same full text again.
            StatusBarNotification updated = buildLiveAlert(completedRounds);
            if (sendToSystem(SEND_LIVE_ALERT, updated)) activeLiveAlert = updated;
        } catch (Throwable error) {
            android.util.Log.d(TAG, "native Live Alert marquee restart failed", error);
        }
        handler.postDelayed(advanceAction, nativeRoundDuration());
    }

    /**
     * Collapse in place. No cancel call is made here, so the small capsule stays
     * present after the last page and remains available for the user's click.
     */
    private void finishPaging() {
        if (!paging) return;
        stopPagingCallbacks();
        // Match the 21:14 implementation's end state, but do not call
        // removeImmediately(): the notification must not be closed by paging.
        paging = false;
        completedRounds = 0;
        notifyFinished();
    }

    /** Called only when the source notification is explicitly removed. */
    void removeImmediately() {
        if (!paging && activeLiveAlert == null) return;
        stopPagingCallbacks();
        paging = false;
        cancelActiveAlert();
        completedRounds = 0;
        notifyFinished();
    }

    /** Remove the native alert only when the explicitly removed entry is its source. */
    void removeIfSource(StatusBarNotification source) {
        if (source == null || sourceNotification == null) return;
        String sourceKey = source.getKey();
        String activeSourceKey = sourceNotification.getKey();
        if (!TextUtils.isEmpty(sourceKey) && sourceKey.equals(activeSourceKey)) {
            removeImmediately();
        }
    }

    private void notifyFinished() {
        Runnable action = onFinished;
        if (action != null) handler.post(action);
    }

    private void stopPagingCallbacks() {
        handler.removeCallbacks(advanceAction);
        handler.removeCallbacks(finishAction);
        handler.removeCallbacks(freeformTicketAction);
    }

    private void cancelActiveAlert() {
        StatusBarNotification alert = activeLiveAlert;
        activeLiveAlert = null;
        clearSource();
        if (alert == null) return;
        try {
            sendToSystem(CANCEL_LIVE_ALERT, alert);
        } catch (Throwable error) {
            android.util.Log.d(TAG, "native Live Alert cancel failed", error);
        }
    }

    private void clearSource() {
        sourceNotification = null;
        appName = "";
        title = "";
        body = "";
    }

    private boolean needsNativeMarquee() {
        return displayBody().length() > APPROX_VISIBLE_CHARS;
    }

    private long nativeRoundDuration() {
        int overflowChars = Math.max(0, displayBody().length() - APPROX_VISIBLE_CHARS);
        return Math.min(MAX_MARQUEE_ROUND_MS,
                MARQUEE_BASE_DURATION_MS + overflowChars * MARQUEE_CHAR_DURATION_MS);
    }

    private String displayBody() {
        String value = safe(body);
        return TextUtils.isEmpty(value) ? "新通知" : value;
    }

    private StatusBarNotification buildLiveAlert(int marqueeRound) throws Exception {
        if (sourceNotification == null) throw new IllegalStateException("missing source");
        Notification source = sourceNotification.getNotification();
        Notification.Builder builder;
        try {
            String channel = source.getChannelId();
            builder = new Notification.Builder(context,
                    TextUtils.isEmpty(channel) ? LIVE_ALERT_CHANNEL : channel);
        } catch (Throwable ignored) {
            builder = new Notification.Builder(context);
        }
        if (source.getSmallIcon() != null) {
            builder.setSmallIcon(source.getSmallIcon());
        } else {
            builder.setSmallIcon(android.R.drawable.sym_def_app_icon);
        }

        String displayTitle = TextUtils.isEmpty(title) ? "新通知" : title;
        String displayBody = displayBody();
        builder.setContentTitle(displayTitle)
                .setContentText(displayBody)
                .setSubText(null)
                .setStyle(new Notification.BigTextStyle().bigText(displayBody))
                .setLocalOnly(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(source.contentIntent);
        // Keep the fallback capsule useful when ColorOS rejects a custom layout.
        setShortCriticalText(builder, displayTitle);

        Notification notification = builder.build();
        Bundle extras = notification.extras;
        if (extras != null) {
            extras.putString("oplusLiveAlertOptions", buildLiveAlertOptions());
            extras.putInt("bubbleNotifyMarqueeRound", marqueeRound);
            if (source.getSmallIcon() != null) {
                extras.putParcelable(EXTRA_SOURCE_SMALL_ICON, source.getSmallIcon());
            }
            // Keep the platform BigTextStyle as the expanded card source. On
            // ColorOS 17 a custom primaryData card takes precedence over the
            // notification style and is rendered as a compact title-only card,
            // which drops the full body when the user expands it.
            extras.putString(EXTRA_LIVE_ALERT_CAPSULE,
                    buildSymmetricCapsule(displayTitle, displayBody));
            extras.putCharSequence("android.bigText", displayBody);
            extras.putCharSequence("android.text", displayBody);
        }
        return new StatusBarNotification(
                sourceNotification.getPackageName(), sourceNotification.getOpPkg(),
                sourceNotification.getId(), sourceNotification.getTag(),
                sourceNotification.getUid(), 0, 0, notification,
                sourceNotification.getUser(), sourceNotification.getPostTime());
    }

    private void setShortCriticalText(Notification.Builder builder, String value) {
        try {
            java.lang.reflect.Method method = Notification.Builder.class.getMethod(
                    "setShortCriticalText", String.class);
            method.invoke(builder, value);
        } catch (Throwable ignored) {
            // Optional on builds without the native capsule API.
        }
    }

    /**
     * ColorOS 17's t_sym capsule is a real two-sided capsule. The first side
     * contains the source app icon and notification title; the second side is
     * reserved for the notification body, which SystemUI can marquee itself.
     */
    private String buildSymmetricCapsule(String displayTitle, String displayBody)
            throws Exception {
        JSONObject icon = new JSONObject();
        icon.put("icon", "icon/" + EXTRA_SOURCE_SMALL_ICON);
        icon.put("shape", "CIRCLE");
        icon.put("desc", displayTitle);

        JSONObject firstIcon = new JSONObject();
        firstIcon.put("icon", icon);
        firstIcon.put("size", "MINI");

        JSONObject title = new JSONObject();
        title.put("text", displayTitle);
        title.put("textAttr", new JSONObject().put("ellipsize", "MARQUEE"));

        JSONObject content = marqueeText(displayBody);

        JSONObject capsuleData = new JSONObject();
        capsuleData.put("type", "t_sym");
        capsuleData.put("firstIcon", firstIcon);
        capsuleData.put("firstTitle", title);
        // The right half's primary visible line is lastTitle. lastContent is a
        // secondary row and is suppressed by the compact status-bar capsule.
        capsuleData.put("lastTitle", content);

        return new JSONObject().put("capsuleData", capsuleData).toString();
    }

    private String buildLiveAlertCard(String displayTitle, String displayBody)
            throws Exception {
        JSONObject icon = new JSONObject();
        icon.put("icon", "icon/" + EXTRA_SOURCE_SMALL_ICON);
        icon.put("shape", "CIRCLE");
        icon.put("desc", displayTitle);

        JSONObject primaryIcon = new JSONObject();
        primaryIcon.put("icon", icon);
        primaryIcon.put("size", "MINI");

        JSONObject primaryTitle = new JSONObject();
        primaryTitle.put("text", displayTitle);

        JSONObject primaryContent = marqueeText(displayBody);

        JSONObject primaryData = new JSONObject();
        primaryData.put("type", "t_standard");
        primaryData.put("icon", primaryIcon);
        primaryData.put("title", primaryTitle);
        primaryData.put("content", primaryContent);
        return new JSONObject().put("primaryData", primaryData).toString();
    }

    /** ColorOS reads these fields from slightly different template revisions. */
    private JSONObject marqueeText(String value) throws Exception {
        JSONObject text = new JSONObject();
        text.put("text", value);
        JSONObject attr = new JSONObject();
        attr.put("maxLine", 1);
        attr.put("ellipsize", "MARQUEE");
        attr.put("repeat", 1);
        text.put("textAttr", attr);
        // Older plugin revisions read repeat from the text object itself.
        text.put("repeat", 1);
        return text;
    }

    private String buildLiveAlertOptions() throws Exception {
        JSONObject options = new JSONObject();
        options.put("isMilestone", true);
        options.put("dataSourcePkgName", sourceNotification.getPackageName());
        options.put("remindType", REMIND_TYPE_STRONG_SHORT);
        options.put("showHostMap", new JSONArray().put(HOST_STATUS_BAR));
        options.put("lockScreenShowHostMap", new JSONArray().put(HOST_STATUS_BAR));

        JSONObject actions = new JSONObject();
        actions.put("isDismissible", false);
        actions.put("isAutoCancel", false);
        actions.put("liveAlertService", "");
        actions.put("immersiveCardType", -1);
        actions.put("requestLockScreenImmersive", -1);
        actions.put("ignoreEntry", 0);
        options.put("extensibleActionMap", actions);
        return options.toString();
    }

    private boolean sendToSystem(String method, StatusBarNotification notification) {
        try {
            Bundle extras = new Bundle();
            extras.putParcelable("content", notification);
            Bundle result = context.getContentResolver().call(PROVIDER, method, null, extras);
            return result != null && result.getInt("resultCode", 0) == -1;
        } catch (Throwable error) {
            android.util.Log.d(TAG, "native Live Alert provider call failed", error);
            return false;
        }
    }

    private void refreshFreeformTicket() {
        if (sourceNotification == null || activeLiveAlert == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("package", sourceNotification.getPackageName());
            context.getContentResolver().call(BubbleConfigProvider.URI,
                    BubbleConfigProvider.METHOD_PREPARE_FREEFORM_LAUNCH, null, extras);
        } catch (Throwable error) {
            android.util.Log.d(TAG, "freeform ticket refresh failed", error);
        }
        // Keep the click-to-freeform ticket alive while the native capsule lives.
        handler.postDelayed(freeformTicketAction, 1_000L);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
