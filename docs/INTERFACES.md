# 接口

三层边界，从下往上：

1. **JNI**：Java ↔ Rust，34 个函数（`NavBarView` 10 个、`RecentsView` 24 个）。
2. **Facade**：`com.android.launcher3.navrs.*`，让 `quickstep` 模块在不依赖 `com.wzy.navrs` 的前提下
   调用 navrs 实现。
3. **配置**：Intent extra、`Settings.Secure`、`SharedPreferences`、日志探针。

---

## 1. JNI · 三键栏（`Java_com_wzy_navrs_NavBarView_*`）

Rust 侧实现：`rust/src/lib.rs`。

| 函数 | 签名 | 语义 |
| --- | --- | --- |
| `nativeCreate` | `long (int w, int h, float density)` | 创建 bar 实例，`w/h` 是 View 像素尺寸 |
| `nativeResize` | `void (long, int w, int h)` | 尺寸变化（当前 `NavBarView` 走的是重建） |
| `nativeSetColor` | `void (long, int argb)` | 按键（图标）颜色 |
| `nativeSetBarColor` | `void (long, int argb)` | 整条 bar 的铺底颜色；alpha=0 表示透明 |
| `nativeSetKeyguard` | `void (long, boolean)` | 锁屏态（图标形态切换） |
| `nativeLongPress` | `int (long, long timeNanos)` | 长按计时到点后询问是否触发；返回动作码 |
| `nativeRender` | `boolean (long, ByteBuffer, long timeNanos)` | 渲染一帧到 RGBA buffer；`true` 表示还需下一帧 |
| `nativeTouch` | `int (long, int action, float x, float y, long timeNanos)` | 触摸；返回动作码（0=无） |
| `nativeDestroy` | `void (long)` | 释放 |
| `nativeVersion` | `String ()` | 版本串，用于确认 `.so` 是否为最新 |

动作码：

| 值 | 含义 |
| --- | --- |
| 0 | 无 |
| 1 | 返回 |
| 2 | 桌面 |
| 3 | 多任务（+ 长按打开调参面板） |

触摸 action：`0=DOWN`、`1=UP`、`2=MOVE`、`3=CANCEL`（`NavBarView.onTouchEvent` 直接映射）。

## 2. JNI · deck（`Java_com_wzy_navrs_RecentsView_*`）

Rust 侧实现：`rust/src/recents.rs`。

### 2.1 生命周期 / 渲染

| 函数 | 签名 | 语义 |
| --- | --- | --- |
| `nativeCreate` | `long (int w, int h, float density, int count)` | 建模型；`count` 是卡片数 |
| `nativeRender` | `boolean (long, ByteBuffer, long timeNanos)` | 渲染背景帧（RGBA，`w*h*4`）；返回值同三键 |
| `nativeDestroy` | `void (long)` | 释放 |
| `nativeTouch` | `int (long, int action, float x, float y, long timeNanos)` | 手势；返回命中动作 |
| `nativeHitAt` | `int (long, float x, float y)` | 命中测试，返回卡片下标（-1 = 无） |
| `nativeCardCount` | `int (long)` | 当前卡片数 |
| `nativeRemoveCard` | `void (long, int index)` | 删除卡片后的重排 |
| `nativeSettle` | `void (long)` | 强制回到静止布局（解除 frozen） |

### 2.2 几何输出

| 函数 | 签名 | 语义 |
| --- | --- | --- |
| `nativeCardRect` | `boolean (long, int index, float[] scratch, float[] out)` | **带裁剪**的卡片四边形。`out[0..3]=l,t,r,b`（buffer 像素），`out[4]=圆角（占卡宽比例）`，`out[5]=skew` |
| `nativeCardQuadRaw` | `void (long, int index, float[] out)` | **不裁剪**的几何（转场需要即将进场的卡） |
| `nativeCardAlpha` | `float (long, int index)` | 卡片亮度 0..1 |

> Java 侧的 `nativeCardQuad()` 是**包装方法**：调用 `nativeCardRect` 后把 buffer 坐标换算成
> View 坐标（`sx = viewW/bufW`），并把 `out[4]` 从比例换成像素。

### 2.3 布局 / 手势参数

| 函数 | 签名 | 语义 |
| --- | --- | --- |
| `nativeSetContent` | `void (long, float aspect, float centerY)` | 卡片内容区宽高比与竖直中心（去掉系统栏后的窗口） |
| `nativeSetMode` | `void (long, int mode)` | 布局模式；当前只用 `5`（gear deck 堆叠） |
| `nativeSetSpring` | `void (long, boolean fast)` | 弹簧快/慢 |
| `nativeSetPitchByCard` | `void (long, boolean on)` | pitch 是否跟随卡宽 |
| `nativeSetCardLarge` | `void (long, boolean large)` | 大卡 / 紧凑卡 |
| `nativeSetFrozen` | `void (long, boolean frozen)` | 冻结布局（转场期间禁止重排） |
| `nativeSetGates` | `void (float shortMax, float longMin)` | 手势两段阈值（屏高比例） |
| `nativeGetGateShort` | `float ()` | 读回短滑阈值 |
| `nativeGetGateLong` | `float ()` | 读回长滑阈值 |
| `nativeDecideTarget` | `int (float progress)` | 0=回 app、1=进后台、2=回桌面 |
| `nativeUpdateProgress` | `float (float displacement, int transitionLen, float dragFactor)` | 把位移映射成 0..1 进度 |
| `nativeSetRunningRect` | `void (float l, float t, float r, float b)` | Shell live tile 的最后一帧矩形 |
| `nativeGetRunningRect` | `void (float[] out)` | 读回（调试） |

## 3. Facade `com.android.launcher3.navrs.NavRsActivity`

`quickstep` 侧只能用这一层（`navrs` 依赖 `Launcher3QuickStepLib`，反向不可见）。

| 方法 | 说明 |
| --- | --- |
| `isOpen()` | 三键后台 Activity 是否在前台 |
| `recentlyExited()` | 600ms 内刚滑出（用于吃掉同一手势） |
| `launchFromGesture(Context, boolean fromHome)` | 手势路径启动后台（当前仅桌面手势用） |
| `captureHomeShot()` / `peekHomeShot()` | 桌面截图（同步采集 / 非消费读取） |
| `getDragProgress()` / `setDragProgress(float)` | 手势进度（0=app，1=后台） |
| `isHomeRelease()` / `setHomeRelease(boolean)` | 本次手势是否落向桌面 |
| `getLongOut()` / `setLongOut(float)` | 长滑出门进度（0..1，驱动后卡位移） |
| `loadGateFractions(Context)` | 从 `Settings.Secure` 读回阈值 |
| `getShortFrac()` / `getLongFrac()` / `getQuickTouchMs()` | 阈值读取 |
| `requestExit()` | 让后台 Activity 滑出 |
| `captureHomeShot()` | 同上（重复列出便于查找） |

## 4. `NavRsBridge.Host` / `Deck`

`NavRsBridge` 定义了 `navrs` 实现、`quickstep` 调用的接口；`NavRsHostImpl` 在 `navrs` 侧实现它，
`NavRsBridge.host()` 在启动时注入（避免编译期反向依赖）。

```java
public interface Host {
    void captureHomeShot();
    Bitmap peekHomeShot();
    float getDragProgress();      void setDragProgress(float progress);
    boolean isHomeRelease();      void setHomeRelease(boolean home);
    float getLongOut();           void setLongOut(float v);
    void loadGateFractions(Context c);
    float getShortFrac();         float getLongFrac();   long getQuickTouchMs();
    void launchFromGesture(Context c, boolean fromHome);
    boolean isActivityOpen();     boolean recentlyExited();
    void exitRecentsActivity();
    void toggleTuning(Context c, View anchor);
    Deck currentDeck();
    void install(Activity a);     void attachLauncher(Activity a);
    void onLauncherStateStart(Activity a, boolean fromApp);
    void onLauncherState(Activity a, boolean keepOverview);
    void startEnter(Activity a);  void finishLiveTile(Activity a);
    void hideCards(Activity a);   boolean consumeDirectOverview();
    void requestExitToHome();
    void offerFrontThumb(Bitmap b);
    boolean isExitAnimating();    long slideOutMs();
    void setRunningRect(float l, float t, float r, float b);
}

public interface Deck {
    void setLongOut(float v);
    void ensureDragReturn(int index);
    void setDragReturnProgress(float progress);
    void settleDragReturn(Runnable onFinished);
    boolean isDragReturning();
    void probeAbsolute(String why);
    boolean isSettled();
}
```

## 5. Intent / Extra

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `com.wzy.navrs.FROM_HOME` | `boolean` | 从桌面进入（决定入场动画与背景是否「清晰起步」） |

启动方式（三键，`OverviewCommandHelper.kt`）：

```kotlin
Intent(ctx, Class.forName("com.wzy.navrs.NavRsRecentsActivity"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    .putExtra("com.wzy.navrs.FROM_HOME", fromHome)
ActivityOptions.makeCustomTaskAnimation(ctx, 0, 0, Handler(Looper.getMainLooper()), null, null)
```

`0/0` 的自定义转场是**故意**的：让系统不要播任何窗口动画，全部由 deck 自己演。

## 6. 配置项

### `Settings.Secure`（即时生效，无需重启）

```bash
adb shell settings put secure navrs_short_frac 0.10   # 短滑阈值（屏高比例）
adb shell settings put secure navrs_long_frac  0.50   # 长滑阈值
adb shell settings put secure navrs_quick_ms   200    # 快速滑动判定（ms）
```

### `SharedPreferences`（`navrs_recents`）

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `mode` | int | 布局模式；`NavRsPrefs.mode()` 恒返回 5（只保留堆叠） |
| `spring_fast` | boolean | 弹簧快/慢 |
| `pitch_by_card` | boolean | pitch 跟随卡宽 |
| `card_large` | boolean | 大卡 |
| `fast_transition` | boolean | 打开卡片用 320ms（否则 480ms） |

入口：长按三键栏「多任务」→ `RecentsTuningPanel`。

## 7. 日志探针

| TAG | 内容 |
| --- | --- |
| `NavRS` | 生命周期、取色结果、入场时间（`entry start ... t=146ms`）、缩略图来源 |
| `NavRSProbe` | 逐帧几何：`f12 p=0.38 grow=0.33 run=... front=... settled=...` |
| `NavRSProbe`（其它） | `desktop shot 1080x2160 mean=... var=...`、`slideOut end t=288`、`drag=0.42` |

常用判读：

- `entry start ... t=NNNms` —— Activity `onCreate` 到首帧的耗时（响应延迟的主要指标）。
- `front thumb src=cache|model` —— 缩略图是否命中缓存（`model` 是可能过期的占位）。
- `status bar icons dark|light luma=NN` —— 状态栏图标重设时机与依据。
- `app bar #rrggbb lum=NN icon=light|dark` —— 三键栏取色结果。

日志开关在代码里是常开的（`Log.d`），量产前建议用 `Build.isDebuggable()` 包一层。
