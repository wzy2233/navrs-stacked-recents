# NavRS v1.0.0 — 预编译 launcher 包（Rust 堆叠后台）

**纯自研的 Android 堆叠式最近任务（Rust + AOSP Launcher3）**，附一份**已在真机上验证过**的
`Launcher3QuickStep` 预编译包（APK + odex + vdex + `libnav_rs.so`）。

> 主体功能已全部完工；作者精力有限，本项目**暂停维护**，不再保证后续更新与 issue 响应。

## 为什么是 launcher 包而不是整包

整包（`m bacon`）要跑 AOSP 的 soong 全量分析。构建这台机器的 WSL 上限 12GB、宿主 15.8GB，
`soong_build` 吃满 10.4GB 后全程换页，四次尝试（`-j14` / `-j6` / `-j4+GOMAXPROCS=4` /
`-j2+GOGC=30`）都停在 `Running globs...` 不再产出 `build.ninja`。所以这次发布的是**能装、能跑**
的 launcher 增量包；整包请按 `docs/ROM.md` 在 ≥32GB 内存的机器上 `m -j16 bacon`。

## 附件

| 附件 | 大小 | SHA-256 | 内容 |
| --- | --- | --- | --- |
| `navrs-launcher-20261115.zip` | 15.0 MB | `cb74e6f14613a40c4cefa263675b507004b2a2a30e2ba488322165564e6a86c7` | 预编译 launcher 包（APK + odex + vdex + `libnav_rs.so` + 安装说明） |
| `navrs-archive-20261117.zip` | 12.9 MB | `7e66734e696d086f6293e5f27a0506101f59fa0850883a43827cefb44115e2d4` | 过程资料归档：52 个演进补丁脚本、反编译参考类、Xposed 形态的早期实现、32 位 `.so`、两轮源码快照 |

归档说明见包内 `MANIFEST.txt`。仓库本身已含最终源码、文档与
`repo manifest -r` 的逐仓库 commit 锁（`docs/manifest-polaris-lineage-22.2.xml`）。

## launcher 包里有什么

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `Launcher3QuickStep.apk` | 12.2 MB | 2026-11-15 构建 |
| `Launcher3QuickStep.odex` | 19.1 MB | 必须与 apk/vdex 同批 |
| `Launcher3QuickStep.vdex` | 0.2 MB | 同上 |
| `libnav_rs.so` | 0.3 MB | Rust 渲染器（arm64-v8a，含 `nativeSetBarColor`） |
| `README.txt` | — | 安装 / 回滚 / 版本说明 |

`navrs-launcher-20261115.zip` — 15.0 MB
`SHA-256: cb74e6f14613a40c4cefa263675b507004b2a2a30e2ba488322165564e6a86c7`

适用：LineageOS 22.2（Android 15）/ Xiaomi Mi MIX 2S（polaris），三键导航。

安装（需要 `adb root`，详见包内 `README.txt`）：

```bash
export MSYS_NO_PATHCONV=1
adb root && adb shell "mount -o rw,remount /system_ext"
adb push Launcher3QuickStep.apk  /system_ext/priv-app/Launcher3QuickStep/
adb push Launcher3QuickStep.odex /system_ext/priv-app/Launcher3QuickStep/oat/arm64/
adb push Launcher3QuickStep.vdex /system_ext/priv-app/Launcher3QuickStep/oat/arm64/
adb push libnav_rs.so            /system/lib64/
adb push libnav_rs.so            /system_ext/priv-app/Launcher3QuickStep/lib/arm64/
adb shell "restorecon -R /system_ext/priv-app/Launcher3QuickStep /system/lib64"
adb shell "am force-stop com.android.launcher3"; adb shell "input keyevent KEYCODE_HOME"
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
```

## 这一版对应哪份源码

包内二进制 = **batch14 + 一处状态栏探针**（去掉强制 `APPEARANCE_LIGHT_STATUS_BARS`），
即最后一次上机验证的状态。

仓库 `main` 的源码是 **batch15**（背景预烘焙模糊、状态栏图标按背景切换、底栏跟随 app 取色、
截图移出主线程），只编译过、未上机，因此没有打进这个包。

## 主要特性

- 堆叠卡片：Rust 计算几何，弹簧收敛，前后卡亮度分层；点按进入 / 上滑销毁 / 上滑关闭 / 点空白关闭。
- 双入口：三键「多任务」键（独立透明 Activity）与底部上滑手势（Launcher 内并列 deck + Shell live tile 接管）。
- 背景：桌面截图 + 烘焙模糊 + scrim。
- 三键栏：Rust 绘制，按键收进中间 66%，按壁纸/前台 app 取色，锁屏联动，长按调参。
- 状态栏图标随背景深浅切换（batch15 起不再「先渐隐」）。

## 已知限制

- 只在 polaris + LineageOS 22.2 验证；其它机型按 `docs/PORTING.md` 自行编译。
- 手势模式在低端机可能掉帧；三键模式是主要目标形态。
- 平板/双栏（`allowQuickSwitch`）未适配，会回落原生 overview。

## 源码与文档

`README.md` / `docs/ARCHITECTURE.md`（时序图、状态机）/ `docs/INTERFACES.md`（34 个 JNI）/
`docs/PORTING.md`（7 处 hook）/ `docs/BUILD.md` / `docs/ROM.md` / `docs/HANDOVER.md`。

License: Apache-2.0（含 AOSP Launcher3 衍生代码，见 `NOTICE`）。
