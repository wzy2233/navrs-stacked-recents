# 换一台电脑怎么把这个项目还原出来

结论先说：**源码和文档 100% 能从本仓库还原，构建环境需要自己 `repo sync` 一份 LineageOS 22.2，
预编译产物从 Release 拿。** 下面按「拿到什么 → 缺什么 → 怎么补」写。

## 1. 仓库里已经有的（clone 即得）

```bash
git clone https://github.com/wzy2233/navrs-stacked-recents.git
```

| 内容 | 路径 | 说明 |
| --- | --- | --- |
| navrs 模块 | `java/navrs/` | `Android.bp`、`AndroidManifest.xml`、8 个 Java 源文件 |
| quickstep facade | `java/quickstep/com/android/launcher3/navrs/` | `NavBarView`、`NavRsActivity`、`NavRsBridge`、`NavRsIntegration`、`TaskbarBridge` 等 7 个 |
| 改过的 AOSP 文件 | `java/framework/` | 9 个原样文件（含 `quickstep-AndroidManifest.xml`、`styles.xml`） |
| Rust | `rust/` | `Cargo.toml/lock`、`lib.rs`、`recents.rs`、`deck.rs` |
| 文档 | `docs/` | 架构/时序图、JNI 接口、移植、编译、ROM、现场笔记 |
| 工具 | `tools/` | 构建/部署/补丁脚本、`publish-api.py`（更新本仓库用） |

代码是**完整状态**，不是补丁片段：`java/framework/` 里的文件就是最终版，
`java/navrs/` 与 `rust/` 是全部新增代码。

## 2. 仓库里没有的（需要自己补）

| 缺什么 | 为什么 | 怎么补 |
| --- | --- | --- |
| LineageOS 22.2 整棵树 | 几百 GB，不可能进 git | 用 `docs/manifest-polaris-lineage-22.2.xml`（`repo manifest -r` 导出的**逐仓库 commit 锁**）精确还原，见第 2.1 节 |
| 编译产物（apk/odex/vdex/so） | 二进制，git 不适合 | Release 里的 `navrs-launcher-20261115.zip`，或自己编（见第 3 节） |
| 设备端状态 | 不在代码里 | `adb root`、`mount -o rw,remount /system_ext`、`cmd overlay enable-exclusive ... threebutton` |
| `libnav_rs-arm*.so` 预编译件 | 仓库里没放（可在本地生成） | `cargo ndk` 重新编（第 3 节），或从 Release 包里的 `libnav_rs.so` 取 arm64 版本 |

> `java/framework/` 的 9 个文件是「改完的最终版」。移植到别的 ROM 时**不要直接覆盖**，
> 按 `docs/PORTING.md` 第 3 节的 7 处 hook 逐处 diff。

## 2.1 精确还原整棵树（推荐）

仓库里的 `docs/manifest-polaris-lineage-22.2.xml` 是 `repo manifest -r` 的输出，记录了
**每个子仓库当时的 exact commit**，用它 sync 出来的树和开发机逐字节一致（不依赖分支漂移）：

```bash
mkdir lineage && cd lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-22.2 \
          -m /path/to/navrs-stacked-recents/docs/manifest-polaris-lineage-22.2.xml
repo sync -c -j8 --no-clone-bundle
```

> 不用 manifest 直接 `repo init -b lineage-22.2` 也能编，但半年后再 sync 可能拉到更新的提交，
> 行为和当时不一定完全一致。

## 3. 在新机器上编出来

```bash
# 1) 落位
L=packages/apps/Launcher3
cp -r java/navrs            $L/navrs
cp -r java/quickstep/...    $L/quickstep/src/com/android/launcher3/navrs/
# java/framework/ 的 9 个文件按 README 里的路径覆盖（先 diff）

# 2) Android.bp 接线（PORTING.md 第 2 节）
#    - Launcher3QuickStepLib 的 static_libs 加 "navrs"
#    - 加 cc_prebuilt_library_shared "libnav_rs"（navrs/libnav_rs-arm64.so）

# 3) Rust
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
cd rust && cargo ndk -t arm64-v8a -o out build --release
cp out/arm64-v8a/libnav_rs.so ../$L/navrs/libnav_rs-arm64.so

# 4) 编 launcher（约 2 分钟；需要 soong 能跑起来，≥16GB 内存）
m -j14 Launcher3QuickStep privapp_whitelist_com.android.launcher3

# 5) 部署（apk + odex + vdex 必须同批）
bash tools/deploy15.sh
```

整包：`m -j16 bacon`（≥32GB 内存，见 `docs/ROM.md`）。

## 4. 还原到什么程度算「一样」

- **源码级**：完全一样（同一个 commit）。
- **行为级**：编出来的 APK 取决于你用的 Launcher3 版本；同一个 LineageOS 22.2 分支上，
  与 Release 里的预编译包等价。
- **设备级**：需要重新按 `docs/BUILD.md` 第 5 节推文件、`restorecon`、切三键。

## 5. 换机后 5 分钟自检

```bash
adb shell "cmd overlay list | grep navbar"          # [x] threebutton
adb shell input keyevent KEYCODE_APP_SWITCH         # 应出现堆叠卡片
adb logcat -d | grep -E "NavRS" | tail -5           # entry start ... / bar colour ...
```
