# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**。

---

## P2 中危缺陷与协议/测试缺口（11 项）

### ISSUE-P2-280：Meta 完全不参与合并 ⇒ 对端新增自定义图标变成悬空 `CustomIconRef`，库级配置恒取本地

- **核实时间点**：2026-09-23 经 sync 全模块 `customIcons` 检索与写出路径核对（官方 / KXC 前提已独立开源码核实）。
- **核实方式**：`KdbxMerger.kt:34-37` 的 `KdbxDatabaseLite` 仅 `rootGroup` + `deletedObjects`；sync 全模块 `customIcons` **零命中**；落库用 `localDb.copy(rootGroup = ..., deletedObjects = ...)`（`SyncConflictController.kt:127-130` 与 `:353-356`）⇒ 图标池恒为本地；写出侧 `KdbxXmlEntrySerializer.kt:40` 直写 `CustomIconRef`，reader 不校验引用是否命中池成员，仅 UI 渲染回落 `EntryIcon.Missing`（`EntryIconPresenter.kt:124`），不修复引用。
- **对照（前提为真）**：官方 `PwDatabase.cs:936` + `:945-979` 的 `MergeInCustomIcons` 按图标 `LastModificationTime` 做 LWW；KXC `Merger.cpp:700-714` 合并时补图标。`RecycleBinUuid` / `HistoryMaxItems` 等 Meta 项同理恒本地。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt`、`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlEntrySerializer.kt`。
- **验收标准**：AC① 合并纳入 `customIcons`（LWW 或并集，口径须与官方一致并写明）；AC② 写出前校验 `CustomIconRef` 命中池成员，未命中须可辨识地失败，禁静默丢图标；AC③ 库级 Meta（`RecycleBinUuid` / `HistoryMaxItems` / `MinVersion`）逐字段给出「合并 / 取 LWW / 以本地为准」结论并登记；AC④ 用例：对端新增图标 + 本端改条目 → 合并后图标**可解析**（含 `:database:` 往返）；AC⑤ 触及外层格式面时按规则 8 对拍。

---

### ISSUE-P2-281：仅自定义字段分歧时冲突界面无从裁决，对端改动必然丢失（`modifiedFields` 全仓零消费）

- **核实时间点**：2026-09-23 经冲突 UI 与合并器双侧核对。
- **核实方式**：`app/.../ui/screens/conflict/ConflictResolutionViewModel.kt:51-108` 自造 diff，仅覆盖 Title / UserName / Password / URL / Notes 五个标准字段，**不含 `customFields`**；`:172-183` 恒传 `fieldResolutions` ⇒ `SyncConflictController.kt:112` 的 `fieldChoice != null` 恒真，`resolveConflict` / `DUPLICATE_BOTH` 在该路径**不可达**；`KdbxMerger.kt:172-181` 在字段解析表为空时直接返回本地条目 ⇒ 远端改动被丢。`modifiedFields` 生产侧零消费（仅 `ConflictStrategy.kt:106`、`KdbxEntryMerger.kt:206` 产出，余为 4 处测试）。无「整条取云端」入口（`ConflictResolutionScreenSections.kt:133-163` 仅字段行 / 全选）。
- **定位提示**：冲突界面目录为 `ui/screens/conflict/`（本族首轮曾误写为 `ui/screens/sync/`，该目录不存在）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/conflict/ConflictResolutionViewModel.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/conflict/ConflictResolutionScreenSections.kt`、`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt`、`sync/src/main/java/com/keepasskey/sync/merge/ConflictStrategy.kt`。
- **验收标准**：AC① 差异来源改为合并器产出的 `modifiedFields`（单一真相源），禁两处各算一份；AC② 自定义字段分歧可见可裁决，并补「整条取本地 / 整条取云端」兜底入口；AC③ 使 `DUPLICATE_BOTH` 等已实现分支真正可达或如实移除；AC④ 用例锁定「仅自定义字段冲突 ⇒ 用户能选且不丢」。

---

### ISSUE-P2-282：Tags 分隔符在读 / 写 / 编辑页三处互不相同，且无官方 `NormalizeTag`，标签数跨实现漂移

- **核实时间点**：2026-09-23 经三处分隔符逐一比对。
- **核实方式**：读侧 `database/.../KdbxXmlGroupReader.kt:43-58`（`:50`）**只按 `;` 切分**；写侧 `KdbxXmlEntrySerializer.kt:52-54` 用 `"; "`；**编辑页输入** `app/.../ui/screens/edit/EntryEditSaveProjection.kt:29` 只按 `,` / `，` / 空格切、**不认 `;`** ⇒ 同一概念三套口径。`KdbxTagsParseEquivalenceTest` 只锁「与旧表达式等价」，未声明逗号非分隔符 ⇒ 不构成刻意锁定。
- **对照（已开源码核实）**：官方 `StrUtil.cs:1530` 的 `g_vTagSep = { ',', ';' }`、切分在 `:1625-1636`、文件侧以裸 `;` 写出（`:1616`），并有 `NormalizeTag`（`:1531-1541`）；KeePassDX `Tags.kt:37-45` 双分隔、`:147` 以 `,` 写出；KeePassXC `KdbxXmlWriter.cpp:316/404` 直写逗号串 ⇒ 读第三方逗号库被读成 1 个标签、回写后对方又读成多个，**双向漂移**。
- **涉及文件**：`database/src/main/java/com/keepasskey/database/xml/KdbxXmlGroupReader.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlEntrySerializer.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlGroupSerializer.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditSaveProjection.kt`。
- **验收标准**：AC① 分隔符集合收敛为**单一常量表**（读 / 写 / 输入解析共用）；AC② 补 `NormalizeTag` 等价实现（trim + 大小写 + 去空 + 剔除含分隔符的非法标签，口径对齐官方）；AC③ 写侧分隔符与官方一致并写明「写 `;`、读 `;` 与 `,`」的取舍；AC④ 用例含「逗号库读入 → 标签数正确 → 回写后可被对方正确解析」；AC⑤ 按规则 8 与 keepassxc-cli / pykeepass 对拍标签往返。

---

### ISSUE-P2-284：分组硬删除只为组自身立墓碑，子条目与子组无墓碑 ⇒ 跨设备合并复活

- **核实时间点**：2026-09-23 经删除分流与墓碑产出核对。**注**：本条在对抗轮未列为独立攻击对象，仅经官方源码旁证（对照段）+ 同文件反向口径支撑，开工前须按规则 6.1② 自行复核调用链。
- **核实方式**：`app/.../data/repository/RecycleBinCoordinator.kt:105-110` 的物理删除分支（回收站禁用 / 与回收站相关时）只追加 `DeletedObject(id = uuid)` 一条，即**组自身**；底层 `database/.../session/SessionTreeEditor.kt:265-278` 的 `removeGroup` 直接摘除整棵子树、不产出子对象墓碑；`sync/.../merge/KdbxTombstoneMerger.kt:22-27` 按 UUID 精确匹配，父组墓碑**不覆盖**子项 ⇒ 他端仍持有条目并将其作为存活对象合回。同文件 `emptyRecycleBin`（`:178-188`）已采用逐对象墓碑口径，构成同文件内直接对照。
- **对照（经核实为真）**：官方 `PwGroup.cs:1367-1386` 的 `DeleteAllObjects` 为**每个子孙 entry 与 group** 追加 `PwDeletedObject`。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/data/repository/RecycleBinCoordinator.kt`、`database/src/main/java/com/keepasskey/database/session/SessionTreeEditor.kt`、`sync/src/main/java/com/keepasskey/sync/merge/KdbxTombstoneMerger.kt`。
- **验收标准**：AC① 组硬删除递归产出子孙条目 / 子组墓碑，并与 `emptyRecycleBin` **复用同一函数**（禁两份实现）；AC② 用例锁定「删组后本库墓碑数 = 子孙对象数 + 组自身」与「他端存活副本经合并后被清除」；AC③ 补 `:database:` / `:sync:` 端到端合并往返；AC④ 与 §246、`ISSUE-P1-03` 的删除语义一致，不得引入「物理删除但无墓碑」的新分支。

---

### ISSUE-P2-285：硬件密钥与「明文不落地」类安全声明无条件渲染（5 处文案，其中 1 处被实现直接否证）

- **核实时间点**：2026-09-23 经渲染点穷举 + `UnlockUiState` 生产构造点穷举（第 ① 条另经逐行反校：`hardwareBackedSecurity` 命中 4 处，唯一赋值在 `@Preview`）。
- **核实方式与清单**（五处**全部无条件渲染**）：
  ① `app/.../ui/screens/unlock/UnlockScreen.kt:217-221` 在 QUICK_UNLOCK 分支显示 `strings.xml:385`「已通过硬件 KeyStore 保全加密主密钥」；真值字段 `UnlockUiState.hardwareBackedSecurity`（`UnlockUiState.kt:49`）**全仓唯一赋值点在 `@Preview`**（`UnlockContentSections.kt:251-261`，生产构造点 8 处无一回填）；`security/KeystoreKeyMaterial.kt:119-126` 对软件级 Keystore 仅 `debugLog.warn`；进入 QUICK_UNLOCK 不要求硬件级（`UnlockModePolicy.kt:31-36`，`BiometricEnrollmentCoordinator.kt:270-277` 降级路径同样置 `isQuickUnlockAvailable = true`）⇒ 软件级机型上「硬件保全」与同卡片 `:113-129` 的「不提供硬件级保护」**同屏并存**。
  ② `strings.xml:660 sec_arch_desc`（渲染于 `SecuritySettingsScreen.kt:424`）称「主密钥驻留 Android Keystore 硬件隔离区，**任何明文数据都不会写入磁盘**」——被两处否证：自产明文导出（`strings.xml:557-563` 自述含全部字段值），以及 `app/data/binary/FileBinaryStore.kt` 的 `store()` / `storeFromStream()` **无任何加密调用**（`core/security/BinaryStore.kt` 契约第 3 条只要求文件 0600 / 目录 0700）⇒ 大附件以明文落私有目录。
  ③ `strings.xml:591 sec_biometric_sub`「密钥由硬件安全模块保护」（`SecuritySettingsScreen.kt:134`），正挂在 ISSUE-P1-22 降级弹窗（`strings.xml:369` 自承软件级、无 TEE/StrongBox）所守护的开关上。
  ④ `strings.xml:745 sync_credential_auth_notice`「同步凭据经**硬件** Keystore 加密封印」（`CloudSyncSections.kt:306`）：走 `SyncCredentialSealer.kt:55/91` → `KeystoreKeyMaterial.kt:119-126`，且**同步面无解锁面那样的降级声明**。
  ⑤ `strings.xml:165 detail_passkey_chip`「FIDO2 硬件芯片保护」（`EntryDetailCards.kt:215`）与 `:212`「已绑定 Passkey 硬件凭据」（`EntryEditComponents.kt:141`）：私钥实为库内软件字段（`PasskeyKeyGeneration.kt:53/98/146` 走 BouncyCastle）+ 自证明 `fmt="none"`。
- **背景与根因**：属本项目明确反对的「界面谎报」族（先例 `ISSUE-P1-241` / `ISSUE-P3-65` / `ISSUE-P2-228` / `ISSUE-P0-02`），且本族**全为安全语义**——用户会据此形成「硬件隔离」「不留明文」的错误认知。限界表与 `产品裁决登记.md` 检索 `SECURITY_LEVEL_SOFTWARE|Keystore` 仅 2 处无关命中 ⇒ 未登记。
- **涉及文件**：上列 5 个渲染点 + `res/values/strings.xml` 与 `res/values-en/strings.xml` 对应键；真值来源 `KeystoreKeyMaterial` / `BiometricEnrollmentCoordinator` / `FileBinaryStore`。
- **验收标准**：AC① 硬件类声明改为**按实测安全等级条件渲染**（判据落单一函数，TEE/StrongBox 与软件级两套文案，中英成对），禁硬编码；AC② 同步面补齐与解锁面同形的软件级降级声明；AC③ `sec_arch_desc` 的「任何明文数据都不会写入磁盘」须如实改写（大附件明文落私有目录 + 用户可触发的明文导出两项事实都要反映），或按 AC⑤ 处置后改写；AC④ passkey 徽标与文案去「芯片 / 硬件」表述，如实写「软件密钥（库内加密存储）」；AC⑤ 对「附件明文落盘」单独裁定：加密落盘（则 ② 前半句可保留）或维持现状并如实措辞，结论登记 `PD-*`；AC⑥ 新增接线守卫用例，锁定「文案分支跟随实测等级」（沿用 `AutofillChannelSwitchWiringTest` 的静态消费点计数口径），禁「有真值字段但无生产赋值」复现。

---

### ISSUE-P2-286：口令生成器熵读数虚高约 3 倍，且全站三套熵模型并存——同一口令三屏三数

- **核实时间点**：2026-09-23 经算式复算与模型清点。
- **核实方式**：`app/.../ui/screens/generator/DicewareWordList.kt:396-407` 以「长度 × log2(观测字符集)」估算；词表实测 **2011** 词，默认口令短语（`wordCount=4` / `capitalize` / `includeNumber` / `separator="-"`）读数 **195.7 位**，真熵 ≈ **54 位**（`4·log2(2011) + log2(90) + 首字母位`）；`:402` 的 `poolSize += 30` 对实长 **26** 的 `CHARS_SYMBOLS`（`:271`）是每字符 +4 的虚增，且按输出中出现的类别判定；`components/SecurityBadge.kt:78` 的 ≥96 档直判「极强」。第二套：`EntryEditFormSections.kt:298` 的 `PASSWORD_BITS_PER_CHAR = 4.5`（`:365`）；第三套：详情页 `detail/PasswordEntropyEstimator.kt:30-49` 调 Rust 真实熵内核。三套共用同一 `PasswordStrengthBar`。文案 `strings.xml:341-344 generator_strength_*` **无任何「理论上限 / 字符集熵」限定**；`PasswordGenerationEngineTest.kt:93-101` 反把字符集模型钉成期望值，无「生成器 = 详情页」对拍。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/generator/DicewareWordList.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt`（`:169-175`）、`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditFormSections.kt`、`app/src/main/java/com/keepasskey/app/ui/components/SecurityBadge.kt`。
- **验收标准**：AC① 熵读数**收敛为单一真相源**（走 crypto 侧强度内核的 `guessesLog10`），禁 UI 层自算；AC② 口令短语按「词数 × log2(词表) + 变形位」建模，禁「字符集 × 长度」代理；AC③ `poolSize` 与真实符号集常量绑定（单一来源），禁 `+30` 魔法数；AC④ 一致性用例：同一输出在生成器 / 编辑页 / 详情页读数**一致**（含短语模式）；AC⑤ 既有字符集模型用例只可改期望、不得删除（测试资产纪律 ①）。

---

### ISSUE-P2-287：会话锁定后口令生成器仍在屏上显示旧明文，点复制抛 `IllegalStateException`

- **核实时间点**：2026-09-23 经擦除路径与 Compose 重组键核对（**现象与首轮报告相反**：不是「显示空口令」，而是旧明文留存）。
- **核实方式**：`app/.../ui/screens/generator/GeneratorViewModel.kt:214-218` 的 `clearGeneratedSecrets()` 只对 `current.currentPassword` 与 `history` 各实例调 `.clear()`，**不更新 `_uiState`**；`GeneratorScreen.kt:163-165` 用 `remember(uiState.currentPassword) { readString() }`——key 是**实例本身**，未变 ⇒ 不重算，已物化的明文 String 继续渲染（`entropyBits` 与旧值自洽，用户不可辨）；此时点复制走 `GeneratorViewModel.kt:129` 的 `secret.useChars { ... }` → `core/security/ProtectedString.kt:92-93` 读点触发 `:172-174` 的 `checkNotCleared()` 抛 `IllegalStateException`，该处无兜底。生成器 tab 可达性只由偏好决定（`RealSettingsRepository.kt:109`、`KeePasskeyApp.kt:195-198`），**不以解锁状态门控** ⇒ 锁库后页面可达。
- **背景与根因**：与 `ISSUE-P2-65`（锁定即擦除生成结果）的意图**相反**——擦除做了，展示层未失效；属「已清零对象继续被消费」族（`ProtectedString` 的 fail-fast 正是为拦此类消费而设计）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorScreen.kt`。
- **验收标准**：AC① 擦除与状态失效原子完成（`_uiState.update` 交出已擦实例的新引用，令 `remember` key 变化），禁「只 clear 不发新态」；AC② 复制路径对已擦实例如实降级为「已锁定，请重新生成」提示，禁崩溃；AC③ 用例锁定「锁库 → UI 不再持有任何明文读数」与「复制不抛异常」；AC④ 生成器 tab 是否应受锁状态门控须给结论（接线或登记 `PD-*`），禁保留现状不声明。

---

### ISSUE-P2-288：主口令建立与修改路径无强度评估、无泄露校验（单字符可建库，健康检查还给满分；官方有硬门槛）

- **核实时间点**：2026-09-23 经强度内核消费点穷举与参考项目定点核实（**「别家也不拦」这一潜在豁免已被否证**，见对照段）。
- **核实方式**：`app/.../ui/screens/database/CreateVaultWizardDialog.kt:166-167` 的 `isFormValid` 仅要求 `vaultName.isNotBlank() && passwordChars.isNotEmpty() && contentEquals(confirmChars) && isKeyFileValid && isLocationValid`；向导其余步骤强度 / 长度关键词 0 命中（`CreateVaultWizardDialogSections.kt`），`DatabasePickerViewModel.kt:132-160` 直落库；改密路径同口径（`MasterKeyChangeDialog.kt:59/99`）。全仓强度评估的生产消费点仅两处：`app/.../detail/PasswordEntropyEstimator.kt:40` 与 `database/.../audit/HealthCheckEngine.kt:126`（均为条目口令），`BreachCheckCoordinator.kt:55-73` 亦只遍历条目 ⇒ **主口令永不参与**；`KdfStrengthAssessor` 只读外层头 KDF（限界 §8 `已知工程限界.md:328-336`），不覆盖主口令 ⇒ 1 个字符可建库，随后健康检查报 100 分。
- **对照（经开源码核实）**：官方 `参考项目/KeePass-2.61.1-Source/KeePass/Util/KeyUtil.cs:163-206` 有 `Program.Config.Security.MasterPassword.MinimumLength` 与 `.MinimumQuality` 的**硬失败**，空 / 弱口令走 AskYesNo；`KeyCreationForm.cs:163/265-267` 至少呈现质量条。`PD-01` 只裁 KDF 默认参数，**未**裁主口令强度。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/MasterKeyChangeDialog.kt`、`app/src/main/java/com/keepasskey/app/data/repository/RealVaultRepository.kt`（建库落库处）。
- **验收标准**：AC① 建库与改密共用**同一门槛判据**（纯函数单点化），至少含长度下限与强度评估（走原生内核，CPU 段置于 `Dispatchers.Default`，遵守 §3 规则 2 的热路径约束）；AC② 低于门槛的处置须明确：阻断或「显式二次确认 + 留痕」，结论登记 `PD-*`，禁默认放行且不声明；AC③ 主口令纳入健康检查与泄露检测口径（或在文档写明不纳入的理由与残余风险并登记限界）；AC④ 泄露检测沿用既有 k-匿名与 fail-closed 口径，禁把主口令明文写入日志或缓存；AC⑤ 用例覆盖「短口令被拒 / 弱口令需确认 / 达门槛直通」三态，中英文案成对。

---

### ISSUE-P2-289：`otpauth://` 不做百分号解码，编码过的种子被静默解成错误密钥（含 label 不解码）

- **核实时间点**：2026-09-23 经解析侧与 Base32 兜底行为逐环核对（覆盖面比首轮更宽：label 亦不解码）。
- **核实方式**：唯一生产入口 `app/.../data/repository/VaultEntryTotpMapping.kt:28`；全仓 `Uri.parse` 无一处用于 otpauth ⇒ 上游未解码；`core/.../otp/TotpKeyUriParser.kt:159-191` 以裸字节切 `&` / `=`，`:242-257` 的 `normalizeBase32` 仅大写去空白去 `=`；`core/.../otp/OtpEngine.kt:146-170` 的 `Base32Decoder.decode` **静默丢弃字母表外字符**（其 KDoc 的「宽容」只声明为兼容存量库展示，未声明百分号后果）⇒ `secret=JBSWY3DPEHPK3PXP%3D%3D` 实得 `JBSWY3DPEHPK3PXP3D3D`，出码永远不对且**无报错**；`issuer` / `account` 里的 `%20` / `%40` 原样入库显示（`:149/199-200`）；`digits=7` 在 `:204` 的 `6..8` 判定后静默回落为 6、未知 `algorithm`（含 RFC 4226 允许的 MD5）在 `:235-239` 回落 SHA1，均无诊断。编辑页 / 扫码 / 1Password PUX（`OnePasswordPuxImporter.kt:193-225`）**共用同一条码路**；测试无 `%3D` 命中，无 `ImportWarnings` 承接。
- **对照**：KeePassDX `OtpEntryFields.kt:152-236` 用 `Uri.getQueryParameter`（框架自带百分号解码）。
- **涉及文件**：`core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt`、`core/src/main/java/com/keepasskey/core/otp/OtpEngine.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt`。
- **验收标准**：AC① 参数值一律先百分号解码再归一（label / account 同修），禁自写切分产生语义分歧；AC② Base32 丢弃非法字符时须**可辨识地失败或告警**（禁静默出错码），且不得放宽既有「兼容存量库展示」的宽容口径——两者作用域须写清；AC③ `digits` / `algorithm` 回落须带诊断信息（禁静默改写），并保留既有钳制语义；AC④ 用例含 `%3D%3D` / `%20` / `digits=7` / `algorithm=md5` 四态，并锁定「非 otpauth 的既有字段路径不回归」；AC⑤ 与 `ISSUE-P3-273`（TOTP 字段映射不落盘）划清边界，两条各自验收。

---

### ISSUE-P2-290：KDF 派生异常路径跳过 `compositeKey` 清零——实现未达自登契约，且无需正确口令即可稳定触发

- **核实时间点**：2026-09-23 经契约条款、分派谓词与抛错链三方核对（抛错链另经独立复验）。
- **核实方式**：① 代码：`database/.../file/KdbxKeyDerivation.kt:53-59` 的 `compositeKey` 仅在 `transform` **正常返回后** `Arrays.fill`，无 `finally`；同文件 `:78-82` 与 `:146` 已刻意使用 `try/finally`，构成同文件内直接对照。同族第二处：`compositeFromPassword`（`:119-120`）在 `sha256(passwordBytes)` 抛出时 `passwordBytes` 与 `passwordHash` 均不清零；`KdbxCipherKeyResolver.kt:81` 的 `deriveLegacyKeys()` 调用点亦在 `try(:83)` 之外。② 契约：`docs/architecture/敏感缓冲所有权契约.md:170`（表第 12 行）**已把**该擦除钩子登记为「函数 `finally`」，R4（`:128-135`）亦强制 `finally` ⇒ 属实现未达自登契约，非已接受取舍。③ 触发可达性（关键）：`KdbxKdfParameterCodec.kt:222-231` 对 memory / iterations / parallelism **三项独立判界**（`ARGON2_MIN_MEMORY_BYTES = 8192`、`ARGON2_MAX_PARALLELISM = 64`），**无** `memory ≥ 8 × p × 1024` 交叉约束；`crypto/.../kdf/Argon2KdfEngine.kt:172-176` 的 `isWithinKdfBounds` 同样无交叉 ⇒ `(m=8192 B, p=64)` 通过分派谓词走原生；`NativeArgon2.deriveKey` 对非法参数返回 null（其 KDoc `:86` 明载），`derive`（`:123-124`）归一为 `CryptoException.KdfException`；`transform` 抛出即跳过清零。④ 时序：派生发生在头部 HMAC 校验**之前**（`KdbxFile.kt:145` 早于 `:148`）⇒ **攻击者无需掌握口令**。
- **泄漏物与定级口径（照此表述，勿夸大）**：滞留的是 **32 字节复合密钥** `SHA-256(SHA-256(口令) ‖ keyFileKey)`——危害是「取得即可绕过 Argon2 直接攻 AES 层」，**不是**主口令明文；口令明文字节的未清零面在 `compositeFromPassword` 那处（两处修法不同，建议同批并修）。须同时写明：进程内取证的读取面仍在限界 §2.2 划定的信任边界之外，本条整改理由是「**契约与代码漂移 + 免密可稳定触发**」，不是「新增高危泄露面」。
- **涉及文件**：`database/src/main/java/com/keepasskey/database/file/KdbxKeyDerivation.kt`、`database/src/main/java/com/keepasskey/database/file/KdbxCipherKeyResolver.kt`、`database/src/main/java/com/keepasskey/database/file/KdbxKdfParameterCodec.kt`、`crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt`。
- **验收标准**：AC① 两处（`compositeKey` 与 `compositeFromPassword`）改 `try/finally`，`KdbxCipherKeyResolver.kt:81` 调用点纳入同一保护范围；AC② KDF 参数校验补 **`memory ≥ 8 × parallelism × 1024` 交叉约束**，与内核 fail-closed 条件**逐项对齐**（禁「上游放行、内核拒」的口径缺口），并核对其余原生下界；AC③ 用例：越界库解锁失败后**不留任何未擦缓冲**，并以 `m=8192, p=64` 为固定样本；AC④ 契约表 `:170` 行措辞与实现对齐（改代码或改文档，不得两不相符）；AC⑤ **设备侧义务**：AC② 属改动原生分派谓词，按 AGENTS.md 测试资产纪律 ② 必须在设备上跑完 `:crypto:` / `:database:` / `:sync:` / `:app:` 四层 `connectedDebugAndroidTest` 方准入库，且**必须使用本机 `Pixel_10` AVD**，禁止在装有真实密码库的实体机上执行（§263 事故口径）。

---

### ISSUE-P2-291：同步配置 / 凭据 / 缓存只按 `remotePath` 键控，无库身份绑定 ⇒ 换库后仍可整库覆盖另一库的云端副本

- **核实时间点**：2026-09-23 经远端路径与凭据解析链核对。**注**：本条在对抗轮未列为独立攻击对象（首轮报告原列 P2），开工前须按规则 6.1② 自行复核「多库 + 多同步配置」的真实装配路径。
- **核实方式**：`app/.../sync/SyncProviderResolver.kt:113-138` 按库文件名 / 偏好推导 `remotePath`，`SyncCredentialsStore.kt:137` 的缺省远端路径为 `/keepasskey.kdbx`（**不含库身份**），`sync/.../SyncCache.kt:434` 的缓存键同为 `remotePath` ⇒ 两个库若指向同一远端对象（或用户改选库后未重配同步），本库整库字节会被上传覆盖对方云端副本，且基线 / 防回滚记录同样按 `remotePath` 记账，无法辨识「换库」。
- **背景与根因**：与 `ISSUE-P2-229`（SAF 库不参与同步）不同面——本条是**同步库身份与远端目标缺少绑定校验**。对照 keepass2android 按 `iocInfo`（含库上下文）解析存储对象（`GetFileStorage(iocInfo)`），非以路径字符串为唯一键。后果是跨库整库数据丢失，非字段级。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncProviderResolver.kt`、`app/src/main/java/com/keepasskey/app/sync/SyncCredentialsStore.kt`、`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt`、`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt`。
- **验收标准**：AC① 同步配置须绑定**库身份**（库头摘要或库 UUID）并参与凭据 / 缓存 / 基线 / 防回滚的键，换库即视为新配置；AC② 首次将「非本配置创建时绑定的库」推向远端时须显式二次确认（说明将整库覆盖），禁静默 PUT；AC③ 用例：两库共用同一 `remotePath` ⇒ 第二个库上传前被拦或需确认，缓存与基线互不串用；AC④ 与 `ISSUE-P1-275`（乐观锁，已闭环，见 `RESOLVED_LOG.md` §272）同批考虑：ETag 基线亦须在库身份变更后失效，不得沿用旧基线。

---

## P3 低危问题、特性接线与体验优化（12 项）

### ISSUE-P3-272：云端同步四项假开关（自动同步 / 事务化写入 / 预加载远程数据库 / 允许的 Wi-Fi SSID）

- **核实时间点**：2026-09-23 经全仓定向 grep 与消费方逐个核对核实。
- **核实方式**：① **自动同步** `autoSyncEnabled` 只活在 `SettingsSyncController` 的内存 `StateFlow`（`SettingsSyncController.kt:48/61/253-255`），`ExtendedSettingsStore` **无对应持久化键**；全仓 `autoSync` 命中仅设置页 / 投影 / 导航图，**无生产消费方**；缺省 `true`，重启即回 `true`。② **事务化写入** `useFileTransactions` **有**持久化键（`ExtendedSettingsStore.kt:82/171`，缺省 `true`），但全仓除 setter（`SettingsExtendedPreferencesController.kt:176`）/ 投影 / UI 外**无消费方**；本地写盘恒走 `AtomicFileWriter.writeAtomic`（`SessionFileWriter.kt:26-32`，条件仅为「是否生成 `.bak`」），上传恒走 `provider.uploadAtomic`（`SyncEngine.kt:192/259/287/347/415/448`，生产侧**无** `.upload(` 调用）——开关关不掉原子写。③ **预加载远程数据库** `preloadDatabaseEnabled` 有持久化键（`ExtendedSettingsStore.kt:85/174`），**零消费方**。④ **允许的 Wi-Fi SSID** `allowedWifiSsids` 有持久化键（同文件 `:72/167`），**零消费方**；同卡真正接线的只有 `wifiOnlySync`（`PeriodicSyncScheduler.kt:40/52` 映射 UNMETERED / CONNECTED）。
- **背景与根因**：三项「偏好只落盘不消费」+ 一项「偏好不落盘不消费」。前三项的界面文案已向用户承诺具体行为（`strings.xml`：`sync_auto_sync_sub`「数据变更后自动推送到云端」、`sync_file_tx_sub`「先写入临时文件再原子替换，意外中断不损坏数据库」、`sync_preload_sub`「Wi-Fi 下后台预取云端副本，加快解锁速度」）与「仅在指定 SSID Wi-Fi 下允许同步」（`SettingsUiState.kt:118`），实际行为与承诺无关；自动同步连回显都在重启后归位，属 `ISSUE-P2-228` 同型假开关。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncSections.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`、`app/src/main/java/com/keepasskey/app/sync/PeriodicSyncScheduler.kt`、`app/src/main/java/com/keepasskey/app/sync/SyncProviderResolver.kt`、`database/src/main/java/com/keepasskey/database/session/SessionFileWriter.kt`、`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt`、`sync/src/main/java/com/keepasskey/sync/provider/SyncProvider.kt`。
- **验收标准**：AC① 四项逐一给出**明确结论**——「真实接线」或「如实移除 / 禁用并标注」（对齐 `ISSUE-P3-65`：不得长期保留可拨动的假开关）；AC② 若接线：Wi-Fi SSID 须接入网络约束判定并与 `wifiOnlySync` 合流为**同一判据**（避免两个入口语义重叠）、预加载与自动同步须接到具体触发点（冷启动 / 变更后 / 保存后）；**事务化写入**须先裁定其是否属可关闭项——原子写是本仓数据安全不变量（`AtomicFileWriter` / `uploadAtomic` 全路径无绕过），若裁定「不可关闭」，则**如实移除该开关**（或改只读展示并写明恒为开启）并登记 `PD-*`，不得保留「关了也没变化」的开关；AC③ 若移除：同步删除 setter / 状态字段 / 投影 / 字符串键（中英两侧），并核对字符串键成对与总数；AC④ 任一改动都须同步修订 `strings.xml` 与 `values-en/strings.xml` 文案，使其**仅**描述真实行为；AC⑤ 新增接线守卫用例，禁「UI 有开关、生产无消费方」复现（沿用 `AutofillChannelSwitchWiringTest` 的静态消费点计数口径）。

---

### ISSUE-P3-273：TOTP 字段映射与默认参数四项「假开关 + 不落盘」（种子字段名 / 设置字段名 / 默认步长 / 默认位数）

- **核实时间点**：2026-09-23 经全仓定向 grep 与解析侧逐点核对核实。
- **核实方式**：① **解析侧写死**：条目 TOTP 配置定位只认 `KdbxConstants.Fields.OTP`（`"otp"`）与自定义字段前缀 `VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX`（`"TOTP"`，`VaultEntryTotpMapping.kt:19-32`；同型见 `VaultEntrySecretReader.kt:154/271`）；缺省参数写死 `DEFAULT_TOTP_PERIOD_SECONDS = 30` / `DEFAULT_TOTP_DIGITS = 6` / `DEFAULT_TOTP_ALGORITHM = "SHA1"`（同文件 `:98/106/124-126`）。② **零消费方**：全仓 `totpSeedFieldName` / `totpSettingsFieldName` / `defaultTotpStepSeconds` / `defaultTotpDigits` 命中仅 `ExtendedSettings` 字段、`SettingsExtendedPreferencesController` setter、`ExtendedSettingsStore` 读写、`SettingsUiStateProjection` 投影、`TotpSettingsScreen` 输入框——**无解析 / 取码侧消费方**。③ **不落盘**：唯一写入方 `updateTotpFieldMapping`（`SettingsExtendedPreferencesController.kt:191-200`）只调 `extendedSettingsStore.publish`（仅改内存权威快照，`ExtendedSettingsStore.kt:54-57` 自述「不改持久化」），**未调 `save`**；其 KDoc 亦自述「只更新内存 Flow、不落盘（ISSUE-P3-29 逐字保留）」。
- **背景与根因**：兼具两类缺陷——(a) **展示选择未应用**：用户在「TOTP 设置」页可自定义字段名与默认步长 / 位数，解析侧一律不理，故第三方库使用非 `TOTP` 前缀的自定义字段命名时行为与设置无关；(b) **回显漂移 + 丢失**：该映射仅驻内存快照，重启即回默认值（`TOTP Seed` / `TOTP Settings` / 30 / 6）；虽可能因其它偏好变更触发整表 `save` 被顺带写盘，但**不可依赖**（本项比「落盘但不消费」更弱）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/TotpSettingsScreen.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt`、`core/src/main/java/com/keepasskey/core/model/KdbxConstants.kt`（`Fields.OTP`）。
- **验收标准**：AC① **不依赖裁决即可先行**：`updateTotpFieldMapping` 改走 `updateExtended`（内存 + 持久化原子完成），消除「改了不落盘」；AC② 四项须给出「接入解析侧」或「如实禁用 / 移除输入项并标注」的明确结论，不得长期保留可改却无效的输入项；AC③ 若接入解析侧：字段名以**设置值优先、官方键回退**的顺序参与 `parseTotpConfig` 定位——**不得移除** `otp` 标准字段与 `TOTP` 前缀回退（官方 / 第三方兼容性不放松），默认步长 / 位数传入取码路径，且不得绕过 `TotpKeyUriParser` 既有的 `period` / `digits` 钳制语义；AC④ 新增用例锁定「设置值真实参与解析 / 取码」闭环（含缺省回退路径），既有 TOTP / HOTP 用例不得回归；AC⑤ 修订中英文案，使其**仅**描述真实生效范围。

---

### ISSUE-P3-274：安全与外观两项假开关（记住最近打开的数据库 / 图标集风格）

- **核实时间点**：2026-09-23 经全仓定向 grep 核实。
- **核实方式**：① **记住最近打开的数据库** `rememberRecentFiles` 有持久化键（`ExtendedSettingsStore.kt:91/178/332`，缺省 `true`），全仓命中仅 setter（`SettingsExtendedPreferencesController.kt:66`）/ 投影（`SettingsUiStateProjection.kt:172`）/ 设置页开关（`SecuritySettingsScreen.kt:375-378`）——**零消费方**，全仓无「最近数据库」列表或记录存取实现；**对照**同卡相邻的 `rememberKeyFileLocation` 真实接线于解锁侧（`SafKeyFileAccess.kt:43` 实际读取该偏好），故本项不能以「同类均未接线」为由豁免。② **图标集风格** `iconSet` 有持久化键（`ExtendedSettingsStore.kt:139/199`），可三选一（Material / KeePass 经典 / 极简单色，`ThemeSettingsListSections.kt:241-245`），但全仓 `IconSetOption` 命中仅 settings 域（`ExtendedSettings.kt` / `SettingsExtendedPreferencesController.kt` / `SettingsUiState.kt` / `SettingsViewModel.kt` / `ThemeSettingsScreen.kt` / `ThemeSettingsListSections.kt`）+ store——**渲染侧无任何消费**。
- **背景与根因**：均为「偏好只落盘不消费」。`rememberRecentFiles` 的界面语义是**安全项**（是否保留打开历史），实际不记录任何东西，用户以为「关闭即不留痕」——属安全语义误导；`iconSet` 属展示类，改选后全站图标无变化。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsScreen.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsListSections.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`（消费方落点待定：最近库记录源 / 图标渲染层）。
- **验收标准**：AC① 两项逐一给出「真实接线」或「如实移除 / 禁用并标注」结论；AC② `rememberRecentFiles`：若接线，须有真实的「最近打开库」记录源与清理逻辑，**关闭即不写入且清理既有记录**（对齐 `createBackupBeforeSave` 关闭时顺带清理历史 `.bak` 的既有口径），并说明记录存储位置与清理时机；若不接线则**移除**该项（安全项不得停留在「看起来能控制痕迹」的假状态）；AC③ `iconSet`：若接线，须把选择真实接入图标渲染层并覆盖三种风格；若不接线则如实禁用并标注（避免「可三选一但全站无变化」）；AC④ 修订中英文案，使其**仅**描述真实行为；AC⑤ 新增接线守卫用例，禁「UI 有开关、生产无消费方」复现。

---

### ISSUE-P3-292：合并历史取三方并集且不截断（未编辑过的条目永久留存对端快照，并放大 `ISSUE-P1-276` 的引用计数）

- **核实时间点**：2026-09-23 经截断挂载点核对（本条由首轮 P2 **降级**为 P3：原「撞 128 MiB 致全端停摆」的定性被否证——历史附件只是 `<Value Ref="x"/>` 短节点，膨胀驱动量是文本，冲破外层 128 MiB 载荷界需量级离谱的快照数）。
- **核实方式**：`sync/.../merge/KdbxEntryMerger.kt:168` 为 `local.history + remote.history + base.history` 三方并集，仅按 `times.lastModificationTime` 去重；条数 / 体积修剪函数 `pruneHistory` 只挂在 `database/.../history/HistoryManager.kt:48`（`recordHistorySnapshot`）与 `:83`（`rollbackToSnapshot`），保存路径的整树修剪只有 `pruneGroupHistoryByAge`（`:130`，按天数不按条数）⇒ **用户未再编辑过的条目，合并来的历史永久留存**；合并落库与写出（`SyncConflictController.kt:357/380`）不经任何截断。
- **后果**：① 每个历史快照是完整 `KdbxEntry`（含各自 `ProtectedString`），内存逐快照单调增长；② 回滚界面会出现对端旧版本；③ **与 P1-276 的耦合（`ISSUE-P1-276` 已于 §273 闭环）**——每多一条历史就给同一池条目多写一个 `<Ref>`，即 `N` 每轮 +1。P1-276 整改后该耦合的表现已由「**整库打不开**」降级为「**放大上限被推高**」：新判据「单条目引用次数 ≤ 1024」「单条目物化字节 ≤ 64 MiB」正是按『合并历史未截断』这一现状取的宽值，**故本条不落地，`MAX_REFERENCES_PER_POOL_ITEM` 就不能收紧**（见 [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§29** 的解除条件）。
- **对照**：KXC `Merger.cpp:452/598`（合并内 `truncateHistory`）、官方 `PwDatabase.cs:939` → `PwEntry.cs:646-685`（按条数 + 体积修剪）均在合并后截断。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt`、`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt`、`database/src/main/java/com/keepasskey/database/history/HistoryManager.kt`。
- **验收标准**：AC① 合并产物在落库前按库 Meta 的 `HistoryMaxItems` / `HistoryMaxSize` 截断（与 `pruneHistory` 复用同一函数，禁两份口径）；AC② 用例：双侧各 6 条历史合并后 ≤ 上限，且被截掉的快照仍按既有约定处理；AC③ 与 `ISSUE-P1-276` 的耦合**已单向闭环**（该条 §273 闭环，指针与耦合说明见 [`resolved/batches/273-附件池引用计费重定口径批次.md`](resolved/batches/273-附件池引用计费重定口径批次.md) 与限界表 **§29**）——**本条落地后必须复评** `KdbxAttachmentBudget.MAX_REFERENCES_PER_POOL_ITEM` 的取值依据（该上限当前正是按「合并历史不截断」取的宽值），复评结论须回填限界表 §29 与本节；禁「只修一个就算闭环」；AC④ 用例只可新增，不得删除既有合并历史用例（测试资产纪律 ①）。

---

### ISSUE-P3-293：剪贴板自动擦除的界面承诺与**已登记口径**不符，且冷启动对账可清除其它应用的内容

- **核实时间点**：2026-09-23 经实现分支与归档批次原文核对（行为本身**已登记**，本条的是文案与未登记的误清面）。
- **核实方式**：`app/.../security/ClipboardSecurityManager.kt:200-214` 的 `armScheduledClear` 按 `autoClearClipboard == false` 不调度，但 `:105-120/285-290` 的熄屏广播、`ON_STOP`、`onSessionLocked`、冷启动对账四条路径不查该设置直调 `clearPendingSensitive()` ⇒ 与 `res/values/strings.xml:651` / `values-en:640` 承诺的「一直留在系统剪贴板……直至被下次复制覆盖或设备重启」（并由 `detail_password_copied_no_clear`（`:329`）同向强化）相悖。方向为安全侧，且**行为已登记**于 `docs/resolved/batches/48-…:72-73`：「切后台即清与『自动擦除开关』**独立生效**；关闭自动擦除者亦受此保护」⇒ 属文案未跟上裁决。另 `reconcileOnColdStart`（`:300-306`）无摘要比对即 `clearClipboard()`，**可清除他应用写入的剪贴板内容**，批 48.4① 只登记了「不留口令等价物」，未登记此误清面。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`。
- **验收标准**：AC① 中英文案改为**仅**描述真实行为（写明「切后台 / 锁屏 / 锁库 / 冷启动四类时机不受该开关约束」），禁以 KDoc 或批次结论替代用户可见文案；AC② 冷启动对账须先比对摘要、只在确属本应用写入时清除，或在文案与设置项说明中如实声明「可能清除他应用内容」并登记 `PD-*`；AC③ 用例锁定「关闭开关后四类时机仍清」的既有已登记行为不被误改（防后续以文案为准的改动反向放宽安全性）。

---

### ISSUE-P3-295：附件面两处——按文件名（而非 `refIndex`）取字节致同名导出错内容；添加时无尺寸上限

- **核实时间点**：2026-09-23 经同名可否产生的前提核实（本仓**不可**产生同名不同内容，只能来自外部库；首轮所提「改按 UI 的 id 取字节」的修法前提被否证）。
- **核实方式**：① `app/.../data/repository/VaultEntrySecretReader.kt:279` 以 `attachments.firstOrNull { it.name == fileName }` 取字节，调用方 `EntryDetailAttachmentExporter.kt:39` 传 `attachment.fileName`，Toast 亦只报文件名 ⇒ 外部库（KeePass XML / Bitwarden / 桌面版）含同名附件时导出 A 得 B 的字节且提示为 A。解析侧逐 `<Binary>` 节点 emit、去重器原样保留 `name`（`KdbxXmlBinaryNode.kt:208-238`、`KdbxBinaryDeduplicator.kt:84-92`）；本仓编辑页按 fileName 视为替换（`EntryEditViewModel.kt:330-341`）故不自产同名。**修法前提**：`VaultEntryMapper.kt:60` 的 UI `id` 就是 `"${entry.id}_${att.name}"`（名字派生），改按 id 无法消歧，**须按 `refIndex` / 列表下标**。② `ui/screens/edit/EntryEditPickers.kt:64` 对 `*/*` 选择结果整份 `readBytes()` 交 `EntryEditViewModel.kt:330-341`；全仓无 `MAX_ATTACHMENT` 类约束（`BinaryStore.kt:47-57` 的 1 MiB 是落盘阈值非上限；`KdbxXmlBinaryNode.kt:58` 的 `AttachmentBudget` 默认 `unlimited()` 且只封解析不可信库）；无路径穿越面（`FileBinaryStore.kt:38` 只用固定目录名）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailAttachmentExporter.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditPickers.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt`。
- **验收标准**：AC① 附件寻址改按 `refIndex`（或树内下标），UI 的 `id` 生成同步去名字依赖；同名场景用例：导入含两个同名附件的第三方库 → 分别导出各自字节；AC② 对「添加无上限」给结论：设尺寸上限并如实提示，或维持现状并登记 `PD-*`（现状属用户自选大文件自伤 OOM，无攻击者面，故 P3）；AC③ 若设上限，须与解析侧预算的口径统一（`ISSUE-P1-276` 已 §273 闭环，现行三面判据与常量见 `database/src/main/java/com/keepasskey/database/xml/KdbxAttachmentBudget.kt`：单条目物化 ≤ 64 MiB / 引用次数 ≤ 1024 / 内联累计 ≤ 64 MiB），禁两套数字。

---

### ISSUE-P3-297：五项「可填 / 可存但界面无消费」的半成品能力（AutoType 序列 / 标签 / 收藏 / `VaultSyncStatus` / 搜索与到期字段）

- **核实时间点**：2026-09-23 经多口径交叉检索（符号名 + `R.string` 引用 + Manifest + 渲染点穷举）逐项确认。
- **核实方式**：① **AutoType 键入序列**可编辑并写回 KDBX（`EntryEditComponents.kt:199-206`、`strings.xml:378-379` 含 `{USERNAME}{TAB}{PASSWORD}{ENTER}` 占位符且**无免责措辞**），但全仓除映射 / 合并 / 比较（`VaultEntryMapper.kt:104/224/293`、`KdbxContentComparator.kt:86-95`）外**零执行方**，`app/autofill/` 与 `app/passkey/` 内 `autoType|overrideUrl` 0 命中。② **标签**只写不读：`app/src/main` 内 26 处命中，UI 侧仅编辑页输入（`EntryEditComponents.kt:191`）与搜索命中（`VaultListProjection.kt:327`），详情页 / 列表 / 导出零渲染。③ **收藏**能标不能看：`EntryDetailTopBar.kt:62-66` 唯一渲染点，持久化到 customData（`VaultEntryMapper.kt:91`），列表无徽标、`VaultSortOption`（`VaultListUiState.kt:24-32`）与筛选无收藏档。④ `VaultSyncStatus`（`VaultListUiState.kt:37/59`）枚举带 `labelRes` 却**零解析**（`sync_status_*` 实际由 `CloudSyncStatusSections.kt:76` 的 `syncStatusText` 承载）⇒ 纯冗余；CONFLICT / OFFLINE 已由 `VaultListScreen.kt:280` 的 `hasPendingConflict` 旁路。⑤ **搜索**为纯 `contains(ignoreCase)` 子串（`VaultListProjection.kt:320-331`，无整词 / 正则 / 含子组）；`KdbxTimes.expires/expiryTime` 有读端与序列化写端但 app 侧**零编辑入口**（`KdbxContentComparator.kt:32` 自证无写入者，`cardExpiry` 系银行卡自定义字段、非 KDBX 过期）。
- **涉及文件**：上列各文件 + `app/src/main/res/values/strings.xml` 与 `values-en/`。
- **验收标准**：AC① 逐项给「接线 / 如实标注 / 移除」三选一结论（对齐 `ISSUE-P3-65` 假开关处置口径），禁继续保留「可填可存但行为为零」的输入项；AC② AutoType 若维持无执行方，须在字段说明中如实标注「本应用不执行键入序列，仅为兼容性保存」并登记 `PD-*`；AC③ 标签 / 收藏若接线，须同时进详情页渲染与列表筛选（禁只补一半）；AC④ `VaultSyncStatus` 冗余枚举按「删除死代码」口径处置；AC⑤ 搜索增强与 `expires` 编辑入口属能力补齐，可拆独立条目，须中英文案成对。

---

### ISSUE-P3-298：借鉴参考项目仍缺的可达性与反馈能力（外部打开入口 / 大屏双栏 / 请求级重试 / 后台失败可见 / OTP 直填 / CM 排序）

- **核实时间点**：2026-09-23 经 Manifest、调度链与参考项目定点核实；其中后四项第一轮由 sync 代理提出、**未经对抗轮单独攻击**，认领时须自行复核。
- **核实方式与清单**：① 无 `.kdbx` 的 `ACTION_VIEW` / `SEND` intent-filter——三份 Manifest 检索后唯一 VIEW 出现在 `<queries>`（`AndroidManifest.xml:42-45`，属包可见性非入口）；SAF 已在页内承接（`DatabasePickerScreen.kt:157`、`OpenExistingVaultDialog.kt:43`），限界 §24（`已知工程限界.md:598`）认 SAF 为准 ⇒ 属**习惯可达性**缺口（官方 / KeePassDX 支持「打开方式」直达）。② 宽屏仅换 NavigationRail（`KeePasskeyApp.kt:362-369`，`screenWidthDp >= 600`），无 `ListDetailPaneScaffold` / `WindowWidthSizeClass` 命中 ⇒ 大屏仍逐页跳。③ 无请求级重试与退避：`app/.../sync/PeriodicSyncWorker.kt:31-41`（含异常也 `Result.success()`）、`sync/.../network/SyncHttpClientFactory.kt:38-51` 只设超时 ⇒ 瞬时 5xx / 429 只能等下个周期（对照 kp2a `BackgroundSyncService` 按状态码重试）。④ 后台同步失败零可见：`SyncCoordinator.kt:108-112` 的 `syncEvents` **全仓无订阅者**，`app/.../notification/NotificationChannels.kt:31-45` 仅「已解锁」与「填充验证码」两条通道 ⇒ 库可连续数周未同步而用户无感（与 `ISSUE-P3-272` 的「自动同步开关是假的」不同面）。⑤ OTP 字段从不直填：`AutofillPostFillTotpActions.kt:52-74` 只复制 / 通知，`AutofillFieldScanner.kt:124-131` 无 `one-time-code` / `SMS_OTP` 通道（定向检索 0 命中）；KeePassDX 有独立 `otpTokenId` 槽并真实填充（`StructureParser.kt:149/339` → `AutofillHelper.kt:314-323`），KeePassXC 有 `get-totp`。设置项文案自我限定为「复制到剪贴板 / 通知」（`strings.xml:861-866`），未宣称填充 ⇒ 属未实现的对齐项、非谎报。⑥ CM 通道无「上次使用置顶」：`CredentialResponseAssembler.kt:137-155` 未用 `AutofillLastFilledStore`——但本仓服务**未实现** `onCompleteGetCredentialRequest`（全仓 0 命中），CM 侧当前无「被选凭据」信号可作数据源 ⇒ 属框架接线面。
- **涉及文件**：`app/src/main/AndroidManifest.xml`、`app/.../ui/KeePasskeyApp.kt`、`app/.../sync/PeriodicSyncWorker.kt`、`sync/.../network/SyncHttpClientFactory.kt`、`app/.../notification/NotificationChannels.kt`、`app/.../autofill/AutofillPostFillTotpActions.kt`、`app/.../passkey/CredentialResponseAssembler.kt`。
- **验收标准**：AC① 逐项给「补齐 / 维持并登记 `PD-*` / 记限界」结论，禁停留在清单状态；AC② 若补④：须新增同步失败通知渠道（可静音、可关）并让 `syncEvents` 有真实订阅者，同时保持 `PeriodicSyncWorker` 的 `Result.success()` 不再吞掉可观测性；AC③ 若补③：重试须与 `ISSUE-P1-275` 的乐观锁语义合流（已闭环，见 `RESOLVED_LOG.md` §272；重试前重新校验基线），禁「重试即无条件 PUT」；AC④ 若补⑤：不得移除既有复制 / 通知路径，且须走 `AutofillValue` 正规通道；AC⑤ ⑥须先取框架侧权威依据（Credential Manager 回调契约）再定可行性，取不到则如实标注未证实。

---

### ISSUE-P3-300：弱 ETag 乐观锁的**真实 DAV 服务器矩阵未实测**——弱 ETag 服务器上的同步收敛行为待证（§272 AC⑤ 显式残余）

> 本条**不属**上文「`ISSUE-P1-276` ～ `ISSUE-P3-299`」审查批例外范围：它由 §272（`ISSUE-P1-275` 闭环批）
> 的 AC⑤ 未执行残余升级为待办承接，与 [`已知工程限界.md`](architecture/已知工程限界.md) §28 互为指针。

- **核实时间点**：2026-09-23（§272 整改当日，随批如实登记）。
- **核实方式**：① §272 已完成的证据面＝RFC 全文核对（RFC 7232 §2.3 / §3.1、RFC 4918 §8.6 / §10.4.2 / §10.4.4 / §10.4.9，rfc-editor.org 原文）+ 有状态 mock 的头形态与弱比较裁决守卫（`StatefulDavDispatcher.weakEtagPaths`）——**协议层与形态层已证**。② 条目 AC⑤ 要求的**真实服务器矩阵**（Apache/mod_dav、nginx-dav、Nextcloud、IIS）当日无环境可用；`tools/local-sync` 联调链路代理侧不可运行（既有口径）且 `run_webdav.py` 不产 ETag，本就不构成弱面取证 ⇒ **未执行**，登记于批次文档 §3.3 与限界表 §28。
- **背景与根因**：§272 弱 ETag 收口选定「回传服务器签发原形态」取向（MOVE `If` 头携 `W/"…"`；`If-Match` 弱期望不发送；S3 弱期望 fail-closed）。理论边界已经 RFC 划清：**弱比较**服务器上原形态必匹配；**强比较 × 弱存储标签**的组合下任何客户端的实体标签预条件均不可满足（RFC 7232 §2.3 强比较要求两侧均非弱），属服务器自绝于条件写，本仓退化为 412 → 冲突重检（不静默覆盖、不丢数据，但同步可能反复提示冲突）。**未证的是**：真实服务器对 RFC 4918 §10.4.4「弱或强比较二选一」的实际取向、以及 MOVE 事务写在弱 ETag 服务器上的兼容性——这决定弱 ETag 服务器（Apache/mod_dav 部分文件系统配置为高发面）上同步是正常收敛还是反复冲突提示。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 `docs/resolved/batches/272-冲突时刻ETag透传与弱校验收口批次.md` §3.3 与 `docs/architecture/已知工程限界.md` §28。
- **验收标准**：AC① 四类服务器各实测三项读数并按规则 8 留证（命令 + 原始响应）：签发 ETag 的强/弱形态；MOVE `If` 头对弱形态（`[W/"…"]`）与强形态（`["…"]`）预条件的接受性；PUT+MOVE 事务写兼容性。AC② 实测**证实**「回传原形态」取向 ⇒ 回填两处登记并闭环本条；实测**推翻** ⇒ 不得就地放宽 §272 守卫用例（限界表 §28 边界条款），须另行立条裁决新取向。AC③ 无法取得的环境（如 IIS）逐项如实标注未执行，禁以 mock 绿推定闭环。AC④ 环境不可得期间，本条与限界表 §28 维持开放，不得归档。

---

### ISSUE-P3-301：`runSyncCycle` 步骤 3 的 `isCached` 判据仍在调用方线程（§274 显式残余）

- **核实时间点**：2026-09-23（§274 整改当日，随批如实登记）。
- **核实方式**：① §274 已把**装配段**（`setupCycleContext`）整体下沉 `Dispatchers.IO`（`SyncCycleRunner.kt:218`），并以 `SyncAssemblyOffMainThreadTest` 锁定凭据解密 / 整库缓存读 / 全库比较 / 原子写四类的执行线程（含栈归因与判别力实验）；② **本条所指调用点不属装配段**——`SyncCycleRunner.kt:243` 的 `if (ctx.isDirty && ctx.syncCache.isCached(ctx.remotePath))`（步骤 3 前置判据）位于该 `withContext` **之外**，仍执行在调用方线程（前台入口持 `viewModelScope`，即 `Main.immediate`）；③ 该调用为 `SyncCache.isCached()`（`SyncCache.kt:59-62`）＝ `exists() && length() > 0`，**一次 stat 级系统调用**，不读内容、不 `fsync`，与四类的量级相差数个数量级——§274 的「主线程零 IO」口径据此把四类与它分列。
- **背景与根因**：§274 整改时**刻意不动**该点，理由是「改它需先论证 `ctx.isCached`（装配段取样值）与步骤 3 重取恒等价」，而该等价依赖「步骤 2 只在返回非 null 时写缓存」这一耦合推理（`establishRemoteBaselineIfMissing` **仅在** `FileNotFound` 分支 `commitLocal` 后 `return`，其余分支返回 null 且不写缓存），牵动同步状态机判定，风险高于收益 ⇒ 当批以「残余如实登记、不宣称为主线程零 IO」收口，本条承接后续处置。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt`（`:243` 重取点；`setupCycleContext` 内 `:127` 为首次取样）、`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt`。
- **验收标准**：AC① 二选一但须**单点化**——(a) 把该判据并入已下沉的装配段产出（**不得**直接换成 `ctx.isCached` 了事，须先补「步骤 2 与步骤 3 之间缓存状态不变」的显式不变量声明或断言）；或 (b) 保留重取但以 `withContext(Dispatchers.IO)` 包裹，并写明为何不接受 `ctx.isCached`；AC② 判据语义不得改变：两步之间的**可观测差异**（若存在）须在条目正文写明，禁「为消除一次 stat 而改动状态机判定」；AC③ 新增或扩展守卫，断言该判据不在主线程执行（沿用 `SyncAssemblyOffMainThreadTest` 的探针 + 栈归因口径）；AC④ 若最终裁定「一次 stat 可接受、不作处理」，须登记 [`docs/architecture/已知工程限界.md`](architecture/已知工程限界.md) 并写明解除条件，不得无声留着。

---

### ISSUE-P3-302：大库下拉刷新的**主线程阻塞量级未实测**（§274 AC④ 显式残余）

- **核实时间点**：2026-09-23（§274 整改当日，随批如实登记；与 `ISSUE-P3-300` 同型）。
- **核实方式**：① §274 已完成的证据面＝**调度边界层**：`SyncAssemblyOffMainThreadTest` 以五个探针（栈归因 + 非空性断言 + 线程身份断言）锁定装配段四类重活不在主线程执行，判别力实验（撤销下沉 ⇒ 五类探针全落主线程基准即红、恢复即绿）证明守卫紧扣缺陷本身；② 条目 AC④ 要求的**真机量级读数**（大库下拉刷新的帧耗时 / 主线程阻塞时长）当日本会话**无设备**，未执行任何真机或 `connected` 用例 ⇒ **未执行**，登记于批次文档 §2.4 与 §3.4。
- **背景与根因**：§274 的整改依据「大库下为**数百毫秒至秒级**」是**估算**（同一主线程上做 Keystore 解密 + 整库密文读 + 全库逐字段比较 + 整库写 + `fd.sync()` 的量级推理，非本机实测）。按定义整改后主线程应只剩一次 stat，但**未证的是**：真实大库（万级条目 / 含附件）下装配段下沉后的实际主线程阻塞与帧耗时，以及把该段整体挪到 `Dispatchers.IO` 后对**同步总时长**的影响（阻塞被移走，该段本身仍须执行完）。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 [`resolved/batches/274-同步周期装配段下沉IO批次.md`](resolved/batches/274-同步周期装配段下沉IO批次.md) §2.4 / §3.4，并按需登记 [`architecture/已知工程限界.md`](architecture/已知工程限界.md)。
- **验收标准**：AC① 接入设备后构造**大库**（万级条目，含 ≥1 MiB 落盘附件与历史快照）实测下拉刷新：记录装配段耗时、主线程最长连续阻塞、掉帧数与同步总时长，并按「量级须实测」留证（工具 + 原始读数）；AC② 实测**证实**「主线程无同步重活」⇒ 回填批次 §2.4 / §3.4 并闭环本条；实测**推翻**（仍见秒级主线程阻塞）⇒ 另行立条定位残余阻塞点（含 `ISSUE-P3-301` 的 stat 面与 `SyncCache` 之外的调用），**不得**就地改判 §274 的守卫口径；AC③ 无设备期间本条维持开放，**不得**以 §274 的 JVM 守卫绿推定量级已证；AC④ 若同批接入设备，与 `ISSUE-P3-300`（真实 DAV 矩阵）一并执行以减少设备占用。

---

### ISSUE-P3-303：不可擦 `String` 明文残留分层——导出侧 `readString()` 可收口，模型层字段 `String` 不可在导出器层解决

> 本条承接 [`敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md) §4 **#19** 的摘除条件
> （「若日后改走 `useChars` 路径可摘除本行」）与 §278 批次 §5.1 的如实声明；两类残留**性质不同**，
> 判据与去向分列，禁混为一谈。

- **核实时间点**：2026-09-23（§278 整改当日，就「不可擦 `String` 能不能解决」命题逐层核对模型与写出口）。
- **核实方式**：
  1. **导出侧 `readString()`（可解）**：`KdbxCsvExporter.kt:76` 的 `entry.password?.readString().orEmpty()`、`KeePassXmlExporter.kt:75/91` 的 `password.readString()` / `field.value.readString()` 三处，物化 JVM `String` 后驻留至 GC。`ProtectedString` 已提供 `useChars` 闭包（`ProtectedString.kt:132-139`，`CharArray` 用毕自动 `fill`）；写出口现状只收 `String`——`KdbxCsvExporter.writeField(writer, value: String)`、`KdbxXmlWriteUtil.textElement(..., text: String)`。⇒ **技术上可解**：加 `CharArray` 写出口（含 XML 转义 / CSV RFC 4180 引号化的 `CharArray` 版），三处改走 `useChars`，产物字节不变。
  2. **模型层字段 `String`（导出器层不可解）**：`KdbxEntry` 的 `title` / `userName` / `url` / `notes`（`KdbxEntry.kt:28-40`）与 `KdbxGroup.name` / `notes`（`KdbxGroup.kt:10`）在**模型上就是 `String`**，导出器只是读取既有 `String`——`readString()` 只出现在 `ProtectedString` 字段（口令、自定义字段值）。清这批须改模型层形态（`ProtectedString` / `CharArray`），牵动映射 / 合并 / 比较 / UI / 序列化全链，**不在导出器层可解**；其性质与限界 §2.4（Compose 文本状态不可擦 `String`）/ RC-01 同族。
- **背景与根因**：§278 按 AC② 二选一取了「登记」分支（限界已接受 + 可收敛点是一致性），把「改 `useChars`」留作摘除条件未实施。用户 2026-09-23 追问「不可擦 `String` 能不能解决」后逐层核实：**一半能、一半不能**，须分开登记，避免「已登记限界」被读作「两类都动不了」或「改 `useChars` 就全干净了」。
- **涉及文件**（仅第 1 类）：`database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt`、`database/src/main/java/com/keepasskey/database/xml/KeePassXmlExporter.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlWriteUtil.kt`（`textElement` 增 `CharArray` 出口）；契约 [`敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md) §4 #19。
- **验收标准**：
  - **第 1 类（导出侧 `readString()`，可解，本条主整改面）**：AC① 三处 `readString()` 改走 `useChars` + `CharArray` 写出口（`writeField` / `textElement` 须有 `CharArray` 重载或等价路径，XML 转义与 CSV 引号语义逐字节等价）；AC② 产物字节与改前**逐字节等价**（`KdbxCsvExporterTest` 4 例 + XML 既有产物断言原样通过，必要时补对照例）；AC③ 完成后**摘除契约 #19**（或就地改写为「已收口」并留痕），禁「代码已改、契约仍记残留」；AC④ 新增守卫锁定「导出器不得再出现 `readString()`」（静态接线，口径同 `WipableByteArrayOutputStreamTest`）。
  - **第 2 类（模型层字段 `String`，导出器层不可解）**：AC⑤ **不在本条整改**——按限界口径处置：若维持现状，须在限界表登记（或扩写 §2.4 同族）并写明「模型层 `title`/`url`/`userName`/`notes`/分组名以 `String` 驻留，进程内取证在信任边界外」；若要收口，须**另立条目**评估模型层改造（`ProtectedString` 化或 `CharArray` 字段）的牵动面，禁在本条顺手改模型。
    **（2026-09-23 §284 补全登记）**：AC⑤ 的「维持现状 + 限界登记」分支已落地——限界表新增 **§2.7**，单列**展示层**（`GeneratorScreen` `readString()` / `EntryDetailSecrets` `toDisplayString()` 与三个 `String` 状态）与**模型层**（`KdbxEntry` / `KdbxGroup` 元数据字段）两类驻留，并与 §2.4 / §2.6 分列；解除条件仍须另立条目。第 1 类（导出侧 `useChars`）**仍未实施**，本条继续开放。
  - **通则**：AC⑥ 两类的结论与去向必须**分列写明**，禁以「不可擦 `String` 都是已接受限界」一句带过（第 1 类恰恰**可以**收口）。

---
