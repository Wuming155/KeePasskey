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

## P1 高危与核心功能问题（1 项）

### ISSUE-P1-277 解锁节流完整性层的 fail-closed 分支不受节流开关约束，且在 Keystore HMAC 不可用时不可自愈

- **优先级**：P1（核心流程阻断——主密码解锁与子库挂载两条路径可被**持续**拒绝，KDoc 自称的「有界锁定」不成立；触发条件是非攻击的 Keystore 故障）。
- **核实时间点**：2026-09-25（直读 `app/src/main` 全部相关源码 + 单测 + `SettingsRepository` 默认值 + `AndroidManifest.xml` / `res/xml/data_extraction_rules.xml`）。
- **核实方式**：逐行直读 `UnlockThrottle.kt` / `UnlockThrottleIntegrity.kt` / `MasterPasswordUnlockSession.kt` / `ChildDatabaseSessionManager.kt` 的 `gate` 消费链；出厂默认值经 `SettingsRepository.kt:56` 与 `RealSettingsRepository.kt:114` 双向核对；「全仓无删除点」经定向检索别名 `unlock_throttle_integrity` 确认（仅命中其定义处）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/security/UnlockThrottle.kt`、`UnlockThrottleIntegrity.kt`、`app/.../ui/screens/unlock/MasterPasswordUnlockSession.kt`、`app/.../data/childdb/ChildDatabaseSessionManager.kt`、`app/.../data/repository/{SettingsRepository,RealSettingsRepository}.kt`、`app/src/androidTest/.../UnlockThrottleDeletionBypassDeviceTest.kt`。
- **参考项目对照**：[`references/本地文件级防护层的参考项目对照.md`](references/本地文件级防护层的参考项目对照.md) §1（2026-09-25：五项目**无一实现**主密码失败计数 / 锁定；kp2a 仅 QuickUnlock PIN 有计数且不落盘；Monica 明示「Android owns retry/lockout policy」）。
- **背景**：本层由 `ISSUE-P3-54`（Keystore HMAC）与 `ISSUE-P2-45`（每库一条存在性标记 + `reset` 改写零值记录）构成，目的是封死「删掉 `shared_prefs` 三个键即复位失败计数」的旁路；层内自陈的威胁模型边界见 `UnlockThrottleIntegrity.kt:94-96`。
- **问题 ①（缺开关门控）**：`gate` 的完整性分支排在 `if (!config.enabled)` **之前**，且锁定上限硬编码 `UnlockThrottlePolicy.MAX_BACKOFF_MS`（30 分钟）而非 `config.maxBackoffMs`（`UnlockThrottle.kt:286-298`）；而 `unlockThrottleEnabled` 出厂为 `false`（`SettingsRepository.kt:56` / `RealSettingsRepository.kt:114`）⇒ **从未开启过节流的用户同样会被这条 fail-closed 拒绝**，其自订的最长锁定（合法域下限 60s）对该分支无效。
- **问题 ②（不可自愈，KDoc「有界」不成立）**：`read` 把**空串 MAC** 当作缺失（`UnlockThrottle.kt:93-94` 的 `takeIf { it.isNotEmpty() }`），而 `persist` 恰在 `integrity.mac()` 返回 null 时写入**空串**（`:114-116` 的 `.orEmpty()`）⇒ 只要 Keystore HMAC 持续不可用（`UnlockThrottleIntegrity.kt:105-116` 的 `catch → null`），`gate` 每次都会重写一条 MAC 仍为空的锁定记录并再判 `integrityIntact=false`，**每次调用重锁 30 分钟**。`UnlockThrottle.kt:124-130` 所称「当前 Keystore 故障下首启也只遇一次有界锁定（上限 `MAX_BACKOFF_MS`）」据此**不成立**。消费侧 `MasterPasswordUnlockSession.unlock:95-108` 与 `ChildDatabaseSessionManager.mount:229-231` 在 `Locked` 时直接返回 ⇒ 在这条链上主密码解锁恒被拒，而用户看到的仍是「请等待 N 分钟」（等待无效）。
- **问题 ③（无应用内自愈路径）**：该 MAC 密钥别名全仓**无删除点**，节流 prefs 也不在「撤销全部生物识别数据」的清理面内（后者清的是 `com.keepasskey.biometric_credentials`，`BiometricCredentialStorage.kt:135-158`）⇒ 没有可自愈入口；而用户唯一会想到的「清除应用数据」会连同 `filesDir/*.kdbx` 一起删除（列表按 `filesDir/*.kdbx` 扫描，`PD-17`），即数据丢失。
- **问题 ④（威胁模型冗余性，与 AC② 同批裁决）**：本层目标对手是「能写 `shared_prefs` 的文件级写者」。本机上该角色要么升级为同 UID / root——此时可读同目录 `filesDir/*.kdbx` 直接离线爆破、或直接替换库文件，节流不构成任何阻力；要么**不存在**——`allowBackup="false"`（`AndroidManifest.xml:77`）且 `data_extraction_rules.xml` 对 cloud-backup 与 device-transfer **两个面、九个域全部 exclude**，无代码执行的文件写入路径已被封闭。**补强证据**：即便把对手限定为「只能改 prefs」，本层也拦不住——MAC 载荷只覆盖 `databaseId + failureCount + lockoutUntilEpochMs`（`UnlockThrottleIntegrity.kt:19-25`），**不含任何新鲜性来源**；攻击者只需事先存下一份合法的低计数组（`reset` 本身就会写一条 count=0 且带有效 MAC 的记录）事后回写即可复位，存在性标记仍在案、不触发任何检测 ⇒ 它封住了「删键」，却没封住同族的「回写旧记录」。
- **处置口径（2026-09-25 用户裁决：**移除完整性层**，基础节流保留）**：
  - **整文件删除**：`app/src/main/java/com/keepasskey/app/security/UnlockThrottleIntegrity.kt`（172 行，含 `UnlockThrottleIntegrity` / `AndroidKeystoreUnlockThrottleIntegrity` / `UnlockThrottleMacPayload` / `UnlockThrottleExistenceMarkerAlias`）；`SecurityModule.kt:40-45` 的 `bindUnlockThrottleIntegrity` 与 `:3,11` 的 import。
  - **部分摘除**：`UnlockThrottle.kt` —— `UnlockThrottleRecord.integrityIntact`（`:20-27`）、store 的 `integrity` 构造参数与全部 MAC / 存在性标记读写（`:78,83-96,99-102,106-111,113-122,124-130,134`）、`gate` 的 `!integrityIntact` fail-closed 分支（`:283-296`）。
  - **`reset` 回退为删键**：`ISSUE-P2-45` 的「写零值记录」动因（使「三键全缺」不再有合法来源）随存在性标记一同消失，故回退为删除计数 / 锁定两键；设备上遗留的 `_unlock_mac` 偏好键与 Keystore 标记别名**惰性无害**（登记为残留，不做迁移）。
  - **保留**：`UnlockThrottlePolicy` / `ThrottleConfig` / `ThrottleConfigSource` / `UnlockThrottleStore` / `SharedPrefsUnlockThrottleStore` 计数主干 / `UnlockThrottleManager` 的 `gate`（其余分支）/ `registerFailure` / `registerSuccess`，以及 `UnlockViewModel`、`ChildDatabaseSessionManager` 两个消费点。
  - **测试资产（共删 24 例）**：整文件删 `UnlockThrottleIntegrityTest`（7）、`SharedPrefsUnlockThrottleStoreTest`（10）、`FakeUnlockThrottleIntegrity`、设备侧 `UnlockThrottleDeletionBypassDeviceTest`（5）；`UnlockThrottleManagerTest` 摘除 `记录完整性校验失败时闸门failClosed并落有效锁定期`（`:120-141`）与 `完整性failClosed不受开关关闭影响`（`:208-222`）；`FakeUnlockThrottleStore` 保留。
- **AC（按移除口径重写）**：
  1. 上述删除 / 摘除完成，`gate` 只保留 `config.enabled` 门控与计数 / 退避判定（完整性分支与其对 `MAX_BACKOFF_MS` 的硬编码一并消失）；
  2. 删除理由按 `.codebuddy/rules/engineering-rules.md`「测试资产纪律」登记于批次文档，与生产代码同一次提交入库；
  3. `.\gradlew.bat test` 全绿 + `python tools/doc/gate_readings.py` 7/7 PASS（读数块原样贴入批次文档）；`:app:connectedDebugAndroidTest` 真机实跑全绿（本批删除设备侧 Keystore 用例，须确认 `:app:` 层无残留失败）；
  4. 批次文档登记「遗留 `_unlock_mac` 键 + 标记别名惰性无害」这一残留事实（不得写成「已清理」）。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-24 同步/加密/passkey 安全审计批五条（`ISSUE-P2-308` ~ `ISSUE-P2-312`）
> 已全部闭环：§315（309 / 312）、§317（308）、§318（310）、§319（311）、§320（313）；
> 2026-09-25 CI 设备门禁与供应链扫描两条（`ISSUE-P2-314` / `ISSUE-P2-315`）闭环见 §325。

## P3 低危问题、特性接线与体验优化（3 项）

> 2026-09-25 近期闭环：CI 触发频率治理（`ISSUE-P3-316`）见 §326；ReDoS 正则修复（`ISSUE-P3-318`）见 §327 / §328；
> 扫码栈迁移（`ISSUE-P3-319`）与扫码回显（`ISSUE-P3-320`）见 §328；CodeQL 默认设置停用（`ISSUE-P3-317`）见 §329；
> 供应链告警豁免（`ISSUE-P3-321`）与 Dependabot 积压 PR 合并（`ISSUE-P3-322`）见 §330；动效节奏降档复定标（`ISSUE-P3-323`）见 §331；
> 无障碍假提示摘除与旧版无障碍填充通道落地（`ISSUE-P3-324`）见 §332；运行完整性门整体移除（`ISSUE-P3-325`，`PD-45` 推翻 `PD-13` / `PD-15`）见 §333。
>
> 下列三条为 **2026-09-25 新增的「纵深防御层冗余性评估」类条目**（与 `ISSUE-P1-277` 同一轮取证）。
> 三条的共同前提：目标对手同为「本地文件级写者」，而该角色在本机上可读同目录 `filesDir/*.kdbx`
> （离线爆破或直接替换库文件），且**无代码执行的文件写入路径**已被 `allowBackup="false"`
> （`AndroidManifest.xml:77`）与 `res/xml/data_extraction_rules.xml`（cloud-backup / device-transfer 九域全 exclude）封闭。
>
> **参考项目对照已完成**：见 [`references/本地文件级防护层的参考项目对照.md`](references/本地文件级防护层的参考项目对照.md)
> （2026-09-25；§2 / §3 / §4 分别对应 `ISSUE-P3-326` / `327` / `328`）。净结论：四条均属**本仓自创加固**，
> 品类内**无可援引的保留依据**，亦**不得**以「参考项目都没做」作为移除依据；唯一可用判据是「对在模型内的对手是否有增量」。
>
> **2026-09-25 用户裁决：四条全部移除**（原话「这几项功能都没有必要，可以全部去掉。软件只需要保留自定义的几个文件备份就可以了」）。
> 三处删除边界由代理裁定（用户授权「自行判断，不要问我」），按**最小爆炸半径**落定：
> ① 同步防回滚——只删状态文件 MAC，**保留「已见内容摘要链」引擎**；
> ② 快速解锁断言——**整层移除**（快速解锁只留「封印凭据 + per-operation 生物识别」这一真闸门）；
> ③ 字段级屏蔽——**保留功能**，去掉 Keystore 密钥依赖，改存明文目标并同步改写安全表述。
>
> **影响面已核实（2026-09-25 只读调研，两路共 150+ 次定向检索）**：各条目的精确删除清单（整文件 / 部分摘除）、
> 测试资产对账、**退役残留清理**（设备侧 Keystore 别名不得成为无人管理残留）与文档同步项，见各条目「处置口径」段。
> **代码尚未改动**——下一步按批次执行（整改 + `.\gradlew.bat test` + 门禁 7/7 + 真机四层 + 归档 + `git push`）。

### ISSUE-P3-326 防回滚状态文件的 Keystore MAC（`KeystoreSyncIntegrityMac`）：冗余性评估与威胁模型口径登记

- **优先级**：P3（纵深防御卫生层；失败方向 fail-open，不产生误锁）。
- **核实时间点**：2026-09-25（直读 `KeystoreSyncIntegrityMac.kt` / `SyncRollbackGuard.kt` + [`security/同步层记录级完整性威胁建模.md`](security/同步层记录级完整性威胁建模.md) §3.3 + [`references/存量5项开放问题的参考项目对照.md`](references/存量5项开放问题的参考项目对照.md) §1）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/KeystoreSyncIntegrityMac.kt`、`sync/src/main/java/com/keepasskey/sync/engine/SyncRollbackGuard.kt`、`sync/.../engine/SyncIntegrityMac.kt`。
- **背景与边界（自陈）**：状态文件位于 `filesDir/rollback/`，由 Keystore 内不可导出 HMAC 密钥认证（`ISSUE-P2-18`）。其覆盖面**只在 T2**：远端对手触碰不到本地文件；纯「云账号被盗」由既有「已见内容摘要链」拦截。`SyncRollbackGuard` 类 KDoc **自陈**「本地文件级攻击者不在本威胁模型内（其本可 Hook 进程），远端攻击者无法触碰本地状态文件」（`:59-69`）——即该 MAC 要防的对手被文档亲口排除。
- **失效模式（偏弱）**：MAC 失效 → `load` 返回空 `State`（`:177`，fail-open）⇒ 防回滚退化为「重新建立新基线」。历史事故 `ISSUE-P0-10` 正是「provider 传参错 → 真机 MAC 恒 null → 防回滚静默下线」，现补 `AppLog.w` 留痕（`KeystoreSyncIntegrityMac.kt:39-45`）。
- **非全空面**：仍覆盖「云账号被盗 **+** 本地文件可写」的组合，故不属「零收益」。
- **处置口径（2026-09-25 用户裁决：只删状态文件 MAC，**保留「已见内容摘要链」防回滚引擎**）**：
  - **整文件删除**：`app/src/main/java/com/keepasskey/app/sync/KeystoreSyncIntegrityMac.kt`（75）、`app/src/main/java/com/keepasskey/app/sync/SyncIntegrityModule.kt`（26，Hilt `@Binds`）、`sync/src/main/java/com/keepasskey/sync/engine/SyncIntegrityMac.kt`（30，含 `NoopSyncIntegrityMac`）。
  - **部分摘除**：`SyncRollbackGuard.kt`（272）的 `integrityMac` 构造参数、`load` 的 MAC 分离 / 校验段（`:172-177`）、`persist` 的 MAC 计算与附加段（`:204-206`）；`SyncCycleSetup.kt:95` 与 `SyncCycleRunner.kt:79-81,250` 的注入摘除；`SyncEngine.kt` **不依赖** MAC（仅持 `SyncRollbackGuard?`），无需改动。
  - **兼容处理（保留一行）**：`PREFIX_MAC`（`:249`）与 `load` 中对 `mac=` 行的过滤**保留**，仅用于兼容升级前遗留的状态文件（其 `mac=` 行不得被解析循环误读，且其 `=` 填充不允许影响 key/value 切分）；常量注释改标「遗留兼容」。
  - **状态字段零失效**：`sequence` / `current` / `recent` 全部保留并继续参与裁决；状态文件退化为**明文摘要文件**（不含明文口令、不含凭据）。「篡改不可检测」按既有限界同向口径登记——原 MAC 失效本就是 fail-open，**行为不回退**。
  - **测试资产**：`SyncRollbackGuardTest` 删 `状态MAC失效时按无历史处理不产生误报回退`（`:199-212`）与 `FixedMac`（`:214-225`），其余 8 例改构造；`SyncEngineTest` 删 `testMac()`（`:738-747`）并改 5 处构造；设备侧 `AndroidKeystoreSecretGuardsDeviceTest` 摘除防回滚 MAC 两例（`:50-71`、`:73-85`）与 `KeystoreSyncIntegrityMac.KEY_ALIAS`；`RawFileWritePermissionDeviceTest:147`、`SyncPendingTreeErasureTest:570`、`SyncConflictMergeLocalTreeEquivalenceTest:262,362` 仅改构造。
  - **文档同步**：[`security/同步层记录级完整性威胁建模.md`](security/同步层记录级完整性威胁建模.md) §3.3 与 §5 / §6 的落点表需改口径（状态文件不再由 Keystore 认证）；[`architecture/已知工程限界.md`](architecture/已知工程限界.md) 相关登记同步。
- **AC（按移除口径重写）**：
  1. 上述删除 / 摘除完成，摘要链裁决（`inspect` / `recordAccepted` / `RollbackVerdict` / `ReplayDetected`）行为逐条不变；
  2. `.\gradlew.bat test` 全绿 + 门禁 7/7 PASS；`:sync:connectedDebugAndroidTest` 真机实跑全绿（本批改动了设备侧 `RawFileWritePermissionDeviceTest` 的构造）；
  3. `同步层记录级完整性威胁建模.md` §3.3 与 `已知工程限界.md` 的表述与本批口径一致（**不得**再声称状态文件受 Keystore 认证）；
  4. 「升级后首轮重放窗口」与 fail-open 两条既有限界**不得**被表述为已消除。

### ISSUE-P3-327 快速解锁断言的 signCount 反克隆 + 登记记录 HMAC：安全增量评估与去留裁决

- **优先级**：P3（功能取舍 / 评估；该断言私钥不承担认证闸门）。
- **核实时间点**：2026-09-25（直读 `UnlockPasskeyManager.kt` / `BiometricCredentialStorage.kt` + [`architecture/已知工程限界.md`](architecture/已知工程限界.md) §3.6）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/security/UnlockPasskeyManager.kt`、`BiometricCredentialStorage.kt`、`UnlockPasskeyKeyPolicy.kt`。
- **背景**：WebAuthn 形态断言的增量自陈见 `UnlockPasskeyManager.kt:58-61`（「本断言不构成第二因素；其价值在于持有性证明 + signCount 反克隆 + 记录防篡改抬高攻击门槛」）。
- **逐条增量**：
  - **反克隆多余** —— 封印密钥是 per-operation 认证的不可导出 Keystore 密钥，断言私钥同样不可导出 ⇒ 跨设备复制载荷解不开、密钥签不出；
  - **记录 HMAC 已自陈边界** —— `BiometricCredentialStorage.kt:165`：「同 UID 任意代码执行者可调用 Keystore 重算 MAC，该威胁域本层不设防」；其声称能防的「仅具备文件级写能力」对手又被 `allowBackup="false"` + 九域 exclude 堵死；
  - **不承担认证闸门** —— 限界 §3.6（`ISSUE-P1-242`）已登记：断言私钥 `setUserAuthenticationRequired(false)`，同 UID 代码执行者无授权即可签出合法断言，只是仍解不开封印载荷。

  ⇒ 对任何在威胁模型内的对手，三条增量之和**趋近于 0**。
- **处置口径（2026-09-25 用户裁决：**整层移除**；快速解锁 = 生物识别授权 → 解封封印凭据）**：
  - **整文件删除**：`app/src/main/java/com/keepasskey/app/security/UnlockPasskeyManager.kt`（263，含 `UnlockPasskeyAssertion` / `UnlockPasskeyGate` / `UnlockPasskeyManager`）、`UnlockPasskeyKeyPolicy.kt`（84）。
  - **部分摘除**：`BiometricCredentialStorage.kt` —— `UnlockPasskeyRecord` / `UnlockPasskeyStore`、`saveUnlockPasskey` / `getUnlockPasskey` / `commitSignCount` / `clearUnlockPasskey` / `integrityMac`、`DB_KEY_SUFFIXES` 的 4 个 `_passkey_*` 项（**封印凭据**的 `saveEncryptedCredential` / `getEncryptedCredential` / `hasEncryptedCredential` / `clearCredential` / `clearAll` / `revokeAllBiometricData` 主体**保留**）；`KeystoreManager.kt` —— `getOrCreateUnlockPasskeyPair` / `getOrCreateUnlockPasskeyIntegrityMac` / `UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS` / `unlockPasskeyAliasFor`；`KeystoreKeyMaterial.kt` —— 对应 4 个方法；`SecurityModule.kt:54-58` 的 `bindUnlockPasskeyStore`；`UnlockViewModel.kt:63,105,118`、`BiometricUnlockCoordinator.kt`（`verifyUnlockPasskey` `:133-156`、`passkeyManager.clear` `:208,298`）、`BiometricEnrollmentCoordinator.kt:269` 的 `enroll` 接线。
  - **退役残留清理（必须保留清理钩子）**：设备上既有 `unlockPasskeyAliasFor(dbId)` 与 `com.keepasskey.unlock_passkey_integrity` 两个别名，须按 `BiometricCredentialStorage.cleanupLegacyQuickUnlockData()`（`:63-73`）的既有模式做**一次性清理**——否则 `revokeAllBiometricData` 不再覆盖它们，会留下无人管理的残留密钥（Wave 12 清退旧 PIN 体系即此模式的先例）。
  - **字符串**：删 `unlock_passkey_verify_failed`（`values/strings.xml:42` / `values-en/strings.xml:42`；消费点 `BiometricUnlockCoordinator.kt:149`）。
  - **测试资产**：整文件删 `UnlockPasskeyManagerGateTest`（7）、`UnlockPasskeyKeySpecTest`（6）、`UnlockPasskeyAssertionTest`（9）、`FakeUnlockPasskeyStore`、设备侧 `UnlockPasskeySigningDeviceTest`（3）；`BiometricCredentialStorageTest`（`:129-155`）与 `BiometricEnableCoordinatorTest`（`:158-176`）摘除 passkey 断言、保留封印凭据部分；`QuickUnlockSealDowngradeDeviceTest:73` 改构造。
  - **文档同步**：[`architecture/已知工程限界.md`](architecture/已知工程限界.md) §3.6 **整节失效**（该限界登记的是「断言私钥不绑定认证」的后果，随本层移除而消失——须改口径而非删除留痕）；[`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) `PD-34` 的 `UnlockPasskeyManager` 登记行须回写为「已移除」。
- **AC（按移除口径重写）**：
  1. 上述删除 / 摘除完成，快速解锁链路为「生物识别授权（`CryptoObject`）→ 解封 → 完成解锁」，无任何残留断言调用；
  2. **一次性清理钩子**落地（启动期或撤销路径），并有用例 / 设备侧证据证明旧别名被清理；
  3. `.\gradlew.bat test` 全绿 + 门禁 7/7 PASS；`:app:connectedDebugAndroidTest` 真机实跑全绿（本批删除了 `UnlockPasskeySigningDeviceTest` 与生物识别撤销相关的 passkey 断言）；
  4. 限界 §3.6 与 `PD-34` 的表述与本批口径一致。

### ISSUE-P3-328 字段屏蔽表的 Keystore HMAC（`KeystoreHmacFieldSignatureSource`）：冗余性评估

- **优先级**：P3（纵深防御；本组四条中**唯一在 T2 之外仍有真实收益**的一条，低优先级、无移除强制）。
- **核实时间点**：2026-09-25（直读 `KeystoreHmacFieldSignatureSource.kt` / `AutofillFieldBlocklistStore.kt` / `HmacFieldSignatureSource.kt`）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/autofill/KeystoreHmacFieldSignatureSource.kt`、`AutofillFieldBlocklistStore.kt`、`HmacFieldSignatureSource.kt`。
- **背景**：屏蔽记录只落定长 hex 签名，密钥驻留 Android Keystore 且不可导出，自陈「即便攻击者拿到应用私有目录也无法离线枚举签名」（`KeystoreHmacFieldSignatureSource.kt:16-23`）。
- **真实收益（非 T2 冗余）**：v1 形态是「随机盐 + SHA-256，盐与签名**同文件**落盘」（`AutofillFieldBlocklistStore.kt:144-162` 迁移注释自述）⇒ 拿到 prefs 即可离线枚举；v2 换成 Keystore HMAC 后无密钥不可枚举，把「用户屏蔽了哪些站点」这类**明文元数据**收敛为一个无法解读的 hex 集合。
- **边界**：读取 prefs 仍需同 UID / root，故整体仍在 T2 邻域；其收益是**信息收敛**而非新增防线。
- **处置口径（2026-09-25 用户裁决：保留字段级屏蔽功能，**去掉 Keystore 密钥依赖**，改存明文目标）**：
  - **整文件删除**：`app/src/main/java/com/keepasskey/app/autofill/KeystoreHmacFieldSignatureSource.kt`（123）、`HmacFieldSignatureSource.kt`（25）、`app/src/main/java/com/keepasskey/app/di/AutofillModule.kt`（27，仅绑定该接口）。
  - **部分摘除**：`AutofillFieldSignature.kt` —— 去掉 `source` 参数与 HMAC 产出；`SCHEMA_VERSION` 升 **v3** 以触发一次性清空 v2 签名（明文与 hex 签名不同域，留着只会占计数），`K_SALT` 的删除逻辑保留；`AutofillFieldBlocklistStore.kt` —— `signatureSource` / `signatureUnavailable(Flow)` / `hmacKeyUnavailable` / `signatureOf`，以及 `isBlocked` 中的密钥不可用置位分支（`:72-74`）；`AutofillHealthProbe.kt:30,41`、`AutofillHealthPolicy.kt`（`FIELD_BLOCK_SIGNATURE_UNAVAILABLE` 枚举 + 报告字段 + `issues` 构建）、`AutofillHealthCard.kt:169-170`。
  - **保留**：`isBlocked` / `block` / `unblock` / `blockedSignatures` / `clearAll` 与选择器 / 设置页入口（`AutofillSettingsComponents.kt:298-308`、`AutofillBlocklistDialogs.kt:190-230`、`AutofillPickerScreen.kt`）。
  - **安全表述必须同批改写（如实声明纪律）**：`autofill_field_block_dialog_desc`（`values:963` / `en:911`）与 `autofill_picker_block_confirm_desc`（`values:957` / `en:905`）现称「不可逆签名 / 不记录网址明文」，改明文后**不再成立**；`autofill_health_issue_field_signature_unavailable`（`values:908` / `en:1206`）随健康项删除；中英成对，禁止只改其一。
  - **边界依据**：prefs 仅同 UID / root 可读，而该对手同时可读 `.kdbx` —— 与 `PD-36`（大附件明文落私有目录）的**已接受边界同源**，故属元数据面而非新增秘密面；该句如实声明须写入设置页文案或限界表。
  - **退役残留清理**：`com.keepasskey.autofill_field_signature` Keystore 别名随实现删除后成为无人管理残留 → 一并删除。
  - **测试资产**：删 `TestHmacFieldSignatureSource`；`AutofillFieldBlocklistStoreTest`（13）、`AutofillFieldSignatureTest`（9）、`AutofillHealthPolicyTest`（9）部分摘除 / 重写；`quality/AlgoHotPathGuardsTest`（`:245-261` 句柄缓存与 `containsAlias` 计数断言）摘除；7 处构造点（`SettingsSessionLockEraseTest:149`、`RecycleBinToggleApplyTest:70`、`HealthCheckViewModelTest:125`、`ChildDatabaseSettingsWiringTest:451`、`BreachCheckHealthTest:128`、`Argon2ParametersApplyTest:65`、`AlgorithmSelectionApplyTest:72`）改构造签名；设备侧 `AndroidKeystoreSecretGuardsDeviceTest` 摘 1 例（`:87-111`）与相关常量。
- **AC（按移除口径重写）**：
  1. 上述删除 / 摘除完成，字段级屏蔽的判定与写入路径**行为不变**（逐条对拍，含 `block` / `isBlocked` / `clearAll` 语义）；
  2. v3 迁移为**保守清空**（旧 v2 签名不误命中），`K_SALT` 等旧密钥材料一并清除；
  3. 中英双语「不可逆 / 不可枚举 / 无法回显」类表述全部改写为与明文存储一致的口径；
  4. `.\gradlew.bat test` 全绿 + 门禁 7/7 PASS；`:app:connectedDebugAndroidTest` 真机实跑全绿（本批改动设备侧字段签名用例）。
