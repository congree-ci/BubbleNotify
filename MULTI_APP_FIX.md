# 应用分身（ColorOS multi-app）气泡点击弹出“选择应用”修复

## 现象

目标 App 创建应用分身后，点该 App 通知弹出的气泡，系统弹出
`com.oplus.multiapp/.chooser.MultiAppResolverActivity`（“选择打开的应用”，
列出原应用 / 分身实例），而不是直接打开对应实例。

## 根因

`BubbleNotificationHook.openNotificationInFreeform()` 的兜底路径把通知“按包名”
在 SystemUI 的 user 0 上下文里启动：

```java
Intent launch = systemUiContext.getPackageManager().getLaunchIntentForPackage(pkg);
systemUiContext.startActivity(launch, freeformOptions);
```

ColorOS（oplus-services.jar）在 `OplusMultiAppManagerService` 里对“目标包存在多实例、
调用方又不是白名单包”的启动会走 `shouldShowResolverActivity()` /
`replaceIntentForMultiApp()`，把启动替换成 `MultiAppResolverActivity`。
`SPECIAL_CALLING_PACKAGE` 只有 `android / com.android.shell / com.android.contacts /
com.oplus.safecenter`，`com.android.systemui` 不在其中，所以气泡点击必然可能触发选择框。

两个维度都会中招：

1. 原应用（user 0）通知 + 该包有分身 → 启动歧义 → 选择框。
2. 分身（user 999）通知 → 按包名在 user 0 启动，实例不对 → 同样歧义。

## 修复（`multiapp-user-fix.patch`）

1. `QueuedNotification` 记录通知所属用户 `userId = StatusBarNotification.getUserId()`。
2. 分身用户的通知：不再按包名启动，改为重发通知自带的 `contentIntent`。
   PendingIntent 绑定创建者用户，系统判定为 App 自启动（`isAppSelfCalling`），
   直接打开分身实例；freeform ticket 仍照常下发，小窗行为不变。
3. 原用户/兜底路径：`launchPackageInFreeform(pkg, options, userId)` 在通知所属
   用户上下文中启动（反射 `Context.createContextAsUser`），并加两个 ColorOS 提示：
   - `oplus.intent.extra.cached_userId = userId`（`getCachedUserId()` 读取）
   - `android.content.IIntentExt.OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER`（反射
     `Intent.getIntentExt().addOplusFlags(...)`），让 `shouldShowResolverActivity()`
     不再弹选择框。
   两个提示都是 OPPO 内部字段，全部反射 + try/catch，非 ColorOS 上无副作用。

## 验证（真机 ColorOS 17 / Android 16）

构造测试 APK `com.dsh.clonecheck`（显示 `user=<uid/100000>` 并带 contentIntent 通知），
用系统“应用分身”克隆，然后：

| 场景 | 旧版 1.10.86 | 修复版 1.10.87 |
| --- | --- | --- |
| 原应用通知（user 0） | 弹“选择打开的应用”（分身验证 / 分身验证 1） | 直接以 user 0 小窗打开，无选择框 |
| 分身通知（user 999） | 依赖调用关系缓存，可能打开错误实例 | 直接以 user 999 小窗打开（日志：`cross-user notification opened through content intent ... user=999`） |

验证后测试分身/测试 APK 已删除，白名单已恢复。

## 注意

- 本工作区源码为 1.10.38，而手机原先安装的是 1.10.86（更新构建，含原生气泡、
  倒计时等本源码没有的功能）。本次按用户确认覆盖安装为 1.10.87(143)，
  这些新功能不在本次构建里；补丁可直接合入新的源码树。
- `build-apk.sh` 已兼容当前 `ff-build/toolchain/usr` 目录布局。
