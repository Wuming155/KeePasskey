# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。按 **P0 → P1 → P2 → P3** 降序排列，
> 每项含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需另立计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；
> 历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源
> （批次的来源、严重度映射与登记裁决一并归位到**各批次正文**，产品口径落
> [`产品裁决登记.md`](architecture/产品裁决登记.md)，已接受取舍落
> [`已知工程限界.md`](architecture/已知工程限界.md)）。
> **闭环纪律**：条目整改通过后**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），
> 并执行 `git commit & push`。
> **认领前复核前提**：条目正文的 `文件:行号` 一律是**核实时刻的快照**，认领时须先复核前提再开工（规则 6.1②）。

> **在册批次**：**14 条**（2026-10-11 数据流安全审查整批登记：`ISSUE-P2-573` / `ISSUE-P2-574` /
> `ISSUE-P3-575` ~ `ISSUE-P3-586`，六要素报告见
> [`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md)）。新条目附「核实时间点 + 核实方式」。
> 上一批 `ISSUE-P2-572`（WebDAV 409/423 兜底「DELETE 后无预条件 MOVE 重试」并发覆盖窗口）已于 **§487** 整条闭环归档——
> 兜底 DELETE 前重探目标元数据（ETag 漂移 ⇒ ConflictError 不 DELETE；探测失败 fail-closed；相同 / 缺失放行），
> 限界表 §28 补记「409/423 服务器类」未实测样本；正文见
> [`resolved/batches/487-WebDAV覆盖兜底重探守卫批次.md`](resolved/batches/487-WebDAV覆盖兜底重探守卫批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-570`（`runCatching` 的协程取消语义与 `catch` 面同型、未逐一收口）已于 **§486** 整条闭环归档——
> 机检并入 `check_cancellation_semantics.py`（runCatching 面 + `LaunchedEffect` 构造补入），红 23 → 绿 0；
> 正文见 [`resolved/batches/486-协程runCatching取消语义并入机检批次.md`](resolved/batches/486-协程runCatching取消语义并入机检批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-571`（自动填充「关联记忆」无感知 / 不随库迁移）已于 **§485** 整条闭环归档——方案 A
> （Kp2a 式询问写回条目 URL）落地，交互口径裁决登记 `PD-83`；正文见
> [`resolved/batches/485-自动填充记住应用关联询问写回批次.md`](resolved/batches/485-自动填充记住应用关联询问写回批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P3-569`（`catch (Throwable)` 的协程取消语义逐处收口）已于 **§484** 整条闭环归档——正文见
> [`resolved/batches/484-协程catch取消语义逐处收口批次.md`](resolved/batches/484-协程catch取消语义逐处收口批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)；该批的逐处判定表（36 处补重抛 / 21 处判不适用并就地标注）
> 与机检读数留痕见该批次正文。
> 再上一批 2026-10-09 第二轮多维深度审查登记的**十三条**（`ISSUE-P2-556` / `ISSUE-P2-557` /
> `ISSUE-P3-558` ~ `ISSUE-P3-568`）已于 **§483** 整批闭环归档——正文见
> [`resolved/batches/483-存量十三条整改批次.md`](resolved/batches/483-存量十三条整改批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)；该批的来源证据与「被推翻 / 降档的代理结论」留痕见
> [`records/多维深度审查报告_2026-10-09.md`](records/多维深度审查报告_2026-10-09.md)。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。（行号一律视为「核实时刻的快照」，见文首。）

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**（`ISSUE-P0-531` 已于 §473 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-537` / `ISSUE-P1-538` 已于 §477 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（2 项）

### `ISSUE-P2-573`：Gradle 配置期执行 PR 可改脚本读取签名口令，恶意 PR 合并后可在维护者本机静默外传口令与 keystore 文件

**背景与影响**：`app/build.gradle.kts:54-56` 在未设 `KEYSTORE_PASSWORD` 环境变量时，经 `:31-34`
`ProcessBuilder("powershell.exe",…,"-File",credManReadScript,…)` 在 **Gradle 配置阶段**执行仓库内脚本
`tools/signing/read-cred.ps1`（`CredReadW` 取凭证管理器中 `KeePasskey/Keystore/StorePassword` 明文，`:64-81`；
`releaseKeyPassword` 链 `:59-61` 同理触发第二目标）。该脚本是 **PR 可改文件**：攻击者 PR 在保持「退出码 0/1/2 +
stdout 语义」调用方契约（`:16-19`）不变的前提下插入外传调用即可伪装；PR 被人工合并后（`PD-04` 登记 main 无分支保护），
Windows 维护者本机运行**任意** gradlew 任务即触发（无 configuration-cache，配置阶段每次全量执行），
口令与 **keystore 文件本身**（同一次执行可连带读取）静默外传——伪造官方更新通道的前提被打开。
三个 workflow 均 `runs-on: ubuntu-latest`（`build.yml:109/155/252/585` 等），CI 永不执行该脚本，无第二道检测。
前置为多条件叠加（恶意 PR 被合并 + Windows 维护者本机构建 + 未设口令环境变量），故 medium。

**整改依据**：构建期读取秘密的代码路径不得依赖 PR 可改的仓库内可变面；与本仓既有供应链纪律
（Actions 全 SHA 钉死、`cargo --locked`）同构。

**验收标准**：
1. 口令/keystore 读取链不再执行 PR 可改的仓库内脚本，或执行前有可核验的完整性基线
   （哈希白名单校验 / 脚本移出仓库可变面 / 由 CI 侧注入），并以机检或测试锁定；
2. 残余取舍如保留，登记进 `产品裁决登记.md` 或 `已知工程限界.md`（`find_decision.py` 可检索）。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（9 攻击面并行 + 每条发现由独立复核员换人读码逐跳复核，
11 confirmed + 3 adjusted、0 rejected），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 1（verdict=confirmed）。
来源攻击面：CI/CD 与供应链。

### `ISSUE-P2-574`：CI 全部门禁的判定本体（机检脚本 / CVSS 断言 / workflow yml 自身）无完整性护栏，单行削弱判据后持续假绿

**背景与影响**：hygiene-gate 15 个机检脚本、CVSS 硬断言 `.github/check_dependency_cvss.py:37`
（`THRESHOLD = 7.0`，被 `dependency-scan.yml:122-123` 直接调用）、connected 非空转断言、互操作对拍
`verify_interop.py` 与三个 workflow yml 自身**都是 PR 可改文件**，且无任何自我完整性护栏（无脚本 hash 基线）；
`check_archive_monotonicity.py:29-31` docstring 明文「门禁脚本集合有意不纳入」；本地 `gate_readings.py:31,43-51`
直接解析 `build.yml` 执行同批脚本，与 CI 同源同盲。三个 workflow 均仅 `schedule`+`workflow_dispatch`
（`build.yml:36-43` 等），**合并前零 CI 自动检测**：单行削弱判据的「脚本维护」PR（无结构信号）被人工合并后，
自下次定时巡检（build 每日 UTC 21:00、dependency-scan 每周一）起以被削判据**持续假绿**——`fail-closed`
只拦非 0 退出码、不校验判据语义，此后该面回归不再被 CI 拦截。前置为 PR 被合并，损害上限与直接放后门等价，故 medium。

**整改依据**：门禁的检测语义不能由被审对象自身承载；`ISSUE-P3-316` 移除 push/PR 触发后该残余风险未登记
（登记漂移由 `ISSUE-P3-586` 承载，本条 fix 与其汇合同一动作但不合并）。

**验收标准**：
1. 门禁脚本集合有可核验的完整性基线——如 CI 以独立于被审 PR 的 ref/仓库拉取门禁脚本，或 workflow 内先校验
   脚本 SHA-256 清单再执行——使「单行削弱判据」的 PR 在 CI 可检出；
2. 基线覆盖不到的盲区如实登记进 `已知工程限界.md`，不得写为已证。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同上口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 2（verdict=adjusted，
脚本实测 50 个 / CI 直接引用 29 个，corrected 已并入）。
来源攻击面：CI/CD 与供应链。

## P3 低危问题、特性接线与体验优化（12 项）

### `ISSUE-P3-575`：`OpenVaultEntryActivity` 对 URI 无 scheme 校验并静默覆写活动库指针，任意普通 App 零前置可达

**背景与影响**：`OpenVaultEntryActivity` exported 且无权限保护（`AndroidManifest.xml:142-157`）；
`extractKdbxUri`（`OpenVaultEntryActivity.kt:78-84`）对 URI **全程无 scheme 校验**（比
`ExportedComponentHygieneTest.kt:151-164` 白名单理由声称的「仅接受 content/file scheme」更宽），唯一校验为显示名
`endsWith(".kdbx")`（`:59`）。任意普通 App（无需任何权限）显式 Intent 携带 `file://` 任意路径（无需 FLAG_GRANT）
→ `:67` `importExternalDatabase` → `VaultLifecycleCoordinator.kt:301-312` **无确认覆写活动库指针** `saveActiveDbId`
→ 解锁分支 `:66-80` `File(path)` 按**相对路径**解析 → `:102-108` 文件不存在即走「以主密码新建空库」分支。
影响为库列表污染 / 顶掉活动库 / UI 混淆，无凭据外泄路径；**本轮 14 条发现中唯一普通恶意 App 零前置可达**。

**验收标准**：
1. `extractKdbxUri` 增加 scheme 白名单（`content://` 与受 FLAG_GRANT 的 URI；`file://` 收敛到可预期来源或拒绝）；
2. 登记新库 / 覆写活动库指针前引入用户确认，不以外部输入静默改状态；
3. 纯 JVM 单测锁定（攻击者 scheme / 相对路径 / 伪装后缀用例），并同步修正
   `ExportedComponentHygieneTest` 白名单理由表述（与代码不符）。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 3（verdict=confirmed，
复核确认三处攻击面 safeNote 与代码不符，以复核为准）。来源攻击面：其他攻击面兜底。

### `ISSUE-P3-576`：自定义图标上传通道对 SAF 流无大小闸门，与同文件附件通道防护不对等

**背景与影响**：`EntryEditPickers.kt:236` `rememberCustomIconPicker` 直接
`openInputStream(uri)?.use { it.readBytes() }` **无上限物化**（峰值约流大小 2 倍，`Error` 不被 `:245`
`catch(e: Exception)` 捕获 → 进程崩溃、未保存编辑丢失）；同文件 `rememberAttachmentPicker` 已有
「申报长度拦截（`:173-179`）+ 读后兜底并 fill(0)（`:188-192`）」双闸门，`AttachmentSizeLimits.kt:13-14`
KDoc 明言「读取字节之前即拒」的设计意图未落到图标通道。攻击者 App 经 MediaStore 贡献超大「图片」
（普通 App 贡献媒体无需权限）诱导用户在 Photo Picker 选中即可触发，影响为进程 DoS。

**验收标准**：图标通道接入与附件通道同等的双闸门（申报长度拦截 + 读后兜底），超限按既有上限拒绝；单测锁定。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 4（verdict=confirmed）。
来源攻击面：其他攻击面兜底。

### `ISSUE-P3-577`：自动填充会话授权宽限豁免判据不含 TOTP，宽限期内用户名+当前验证码免确认直填

**背景与影响**：会话授权宽限（30 秒 TTL，`AutofillSessionGrantStore.kt:82`）豁免二次确认的判据
`datasetCarriesPassword`（`AutofillUnlockedCandidates.kt:116-119`）只统计**口令字段**：交付不含口令但含
`calculateEntryTotp` 当前码的数据集时（`AutofillDatasetBuilders.kt:199 → 293-301`，排除 HOTP `:295`），
`:302-304` 因 skip 跳过 `attachConfirmationAuth`，框架把用户名+当前 TOTP 直填表单、无确认 UI；
「仅用户名+OTP 框、无口令框」表单可独立成立（`AutofillTargetFieldResolver.kt:20,79,109`）。
前置三重叠加：用户显式开启非默认宽限开关（`ExtendedSettingsStore.kt:259-260` 默认 false）+
30 秒内对该「包名+可归属域」确认过一次 + 库解锁态；调用方须已通过归属校验。

**验收标准**：① 豁免判据把 TOTP 直填值计入敏感值（含 TOTP 的数据集在宽限期内仍挂 `setAuthentication`）；
② `AutofillSessionGrantStoreTest` 补 TOTP 场景用例（既有 `:129-181` 无此场景，缺口未被测试锁定）。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 5（verdict=confirmed，
策略表达式实际在 `AutofillSessionGrantStore.kt:112-117`，原引 118-121 为 KDoc 偏移已修正）。
来源攻击面：自动填充与通行密钥目标验证。

### `ISSUE-P3-578`：HIBP 泄露库查询响应体无界物化，与 sync 侧已整改的「无界读取」同型未对齐

**背景与影响**：`HibpRangeClient.kt:48-49,53` 对服务端响应体 `response.body.string()` **整体物化为 String、
无尺寸封顶**（`BreachCheckModule.kt:36-43` 的客户端无响应体上限、未定制信任锚）——与 sync 侧 `ISSUE-P0-09`
已整改面同型（sync 以 `SyncDownloadLimits` 双重封顶，此出口未对齐）。触发前置：须先取得设备系统信任的 CA
（纯网络 MitM 因信任锚仅系统 CA 不可达），影响为进程 OOM；缓解因素 `callTimeout 30s` 已由复核确认（佐证 low）。

**验收标准**：响应体读取按 sync 侧同型口径做尺寸封顶（申报上限 + 读后兜底）；单测锁定。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 6（verdict=confirmed）。
来源攻击面：网络与 TLS。

### `ISSUE-P3-579`：主密码解锁节流出厂默认关闭且三处 KDoc 口径矛盾，「2026-09-12 用户裁决」未按纪律登记

**背景与影响**：解锁失败节流出厂默认关闭（`SettingsRepository.kt:80-82` 默认 false、
`RealSettingsRepository.kt:123` 缺省 false），`UnlockThrottle.kt:255` 对 `enabled=false` 无视锁定截止直接放行 →
出厂未动设置的用户解锁尝试无节流（`MasterPasswordUnlockSession.kt:114-130` 闸门放行；子库同理
`ChildDatabaseSessionManager.kt:229`）。三处口径矛盾：`UnlockThrottle.kt:109/113-116` ThrottleConfig KDoc
「默认 true——安全默认不得放松」、`UnlockThrottle.kt:186-188` 冷启动回落 `enabled=false`、
`RealSettingsRepository.kt:309` KDoc「默认开启」（复核发现的第三处）；支撑出厂关闭的「2026-09-12 用户裁决」
仅留痕 `docs/resolved/batches/29-用户报告修复批次-复合封印指纹解锁.md:44`，未登记进 `产品裁决登记.md`
（`find_decision.py`「节流/出厂默认/重试节流」均不命中）。已开启节流的用户另有「锁定期杀进程重启、配置流未抵达的
冷启动短窗回落 false」绕过窗口。本条在本威胁模型内非直接绕过（提交口令尝试需 UI 交互），属纵深防御弱化 + 登记/口径缺口。

**验收标准**：① 三处 KDoc 与实现口径收敛一致；② 「出厂默认关闭」裁决按纪律补登进 `产品裁决登记.md`
（`find_decision.py` 可检索）；如改默认值为开启属产品裁决，先裁决再动；③ 冷启动回落窗口如实登记或收口。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 7（verdict=confirmed）。
来源攻击面：密钥存储与生物识别。

### `ISSUE-P3-580`：KDBX 并入凭据链「原件清零」契约落空，第二库主密码与密钥文件字节正常路径不清零

**背景与影响**：`KdbxMergeController.submitCredentials`（`KdbxMergeController.kt:106-107`）KDoc 声称
「借用语义：本方法消费后在 finally 清零」，但正常路径 `:128-129` `copyOf` 出副本交 `runMerge`、
`finally :209-210` 只清**副本**——调用方持有的**原件**（第二库主密码 CharArray 与密钥文件字节，均为 KDF 密钥材料）
在正常路径不清零，驻留 GC 堆至回收；同函数仅两个早退分支（`:112-113` / `:119-120`）才清原件，语义自相矛盾。
上层 `SettingsImportPresenter.kt:55-65`（`:59` 读出的 keyBytes 同样从不 fill(0)）与
`VaultImportController.kt:112-115`（KDoc 同款失实声明）两层透传。威胁模型下堆内存不可达，属纪律合规缺口。

**验收标准**：① 正常路径对调用方原件清零，或契约改为「调用方负责清零」并同步三层 KDoc + 调用方补 `fill(0)`；
② 对照 `architecture/敏感缓冲所有权契约.md` 复核所有权标注（O/B/S/P）与收口点登记表。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 8（verdict=confirmed）。
来源攻击面：导入导出与解析。

### `ISSUE-P3-581`：1PUX 导入解析的 export.data 明文副本（整份口令 JSON）用毕不清零

**背景与影响**：`OnePasswordPuxImporter.parseOrThrow`（`OnePasswordPuxImporter.kt:81-84`）从
`PuxArchiveReader.readEntry`（`PuxArchiveReader.kt:49-78`，经 `:98-106` `readBounded` 返回全新独立数组）取得
`export.data`——**整份含全部明文口令的 JSON 字节**——解码解析后失去引用但全程无 `fill(0)`（成功路径 `:98-106`
直接 return、失败路径 `:93-97` 只清 ImportedEntry）；`VaultImportController.runImport` 的 `finally :220-223`
清的是原始 ZIP 容器与解析条目，覆盖不到这份解析器内部的中间副本（复核确认实为三份：exportData +
`readBounded` 的 ByteArrayOutputStream 内部缓冲均无人管）。需堆取证方可利用，威胁模型内不可达。

**验收标准**：① `exportData` 成功 / 失败路径均清零；② `readBounded` 内部缓冲等调用方不可达的副本如实登记进
`已知工程限界.md` 或改造接口使可擦除；③ 单测/走查锁定。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 9（verdict=confirmed）。
来源攻击面：导入导出与解析。

### `ISSUE-P3-582`：app 模块四点落盘面未做仅属主权限收敛且固定临时名，与 sync 模块 0600/0700 自声明口径漂移

**背景与影响**：两条云端下载落地链 + 两处同族落盘点全程未做仅属主权限收敛、临时名固定可并发互踩：
`CloudVaultImporter.kt:148-163`（固定名 `<name>.kdbx.importing` → 裸 `outputStream()` → `renameTo`；失败路径
`:166` 还会删掉并发者正在写的 tmp）、`SyncRemoteVaultCopy.kt:52-55`（固定名 `.backup`）、
`VaultFileCopy.kt:75-84`、`KeyFileVaultCopyStore.kt:86-95`。与 sync 模块自 `ISSUE-P1-07`/`P3-202` 起
「所有落盘面显式 0600/0700、不依赖 umask」的自声明口径漂移（对照 `SyncCacheFiles.kt:30-35,88-100`：
`restrictToOwnerOnly` + UUID 唯一临时名）。权限面利用需 root/同 UID（不在威胁模型），互踩为本地完整性问题；
`DatabasePickerViewModel.kt:319-326` 云端导入为裸 `viewModelScope.launch` 无提交互斥。

**验收标准**：① 四点接入 `restrictToOwnerOnly` + 唯一临时名，与 `SyncCacheFiles` 口径对齐；
② 设备侧 stat 断言（参照 `SyncCacheAndroidRuntimeTest` 形态）或如实登记；③ 云端导入入口补提交互斥。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 10（verdict=adjusted，
同族未接入点由 1 处扩大为 4 处，corrected 已并入）。来源攻击面：同步层对抗恶意云端（合并「备份与本地存储」面 safeNote 重叠表述）。

### `ISSUE-P3-583`：`android:allowBackup="false"` 第一道闸门无任何静态守卫锁定

**背景与影响**：`AndroidManifest.xml:93` `android:allowBackup="false"` 是备份面第一道闸门，但
`BackupAndSafHardeningTest.kt:24-73` 三个测试只断言 dataExtractionRules 引用字面量、九域排除与 SAF 归属判定，
**对 allowBackup 取值零断言**——未来改动（接入要求开启备份的 SDK、误编辑清单）把取值改回 true 后无任何断言变红。
复核修正：九域排除的任何破坏形态会被既有 contains/内容断言抓红，故泄露需 allowBackup 误改与九域破坏同时静默成立，
路径很窄，定性为回归防护缺口（纯静态、无数据随之出域）。

**验收标准**：`BackupAndSafHardeningTest` 新增对 `allowBackup="false"` 取值的断言。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 11（verdict=adjusted）。
来源攻击面：备份与本地存储。

### `ISSUE-P3-584`：WebDAV/S3 Provider 的 `@VisibleForTesting` client 注入参数位于 public 主构造签名，单行传参即整体旁路网络防线

**背景与影响**：`WebDavSyncProvider.kt:56-60,73-92` 与 `S3SyncProvider.kt:77-78,94-117` 的
`@VisibleForTesting` client 注入参数位于 **public 主构造签名**上（`private val` 修饰的仅是属性而非参数）：
一旦任何生产构造点按名传 `client`，`client==null` 分支整体跳过——TLS-only 连接规格、SSRF 连接期两层守卫
（`SsrfGuardDns`/`SsrfGuardSocketFactory`）与构造期 HTTPS 强制全部失效，Authorization/SigV4 头注入攻击者指定客户端
——绕过网络防线的最短单行改动路径。当前 3 个生产构造点 6 处（`SyncProviderResolver.kt:45,70`；
`CloudVaultImporter.kt:251,265`；`RemoteBrowseController.kt:81,114`）逐一读实均未传参，运行期不可达，
属结构性回归面（`ISSUE-P3-56` 子项 3 的整改未关闭调用点传参路径）。

**验收标准**：① 注入通道移出 public 主构造签名（internal 工厂 / secondary constructor / 单独测试构造），
或以机检锁定生产源集零传参；② lint 对构造参数上 `@VisibleForTesting` 的拦截行为核实并留痕。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 12（verdict=confirmed）。
来源攻击面：网络与 TLS。

### `ISSUE-P3-585`：fast-gate 互操作对拍依赖浮动拉取（pip/apt 均无版本 pin），与本仓供应链纪律不一致

**背景与影响**：`build.yml:189-192` 在 fast-gate 内 `apt-get install keepassxc` 与
`pip install pykeepass cryptography` **均无版本 pin**，`:194-197` 调用的 `verify_interop.py` 对拍判据
（cryptography 独立解析 PEM `:155-173`、keepassxc-cli 交叉读 `:176-200`、pykeepass 字段与保护位判据 `:208-308`）
完全运行在浮动拉取包所在解释器进程内（`tools/passkey-interop/verify_interop.py:55-60` import 即执行；
`:391` 仅打印版本、无兼容性断言）——上游包被投毒或发布含行为变更的新版本时，可伪造「对拍通过」输出与退出码，
或吞掉判据失败。前提（上游投毒）当前无证据且不在威胁模型能力清单内；与本仓既有纪律
（`build.yml:14` Actions 全 SHA 钉死、`cargo --locked`）显式不一致。

**验收标准**：① `pykeepass` / `cryptography` / `keepassxc` 版本 pin（含留痕的升级流程），与 Actions SHA 钉死纪律同构；
② `verify_interop.py` 的版本兼容性断言口径一并补齐或登记。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 13（verdict=confirmed）。
来源攻击面：CI/CD 与供应链。

### `ISSUE-P3-586`：PD-04 登记口径与代码漂移——「工作流照常在 push/PR 上运行」自 ISSUE-P3-316 起已失真

**背景与影响**：`docs/architecture/产品裁决登记.md:141` 逐字声称「工作流照常在 `push{main}` 与 `pull_request`
上运行」、`:158`「已在位且同时在 push 与 PR 上运行」——但三个 workflow 已于 `ISSUE-P3-316`
（2026-09-25，晚于 PD-04 的 2026-09-17 登记 8 天）全部改为 `schedule`+`workflow_dispatch`
（`build.yml:36-43`、`codeql.yml:31-36`、`dependency-scan.yml:27-31`），登记未同步：PD-04 理由 3 依赖的
「闸门即时运行」面已不存在，合并进 main 的内容**合并前无任何 GitHub Actions 自动检测**、合并后最长 24h
（供应链扫描 / CodeQL 每周）才被巡检；两条各自登记过的取舍（PD-04 + ISSUE-P3-316）组合出的新残余风险
未被任何登记承载（`find_decision.py`「P3-316/巡检」两表 no_match）。

**验收标准**：① PD-04 条目补记 `ISSUE-P3-316` 后的触发面现状与「合并前零自动检测 / 合并后延迟巡检」残余风险
（或迁移登记进 `已知工程限界.md`）；② `find_decision.py` 检索口径与文本一致；
③ 与 `ISSUE-P2-574` 的修复动作汇合处互相引用。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 14（verdict=confirmed）。
来源攻击面：CI/CD 与供应链。

