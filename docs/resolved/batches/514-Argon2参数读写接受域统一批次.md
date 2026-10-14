# §514 Argon2 参数读写接受域统一批次

> **状态：已实施并通过本批次验收。** 本批次闭环 `ISSUE-P1-595`。

<a id="s514"></a>

## 1. 原始条目

> 以下条目为闭环前从 `ACTIVE_ISSUES.md` 剪切的原始登记快照；其「仅审计」属历史状态，最终实施与验收结论以第 2 节为准。

### ISSUE-P1-595：Argon2 设置可写出本应用读侧拒绝的数据库

- **整改方案**：[ISSUE-P1-595 独立方案](../../architecture/问题整改方案/P1/ISSUE-P1-595/Argon2参数读写接受域统一方案.md)；方案已编制，尚未实施。

- **来源 / 严重度**：全面软件审计 `AUD-07`，High，可信度高（动态范围以报告为准）。
- **核实时间点 + 核实方式**：2026-10-13；设置选项、写侧与读侧边界直读及算术核对；现有编译类接受 256 MiB × 50 轮的引擎分派判据，读侧校验却抛损坏文件异常。未执行高负载派生。仅审计，未整改。
- **背景 / 位置**：`DatabaseSettingsArgon2DialogSections.kt:42-48` 可选 256 MiB / 50 轮；`SettingsPreferencesController.kt:277-297` 与 `KdbxFile.save` 未执行读侧联合预算校验，`KdbxKdfParameterCodec.kt:255-265` 拒绝超过 8 GiB 字节·轮或设备堆半量。
- **触发 / 后果**：原生内核成功派生并保存 256 MiB × 33~50 轮后，重新读取在派生前即拒绝；256 MiB × 50 = 12.5 GiB 字节·轮。实际写出是否成功依设备内存，未做高负载设备复现。
- **建议 / 验收**：UI、写侧、读侧共用可接受参数判据，并在改变会话前拒绝不可重开的设置；逐项验证所有 UI 组合写后重开。
- **详细证据**：[全面软件审计](../../security/全面软件审计_2026-10-13.md)。

## 2. 实施与验收

- **前提复核（认领时，2026-10-14；直读 + 算术核对）**：前提成立——设置对话框 `MEMORY_MB_OPTIONS = {16,32,64,128,256}`、
  轮数上限 50 ⇒ 256 MiB × 50（12.5 GiB 字节·轮）可达；读侧 `KdbxKdfParameterCodec` 联合预算 `2^33`（≈8.59×10^9）
  会拒绝 256 MiB × 33~50（33 > `2^33 / 2^28 = 32`），而写侧 `KdbxFile.save` 与设置控制器此前均不经过该判据。
  行号漂移（原条目 `:255-265` 等）已随 §513 后重排，对象与事实全部在案。

- **改动一（新增 `KdbxKdfAcceptance.kt`，158 行，判据单一真相源）**：Argon2 / AES-KDF 全部边界收敛为公开
  `object`——`argon2Rejection(memoryInBytes, iterations, parallelism, version, deviceBudgetBytes = deviceMemoryBudgetBytes())`
  （返回 null=可接受，否则中文拒绝原因）、`argon2SaltRejection`、`aesKdfRejection`、`aesKdfSeedRejection`、
  `memoryBytesFromMbOrNull`（溢出 / 负数返回 null，不回绕）、`deviceMemoryBudgetBytes()`（JVM 堆一半，**可注入**
  供确定性测试）、`requireAcceptableOrThrow(params)`（写前 fail-closed）。常量与原读侧逐项同值：
  `ARGON2_MAX_TOTAL_WORK = 2^33`（联合预算，除法判定避免乘法溢出）、内存 4 GiB、迭代 2^24、并行度 64、
  每通道下界 8192、盐 8..0x3FFFFFFF、`AES_KDF_MAX_ROUNDS = 2^28`、种子 32 字节。

- **改动二（`KdbxKdfParameterCodec.kt` 305 → 224 行，读侧仅委托）**：删除各处 private 常量与判定体，
  `validateArgon2Bounds / validateArgon2SaltBounds / validateAesKdfBounds / validateAesKdfSeedBounds` 改为委托
  `KdbxKdfAcceptance`（异常类型仍为 `KdbxCorruptFileException`，消息文本保留）；`ARGON2_MAX_TOTAL_WORK` 保留为
  指向判据的 `internal const` 别名（设备侧 `KdfWallClockDeviceTest` 既有引用零改动）；类 KDoc 补 ISSUE-P1-595
  说明并**更正「宽于一切合法用户配置」的过时陈述**（联合预算并非宽于一切合法配置；设备预算属设备能力限制，
  非 KDBX 格式上限）。

- **改动三（`KdbxFile.kt` 写侧写前校验，496 → 499 行）**：`save()` 在**写任何目标字节 / 进行 KDF 之前**
  （`updatedHeader` 组装后、`deriveKeys` 前）调用 `KdbxKdfAcceptance.requireAcceptableOrThrow(updatedHeader.kdfParameters)`
  ——绕过 UI 的调用者也被 fail-closed 拦截（`IllegalArgumentException`）。

- **改动四（`SettingsPreferencesController.kt` 提交前校验）**：`setArgon2Parameters` 在改变会话前调用
  `memoryBytesFromMbOrNull` + `argon2Rejection`（按会话头 `kdf.version`），非法组合直接返回——不改会话头、
  不落盘、不派生；原 `memoryMb * 1024L * 1024L` 直接乘法改走溢出安全换算。

- **改动五（`DatabaseSettingsArgon2Dialog.kt` 动态禁用 + 原因）**：非法组合**禁用「应用」并显示与判据同源的
  拒绝原因**（不另抄边界文案）。判据辅助 `argon2AcceptanceRejection`（MiB 换算溢出按非法处置）与原因组件
  `Argon2AcceptanceReasonText` 为**等价拆分外提**：初版把 `Argon2ParametersDialog` 推到 105 行触发行人仓库
  `long_functions` 门禁（≥100 行 fail-closed），拆分后压回阈值内（门禁 `functions_ge_100=0`）。

- **测试（新增 1 个用例类 / 2 个用例类增补，共 10 例；`--max-workers=1` 定向 + 回读 XML）**：
  - `com.keepasskey.database.file.KdbxKdfAcceptanceTest`（新增，7 例；XML 实测 `tests=7 skipped=0 failures=0 errors=0`）：
    256 MiB × 32 允许 / ×33、×50 拒绝；读侧 codec 同拒绝；注入固定设备预算的上下邻点；每通道下界上下邻点；
    超大 Long / 非法版本 / 非法盐长无溢出旁路；MiB 溢出返回 null；**UI 全选项组合枚举**（16~256 MiB ×
    1..50 轮 × p∈{1,2,4,8} 注入充足预算与联合预算边界逐点一致）。
  - `com.keepasskey.database.file.KdbxKdfParameterCodecWriteContractTest`（+1 例，`tests=12 skipped=0 failures=0 errors=0`）：
    联合预算超限（256 MiB × 50）时 `KdbxFile.save` 抛 `IllegalArgumentException` 且**未写出任何字节**（`out.size()==0`）。
  - `com.keepasskey.database.file.Argon2KdfBoundsMirrorTest`（既有 7 例回归，`tests=7 skipped=0 failures=0 errors=0`）：
    委托后跨模块上界镜像与堆门槛语义零漂移。
  - `com.keepasskey.app.ui.screens.settings.Argon2ParametersApplyTest`（+2 例，`tests=4 skipped=0 failures=0 errors=0`）：
    ① 超出联合预算的组合被拒且会话头不变、状态非 DIRTY；② 对话框判据锁定 256 MiB × 33~50 越界档、溢出 /
    负数 / 低于每通道下界按非法处置、官方默认与低配组合不误拒。

- **全量单测**：`./gradlew.bat test --max-workers=1` EXIT 0；`count_test_results.py` 读数
  **`xml=576 tests=3762 failures=0 errors=0 skipped=15`**（§513 基线 575/3752 ⇒ +1 个 XML、+10 例）。

- **验收矩阵对照（方案 §4）**：① 256 MiB×32/33/50 —— 见 KdbxKdfAcceptanceTest 首例（固定预算确定性断言）；
  ② 堆预算低于目标内存 —— 判据注入预算上下邻点 + 读侧同判据；③ 每通道下界上下邻点 —— 判据与读侧同结论；
  ④ 超大 Long / 非法版本 / 盐长 —— 无溢出旁路、不触发派生；⑤ UI 组合 —— 非法组合不改磁盘/会话（app 用例 +
  写侧零字节用例、对话框动态禁用），合法组合真实写后重开取代表档（32 MiB × 5 轮 × p4 既有用例，真派生重开
  + 错误口令拒解）。

## 3. 门禁读数

=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 510 份；分册登记 512 条；全量索引 512 条；最大 §513）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 647 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：63 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=9  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 760 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 511 个 / 批次正文 509 份；现树 工作区：512 / 510；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 760 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 760 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 760 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===

## 4. 限制与后续边界

- **高成本合法组合的真机往返未做**：方案 §4「所有 UI 组合合法组合实际写后重开」未逐项执行——仅执行代表档
  （32 MiB × 5 轮 × p4 的真实写盘重开与错误口令拒解）。256 MiB × 32（恰在 `2^33` 联合预算上界）等高成本组合的
  设备侧真实派生 / 往返未跑；判据面已由注入预算的确定性单测锁定。
- **未做官方实现（KeePass / KeePassXC）对拍**：本批不改 KDBX 格式、头部字段与写出字节布局（仅前置拒绝非法
  参数），互操作面零变化，故未新增对拍；不对拍不构成「接受域取值与官方一致」的额外证据。
- **写侧 / UI 的动态设备预算（JVM 堆一半）未真机实测**：该面由共享判据的**注入预算**单测覆盖
  （`KdbxKdfAcceptanceTest` 上下邻点）；宿主无法确定性固定设备堆，`KdbxFile.save` 对「当前设备堆」的实际拒绝
  未单独成例。
- **基准推荐值未按判据钳制**：`KdfBenchmark` 推荐上限 512 MiB × 20 轮（= 10.7 GiB 字节·轮）在 memoryClass
  很大（≥ ~430 MiB 目标内存档）的设备上可超过 `2^33` 联合预算——该推荐值现在会被对话框判据**禁用「应用」并
  显示原因**（不会再写出不可重开的库），但推荐本身未钳制进接受域（crypto 模块不可反向依赖 database，钳制需
  设计注入面）；留待后续批次评估。
- **未触达原生绑定 / JNI / Rust**：改动仅 `database` 层 Kotlin 与 `app` 层设置 UI / 控制器，不触发四层设备测试
  与原生验证义务；`*/src/androidTest/**` 零新增、零修改。
- **行数分档**：本批新增 `KdbxKdfAcceptance.kt`（158 行）；`KdbxFile.kt` 496 → 499（仍 < 500）；
  门禁 `tier1=0 / tier2=35 / budget=35` 持平。
