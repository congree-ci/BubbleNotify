package io.github.freeform.bubble;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.TextView;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用选择器：支持搜索、拼音首字母分组、右侧字母索引、已选置顶。
 */
@SuppressLint("SetTextI18n")
public final class AppPickerActivity extends Activity {
    static final String EXTRA_WHITELIST = "whitelist";
    static final String EXTRA_SELECTED = "selected";
    static final String EXTRA_STATUS_SCROLL = "status_scroll";
    private MaterialPalette palette;
    private List<AppItem> allApps = new ArrayList<>();
    private List<AppItem> filteredApps = new ArrayList<>();
    private Set<String> selectedPackages = new HashSet<>();
    private AppAdapter adapter;
    private ListView listView;
    private TextView letterToast;
    private TextView subtitleRef;
    private String[] sections;
    private boolean whitelist;
    private boolean statusScroll;
    private boolean showUserApps = true;
    private boolean showSystemApps = true;
    private String filterQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = MaterialPalette.resolve(this);
        whitelist = getIntent().getBooleanExtra(EXTRA_WHITELIST, true);
        statusScroll = getIntent().getBooleanExtra(EXTRA_STATUS_SCROLL, false);
        configureWindow();
        ArrayList<String> selected = getIntent().getStringArrayListExtra(EXTRA_SELECTED);
        if (selected != null) selectedPackages.addAll(selected);
        setContentView(buildPage());
        loadApplications();
        overridePendingTransition(R.anim.popup_enter, 0);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.popup_exit);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setWindowAnimations(android.R.style.Animation_Dialog);
    }

    private View buildPage() {
        FrameLayout root = new FrameLayout(this);
        // 弹窗外部只保留遮罩，不处理底层截图或模糊。
        root.setBackground(materialBackdrop());
        root.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(14), dp(20), dp(14));
        panel.setBackground(rounded(palette.surfaceContainer, 28));
        // 面板内部点击不能关闭弹窗（仅外侧空白关闭）
        panel.setOnClickListener(v -> {});
        DisplayMetrics dm = getResources().getDisplayMetrics();
        root.addView(panel, new FrameLayout.LayoutParams(
                (int) (dm.widthPixels * 0.92f),
                (int) (dm.heightPixels * 0.86f),
                Gravity.CENTER));

        // 标题
        TextView title = new TextView(this);
        title.setText(statusScroll ? "状态栏通知白名单"
                : (whitelist ? "通知白名单" : "应用黑名单"));
        title.setTextSize(22);
        title.setTextColor(palette.onSurface);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(statusScroll ? "选中的应用在状态栏滚动显示通知，并自动抑制系统顶部横幅"
                : (whitelist ? "选中的应用才会显示气泡通知"
                : "选中的应用始终不显示气泡，且其在前台时其它应用也不弹气泡"));
        subtitle.setTextSize(14);
        subtitle.setTextColor(palette.onSurfaceVariant);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        subtitleRef = subtitle;
        updateSubtitle();
        panel.addView(subtitle);

        // 搜索框
        EditText searchBox = new EditText(this);
        searchBox.setHint("搜索应用名称或拼音");
        searchBox.setTextSize(14);
        searchBox.setTextColor(palette.onSurface);
        searchBox.setHintTextColor(palette.onSurfaceVariant);
        searchBox.setBackground(rounded(palette.surface, 20));
        searchBox.setPadding(dp(16), dp(12), dp(16), dp(12));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });
        panel.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, dp(8), 0, 0);
        CheckBox userApps = new CheckBox(this);
        userApps.setText("用户应用");
        userApps.setTextSize(14);
        userApps.setTextColor(palette.onSurface);
        userApps.setChecked(showUserApps);
        userApps.setOnCheckedChangeListener((button, checked) -> {
            showUserApps = checked;
            filter(filterQuery);
        });
        filterRow.addView(userApps, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        CheckBox systemApps = new CheckBox(this);
        systemApps.setText("系统应用");
        systemApps.setTextSize(14);
        systemApps.setTextColor(palette.onSurface);
        systemApps.setChecked(showSystemApps);
        systemApps.setOnCheckedChangeListener((button, checked) -> {
            showSystemApps = checked;
            filter(filterQuery);
        });
        filterRow.addView(systemApps, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(filterRow);

        // 工具栏：清空 / 全选
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(0, dp(12), 0, dp(12));
        TextView clearButton = toolbarButton("清空");
        clearButton.setOnClickListener(v -> {
            selectedPackages.clear();
            adapter.notifyDataSetChanged();
        });
        TextView selectAllButton = toolbarButton("全选");
        selectAllButton.setOnClickListener(v -> {
            for (AppItem item : filteredApps) {
                selectedPackages.add(item.packageName);
            }
            adapter.notifyDataSetChanged();
        });
        toolbar.addView(clearButton);
        toolbar.addView(selectAllButton);
        panel.addView(toolbar);

        // 列表区域
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.HORIZONTAL);
        listContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setVerticalScrollBarEnabled(false);
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        listContainer.addView(listView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 右侧字母索引
        SideIndexBar sideIndex = new SideIndexBar(this);
        listContainer.addView(sideIndex, new LinearLayout.LayoutParams(
                dp(28), ViewGroup.LayoutParams.MATCH_PARENT));

        panel.addView(listContainer);

        // 底部保存按钮
        TextView saveButton = new TextView(this);
        saveButton.setText("保存");
        saveButton.setTextSize(16);
        saveButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        saveButton.setTextColor(palette.onPrimary);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setBackground(rounded(palette.primary, 24));
        saveButton.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(16);
        saveButton.setLayoutParams(saveParams);
        saveButton.setOnClickListener(v -> saveAndFinish());
        panel.addView(saveButton);

        // 字母提示浮层：悬浮于面板中央，monet 动态取色
        letterToast = new TextView(this);
        letterToast.setTextSize(40);
        letterToast.setTextColor(palette.onSurface);
        letterToast.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letterToast.setGravity(Gravity.CENTER);
        letterToast.setBackground(rounded(palette.surfaceContainerHigh, 20));
        letterToast.setPadding(dp(24), dp(16), dp(24), dp(16));
        letterToast.setVisibility(View.GONE);
        root.addView(letterToast, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        return root;
    }

    private TextView toolbarButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(palette.primary);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(rippleOver(palette.surfaceContainer));
        return button;
    }

    private void updateSubtitle() {
        if (subtitleRef == null) return;
        String base = statusScroll
                ? "选中的应用才会显示通知栏滚动或流体云通知"
                : (whitelist ? "选中的应用才会显示气泡通知"
                : "选中的应用始终不显示气泡，且其在前台时其它应用也不弹气泡");
        subtitleRef.setText("共 " + allApps.size() + " 个应用 · " + base);
    }

    /** 弹窗外部遮罩：不读取底层截图，避免产生外部模糊。 */
    private android.graphics.drawable.Drawable materialBackdrop() {
        return new android.graphics.drawable.ColorDrawable(0x35000000);
    }

    private void loadApplications() {
        PackageManager packageManager = getPackageManager();
        Set<String> seen = new HashSet<>();
        for (ApplicationInfo info : packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0))) {
            if (!info.enabled || info.packageName.equals(getPackageName())) continue;
            if (!seen.add(info.packageName)) continue;
            CharSequence label = info.loadLabel(packageManager);
            String name = label == null ? info.packageName : label.toString();
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            allApps.add(new AppItem(info.packageName, name, system));
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(allApps, (a, b) -> {
            boolean aSelected = selectedPackages.contains(a.packageName);
            boolean bSelected = selectedPackages.contains(b.packageName);
            if (aSelected != bSelected) return aSelected ? -1 : 1;
            boolean aHash = "#".equals(a.letter);
            boolean bHash = "#".equals(b.letter);
            if (aHash != bHash) return aHash ? 1 : -1;
            int letter = a.letter.compareTo(b.letter);
            if (letter != 0) return letter;
            return collator.compare(a.label, b.label);
        });
        filter("");
        updateSubtitle();
    }

    private void filter(String query) {
        filteredApps.clear();
        filterQuery = query == null ? "" : query;
        String lower = filterQuery.toLowerCase(Locale.getDefault());
        for (AppItem item : allApps) {
            boolean typeMatches = item.system ? showSystemApps : showUserApps;
            if (typeMatches && (lower.isEmpty()
                    || item.label.toLowerCase(Locale.getDefault()).contains(lower)
                    || item.pinyin.toLowerCase(Locale.getDefault()).contains(lower)
                    || item.letter.toLowerCase(Locale.getDefault()).contains(lower))) {
                filteredApps.add(item);
            }
        }
        buildSections();
        adapter.notifyDataSetChanged();
    }

    private void buildSections() {
        List<String> sectionList = new ArrayList<>();
        for (AppItem item : filteredApps) {
            String letter = item.letter;
            if (selectedPackages.contains(item.packageName)) {
                letter = "已选";
            }
            if (sectionList.isEmpty() || !sectionList.get(sectionList.size() - 1).equals(letter)) {
                sectionList.add(letter);
            }
        }
        sections = sectionList.toArray(new String[0]);
    }

    private void saveAndFinish() {
        Intent result = new Intent();
        result.putStringArrayListExtra(EXTRA_SELECTED, new ArrayList<>(selectedPackages));
        setResult(RESULT_OK, result);
        finish();
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    /** 分组行背景：分组首尾大圆角、中间小圆角，白色底（与设置页条目一致）。 */
    private GradientDrawable groupRowBackground(boolean first, boolean last) {
        int big = dp(20);
        int small = dp(6);
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

    private android.graphics.drawable.Drawable rippleOver(int maskColor) {
        android.content.res.TypedArray typed = getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        android.graphics.drawable.Drawable ripple = typed.getDrawable(0);
        typed.recycle();
        if (ripple != null) ripple.setTint(maskColor);
        return ripple;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter implements SectionIndexer {
        @Override public int getCount() { return filteredApps.size(); }
        @Override public Object getItem(int position) { return filteredApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = createItemView(parent);
                holder = new ViewHolder();
                holder.letter = convertView.findViewWithTag("letter");
                holder.icon = convertView.findViewWithTag("icon");
                holder.label = convertView.findViewWithTag("label");
                holder.packageName = convertView.findViewWithTag("package");
                holder.checkBox = convertView.findViewWithTag("check");
                holder.row = convertView.findViewWithTag("row");
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppItem item = filteredApps.get(position);
            boolean selected = selectedPackages.contains(item.packageName);

            // 分组标题
            String section = selected ? "已选" : item.letter;
            boolean showHeader = position == 0;
            if (!showHeader) {
                AppItem prev = filteredApps.get(position - 1);
                String prevSection = selectedPackages.contains(prev.packageName) ? "已选" : prev.letter;
                showHeader = !section.equals(prevSection);
            }
            holder.letter.setVisibility(showHeader ? View.VISIBLE : View.GONE);
            holder.letter.setText(section);

            // 分组圆角与间距：分组内小圆角，分组首尾大圆角，分组之间加大间距
            boolean firstOfGroup = position == 0;
            boolean lastOfGroup = position == filteredApps.size() - 1;
            if (position > 0) {
                AppItem prev = filteredApps.get(position - 1);
                String prevSection = selectedPackages.contains(prev.packageName) ? "已选" : prev.letter;
                firstOfGroup = !section.equals(prevSection);
            }
            if (position < filteredApps.size() - 1) {
                AppItem next = filteredApps.get(position + 1);
                String nextSection = selectedPackages.contains(next.packageName) ? "已选" : next.letter;
                lastOfGroup = !section.equals(nextSection);
            }
            holder.row.setBackground(groupRowBackground(firstOfGroup, lastOfGroup));
            // 用 padding 控制分组间距（ListView 会强制覆写 item 的 LayoutParams，
            // 用 margin 会抛类型转换异常导致崩溃）
            convertView.setPadding(0, 0, 0, lastOfGroup ? dp(16) : dp(2));

            holder.label.setText(item.label);
            holder.packageName.setText(item.packageName);
            holder.checkBox.setChecked(selected);

            try {
                holder.icon.setImageDrawable(getPackageManager().getApplicationIcon(item.packageName));
            } catch (Throwable ignored) {
                holder.icon.setImageDrawable(null);
            }

            convertView.setOnClickListener(v -> {
                if (selectedPackages.contains(item.packageName)) {
                    selectedPackages.remove(item.packageName);
                } else {
                    selectedPackages.add(item.packageName);
                }
                notifyDataSetChanged();
            });

            return convertView;
        }

        private View createItemView(ViewGroup parent) {
            LinearLayout item = new LinearLayout(AppPickerActivity.this);
            item.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemParams.bottomMargin = dp(4);
            item.setLayoutParams(itemParams);

            TextView letter = new TextView(AppPickerActivity.this);
            letter.setTag("letter");
            letter.setTextSize(12);
            letter.setTextColor(palette.primary);
            letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            letter.setPadding(0, dp(8), 0, dp(4));
            item.addView(letter, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setTag("row");
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(AppPickerActivity.this);
            icon.setTag("icon");
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconParams.rightMargin = dp(12);
            row.addView(icon, iconParams);

            LinearLayout textColumn = new LinearLayout(AppPickerActivity.this);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView label = new TextView(AppPickerActivity.this);
            label.setTag("label");
            label.setTextSize(15);
            label.setTextColor(palette.onSurface);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textColumn.addView(label);

            TextView packageName = new TextView(AppPickerActivity.this);
            packageName.setTag("package");
            packageName.setTextSize(12);
            packageName.setTextColor(palette.onSurfaceVariant);
            textColumn.addView(packageName);

            row.addView(textColumn);

            CheckBox checkBox = new CheckBox(AppPickerActivity.this);
            checkBox.setTag("check");
            checkBox.setClickable(false);
            checkBox.setFocusable(false);
            row.addView(checkBox);

            return item;
        }

        @Override public Object[] getSections() { return sections; }

        @Override
        public int getPositionForSection(int sectionIndex) {
            if (sections == null || sectionIndex < 0 || sectionIndex >= sections.length) return 0;
            String target = sections[sectionIndex];
            for (int i = 0; i < filteredApps.size(); i++) {
                AppItem item = filteredApps.get(i);
                String section = selectedPackages.contains(item.packageName) ? "已选" : item.letter;
                if (section.equals(target)) return i;
            }
            return 0;
        }

        @Override
        public int getSectionForPosition(int position) {
            if (sections == null || position < 0 || position >= filteredApps.size()) return 0;
            AppItem item = filteredApps.get(position);
            String section = selectedPackages.contains(item.packageName) ? "已选" : item.letter;
            for (int i = 0; i < sections.length; i++) {
                if (sections[i].equals(section)) return i;
            }
            return 0;
        }
    }

    private static final class ViewHolder {
        TextView letter;
        ImageView icon;
        TextView label;
        TextView packageName;
        CheckBox checkBox;
        View row;
    }

    private final class AppItem {
        final String packageName;
        final String label;
        final String pinyin;
        final String letter;
        final boolean system;

        AppItem(String packageName, String label, boolean system) {
            this.packageName = packageName;
            this.label = label;
            this.system = system;
            this.pinyin = toPinyin(label);
            String first = pinyin.isEmpty() ? "#" : pinyin.substring(0, 1).toUpperCase(Locale.getDefault());
            if (!first.matches("[A-Z]")) first = "#";
            this.letter = first;
        }

        private String toPinyin(String text) {
            HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
            format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
            format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch >= 0x4E00 && ch <= 0x9FFF) {
                    try {
                        String[] array = PinyinHelper.toHanyuPinyinStringArray(ch, format);
                        builder.append(array == null || array.length == 0 ? "" : array[0]);
                    } catch (BadHanyuPinyinOutputFormatCombination ignored) {
                    }
                } else {
                    builder.append(Character.toLowerCase(ch));
                }
            }
            return builder.toString();
        }
    }

    private final class SideIndexBar extends View {
        private final String[] letters;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int cellHeight;
        private int lastIndex = -1;

        SideIndexBar(Context context) {
            super(context);
            List<String> list = new ArrayList<>();
            list.add("已选");
            for (char c = 'A'; c <= 'Z'; c++) list.add(String.valueOf(c));
            list.add("#");
            letters = list.toArray(new String[0]);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(palette.primary);
            setBackground(rounded(palette.surfaceContainer, 16));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (cellHeight == 0) return;
            float x = getWidth() / 2f;
            float textSize = Math.min(dp(11), cellHeight * 0.65f);
            paint.setTextSize(textSize);
            for (int i = 0; i < letters.length; i++) {
                float y = cellHeight * (i + 0.5f) + (paint.descent() - paint.ascent()) / 2f - paint.descent();
                paint.setColor(lastIndex == i ? palette.primary : palette.onSurfaceVariant);
                canvas.drawText(letters[i], x, y, paint);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            cellHeight = h / Math.max(1, letters.length);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int index = (int) (event.getY() / cellHeight);
            index = Math.max(0, Math.min(letters.length - 1, index));
            if (index != lastIndex || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastIndex = index;
                scrollToLetter(letters[index]);
                showLetterToast(letters[index]);
                invalidate();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                hideLetterToast();
            }
            return true;
        }

        private void scrollToLetter(String letter) {
            for (int i = 0; i < filteredApps.size(); i++) {
                AppItem item = filteredApps.get(i);
                String section = selectedPackages.contains(item.packageName) ? "已选" : item.letter;
                if (section.equals(letter)) {
                    listView.setSelection(i);
                    return;
                }
            }
        }

        private void showLetterToast(String letter) {
            letterToast.setText(letter);
            letterToast.setVisibility(View.VISIBLE);
        }

        private void hideLetterToast() {
            letterToast.setVisibility(View.GONE);
            lastIndex = -1;
            invalidate();
        }
    }
}
