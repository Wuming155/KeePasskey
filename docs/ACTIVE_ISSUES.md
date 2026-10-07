# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。
> **新增批次（2026-10-06）**：`ISSUE-P1-495` / `ISSUE-P2-496~502` / `ISSUE-P3-503~516` 出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)（假开关 / 接线断裂 / 读取源错误与静默降级三类，九路并行排查 + 逐条独立复核）。**严重度映射**：报告标 high → **P1**，medium → **P2**，low → **P3**。**三项登记裁决**（登记时新定，须随条目一并遵守）：
> ① **同族去重**：报告 F12 为「原生探活失败静默回落零观测」缺陷族的唯一母条目，其子集 F03（cipher 三引擎）/ F07（强度面）/ F11（Passkey 签名面）并入 `ISSUE-P2-499`，不独立立项（该合并条目已于 §454 闭环归档）；
> ② **severity 取族内最大值**：`ISSUE-P2-499` 判 medium（母条目自身 low，子集 F03 为 medium——ChaCha20 BC 回落 ≈44× 慢、Twofish 整库数据流走纯 Java），条目内分层保留两侧论据；
> ③ **F25 不登记**：复核判定其缺陷前提被证伪（与格式裁决者 KeePass 2.61.1 官方 C# release 行为逐行同型，批次 273 勘误②复审显式维持、写侧 D17 守卫在位），`holds=false` 依据是前提证伪而非登记表命中。
> **触发状态口径**（报告 v3 判据 E1/E2，与本文件「只放现存问题」不冲突——masked 指**错误效果已产生或曾实际发作**，dormant 指**零错误效果、缺陷以声明侧残留 / 守卫缺口形态存在**）：masked 7 条（`P1-495` 除外，另 `P2-496~499`）应优先认领；dormant 条目为前瞻性风险，可按批连续解决。全部条目为**只读静态排查**产出，认领时须按规则 6.1② 先复核前提。
> **真机走查（2026-10-06）**：`ISSUE-P1-495` 收工时的真机（MIUI / `M332BF` / Android 17）五层走查——`:crypto:` 37/37、`:database:` 18/18、`:sync:` 25/25 全绿；`:core:` 4 例中 1 例**确定性假红**（曾登记为 `ISSUE-P2-517`，**已于 §450 整条闭环**，本文件不再保留该条目）；`:app:` 51 例中 3 例失败属已登记的 `ISSUE-P2-492` 厂商冻结面（读数与未定性项见 [`architecture/实现约定与验证现状.md`](architecture/实现约定与验证现状.md)）。
> **新增批次（2026-10-07）**：`ISSUE-P2-518~521` / `ISSUE-P3-517~525` 出自 [`records/六专题综合研究与实测报告_2026-10-07.md`](records/六专题综合研究与实测报告_2026-10-07.md)（动态工作流六专题调研 + 五组实测的报告，§9.3 为 8 条 backlog 草稿；报告同日落册入库并登记文档地图）。**严重度映射**：草稿标 P2 → P2、标 P3 → P3；另 4 条（`P3-520` / `P3-521` / `P3-522` / `P3-525`）为报告正文 §1 / §3 / §6 已核实发现的补登。各条登记前均已逐条复核前提（核实时间点与方式见条目正文）；`P3-523` 涉及设备侧复跑，执行前按 §263 确认设备上无待保留数据。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

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

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-495` 已于 §449 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（4 项）

> 来源见文首「新增批次（2026-10-07）」。

### ISSUE-P2-518：LIVE 层无「防回滚」端到端用例——四段场景中唯一在真实服务层零覆盖的段落

- **核实时间点**：2026-10-07；**核实方式**：实读 `tools/local-sync/README.md` 场景映射总表与已知限界段、`sync/src/test/java/com/keepasskey/sync/LiveSyncServersTest.kt` 全部 @Test，并以本地 wsgidav:9443 + MinIO:9000 实跑 `:sync:testDebugUnitTest --tests LiveSyncServersTest -DliveSyncTest=true` 复核——tests=12 failures=0，12 个用例名与 XML 逐一对应，其中无任何 RollbackRejected / 防回滚状态文件用例；回滚段仅 mock 层覆盖（`SyncRollbackGuardTest` + `SyncEngineTest`）。
- **背景**：防回滚裁决（`SyncRollbackGuard.kt:129-142` 摘要环 + `SyncEngine.kt` 五处 isReplay 拒绝点）的引擎层已被 mock 单测锁定，但「服务端 .kdbx 被整体回滚为旧版本 → 同步 → 拒绝应用 + 用户提示」这一端到端链在 LIVE 层零用例——该面恰是防回滚存在意义的原型场景（旧库整体复活），mock 层无法证明真实服务端响应形态（ETag / 时间戳变化）下裁决链仍然成立。
- **涉及文件**：`sync/src/test/java/com/keepasskey/sync/LiveSyncServersTest.kt`、`tools/local-sync/`（如需服务端脚本配合）。
- **验收标准**：① LIVE WebDAV 与 LIVE S3 各补一例：建库→同步→在服务端把 .kdbx 回滚为旧版本→触发同步，断言本地拒绝应用（`RollbackRejected` 或引擎等价拒绝路径）、用户提示（前台 snackbar / 后台通知）出现、rollback 状态文件未被清；② 计数以 `count_test_results.py` 聚合复核，防 tests=0 空转；③ `gate_readings.py` 全 PASS。

### ISSUE-P2-519：verify_interop.py 至今无自动调用点——互操作对拍不在任何 CI 通路内

- **核实时间点**：2026-10-07；**核实方式**：grep `.github/` 中 `verify_interop` / `passkey-interop` 零命中（与批次 449.5 的如实声明一致）；同日手动实跑 `python tools/passkey-interop/verify_interop.py` 退出码 0、161 条判据全绿（keepassxc-cli 2.7.12 + pykeepass 4.2.0）。
- **背景**：`ISSUE-P1-495` 修复后缺 keepassxc-cli 退 2、判据失败退 1（fail-closed），但对拍仍只在有人手动执行时运行——`PasskeyData` schema / `PasskeyPkcs8Codec` / KPEX 字段的回归（`ISSUE-P2-211` 正是被它揭出的）在 CI 上无阻断点，探针产物或判据漂移只能靠人肉记忆触发。「闸门存在 ≠ 闸门被执行」正是 `ISSUE-P3-305` 的根因同型。
- **涉及文件**：`.github/workflows/build.yml`、`tools/passkey-interop/verify_interop.py`（如需入口调整）。
- **验收标准**：① CI 增加步骤：先 `:database:testDebugUnitTest --tests "*PasskeyInteropProbeTest*"` 产出产物，再跑 `python tools/passkey-interop/verify_interop.py`；退出码非 0 即红，严禁 `|| true` 吞码；② runner 缺 keepassxc-cli 时退出码 2 的口径须显式（安装 CLI；缺 CLI 退 2 即红，不得静默跳过）；③ 若纳入 `gate_readings.py` 解析范围须同步其段落。

### ISSUE-P2-520：换密成功后 .bak 删除失败静默残留旧口令可解密文，且换密回执缺「云端旧版本仍可解」告知

- **核实时间点**：2026-10-07；**核实方式**：实读 `AtomicFileWriter.kt:266-269`（deleteBackup「删除失败仅记录告警并返回 false，绝不在此抛出以阻断主流程」）、`SessionPersistence.kt:185-188`（changeCredentials 成功路径删 .bak）、`SessionFileWriter.kt:40-53` 同口径；grep 全 app 模块未发现呈现该残留的 UI 代码；换密回执仅「主密码已更新」（`SettingsMasterKeyChangeController.kt:122-135`，strings.xml:755）；换密链路无同步触发点（检索证据见综合报告 §4.1）。
- **背景**：换密写盘完成与 deleteBackup 之间（或 unlink 失败）时，旧口令可解的 .bak 静默留在 files/ 下——与「凭据轮换后旧密文快照必须失效」的登记意图（`ISSUE-P2-11`，`AtomicFileWriter.kt:244-246`）相悖的残余窗口；云端旧版本保持旧口令加密直到下轮上传，同样无提示。本条为**残余暴露面缺告知**（deleteBackup 失败仅告警系既有实现取舍），非已证实的行为缺陷。
- **涉及文件**：`AtomicFileWriter.kt`、`SessionPersistence.kt`、`SettingsMasterKeyChangeController.kt`、`strings.xml`（中英）。
- **验收标准**：① deleteBackup 失败时给用户可见信号（换密回执附警告或等价可见提示），补宿主用例锁定「删除失败 → 有可见信号」路径；② 换密回执补充「云端旧版本稍后自动更新（上传前仍可用旧口令解开）」口径；③ 若某项裁决为接受残余，回写限界表而非留空。

### ISSUE-P2-521：库文件损坏时无 .bak 恢复路径——解锁失败只有通用错误，全 app 无任何 .bak 消费入口

- **核实时间点**：2026-10-07；**核实方式**：实读 `MasterPasswordUnlockSession.kt:217` → `KdbxErrorTexts.kt:44`（损坏路径归 UNLOCK_FAILED 通用文案，无恢复指引）、`SessionOpener.kt:191-195`（打开失败仅返回 Failure）；grep 全 app 模块证实零 `.bak` 消费代码；`AtomicFileWriter.kt:67-114` 五步原子写保证 .bak 恒为上一稳定版本；SAF 自选位置非原子写（限界 §24）使「原文件真损坏」面真实可达。
- **背景**：filesDir 通道断电不损坏原文件，但历史损坏 / 外部写坏 / SAF 路径截断仍可能产生打不开的库——此时用户唯一出路是通用「解锁密码库失败」，而内容为上次成功保存版本的 `.bak` 就在同一目录，产品内却没有任何恢复引导。
- **涉及文件**：app 解锁链（`UnlockViewModel` / `SessionOpener` / `MasterPasswordUnlockSession` / `KdbxErrorTexts`）、`database`（如需暴露备份定位）、`strings.xml`（中英）。
- **验收标准**：① 解锁失败且错误分类属「文件损坏 / 格式错误」（**非**凭据错误）且 `.bak` 存在时，向用户提供「从 .bak 恢复」入口，并明确告知其内容为上次成功保存的版本；② 恢复动作须用户显式确认，以原子方式（临时文件 + rename）用 `.bak` 覆盖主文件，`.bak` 自身保留；③ 凭据错误（主密码 / 密钥文件不匹配）不得出现该入口，防误覆盖；④ 宿主用例锁定错误分类判据与恢复复制语义；⑤ 若裁决不做，回写限界表而非留空。

## P3 低危问题、特性接线与体验优化（9 项）

### ISSUE-P3-517：@Preview 反向态覆盖缺口——缺反向态预览 5 处、完全无预览 8 处（两态齐 14/27=51.9%）

- **核实时间点**：2026-10-07；**核实方式**：`python tools/doc/check_preview_state_coverage.py` 现跑读数（EXIT=0）+ `export-preview-main.bat` 导出 40 张逐张目检。
- **背景**：漏态 ⇒ 该态此前只有真机能看见。缺反向态 5 条：`EntryEditExtraSection.expiresEnabled`、`ImportExportSettingsScreen.isExportInProgress` / `keyFileImportBusy`、`VaultListContent.searchAdvancedEnabled`、`KeePasskeyTheme.dynamicColorEnabled`；完全无预览 8 条：`SecureDialogWindowEffect`、`SettingsScreen`、`AutofillSwitchRow`、`SettingsToggleRow`、`DebugSwitchRow`、`SecuritySwitchRow`、`ThemeSelectionCard`（×2）。
- **涉及文件**：上列组件所在文件（`app/src/main/java/com/keepasskey/app/ui/**`）。
- **验收标准**：① 逐条补反向态 / 补预览（或逐条给出豁免理由并登记）；② 复跑 `check_preview_state_coverage.py` 读数回写批次文档，两态齐比率只升不降；③ 重生包装后 `:app:compileDebugScreenshotTestKotlin --rerun` 通过。

### ISSUE-P3-518：tools/local-sync/README.md 场景 10「契约无 LIST 操作」与当前契约存在措辞漂移

- **核实时间点**：2026-10-07；**核实方式**：实读 `SyncProvider.kt:41-48`（listRemoteDirectory 契约，默认 501）、`WebDavDirectoryList.kt:45`（Depth:1 PROPFIND）、`S3DirectoryList.kt:45-52`（ListObjectsV2）、生产调用方 `RemoteBrowseController.kt:169/:173`。
- **背景**：引擎主循环确无目录枚举（KDBX 单文件模型），但 Provider 契约确有分页 LIST 且被远端浏览 UI 消费；README 场景 10 的「契约无 LIST 操作」未限定到引擎面，会误导读者以为契约层无此操作。
- **涉及文件**：`tools/local-sync/README.md`。
- **验收标准**：表述限定为「同步引擎主循环无 LIST；契约层有 `listRemoteDirectory`（WebDAV PROPFIND / S3 ListObjectsV2），生产调用方为远端浏览 UI」；改后 `check_md_links.py` 通过。

### ISSUE-P3-519：预览走查两处观感/冗余项（非功能缺陷，低成本清理）

- **核实时间点**：2026-10-07；**核实方式**：`export-preview-main.bat` 导出 40 张逐张 Read 目检 + 主会话复核截图（`preview-exports/main/light/ThemeSettingsScreenPreviewScreenshotExport_外观设置页 - 浅色_c88978fe_0.png` 孤字行属实；`WebDavSyncScreen.kt:90-103` 别名预览与 `CloudSyncScreen` 同传默认 `SettingsUiState()` 逐像素相同属实）。
- **背景**：① `strings.xml:1008` `theme_mode_light_sub`「明亮清爽，适合日间」在模式卡片宽度下换行后第二行只剩一个「间」字（亮暗同现）；② `WebDavSyncScreenPreview` 经别名函数渲染与「云端同步设置页」预览完全重复，且别名函数本身有生产调用（`KeePasskeySettingsNavGraphRoutes.kt:133`）不可删。
- **涉及文件**：`strings.xml:1008`（中英对称性核对）；`WebDavSyncScreen.kt`（删冗余预览函数）；`app/build.gradle.kts` `mainScreenPreviewPrefixes`（同步摘除 `WebDavSyncScreenPreviewScreenshotExport`，防逐屏命中断言红）。
- **验收标准**：① 文案调整后复跑导出目检无孤字行；② 冗余预览删除后包装重生 + `:app:compileDebugScreenshotTestKotlin --rerun` 通过、导出任务逐屏断言仍绿；③ 改过 UI 后跑 `check_box_slot_children.py`。

### ISSUE-P3-520：已知工程限界表 §5 重放裁决点行号快照漂移（2026-09-21 快照 → 现查已漂移）

- **核实时间点**：2026-10-07；**核实方式**：`grep -n "isReplay(" sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt` 五处命中 `:180` / `:249` / `:300` / `:325` / `:397`；限界表 §5 记 `:166` / `:228` / `:275` / `:300` / `:368`（2026-09-21 快照），条数一致、行号漂移。
- **背景**：行号快照按条目维护规则 3 本属「核实时刻的快照」，但该表行号已被后续批次引用为定位依据，漂移不修正会误导认领者。
- **涉及文件**：`docs/architecture/已知工程限界.md` §5、`docs/records/限界表证据与实测数据汇录.md` 附二（按格式补更正留痕）。
- **验收标准**：① §5 行号更新为现查值并注日期；② 附二按既有格式补「§5 · 2026-10-07 更正」留痕；③ `check_md_links.py` 通过。

### ISSUE-P3-521：竞品对比报告 §5-G14 处置列未回写「设置页搜索」子项被 §427/PD-73 作废的后续

- **核实时间点**：2026-10-07；**核实方式**：实读报告 §5 G14 行（处置列「已登记 `ISSUE-P3-444`」）与 §294 行更新块（止于 2026-10-03）；对照 `docs/RESOLVED_LOG.md` §427（删 `SettingsSearch.kt` 等，tests -5）与 `产品裁决登记.md` PD-73（仅针对设置页）。
- **背景**：P3-444 的「设置页搜索」子项在 §425 落地后又被 §427 整条作废移除（用户走查裁决落 PD-73），报告的登记结果块与 G14 处置列均未回写此后续，读者会据旧状态误判该子项仍存在。
- **涉及文件**：`docs/references/UI与操作体验的竞品对比报告.md`（G14 处置列 + 更新块）。
- **验收标准**：① G14 处置列注明「设置页搜索」子项经 §427/PD-73 作废移除、其余子项维持；② 更新块补一条带日期的闭环回执；③ `check_md_links.py` 通过。

### ISSUE-P3-522：PasskeyPrf KDoc「与 KeePassDX 逐字节一致」为无出处声明——须升级为带引用的核实结论

- **核实时间点**：2026-10-07；**核实方式**：目标性实读 `参考项目/KeePassDX/app/src/main/java/com/kunzisoft/keepass/credentialprovider/passkey/util/PasskeyHelper.kt:277-296`——`SALT_PRF = "WebAuthn PRF"`、`derivePrfSalt = SHA-256(UTF8(SALT_PRF) || [0x00] || rawSalt)`、`computePrfValue = HMAC-SHA-256(secret, derivePrfSalt(salt))`，与本仓 `PasskeyPrf.clientSideProcess` / `computeValue`（`digest.update(0x00)` 单字节 + `hmacSha256(secret, salt)`）逐字节同构，**声明属实**。
- **背景**：`PasskeyPrf.kt` KDoc 的「本对象与之逐字节一致」仅为口径声明、无参考项目出处引用（综合报告 §3.1 指出其不可追溯）；已核实为真的前提下，把声明升级为带 `path:line` 与核实日期的引用即可闭环。
- **涉及文件**：`crypto/src/main/java/com/keepasskey/crypto/passkey/PasskeyPrf.kt`（仅 KDoc）。
- **验收标准**：KDoc 写明参考实现出处（`PasskeyHelper.kt:277-296`）与核实日期；不改动任何可执行代码。

### ISSUE-P3-523：MIUI 第二数据点（tests=51 / failures=3）的 3 例失败逐例定性未做

- **核实时间点**：2026-10-07；**核实方式**：实读 `docs/architecture/实现约定与验证现状.md` §4.1（行 169-174）原文——「仅产出 tests=51（对照非 MIUI 基线 67）且 failures=3」并自陈「逐例定性未做」；三例为：① `DialogWindowHardeningDeviceTest` 479s 空 `<failure>`；② `AutofillAuthChainDeviceTest` 跨包显式启动失败；③ `CredentialSaveChainDeviceTest` 经 Assume 记录。
- **背景**：`:app:` 层在 MIUI 的整层零结果已由 `ISSUE-P2-492` 收口定性（freezeUid 厂商冻结面），但第二数据点的 3 例失败至今无逐例归因——若其中混有非冻结面的真实缺陷，现口径无法区分。在位真机 Redmi 4X（santoni / **LineageOS** / API 37，非 MIUI）可承接复跑（§9.2b）。
- **涉及文件**：`docs/architecture/实现约定与验证现状.md` §4.1（定性结论回写）；（定性后）可能新登记缺陷条目。
- **验收标准**：① 在该非 MIUI 真机复跑三例（先按 §263 确认设备上无待保留的 keepasskey 数据），区分「MIUI 冻结/厂商面」与「可复现真实缺陷」；② 定性结论回写 §4.1（或新登记条目），禁止继续以「未定性」状态长期挂账。

### ISSUE-P3-524：密钥文件交付文案与批次 416「私有副本兜底」现实的表述一致性待复核

- **核实时间点**：2026-10-07；**核实方式**：Read `app/src/main/res/values/strings.xml:1483-1500`——`db_picker_keyfile_backup_warning`（:1491，建库一次性交付对话框语境）原文「该密钥文件是解锁本库的第二因子，**本应用不会代为保管**。请立即导出到安全位置；一旦丢失，即使记得主密码也无法再打开此库。」；对照批次 416 文档「副本在建库成功那一刻就已落私有目录，密钥文件再也不会『永久丢失』」与 `KeyFileVaultCopyStore.kt:19-38,66`（filesDir/keyfiles，DEK+Keystore 信封加密）。
- **背景**：自批次 416 起应用确有私有加密副本兜底，「永久丢失」仅在「SAF 导出件与私有副本同时丢失」（典型为卸载/清除数据且未导出）时成立；交付文案「本应用不会代为保管」「一旦丢失……无法再打开」在「未导出但私有副本仍在」的常态下已过强，两处对同一风险的表述表面冲突。
- **涉及文件**：`app/src/main/res/values/strings.xml:1489/:1491/:1497`（及 values-en 对应条目）。
- **验收标准**：① 逐句核对三条文案与 416 兜底现实的一致性，给出「维持 / 加注限定 / 修订」结论并留痕批次文档；② 若修订文案，同步核对该对话框既有预览 / 测试断言是否需更新；③ 改动与文档同一次提交，`check_md_links.py` 与门禁读数通过。

### ISSUE-P3-525：@Preview 横屏维度零覆盖——50 个预览文件无任何 widthDp/heightDp/orientation 指定

- **核实时间点**：2026-10-07；**核实方式**：grep `widthDp|heightDp` 与 `spec:orientation=landscape` 在 50 个含 `@Preview` 的 `*Preview*.kt` 零命中；`KeePasskeyApp.kt:316` `isWideScreen = screenWidthDp >= 600`（Rail 分支）在 Layoutlib 无任何覆盖实例；大字号仅 2 处 fontScale=2.0f（`SettingsScreenPreviews.kt:74` / AuthenticatorScreen.kt）。
- **背景**：Layoutlib 预览全部为纵向默认尺寸 ⇒ 横屏布局（含宽屏 Rail 切换、TotpScanDialog 去方向锁后的横排布）此前只有真机能看见；「预览已渲染 ≠ 真机已核对」的限界之外，连预览侧的横屏维度也是零。
- **涉及文件**：主屏预览文件（优先：应用脚手架 / 密码库列表 / 凭据编辑 / 解锁页）。
- **验收标准**：① 至少 3 个主屏预览补横屏变体（`widthDp`/`heightDp` 或 landscape spec），其中至少 1 个宽度 ≥600dp 覆盖 Rail 分支；② 包装重生 + `:app:compileDebugScreenshotTestKotlin --rerun` 通过；③ 导出目检留痕批次文档（横屏初检结论）。
