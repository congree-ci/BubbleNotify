package io.github.freeform.bubble;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Standalone Material 3 Expressive style configuration UI for the module. */
@SuppressLint("SetTextI18n")
public final class BubbleSettingsActivity extends Activity {
    private static final int REQUEST_EXPORT_RUNTIME_LOG = 904;

    private MaterialPalette palette;
    private BubbleConfig config;
    private LinearLayout content;
    private TextView whitelistSummary;
    private TextView blacklistSummary;
    private TextView statusScrollWhitelistSummary;
    private TextView placementSummary;
    private TextView timeoutValue;
    private TextView contentPositionSummary;
    private TextView contentAlphaValue;
    private TextView contentTimeoutValue;
    private TextView contentWidthValue;
    private TextView iconSizeValue;
    /** Live preview of the bubble at the currently selected icon size. */
    private BubbleView iconPreview;
    private FrameLayout iconPreviewStage;
    private TextView bubbleSizeValue;
    private TextView statusPill;
    private TitleBubbleEmitter titleBubbleEmitter;
    private SettingsCard animationScreenCard;
    private int entranceIndex;
    private TextView badgeSizeValue;
    /** Slider drags are debounced so every pixel does not rewrite the whole preference file. */
    private int pendingBubbleSizePercent = -1;
    private int pendingIconSizePercent = -1;
    private int pendingBadgeSizePercent = -1;
    private final android.os.Handler iconSizeHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable bubbleSizeCommitAction = () -> {
        int percent = pendingBubbleSizePercent;
        pendingBubbleSizePercent = -1;
        if (percent < 0 || config == null) return;
        config.setBubbleSizePercent(percent);
        notifyBubbleTimeoutChanged();
    };
    private final Runnable iconSizeCommitAction = () -> {
        int percent = pendingIconSizePercent;
        pendingIconSizePercent = -1;
        if (percent < 0 || config == null) return;
        config.setIconSizePercent(percent);
        notifyBubbleTimeoutChanged();
    };
    private final Runnable badgeSizeCommitAction = () -> {
        int percent = pendingBadgeSizePercent;
        pendingBadgeSizePercent = -1;
        if (percent < 0 || config == null) return;
        config.setBadgeSizePercent(percent);
        notifyBubbleTimeoutChanged();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = MaterialPalette.resolve(this);
        config = new BubbleConfig(this);
        config.rawPreferences().registerOnSharedPreferenceChangeListener((prefs, key) ->
                getContentResolver().notifyChange(BubbleConfigProvider.URI, null));
        configureWindow();
        setContentView(buildPage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (placementSummary != null) placementSummary.setText(placementSummaryText());
        if (statusScrollWhitelistSummary != null) {
            statusScrollWhitelistSummary.setText(statusScrollWhitelistSummaryText());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Flush pending size changes so leaving the page never loses the last drag.
        iconSizeHandler.removeCallbacks(iconSizeCommitAction);
        iconSizeHandler.removeCallbacks(badgeSizeCommitAction);
        iconSizeCommitAction.run();
        badgeSizeCommitAction.run();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean wasDark = palette.dark;
        palette = MaterialPalette.resolve(this);
        if (palette.dark != wasDark) {
            recreate();
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(palette.surfaceContainer);
        window.setNavigationBarColor(palette.surfaceContainer);
        window.getDecorView().setSystemUiVisibility(
                palette.dark ? 0
                        : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.surfaceContainer);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(16), dp(32), dp(16), dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader();
        addSectionLabel("通用");
        addMasterCard();
        addSectionLabel("应用规则");
        addApplicationRulesCard();
        addSectionLabel("即时建议");
        addImmediateSuggestionsCard();
        addSectionLabel("通知内容");
        addContentPreviewCard();
        addSectionLabel("气泡外观");
        addAppearanceCard();
        addSectionLabel("显示位置");
        addPlacementCard();
        addSectionLabel("显示时长");
        addTimeoutCard();
        addSectionLabel("动画与屏幕");
        addAnimationCard();
        addSectionLabel("诊断");
        addDiagnosticsCard();
        addInfoPanel();
        return scroll;
    }

    private void addHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        // Align the accent marker against the title row itself.  Centering the
        // marker in the full header also includes the subtitle/status rows and
        // makes it sit noticeably too low.
        header.setGravity(Gravity.TOP);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        // Keep a generous, symmetric breathing area around the title. The
        // emitter is allowed to draw through this area without being clipped.
        header.setPadding(dp(2), dp(56), dp(8), dp(8));

        View marker = new View(this);
        marker.setBackground(rounded(palette.primary, 3));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(4), dp(22));
        // titleStage is 52dp tall and the title itself is 44dp tall; this
        // centers the marker on the actual “气泡通知” text rather than on the
        // entire multi-row header.
        markerParams.topMargin = dp(11);
        markerParams.rightMargin = dp(10);
        header.addView(marker, markerParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setClipChildren(false);
        titles.setClipToPadding(false);
        FrameLayout titleStage = new FrameLayout(this);
        titleStage.setClipChildren(false);
        titleStage.setClipToPadding(false);
        TextView title = text("气泡通知", 30, palette.onSurface, true);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setTranslationY(dp(2));
        titleStage.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.START | Gravity.TOP));
        titles.addView(titleStage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView subtitle = text("ColorOS 16/17 · LSPosed 模块", 14, palette.onSurfaceVariant, false);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);
        statusPill = text(config.isEnabled() ? "正在运行" : "停用气泡通知", 12,
                config.isEnabled() ? palette.onPrimaryContainer : palette.onSurfaceVariant, true);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusPill.setBackground(rounded(config.isEnabled()
                ? palette.primaryContainer : palette.surfaceContainerHighest, 14));
        FrameLayout statusStage = new FrameLayout(this);
        statusStage.setClipChildren(false);
        statusStage.setClipToPadding(false);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP);
        pillParams.topMargin = dp(6);
        statusStage.addView(statusPill, pillParams);
        titleBubbleEmitter = new TitleBubbleEmitter(this, statusPill, palette.primary,
                config.isEnabled());
        // Extend the drawing surface above the status stage. The negative top
        // margin keeps the launch point aligned with the pill while leaving
        // enough canvas for the rise into the title's blank area.
        FrameLayout.LayoutParams emitterParams = new FrameLayout.LayoutParams(
                dp(156), dp(300), Gravity.START | Gravity.TOP);
        emitterParams.topMargin = -dp(284);
        statusStage.addView(titleBubbleEmitter, emitterParams);
        // Keep the status row compact so the first normal section starts with
        // the same spacing as the other groups.
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        titles.addView(statusStage, statusParams);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(header);
    }

    /** 主开关：primaryContainer 大色块卡片（MD3 Expressive 强调色块）。 */
    private void addMasterCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(palette.primaryContainer, 28));
        card.setPadding(dp(22), dp(10), dp(22), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        card.setElevation(0f);
        playEntrance(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(18));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("启用气泡通知", 20, palette.onPrimaryContainer, true));
        TextView hint = text("收到符合规则的通知时显示气泡", 14, palette.onPrimaryContainer, false);
        hint.setAlpha(0.8f);
        hint.setPadding(0, dp(4), 0, 0);
        labels.addView(hint);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialSwitch enabled = makeSwitch(config.isEnabled(), (view, checked) -> {
            config.setEnabled(checked);
            updateStatusPill(checked);
            if (titleBubbleEmitter != null) titleBubbleEmitter.setAnimationEnabled(checked);
            notifyBubbleTimeoutChanged();
        });
        row.addView(enabled);
        card.addView(row);
        content.addView(card);
    }

    private void addApplicationRulesCard() {
        LinearLayout card = card();
        whitelistSummary = text(whitelistSummaryText(), 14, palette.onSurfaceVariant, false);
        card.addView(actionRow("通知白名单", whitelistSummary, "选择",
                view -> showApplicationPicker(true)));
        blacklistSummary = text(blacklistSummaryText(), 14, palette.onSurfaceVariant, false);
        card.addView(actionRow("应用黑名单", blacklistSummary, "选择",
                view -> showApplicationPicker(false)));
        statusScrollWhitelistSummary = text(statusScrollWhitelistSummaryText(),
                14, palette.onSurfaceVariant, false);
        card.addView(actionRow("状态栏通知白名单", statusScrollWhitelistSummary, "选择",
                view -> showStatusScrollApplicationPicker()));
        LinearLayout tickerRow = horizontalRow();
        tickerRow.addView(labels("状态栏滚动通知", null),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tickerRow.addView(makeStatusScrollModePicker(config.getStatusScrollMode()));
        card.addView(tickerRow);
        LinearLayout roundsPanel = sliderPanel();
        TextView roundsLabel = text("状态栏通知显示遍数：" + config.getStatusScrollRounds(),
                14, palette.onSurfaceVariant, false);
        roundsPanel.addView(roundsLabel);
        MaterialSlider roundsSlider = makeSlider(1, 5, config.getStatusScrollRounds());
        roundsSlider.setOnValueChange((slider, rounds) -> {
            config.setStatusScrollRounds(rounds);
            roundsLabel.setText("状态栏通知显示遍数：" + rounds);
            notifyBubbleTimeoutChanged();
        });
        roundsPanel.addView(roundsSlider, matchWidthParams());
        card.addView(roundsPanel);
        content.addView(card);
    }

    private void addImmediateSuggestionsCard() {
        LinearLayout card = card();
        List<View> childRows = new ArrayList<>();

        LinearLayout masterRow = horizontalRow();
        masterRow.addView(labels("启用即时建议", "根据 ColorOS 当前场景主动显示可执行建议"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch master = makeSwitch(config.isImmediateSuggestionsEnabled(),
                (view, checked) -> {
                    config.setImmediateSuggestionsEnabled(checked);
                    notifyBubbleTimeoutChanged();
                    for (View child : childRows) {
                        setEnabledRecursively(child, checked);
                        child.animate().alpha(checked ? 1f : 0.45f)
                                .setDuration(160L).start();
                    }
                });
        masterRow.addView(master);
        card.addView(masterRow);

        childRows.add(addImmediateSwitchRow(card, "临时保持屏幕常亮",
                "屏幕半暗后点击屏幕时，建议保持当前界面常亮",
                config.isScreenAwakePromptEnabled(), config::setScreenAwakePromptEnabled));
        childRows.add(addImmediateSwitchRow(card, "关闭手电筒",
                "离开相机后手电筒持续开启 5 秒时建议关闭",
                config.isFlashlightSuggestionEnabled(), config::setFlashlightSuggestionEnabled));
        childRows.add(addImmediateSwitchRow(card, "打开音乐应用",
                "连接耳机且没有播放音频时建议音乐应用",
                config.isMusicSuggestionEnabled(), config::setMusicSuggestionEnabled));
        childRows.add(addImmediateSwitchRow(card, "打开新应用",
                "首次安装带桌面入口的应用后建议打开",
                config.isAppInstallSuggestionEnabled(), config::setAppInstallSuggestionEnabled));
        childRows.add(addImmediateSwitchRow(card, "识别剪贴板内容",
                "仅在本机识别应用链接、购物链接、快递单号、电话号码和地址，默认关闭",
                config.isClipboardSuggestionEnabled(), config::setClipboardSuggestionEnabled));
        childRows.add(addImmediateSwitchRow(card, "临时通知不贴边",
                "建议气泡停在设定位置，不吸附、不半隐藏在屏幕边缘",
                config.isSuggestionNoEdgeEnabled(), config::setSuggestionNoEdgeEnabled));

        boolean enabled = config.isImmediateSuggestionsEnabled();
        for (View child : childRows) {
            setEnabledRecursively(child, enabled);
            child.setAlpha(enabled ? 1f : 0.45f);
        }
        content.addView(card);
    }

    private View addImmediateSwitchRow(LinearLayout card, String title, String subtitle,
                                       boolean checked, java.util.function.Consumer<Boolean> setter) {
        LinearLayout row = horizontalRow();
        row.addView(labels(title, subtitle),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(makeSwitch(checked, (view, value) -> {
            setter.accept(value);
            notifyBubbleTimeoutChanged();
        }));
        card.addView(row);
        return row;
    }

    private void addPlacementCard() {
        LinearLayout card = card();
        placementSummary = text(placementSummaryText(), 14, palette.onSurfaceVariant, false);
        card.addView(actionRow("气泡位置", placementSummary, "调整", view ->
                startActivity(new Intent(this, BubblePlacementActivity.class))));
        TextView resetSummary = text("恢复到系统默认的右下角位置", 14, palette.onSurfaceVariant, false);
        card.addView(actionRow("重置位置", resetSummary, "重置", view -> {
            config.resetPlacement();
            notifyBubbleTimeoutChanged();
            placementSummary.setText(placementSummaryText());
        }));
        content.addView(card);
    }

    private void addContentPreviewCard() {
        LinearLayout card = card();
        List<View> contentOptionRows = new ArrayList<>();
        final MaterialSlider[] widthSliderRef = new MaterialSlider[1];
        final LinearLayout[] widthPanelRef = new LinearLayout[1];

        LinearLayout row = horizontalRow();
        LinearLayout labels = labels("显示通知内容", "从气泡旁弹出应用名、标题和正文");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch enabled = makeSwitch(config.isContentPreviewEnabled(), (view, checked) -> {
            config.setContentPreviewEnabled(checked);
            notifyBubbleTimeoutChanged();
            for (View optionRow : contentOptionRows) {
                setEnabledRecursively(optionRow, checked);
                optionRow.animate().alpha(checked ? 1f : 0.45f)
                        .setDuration(160L).start();
            }
            MaterialSlider widthSlider = widthSliderRef[0];
            LinearLayout widthPanel = widthPanelRef[0];
            if (widthSlider != null && widthPanel != null) {
                boolean automatic = config.isContentPreviewAutoWidth();
                widthSlider.setEnabled(checked && !automatic);
                widthPanel.setAlpha(checked && !automatic ? 1f : 0.45f);
            }
        });
        row.addView(enabled);
        card.addView(row);

        LinearLayout edgeOnlyRow = horizontalRow();
        LinearLayout edgeOnlyLabels = labels("仅贴边显示内容", "气泡半隐藏在屏幕边缘时才弹出通知浮层");
        edgeOnlyRow.addView(edgeOnlyLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        edgeOnlyRow.addView(makeSwitch(config.isContentPreviewEdgeOnly(), (view, checked) -> {
            config.setContentPreviewEdgeOnly(checked);
            notifyBubbleTimeoutChanged();
        }));
        contentOptionRows.add(edgeOnlyRow);
        card.addView(edgeOnlyRow);

        LinearLayout suppressBannerRow = horizontalRow();
        LinearLayout suppressBannerLabels = labels("气泡接管时隐藏横幅通知",
                "气泡出现时不再同时显示系统顶部横幅（贴边与否则由内容浮层开关决定）");
        suppressBannerRow.addView(suppressBannerLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        suppressBannerRow.addView(makeSwitch(config.isContentPreviewSuppressBanner(), (view, checked) -> {
            config.setContentPreviewSuppressBanner(checked);
            notifyBubbleTimeoutChanged();
        }));
        contentOptionRows.add(suppressBannerRow);
        card.addView(suppressBannerRow);


        LinearLayout clearStatusRow = horizontalRow();
        LinearLayout clearStatusLabels = labels("下滑气泡时清除状态栏通知",
                "下滑关闭当前气泡时，同时尝试清除对应的系统通知");
        clearStatusRow.addView(clearStatusLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearStatusRow.addView(makeSwitch(config.isClearStatusOnDownSwipeEnabled(), (view, checked) -> {
            config.setClearStatusOnDownSwipeEnabled(checked);
            notifyBubbleTimeoutChanged();
        }));
        contentOptionRows.add(clearStatusRow);
        card.addView(clearStatusRow);

        LinearLayout fluidCloudRow = horizontalRow();
        LinearLayout fluidCloudLabels = labels("包含流体云通知",
                "将系统流体云中的应用通知也显示为气泡");
        fluidCloudRow.addView(fluidCloudLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fluidCloudRow.addView(makeSwitch(config.isIncludeFluidCloudNotificationsEnabled(), (view, checked) -> {
            config.setIncludeFluidCloudNotificationsEnabled(checked);
            notifyBubbleTimeoutChanged();
        }));
        contentOptionRows.add(fluidCloudRow);
        card.addView(fluidCloudRow);

        boolean contentEnabled = config.isContentPreviewEnabled();
        for (View optionRow : contentOptionRows) {
            setEnabledRecursively(optionRow, contentEnabled);
            optionRow.setAlpha(contentEnabled ? 1f : 0.45f);
        }

        contentPositionSummary = text(contentPositionLabel(), 14, palette.onSurfaceVariant, false);
        card.addView(actionRow("内容位置", contentPositionSummary, "选择",
                view -> showContentPositionPicker()));

        LinearLayout alphaPanel = sliderPanel();
        contentAlphaValue = text("浮层不透明度  " + config.getContentPreviewAlpha() + "%",
                14, palette.onSurface, true);
        alphaPanel.addView(contentAlphaValue);
        MaterialSlider alpha = makeSlider(35, 100, config.getContentPreviewAlpha());
        alpha.setOnValueChange((slider, percent) -> {
            config.setContentPreviewAlpha(percent);
            notifyBubbleTimeoutChanged();
            contentAlphaValue.setText("浮层不透明度  " + percent + "%");
        });
        alphaPanel.addView(alpha, matchWidthParams());
        contentOptionRows.add(alphaPanel);
        card.addView(alphaPanel);
        setEnabledRecursively(alphaPanel, contentEnabled);
        alphaPanel.setAlpha(contentEnabled ? 1f : 0.45f);

        LinearLayout overlayPanel = sliderPanel();
        TextView overlayValue = text("内部叠加层  "
                + config.getContentPreviewOverlayAlpha() + "%", 14, palette.onSurface, true);
        overlayPanel.addView(overlayValue);
        MaterialSlider overlaySlider = makeSlider(0, 70,
                config.getContentPreviewOverlayAlpha());
        overlaySlider.setOnValueChange((slider, percent) -> {
            config.setContentPreviewOverlayAlpha(percent);
            notifyBubbleTimeoutChanged();
            overlayValue.setText("内部叠加层  " + percent + "%");
        });
        overlayPanel.addView(overlaySlider, matchWidthParams());
        contentOptionRows.add(overlayPanel);
        card.addView(overlayPanel);
        setEnabledRecursively(overlayPanel, contentEnabled);
        overlayPanel.setAlpha(contentEnabled ? 1f : 0.45f);

        LinearLayout widthModeRow = horizontalRow();
        LinearLayout widthModeLabels = labels("自动调整浮层长度", "根据通知文字和屏幕可用空间决定长度");
        widthModeRow.addView(widthModeLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSlider width = makeSlider(180, 360, config.getContentPreviewWidthDp());
        LinearLayout widthPanel = sliderPanel();
        widthSliderRef[0] = width;
        widthPanelRef[0] = widthPanel;
        MaterialSwitch autoWidth = makeSwitch(config.isContentPreviewAutoWidth(), (view, checked) -> {
            config.setContentPreviewAutoWidth(checked);
            notifyBubbleTimeoutChanged();
            width.setEnabled(!checked && widthModeRow.isEnabled());
            widthPanel.animate().alpha(checked || !widthModeRow.isEnabled() ? 0.45f : 1f)
                    .setDuration(160L).start();
        });
        widthModeRow.addView(autoWidth);
        contentOptionRows.add(widthModeRow);
        card.addView(widthModeRow);

        contentWidthValue = text("手动长度  " + config.getContentPreviewWidthDp() + " dp",
                14, palette.onSurface, true);
        widthPanel.addView(contentWidthValue);
        width.setOnValueChange((slider, widthDp) -> {
            if (config.isContentPreviewAutoWidth() || !slider.isEnabled()) return;
            config.setContentPreviewWidthDp(widthDp);
            notifyBubbleTimeoutChanged();
            contentWidthValue.setText("手动长度  " + widthDp + " dp");
        });
        widthPanel.addView(width, matchWidthParams());
        boolean automatic = config.isContentPreviewAutoWidth();
        width.setEnabled(!automatic);
        widthPanel.setAlpha(automatic ? 0.45f : 1f);
        contentOptionRows.add(widthPanel);
        card.addView(widthPanel);
        setEnabledRecursively(widthModeRow, contentEnabled);
        setEnabledRecursively(widthPanel, contentEnabled);
        widthModeRow.setAlpha(contentEnabled ? 1f : 0.45f);
        width.setEnabled(contentEnabled && !automatic);
        widthPanel.setAlpha(contentEnabled && !automatic ? 1f : 0.45f);

        LinearLayout dynamicTimeoutRow = horizontalRow();
        LinearLayout dynamicTimeoutLabels = labels("按字数动态延长停留", "根据通知文字多少自动增加浮层显示时长");
        dynamicTimeoutRow.addView(dynamicTimeoutLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch dynamicTimeout = makeSwitch(config.isContentPreviewDynamicTimeout(), (view, checked) -> {
            config.setContentPreviewDynamicTimeout(checked);
            notifyBubbleTimeoutChanged();
        });
        dynamicTimeoutRow.addView(dynamicTimeout);
        contentOptionRows.add(dynamicTimeoutRow);
        card.addView(dynamicTimeoutRow);

        LinearLayout previewTimeoutPanel = sliderPanel();
        contentTimeoutValue = text(
                "内容停留  " + config.getContentPreviewTimeoutSeconds() + " 秒",
                14, palette.onSurface, true);
        previewTimeoutPanel.addView(contentTimeoutValue);
        MaterialSlider previewTimeout = makeSlider(2, 15, config.getContentPreviewTimeoutSeconds());
        previewTimeout.setOnValueChange((slider, seconds) -> {
            config.setContentPreviewTimeoutSeconds(seconds);
            notifyBubbleTimeoutChanged();
            contentTimeoutValue.setText("内容停留  " + seconds + " 秒");
        });
        previewTimeoutPanel.addView(previewTimeout, matchWidthParams());
        contentOptionRows.add(previewTimeoutPanel);
        card.addView(previewTimeoutPanel);
        setEnabledRecursively(dynamicTimeoutRow, contentEnabled);
        setEnabledRecursively(previewTimeoutPanel, contentEnabled);
        dynamicTimeoutRow.setAlpha(contentEnabled ? 1f : 0.45f);
        previewTimeoutPanel.setAlpha(contentEnabled ? 1f : 0.45f);

        content.addView(card);
    }

    /** 气泡大小、气泡内图标与角标大小的调节（含实时预览）。 */
    private void addAppearanceCard() {
        LinearLayout card = card();

        LinearLayout bubbleRow = horizontalRow();
        bubbleRow.addView(labels("气泡大小", "整体缩放气泡（通知图标与角标按同比例缩放）"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bubbleSizeValue = text(config.getBubbleSizePercent() + "%", 15, palette.primary, true);
        bubbleRow.addView(bubbleSizeValue);
        card.addView(bubbleRow);

        LinearLayout bubblePanel = sliderPanel();
        MaterialSlider bubbleSlider = makeSlider(BubbleConfig.MIN_BUBBLE_SIZE_PERCENT,
                BubbleConfig.MAX_BUBBLE_SIZE_PERCENT, config.getBubbleSizePercent());
        bubbleSlider.setOnValueChange((materialSlider, percent) -> {
            bubbleSizeValue.setText(percent + "%");
            applyPreviewBubbleSize(percent);
            scheduleBubbleSizePersist(percent);
        });
        bubblePanel.addView(bubbleSlider, matchWidthParams());
        TextView bubbleHint = text(
                "60%–140%，默认 100%；当前显示的气泡也会立即缩放", 12,
                palette.onSurfaceVariant, false);
        bubbleHint.setPadding(0, dp(6), 0, 0);
        bubblePanel.addView(bubbleHint);
        card.addView(bubblePanel);

        LinearLayout row = horizontalRow();
        row.addView(labels("气泡图标大小", "调整气泡内部通知图标占气泡的比例"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        iconSizeValue = text(config.getIconSizePercent() + "%", 15, palette.primary, true);
        row.addView(iconSizeValue);
        card.addView(row);

        // Reuse the real bubble view so the preview matches the actual overlay exactly.
        // The stage grows with the bubble size so the preview is never clipped.
        iconPreviewStage = new FrameLayout(this);
        iconPreviewStage.setClipChildren(false);
        iconPreviewStage.setClipToPadding(false);
        int previewSize = Math.round(dp(92f) * config.getBubbleSizePercent() / 100f);
        try {
            BubbleView preview = new BubbleView(this, config);
            preview.setClickable(false);
            preview.setFocusable(false);
            preview.setContentDescription("气泡外观预览");
            Drawable sampleIcon = null;
            try {
                sampleIcon = getPackageManager().getApplicationIcon(getPackageName());
            } catch (Throwable ignored) {
            }
            if (sampleIcon == null) sampleIcon = getDrawable(R.drawable.ic_bubble);
            preview.setIcon(sampleIcon);
            preview.setAppBadge(sampleIcon);
            iconPreviewStage.addView(preview, new FrameLayout.LayoutParams(
                    previewSize, previewSize, Gravity.CENTER));
            iconPreview = preview;
        } catch (Throwable ignored) {
            // The preview is a nicety; the slider keeps working without it.
            iconPreview = null;
        }
        card.addView(iconPreviewStage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(94f), previewSize + dp(12f))));

        LinearLayout panel = sliderPanel();
        MaterialSlider slider = makeSlider(BubbleConfig.MIN_ICON_SIZE_PERCENT,
                BubbleConfig.MAX_ICON_SIZE_PERCENT, config.getIconSizePercent());
        slider.setOnValueChange((materialSlider, percent) -> {
            iconSizeValue.setText(percent + "%");
            if (iconPreview != null) iconPreview.setIconScalePercent(percent);
            scheduleIconSizePersist(percent);
        });
        panel.addView(slider, matchWidthParams());
        TextView hint = text("60% 更通透，150% 图标填满气泡（超出部分会被气泡边缘裁掉）；默认 100%", 12,
                palette.onSurfaceVariant, false);
        hint.setPadding(0, dp(6), 0, 0);
        panel.addView(hint);
        card.addView(panel);

        LinearLayout badgeRow = horizontalRow();
        badgeRow.addView(labels("角标大小", "气泡边缘应用图标的大小（使用应用原始图标）"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badgeSizeValue = text(config.getBadgeSizePercent() + "%", 15, palette.primary, true);
        badgeRow.addView(badgeSizeValue);
        card.addView(badgeRow);

        LinearLayout badgePanel = sliderPanel();
        MaterialSlider badgeSlider = makeSlider(BubbleConfig.MIN_BADGE_SIZE_PERCENT,
                BubbleConfig.MAX_BADGE_SIZE_PERCENT, config.getBadgeSizePercent());
        badgeSlider.setOnValueChange((materialSlider, percent) -> {
            badgeSizeValue.setText(percent + "%");
            if (iconPreview != null) iconPreview.setBadgeScalePercent(percent);
            scheduleBadgeSizePersist(percent);
        });
        badgePanel.addView(badgeSlider, matchWidthParams());
        TextView badgeHint = text(
                "50%–180%，默认 100%；角标中心始终位于气泡圆形边上，一半露在气泡外",
                12, palette.onSurfaceVariant, false);
        badgeHint.setPadding(0, dp(6), 0, 0);
        badgePanel.addView(badgeHint);
        card.addView(badgePanel);

        content.addView(card);
    }

    /** Resizes the in-page bubble preview without rebuilding the whole settings page. */
    private void applyPreviewBubbleSize(int percent) {
        if (iconPreview == null || iconPreviewStage == null) return;
        iconPreview.setBubbleSizePercent(percent);
        int size = iconPreview.getBubbleSizePx();
        ViewGroup.LayoutParams previewParams = iconPreview.getLayoutParams();
        if (previewParams != null) {
            previewParams.width = size;
            previewParams.height = size;
            iconPreview.setLayoutParams(previewParams);
        }
        ViewGroup.LayoutParams stageParams = iconPreviewStage.getLayoutParams();
        if (stageParams != null) {
            stageParams.height = Math.max(dp(94f), size + dp(12f));
            iconPreviewStage.setLayoutParams(stageParams);
        }
        iconPreviewStage.requestLayout();
    }

    private void scheduleBubbleSizePersist(int percent) {
        pendingBubbleSizePercent = percent;
        iconSizeHandler.removeCallbacks(bubbleSizeCommitAction);
        iconSizeHandler.postDelayed(bubbleSizeCommitAction, 120L);
    }

    /** Coalesces slider drags into one preference write (~120ms after the last move). */
    private void scheduleIconSizePersist(int percent) {
        pendingIconSizePercent = percent;
        iconSizeHandler.removeCallbacks(iconSizeCommitAction);
        iconSizeHandler.postDelayed(iconSizeCommitAction, 120L);
    }

    private void scheduleBadgeSizePersist(int percent) {
        pendingBadgeSizePercent = percent;
        iconSizeHandler.removeCallbacks(badgeSizeCommitAction);
        iconSizeHandler.postDelayed(badgeSizeCommitAction, 120L);
    }

    private void addAnimationCard() {
        animationScreenCard = (SettingsCard) card();
        LinearLayout row = horizontalRow();
        LinearLayout labels = labels("持续播放主气泡弹跳", "以较长间隔重复轻微落地、压缩和回弹动画");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(makeSwitch(config.isMainBubbleAnimationContinuous(), (view, checked) -> {
            config.setMainBubbleAnimationContinuous(checked);
            notifyBubbleTimeoutChanged();
        }));
        animationScreenCard.addView(row);
        content.addView(animationScreenCard);
    }

    private void showContentPositionPicker() {
        String[] choices = {"自动（优先屏幕内侧）", "气泡上方", "气泡下方", "屏幕内侧"};
        int selected = config.getContentPreviewPosition();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("通知内容位置")
                .setSingleChoiceItems(choices, selected, (dialog1, which) -> {
                    config.setContentPreviewPosition(which);
                    notifyBubbleTimeoutChanged();
                    contentPositionSummary.setText(contentPositionLabel());
                    dialog1.dismiss();
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> applyDialogStyle(dialog));
        dialog.show();
    }

    private String contentPositionLabel() {
        switch (config.getContentPreviewPosition()) {
            case BubbleConfig.CONTENT_ABOVE: return "气泡上方";
            case BubbleConfig.CONTENT_BELOW: return "气泡下方";
            case BubbleConfig.CONTENT_INWARD: return "屏幕内侧";
            default: return "自动选择不遮挡的位置";
        }
    }

    private void addTimeoutCard() {
        LinearLayout card = card();
        LinearLayout switchRow = horizontalRow();
        LinearLayout switchLabels = labels("启用气泡超时", "关闭后气泡会保留到点击或被新通知替换");
        switchRow.addView(switchLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch autoDismiss = makeSwitch(config.isBubbleAutoDismissEnabled(), null);
        switchRow.addView(autoDismiss);
        card.addView(switchRow);

        LinearLayout dimRow = horizontalRow();
        LinearLayout dimLabels = labels("超时后降低透明度", "到达时长后保留气泡并降低至约 35%");
        dimRow.addView(dimLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch dimOnTimeout = makeSwitch(config.shouldDimBubbleOnTimeout(), (view, checked) -> {
            config.setDimBubbleOnTimeout(checked);
            notifyBubbleTimeoutChanged();
            timeoutValue.setText(checked
                    ? "降低透明度  " + config.getTimeoutSeconds() + " 秒"
                    : "自动关闭  " + config.getTimeoutSeconds() + " 秒");
        });
        dimRow.addView(dimOnTimeout);
        card.addView(dimRow);

        LinearLayout timeoutPanel = sliderPanel();
        timeoutValue = text("自动关闭  " + config.getTimeoutSeconds() + " 秒",
                14, palette.onSurface, true);
        timeoutPanel.addView(timeoutValue);
        MaterialSlider timeout = makeSlider(3, 15, config.getTimeoutSeconds());
        timeout.setOnValueChange((slider, seconds) -> {
            config.setTimeoutSeconds(seconds);
            notifyBubbleTimeoutChanged();
            timeoutValue.setText((config.shouldDimBubbleOnTimeout()
                    ? "降低透明度  " : "自动关闭  ") + seconds + " 秒");
        });
        timeoutPanel.addView(timeout, matchWidthParams());
        card.addView(timeoutPanel);

        boolean enabled = config.isBubbleAutoDismissEnabled();
        timeout.setEnabled(enabled);
        dimOnTimeout.setEnabled(enabled);
        timeoutPanel.setAlpha(enabled ? 1f : 0.45f);
        dimRow.setAlpha(enabled ? 1f : 0.45f);
        if (!enabled) {
            timeoutValue.setText("永不自动关闭");
        } else if (config.shouldDimBubbleOnTimeout()) {
            timeoutValue.setText("降低透明度  " + config.getTimeoutSeconds() + " 秒");
        }
        autoDismiss.setOnCheckedChanged((view, checked) -> {
            config.setBubbleAutoDismissEnabled(checked);
            notifyBubbleTimeoutChanged();
            timeout.setEnabled(checked);
            dimOnTimeout.setEnabled(checked);
            dimRow.animate().alpha(checked ? 1f : 0.45f).setDuration(160L).start();
            timeoutPanel.animate().alpha(checked ? 1f : 0.45f).setDuration(160L).start();
            timeoutValue.setText(checked
                    ? (config.shouldDimBubbleOnTimeout()
                            ? "降低透明度  " : "自动关闭  ")
                            + config.getTimeoutSeconds() + " 秒"
                    : "永不自动关闭");
        });
        content.addView(card);
    }

    private void addDiagnosticsCard() {
        LinearLayout card = card();
        TextView exportSummary = text(
                "导出 SystemUI 与 system_server 的运行记录，不含通知文本。",
                14, palette.onSurfaceVariant, false);
        card.addView(actionRow("导出运行日志", exportSummary, "导出",
                view -> exportRuntimeLog()));
        TextView clearSummary = text("删除已捕获的运行日志。", 14, palette.onSurfaceVariant, false);
        card.addView(actionRow("清除运行日志", clearSummary, "清除", view ->
                getContentResolver().call(BubbleConfigProvider.URI,
                        BubbleConfigProvider.METHOD_CLEAR_RUNTIME_LOG, null, null)));
        content.addView(card);
    }

    private void exportRuntimeLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "BubbleNotify-runtime.log");
        startActivityForResult(intent, REQUEST_EXPORT_RUNTIME_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_WHITELIST
                || requestCode == REQUEST_PICK_BLACKLIST
                || requestCode == REQUEST_PICK_STATUS_SCROLL_WHITELIST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> selected = data.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED);
                if (selected != null) {
                    Set<String> set = new HashSet<>(selected);
                    if (requestCode == REQUEST_PICK_WHITELIST) {
                        config.setWhitelist(set);
                    } else if (requestCode == REQUEST_PICK_BLACKLIST) {
                        config.setBlacklist(set);
                    } else {
                        config.setStatusScrollWhitelist(set);
                    }
                    notifyBubbleTimeoutChanged();
                    refreshApplicationSummaries();
                }
            }
            return;
        }
        if (requestCode != REQUEST_EXPORT_RUNTIME_LOG || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        String runtimeLog = "";
        try {
            Bundle result = getContentResolver().call(BubbleConfigProvider.URI,
                    BubbleConfigProvider.METHOD_READ_RUNTIME_LOG, null, null);
            if (result != null && result.getBoolean("success")) {
                runtimeLog = result.getString("log", "");
            }
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new IOException("No output stream");
                output.write(runtimeLog.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setMessage("无法导出运行日志: " + error.getClass().getSimpleName())
                    .setPositiveButton("确定", null)
                    .create();
            dialog.setOnShowListener(d -> applyDialogStyle(dialog));
            dialog.show();
        }
    }

    private void notifyBubbleTimeoutChanged() {
        getContentResolver().notifyChange(BubbleConfigProvider.URI, null);
    }

    // ---------- 控件工厂 ----------

    private MaterialSwitch makeSwitch(boolean checked, MaterialSwitch.OnCheckedChangeListener listener) {
        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setPalette(palette);
        sw.setChecked(checked);
        sw.setOnCheckedChanged(listener);
        return sw;
    }

    private View makeStatusScrollModePicker(int selectedMode) {
        LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.HORIZONTAL);
        picker.setGravity(Gravity.CENTER);
        picker.setPadding(dp(2), dp(2), dp(2), dp(2));
        picker.setBackground(outlinedRounded(palette.surface, 14, palette.outline));
        picker.setLayoutParams(new LinearLayout.LayoutParams(dp(222), dp(42)));

        String[] labels = {"关闭", "通知栏", "流体云"};
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            TextView option = text(labels[i], 13, palette.onSurfaceVariant, true);
            option.setGravity(Gravity.CENTER);
            option.setMinHeight(dp(36));
            option.setPadding(dp(4), 0, dp(4), 0);
            option.setClickable(true);
            option.setFocusable(true);
            option.setForeground(rippleOver(null));
            option.setOnClickListener(view -> {
                config.setStatusScrollMode(mode);
                updateStatusScrollModePicker(picker, mode);
                notifyBubbleTimeoutChanged();
            });
            picker.addView(option, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        updateStatusScrollModePicker(picker, selectedMode);
        return picker;
    }

    private void updateStatusScrollModePicker(LinearLayout picker, int selectedMode) {
        if (picker == null) return;
        int normalized = Math.max(BubbleConfig.STATUS_SCROLL_OFF,
                Math.min(BubbleConfig.STATUS_SCROLL_FLUID_CLOUD, selectedMode));
        for (int i = 0; i < picker.getChildCount(); i++) {
            View child = picker.getChildAt(i);
            boolean selected = i == normalized;
            child.setBackground(statusScrollSegmentBackground(i, selected));
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(selected
                        ? palette.onSecondaryContainer : palette.onSurface);
            }
        }
    }

    private GradientDrawable statusScrollSegmentBackground(int index, boolean selected) {
        int radius = dp(11);
        float left = index == 0 ? radius : 0f;
        float right = index == 2 ? radius : 0f;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? palette.secondaryContainer : android.graphics.Color.TRANSPARENT);
        drawable.setCornerRadii(new float[]{left, left, right, right, right, right, left, left});
        return drawable;
    }

    private MaterialSlider makeSlider(int min, int max, int value) {
        MaterialSlider slider = new MaterialSlider(this);
        slider.setPalette(palette);
        slider.setRange(min, max);
        slider.setValue(value);
        return slider;
    }

    private LinearLayout sliderPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(8));
        return panel;
    }

    private LinearLayout.LayoutParams matchWidthParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addInfoPanel() {
        TextView info = text(
                "使用帮助\n\n"
                        + "• 首次使用请确认已在 LSPosed 中启用模块并勾选 SystemUI 作用域；修改作用域或核心开关后，建议重启 SystemUI。\n"
                        + "• 通知白名单决定哪些应用可以显示气泡，未勾选任何应用时不会显示气泡；应用黑名单中的应用不会弹出气泡，且这些应用在前台时其它应用也不弹气泡。\n"
                        + "• 内容浮层会从对应气泡释出并在超时后收回；开启“气泡接管时隐藏横幅通知”后，气泡出现时不再同时显示系统顶部横幅（气泡未贴边时同样生效）。关闭内容浮层时，仍可使用气泡点击和手势操作通知。\n"
                        + "• 状态栏滚动通知用于补充显示未开启横幅的应用通知；开启内容浮层或横幅通知的应用不会重复显示。\n"
                        + "• 气泡外观：可分别调整气泡内通知图标与右下角应用角标的大小；角标直接使用应用原始图标（不再套圆形底）。\n"
                        + "• 有多条通知时，其余气泡堆叠在主气泡后方；长按后松开或长按上滑会用连续动画展开（上方空间不足时向下展开），点击任一气泡即可打开对应通知或建议。展开期间暂停动画，短暂无操作自动收回堆叠状态。\n"
                        + "• 点击气泡打开对应通知；长按后拖动可改变位置，横向滑动可贴边，向下滑动关闭气泡（临时常亮气泡同样支持下滑关闭）；是否同步清除状态栏通知由本页开关决定。\n"
                        + "• 息屏或锁屏时收到消息同样会生成气泡，气泡在此期间不会自动消失，屏幕点亮后可直接查看并在解锁后显示内容浮层。\n"
                        + "• 常亮询问仅在屏幕即将熄灭后被点击唤亮时触发，确认后保持常亮，退出当前界面或手动熄屏即可结束。\n"
                        + "• 若通知没有显示、位置异常或设置未及时生效，请先重新打开本页或重启 SystemUI；仍有问题时可在“诊断”中导出运行日志。\n\n"
                        + "当前版本：" + versionName(),
                14, palette.onSurfaceVariant, false);
        info.setLineSpacing(dp(4), 1f);
        info.setPadding(dp(16), dp(18), dp(16), 0);
        content.addView(info);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private View actionRow(String title, TextView summary, String action,
                           View.OnClickListener listener) {
        LinearLayout row = horizontalRow();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, palette.onSurface, true));
        summary.setPadding(0, dp(3), dp(8), 0);
        labels.addView(summary);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button button = tonalButton(action);
        button.setOnClickListener(listener);
        row.addView(button);
        return row;
    }

    private static final int REQUEST_PICK_WHITELIST = 801;
    private static final int REQUEST_PICK_BLACKLIST = 802;
    private static final int REQUEST_PICK_STATUS_SCROLL_WHITELIST = 803;

    private void showApplicationPicker(boolean whitelist) {
        showApplicationPicker(whitelist, false);
    }

    private void showStatusScrollApplicationPicker() {
        showApplicationPicker(true, true);
    }

    private void showApplicationPicker(boolean whitelist, boolean statusScroll) {
        Intent intent = new Intent(this, AppPickerActivity.class);
        intent.putExtra(AppPickerActivity.EXTRA_WHITELIST, whitelist);
        intent.putExtra(AppPickerActivity.EXTRA_STATUS_SCROLL, statusScroll);
        intent.putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED,
                new ArrayList<>(statusScroll ? config.getStatusScrollWhitelist()
                        : (whitelist ? config.getWhitelist() : config.getBlacklist())));
        int requestCode = statusScroll ? REQUEST_PICK_STATUS_SCROLL_WHITELIST
                : (whitelist ? REQUEST_PICK_WHITELIST : REQUEST_PICK_BLACKLIST);
        startActivityForResult(intent, requestCode);
    }


    private List<AppItem> loadApplications() {
        PackageManager packageManager = getPackageManager();
        List<AppItem> result = new ArrayList<>();
        for (ApplicationInfo info : packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0))) {
            if (!info.enabled || info.packageName.equals(getPackageName())) continue;
            CharSequence label = info.loadLabel(packageManager);
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            result.add(new AppItem(
                    info.packageName,
                    label == null ? info.packageName : label.toString(),
                    system));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        result.sort((left, right) -> {
            if (left.system != right.system) return left.system ? 1 : -1;
            int labelResult = collator.compare(left.label, right.label);
            return labelResult != 0 ? labelResult
                    : left.packageName.compareTo(right.packageName);
        });
        return result;
    }

    private void refreshApplicationSummaries() {
        whitelistSummary.setText(whitelistSummaryText());
        blacklistSummary.setText(blacklistSummaryText());
    }

    private String whitelistSummaryText() {
        int count = config.getWhitelist().size();
        return count == 0 ? "未选择应用，不会显示气泡" : "已选择 " + count + " 个应用";
    }

    private String blacklistSummaryText() {
        int count = config.getBlacklist().size();
        return count == 0 ? "未屏蔽任何应用" : "已屏蔽 " + count + " 个应用";
    }

    private String statusScrollWhitelistSummaryText() {
        int count = config.getStatusScrollWhitelist().size();
        return count == 0 ? "所有应用均可显示" : "已选择 " + count + " 个应用";
    }

    private String placementSummaryText() {
        return "竖屏 " + config.getPositionX(false) + "%, " + config.getPositionY(false)
                + "%" + dockLabel(config.getDockEdge(false))
                + "  ·  横屏 " + config.getPositionX(true) + "%, "
                + config.getPositionY(true) + "%" + dockLabel(config.getDockEdge(true));
    }

    private String dockLabel(int edge) {
        if (edge < 0) return "（左侧半隐藏）";
        if (edge > 0) return "（右侧半隐藏）";
        return "";
    }

    // ---------- 视觉辅助 ----------

    /** 为 AlertDialog 应用 MD3 Expressive 圆角背景与按钮样式。 */
    private void applyDialogStyle(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable background = rounded(palette.surface, 28);
            window.setBackgroundDrawable(background);
        }
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        styleDialogButton(positive, true);
        styleDialogButton(negative, false);
        styleDialogButton(neutral, false);
    }

    private void styleDialogButton(Button button, boolean filled) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(20), dp(10), dp(20), dp(10));
        if (filled) {
            button.setTextColor(palette.onPrimary);
            button.setBackground(rounded(palette.primary, 20));
            button.setForeground(rippleOver(palette.primary));
        } else {
            button.setTextColor(palette.onSurface);
            button.setBackground(rounded(palette.surfaceContainerHighest, 20));
            button.setForeground(rippleOver(palette.surfaceContainerHighest));
        }
    }

    /** 入场动画：卡片轻微上浮淡入，制造 MD3 层次感。 */
    private void playEntrance(View card) {
        card.setAlpha(0f);
        card.setTranslationY(dp(14));
        card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .setStartDelay(40L * entranceIndex++)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();
    }

    private void addSectionLabel(String value) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(2), dp(4), dp(2), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        params.bottomMargin = dp(6);
        chip.setLayoutParams(params);

        View marker = new View(this);
        marker.setBackground(rounded(palette.primary, 3));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(4), dp(18));
        markerParams.rightMargin = dp(10);
        chip.addView(marker, markerParams);
        TextView label = text(value, 13, palette.onSurfaceVariant, true);
        label.setLetterSpacing(0.01f);
        chip.addView(label);
        content.addView(chip);
    }

    private LinearLayout card() {
        SettingsCard card = new SettingsCard(this);
        playEntrance(card);
        return card;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setForeground(rippleOver(null));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private LinearLayout labels(String title, String subtitle) {
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 17, palette.onSurface, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = text(subtitle, 14, palette.onSurfaceVariant, false);
            detail.setPadding(0, dp(4), dp(8), 0);
            labels.addView(detail);
        }
        return labels;
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    /** MD3 Expressive 填充色调按钮（primary 底 + onPrimary 文字，大圆角）。 */
    private Button tonalButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(palette.onSecondaryContainer);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setStateListAnimator(null);
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
        button.setBackground(rounded(palette.secondaryContainer, 20));
        button.setElevation(0f);
        button.setForeground(rippleOver(palette.secondaryContainer));
        return button;
    }

    /** 返回一个 ripple drawable，可叠加在任意背景上。 */
    private android.graphics.drawable.Drawable rippleOver(Integer maskColor) {
        android.content.res.TypedArray typed = getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        android.graphics.drawable.Drawable ripple = typed.getDrawable(0);
        typed.recycle();
        if (maskColor != null && ripple != null) {
            ripple.setTint(maskColor);
        }
        return ripple;
    }

    /** 内嵌分隔线：从文字起始处开始（MD3 列表分隔线规范）。 */
    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(palette.outlineVariant);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f)));
        params.leftMargin = dp(16);
        divider.setLayoutParams(params);
        return divider;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlinedRounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(Math.max(1, dp(1)), strokeColor);
        return drawable;
    }

    /** 设置页分组行背景：首尾大圆角，中间小圆角。 */
    private GradientDrawable rowBackground(boolean first, boolean last) {
        int big = dp(28);
        int small = dp(4);
        float[] radii;
        if (first && last) {
            radii = new float[]{big, big, big, big, big, big, big, big};
        } else if (first) {
            radii = new float[]{big, big, big, big, small, small, small, small};
        } else if (last) {
            radii = new float[]{small, small, small, small, big, big, big, big};
        } else {
            radii = new float[]{small, small, small, small, small, small, small, small};
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(palette.surface);
        drawable.setCornerRadii(radii);
        return drawable;
    }

    private void updateStatusPill(boolean enabled) {
        if (statusPill == null) return;
        statusPill.setText(enabled ? "正在运行" : "停用气泡通知");
        statusPill.setTextColor(enabled ? palette.onPrimaryContainer : palette.onSurfaceVariant);
        statusPill.setBackground(rounded(enabled
                ? palette.primaryContainer : palette.surfaceContainerHighest, 14));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setEnabledRecursively(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursively(group.getChildAt(index), enabled);
        }
    }

    private static final class AppItem {
        final String packageName;
        final String label;
        final boolean system;

        AppItem(String packageName, String label, boolean system) {
            this.packageName = packageName;
            this.label = label;
            this.system = system;
        }
    }

    /** Lightweight self-drawn bubbles used as a playful title accent. */
    private final class TitleBubbleEmitter extends View {
        private final TextView titleView;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private final ArrayList<TitleBubble> bubbles = new ArrayList<>();
        private final Runnable nextCycleAction = this::startCycle;
        private ValueAnimator animator;
        private long cycleDuration;
        private boolean running;
        private boolean animationEnabled;

        TitleBubbleEmitter(android.content.Context context, TextView titleView, int accent,
                           boolean initiallyEnabled) {
            super(context);
            this.titleView = titleView;
            this.animationEnabled = initiallyEnabled;
            setWillNotDraw(false);
            setClickable(false);
            fill.setStyle(Paint.Style.FILL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(0.8f));
            edge.setColor(Color.argb(150, 255, 255, 255));
            // Use the same accent family as the section markers, softened for
            // the small translucent bubbles.
            fill.setColor(Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            running = animationEnabled;
            if (running) post(nextCycleAction);
        }

        @Override
        protected void onDetachedFromWindow() {
            running = false;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            removeCallbacks(nextCycleAction);
            super.onDetachedFromWindow();
        }

        private void startCycle() {
            if (!running || !animationEnabled) return;
            bubbles.clear();
            int count = 3 + random.nextInt(5); // 3–7 bubbles per cycle
            // Launch from the centre of the status pill rather than from the
            // title text, so every bubble visibly rises out of “正在运行”.
            float anchor = titleView.getWidth() > 0
                    ? titleView.getWidth() * 0.5f
                    : titleView.getPaint().measureText(titleView.getText().toString()) * 0.5f
                            + dp(10);
            for (int i = 0; i < count; i++) {
                boolean beforeTitle = random.nextFloat() < 0.08f;
                float baseX = beforeTitle
                        ? anchor - dp(4) - random.nextFloat() * dp(13)
                        : anchor + random.nextFloat() * dp(42);
                bubbles.add(new TitleBubble(baseX,
                        dp(2.2f + random.nextFloat() * 3.2f),
                        random.nextFloat() * 0.22f,
                        // The emitter surface is extended above the pill; this
                        // rise carries bubbles to roughly the midpoint of the
                        // enlarged blank area above the title.
                        dp(104 + random.nextFloat() * 22),
                        random.nextFloat() * (float) (Math.PI * 2),
                        random.nextFloat() * 0.9f + 0.65f));
            }
            // Slow the complete rise/sway/burst cycle to roughly half speed
            // while preserving the same easing and stagger between bubbles.
            cycleDuration = (1050L + random.nextInt(360)) * 2L;
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(cycleDuration);
            animator.addUpdateListener(value -> invalidate());
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!running) return;
                    postDelayed(nextCycleAction,
                            260L + random.nextInt(500));
                }
            });
            animator.start();
        }

        void setAnimationEnabled(boolean enabled) {
            animationEnabled = enabled;
            if (!enabled) {
                running = false;
                removeCallbacks(nextCycleAction);
                if (animator != null) {
                    animator.cancel();
                    animator = null;
                }
                bubbles.clear();
                invalidate();
                return;
            }
            if (isAttachedToWindow() && !running) {
                running = true;
                removeCallbacks(nextCycleAction);
                post(nextCycleAction);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (animator == null || bubbles.isEmpty()) return;
            float time = (float) animator.getAnimatedValue();
            for (TitleBubble bubble : bubbles) {
                float local = clamp01((time - bubble.delay) / (1f - bubble.delay));
                if (local <= 0f || local >= 1f) continue;
                float eased = 1f - (float) Math.pow(1f - local, 3f);
                float sway = (float) Math.sin(local * Math.PI * 2.2f + bubble.phase)
                        * dp(3.5f) * bubble.sway;
                float x = bubble.startX + sway;
                float y = getHeight() - dp(4) - bubble.startRise * eased;
                float burst = clamp01((local - 0.78f) / 0.22f);
                float radius = bubble.radius * (0.20f + 0.95f * clamp01(local / 0.42f));
                radius *= 1f + 0.8f * burst;
                int alpha = Math.round(175f * (1f - burst)
                        * Math.min(1f, local / 0.18f));
                fill.setAlpha(alpha);
                edge.setAlpha(Math.round(alpha * 0.82f));
                canvas.drawCircle(x, y, radius, fill);
                canvas.drawCircle(x - radius * 0.08f, y - radius * 0.08f,
                        Math.max(0f, radius - dp(0.5f)), edge);
            }
        }

        private float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        private final class TitleBubble {
            final float startX;
            final float radius;
            final float delay;
            final float startRise;
            final float phase;
            final float sway;

            TitleBubble(float startX, float radius, float delay, float startRise,
                        float phase, float sway) {
                this.startX = startX;
                this.radius = radius;
                this.delay = delay;
                this.startRise = startRise;
                this.phase = phase;
                this.sway = sway;
            }
        }
    }

    /**
     * 设置页卡片容器：灰色背景上承载多个白色圆角行。
     * 每添加一个子 view 会自动刷新该行的圆角与间距。
     */
    private static final class SettingsCard extends LinearLayout {
        private final BubbleSettingsActivity activity;

        SettingsCard(BubbleSettingsActivity activity) {
            super(activity);
            this.activity = activity;
            setOrientation(VERTICAL);
            setBackground(activity.rounded(activity.palette.surfaceContainer, 28));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = activity.dp(12);
            setLayoutParams(params);
        }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            super.addView(child, index, params);
            refreshStyles();
        }

        private void refreshStyles() {
            int rows = getChildCount();
            for (int i = 0; i < rows; i++) {
                View row = getChildAt(i);
                boolean first = i == 0;
                boolean last = i == rows - 1;
                row.setBackground(activity.rowBackground(first, last));
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
                if (lp == null) {
                    lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                lp.bottomMargin = last ? 0 : activity.dp(2);
                row.setLayoutParams(lp);
            }
        }
    }
}
