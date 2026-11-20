# 状态栏滚动通知图标：改用系统的单色图标

## 现象

状态栏滚动通知（ticker）的图标有时显示成一块单色方块/实心色块，而不是应用的通知小图标形状。

## 根因

`resolveTickerIcon()` 原来按这个顺序取图标：

1. 旧版图标增强模块（1.x）的替换缓存（代码里叫 `resolveGlyphStatusBarIcon()`，
   现改名 `resolveLegacyIconEnhancementCache()`）；
2. `resolveStatusBarIcon()`：直接 `notification.getSmallIcon().loadDrawable()`。

现在装的「ColorOS 通知图标增强」是 2.0.1：它没有 1.x 的缓存类，而是直接 hook
`StatusBarIconView.setIcon` 改写状态栏图标视图。所以第 1 步永远拿不到东西，
ticker 实际一直在用第 2 步的**原始 smallIcon**。

第 2 步拿到的是**应用原始 smallIcon**，没有经过 SystemUI 的图标处理。部分应用把
adaptive icon / 整块背景图当 smallIcon，ticker 又在 `StatusScrollOverlay.copyClockStyle()`
里用 `PorterDuff.Mode.SRC_IN` 统一染色，于是整块背景被染成一个单色方块。
旧版 1.x 的替换缓存也不是每个通知 key、每个时刻都有，所以问题“有时”出现。

## 修复（`ticker-icon-fix.patch`）

1. `resolveTickerIcon()` 优先调用新的 `resolveStatusBarIconViewIcon()`：
   在状态栏视图树里找到当前通知 key 对应的 `StatusBarIconView`，复制它的
   `ImageView.getDrawable()`。这就是 SystemUI 此刻实际渲染的图标——已经过
   ColorOS 通知图标增强 / 系统自身的图标替换与着色，等于“系统的单色图标”。
   - 通过反射 `getNotification()` 拿 `StatusBarNotification`，用通知 key 比对，
     避免 slot 复用导致拿错图标；遍历深度限制 12 层。
2. 新增 `monochromeTickerIcon()`：对 `AdaptiveIconDrawable` 优先取
   `getMonochrome()`，没有时退到 `getForeground()`，只画前景层，不再把
   adaptive icon 的实心背景层画出来，从根源上消除“单色方块”。
3. 兜底顺序不变：状态栏实际图标 → 旧版增强模块缓存（兼容 1.x）→ 原始 smallIcon（再做一次
   adaptive 归一化）。

## 验证

- 编译通过，模块在 SystemUI 内正常加载（无异常）。
- 说明：测试用的 adaptive-icon 测试应用在触发 ticker 时偶发被系统通知监听
  延迟/合并，未能稳定复现到“方块”截图；逻辑上第 2 步的 adaptive 背景层正是
  方块来源，已按上述方式规避。若你仍看到某个具体应用的方块，告诉我应用名，
  我按该应用的 `smallIcon` 类型再针对性处理。

## 注意

- 本改动已包含在当前安装的 1.10.87(143) 构建里；`dist/BubbleNotify-1.10.87-v143-debug-signed.apk`
  与之一致。
- `multiapp-user-fix.patch` 是应用分身问题的补丁，本文件是 ticker 图标补丁，可分别合入新源码。
