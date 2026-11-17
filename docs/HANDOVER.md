# NavRS 后台（Recents）交接文档

> 更新：2026-11-14 11:35 · 设备 ADB 序列号 `6c17a27d`（Xiaomi Mi MIX 2S / polaris，LineageOS 22.2 / Android 15）
> **已部署**：`patch_batch8.py` 已编译并推送（APK `5f83b3c8…` / odex `9041fbdb…` / vdex `4430466e…`），
> 第 10 节是本轮实测结论；第 7 节第 1、3 条仍需真手指复测。
> 当前部署版本：APK `cf91e9751d3b8ef17bc39eaa529a1643`（/system_ext/priv-app/Launcher3QuickStep/），
> libnav_rs.so `72b2c21d1658652285bc796f30e0cb8d`（/system/lib64 与 priv-app/lib/arm64 各一份）

---

## 0. 一句话现状

手势导航下的“后台（Overview）”由我们自己的一套 Rust+Java 渲染替代 stock：
- **从 app 上滑**：短触摸（<200ms）直接回桌面、不出现卡片；慢推 1/10 屏内回 app；1/10~1/2 进后台；>1/2 回桌面（卡片飞图标）。
- **从桌面小横条上滑**：独立 Activity（NavRsRecentsActivity）呈现后台，卡片从左滑入，背景是**含图标/组件的桌面截图**高斯模糊。
- 后台内**上滑**：卡片整组左移出屏，模糊同步由强到清。
目前功能主链路可用，仍有 3 个稳定性/一致性问题在最后一节列出。

---

## 1. 资料与代码位置

| 内容 | 位置 |
| --- | --- |
| Android 源码树（WSL） | `/root/android/lineage`（ext4，勿放到 /mnt/c） |
| Launcher 工程 | `/root/android/lineage/packages/apps/Launcher3` |
| **navrs 独立模块（主要改这里）** | `packages/apps/Launcher3/navrs/src/com/wzy/navrs/` |
| navrs 模块构建脚本 | `packages/apps/Launcher3/navrs/Android.bp` + `navrs/AndroidManifest.xml` |
| launcher 侧桥接层（很少改） | `quickstep/src/com/android/launcher3/navrs/NavRsBridge.java`、`NavRsOverlay.java`、`NavRsActivity.java`、`NavRsView.java` + `NavRsIntegration/NavBarView/TaskbarBridge`（taskbar 三件套仍留在 launcher 模块） |
| Rust 工程 | `C:\Users\wzy\Documents\Qoder\2026-11-01\29ef2f7a\nav-rs`（另有镜像副本 `C:\Users\wzy\Documents\nav-rs-上滑销毁修正版`，改完要同步） |
| 架构笔记（历史） | `navrs-recents-architecture.md`（早期版本，含大量踩坑记录，可参考但部分已过时） |
| 历史备份 | `backup_20261101/`、`backup_20261103/`、`backup_20261103_round13/` |

### navrs 模块文件（5 个）
- `RecentsView.java`：卡片绘制/手势/进场/退出/launch/dismiss/handoff（~1700 行，主文件）
- `RecentsOverlay.java`：把我们的 RecentsView 挂到 launcher decor、缩略图加载、背景模糊层
- `NavRsRecentsActivity.java`：独立后台 Activity（从桌面/3 键进入时用）
- `NavRsPrefs.java`：样式参数（mode 固定 5 = gear，pitch_by_card 等）
- `RecentsTuningPanel.java`：长按导航栏“方块键”弹出的调节面板（已删得只剩动画速度）
- `NavRsHostImpl.java`：实现 launcher 侧 `NavRsBridge.Host`，反射注册

### Rust 端
- `nav-rs/src/recents.rs`：布局 + 触摸 + 弹簧（gear 牌堆、吸附、拖拽增益）
- `nav-rs/src/deck.rs`：给 Java 的分档接口（三档阈值/进度归一化，目前 Java 已自带一套，deck.rs 仅留接口）
- `nav-rs/src/lib.rs`：导航栏 + JNI 导出

---

## 2. 产物位置（编译/部署物）

| 产物 | 本机路径 | 设备路径 |
| --- | --- | --- |
| APK | `out/Launcher3QuickStep.apk` | `/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk` |
| Rust 库 | `nav-rs/out/arm64-v8a/libnav_rs.so` | `/system/lib64/libnav_rs.so` 和 `/system_ext/priv-app/Launcher3QuickStep/lib/arm64/libnav_rs.so` |
| privapp 白名单 | `out/com.android.launcher3.xml` | `/system_ext/etc/permissions/com.android.launcher3.xml` |
| 探针日志 | `probe.log`、`probe2.log`…`probe7.log`（logcat `NavRSProbe/NavRS`） | — |
| 截图/录像样本 | `caps/`、`frames1/`、`frames2/`、`rec1.mp4/rec2.mp4`（仅作视觉确认，不作为判断依据） | — |

---

## 3. 构建与部署

### 3.1 编译
```bash
# 脚本：/root/navrs_fast.sh
cd /root/android/lineage
source build/envsetup.sh; breakfast polaris
m -j14 Launcher3QuickStep privapp_whitelist_com.android.launcher3
# 产物自动拷到 C:\...\29ef2f7a\out\
```
- **只改 navrs 模块 → 约 54 秒**；改 launcher 侧文件 → 约 2 分钟；
  首次新增/删除 `Android.bp` 模块会触发全树 re-glob（本机 WSL 12GB 内存会 swap，可能 20 分钟以上，属一次性）。
- Rust：
```bash
cd C:/Users/wzy/Documents/Qoder/2026-11-01/29ef2f7a/nav-rs
set ANDROID_NDK_HOME=C:/Users/wzy/AppData/Local/Android/Sdk/ndk/27.2.12479018
cargo ndk -t arm64-v8a -o out build --release      # 约 5 秒
```

### 3.2 部署（每次都要 root + remount）
```bash
adb -s 6c17a27d root
adb -s 6c17a27d remount
adb -s 6c17a27d push <apk> /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
# 若改了 Rust：
adb -s 6c17a27d push <so> /system/lib64/libnav_rs.so
adb -s 6c17a27d push <so> /system_ext/priv-app/Launcher3QuickStep/lib/arm64/libnav_rs.so
adb -s 6c17a27d shell 'chmod 644 ...; restorecon ...'
adb -s 6c17a27d shell 'am force-stop com.android.launcher3; input keyevent KEYCODE_HOME'
```
注意：重启后 adbd 变回 shell，必须 `adb root`；`/system_ext` 只读，必须 `remount`。

---

## 4. 架构

### 4.1 三条入口
1. **从 app 上滑（手势）**：SystemUI/Shell 起 recents 动画 → Launcher 进入 `BACKGROUND_APP`
   → `QuickstepLauncher.startNavRsDragHide()` 立即隐藏 stock 面板、装模糊墙
   → `RecentsOverlay.installNow()` 把我们的 `RecentsView` 挂到 decor 顶层
   → 每帧 tick 把 `dragProgress` 喂给牌堆（`setDragReturnProgress(1-progress)`、`setLongOut`）
   → 松手按 **触摸时长 + 位移** 分档（见 4.4）。
2. **从桌面小横条上滑**：`NoButtonNavbarToOverviewTouchController.onMotionPauseDetected()`
   → `NavRsRecentsActivity.launchFromGesture(launcher, fromHome=true)` 起独立 Activity（透明主题）
   → `NavRsRecentsActivity` 用 `captureDisplay` 截当前桌面做背景，卡片从左侧滑入。
3. **3 键导航多任务**：`NavBarView`（Rust 导航栏）→ 起同一个 `NavRsRecentsActivity`（fromHome=false）。

### 4.2 关键数据流
- 卡片几何**唯一来源**是 Rust：`nativeCardQuad()` 返回 [l,t,r,b,corner,skew]；Java 只负责把缩略图贴进这些矩形。
- 缩略图：`RecentsOverlay.windowAspectThumb()` —— **整窗比例**，状态栏/手势条两条带用 app 边缘色涂掉（不裁切），
  这样卡片尺寸与窗口动画的卡（716×1432）一致。手势路径另有一个“运行中 app 实时截图”钉在前卡（`screenshotTask`）。
- 背景：手势路径优先用 `sNavRsDesktopShot` —— `captureNavRsDesktopShot()` 在 NORMAL 状态下调
  `NavRsActivity.captureHomeShot()` 截 display（含图标/组件），结果缓存在静态字段；只有截图拿不到时才
  回退 `WallpaperManager.getDrawable()`（日志 `drag wall shot=true/false wallpaperFallback=…`）。
  指纹：`desktop shot … mean=… var=…`，var 接近 0 说明截到的是纯壁纸而不是桌面。
  + RenderEffect 高斯模糊；Activity 路径用自己的 `captureDisplay` 截图。

### 4.3 时序常量
| 名称 | 值 | 含义 |
| --- | --- | --- |
| `SLIDE_IN_MS` | 420 | 桌面进入后台，卡片左→右滑入 |
| `SLIDE_OUT_MS` | 280 | 后台退出（卡片整组左移 / 模糊淡出），两者同起同止 |
| `HANDOFF_MS` | 220 | 松手后从 shell 实卡矩形滑到牌堆静止位（`settleDragReturn` 里 `haveRun ? HANDOFF_MS : 160`）|
| wall ramp | 320 | 远滑回桌面时模糊淡出时长 |
| `navrs_quick_ms` | 200 | 触摸时长阈值（<200ms = 快甩直接回桌面） |
| `EXIT_GRACE_MS` | 60 | 退出动画结束后延迟删除 overlay 的宽限 |

### 4.4 三档判定（`AbsSwipeUpHandler.calculateEndTarget`，方法最前面）
```
触摸时长 < quick(200ms)                      → HOME（直接桌面，牌堆 alpha 全程 0，不显示卡片）
否则 shift < shortFrac(0.1)×屏高/拖动长度     → LAST_TASK（回 app）
否则 shift < longFrac(0.5)×屏高/拖动长度      → RECENTS（进后台）
否则                                          → HOME（远滑，卡片缩小飞图标 + 模糊淡出）
```
另外**最顶层接管**：只要牌堆已静止 / 独立 Activity 在前台，且上滑 shift>0.04，
就直接调用各自的 `exitToHome()`（卡片左滑 + 模糊同步降 0）并返回 `LAST_TASK`，
绕开所有 stock home 动画（including fling/atomic 路径）。

### 4.5 Rust gear（齿轮）动画数学
- 牌堆从左边缘被“拉出”：`u_i = pitch + peel − pitch·i`
- 投影：`s = 1 − cos(π/2·u)`，卡心 `cx = W/2 + (s − s_rest)·W`（已做屏幕居中，`s_rest` 对应静息位）
- 静止前卡索引：`round(peel/pitch)`；`peel ∈ [0, pitch·(n−1)]`
- pitch = `0.30`（`pitch_by_card` 打开时 = 卡宽/W×1.15）；左邻露出约 181px
- 拖动：`peel = down_peel + dx/W × 0.80`，速度 EMA
- 松手：粘滞阻尼 c=2.4 + 干摩擦 Tf=0.55 滑行；速度 ≤0.25 时吸附到 `round(peel/pitch)·pitch`（k=8, damp=4.5）
- `animating()` 把“偏离整卡位”也算在动画中，避免速度归零后停帧不吸附

### 4.6 模块拆分（2026-11-13）
- 独立模块 `navrs`（android_library，`libs: ["Launcher3QuickStepLib"]`），App 侧 `static_libs` 增加 `navrs`。
- launcher 侧只保留 facade（`NavRsBridge/NavRsOverlay/NavRsActivity/NavRsView`），
  实现类 `com.wzy.navrs.NavRsHostImpl` 由 `NavRsBridge.host()` 反射加载。
- 目的：改动画只编小模块（54s）。taskbar 三件套（`NavBarView/NavRsIntegration/TaskbarBridge`）依赖 launcher 内部类型，未拆。

---

## 5. 可调参数（不用重编译）
```bash
settings put secure navrs_short_frac 0.1   # 短/中分界（屏高比例，默认 0.1）
settings put secure navrs_long_frac  0.5   # 中/远分界（默认 0.5）
settings put secure navrs_quick_ms   200   # 快甩时长阈值（ms）
```
代码内常量：`RecentsView.SLIDE_IN_MS/SLIDE_OUT_MS/HANDOFF_MS`、Rust `DECK_PITCH=0.30`、
`QuickstepLauncher` 里 wall 模糊 52px、ramp 320ms。

---

## 6. 探针与日志判读（tag：`NavRSProbe` / `NavRS`）

```bash
adb -s 6c17a27d logcat -v time -s NavRSProbe NavRS
```
关键行：
| 日志 | 含义 |
| --- | --- |
| `endTarget=… shift=… vy=… dur=… quick=…` | 三档判定结果与依据 |
| `deck shown elapsed=… progress=…` / `deck was never/shown` | 牌堆是否真的显示过（快甩应为 never） |
| `ABS settle-end / ABS tick c0=…` | 所有卡片的**绝对屏幕矩形**（核心证据） |
| `STOCK_ABS s0=…` | stock TaskView 绝对矩形（用于对齐） |
| `slideOut t=… tx=…` / `wallFade t=… a=… r=…` | 退出动画两条时间线（应同起同止） |
| `calcEnd top atomic=… fling=…` + `deck/activity exit takeover` | 顶层接管是否生效 |
| `home pill suppressed after exit` | 退出后 600ms 内抑制再次进后台 |
| `touch ignored: deck alpha=…` | 不可见牌堆已不再吞触摸 |
| `gestureHandler start … launcherState=… overlay=…` | Shell 是否介入该手势 |

约定：**判断只用探针数字（绝对坐标 + 时间戳），不用截图**；每轮改完用中文汇报「问题 / 进展 / 判断」。

---

## 7. 当前 Bug / 待办（按优先级）

> 2026-11-14：已修 3 处（快滑闪背景 / 后台停留 tick 空转 / 每帧重建模糊），见第 10 节。
> 第 1、3 条的入口是 home-pill→`NavRsRecentsActivity`，注入手势进不去，**必须真手指复测**。
> 第 2 条经实测是 deck 自己吃掉上滑后正常退出，未复现。

1. **桌面→后台后的快滑仍可能走 app→home 动画**（真机最需要验证）
   - 注入路径已验证会走 `activity exit takeover`；真机时若 Shell 以 atomic 方式接走手势，
     顶层接管理论上已覆盖，但**尚未用真手指确认**。
   - 若仍复现：读 `calcEnd top …` 与 `activity exit takeover` 是否出现；若根本没进 `calculateEndTarget`，
     需要改 SystemUI 侧（`OtherActivityInputConsumer`/`RecentsTransitionHandler`）入口。
2. **后台里再次中滑会二次进入后台**（已加 600ms 抑制，待真机确认）。
3. **小横条/左右边缘上滑偶发弹出系统后台**（叠层或灰白卡住）
   - 已加：每次进 OVERVIEW 强制隐藏 stock 面板；不可见牌堆不吞触摸。
   - 待复现时抓 `NavRS` 日志确认 stock 面板是否可见、overlay 是否存在。
4. **SystemUI 侧“彻底屏蔽 overview 下的 stock home 动画”** —— 目前靠 overlay 覆盖 + 延迟删除兜底，未在 Shell 层硬屏蔽。
5. Rust 里 mode 0–4 的布局分支（fan/deck/carousel/peel）仍在，Java 已强制 mode=5，属死代码，可清理。
6. 缩略图在部分 app（无 snapshot）时回退到“图标卡”，视觉上与其他卡不一致，可再做统一占位。
7. 早期遗留：`deck.rs` 的 native 分档接口与 Java 内分档重复，建议二选一。

---

## 8. 已知坑（务必先看）

- **WSL 内存**：soong_build 峰值 ~12.5GB，`.wslconfig` 现为 memory=13GB/processors=14/swap=64GB；
  新增/删除 `Android.bp` 会全树 re-glob，可能 swap 30GB、跑 20 分钟；期间 `wsl` exec 可能 `WSAETIMEDOUT`，等待重试即可。
- **adb**：重启后必须 `adb root`；`/system_ext` 只读必须 `remount`；`.so` 推完要 `restorecon`。
- **.so 版本**：`UnsatisfiedLinkError` 基本都是设备上的 .so 未更新；检查 `/proc/<pid>/maps`。
- **do NOT** 在 launcher 的 overview 路径里 `hide(navigationBars())`（导航栏就是 Launcher Taskbar）。
- **不要用 `SurfaceControl.setAlpha`** 操作已释放的 leash（曾导致 NPE）。
- **手势注入局限**：`input swipe` 能进 app，但真机的边缘手势可能先被 Shell 接走；关键路径必须真手指验证。
- 资源 id（`overview_actions_view`、`snapshot`）在 navrs 模块里用运行时按名解析，避免跨模块 R 冲突。

---

## 9. 下一步建议顺序

1. 真机复测第 7 节 1–3 条，收集 `calcEnd top / takeover / slideOut / wallFade / ABS` 日志。
2. 若 1 仍失败：在 Shell 的 `OtherActivityInputConsumer`/`finishRecentsControllerToHome` 加一层“若 NavRS 前台则改为调用 exitToHome 并取消 home 动画”。
3. 清理 Rust 死代码 + 统一缩略图占位。
4. 如果编译时间还要降：把 navrs 的 5 个类进一步拆成“布局层/渲染层”，或提高 WSL 内存并限制 soong jobs。

---

## 10. 2026-11-14 修正（patch_batch8.py，纯 Java，不用重编 .so）

> 已编译并部署：APK `5f83b3c814321f14e772ae74d103ebb2`、odex `9041fbdb…`、vdex `4430466e…`
> （三件一起推，oat 与 apk 必须同批，否则系统会回退校验 apk）。

### 10.1 先更正一条：settle 早就会交回绘制权

`RecentsView.settleDragReturn()` 的 `onAnimationEnd` **当前已经**复位
`launchReturning=false / settledDrag=false / launchFrom=-1 / launchRect=null / launchOthers=null`
并 `nativeSetFrozen(false)+nativeSettle(handle)`。所以 `isSettled()` 会变真、`drawDismiss()` 可达。
上机实测删卡正常（`dismiss idx=0 before=3 after=2` → `dismiss task id=1300 … remaining=2`）。
**早前基于 11/03 快照（RV.java）得出的"isSettled 恒 false / dismiss 不可达"结论是错的，已作废。**

### 10.2 实测到的真问题（本轮已修）

1. **快滑回桌面时背景闪一帧全亮。** tick 的 deliberate 门槛让 wall 停在 alpha 0，
   而退出 ramp 从硬编码 `1f` 起跳：
   `deck was never shown wallAlpha=0.0` → `wallFade t=0 a=1.00 r=52`。
   **改法**：从 `wall.getAlpha()` 起跳；`<=0.01` 时直接移除背景，不跑动画。
   验证：快滑后日志里不再出现 `wallFade` 行。

2. **后台停留期间 tick 不停。** 进 OVERVIEW 只清了 `mNavRsDrivingDeck`，
   `mNavRsDragHideActive` 仍为 true → 60fps 自重投递 + 每 20 帧一条
   `ABS tick …`（实测每 330ms 一条，几何完全不变）。
   **改法**：OVERVIEW 入口 `mNavRsDragHideActive = false`。验证：停留 3 秒 `ABS tick` 计数 0。

3. **每帧新建 RenderEffect** 做亚像素模糊 → 改为整像素台阶（`mNavRsWallRadius` 记忆上次半径）。

4. 顺带：删掉 `View quickRv = …? null : null;` 死变量；`onDetachedFromWindow` 取消 `dismissAnimator`
   （先 `removeAllListeners()`，避免 cancel 触发 `resetDismiss()` 误报删除）。

5. **deck 退出接管 `LAST_TASK` → `HOME`**：原来"回桌面"的动作配"回 app"的结束目标。
   这条**注入路径复现不出来**（deck 自己会吞掉上滑，见下），需真手指确认。

### 10.3 两条入口谁在吃手势（重要）

实测从 app 上滑进后台再上滑退出，**根本不经过 `calculateEndTarget`**：

```
NavRS : edge swipe up (move): closing overview dy=-47.9
NavRSProbe: wallFade t=0 a=1.00 r=52      <- 与 slideOut 同起同止，280ms
NavRSProbe: slideOut end t=288
```

是 `RecentsView.onTouchEvent` 的 edgeSwipeUp 直接调的 `startSlideOutToLeft()`，
所以 §7 第 1 条（"快滑仍走 app→home 动画"）只可能发生在**deck 没吃掉手势**的场合：
atomic、fling、或 home-pill→Activity 那条路径。

### 10.4 现在可以注入 home-pill 那条路径了

`input swipe` 是匀速的，触发不了 `onMotionPauseDetected`；`input motionevent` 可以手动造停顿：

```bash
adb shell "input motionevent DOWN 540 2100; input motionevent MOVE 540 2020; \
            input motionevent MOVE 540 1960; sleep 0.25; \
            input motionevent MOVE 540 1955; input motionevent UP 540 1955"
```

batch12 之后 0.25s 停顿就能进（原来要 0.4s），见 §11.2。

---

## 11. 2026-11-15 四项体验修正（batch12/13）

### 11.1 从桌面进后台后，上滑退出变成了"缩成图标飞回桌面"

`AbsSwipeUpHandler` 的 absorb 分支在 batch11 里对两条路径都返回 `HOME`，于是 Shell 走了
stock 的 swipe-to-icon 动画。**Activity 路径**（桌面小横条进入）其实已经自己播完
"卡片左移 + 背景变清晰"，Shell 再动一次就是重复动画。

改法：activity 路径返回 `LAST_TASK`（取消手势，什么都不动画），deck 路径保留 `HOME`。
判据用 `NavRsActivity.recentlyExited() || isOpen()`。

验证（22:20:46）：

```
NavRS   : edge swipe up: closing overview
NavRSProbe: slideOut end t=309
NavRSProbe: exit in flight -> absorb gesture -> LAST_TASK(activity) shift=1.66 anim=false recent=true
（日志里没有 homeFactory 那一行 = 没有走图标飞行动画）→ 落点 QuickstepLauncher
```

### 11.2 小横条上滑要滑很远才进后台

两个闸门一起放松：

| 位置 | 原值 | 新值 |
| --- | --- | --- |
| `NoButtonNavbarToOverviewTouchController.mMotionPauseMinDisplacement` | 1×touchSlop | 0.5×touchSlop |
| `MotionPauseDetector` 的静止判定 | 300ms（`FORCE_PAUSE_TIMEOUT`） | 180ms（新增 `setForcePauseTimeoutMs`）|

验证：`input motionevent` 的停顿从 0.4s 降到 **0.25s** 仍能稳定进后台。

### 11.3 中滑进后台时前卡有一帧空窗（透出下面那张卡）

`drawLaunch()` 在"Shell 的 live tile 应该在这一格"时会 `continue` 跳过前卡（`skip-live`）。
Shell 释放 tile 比我们的 settle 早若干帧，那几帧该位置是空的，露出左邻卡。

改法：**照画，不跳过**。live tile 在独立 SurfaceControl 上、位于 launcher 窗口之上，
所以顶着它画是不可见的，一旦它消失我们这张卡已经在原位。日志从 `skip-live` 变 `under-live`。

验证：一次中滑后 `skip-live`=0、`under-live`=35，截图前卡完整、左邻只露边。

### 11.4 三键导航间距太远、贴着屏幕角

Rust `lib.rs` 的 nav bar 布局原来是三等分整条 bar（中心 180 / 540 / 900）。
新增 `BTN_BAR_FRACTION = 0.66`，把三个按钮收进中间 66%：

| | 按钮中心 (1080 宽) | 相邻间距 | 到屏幕边缘 |
| --- | --- | --- | --- |
| 改前 | 180 / 540 / 900 | 360 | 180 |
| 改后 | 302 / 540 / 778 | 238 | 302 |

触摸区（`Button.rect`）同步收窄，误触面积一起变小。`.so` 需一起推两个路径
（`/system/lib64/` 和 `priv-app/lib/arm64/`）。

> 观察（未定论）：`settings put secure navigation_mode 0` 切到三键时，屏幕中间会多出一条
> 长条（疑似 launcher taskbar 的 nav handle），切回手势模式后消失。可能是切换瞬间的过渡态，
> 也可能是本就有；真机切换一次确认即可。

### 11.5 当前部署

APK `03faed3ed5b901e34c04aeb7d616d670`（batch13）、
`libnav_rs.so` `3dbc3e44419ef7c37f26fdf49a0c52b9`（batch12 的 Rust 布局）。

> 注意：absorb 那行日志在 batch12 之前写成 "-> HOME" 但实际返回 LAST_TASK，
> batch13 已改为 `-> LAST_TASK(activity)` / `-> HOME(deck)`。

---

## 12. 2026-11-15 第二轮（batch14）

### 12.1 后台里上滑会把整个界面“变成卡片”

原因在 Shell 侧：`TouchInteractionService.onInputEvent` 在 DOWN 时就为“底部上滑”创建
`OtherActivityInputConsumer`，随后 `startTouchTrackingForWindowAnimation` 启动 recents 动画，
把**当前顶层窗口（我们的 NavRsRecentsActivity）**当成 app 缩成卡片。

改法：在创建消费者之前加一条分支——`isInSwipeUpTouchRegion && NavRsActivity.isOpen()`
时 `mUncheckedConsumer = InputConsumer.NO_OP`，不建消费者、不启动动画，手势留给 activity
自己（deck 的 edgeSwipeUp 会滑出）。

验证（23:06:40）：

```
NavRS     : edge swipe up (move): closing overview
NavRSProbe: slideOut end t=289
（没有 gestureHandler start / stateStart Background / absorb —— Shell 完全没介入）→ QuickstepLauncher
```

> 踩坑：`com.wzy.navrs.NavRsRecentsActivity` 在 quickstep 模块里不可见（编译报 cannot find symbol），
> 必须用同模块的 facade `com.android.launcher3.navrs.NavRsActivity`。

### 12.2 后台背景有时是实时画面而不是模糊桌面

`onStateSetEnd(NORMAL)` 后 500ms 截屏，只校验了 LauncherState。这 500ms 里如果 app 已经起来，
截到的就是那个 app，存进 `sNavRsDesktopShot`，下次当“桌面”用。

改法：延迟 900ms + 要求 `hasWindowFocus()`（app 在前台时 launcher 没有焦点）+ 失败重试一次；
`captureNavRsDesktopShot()` 内部也加了同样的守卫。

验证：从 app 进后台，`drag wall shot=true wallpaperFallback=false`，截图背景是模糊的桌面
（能辨认出模糊的图标和 dock）。

### 12.3 三键键位看不见 / 与底栏同色

原来底栏完全透明，键色取框架的 dark-intensity 管线（`NavbarButtonsViewController.applyButtonColors`），
浅色壁纸下会出现白键白底。

改法（用户提的方案）：
- Rust `lib.rs` 新增 `nativeSetBarColor`，把整条 bar 用给定 ARGB 铺底（alpha 0 = 透明）。
- Java `NavBarView` 在 `onAttachedToWindow` 后台线程解码壁纸，**采样底部 1/8、水平中间 60% 的平均色**，
  取 `0xE0` 透明度作为底栏色，再按该色亮度选键色（lum>140 → 深色键，否则白键）。
- `setIconColor()` 改为：一旦算出对比色，就以对比色为准，不再被框架颜色覆盖。

实测（暗色壁纸）：`bar colour #1a272c lum=37 icon=light` → 深色底栏 + 白键，三个键清晰可见。

> 未实测：浅色壁纸 → 亮底栏 + 深键。逻辑对称，但需要换张亮壁纸确认一次。

### 12.4 换导航模式的正确姿势（重要）

这台机器上 `settings put secure navigation_mode 2` **不生效也不持久**——SystemUI 重启后会被
overlay 写回 3 键。必须走 overlay：

```bash
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb shell "cmd overlay list" | grep navbar      # 确认 [x] 在哪一项
```

排查时先看这个，否则会误判成“手势被吞掉/代码坏了”（我今晚就绕了半小时）。

### 12.5 当前部署

APK `3a488530be82c138661b8b97e534087b`、`libnav_rs.so` = batch14 版（含底栏铺底）。
