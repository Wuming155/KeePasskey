# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。

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

> **暂无开放项**。（最近一条 `ISSUE-P1-431` 封印载荷瘦身已于 §401 整条闭环，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §401）

## P2 中危缺陷与协议/测试缺口（3 项）

> 前 5 条（`ISSUE-P2-466` ~ `470`，出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)）已于 §446 整条闭环。
> 后 3 条为 **§446 真机实跑新发现**：`490` 设备侧测试判据在等号边界自相矛盾（`maxHeap` 恰为 256 MiB 即必红，**已修，待跨设备复跑**）；`491` 漏更设备侧用例致 `:app:androidTest` 编译失败并**静默阻断**第四层真机义务（**已修，剩 CI 机检接线**）；`492` `:app:` 层设备侧在 MIUI 真机上因系统 UID 冻结 + autofill 服务反复 bind/unbind 无法推进（**环境面欠账**）。


### ISSUE-P2-490：`InlineCompressedBinaryBudgetDeviceTest` 的 `2D ≤ M` 分支判据在**等号边界**必然失败（设备侧测试缺陷 · §446 发现）

- **核实时间点**：2026-10-05（真机 `M332BF` / Android 17 / API 37 实跑）；**核实方式**：真机 `:database:connectedDebugAndroidTest` 18 例中 1 例红，取其 `TEST-M332BF - 17.xml` 的 `<failure>` 原文（`InlineCompressedBinaryBudgetDeviceTest.kt:68`，`maxHeap=268435456` 即 **256 MiB**）；对照 §217 批次记录该用例在 Pixel_10 AVD 上 `maxHeap = 192 MiB` 时走 OOM 分支通过 ⇒ **同一用例在不同堆界设备上结论相反**。另经 `git diff --stat -- database/` 确认本批零触碰该模块，非回归。
- **背景**：该用例以「解压峰值下界 `≈ 2D`」为判据（`D = 128 MiB`，故 `2D = 256 MiB`）：`2D > M` 断言必须 OOM，`2D ≤ M` 断言必须成功。但 `2D` 只是**下界**——真实峰值还含 base64 解码中间态、`GZIPOutputStream` 解压缓冲与 `toByteArray()` 副本，故实际占用**严格大于 `2D`**。于是 `2D == M`（本机恰好 `256 MiB == 256 MiB`）时 else 分支要求成功，而真实行为必然 OOM ⇒ **判据在等号边界自相矛盾**。这不是环境噪声：`maxHeap = 256 MiB` 是 Android 常见堆界，凡命中该值的设备都会红；此前只在 192 MiB 的 AVD 上验证过，缺陷被掩盖。
- **涉及文件**：`database/src/androidTest/java/com/keepasskey/database/xml/InlineCompressedBinaryBudgetDeviceTest.kt`。
- **验收标准**：① 判据改为区分「`2D` 下界」与「实测峰值」，使 `2D ≤ M < 真实峰值` 区间不再自相矛盾（可按 `Assume` 标注该区间为环境不可判别，或改为断言「成功 ⇒ `M` 显著大于 `2D`」并写明余量依据）；② 须在 `maxHeap` 为 192 MiB 与 256 MiB 两类设备上各实跑一次绿（AVD + 真机），不得只在单台设备上验证；③ `:database:connectedDebugAndroidTest` 全绿且 `skipped == 0`；④ 结论回写 `docs/architecture/已知工程限界.md` §4.1 设备侧覆盖现状（`ISSUE-P2-200` 落点① 的量化结论依赖此判据）。

### ISSUE-P2-491：`ISSUE-P1-431` 移除 `keyFileBytes` 时漏更设备侧用例，`:app:androidTest` 编译失败已阻断第四层真机实跑（§446 发现 · 本批已修）

- **核实时间点**：2026-10-05（真机 `M332BF`）；**核实方式**：真机 `:app:connectedDebugAndroidTest` 报 `Kotlin compiler: NAMED_PARAMETER_NOT_FOUND / No parameter with name 'keyFileBytes' found`（`QuickUnlockSealDowngradeDeviceTest.kt:70`）；经 `git log -S keyFileBytes` 定位移除方为 `5c3005ea`（`ISSUE-P1-431` 封印载荷瘦身），经 `git diff --stat -- app/src/androidTest/` 确认本批零触碰该文件 ⇒ **先前批次遗留，非本批回归**。
- **背景**：`BiometricEnrollmentCoordinator` 的 `keyFileBytes` 形参已随封印载荷瘦身移除（密钥文件字节不再进 Keystore 载荷），但该设备侧用例的 `buildCoordinator` 仍传该具名实参 ⇒ `:app:androidTest` **编译期**即失败。后果不止一例红：AGENTS.md 测试资产纪律要求原生面改动须**四层 `connectedDebugAndroidTest` 真机实跑**方可入库，而 `:app:` 层编译不过即**根本无法实跑**，该义务被静默阻断——与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型，只是形态是「用例存在 ≠ 用例可编译」。本批已删除该陈旧具名实参（**只删一行、不删用例**）解除阻断。
- **涉及文件**：`app/src/androidTest/java/com/keepasskey/app/security/QuickUnlockSealDowngradeDeviceTest.kt`。
- **验收标准**：① `:app:connectedDebugAndroidTest` 编译通过并在真机实跑（用例不得被删除或 `Assume` 跳过）；② **补一条机检**：`*/src/androidTest/**` 与 `src/main` 的构造器具名实参一致性属编译期事实，但 `compileDebugAndroidTestKotlin` **不在 `test` 任务依赖图内** ⇒ 须新增把 `:app:compileDebugAndroidTestKotlin`（及余三层同任务）纳入 CI 的门禁条，避免同类遗漏再次静默阻断真机义务；③ 回写 `AGENTS.md` §5：设备侧「编译通过」亦不构成验证证据的补充说明（与 `:app:compileDebugScreenshotTestKotlin` 同款，须显式跑）。

### ISSUE-P2-492：`:app:` 层设备侧在 MIUI 真机上无法推进（系统 UID 冻结 + autofill 服务反复 bind/unbind，环境面欠账 · §446 登记）

- **核实时间点**：2026-10-05（真机 `M332BF` / Android 17 / API 37 / MIUI 定制系统）；**核实方式**：`:app:connectedDebugAndroidTest` 首轮运行 **40 分钟零结果**（无 `TestRunner` 日志、无宿主结果 XML），主动终止；按 CI 既定口径（`build.yml:482` 将 `AutofillAuthChainDeviceTest` 登记为「系统填充 UI 呈现」环境敏感面、对模拟器以 `notClass` 排除）排除该类重跑，推进 14 分钟仍未出结果，再次主动终止。`logcat` 取证：系统对被测进程反复 `freezeUid SUCCESS`（`reason=freeze_able` / `from system`），且 `KeePasskeyAutofillService` 持续 bind/unbind（`MiuiAutofillServiceHelper: initAutofillServicePackageName`）；`ps` 确认 `am instrument` 与被测进程均存活但无进展。
- **背景**：`:app:` 层设备侧共 24 个测试文件，含自动填充认证链路、系统 UI、生物识别等**深度依赖系统服务生命周期**的用例。在 MIUI 的 UID 冻结策略下，被测进程被系统冻结且 autofill 服务持续解绑重绑，设备侧无法推进。**性质判定为环境面**（厂商系统冻结策略），非产品缺陷、非回归——`git diff --stat -- app/src/` 确认本批在该模块只改协程调度器归属（`SettingsHealthController`），其验证面是宿主单测、不依赖设备层。须如实登记而非以「设备侧无新增义务」一笔带过：本条是 §446 四层实跑中**唯一未取得读数的一层**。
- **涉及文件**：`app/src/androidTest/**`（涉事用例集待定位到具体类）。
- **验收标准**：① 在**非 MIUI 设备或 AVD** 上跑通 `:app:connectedDebugAndroidTest` 并取得读数（须含 `skipped == 0`）；② 定位并登记具体受阻用例类/方法（当前只能确认「整层无法推进」，**未能定位到单个用例**——如实登记该取证缺口）；③ 评估该层是否需拆分「依赖系统服务生命周期的用例」与「纯应用内用例」两个 `notClass` 分组，使厂商系统差异不至于**整层不可测**；④ 结论回写 `docs/architecture/已知工程限界.md` §4.1 设备侧覆盖现状。

## P3 低危问题、特性接线与体验优化（1 项）

> 原 **8 项**（`ISSUE-P3-474` ~ `480` 七条 Rust 原生面去重 / 空间收口 + `ISSUE-P3-489` 工序项）已于 §447 整条闭环
> （四层 `connectedDebugAndroidTest` 真机实跑：Redmi 4X `:crypto:` 37/37 · `:database:` 18/18 · `:sync:` 25/25 · `:app:` 67/67，全 `skipped=0`）。
> 本条为 §447 真机取证过程的**新发现**（规则 6.1：发现新问题即时补登）。

### ISSUE-P3-493：设备侧 connected 任务在「被测应用起不来」时静默 `tests=0` 且 `BUILD SUCCESSFUL`

- **核实时间点**：2026-10-05；**核实方式**：AVD `emulator-5554`（`Pixel_10` / API 36，用户 0 `RUNNING_LOCKED`）上两次
  `:app:connectedDebugAndroidTest`（含一次单类 `-P...class=` 过滤）均产出 `TEST-Pixel_10(AVD) - 16.xml` 的
  `<testsuites tests="0" .../>`、`test-result-exit-code.txt` = `0`、`BUILD SUCCESSFUL`，而 `adb shell pm list packages`
  显示设备上**从未**装上 app / 测试包（UTP 未安装）；同一设备手动 `am instrument` 复现真实成因：
  `INSTRUMENTATION_RESULT: shortMsg=Process crashed` + `INSTRUMENTATION_CODE: 0`，异常原文
  `IllegalStateException: SharedPreferences in credential encrypted storage are not available until after user (id 0) is unlocked`
  （app 在 `MainApplication.onCreate` 即崩）。对照：同批在真机 `Redmi 4X`（已解锁）上同一任务 67/67 全绿。
- **背景**：`am instrument` 在「一个用例都没跑」时返回 `INSTRUMENTATION_CODE: 0`（成功码），UTP 据此归为
  `tests=0 + 成功` ⇒ **整层设备门禁空转而显绿**，与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型、
  与 `ISSUE-P2-491`「用例存在 ≠ 用例可编译」互为两层。触发前提（Android 用户未解锁 / 被测进程起不来）属环境面，
  但本仓消费方对 `connectedDebugAndroidTest` 的 `BUILD SUCCESSFUL` **无任何「真的跑了用例」判别**，
  故同类环境一旦出现，第四层义务会再次被静默豁免。
- **涉及文件**：`tools/doc/`（新增结果断言脚本落点）；`.github/workflows/build.yml` 的 `device-gate`。
- **验收标准**：
  ① 新增机检：解析 `*/build/outputs/androidTest-results/connected/**/TEST-*.xml`，对**本应有用例的层**断言
  `tests > 0`（`tests == 0` 即退出码 1）；与本仓既有「唯一尺子」`count_test_results.py` 分工不重叠（后者只数 JVM 单测）；
  ② `device-gate` 挂该断言（或 CI 内等价步骤），使「空转」不再显绿；
  ③ 如实登记：本形态在「Android 用户未解锁」前置下实测可复现；修复前**不得**据 `connectedDebugAndroidTest` 的
  `BUILD SUCCESSFUL` 推定「该层已跑」。

