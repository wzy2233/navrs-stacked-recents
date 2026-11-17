# 移植指南

> 目标：把 NavRS 搬到另一台机器 / 另一份 AOSP 或 LineageOS 树里。
> 这套东西**不是 APK**，必须跟 ROM 一起编译；下面按「落位 → 接线 → 编译 → 部署 → 验证」走一遍。

## 0. 前置条件

| 项 | 要求 |
| --- | --- |
| 源码树 | AOSP 15 / LineageOS 22.x 的 `packages/apps/Launcher3`（含 `quickstep`、`navrs` 挂载点） |
| 设备 | Android 15，支持三键导航（`navigation_mode=0`） |
| 工具链 | `m`（soong）+ NDK r27（`27.2.12479018` 验证过）+ Rust + `cargo-ndk` |
| 权限 | 能改 `system_ext`（或 `system`）分区，能 `adb root` |
| 参考实现 | 本仓库 `java/framework/` 里的 7 个文件（原样取自验证过的树） |

> 如果目标树的 Launcher3 版本差异较大（例如 Android 14），**先 diff `java/framework/` 与目标树**，
> 把 hook 逐个对齐；Rust 侧与 navrs 模块本身不含版本相关代码。

## 1. 代码落位

```
packages/apps/Launcher3/
├── navrs/                                  ← 复制 java/navrs/ 整个目录
│   ├── Android.bp
│   ├── AndroidManifest.xml
│   ├── libnav_rs-arm64.so                  ← 见第 4 节
│   ├── libnav_rs-arm.so
│   └── src/com/wzy/navrs/*.java
└── quickstep/src/com/android/launcher3/
    └── navrs/                              ← 复制 java/quickstep/... 5 个文件 + 2 个辅助
        ├── NavBarView.java
        ├── NavRsActivity.java
        ├── NavRsBridge.java
        ├── NavRsIntegration.java
        ├── NavRsOverlay.java   （占位/兼容）
        ├── NavRsView.java      （占位/兼容）
        └── TaskbarBridge.java
```

`navrs/Android.bp`（原样）：

```bp
android_library {
    name: "navrs",
    srcs: ["src/**/*.java"],
    libs: ["Launcher3QuickStepLib"],
    platform_apis: true,
    min_sdk_version: "current",
}
```

`navrs/AndroidManifest.xml` 只是一行 `package` 声明（模块本身没有组件）。

**后台 Activity 声明在 `quickstep/AndroidManifest.xml`**（`<application>` 里）：

```xml
<activity
    android:name="com.wzy.navrs.NavRsRecentsActivity"
    android:excludeFromRecents="true"
    android:exported="true"
    android:launchMode="singleTask"
    android:taskAffinity=""
    android:theme="@style/NavRsRecentsTheme"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation|uiMode" />
```

**主题在 `quickstep/res/values/styles.xml`**：

```xml
<style name="NavRsRecentsTheme" parent="@android:style/Theme.DeviceDefault.NoActionBar">
    <item name="android:windowDisablePreview">true</item>
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:backgroundDimEnabled">false</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
</style>
```

完整文件见 `java/framework/quickstep-AndroidManifest.xml` 与
`java/framework/quickstep-res-values-styles.xml`。

> `launchMode=singleTask` + `taskAffinity=""` 很关键：重复按多任务键要复用同一个实例
> （`onNewIntent` 里做「再按一次回到 app」），且不能并进 launcher 的 task。
> `windowIsTranslucent=true` 是「缩略图就绪前 app 仍可见」的前提。

## 2. 构建接线（`Launcher3/Android.bp`）

两处：

**① 把 `navrs` 编进 launcher 库**（`Launcher3QuickStepLib` 的 `static_libs` 里加一项）：

```bp
static_libs: [
    ...
    "navrs",
],
```

**② 把 `.so` 作为预编译库装进 `/system/lib64`**：

```bp
cc_prebuilt_library_shared {
    name: "libnav_rs",
    arch: {
        arm64: { srcs: ["navrs/libnav_rs-arm64.so"] },
        arm:   { srcs: ["navrs/libnav_rs-arm.so"] },
    },
    strip: { none: true },
    compile_multilib: "both",
}
```

> 这台机器上最终还额外把 `libnav_rs.so` 推到 `priv-app/Launcher3QuickStep/lib/arm64/`
> （见第 5 节），因为 `System.loadLibrary("nav_rs")` 在 priv-app 里优先查自己的 `lib/`。

## 3. 框架 hook（7 处）

| # | 文件 | 位置 / 方法 | 改什么 |
| --- | --- | --- | --- |
| 1 | `taskbar/NavbarButtonsViewController.java` | 创建按钮容器之后 | `NavRsIntegration.install(mNavButtonsView, mNavButtonContainer, mNavButtonController)` |
| 2 | `quickstep/OverviewCommandHelper.kt` | `executeWhenRecentsIsNotVisible()` | 直接启动 `NavRsRecentsActivity`（跳过 Shell overview）；`fromHome` 时**后台线程**截图 |
| 3 | `quickstep/TouchInteractionService.java` | `onInputEvent` 创建消费者之前 | `isInSwipeUpTouchRegion && NavRsActivity.isOpen()` → `InputConsumer.NO_OP` |
| 4 | `uioverrides/QuickstepLauncher.java` | 状态回调 | `startNavRsDragHide()/stopNavRsDragHide()`、wall/scrim、60fps tick、桌面截图缓存、`clearAppBarColor()` |
| 5 | `quickstep/AbsSwipeUpHandler.java` | `calculateEndTarget()` / absorb 分支 | 每帧 `setDragProgress()`；退出中返回 `LAST_TASK`（activity）或 `HOME`（deck） |
| 6 | `uioverrides/touchcontrollers/NoButtonNavbarToOverviewTouchController.java` | 阈值字段 | `mMotionPauseMinDisplacement` 1 → 0.5 slop；桌面手势 → `launchFromGesture(context, true)` |
| 7 | `quickstep/util/MotionPauseDetector.java` | 新增 | `setForcePauseTimeoutMs(long)`，默认 300 → 180 |

### 3.1 三键栏安装（#1）

```java
NavRsIntegration.install(mNavButtonsView, mNavButtonContainer, mNavButtonController);
```

`install()` 会：

1. 把 `TaskbarNavButtonController` 存到 `TaskbarBridge`（事件转发用）；
2. new 一个 `NavBarView(context, /*taskbarMode=*/true)` 加进 `mNavButtonsView`；
3. `mNavButtonContainer.setAlpha(0f)` + `INVISIBLE` —— **只改 alpha，不要 remove**，
   否则 IME 变化时 `LauncherAnimUtils` 会 NPE（踩过）。

### 3.2 启动后台（#2）

```kotlin
if ((command.type == TOGGLE || command.type == SHOW) && !allowQuickSwitch) {
    val fromHome = TopTaskTracker.INSTANCE.get(tis).getCachedTopTask(false).isHomeTask
    if (fromHome && !NavRsActivity.isOpen()) {
        Thread({ NavRsActivity.captureHomeShot() }, "navrs-home-shot").start()
    }
    val intent = Intent(tis, Class.forName("com.wzy.navrs.NavRsRecentsActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("com.wzy.navrs.FROM_HOME", fromHome)
    val options = ActivityOptions.makeCustomTaskAnimation(tis, 0, 0, Handler(Looper.getMainLooper()), null, null).toBundle()
    tis.startActivity(intent, options)
    return true
}
```

要点：

- `captureDisplay` 是**同步全屏回读**，放主线程会让按键延迟 30–120ms，必须扔后台线程。
- `0/0` 自定义转场 = 不播系统窗口动画。
- `!allowQuickSwitch`：平板/双栏（`isTablet || isTwoPanels`）走原生逻辑，本项目不覆盖。

### 3.3 后台内上滑（#3）

```java
} else if (isInSwipeUpTouchRegion && NavRsActivity.isOpen()) {
    reasonString.append("NavRS recents is showing, leaving the swipe alone");
    mUncheckedConsumer = InputConsumer.NO_OP;
}
```

不加这一条，Shell 会把**我们的后台 Activity** 当成前台 app 缩成卡片（整屏变小卡）。

### 3.4 Launcher 内的并列 deck（#4）

`QuickstepLauncher` 里维护：

- `mNavRsDragWall`（`ImageView`，模糊桌面）、`mNavRsDragScrim`（`#66000000`）、
  `mNavRsOverviewPanel`（原生 overview，`alpha=0` + `INVISIBLE`）；
- `mNavRsDragHideTick`：`Choreographer` 60fps 回调，按 `NavRsActivity.getDragProgress()`
  更新 wall 的 RenderEffect 半径、scrim alpha、以及驱动 `NavRsOverlay.current()` 的 deck；
- `startNavRsDragHide()`：`NavRsActivity.isOpen()` 时直接 return（否则退出动画中会二次进后台）；
- `captureNavRsDesktopShot()`：`NORMAL` + `hasWindowFocus()` 才截屏（900ms 延迟，失败重试一次），
  并在工作线程烘焙模糊。

### 3.5 手势落点（#5）

```java
if (NavRsActivity.recentlyExited() || NavRsActivity.isOpen()) {
    endTarget = Constants.LAST_TASK;   // 取消手势，什么都不动画
    reason = "-> LAST_TASK(activity)";
} else {
    endTarget = Constants.HOME;        // deck 路径
    reason = "-> HOME(deck)";
}
```

**不要**在 `onStateSetStart/End` 里递归 `goToState` —— 会打断 state manager，整屏冻在中间态。

### 3.6 手势阈值（#6/#7）

| 位置 | 原值 | 新值 |
| --- | --- | --- |
| `NoButtonNavbarToOverviewTouchController.mMotionPauseMinDisplacement` | 1×slop | 0.5×slop |
| `MotionPauseDetector` 静止判定 | 300ms | 180ms（新增 setter） |

## 4. Rust 侧

```bash
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018   # 或你的路径
cd rust/
cargo ndk -t arm64-v8a -o out build --release
cp out/arm64-v8a/libnav_rs.so ../java/navrs/libnav_rs-arm64.so
```

- crate 类型 `cdylib`，`[lib] name = "nav_rs"` → `libnav_rs.so`。
- `panic = "abort"`、`strip = "symbols"`、`lto = "thin"`。
- 依赖仅 `jni = "0.21"`。
- 32 位设备再跑一次 `-t armeabi-v7a`（仓库内的 `libnav_rs-arm.so` 即此产物）。

> Rust 与 Java 之间**没有共享头文件**：函数名由 `#[no_mangle] pub extern "system" fn
> Java_com_wzy_navrs_*` 手写，签名必须与 `NavBarView`/`RecentsView` 的 `native` 声明逐字一致。
> 改签名时两边一起改，否则运行时 `UnsatisfiedLinkError`（编译期发现不了）。

## 5. 部署

`Launcher3QuickStep` 在 `system_ext/priv-app/`，**apk + odex + vdex 必须同批推**，
否则 dex 校验失败会回落解释执行（表现为「开机后桌面很卡」）。

```bash
export MSYS_NO_PATHCONV=1        # Git Bash 必加，否则 /system_ext/... 被改写成 C:/...
adb root
adb shell "mount -o rw,remount /system_ext"
adb push Launcher3QuickStep.apk  /system_ext/priv-app/Launcher3QuickStep/
adb push Launcher3QuickStep.odex /system_ext/priv-app/Launcher3QuickStep/oat/arm64/
adb push Launcher3QuickStep.vdex /system_ext/priv-app/Launcher3QuickStep/oat/arm64/
adb push libnav_rs.so            /system/lib64/
adb push libnav_rs.so            /system_ext/priv-app/Launcher3QuickStep/lib/arm64/
adb shell "chmod 644 /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk \
  /system_ext/priv-app/Launcher3QuickStep/oat/arm64/Launcher3QuickStep.{odex,vdex} \
  /system/lib64/libnav_rs.so \
  /system_ext/priv-app/Launcher3QuickStep/lib/arm64/libnav_rs.so"
adb shell "restorecon -R /system_ext/priv-app/Launcher3QuickStep /system/lib64"
adb shell "am force-stop com.android.launcher3"
adb shell "input keyevent KEYCODE_HOME"
```

> `system_ext` 很容易满（本机 464MB 分区只剩 ~10MB）。**删除/覆盖正在被进程 mmap 的文件不会
> 立刻释放空间**（deleted-but-open），表现为 `df` 不变、push 报 `No space left`。
> 解决办法：`adb reboot` 后再推，或先腾出 ≥30MB。

## 6. 切到三键导航

`settings put secure navigation_mode 2` 在这类 ROM 上会被 SystemUI 重启写回，必须走 overlay：

```bash
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural   # 切回手势
adb shell "cmd overlay list | grep navbar"    # 确认 [x]
```

## 7. 验证清单

```bash
# 1) 三键：app → 后台
adb shell am start -n <任意 app>
adb shell input keyevent KEYCODE_APP_SWITCH
adb logcat -d | grep -E "NavRS|NavRSProbe" | tail -20
#   期望：entry start ... t=<150ms 左右>；没有 "gestureHandler start / stateStart Background"

# 2) 后台里上滑退出
adb shell "input motionevent DOWN 540 2100; input motionevent MOVE 540 2000; \
            input motionevent MOVE 540 1950; sleep 0.25; input motionevent UP 540 1950"
#   期望：edge swipe up (move): closing overview → slideOut end t≈280

# 3) 桌面 → 后台
adb shell input keyevent KEYCODE_HOME; adb shell input keyevent KEYCODE_APP_SWITCH
#   期望：fromHome=true，背景是先清晰后模糊

# 4) 三键栏取色
adb logcat -d | grep "bar colour\|app bar"
```

录屏抽帧（判读动画是否掉帧 / 是否有闪断）：

```bash
adb shell screenrecord --time-limit 8 --bit-rate 12000000 /sdcard/rec.mp4
adb pull /sdcard/rec.mp4 .
ffmpeg -i rec.mp4 -vf fps=30 frames/f%03d.png
ffmpeg -start_number 50 -i frames/f%03d.png -frames:v 1 -vf "crop=1080:100:0:0,tile=1x16" top.png
```

## 8. 常见坑（都踩过）

1. **模块可见性**：`quickstep` 看不到 `com.wzy.navrs.*`（编译报 `cannot find symbol`）。
   只能用 facade `com.android.launcher3.navrs.NavRsActivity`。
2. **不要递归 `goToState`**：在 `onStateSetStart/End` 里再切状态会打断 state manager，冻屏。
3. **退出落点**：deck 路径用 `HOME`；activity 路径必须 `LAST_TASK`（取消手势），
   否则会出现「缩成图标飞回桌面」的重复动画。
4. **`isOpen()` 守卫**：`startNavRsDragHide()` 里若不判断，退出动画期间会二次进后台。
5. **缩略图来源**：`task.thumbnail` 可能是过期的黑图，只有 `TaskThumbnailCache` 的
   `fromCache=true` 才可信（`applyThumb()` 用它触发 `startEntry`）。
6. **截图时机**：`NORMAL` 之后 500ms 截屏可能截到刚起来的 app，把 app 当桌面存下来。
   用 900ms + `hasWindowFocus()` + 重试。
7. **`clipPath` 开销**：卡片圆角目前用 `Canvas.clipPath`，在低端 GPU 上是主要 fill 成本；
   若要继续优化，改成 `BitmapShader + drawRoundRect`。
8. **状态栏图标**：新建窗口默认是「浅色图标」，会在 app 的浅色顶栏上「渐隐」。
   必须在 `onCreate` 里用 `WindowManager.LayoutParams.appearance` 先声明与 app 一致的深浅。
9. **`input swipe` 触发不了 `onMotionPauseDetected`**：匀速手势没有「停顿」，
   必须用 `input motionevent DOWN/MOVE … sleep 0.25 … UP` 造停顿。
10. **`restorecon` 只对目录报 “Skipping”**：正常，文件仍会被重打标签；用 `ls -Z` 复核。

## 9. 适配新机型

- 卡片几何全部用**比例**（屏高/卡宽），不写死像素；换分辨率无需改 Rust。
- 卡片内容区用 `nativeSetContent(aspect, centerY)` 传入「去掉系统栏后的窗口宽高比」，
  所以刘海/挖孔屏只需保证 `contentInsets()` 取到正确的 status/nav 高度。
- 三键栏布局用 `BTN_BAR_FRACTION=0.66`（占屏宽比例），横屏时自动按高度算。
- 导航栏高度取 `navigation_bar_height`，取不到时回退 `48dp`。
- 若目标机是平板/双栏，`OverviewCommandHelper` 的 `allowQuickSwitch` 会让它走原生逻辑
  —— 需要自己扩展，本项目未适配。
