# 更新记录

> 本项目按「批次」迭代，每批都是「改源码 → 编译 launcher → 推机器 → 录屏/日志验证」。
> 详细的现场记录见 [docs/HANDOVER.md](docs/HANDOVER.md)。

## batch 15 · 背景重做、状态栏与底栏取色、进后台性能

- **背景不再跑 RenderEffect**：新增 `NavRsBlur`（降采样 + 三次 box blur + 放大），
  模糊一次性烘焙；进入时用 `sharp / blur / scrim` 三层交叉淡入替代「模糊半径动画」，
  观感一致但每帧零成本。
- **桌面截图离开主线程**：`captureNavRsDesktopShot()` 改到工作线程，并在同一线程烘焙模糊
  （`sNavRsDesktopBlur`）。原先的同步全屏回读会卡主线程 30–120ms。
- **三键按下的截图异步化**：`OverviewCommandHelper` 里 `captureHomeShot()` 改到独立线程，
  按键不再等截图。
- **状态栏图标不再「先渐隐」**：新窗口在 `onCreate` 用 `WindowManager.LayoutParams.appearance`
  先声明与当前 app 顶栏一致的深浅，等前卡滑出状态栏（`RecentsView.setOnEntryTopCleared`，
  p≈0.72）后，再按背景顶带亮度重设一次。
- **壁纸解码移到工作线程**（仅在无截图缓存时兜底）。
- **底栏跟随前台 app 取色**：`NavBarView.sampleAppBarColor()` 对 app 截图导航栏上方一带取平均色，
  优先级 app 采样 > 壁纸采样；回桌面时 `clearAppBarColor()`。

## batch 14 · 后台内上滑、桌面截图可信度、三键取色

- `TouchInteractionService`：后台 Activity 在前台时，底部上滑不再创建
  `OtherActivityInputConsumer`（否则整屏后台被缩成卡片），直接 `NO_OP`。
- 桌面截图：`NORMAL` 后 900ms + `hasWindowFocus()` 双闸门 + 失败重试一次；
  `captureNavRsDesktopShot()` 内部同样校验，避免把刚起来的 app 当桌面缓存。
- 三键栏铺底：Rust 新增 `nativeSetBarColor`，`NavBarView` 采样壁纸底部 1/8（水平中间 60%）
  平均色作底栏色（α=0xE0），按亮度选深/浅按键，解决「白键白底」。
- 三键收进屏幕中间 66%（`BTN_BAR_FRACTION=0.66`）：按钮中心 180/540/900 → 302/540/778。

## batch 12 / 13 · 落点、阈值、空窗

- absorb 分支：activity 路径返回 `LAST_TASK`（取消手势），deck 路径保留 `HOME`，
  去掉「缩成图标飞回桌面」的重复动画。
- `mMotionPauseMinDisplacement` 1 → 0.5 slop；`MotionPauseDetector` 新增
  `setForcePauseTimeoutMs()`，300 → 180ms（小横条上滑更容易进后台）。
- `drawLaunch()` 的 `skip-live` 改为 `under-live`：Shell 释放 tile 前照画前卡，
  修掉中滑进后台时「前卡空一帧露出左邻卡」。

## batch 9 / 10 / 11 · 退出后二次进入后台

- 退出动画在跑时不落 `RECENTS`；新增 `recentlyExited()`（600ms）兜底；
  `startNavRsDragHide()` 里若 `NavRsActivity.isOpen()` 直接 return。
- 合并 batch9/10 的临时探针，去掉重复的 `goToState` 调用（曾导致整屏冻结）。

## batch 8 · 快滑闪帧与 tick 开销

- 退出 ramp 从 `wall.getAlpha()` 起跳，修「快滑时闪一帧」。
- `OVERVIEW` 入口把 `mNavRsDragHideActive=false`，停掉 60fps tick。
- 模糊半径取整像素台阶，减少 RenderEffect 重算次数。
- 删死变量、detach 时取消 `dismissAnimator`。

## batch 1 – 7 · 打地基

- 把 `RecentsView` 从单体拆成「Rust 几何 + Java 合成」两层；
- 卡片宽高比 / 内容区对齐到窗口（去掉系统栏后的 aspect）；
- 手势链路接入：`AbsSwipeUpHandler` 每帧写 `dragProgress`，`RecentsOverlay` 在 launcher
  窗口里并行绘制 deck；
- 齿轮式堆叠布局（mode 5）收敛为唯一布局，其余五种布局移除；
- 三键栏首次接入 Taskbar（`NavRsIntegration`），事件转发到 `TaskbarNavButtonController`；
- 调参面板 `RecentsTuningPanel` 与 `NavRsPrefs`。
