# NavRS Rust 后台移植技术档案

> 设备：Xiaomi Mi MIX 2S（polaris，SDM845，1080×2160）
> 系统：LineageOS 22.2（Android 15，userdebug）+ AOSP Launcher3 r32
> 模块：`C:\Users\wzy\Documents\nav-rs-上滑销毁修正版`
> 源码树：WSL `/root/android/lineage`
> 本文记录 2026-11-01 ~ 2026-11-02 的完整过程、接口、几何锚点、动画时序、踩坑和审计结论。

---

## 1. 最终架构

```
[3 键 Recents / 桌面多任务键]
        │  (SystemUI → TouchInteractionService → OverviewCommandHelper)
        ▼
com.android.launcher3/com.wzy.navrs.NavRsRecentsActivity   ← 独立 Activity，独占一个 task
        │
        ├─ Wallpaper（自绘壁纸 + RenderEffect 高斯模糊 + scrim，进场帧才显示）
        ├─ com.wzy.navrs.RecentsView（Rust 渲染：libnav_rs.so）
        └─ RecentsTuningPanel（长按多任务弹出，Preferences 持久化）

系统导航栏 = Launcher Taskbar（NavBarView/Rust 三键画在这里，独立窗口，常驻顶层）
```

- **不再使用 Quickstep 的 overview / RecentsAnimation / stock TaskView**。点击多任务键直接打开我们自己的 Activity（0 时长 task 转场）。
- 任务列表来自 `RecentsModel.getTasks()`，缩略图来自 `TaskThumbnailCache`，启动/销毁任务用 taskId 直接操作，不依赖 stock TaskView 的绑定状态。
- Rust 只负责布局、位移、缩放、亮度、绘制卡片；缩略图合成、任务操作、窗口/壁纸由 Java 层负责。

---

## 2. 代码地图

| 路径 | 职责 |
|---|---|
| `quickstep/src/com/android/launcher3/navrs/NavRsRecentsActivity.java` | 独立后台 Activity：自绘模糊壁纸、任务加载、缩略图、Rust 轮播、调节面板、进出动画；不自绘三键 |
| `quickstep/src/com/android/launcher3/navrs/RecentsView.java` | Rust 轮播的 Java 壳：JNI、缩略图数组、点按/上滑销毁/退出、launch/return/enter 动画、亮度矩阵、圆角 |
| `quickstep/src/com/android/launcher3/navrs/NavBarView.java` | Rust 三键导航；装在 Taskbar 中（系统导航栏），长按 recents 会优先路由到当前 Recents Activity |
| `quickstep/src/com/android/launcher3/navrs/RecentsTuningPanel.java` | 调节面板：样式(6 种)/回弹/间距/卡片大小/动画速度；`toggle(Activity, View, RecentsView)` 供 Activity 使用 |
| `quickstep/src/com/android/launcher3/navrs/NavRsPrefs.java` | SharedPreferences：`navrs_recents`，5 个 key |
| `quickstep/src/com/android/launcher3/navrs/RecentsOverlay.java` | **旧路径**：Launcher live overview 上的 overlay（当前 Recents 键不再使用，保留作回退） |
| `quickstep/src/com/android/quickstep/OverviewCommandHelper.kt` | 3 键 Recents 的入口：`TOGGLE/SHOW` 且非 quick-switch 时直接启动 `NavRsRecentsActivity` |
| `quickstep/AndroidManifest.xml` | `<activity android:name="com.wzy.navrs.NavRsRecentsActivity" ... android:taskAffinity="" android:excludeFromRecents="true" android:launchMode="singleTask">` |
| `quickstep/res/values/styles.xml` | `NavRsRecentsTheme`：`windowIsTranslucent=true`、`windowBackground=transparent`、`windowDisablePreview=true`（**不再** `windowShowWallpaper`） |
| `quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java` | 旧路径 hook：`onStateSetStart/End`（`RecentsOverlay`）；当前 Recents 键不触发 |
| `src/recents.rs` / `src/lib.rs` | Rust 渲染与几何：`card_quad`、`touch`、`render`、JNI |

构建产物：
- `Launcher3QuickStep.apk` → `/system_ext/priv-app/Launcher3QuickStep/`
- `libnav_rs.so` → `/system/lib64/libnav_rs.so`（app 目录软链）
- 快速脚本：`/root/navrs_fast.sh`（-j8 + 拷贝 APK/库到 Windows `out/`）

---

## 3. 关键接口

### 3.1 Java ⇄ Rust（JNI，包名必须是 `com.wzy.navrs`）

`RecentsView` native：
`nativeCreate(w,h,density,count)`、`nativeSetContent(aspect, centerY)`、`nativeSetMode`、`nativeSetSpring`、`nativeSetPitchByCard`、`nativeSetCardLarge`、`nativeSetFrozen`、`nativeRemoveCard`、`nativeHitAt`、`nativeCardCount`、`nativeSettle`、`nativeCardQuad`、`nativeCardQuadRaw`、`nativeCardRect`、`nativeCardAlpha`、`nativeRender`、`nativeTouch`、`nativeDestroy`。

`NavBarView` native：`nativeCreate(w,h,density)`、`nativeResize`、`nativeSetColor`、`nativeSetKeyguard`、`nativeLongPress`、`nativeRender`、`nativeTouch`、`nativeDestroy`。

> JNI 符号是 `Java_com_wzy_navrs_*`。源码目录放在 `com/android/launcher3/navrs/` 但 `package com.wzy.navrs;`，这样不用改 Soong glob。

### 3.2 任务/缩略图/启动

- `RecentsModel.INSTANCE.get(this).getTasks(groups -> ...)`：**返回顺序是旧 → 新**，Activity 内 `Collections.reverse(list)` 得到新 → 旧。
- 过滤 `com.android.launcher3`（本 Activity、launcher home），否则会多出“房子图标”的幽灵卡。
- `TaskThumbnailCache.getThumbnailInBackground(task, cb)`：对**每个任务**取快照（包括屏幕外），返回 `ThumbnailData.thumbnail`（可能是 HARDWARE bitmap）。
- 启动：`ActivityManagerWrapper.startActivityFromRecents(taskId, options)`；销毁：`removeTask(taskId)`。
- 点卡片/上滑销毁都绑定**启动时捕获的 taskId**，即使 stock TaskView 回收也不会串 app。

### 3.3 三键 / 调节面板

- 三键本身由 **Taskbar 窗口**里的 `NavBarView`（Rust）绘制，这个 Taskbar 就是系统导航栏 surface（`mNavigationBar=Window{… Taskbar}`，inset 132 px），在所有 app 与后台界面上都常驻。
- 点击：`TaskbarBridge` → `TaskbarNavButtonController.onButtonClick`（back/home/recents 全走系统行为）。
- 长按 recents：`NavBarView.onLongPress(3)` → `NavRsRecentsActivity.current() != null` 时 `showTuningPanel()`（面板挂到后台 Activity 的 decor，实时作用当前 RecentsView），否则走 launcher 内的 `RecentsTuningPanel.toggle(context, view)`。
- 后台 Activity 的 back/recents/home：back → `onBackPressed`；recents → `onNewIntent`（toggle）；home → `onStop` 里 finish。

`RecentsTuningPanel` 的 5 个值来自 `NavRsPrefs`，每次修改立即 `NavRsPrefs.apply(target)`，下次打开后台沿用。

---

## 4. 几何与锚点（本机实测值）

| 量 | 值 | 说明 |
|---|---|---|
| 屏幕 | 1080×2160 | dash 显示 |
| 状态栏 inset | 77 px | `statusBars()` |
| 三键导航 inset | 132 px | `getInsetsIgnoringVisibility(navigationBars())`（即使隐藏也取稳定值） |
| 内容区 | 1080×1951 | `2160 - 77 - 132` |
| 内容比例 | 1080/1951 = **0.5536** | 传给 `nativeSetContent(aspect, centerY)` |
| 卡片中心 | (77 + 1951/2)/2160 = **0.487** | 原生 `card_cy()` 使用 |
| 卡片高度系数 | 小 0.663 / 大 0.85 | `CARD_H_SMALL/LARGE` |
| 小卡尺寸 | ≈793×1432 | `w = h*0.663*0.5536`，与裁过的截图同比例 |
| 圆角 | 0.13 × 卡片宽 ≈ 103 px | 原生 `CORNER=0.13`，Java 换算 `out[4]=(right-left)*out[4]` |
| 缩略图 | 最长边 ≤720/1440，裁掉状态栏+导航栏 | 源多为 HARDWARE，先 `copy(ARGB_8888)` 再裁/缩放 |
| Rust 三键高度 | 132 px | Taskbar 内的 `NavBarView`，位于系统导航栏 surface（独立窗口） |
| Activity 窗口 | 全屏（`decorFitsSystemWindows(false)`） | Rust 轮播 `MATCH_PARENT` 全屏；内容 inset 由 native 计算 |

**动画边缘判定**
- 放大（卡片→app）：`drawLaunch` 终点是**内容区** `(0, contentTop, w, h-contentBottom)`，不再压状态栏/导航栏。
- 缩小（app→后台）：`startReturn(0)` 的第一帧就是同一内容区矩形，与截图内容完全重合。
- 导航栏/状态栏变化：inset 用 `getInsetsIgnoringVisibility` 取稳定值；Activity 不再隐藏导航栏，窗口再叠加 `fitInsetsTypes(0)`，窗口边界和动画锚点都不随 inset 变化。
- Activity 内 Rust view 全屏（无 bottomMargin），导航栏（Taskbar）叠在底部，不参与卡片几何。

---

## 5. 动画时间线

### 5.1 app → 后台（Recents 键）

```
t0      OverviewCommandHelper: makeCustomTaskAnimation(0,0) 启动 NavRsRecentsActivity
t0+     Activity 透明窗口：壁纸层/卡片层都是 INVISIBLE / alpha 0，
        下层真实 app 画面继续可见（实测 100ms 级）
t0+~1帧 RecentsModel.getTasks() 回调（旧→新，反转，过滤 launcher）
t0+~1帧 showTasks()：创建 RecentsView（alpha 0）、setContentInsets、添加全屏
t0        OverviewCommandHelper 用 TopTaskTracker 判定 fromHome，随 Intent 传 EXTRA_FROM_HOME
t0+~100ms 缩略图缓存回调（front task，src=cache）：
          → crop(状态栏/导航栏) → scale → 记录 luma
          → 同一帧：壁纸层 VISIBLE + scrim VISIBLE + rust.alpha=1
          → fromHome=false：startReturn(0)（app 截图满屏 → 缩回轮播）
          → fromHome=true ：startSlideInFromLeft()（整套牌面从左侧平移进场）
700ms   兜底：若缩略图一直没来，也触发 startEntry（避免永久定格）
```

- **两条进场路径按 `fromHome` 分流**：
  - 从 app：`startReturn` 内部 `launchProgress=1 → 0`（ValueAnimator，`transitionDuration()` = 320/480ms），app 截图从内容区满屏缩回轮播位置（不变）。
  - 从桌面：`startSlideInFromLeft()` 先把 `translationX` 置为 `-width`，再 420ms `DecelerateInterpolator(1.5)` 平移回 0；没有"最近 app 截图盖满再缩小"的过程。
- 从桌面进场时，背景是**按多任务那一刻截取的桌面截图**（壁纸 + 图标 + 组件，`ScreenCapture.captureDisplay`），并在这 420ms 内把 `RenderEffect` 高斯模糊 `0→52px`，卡片就位时模糊也到顶；scrim 透明度与模糊同步 `0→1`。
- `fromHome` 来源：`TopTaskTracker.INSTANCE.get(ctx).getCachedTopTask(false).isHomeTask()`；`OverviewCommandHelper` 判定后写 `EXTRA_FROM_HOME`。
- **进场门槛是 cache 缩略图，不是 model 缩略图**：`Task.thumbnail` 可能是过期的黑色占位图，实测会让第一帧变成黑卡。
- **没有 appSnapshot 中间层**（已删除）；壁纸层不再提前显示，所以不会出现「app 消失 → 只剩壁纸 → 卡片姗姗来迟」。
- 背景：`drawLaunch` 不再画黑底（`drawColor(0x00000000)`），壁纸在卡片周围可见。
- 实测（screenrecord 60fps + ffmpeg 抽帧，YAVG 亮度）：app→后台和桌面→后台两条路径都**没有黑帧、没有纯壁纸帧**；t≈144–198ms 时 cache 缩略图到达并一次性覆盖。桌面→后台的滑入抽帧：纯壁纸帧后，亮像素占比 2.6%→48.2% 单调上升约 420ms。

### 5.2 后台 → app（点卡片）

```
onCardTap(index) → taskId = tasks[index].key.id
  → RecentsView.startLaunch(index)：卡片放大到内容区满屏（320/480ms）
  → 回调：startActivityFromRecents(taskId, makeCustomTaskAnimation(0,0)+SOLID splash)
  → finishAndRemoveTask() + overridePendingTransition(0,0)
```

- 放大动画终点 = 内容区；随后 app 以自己的窗口出现（自定义 task 动画 0），不再有左滑转场。
- 启动使用捕获的 taskId，不用 recycler 的 TaskView。

### 5.3 上滑销毁 / 点击空白退出

- 上滑：`RecentsView` 内部 `ACancel/MOVE/UP` 判定 → `beginDismiss` → 动画 → `nativeRemoveCard` → `onCardDismiss(index)` → `removeTask(taskId)`（Activity 侧 thumbs 冻结）。
- **点击空白处退出（左滑）**：Java 侧判定真 tap（`tapMoved` ≤ 8dp、≤350ms、`nativeTouch` 返回 -1）后调用 `startSlideOutToLeft()`：整套牌面 280ms `AccelerateInterpolator(1.2)` 从 `translationX 0 → -width` 滑出左边缘。滑出**开始时**回调 `onExitStart`：
  - 桌面来源（有 home 截图）：背景模糊同步 `52→0`，卡片消失时桌面刚好清晰；滑出结束 `finish()`。
  - app 来源：背景 160ms 淡出露出原 app；滑出结束再 `finish()`。
- **多任务键 = 同一套切换**：后台打开期间再按多任务键，`onNewIntent` 按**进入时**的 `fromHome` 分派：`fromHome=true` → `startSlideOutToLeft()`（与点空白完全一致）；`fromHome=false` → `startLaunch(0)` 把当前 app 卡片放大回内容区并 `launchTask`（回到当前 app）。重按时系统重算的 extra 不采信。
- 实测（60fps 抽帧）：
  - 桌面进场：背景条带清晰度 `2.90→0.87` 单调下降（约 420ms），卡片同时从左侧进入。
  - 桌面退出（多任务键）：卡片右缘 `maxx 906→0`，背景清晰度 `0.82→2.83` 同步上升，卡片消失时桌面刚好清晰。
- 下滑空白处**不再退出**（旧的 `dy>96 且竖直` 判定已删除）——下滑是空操作，不会退出也不会误开卡片（见踩坑 25）。
- 底部三键：**就是系统导航栏本身**（见 6.1），不是 Activity 自绘的 NavBarView：
  - back → 系统返回 → `onBackPressed()` → finish；
  - recents → APP_SWITCH → OverviewCommandHelper → singleTask 复用 → `onNewIntent()` → finish（toggle）；
  - home → 系统回桌面 → 本 Activity `onStop()` 时 finish（避免下次 singleTask 复用旧实例）。
  - 长按 recents → taskbar 的 NavBarView → `NavRsRecentsActivity.current()` → `showTuningPanel()`，面板挂到 Activity decor，改动实时作用于当前 RecentsView。

### 5.4 手势导航（独立 Activity）

手势从 app 上滑进入 overview 时，不再走 launcher 内的 `RecentsOverlay`，而是打开 `NavRsRecentsActivity`：

- `AbsSwipeUpHandler.calculateEndTarget()`：判定为 `RECENTS`（非 atomic）时，记录 `mPendingNavRsLaunch` 与 `fromHome`（取 `mGestureState.getRunningTask().isHomeTask()`），并返回 `LAST_TASK`——这样 launcher 不会进入 stock overview 状态。
- `resumeLastTask()`：先把 recents 动画收回到 app（`finish(false)`），再 `MAIN_EXECUTOR.execute(...)` 调 `NavRsRecentsActivity.launchFromGesture(context, fromHome)`。**必须等 app 回到前台再启动 Activity**，否则 Activity 后面会是桌面而不是原 app。
- `QuickstepLauncher.onStateSetStart/End`：`DisplayController.getNavigationMode(this).hasGestures` 为真时跳过所有 `RecentsOverlay` 调用（手势模式不再用 legacy overlay）。
- **拖拽段隐藏 stock 面板**：手势 `BACKGROUND_APP` 期间，`QuickstepLauncher` 用 `postOnAnimation` 每帧把 stock `overview_panel` 设为 `INVISIBLE` + alpha 0（缓存引用），只保留 shell 的运动中 app live tile + 壁纸；`NORMAL` / `onStop` 时恢复 VISIBLE。这样拖拽时不会出现系统 overview 的上一个任务卡片。
- **拖拽背景模糊渐变**：`AbsSwipeUpHandler.updateSysUiFlags(progress)` 把实时拖拽进度写到 `NavRsRecentsActivity.setDragProgress`；`QuickstepLauncher` 的手势 tick 每帧读它，给叠在窗口里的壁纸 ImageView 设 `RenderEffect` 半径 `0→52`、scrim alpha `0→1`，手势结束移除。实测：浅拖背景清晰，深拖背景明显高斯模糊；运行 app 的活动缩略随手指缩放（shell live tile）。
- 结果：手势松手 → app 回到前台 → 独立 Activity 以 fromHome=false 进场（壁纸模糊 + 反向缩小）；点空白退出 → 回到原 app。连续 3 次循环无崩溃。
- 本机手势 overview 只从 app 触发（桌面下滑是应用抽屉，上滑按住不进入 overview），因此手势路径 `fromHome=false`。

**手势（拖拽+停驻）全程由 launcher 内的 Rust 轮播绘制**（`RecentsOverlay`）：`BACKGROUND_APP` 时安装并 `beginDragReturn(0)`；`QuickstepLauncher` 的手势 tick 每帧把 `1 - 拖拽进度` 喂给 `setDragReturnProgress`。**邻居与当前卡精确同步**：`AbsSwipeUpHandler.applyScrollAndTransform()` 每帧把 shell 的 `TaskViewSimulator.getCurrentRect()`（真实运行卡矩形）通过 `RecentsView.setRunningRect()` 发布；`drawLaunch` 在拖拽时用该矩形做整副牌的分组变换（settled 前卡矩形 → 当前运行卡矩形），所以邻居按当前卡的真实缩放/位移同步运动，而前卡在拖拽时跳过绘制（shell 的 live tile 就是前卡，避免重影）。松手进 `OVERVIEW` 时 `settleDragReturn` 回到规定位置。长滑（HOME）松手时 `rampOutNavRsDragBackdrop` 让模糊 52→0。三键路径仍用独立 `NavRsRecentsActivity`；桌面小横条上滑仍进 Activity(fromHome=true)。

- **桌面小横条上滑**：`NoButtonNavbarToOverviewTouchController.onMotionPauseDetected()`（stock overview 的触发点）改为 `NavRsRecentsActivity.launchFromGesture(mLauncher, true)`，不再进 stock overview；走 fromHome=true 进场（桌面截图 + 卡片左滑入 + 模糊 0→52）。实测：`launch from gesture fromHome=true`、`entry start fromHome=true`，背景是模糊的桌面（搜索栏/Dock），点空白回桌面。
  - 普通快速上滑（不按住）仍是系统 All Apps 抽屉；若要把它也改成 recents，需要再改 `PortraitStatesTouchController` 的 NORMAL→ALL_APPS 判定（会占用抽屉手势）。

---

## 6. 背景 / 壁纸 / 模糊

### 6.1 导航栏归属（本轮关键发现）

- 本机 `dumpsys window`：`mNavigationBar=Window{… u0 Taskbar}`，`InsetsSource type=navigationBars frame=[0,2028][1080,2160]`。
- 也就是说：**系统三键导航栏的 surface 就是 Launcher 的 Taskbar**，NavRS 的 Rust 三键是画在这个 Taskbar 里的。
- 因此 Activity 里调用 `controller.hide(navigationBars())` 时，WindowManager 收起的是 **Rust 三键所在的 Taskbar surface**（insets animation leash）；视觉上就是「我们的三键向下滑走」。
- 修复：Activity **不再 hide 导航栏**，也不再自绘第二条 NavBarView；系统三键（Taskbar）常驻顶层（实测 `visible=true`），没有任何 inset 动画。`fitInsetsTypes(0)` 只用来保证窗口自身不因 inset 变化而移动。

### 6.2 壁纸与模糊

- **桌面来源：整屏快照做背景**。按多任务那一刻在 `OverviewCommandHelper` 里（主线程、启动 Activity 之前）调用 `NavRsRecentsActivity.captureHomeShot()`：`WindowManagerGlobal.getWindowManagerService().captureDisplay(0, CaptureArgs, syncListener)` → `ScreenshotHardwareBuffer.asBitmap()` → `copy(ARGB_8888)` 存静态字段。这样背景包含**壁纸、图标、组件、搜索栏**，而不是只有壁纸。
  - 依赖 `READ_FRAME_BUFFER`（Launcher3 已声明且开机 `granted=true`，无需额外运行时授权）。
  - 模糊用 `RenderEffect.createBlurEffect(r,r,CLAMP)` 挂在这个 ImageView 上，半径由 `ValueAnimator` 驱动：进场 `0→52`、退场 `52→0`，与卡片滑动同长（420/280ms）；scrim alpha 与半径同步。
- **app 来源**：仍用自绘壁纸层（`WallpaperManager.getDrawable()` + 静态 `RenderEffect` 52 + scrim），没有进退场模糊动画。
- **手势导航路径**（Quickstep `RecentsOverlay` 叠在 stock overview 上）：新增一层不透明的「模糊壁纸 + scrim」背板；把 Rust `RecentsView` 背景设为透明（它默认是不透明 `0xFF0A0D12`）；再把 stock `overview_panel` / `overview_actions_view` 设成 INVISIBLE（退出时恢复），避免 stock 面板在下面重复绘制。手势 overview 基本都从 app 触发（桌面下滑是抽屉），所以这里用壁纸模糊，与三键 fromApp 一致。
- 自绘壁纸层默认 INVISIBLE，进场那一帧才显示。
- 权限：`WallpaperManagerService.getWallpaperWithFeature()` 先检查 `READ_WALLPAPER_INTERNAL`，否则走 `checkPermissionReadImages()`（要求 READ_EXTERNAL_STORAGE + READ_MEDIA_IMAGES 运行时授权）。
  - 已给 Launcher 加 `signature|privileged` 的 `android.permission.READ_WALLPAPER_INTERNAL`，并写进 `frameworks/base/data/etc/com.android.launcher3.xml` 的 privapp 白名单；Launcher3QuickStep 是 system_ext 上的 privileged app，开机即 granted。
  - 实测：`READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` 都 `granted=false` 时，壁纸仍能正常读取并模糊——干净刷机无需任何 adb 授权。
- 兜底：`getDrawable()` 失败时壁纸层填纯深色 `0xFF10141A`，**绝不回退到 FLAG_SHOW_WALLPAPER**（那个会把 app 截图当背景，就是之前的“背景变成 app 截图”）。
- `drawLaunch` / `drawDismiss` 都不画不透明底色。

---

## 7. 踩坑清单（按时间线）

1. **硬件 bitmap 不能画进软件 Canvas**：`TaskThumbnailViewDeprecated.drawOnCanvas` 抛 `Software rendering doesn't support hardware bitmaps` → 先 `getThumbnail()` 再 `copy(ARGB_8888,false)`。
2. **竖滑被 `PortraitStatesTouchController` 抢走**：横滑/点击正常、竖滑收不到 MOVE → `requestDisallowInterceptTouchEvent(true)`（旧 overlay 路径）。
3. **JNI 包名**：必须是 `com.wzy.navrs`，否则 `UnsatisfiedLinkError`（`Java_com_wzy_navrs_*`）。
4. **stock 双导航栏**：只 alpha=0 不够，必须 `setVisibility(INVISIBLE)`。
5. **TaskView 复用/顺序**：stock child order 不可靠；模型顺序是旧→新；必须反转 + 用 taskId 启动。
6. **`getWindow().getInsetsController()` 在 `setContentView` 前调用 → DecorView NPE → launcher 进程崩溃/黑屏**（本轮回归过一次，已改回 setContentView 之后）。
7. **manifest 加 Activity 要加在 `quickstep/AndroidManifest.xml`**，不是根 `AndroidManifest.xml`（Launcher3QuickStep 用 quickstep 那份）。
8. **改 manifest/主题资源需要重启**才能让 PackageManager 看到新组件/主题；只改 Java 可以 force-stop。
9. **新增文件/改 manifest 触发 Soong 重分析（约 20+ 分钟）**；纯内容改动约 2 分钟。
10. **WSL 命令里的 `$`、heredoc 和路径**：外层 shell 会展开/转换，容易写坏补丁；补丁改成「本地写 .py → cp 进 WSL → python3 执行」。
11. **adb daemon socket buffer**：频繁 kill/start 会报 10055；必要时 `adb kill-server && adb start-server`。
12. **Settings 大量界面 `excludeFromRecents=true`**：从 Settings 按多任务时它可能不在列表里，不是 bug。
13. **多次启动同一 app 会产生多个 task**（列表中多个 Jelly），是系统真实任务。
14. **Rust 原生背景曾经不透明**：Activity 壁纸被挡住；已改成透明 fill，并删除原生占位卡体（避免占位卡透出）。
15. **亮度用 alpha 会半透明**：改成 ColorMatrix 缩放 RGB，卡片始终不透明；齿轮最左被埋卡片固定 0.8 亮度。
16. **圆角曾经只有 6px**：`out[4] = CORNER_RATIO*width*out[4]` 双重缩放；现在直接 `(right-left)*out[4]`（原生 CORNER=0.13）。
17. **卡片“拍扁”**：Rust view 底部留了 132px margin 导致内容高度变小 → 全屏 view + 内部 content inset；比例 0.5536 与裁剪图一致。
18. **放大最后一帧下边缘对不齐**：终点曾是整屏或内容区反复调整；最终按内容区（上避状态栏、下避导航栏），且 app 自身按相同 inset 处理。
19. **进入后台闪壁纸/占位卡**：先后用过 appSnapshot、提前 hide system bar 等方案，最终采用「透明窗口 + 无 snapshot + Rust 第一帧覆盖 + 34ms 后开壁纸」。
20. **提前 hide system bar 导致 NPE**：见第 6 条，已修复。
21. **三键下滑的真正来源是 `hide(navigationBars())`**：系统导航栏 surface = Launcher Taskbar，隐藏请求会让 WindowManager 给 Taskbar 套 insets animation leash；Rust 三键因此被「收下去」。修复：不 hide、不自绘第二条栏，见 6.1。
22. **model 缩略图可能是黑色占位图**：`Task.thumbnail` 先到但内容为黑，若用它触发进场会得到「满屏黑卡」；改成只在 cache 回调（`src=cache`）到达后才 startEntry，日志同时打印 `w×h` 与粗略亮度 `luma`。
23. **壁纸读取失败会退化成 app 截图**：`FLAG_SHOW_WALLPAPER` 在 app 位于下层时会显示 app 快照。新版完全去掉该 flag，改自绘壁纸层 + 纯深色兜底。
24. **干净刷机后 READ_EXTERNAL_STORAGE 不授予**：自绘壁纸需要读系统壁纸；改用 privileged 的 `READ_WALLPAPER_INTERNAL` + privapp 白名单，开机即 granted，不再依赖运行时授权。
25. **齿轮/peel 模式竖滑被当成点击**：`touch_peel`（`mode≥4`，齿轮=5）原来只按 `dx` 判定 `moved`，且不记录 `down_y`，纯竖直滑动仍算 tap，抬手 `hit_test` 命中卡片 → 误启动卡片。已补 `down_y` 记录与 `dy` 判定（`|dy|>SLOP` 也置 `moved`）；修复后竖滑在 `touch()`/`touch_peel()` 两条路径都返回 -1。
26. **点击空白退出只在真 tap 触发**：Java 侧用 `tapMoved`（>8dp）和 `TAP_TIMEOUT_MS=350` 判定；`nativeTouch` 返回 -1 且未移动才 `onExit`，拖拽不会误退出。
27. **关闭转场固定约 387ms 且显示冻结壁纸**：`overridePendingTransition(0,0)` 在 API 35 上没把默认 CLOSE 转场压到 0（实测 `Finish Transition ... finished=386.73ms`）。若牌面滑出后直接 finish，会看到约 0.4s 的静止模糊壁纸。修法：滑出结束后先把壁纸/scrim 用 160ms 淡到 0（露出桌面），再 finish；此时关闭转场作用在透明窗口上，肉眼无感。
28. **`captureDisplay` 取整屏**：`ScreenshotHardwareBuffer.asBitmap()` 可能是 HARDWARE 位图，且 `captureDisplay` 是 `oneway`（靠同步 listener 的 latch 阻塞等待）。已 `copy(ARGB_8888)` 成软件位图再交给 ImageView；截图为 1080×2160，含状态栏/导航栏区域，但它们被系统状态栏和 Taskbar 覆盖，无影响。
29. **多任务键重按时 `fromHome` 会被重算错**：后台已打开时 `TopTaskTracker` 看到的顶层是 Recents 自己，重算会得到 `fromHome=false`。所以 `onNewIntent` 必须用**进入时**保存的 `fromHome`，不能用新 Intent 的 extra；否则从桌面重按会误走"放大回 app"而不是左滑退出。
30. **手势 overview 背景是深色、且卡顿**：手势走 `RecentsOverlay`（叠在 stock overview 上）。`RecentsView` 默认背景是**不透明 `0xFF0A0D12`**（Activity 路径里有 `setBackgroundColor(TRANSPARENT)`，overlay 路径没有），实测背景像素就是 `(10,13,18)`；同时 stock `overview_panel` 仍可见 → 下面重复绘制。修法：launcher 路径把 `RecentsView` 设透明、在它后面加「模糊壁纸 + scrim」背板、并隐藏 stock 面板（退出恢复）。
31. **手势托管必须在 app 回到前台之后启动 Activity**：在 `calculateEndTarget`（松手瞬间）就 `startActivity`，recents 动画会被打断，Activity 后面变成**桌面**而不是原 app，点空白会回桌面。改为返回 `LAST_TASK`，在 `resumeLastTask()` 里（app 已恢复）再启动。
32. **手势模式下的 `fromHome` 不能用 TopTaskTracker**：松手时 launcher 已进入 Background，`TopTaskTracker` 看到的顶层是 home，会把 from-app 误判成 fromHome=true。改用 `mGestureState.getRunningTask().isHomeTask()`（记录的是手势起始任务）。本机桌面手势不进入 overview，实际总是 false。
33. **`navigation_mode=2` 不等于手势导航**：还要切 overlay `cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`，否则仍是 `threebutton`。
34. **手势拖拽中的 stock 卡片来自 `overview_panel`**：在 `BACKGROUND_APP` 里只设一次 `INVISIBLE` 会被状态动画改回可见，拖拽时仍看到上一个任务卡片。改为 `postOnAnimation` 每帧强制 `INVISIBLE` + alpha 0（缓存 panel 引用），`NORMAL`/`onStop` 恢复；拖动中只剩 shell 的运行 app live tile + 壁纸。
35. **桌面小横条上滑进入的是 stock overview**：触发点在 `NoButtonNavbarToOverviewTouchController.onMotionPauseDetected()`（motion pause 时 `goToState(OVERVIEW)`）。改为先 `dispatchOnCancel` + `goToState(NORMAL, false)`，再 `launchFromGesture(mLauncher, true)`；这样直接走我们 Activity 的 fromHome=true 进场（桌面截图背景 + 左滑入 + 模糊渐变）。
36. **拖拽实时进度要单独传**：进度在 `AbsSwipeUpHandler.mCurrentShift`，launcher 侧拿不到。在 `updateSysUiFlags(windowProgress)` 里写静态 `NavRsRecentsActivity.setDragProgress`，`QuickstepLauncher` 的手势 tick 每帧读静态值驱动背景模糊。
37. **手势停驻态由 launcher 内的 Rust 轮播承担**：`RecentsOverlay` 在 `BACKGROUND_APP` 安装，`RecentsView.beginDragReturn(0)` + 每帧 `setDragReturnProgress(1-progress)` 驱动前卡与邻居；松手 `OVERVIEW` 时 `settleDragReturn`。这样拖拽和停驻都在 launcher，Activity 不参与手势。
38. **长滑回桌面要单独判**：`calculateEndTarget` 得到 `HOME` 时置 `setHomeRelease(true)`；`QuickstepLauncher` 在 `onStateSetStart(NORMAL)` 调 `rampOutNavRsDragBackdrop()`（模糊 52→0、alpha→0、320ms）配合系统 app→图标→归位。
39. **不要用 SurfaceControl.setAlpha 去隐藏 leash**：运行卡的 leash 每帧由 shell 重设；且在 `onRecentsAnimationFinished` 时 leash 可能已 release，`setAlpha` 会 NPE 崩溃（实测 `mNativeObject ... is null`）。要同步邻居，用 `TaskViewSimulator.getCurrentRect()` 拿真实矩形，在 Java 侧做分组变换；拖拽时跳过绘制我们的前卡，由 shell 的 live tile 当前卡。

---

## 8. 本轮审计结论（重点）

### 已确认正确
- **任务 ↔ 截图 ↔ 启动**：用 taskId 三元绑定，日志可见 `card i id=... pkg=...`、`tap card i id=...`、`launched task ...`；实测点最前/左侧卡片进入的 app 与截图一致。
- **缩略图裁剪**：状态栏 77 + 导航栏 132 被裁掉；卡片比例 0.5536 与裁剪图一致；圆角 13%。
- **动画锚点**：`contentTop/contentBottom` 来自 `getInsetsIgnoringVisibility`；窗口 `fitInsetsTypes(0)` 后，inset 变化不会移动窗口；放大终点和缩小首帧都固定在内容区。
- **背景**：launch/dismiss 两处绘制都不再填充不透明色；自绘高斯壁纸在所有测试路径都正确显示（运行时存储权限全关也能工作）。
- **三键/面板**：系统三键（Taskbar）常驻顶层，back/recents/home 均实测可用；长按多任务把面板路由到当前 Recents Activity。

### 本轮发现并已修
1. **`drawDismiss` 仍是黑底**（`drawColor(0xFF0A0D12)`）：上滑销毁时背景会黑掉 → 已改透明。
2. **`appSnapshot` 死代码**：`setupSnapshotView()` 在 contentTop/contentBottom 还是 0 时创建（margins 0），且该 view 永远 INVISIBLE；已整段删除，避免以后误用。
3. **三键下滑（你看到的是 Rust 样式）**：根因是 Activity 里 `hide(navigationBars())` 收起了 Taskbar surface。已改为不隐藏导航栏、Activity 不再自绘 NavBarView；长按面板通过 `NavRsRecentsActivity.current()` 路由。
4. **进场闪壁纸 / 黑卡**：壁纸层与卡片层改为进场那一帧才显示，且等 cache 缩略图；60fps 抽帧验证无黑帧、无纯壁纸帧。
5. **壁纸失效（桌面未模糊 / 变成 app 截图）**：改用 privileged `READ_WALLPAPER_INTERNAL`；去掉 `FLAG_SHOW_WALLPAPER`；读取失败用深色兜底。
6. **`cornerPx()` 字段/方法**残留（现在用原生比例）——无功能影响，可下次清理。
7. **`RecentsTuningPanel.sTarget` 是静态引用**：面板打开时指向某个 Activity 的 RecentsView；Activity 销毁后面板随 decor 一起销毁，实际风险低，后续可改弱引用。
8. **桌面进后台动画**：新增 `fromHome` 检测（`TopTaskTracker...isHomeTask()`）+ `startSlideInFromLeft()`；桌面按多任务时牌面从左侧滑入，不再用最近 app 截图盖满再缩小。
9. **空白处交互改写**：删除下滑退出，改为点击空白处退出；同时修复 `touch_peel` 竖滑误判为点击（踩坑 25），否则竖滑会误启动卡片。
10. **退出转场改为左滑 + 背景溶解**：点空白后牌面从左侧滑出画面、背景淡出到桌面，替代默认的向下关闭转场（踩坑 27）。
11. **背景改为桌面整屏高斯模糊**：`captureDisplay` 截取壁纸+图标+组件，`RenderEffect` 半径随进退场动画 0↔52 同步变化（踩坑 28）。
12. **多任务键与空白处统一**：桌面来源时两者都走 `startSlideOutToLeft()`；app 来源时多任务键走 `startLaunch(0)` 放大回当前 app，空白处仍左滑退出（踩坑 29）。
13. **手势导航背景与卡顿**：手势路径补「模糊壁纸 + scrim」背板、`RecentsView` 透明化、隐藏 stock 面板（踩坑 30）。
14. **手势 overview 改为独立 Activity**：`AbsSwipeUpHandler` 把 RECENTS 改判为 LAST_TASK，`resumeLastTask()` 后启动 `NavRsRecentsActivity`；手势模式下不再安装 legacy overlay（踩坑 31–32）。
15. **手势拖拽隐藏 stock 面板**：`QuickstepLauncher` 在手势期间每帧隐藏 `overview_panel`，拖拽只保留 shell 的 app live tile + 壁纸（踩坑 34）。
16. **桌面小横条上滑进入我们的 recents**：`NoButtonNavbarToOverviewTouchController.onMotionPauseDetected()` 改为启动 `NavRsRecentsActivity(fromHome=true)`，不再进 stock overview（踩坑 35）。
17. **拖拽背景模糊渐变**：拖拽进度经静态传递到 launcher，手势 tick 每帧更新模糊壁纸半径 0→52（踩坑 36）。

### 仍待确认/待办
- **旧 overlay 路径**：`QuickstepLauncher.onStateSetStart/End` 仍会挂 `RecentsOverlay`；当前 Recents 键不走它，但如果使用手势导航进 overview，可能出现旧 overlay。需要决定是保留作为回退还是彻底移除。
- **整包 OTA 未重打**：当前是 push APK 迭代；UI 定稿后再 `m bacon` 出 zip（约 3 小时）。
- **极端空缩略图**：任务从未渲染过时 cache 也返回空，700ms 兜底会在无图状态下开始缩小；可加 app icon 兜底。
- **偶发首次按多任务被吞**：脚本化 `am start` 后立刻按 Recents 时，ATM 偶发返回 `START_CANCELED`（result code=2）；真实「卡片↔后台」循环实测稳定，暂不处理。

---

## 9. 验证记录（本轮/前几轮真机）

```
# 本轮（不隐藏导航栏 + 进场门槛 + privileged 壁纸）
NavRS: front thumb src=cache 720x1300 luma=166
NavRS: entry start reason=thumb t=198ms frontThumb=true
NavRS: tuning panel shown (activity)
NavRS: recents activity launched task 329
dumpsys window: InsetsSource type=navigationBars ... visible=true
dumpsys package: READ_WALLPAPER_INTERNAL granted=true / READ_EXTERNAL_STORAGE granted=false / READ_MEDIA_IMAGES granted=false
```

第 11 轮（点击空白退出 + 桌面左侧滑入 + touch_peel 竖滑修复）：
```
OverviewCommandHelper: ... skipped system overview (fromHome=true)
NavRS: entry start reason=thumb fromHome=true t=154ms frontThumb=true
OverviewCommandHelper: ... skipped system overview (fromHome=false)
NavRS: entry start reason=thumb fromHome=false t=166ms frontThumb=true
```
- 手势实测：tap 顶栏空白(200,250) → 焦点回桌面；顶栏竖滑(540,150→520) → 仍在 recents 且无 `launched task`；卡上竖滑(540,1100→1550) → 无启动；tap 卡片 → `launched task 410`；上滑 → `swipe-up hit card 0` / `dismiss idx=0 before=4 after=3`。
- 桌面→后台抽帧：纯壁纸帧(YAVG 52, bright 0%) 后，卡片亮像素占比 2.6%→48.2% 单调上升（约 420ms），卡片从画面左侧进入。

第 12 轮（点空白退出：左滑 + 背景溶解）：
- 抽帧（60fps）：卡片右缘 `maxx 906→0`（约 200ms）滑出左侧；紧接着 `YAVG 52→97`（约 180ms）背景溶解到桌面；全程无静止壁纸段、无向下关闭转场。
- 回归：桌面进后台 `fromHome=true t=63ms`；点卡片 `launched task 490`（Contacts）。

第 13 轮（桌面整屏模糊 + 进退场模糊动画 + 多任务键统一）：
```
NavRS: home shot 1080x2160
OverviewCommandHelper: ... (fromHome=true)
NavRS: entry start reason=thumb fromHome=true t=175ms frontThumb=true
# 桌面重按多任务：焦点回 QuickstepLauncher（走左滑，不是放大）
OverviewCommandHelper: ... (fromHome=false)   # 重按时的重算值，被忽略
# app 重按多任务：
NavRS: entry start reason=thumb fromHome=false t=158ms
NavRS: recents activity launched task 536      # Jelly，放大回当前 app
```
- 桌面进场抽帧：背景条带清晰度 `2.90→0.87` 单调下降（约 420ms），卡片同时从左侧进入；f012 可见背景是锐利的搜索栏/图库/ Dock，f025 为同一桌面强模糊。
- 桌面退出（多任务键）抽帧：卡片 `maxx 906→0`，背景清晰度 `0.82→2.83` 同步上升到锐利；卡片消失时桌面刚好清晰。
- app 退出（多任务键）：f050 可见 Jelly 卡片放大到内容区，随后 `launched task 536`；app 来源点空白仍回原 app。

第 14 轮（手势导航：背景模糊 + 去重复绘制）：
```
NavRS: recents overlay installed n=6 hideStock=false enter=true
StatsLog: LAUNCHER_OVERVIEW_GESTURE
```
- 背景像素由 `(10,13,18)`（`0xFF0A0D12` 不透明）变为高斯模糊壁纸（teal），黑底消失。
- `dumpsys gfxinfo com.android.launcher3`：一次手势 overview 共 64 帧，janky 1（1.56%），99th 17ms，Missed Vsync 0。
- 手势导航需真机切换：`cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`（仅 `navigation_mode=2` 不够，overlay 仍可能是 threebutton）。

第 15 轮（手势 overview 完全走独立 Activity）：
```
NavRS: launch from gesture fromHome=false
NavRS: entry start reason=thumb fromHome=false t=207ms frontThumb=true
# 无 "recents overlay installed"、无 "toState: Overview"
```
- 手势松手后：焦点 = `NavRsRecentsActivity`；任务栈里 app（Jelly #536）visible=true、home visible=false。
- 点空白退出：焦点回到 app（Jelly），不是桌面。
- 连续 3 次「app→手势→后台→点空白→app」循环全部正确，无 FATAL/崩溃。
- 拖拽过程仍是系统 shell 动画（app + 上一个任务）；独立 Activity 在松手后接管（见 5.4 固有限制）。

第 16 轮（手势拖拽隐藏 stock 面板）：
- 拖动中截帧：只剩运行 app（Jelly）的活动缩略 + 壁纸，**没有**上一个任务卡片。
- 3 次循环：手势 → `NavRsRecentsActivity`，点空白 → Jelly；无 FATAL/崩溃。
- `dumpsys gfxinfo`（3 次手势合计）：345 帧，janky 18（5.22%），99th 27ms，Missed Vsync 3。
- 按 HOME 后桌面正常（stock panel 已恢复 VISIBLE）。

第 17 轮（桌面小横条上滑 → 我们的 recents）：
```
NavRS: launch from gesture fromHome=true
NavRS: entry start reason=thumb fromHome=true t=158ms frontThumb=true
```
- 桌面小横条上滑按住：焦点 `NavRsRecentsActivity`；截图为模糊桌面（搜索栏/Dock 均模糊）+ 左侧滑入的卡片；点空白 → 回桌面。
- 不再出现 stock overview / stock 卡片。普通快速上滑仍是 All Apps 抽屉（见 5.4 说明）。

第 18 轮（app 拖拽背景模糊渐变）：
- 浅拖（y≈1700）截帧：背景清晰（桌面正常）。
- 深拖（y≈650）截帧：背景明显高斯模糊；运行 app 的活动缩略随手指缩小。
- 松手 → `NavRsRecentsActivity`；点空白 → 回原 app。

第 19 轮（手势全程 launcher 自绘）：
```
NavRS: recents overlay installed n=8 hideStock=false enter=true
StateManager: goToState - fromState: Background, toState: Overview
StatsLog: LAUNCHER_HOME_GESTURE   # 长滑
```
- 拖拽中截帧：前卡跟手缩小，邻居卡片从两侧滑入，背景高斯模糊。
- 松手后焦点仍是 `QuickstepLauncher`（停驻在 launcher，不再启动 Activity）；长滑回桌面且模糊背板渐隐。
- 注：短滑用 adb 注入手势测试不稳定，建议真机手指确认；三键路径与桌面小横条上滑(→Activity) 未受影响。

第 20 轮（邻居与当前卡精确同步）：
- 用 `TaskViewSimulator.getCurrentRect()` 作为真实运行卡矩形，`drawLaunch` 对整副牌做分组变换（settled→当前），拖拽时跳过绘制我们的前卡（shell live tile 即前卡）。
- 浅拖 vs 深拖截帧：当前卡与左右邻居（Jelly/PiliPala）一起缩放/左移，间距随当前卡同步，不再是邻居停驻。
- 当前构建无崩溃；上一版 `SurfaceControl.setAlpha` 隐藏 leash 的 NPE 已通过移除调用解决（踩坑 39）。
- 仍待办：桌面小横条上滑"卡片弹出又缩回"（home→Activity 被手势打断）。

- 60fps 录屏抽帧（screenrecord + ffmpeg，按 YAVG 亮度）：app→后台、桌面→后台都没有黑帧/纯壁纸帧；t≈144–198ms cache 缩略图到达并一次性覆盖。
- 卡片→app：`startLaunch` 最后一帧覆盖内容区，紧接着 app 窗口出现，没有第二次转场。
- 系统三键（Taskbar）：back 关闭后台、recents 开关后台、home 回桌面；`navigationBars visible=true` 全程不变。
- 壁纸：运行时存储权限全关，后台背景仍是桌面壁纸的高斯模糊；没有出现 app 截图背景。
- 上滑销毁：任务真实移除；下滑/返回/再按多任务：回 app。
- 长按多任务：面板在后台界面内弹出（`tuning panel shown (activity)`），样式 chip 切换实时生效（mode 5↔0 实测）。

历史日志：
```
NavRS: recents overlay installed n=8 hideStock=false enter=true
NavRS: recents model order: 26,36,61,56,35,62,53,71
NavRS: first card id after sort: 71
NavRS: card 0 id=71 pkg=com.android.documentsui
NavRS: tap card 1 id=53
NavRS: launch task 53 without transition: true
OverviewCommandHelper: navrs recents activity - skipped system overview
NavRS: activity tasks n=7 / task 0 id=68 Jelly
NavRS: recents activity launched task 68
```

---

## 10. 构建 / 部署 / 快速迭代

```bash
# 编译（热缓存约 2 分钟；新增文件/manifest 变动约 20+ 分钟）
/root/navrs_fast.sh

# 推 APK（Java 变化）
adb root && adb remount
adb push out/Launcher3QuickStep.apk /system_ext/priv-app/Launcher3QuickStep/
adb shell am force-stop com.android.launcher3

# Rust 库变化
adb push out/libnav_rs.so /system/lib64/libnav_rs.so

# manifest / 主题资源变化
adb reboot
```

Rust 库：
```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME="$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018"
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 29 build --release
# 产物拷贝到 packages/apps/Launcher3/navrs/libnav_rs-arm64.so / -arm.so
```

- `libnav_rs` 在 AOSP 里是 **prebuilt**（`packages/apps/Launcher3/navrs/libnav_rs-*.so`，被 `Android.bp` 的 `cc_prebuilt_library_shared` 引用）；`m Launcher3QuickStep` **不会重编 Rust**。改 Rust 路径：先 `cargo ndk` 出 `.so` 覆盖 prebuilt → 再 `m`。
- 生成的 APK **不含** `lib/arm64/libnav_rs.so`（zip 里无任何 `.so`），运行时从 `/system/lib64/libnav_rs.so` 加载。所以只改 Rust 时，push `/system/lib64/libnav_rs.so` 即可，APK 不变；push 后 `am force-stop com.android.launcher3` 重载。

---

## 11. 一句话总结

现在这块后台是「**独立 Activity + Rust 轮播 + taskId 绑定 + 内容区几何**」的完整闭环：任务、截图、启动、销毁、三键、调节面板、壁纸模糊、进出动画都在我们自己的代码里；进场分两条路（app→后台反向缩小、桌面→后台左侧滑入），背景是**桌面整屏截图的高斯模糊**并随进退场同步变模糊/变清晰，空白处点击与多任务键在桌面来源下走同一套左滑退出。剩下主要是**底部三键在 inset 动画中的位置稳定性**和**整包 OTA 重打**两件事。
