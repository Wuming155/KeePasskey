# KeePasskey 残余任务修复计划（REPAIR_PLAN）

> **更新时间**：2026-09-08（TASK-45/20 落地回写）
> **文档定位**：本文档为 `STATUS.md` §2「未完成工作唯一看板」中剩余 **4 项未实现 / 待验证任务**（TASK-02 / 19 / 43 / 44）的详细落地规划，**仅作执行依据**；各任务的「状态 / 优先级」以 `STATUS.md` 看板为唯一真相源，本文档不维护状态列。**TASK-46（2026-09-08）、TASK-45（2026-09-08，`d4bc46c`）、TASK-20（2026-09-08，`c530761`）已落地并回写 `STATUS.md` §2，其规划段落随之删除**。
> **来源**：基于 `STATUS.md` §2 / §3 / §6 与 `FINDINGS_TRACKER.md`（P2-5 / P2-14 / P1-6 / P3-27 等）制定。
> **生命周期纪律**：本文档仅承载执行细节（范围 / 依据 / 风险 / 验收）。各项落地并经 `.\gradlew.bat test` 全绿、随代码 `git commit` 后，**必须即时回写 `STATUS.md` §2 对应 TASK 状态并删除本文件**，杜绝与 SSOT 多头并存（见 `AGENTS.md` 纪律 #7）。**本次仅制定计划，不提交 GitHub（用户明确指示）。**
> **编号规则**：批次统一以 **STATUS TASK ID + Git 提交号** 标识；旧「Wave」编号体系已冻结（见 `STATUS.md` §4）。

---

## 任务总览（执行顺序建议）

| 序 | TASK | 领域 | 主题 | STATUS 状态 | 优先级 | 建议 |
|:--:|:---:|:---:|---|:---:|:---:|---|
| 1 | **TASK-44** | 特性 | 自动填充黑名单完整生命周期 | ❌ | P3 | 单独功能批次 |
| 2 | **TASK-02** | 平台 | 凭据能力注册实机回归 | 📋 | P1 | 趁真机验证（非代码改动） |
| 3 | **TASK-19** | 依赖 | zxing → CameraX + ML Kit 评估 | 📋 | P3 | 仅评估，建议暂缓 |
| 4 | **TASK-43** | 特性 | 进阶偏好消费方接线（拆批） | ❌ | P3 | 拆多批，逐批落地 |

> 上表「STATUS 状态」为 2026-09-08 快照引用，实时状态一律以 `STATUS.md` §2 为准。TASK-45 / TASK-20 已于 2026-09-08 落地（见各自提交 `d4bc46c` / `c530761`），规划段落已删除。

---

## TASK-44（P3）：自动填充黑名单完整生命周期

**依据**：`TASK-36` 整改衍生（诚实化确认黑名单为端到端功能缺口）；`FINDINGS_TRACKER.md` P3-27。
**背景**：`AutofillSettingsScreen` 仅展示 `disabledAutofillQueriesCount` 计数，且该计数**无任何写入方**——黑名单增删数据源、持久化、服务侧消费均未闭环。

**范围与落地**（对齐 KP2A「禁用自动填充查询」语义）：
1. **存储**：新增 `AutofillBlocklistStore`（DataStore / SharedPreferences 持久化包名集合），替代无写入方的 `disabledAutofillQueriesCount` 计数。
2. **服务侧消费**：`KeePasskeyAutofillService` 填充前解析请求来源包名，命中黑名单则不下发数据集（fail-closed，不降级已有填充）。
3. **入口**：详情页「为本应用禁用自动填充」写入黑名单 + 系统设置通道；删除动作从黑名单移除。
4. **设置页**：`AutofillSettingsScreen` 由「仅计数」改为条目化列表展示与删除（替代当前空 onClick 写死项遗留的诚实化展示）。

**风险**：
- 来源包名获取依赖 Autofill `sourceData` / `ClientState` 解析权限，需确认填充框架可见包名。
- 跨进程包名可信度：仅作「禁用」决策，不影响已解锁数据安全性。

**验收**：端到端回归（写入→填充拦截→删除）用例；设置页计数改为真实条目列表，零无写入方计数器。

---

## TASK-02（P1）：凭据能力注册实机回归

**依据**：`STATUS.md` §2 TASK-02（Wave 16 遗留）。
**背景**：代码层已核实 `AndroidManifest.xml` 凭据服务 `meta-data` 名为 `android.credentials.provider`（契约名正确），`@xml/credential_provider_service` 资源存在，Autofill 兼容层 `android.autofill` 同步在位；仅剩真机验证。

**范围与落地**：
1. 真机「设置 → 密码、密钥和自动填充」确认 KeePasskey 出现且能力生效（Credential Provider + Autofill 双通道）。
2. 验证 Credential Manager 创建 / 调用通行密钥（TASK-18 设备绑定解锁通行密钥）实机可用。
3. 若发现契约名 / 资源问题，回写对应文件并登记新 TASK。

**风险**：不同 OEM 设置入口路径差异；仅真机可验证，无法以单测替代。
**验收**：真机记录 / 截图确认能力生效；无代码改动（除非发现契约缺陷）。

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
| **43b** | 自动填充体验：`disabledAutofillQueriesCount`（并入 TASK-44）/ `autofillCopyTotp` / `inlineSuggestionsEnabled` / `autoReturnFromQuery` / `autofillShowTotpNotification` / `overrideNoAutofill` / `skipDalVerification` | 对接 `KeePasskeyAutofillService` |
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

## 执行纪律提醒（落地时遵循）

1. 每批落地后 `.\gradlew.bat test` 全绿（当前基线 475 例：463 通过 / 12 跳过）方准入库。
2. 落地即回写 `STATUS.md` §2 对应 TASK 状态（本计划被吸收后删除），与代码**同一次 `git commit`**。
3. 跨文档口径一致性（AGENTS.md 纪律 #9）：版本基线 / 测试例数 / 看板项数一律以 `STATUS.md` 为准。
4. 本次计划制定不提交 GitHub；实现时再按纪律提交并推送。
