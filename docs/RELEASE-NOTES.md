# NavRS v1.0.0

**纯自研的 Android 堆叠式最近任务（Rust + AOSP Launcher3）**，附一台已刷入的
LineageOS 22.2 整包（Xiaomi Mi MIX 2S / polaris）。

> 主体功能已全部完工；作者精力有限，本项目**暂停维护**，不再保证后续更新与 issue 响应。

## 这个包里有什么

- `lineage_polaris-ota.zip` — LineageOS 22.2（Android 15）整包，已内置本项目的
  `Launcher3QuickStep`（含 Rust 渲染器 `libnav_rs.so`，arm64-v8a + armeabi-v7a）。
- 默认导航模式：**三键**。刷完开机即可直接按多任务键体验堆叠后台。
- 除 Launcher3 外，未修改其它系统组件。

## 主要特性

- 堆叠卡片（deck）：Rust 计算几何，弹簧收敛，前后卡亮度分层。
- 双入口：三键「多任务」键 / 底部上滑手势，共用同一套 deck。
- 背景：桌面截图 + 一次性烘焙模糊 + scrim，进入时交叉淡入。
- 三键栏：Rust 绘制，按键收进中间 66%，按壁纸/前台 app 取色。
- 状态栏图标随背景深浅切换，进入后台时不再「先渐隐」。
- 点按进入、上滑销毁、上滑关闭后台、点空白关闭、左右翻页。

## 刷入

```bash
# 已解锁 bootloader + 有 LineageOS recovery / TWRP
adb sideload lineage_polaris-ota.zip
# 或在 recovery 里 Install → 选择 zip
```

> 这是**整包**，会覆盖系统分区；`data` 不受影响。首次开机较慢（dex2oat），属正常。

## 校验

见仓库 `docs/ROM.md`（SHA-256 / 大小 / 构建指纹）。

## 已知限制

- 仅在 polaris + LineageOS 22.2 验证；其它机型请按 `docs/PORTING.md` 自行编译。
- 手势模式下低端机仍有掉帧；三键模式是主要目标形态。
- 平板/双栏（`allowQuickSwitch`）未适配，会回落到原生 overview。

## 源码与文档

- 架构 / 时序图：`docs/ARCHITECTURE.md`
- 接口（JNI / facade / 配置）：`docs/INTERFACES.md`
- 移植指南：`docs/PORTING.md`
- 编译与部署：`docs/BUILD.md`
- 现场踩坑记录：`docs/HANDOVER.md`

License: Apache-2.0（含 AOSP Launcher3 衍生代码，见 `NOTICE`）。
