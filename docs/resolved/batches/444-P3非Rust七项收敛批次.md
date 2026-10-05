# §444 P3非Rust七项收敛批次（2026-10-05）

**条目**：`ISSUE-P3-481` / `482` / `483` / `484` / `485` / `486` / `487` **七条整条闭环**（P3 14 → **7**，剩余 7 项均为 Rust 原生面，需四层真机另批）
**来源**：[`records/软件工程质量审查记录_2026-10-05.md`](../../records/软件工程质量审查记录_2026-10-05.md)（五维度静态审查 + 深审；low 映射 P3）；本批仅做其中**非 Rust** 的 7 项（用户 2026-10-05 明确范围「非Rust 7项优先」）。

## 444.1 ISSUE-P3-481：`libs.versions.toml` 的 `agp` 字段值与注释叙述不一致

- **前提复核（2026-10-05 直读）**：字段 `agp = "9.4.1"`（17 行），注释 8-16 行整段陈述「降级到 AGP 9.2.1」，与生效值矛盾；`RESOLVED_LOG.md` §330 已记「9.2.1→9.4.1 由 Dependabot PR 合并（commit d53fc93e，附 SupplyChainScanSurfaceTest 对 9.4.1 工具链面的 fail-closed 验证）」⇒ 9.4.1 为实际生效值，注释为陈旧遗留；另 14 行「Gradle 9.7.1 ≥ AGP 9.2 要求的 9.4.1」表述错乱。
- **整改**：`gradle/libs.versions.toml` 8-16 行注释改写为如实演进史「9.4.0（Dependabot 批量落地）→ 9.2.1（IDE 兼容性降级，Quail 1 | 2026.1.1 RC 2 上限 9.2.1，超限报 incompatible + studio 子命令 not implemented）→ 9.4.1（Dependabot 回升级，当前生效值，§330 + d53fc93e + SupplyChainScanSurfaceTest 背书）」；降级/回升级不损失构建能力口径保留（API 37.0 同级）；14 行错乱句改为「Gradle 9.7.1 ≥ AGP 9.2 / 9.4 要求的最低 Gradle 9.4.1」。

## 444.2 ISSUE-P3-482：`composeScreenshot` 未标 dev-only

- **前提复核**：`composeScreenshot = "0.0.1-alpha16"`（76 行）为仅开发期工具（截图导出插件），既有注释「本地开发工具，非生产依赖」未显式标注属性；`material3 = "1.5.0-alpha28"` 属已裁决取舍（35-43 行管控 + 退出条件），本批不动。
- **整改**：74-76 行注释补「ISSUE-P3-482：dev-only 显式标注」+「生产 APK / AAB 不打包、不执行本插件逻辑」。

## 444.3 ISSUE-P3-483：`SyncCache.openCacheStream` 关闭责任在调用方

- **前提复核（2026-10-05 逐层清点）**：`SyncCache.openCacheStream(key)`（`SyncCache.kt:282-285`，`FileInputStream` 裸返回）← `FileBinaryStore.openStream`（`FileBinaryStore.kt:54-55`，唯一生产消费，纯透传）← `BinaryStore.openStream`（接口，`core/.../BinaryStore.kt:35`）← `InnerHeader.BinaryItem.openStream()`（`InnerHeader.kt:70-71`）← 末端 `InnerHeader.writeTo:125`（已 `.use{}`）与 `KdbxBinaryDeduplicator.contentEquals:153-154`（双流均已 `.use{}`）；`KdbxAttachment.kt:55` 属另一条无参链，无生产消费者。故为纪律性风险而非实测泄漏。
- **整改（选定落点：`BinaryStore.openStream` 接口层）**：
  - **理由**：仅在 `SyncCache` 的 KDoc 加一句不足以覆盖经 `FileBinaryStore` 透传的调用方；改「接收消费 lambda」形态须改接口 + 全部透传层与调用点，爆破面大于 P3 纪律性风险收益。故以本接口 KDoc 为单一真相源（所有权移交调用方、必须 `use{}`、透传链说明、落点理由），各透传层指向此处、不重复立规。
  - `core/.../BinaryStore.kt:34` 扩展为关闭责任契约（含透传链与落点理由）；`SyncCache.openCacheStream` 改为单行指针注释（见 444.5 过程缺陷：多行版曾把该文件推入 tier2）；`FileBinaryStore.openStream` 补纯透传指针注释；末端两处（`InnerHeader.writeTo:125`、`KdbxBinaryDeduplicator.contentEquals:153-154`）就地标注「末端消费点，已 `.use{}`」。

## 444.4 ISSUE-P3-484：健康度页展示映射收敛

- **前提复核**：判定侧已集中（`HealthCheckComponents.kt:234 healthAuditTone` + `HealthCheckAuditToneTest`）；展示侧 `HealthCountAuditRow` 内有三处 `when(tone)` 拷贝（图标 / 配色 / 文案各一，272-291 行）。
- **整改**：新增单一真相源 `HealthCountRowPresentation`（icon / iconTint / statusText / isWarning）+ `@Composable healthCountRowPresentation(tone)`（一次 `when` 同源返回三者）；`HealthCountAuditRow` 改为一次调用取件，删除三处 `when`。泄露行（`HealthBreachAuditRow` 五态）为另一维度，不合并，仅在新 helper KDoc 声明「共享图标语义属有意一致而非拷贝」。`HealthCheckComponents.kt` 350→约 390 行，仍在 tier2 下限 400 之下；`long_functions` 仍绿。

## 444.5 ISSUE-P3-485：`applyResolvedEntriesToGroup` 组内 O(k²)

- **前提复核**：`SyncConflictMergeAdjudication.kt:233` `own.forEach { working.indexOfFirst { it.id == entry.id } }` 嵌套 ⇒ 单组 O(k²)。
- **整改**：一次建 `HashMap<KdbxUuid, Int>`（`working.size * 2` 初始容量）替代内层查找，单组降回 O(k)；追加即同步回表，故「重复 id（先替换后又替换 / 先追加后又替换）」终态与逐条覆盖一致；`KdbxUuid` 有正常 `equals/hashCode`（`contentEquals/contentHashCode`），`HashMap` 可用。语义「同父组内按原冲突顺序找到即替换、找不到即追加」不变。

## 444.6 ISSUE-P3-486：防回滚状态 KDoc 仍称含 Keystore HMAC（4 处）

- **前提复核（2026-10-05 `grep "Keystore HMAC"` 全仓分类）**：当前时态陈旧站点 4 处（`SyncCacheMaintenance.kt:181`、`SyncEngine.kt:26`、`SyncCacheEvictor.kt:51`、`DatabaseModule.kt:77`）；沿革语境（`AutofillFieldSignature.kt:29/37`、`AutofillFieldBlocklistStore.kt:22/110/150`、`UnlockThrottle.kt:60`）不属陈旧；`SyncRollbackGuard.kt:31-32/51/65-66/242-243` 已为「明文摘要 / 不再附加 MAC / mac= 仅遗留过滤」。
- **整改**：4 处逐处同步为「仅 SHA-256 摘要的明文状态文件（ISSUE-P3-326 起无 MAC）」，保留「无密文 / 无明文」表述。复查：`sync/...` 零命中；`app/...` 6 命中全部落入「原 / 遗留」沿革语境。

## 444.7 ISSUE-P3-487：旧版无障碍回填通道口令 String 补登记 §2.6

- **前提复核**：`LegacyAutofillCoordinator.kt:27-39`（`PendingFill(password: String)` 经 `Bundle.putCharSequence` 交付）与 `LegacyAutofillAccessibilityService.kt:200-218`（`fillViaSetText` 走 `ACTION_SET_TEXT`，只收 `CharSequence`）；`grep LegacyAutofill / P3-324 / ACTION_SET_TEXT` 于 `已知工程限界.md` §2.6 零命中。
- **整改**：`docs/architecture/已知工程限界.md` §2.6 事实段补一 bullet（同类、非新类别，含「String 是 ACTION_SET_TEXT API 硬约束」与「生命周期压缩到提交→消费→回填一段（覆盖写入、消费即清除、锁定即丢弃，toString 已脱敏）」）；依据行补「ISSUE-P3-487 补登（2026-10-05 直读上述两文件）」。

## 444.8 过程缺陷（如实留痕）

- **首版 `SyncCache.openCacheStream` 七行 KDoc 把该文件推入 tier2**：改后 `count_line_tiers` 报 `tier2=36 > budget=35`，新增项恰为 `SyncCache.kt:404`（改前 398 行，+6 行越过 400 线）。按「棘轮只紧不松」压缩为单行指针注释（关闭纪律单一真相源仍在 `BinaryStore.openStream`，此处不另立规则），回落出 tier2，门禁回 9/9。教训：398 行附近文件加注释亦须先查档位。

## 444.9 验证

- 全量单测：`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL`（6m 17s，114 tasks）；`python tools/doc/count_test_results.py` → `xml=518 tests=3371 failures=0 errors=0 skipped=13`（与 §440-§443 基线持平，本批未增删用例；skipped=13 为既有 Assume 通道，非本批引入）。
- 相关回归（全量已覆盖）：`HealthCheckAuditToneTest`（484 判定口径不变）、合并等价用例（485 落位语义不变）、`:sync:` / `:database:` 单测（483 关闭纪律零行为变更，末端 `.use{}` 原样）。
- 门禁读数（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 442 份；分册登记 444 条；全量索引 444 条；最大 §444）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 576 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

  注：上块为入库终态复跑读数（含最大 §444，一致）。

## 444.10 如实声明

- 本批 7 项均为非 Rust 面（配置 / KDoc / 纯 Kotlin 重构），未触 `crypto/src/main/rust/**` / JNI / 原生分派与探活，按 AGENTS.md 测试资产纪律**无四层真机义务**；剩余 P3 7 项（474-480）为 Rust 原生面，另批处理。
- 未新增 `*Manager/*Util/*Helper/*Common` 类型；`HashMap` 为标准容器，非新类型。
- `SyncCache.kt` 单行化后关闭理由仅在 `BinaryStore.openStream` 详述，`SyncCache` 侧仅指针——此为 AC 允许的二选一落点（接口层），已写明理由。
