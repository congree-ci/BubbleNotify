# Bubble Notifications · 气泡通知

[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-ColorOS%2016%20%2F%2017-1f6feb.svg)](#兼容性)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20101%2B-6f42c1.svg)](#兼容性)

把通知变成一颗可以拖动、可以贴在屏幕边上的圆形气泡。点一下，直接用系统小窗打开对应应用；不想处理就往下滑掉。

这是一个面向 **ColorOS 16 / 17（Android 15 / 16）** 的 LSPosed 模块，通过 Hook `SystemUI` 的通知管线与电源/小窗相关方法实现，不修改系统分区，也不需要刷 Magisk 模块。

> 当前版本：**1.10.88**（versionCode 144）　模块 id：`bubble-notify`　包名：`io.github.freeform.bubble`

## 功能特性

### 气泡
- **通知气泡**：符合规则的通知显示为一颗 Material 玻璃质感的圆形气泡，气泡里是通知小图标，右下角是应用原始图标角标。
- **堆叠**：同一应用的多条通知、不同应用的多颗气泡可以堆叠/并排显示，不互相遮挡。
- **点击进小窗**：点击气泡用 ColorOS freeform 小窗打开通知对应的应用（或直接打开通知的 PendingIntent）；对开了「应用分身」的应用会直接进入对应实例，不再弹「选择打开的应用」。
- **拖动与贴边**：拖到屏幕边缘松手会自动吸附，半隐藏在屏幕外；横屏、竖屏分别记住位置，可以一键恢复到默认的右下角。
- **关闭**：向下滑动气泡关闭；可同时尝试清除状态栏里的对应通知。
- **超时**：到时间自动关闭、降低透明度保留，或永不过期。

### 通知内容浮层
- 气泡旁边弹出应用名、标题和正文，位置自动选择不遮挡气泡的一侧（也可固定为上/下/屏幕内侧）。
- 宽度自动按文字和可用空间调整，也可以手动指定 dp。
- 浮层不透明度、停留时长可调，文字多时自动延长。
- 可以只在气泡贴边半隐藏时才显示内容，也可以在气泡接管通知时隐藏系统顶部横幅。

### 外观
- 气泡大小 60%–140%，通知图标比例、应用角标大小各自可调；角标中心始终落在气泡圆形边上。
- 可选的持续弹跳动画。

### 应用规则
- 通知白名单 / 黑名单，决定哪些应用的通知会变成气泡。
- 状态栏滚动通知（ticker）白名单与显示遍数。

### 状态栏滚动通知
- 接管系统 ticker，用系统处理过的单色通知图标绘制，避免部分应用的小图标被画成一块色块。

### 即时建议
根据当前场景主动给出一个可执行的气泡，全部可在设置里单独开关：
- 屏幕半暗后点击屏幕 → 建议临时保持常亮
- 离开相机后手电筒仍开启 → 建议关闭
- 连接耳机且无音频播放 → 建议打开音乐应用
- 首次安装带桌面入口的应用 → 建议打开
- 剪贴板内容识别（应用链接、购物链接、快递单号、电话、地址）→ 建议直接跳转，**默认关闭，全部在本机完成**
- 临时通知不贴边，停在设定位置

### 其他
- 可选把系统的流体云通知也纳入气泡。
- 本地运行日志导出，方便反馈问题。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 系统 | ColorOS 16 / 17（Android 15 / 16） |
| Root | 需要，已安装 **LSPosed**（Zygisk 版） |
| LSPosed API | ≥ 101（`module.prop` 中 `minApiVersion=101`） |
| 作用域 | `com.android.systemui`、`com.oplus.systemui`、`system` |
| minSdk | 35 |

非 ColorOS 系统（MIUI/HyperOS、One UI、AOSP 等）没有实现对应的小窗与系统内部接口，**不保证可用**。

## 安装

1. 在已 root 的手机上装好 LSPosed（Zygisk），确认 LSPosed 管理器可以正常工作。
2. 安装 [Releases](../../releases) 里的 APK，或按下面的「构建」自行编译。
3. 打开 LSPosed 管理器 → 模块 → 启用 **Bubble Notifications** → 在作用域里勾选「系统界面」（`com.android.systemui`）和「系统」（`system`）。
4. 重启系统界面（LSPosed 内可直接重启）或重启手机。
5. 打开「气泡通知」应用，先给需要的应用配好白名单，再按需调整外观与位置。

升级时直接覆盖安装 APK，LSPosed 作用域会保留；升级后建议重启一次系统界面。

## 使用说明

- **第一次使用**：建议只添加几个常用应用到白名单，确认效果后再扩大范围。
- **拖动气泡**：按住气泡拖到任意位置，松手自动贴边；在设置 → 显示位置里可以查看和重置百分比坐标。
- **下滑关闭**：默认只关气泡；打开「下滑气泡时清除状态栏通知」后会连同系统通知一起清除。
- **内容浮层**：如果不想让气泡旁边弹文字，关闭「显示通知内容」即可。
- **即时建议**：每一项独立开关，不确定是什么就先保持全部关闭。
- **反馈问题**：设置页最下方可以导出运行日志，提交 Issue 时附上（日志里可能包含应用包名，请自行确认后再贴出）。

## 构建

### 方式一：Gradle（推荐在电脑上）

需要 JDK 17、Android SDK Platform 37 与对应 Build-Tools：

```bash
./gradlew assembleDebug
# 产物：build/outputs/apk/debug/
```

release 构建默认使用本机 debug 签名，方便覆盖安装测试；正式分发请替换成你自己的签名配置：

```bash
./gradlew assembleRelease
```

### 方式二：build-apk.sh（在手机上直接出包）

`build-apk.sh` 是一条不需要 Gradle / Android SDK 的构建路径，用 `aapt`、`d8`、`apksigner` 和 `android.jar` 手工打包，适合在手机上迭代（本仓库的版本就是这样构建的）。它依赖维护者手机上的工具链路径（`$DSH_HOME/ff-build`、OpenJDK 17、`android.jar`），换一台机器需要按脚本开头的说明调整路径。

```bash
bash build-apk.sh
# 产物：dist/BubbleNotify-<version>-v<code>-debug-signed.apk
```

首次运行会在 `keystore/` 下自动生成一个密码为 `android` 的 debug keystore；**这个目录已在 `.gitignore` 中，不要提交**。

## 项目结构

```
.
├── src/main/java/io/github/freeform/bubble/   # 21 个 Java 源文件（Hook、气泡视图、设置页、配置等）
├── src/main/res/                              # 图标、主题、动画资源
├── src/main/resources/META-INF/xposed/        # 模块元数据、作用域、入口列表
├── libs/                                      # 离线构建用的依赖 jar（libxposed API、pinyin4j）
├── build.gradle / settings.gradle             # Gradle 构建配置
├── build-apk.sh                               # 手机端免 Gradle 构建脚本
├── BUBBLE_SIZE.md                             # 开发者笔记：气泡缩放与角标几何
├── MULTI_APP_FIX.md                           # 开发者笔记：应用分身点击修复
└── TICKER_ICON_FIX.md                         # 开发者笔记：状态栏滚动图标修复
```

## 已知限制

- 只针对 ColorOS 16 / 17 适配；系统大版本更新后，依赖的厂商内部类和方法可能改名或消失，模块会失效甚至导致系统界面异常。
- 与其它修改 SystemUI 通知/状态栏的模块（通知图标增强、状态栏歌词等）可能冲突，出问题时先只保留本模块再排查。
- 即时建议依赖系统状态与剪贴板事件，部分场景在省电模式或后台限制下可能不触发。
- 模块会接管部分系统行为，**请自行评估风险**；因使用本模块导致的系统异常、数据丢失等由使用者承担。

## 隐私

- APK 未申请 `INTERNET` 权限，模块不联网、不上报任何数据。
- 剪贴板识别只在本机做正则/语义判断，命中后才会显示建议，不会保存剪贴板内容。
- 运行日志保存在应用私有目录，只有你手动导出时才会离开设备。

## 许可证

本项目以 [GNU General Public License v3.0](LICENSE) 发布，版权归 **Congree** 所有。

第三方依赖：
- [libxposed API](https://github.com/LSPosed/libxposed-api) — Apache-2.0，编译期依赖
- [pinyin4j](https://github.com/belerweb/pinyin4j) — BSD，用于应用名拼音排序

---

## English

**Bubble Notifications** is an LSPosed module for ColorOS 16/17 that turns notifications into draggable, edge-docked bubbles. Tap a bubble to open the notification in a ColorOS freeform window, swipe down to dismiss it. It also provides an inline content preview, notification whitelist/blacklist, ticker (status-bar scrolling notification) control, and context-aware suggestions.

Requires: ColorOS 16/17 (Android 15/16), root + LSPosed (API 101+), scope `com.android.systemui` + `system`. No internet permission; clipboard recognition is on-device and off by default.

Licensed under GPL-3.0. Built and tested on-device; other ROMs are unsupported.
