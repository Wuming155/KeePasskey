# KeePasskey 残余任务修复计划（REPAIR_PLAN）

> **更新时间**：2026-09-09（TASK-48 落地段删；TASK-02 实机回归端到端失败按用户裁定移出本计划后期解决，看板转 ❌；余 3 项）
> **文档定位**：本文档为 `STATUS.md` §2「未完成工作唯一看板」中剩余 **3 项未实现 / 待评估任务**（TASK-19 / 43 / 49）的详细落地规划，**仅作执行依据**；各任务的「状态 / 优先级」以 `STATUS.md` 看板为唯一真相源，本文档不维护状态列。**TASK-44（2026-09-08）、TASK-46（2026-09-08）、TASK-45（2026-09-08，`d4bc46c`）、TASK-20（2026-09-08，`c530761`）、TASK-47（2026-09-08）、TASK-48（2026-09-09，`354d432`）已落地并回写 `STATUS.md` §2，其规划段落随之删除；TASK-02 于 2026-09-09 完成实机回归（注册层通过 / 端到端失败），按用户裁定移出本计划后期解决（看板 TASK-02 📋→❌）**。
> **来源**：基于 `STATUS.md` §2 / §3 / §6 与 `FINDINGS_TRACKER.md`（P2-5 / P2-14 / P1-6 / P3-27 等）制定。
> **生命周期纪律**：本文档仅承载执行细节（范围 / 依据 / 风险 / 验收）。各项落地并经 `.\gradlew.bat test` 全绿、随代码 `git commit` 后，**必须即时回写 `STATUS.md` §2 对应 TASK 状态并删除本文件**，杜绝与 SSOT 多头并存（见 `AGENTS.md` 纪律 #7）。**本次仅制定计划，不提交 GitHub（用户明确指示）。**
> **编号规则**：批次统一以 **STATUS TASK ID + Git 提交号** 标识；旧「Wave」编号体系已冻结（见 `STATUS.md` §4）。

---

## 任务总览（执行顺序建议）

| 序 | TASK | 领域 | 主题 | STATUS 状态 | 优先级 | 建议 |
|:--:|:---:|:---:|---|:---:|:---:|---|
| 1 | **TASK-19** | 依赖 | zxing → CameraX + ML Kit 评估 | 📋 | P3 | 仅评估，建议暂缓 |
| 2 | **TASK-43** | 特性 | 进阶偏好消费方接线（拆批） | ❌ | P3 | 拆多批，逐批落地 |
| 3 | **TASK-49** | 特性 | 图标渲染 / 删除 + 引用展示侧残余接线 | ❌ | P3 | 数据通道已就绪，消费点平移 |

> 上表「STATUS 状态」为 2026-09-09 快照引用，实时状态一律以 `STATUS.md` §2 为准。TASK-44 / TASK-47 / TASK-48 已先后落地并随代码提交回写 `STATUS.md` §2，规划段落已删除；TASK-45 / TASK-20 此前已落地（见各自提交 `d4bc46c` / `c530761`）；TASK-02 已于 2026-09-09 回归并移出本计划（后期解决）。

---

## TASK-19（P3）：zxing → CameraX + ML Kit 迁移评估

**依据**：`STATUS.md` §2 TASK-19（依赖治理）。
**背景**：`zxing-android-embedded:4.3.0` 保持稳定，当前扫码（TOTP 添加 / 解锁）可用。

**范围与落地**（评估型，不直接改代码）：
1. 评估 `zxing-android-embedded` → `CameraX` + `ML Kit Barcode Scanning` 的迁移成本 / 收益 / 风险。
2. 输出评估结论（建议迁移 / 暂缓），决策回写 `STATUS.md` §2 TASK-19 状态。

**风险**：
- ML Kit 二进制体积与隐私（Google Play 数据安全声明需更新）。
- CameraX 生命周期接入改造（当前 zxing 封装自带 Activity）。

**验收**：评估结论文档化；落地决策（迁移或暂缓）回写 STATUS。

---

## TASK-43（P3）：进阶偏好消费方接线（拆批建议）

**依据**：`TASK-12` 裁定衍生（全部开关保留为预留功能，已随 TASK-12 持久化不再回显丢失）；`FINDINGS_TRACKER.md` P1-6。
**背景**：设置页约 30 个进阶开关 + 5 源导入解析器（1PUX / Bitwarden / KeePass / 浏览器 CSV）均为真实功能缺口，消费方未接线。

**拆批规划**（每批独立提交、独立验收，避免一次性大改）：

| 子批 | 范围 | 说明 |
|:--:|---|---|
| **43a** | 同步相关：`webdavChunkedUpload` / `webdavChunkSizeMb` / `createBackupBeforeSave` / `checkRemoteChangesBeforeSave` / `conflictResolution` 默认策略 / `useFileTransactions`（信息展示） | 对接 `WebDavSyncProvider` / `SyncCoordinator` |
| **43b** | 自动填充体验：`autofillCopyTotp` / `inlineSuggestionsEnabled` / `autoReturnFromQuery` / `autofillShowTotpNotification` / `overrideNoAutofill` / `skipDalVerification`（黑名单 `disabledAutofillQueriesCount` 已随 TASK-44 闭环并下架该计数） | 对接 `KeePasskeyAutofillService` |
| **43c** | UI 体验：`maskPasswordsDefault` / `maskTotpDefault` / `listDensity` / `autoActivateSearchOnOpen` / `showGroupInSearchResult` / `showGroupInEntry` / `showUnlockedNotification` / `showKillAppOption` | 对接各 Compose 屏幕 |
| **43d** | 导入解析器：1PUX / Bitwarden / KeePass / 浏览器 CSV（5 源） | 独立功能，需解析器 + 映射 `UiVaultEntry` |
| **43e** | 子库挂载（TASK-13 诚实提示后续真实实现） | 真实功能缺口，工作量较大 |
| **43f** | 调试：`debugLogEnabled` / `verboseSyncLog` | 对接 `DebugLogBuffer` |

> 其余开关（`rememberRecentFiles` / `rememberKeyFileLocation` / `clearPasswordOnLeave` / `lockWhenNavigateBack` / `offerSaveCredentials` / `preloadDatabaseEnabled` / `iconSet` / TOTP 字段映射）按就近原则并入上述子批或独立小批。

**风险**：
- 每个开关消费方需独立评估副作用，严禁「假开关」（TASK-12/13/36 已确立诚实化先例）。
- 导入解析器涉及外部格式兼容，应参照 `docs/reference-projects.md` 优先级（KeePassDX 次优先、KeePass-2.61.1 为格式裁决者），**先读架构分析文档、禁止直接翻参考项目源码**。

**验收**：每子批独立提交，`STATUS.md` §2 TASK-43 状态按子批进展增量更新；开关消费方真实生效或如实标注未实现（不回显丢失）。

---

## TASK-48（P3）：占位库假元数据（FINDINGS P2-26）

**依据**：`FINDINGS_TRACKER.md` P2-26；`STATUS.md` §2 TASK-48。
**背景**：无 `.kdbx` 文件时 `RealVaultRepository` 回退占位 `default_vault` 并附静态描述文案，风险已接受（引导创建首个库）。

**范围**：保留引导语义，但在 UI 上将占位项与真实库显式区分（标签 / 视觉样式 / 点击行为），避免用户误认存在真实数据库。
**验收**：空库场景下占位项不可被当作真实库打开/同步；存在真实库时不出现占位项。

---

## TASK-49（P3）：图标渲染 / 删除 + 引用展示侧残余接线

**依据**：`STATUS.md` §2 TASK-49（TASK-15 / TASK-17 残余）。
**范围**：
1. **图标（TASK-15 残余）**：列表行与详情页渲染 `customIconId` 对应位图（`VaultRepository.getCustomIconBytes` 已就绪），并提供图标删除入口（删除须同步清理 KDBX Meta CustomIcons 与条目引用）。
2. **字段引用（TASK-17 残余）**：Notes / URL 展示侧经 `FieldReferenceEngine` 展开；**必须保持投影层不物化被引用密码明文**的 M1 语义（仅消费点展开）。

**风险**：图标删除涉 Meta 清理与引用失效处理；引用展开在 Notes 中可能触发跨条目递归，须沿用既有深度上限 10 防循环。
**验收**：两项各补回归用例（图标删除后引用回落默认图标；Notes 引用展开且循环引用不崩溃）。

---

## 执行纪律提醒（落地时遵循）

1. 每批落地后 `.\gradlew.bat test` 全绿（当前基线 514 例：502 通过 / 0 失败 / 12 跳过）方准入库。
2. 落地即回写 `STATUS.md` §2 对应 TASK 状态（本计划被吸收后删除），与代码**同一次 `git commit`**。
3. 跨文档口径一致性（AGENTS.md 纪律 #9）：版本基线 / 测试例数 / 看板项数一律以 `STATUS.md` 为准。
4. 本次计划制定不提交 GitHub；实现时再按纪律提交并推送。
