# 编译与部署

## 1. 环境

```bash
# 源码
repo init -u https://github.com/LineageOS/android.git -b lineage-22.2
repo sync -c -j8

# 设备（示例：polaris / 小米 MIX 2S）
source build/envsetup.sh
breakfast polaris

# Rust
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.2.12479018
```

## 2. 先编 Rust

```bash
cd packages/apps/Launcher3/rust          # 仓库里的 rust/ 目录
cargo ndk -t arm64-v8a -o out build --release
cp out/arm64-v8a/libnav_rs.so ../navrs/libnav_rs-arm64.so
# 32 位（可选）
cargo ndk -t armeabi-v7a -o out build --release
cp out/armeabi-v7a/libnav_rs.so ../navrs/libnav_rs-arm.so
```

> `.so` 作为 `cc_prebuilt_library_shared` 参与编译（见 `docs/PORTING.md` 第 2 节），
> 所以每次改 Rust 都要重新 `cargo ndk` 并覆盖 `navrs/libnav_rs-*.so`。

## 3. 编 launcher（快循环，约 1.5–2 分钟）

```bash
cd /path/to/lineage
source build/envsetup.sh
breakfast polaris
m -j14 Launcher3QuickStep privapp_whitelist_com.android.launcher3
```

产物：

```
out/target/product/<device>/system_ext/priv-app/Launcher3QuickStep/
├── Launcher3QuickStep.apk
└── oat/arm64/Launcher3QuickStep.{odex,vdex}
```

## 4. 编整包（出刷机包）

```bash
m -j14 bacon          # LineageOS；AOSP 用 `m otapackage`
# 产物：out/target/product/<device>/lineage_<device>-ota.zip
```

## 5. 部署（只换 launcher 时）

见 `docs/PORTING.md` 第 5 节。三个必须遵守的点：

1. `export MSYS_NO_PATHCONV=1`（Git Bash / MSYS 环境）；
2. apk + odex + vdex **同一批推送**；
3. `chmod 644` + `restorecon -R` + `am force-stop com.android.launcher3` + `KEYCODE_HOME`。

脚本参考 `tools/deploy15.sh`（把 `LOCAL` 指向产物目录即可）。

## 6. 调参与自测

### 阈值（即时生效）

```bash
adb shell settings put secure navrs_short_frac 0.10
adb shell settings put secure navrs_long_frac  0.50
adb shell settings put secure navrs_quick_ms   200
```

### 面板

长按三键栏「多任务」→ `RecentsTuningPanel`：卡片尺寸、弹簧快慢、pitch 跟随、过渡速度。

### 帧率

```bash
adb shell dumpsys gfxinfo com.android.launcher3 reset
adb shell input keyevent KEYCODE_APP_SWITCH
adb shell dumpsys gfxinfo com.android.launcher3 | head -20
```

看 `Janky frames`、`90th percentile`、`GPU HISTOGRAM`。改完动画/背景后对比这组数字，
比「看着顺不顺」可靠。

### 录屏抽帧

```bash
adb shell "screenrecord --time-limit 8 --bit-rate 12000000 /sdcard/rec.mp4" &
sleep 2; adb shell input keyevent KEYCODE_APP_SWITCH; sleep 6
adb pull /sdcard/rec.mp4 .
ffmpeg -i rec.mp4 -vf fps=30 frames/f%03d.png
# 只看状态栏一条：
ffmpeg -start_number 96 -i frames/f%03d.png -frames:v 1 \
       -vf "crop=1080:100:0:0,tile=1x12" statusbar.png
```

## 7. 已知的构建坑

| 现象 | 原因 / 处理 |
| --- | --- |
| `remote secure_mkdirs() failed`，但文件没推上去 | 少了 `MSYS_NO_PATHCONV=1`，路径被改写成 `C:/…` |
| `No space left on device`，`df` 却看不出变化 | 被进程 mmap 的旧文件未释放；`adb reboot` 后重试 |
| 开机后桌面极卡 | apk/odex/vdex 版本不匹配，回落到解释执行；三个文件必须同批推 |
| `UnsatisfiedLinkError: nativeSetBarColor` | `.so` 与 Java 不同步，重新 `cargo ndk` 并推两个路径 |
| 三键不显示 | 当前不是 `navigation_mode=0`，用 overlay 切（PORTING 第 6 节） |
| 后台里上滑，整屏被缩成卡片 | `TouchInteractionService` 的 `NO_OP` 分支没打上 |
