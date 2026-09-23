<a id="s298"></a>

# §298 弱 ETag 设备期望迁移批次

> `ISSUE-P3-304` **整条闭环**（P3 13 → **12 项**）。
> 触发＝§297 批次 AC⑤ 四层设备侧验证实跑中揭出（`:sync:` 层 1 例红），按规则 6.1② 即登即修。
> 覆盖面：`ISSUE-P3-304`（本体，sync androidTest 单断言期望）。纯测试期望迁移，**零生产代码改动**。

---

## 1. 条目正文（原样收录）

### `ISSUE-P3-304`：`WebDavPropfindParserDeviceTest` 弱 ETag 期望滞留 §151 旧语义——未随 §272「保留 W/」读写一致化更新

- **核实时间点**：2026-09-23（§297 批次 AC⑤ 四层设备侧验证实跑中暴露）。
- **核实方式**：Pixel_10 AVD（API 36）上 `:sync:` 层 24 例中 1 例失败——`元数据语义在真机保持_零字节与哨兵与目录判定` 断言 `W/"abc123"` 经 `WebDavPropfindParser.parse` 应得 `abc123`，实际得 `W/abc123`。生产侧 `sync/.../model/SyncModels.kt:95-105` 的 `cleanEtag` 自 §272（ISSUE-P1-275，commit `0931e166`）起**刻意保留弱标记**（剥引号但留 `W/`；KDoc 明载「读侧吞掉 W/ 会让写侧在严格服务器上退化为恒 412」），宿主侧 `SyncModelsTest.kt:28` 已按新语义锁定 `W/abc123`；设备侧用例最后改动停在 §151（commit `505fb045`），期望未随迁——**纯测试期望漂移，生产行为无缺陷**。
- **根因**：§272 改动原生面无关但改动了 androidTest 覆盖的行为面，当批未实跑 `:sync:` 设备层（其 AC⑤ 亦如实标注「真实 DAV 矩阵未执行」），设备侧旧期望漏迁。宿主 `test` 不覆盖 `*/src/androidTest/**`，滞留至今。
- **涉及文件**：`sync/src/androidTest/java/com/keepasskey/sync/webdav/WebDavPropfindParserDeviceTest.kt`（单断言期望）。
- **验收标准**：AC① 该断言期望改为 `W/abc123`（与 `SyncModelsTest` 同口径）并在断言处留 §272 语义注释；AC② Pixel_10 AVD 上 `:sync:connectedDebugAndroidTest` 全绿；AC③ 不回退生产侧 `cleanEtag` 行为。

**开工前前提复核（2026-09-23）**：成立，即登即修。生产 `cleanEtag` 保留 `W/` 的语义与 KDoc、宿主 `SyncModelsTest.kt:28` 锁定值、§272 批次意图三方一致；设备侧断言确为 §151 旧语义孤例（全仓 androidTest 无第二处 `cleanEtag` 期望）。

---

## 2. 整改

`WebDavPropfindParserDeviceTest.kt:131` 单断言期望 `"abc123"` → `"W/abc123"`，断言处补 §272 口径注释（剥引号保留弱标记 + 不回退生产行为的指向）。生产侧零改动（AC③）。

## 3. 验证

- `:sync:assembleDebugAndroidTest` 重建后 Pixel_10 AVD 复跑 `:sync:` 层：**OK（24 tests）**（AC②）。
- 宿主全量回归见 §297 §3.1（`tests=2654` 全绿；本批未增删宿主用例）。
- 按测试资产纪律 ②，`*/src/androidTest/**` 修改已真机（AVD）实跑，非仅编译通过。

## 4. 如实声明

- 本条为**测试期望迁移**，不构成对 §272 生产语义的再裁决；`cleanEtag` 保留 `W/` 的读写一致化口径维持不变。
- 附带观察（不成立新项）：`:app:` 层 `AutofillAuthChainDeviceTest` 依赖「中文 locale + 冷会话」环境前置但未在自身断言中声明，英文 locale 或热会话下必挂——已在 §297 §3.2 留痕；若未来该用例需常态化在 AVD 连跑，可考虑把环境前置写成显式断言或自动适配，当前不处理。
