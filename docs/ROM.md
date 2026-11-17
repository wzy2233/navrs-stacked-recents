# 刷机包（ROM）

## 1. 这个包是什么

一台已经内置本项目的 LineageOS 22.2（Android 15）**整包 OTA**：

| 项 | 值 |
| --- | --- |
| 设备 | Xiaomi Mi MIX 2S（`polaris`） |
| ROM | LineageOS 22.2（Android 15） |
| 构建目标 | `lineage_polaris-ota.zip` |
| 变更范围 | 仅 `Launcher3QuickStep`（含 `libnav_rs.so`）；其它系统组件未改 |
| 默认导航 | **三键**（刷完直接按多任务键） |

安装：`adb sideload lineage_polaris-ota.zip`，或 recovery 里 Install 选 zip。
这是整包，会覆盖系统分区，`data` 不受影响。首次开机较慢（dex2oat），属正常。

> **注意**：仓库里的 `lineage_polaris-ota.zip`（2026-11-02 构建）是**本项目开始前**的
> LineageOS 基线包，**不包含** NavRS。不要把它当成"带堆叠后台的包"发布或刷入。

## 2. 自己构建

### 2.1 硬件要求（重要）

AOSP 全量分析 + 打包对内存很敏感：

| 内存 | 表现 |
| --- | --- |
| ≥ 32 GB | 正常，`m -j16 bacon` 约 20–40 分钟（增量） |
| 16 GB | 能过，但 `soong_build` 会大量换页；建议 `-j6` 且不要同时开别的程序 |
| ≤ 12 GB | 容易在 soong 分析阶段卡住（本仓库开发后期就是这种情况），建议加内存或换机器 |

### 2.2 命令

```bash
cd /path/to/lineage
source build/envsetup.sh
breakfast polaris

# 先确认 Rust 产物已就位（见 docs/BUILD.md 第 2 节）
ls -la packages/apps/Launcher3/navrs/libnav_rs-arm64.so

m -j6 bacon
# 产物：out/target/product/polaris/lineage_polaris-ota.zip
```

只改动画/Java 时不必每次打包，`m -j14 Launcher3QuickStep` + 手动推三个文件更快
（`docs/BUILD.md` 第 3、5 节）。

### 2.3 发布前自检

1. 刷入后开机，确认三键栏由 Rust 绘制（按键位置比原生更聚拢，居中 66%）；
2. 打开任意 app → 按多任务键：应出现堆叠卡片 + 模糊桌面背景，**没有**原生 overview 闪一下；
3. 后台里上滑：卡片向左滑出、背景溶回桌面；
4. `adb logcat -d | grep -E "NavRS" | tail`：应看到 `entry start ... t=<200ms 内>`、
   `status bar icons ...`、`app bar #...`；
5. 桌面 → 多任务键：卡片从左侧滑入，背景由清晰变模糊。

### 2.4 计算校验和

```bash
sha256sum out/target/product/polaris/lineage_polaris-ota.zip
ls -l  out/target/product/polaris/lineage_polaris-ota.zip
# 把 sha256 与字节数写进 Release 说明
```

## 3. 上传到 Release

大文件不能走 git（GitHub 单文件 100MB 限制），必须作为 Release 附件。

### 方式 A：`gh`（推荐）

```bash
gh auth login                       # 只需一次
bash tools/publish-release.sh /path/to/lineage_polaris-ota.zip
```

### 方式 B：REST API

```bash
export GITHUB_TOKEN=<有 repo 权限的 token>
bash tools/publish-release.sh /path/to/lineage_polaris-ota.zip
```

脚本会：建 tag `v1.0.0` → 用 `docs/RELEASE-NOTES.md` 作说明 → 上传 zip 作附件。

### 方式 C：网页

Releases → Draft a new release → tag `v1.0.0` → 粘贴 `docs/RELEASE-NOTES.md` →
拖入 zip → Publish。

> 单个 Release 附件上限 2GB；本包约 1.2GB，可直接上传。

## 4. 记录

| 文件 | 大小 | SHA-256 | 构建日期 |
| --- | --- | --- | --- |
| `lineage_polaris-ota.zip` | 构建后填写 | 构建后填写 | — |

> 本表由构建者填写；仓库不保存二进制，避免 git 历史膨胀。
