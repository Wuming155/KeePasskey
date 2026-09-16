# §96 FAB 收缩 / Slider 手感 / Segmented 选中态 / 热区批次（2026-09）

**条目**：ISSUE-P3-131  
**来源**：第二轮 UI 点评（FAB 遮挡 / Slider 轨样式 / Segmented 一致性 / 辅助热区）

## 96.1 整改内容

| 项 | 改动 | 文件 |
|---|---|---|
| Auto-collapsing FAB | Extended FAB 滚动中缩回为 56dp 图标态（`expanded` 跟随 `!listState.isScrollInProgress`）；`hideFabOnScroll` 仍可整只隐藏；列表 `bottom=96dp` 已预留末条空间 | `VaultListFab` / `VaultListScreen` |
| Slider | 新增 `ValueSlider`：连续宽轨（去密集 `steps` 圆点）、拖拽中浮动数值气泡（高度占位防跳动）、跨档 `TextHandleMove` 触感；生成器随机长度 / 词数 / 编辑页内嵌生成器共用 | `ui/components/ValueSlider.kt`、`GeneratorModeOptions`、`EntryEditComponents` |
| Segmented 选中态 | 显式 `SegmentedButtonDefaults.Icon(active = selected)`，选中项带 M3 勾选图标 | `TotpSettingsScreen` |
| 复制热区 | TOTP 复制胶囊 `minHeight` 40→48，padding 同步加大 | `AuthenticatorScreen` |
| QuickUnlock 热区 | 「切换回极速解锁」`TextButton` 显式 `heightIn(min = 48.dp)` | `UnlockContentSections` |

## 96.2 如实声明

- 列表末条遮挡：本轮前已有 `contentPadding.bottom = 96.dp`，本批主修 FAB 收缩而非补 inset。
- 健康度风险 Chip 背景、OLED Surface 深度自适应：**未改**——前者已有图标+色文字区分，后者属调色盘策略，需单独产品口径。
- 设备侧触感 / FAB 动画实测：本批为代码 + 编译 / 宿主单测。

## 96.3 验证

- `.\gradlew.bat :app:compileDebugKotlin` — BUILD SUCCESSFUL
- `.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.ui.*"` — BUILD SUCCESSFUL
- `.\gradlew.bat assembleRelease` — 见交付说明
