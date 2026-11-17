# 被改动的 AOSP 文件

这里的 9 个文件**不是**新增代码，而是 `packages/apps/Launcher3` 里被 NavRS 改过的原始文件，
原样取自验证通过的树（LineageOS 22.2 / Android 15）。用途：

1. 和你的目标树 **diff**，就能看到每一处 hook 的确切位置；
2. 如果目标树版本相近，可以直接覆盖，再按 `docs/PORTING.md` 第 2 节补 `Android.bp` 接线。

| 文件 | 对应 AOSP 路径 | 改了什么 |
| --- | --- | --- |
| `NavbarButtonsViewController.java` | `quickstep/src/com/android/launcher3/taskbar/` | 调用 `NavRsIntegration.install()` 装上 Rust 三键栏 |
| `OverviewCommandHelper.kt` | `quickstep/src/com/android/quickstep/` | 三键/键盘触发时直接启动 `NavRsRecentsActivity`，不再走 Shell overview |
| `TouchInteractionService.java` | `quickstep/src/com/android/quickstep/` | 后台 Activity 在前台时，底部上滑不再创建输入消费者 |
| `QuickstepLauncher.java` | `quickstep/src/com/android/launcher3/uioverrides/` | 手势 deck 的 wall/scrim、60fps tick、桌面截图缓存与预模糊、回桌面清 app 取色 |
| `AbsSwipeUpHandler.java` | `quickstep/src/com/android/quickstep/` | 每帧写 `dragProgress`；退出落点 `LAST_TASK`/`HOME` 分叉 |
| `NoButtonNavbarToOverviewTouchController.java` | `quickstep/src/com/android/launcher3/uioverrides/touchcontrollers/` | 停顿阈值 1→0.5 slop；桌面手势走 `launchFromGesture` |
| `MotionPauseDetector.java` | `quickstep/src/com/android/quickstep/util/` | 新增 `setForcePauseTimeoutMs()`（300→180ms） |
| `quickstep-AndroidManifest.xml` | `quickstep/AndroidManifest.xml` | 声明 `NavRsRecentsActivity`（singleTask / 透明 / excludeFromRecents） |
| `quickstep-res-values-styles.xml` | `quickstep/res/values/styles.xml` | 新增 `NavRsRecentsTheme`（透明窗口、禁用预览、无 dim） |
| `Launcher3-Android.bp` | `packages/apps/Launcher3/Android.bp` | `Launcher3QuickStepLib` 的 `static_libs` 加 `"navrs"`；新增 `cc_prebuilt_library_shared "libnav_rs"`（arm64/arm 两个预编译 .so） |

## 怎么用

```bash
# 目标树里逐个 diff
diff -u /path/to/Launcher3/quickstep/src/com/android/launcher3/taskbar/NavbarButtonsViewController.java \
        java/framework/NavbarButtonsViewController.java | less
```

搜索关键字（快速定位 hook）：

```bash
grep -rn "NavRs\|navrs" java/framework/
```

## 注意

- 这些文件里的其它内容与 AOSP 一致，**不要**把它们当成完整替换件直接覆盖到差异较大的树上，
  先 diff 再手工合并。
- `NavRsIntegration` / `NavRsActivity` / `NavRsBridge` / `TaskbarBridge` 在
  `java/quickstep/com/android/launcher3/navrs/` 下，属于新增文件，直接拷进目标树即可。
