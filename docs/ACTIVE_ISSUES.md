# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
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

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（本区最近一次归零：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)）。

---

## P2 中危缺陷与协议/测试缺口（2 项）

> 本区最近一次归零：§230 ~ §233 四批闭环 `ISSUE-P2-226` / `P2-227` / `P2-228` / `P2-229`
> ——即用户 2026-09-20 真机报告的四项问题（自身界面仍出现填充建议 / 生物识别被禁用时归因笼统 /
> 自动填充卡三个假开关且文案谎称需要无障碍 / 新建库无位置选择入口）；实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/) 的 230 ~ 233 号批次。
> 下列两条为 **2026-09-20 过度安全设计评估与三轮审计对账**后，经 HEAD 对拍登记的余量。

### ISSUE-P2-231：依赖完整性锁定缺失（Java 依赖面全链无哈希/锁定校验）

- **核实时间点**：2026-09-20（过度安全设计评估报告 · 供应链条审计对拍）。
- **核实方式**：`gradle/` 目录列举仅 `wrapper/`、`gradle-daemon-jvm.properties`、`libs.versions.toml`
  （无 `verification-metadata.xml`）；全仓文件名检索 `*.lockfile` **0 命中**；构建脚本中
  `verification-metadata` / `dependencyLocking` / `lockAllConfigurations` **0 命中**；
  [`docs/resolved/batches/87-CI产物可见性与供应链硬化批次.md`](resolved/batches/87-CI产物可见性与供应链硬化批次.md)
  第 33-36 行**明载「本批不引入」**并给出理由（生成完整元数据需在干净环境重写全量依赖图哈希等）。
- **背景与影响**：`SECURITY_RECHECK_2026-09.md` §6.8 的 `SUPPLY-02` 复审结论为
  **`CONFIRMED VULNERABILITY` / MEDIUM**——已建链条为「Gradle 分发 ✅ / wrapper JAR ✅（仅 fast-gate）/
  Rust ✅ / **Java 依赖 ❌**」，并构成复核登记的可成立短链
  「**投毒传递依赖 → 未被发现 → 未被校验 → 任意代码执行**」（前提是上游投毒，**当前无证据**）。
  与既有 `dependencyCheck`（已知 CVE 扫描，PD-04 / 限界 §21）**正交、不可互相替代**。
- **决策冲突留痕（开工前必须处理）**：§87 已作「不引入」的结论 ⇒ 本条**不得**直接实施，
  必须先落一次**重开决策**（写明覆盖 §87 的**新证据/新理由**），否则即属静默推翻既有裁决。
- **验收标准**：
  - AC① **重开决策**：在 ①`verification-metadata.xml`（sha256 全量 + 更新流程）与
    ② Gradle dependency locking（`--write-locks` → `*.lockfile`）之间给出选择与理由；
    若判为「仍不引入」，须给出替代缓解口径并把结论登记到 [`产品裁决登记.md`](architecture/产品裁决登记.md)。
  - AC② 若实施：**同批交付「新增依赖时的更新流程」**（脚本或 CI 步骤），并接线 `.github/workflows/`，
    否则该闸门将成为长期红灯源。
  - AC③ 验证：写入操作**幂等**（重复执行不产生漂移）；CI 实跑通过——供应链改动
    **不得只凭宿主单测判定**。
  - AC④ **不得**以本条为由放宽或删除既有 `dependencyCheck` 的 `failBuildOnCVSS = 7.0` fail-closed 断言
    （PD-04 与限界 §21 的口径不变）。
- **依据**：`SECURITY_RECHECK_2026-09.md` §6.8 `SUPPLY-02` / §7 `CHAIN-E`；
  [`docs/resolved/batches/87-CI产物可见性与供应链硬化批次.md`](resolved/batches/87-CI产物可见性与供应链硬化批次.md)；
  [`docs/architecture/已知工程限界.md`](architecture/已知工程限界.md) §21。

### ISSUE-P2-232：完整性风险升级无「主动熔断」接线（探测有效，但活动会话不被驱逐）

- **核实时间点**：2026-09-20（同轮对账；三轮审计的最终分歧点之一，结案口径为「维持现状 + 接线闭环」）。
- **核实方式**：全仓检索 `RuntimeIntegrityReport` / `RuntimeIntegrityGate` 的**全部消费点**——
  仅 `SecuritySettingsScreen` 与 `SettingsUiStateProjection`（风险提示卡）、
  `BiometricAuthManager.currentEnforcement()`（快速解锁门控）、
  `KeePasskeyAutofillService.awaitEnforcement()`、`KeePasskeyCredentialProviderService.awaitEnforcement()`
  （凭据下发门控）；`RuntimeIntegrityDetector` 的 `currentEnforcement()` / `refresh()` 中
  **无** `AutoLockSessionGuard.triggerLock` 或 `clearSensitiveData` 调用；`AutoLock*` 的熔断
  仅由熄屏 / 后台超时驱动（`app/src/main/java/com/keepasskey/app/security/AutoLockSessionGuard.kt:40-76`）。
- **背景与影响**：`P3-120` 已由真机实测证成（三形态 **3/3** 命中，`[D]` 级，见
  `RuntimeIntegrityDetector.kt:216-229` 与 [`docs/records/运行完整性检测Frida实测基线.md`](records/运行完整性检测Frida实测基线.md)），
  但探测结果在**已解锁的活动会话**期间只驱动 UI 提示与**后续**通道门控，
  **不缩短「已被注入的进程仍持有整棵解密对象树」的暴露窗口**。
- **前置（必须先裁决）**：是否「风险升级即锁库」是**产品取舍**（用户可能正在编辑条目 / 浏览设置页时被强制退回解锁页），
  且检测面存在**误伤形态**（落点层为「文件存在性」检查：`/data/local/tmp/frida-server` **仅在案未运行**即判 `COMPROMISED`）
  ⇒ 须先经 [`产品裁决登记.md`](architecture/产品裁决登记.md) `PD-13` 裁决（含分级判据），裁决前不得改动。
- **验收标准（PD-09 裁决后细化，以下为下限）**：
  - AC① 判定策略纯函数化、宿主可测（`COMPROMISED → 应熔断`；`ELEVATED → 不熔断`）；
  - AC② 接线**复用** `AutoLockSessionGuard.triggerLock` 内核并保持幂等，不新造熔断路径；
  - AC③ 设备侧用例：真机注入后 **≤1 个重扫周期**内会话被锁且敏感缓冲清零；
  - AC④ 负例：`ELEVATED`（含「改名运行但未 attach」形态）**不**触发熔断；
  - AC⑤ 涉及运行时接线 ⇒ 按测试资产纪律②跑 `:app:connectedDebugAndroidTest`（真机）。
- **依据**：`RuntimeIntegrityDetector.kt` / `AutoLockSessionGuard.kt` / `KeePasskeyAutofillService.kt` /
  `KeePasskeyCredentialProviderService.kt` 直读；`SECURITY_RECHECK_2026-09.md` §6.8.1 与 §15.3 #11（P0 判据）。

---

## P3 低危问题、特性接线与体验优化（4 项）

### ISSUE-P3-230：打开已有 SAF 库时持久化授权失败被静默吞掉（重启后库打不开且无告警）

- **核实时间点**：2026-09-20 随 `ISSUE-P2-229` 整改过程中同处代码走查发现。
- **核实方式**：`VaultLifecycleCoordinator.importExternalDatabase`（现 `:213` 附近）对
  `takePersistableUriPermission(uri, READ or WRITE)` 包 `try { } catch (_: Throwable) { }`，
  注释理由为「部分外部 Provider 不支持持久化授权，容错继续」；调用链
  `OpenExistingVaultDialog.kt:43`（`OpenDocument`）→ 本方法 → 解锁走 `openStream`。
- **背景与影响**：临时授权在**本次会话内**有效，故打开与保存都能成功；一旦进程重启，
  未拿到持久化授权的 uri 会失去读权限 ⇒ 该库**从列表里可见却永远打不开**，
  且应用不给任何归因提示（用户只会看到「解锁失败」）。新建路径已在 §233 改为
  「授权失败即在建库前显式失败」，本条是同一缺陷在**已有库**侧的残留面。
- **验收标准**：AC① 授权失败不得静默：至少落 `AppLog.w` 并在打开成功后给出一次性可见提示
  （文案含「重启后可能需要重新选择该文件」）；AC② 列表项对「无持久化授权」的库给出可辨识状态，
  点击可直接重新拉起 `OpenDocument` 重授；AC③ 不改变既有「provider 不支持持久化仍允许本次打开」
  的容错取向（不得改为硬失败，否则云盘类 provider 会整体不可用）。

### ISSUE-P3-233：复核报告 `P3-120` 状态陈旧（基线漂移，已实际造成两轮误判）

- **核实时间点**：2026-09-20（对拍 HEAD 时发现）。
- **核实方式**：`SECURITY_RECHECK_2026-09.md` §10.1 仍把 `P3-120` 列为「需设备侧证据」、
  §3.3.2 矩阵仍标 `[U]` / `UNVERIFIED`；而 HEAD 的 `app/src/main/java/com/keepasskey/app/security/RuntimeIntegrityDetector.kt:216-229`
  KDoc 已明载 **2026-09-16 真机实测（Redmi 4X / LineageOS / Android 17 / API 37，frida-server 17.15.3）
  三形态 3/3 命中**，同源证据见 [`docs/records/运行完整性检测Frida实测基线.md`](records/运行完整性检测Frida实测基线.md)。
- **背景与影响**：该漂移在 2026-09-20 的过度安全设计评估与三轮审计中**已实际造成双方连续两轮
  基于陈旧前提的推演**（`SYS-5`「基线与 HEAD 混用」的典型复发），直至补读被测代码才瞬时收敛。
  这是一条**会持续误导后续评审**的文档缺陷，而非措辞偏好。
- **验收标准**：
  - AC① §10.1 该行更新为「**已完成（`[D]`）**」，并给出证据指针（KDoc 行号 + 实测记录文档）；
  - AC② §3.3.2 矩阵该行同步为已实测口径；
  - AC③ `§15.3` 方法学增补一条：**引用任何复核结论前必须对拍被测代码 / 产物的 HEAD 现状**，
    并以本轮为实例（含「双方同时引用旧基线」这一关键情节）；
  - AC④ 改后必跑 `bash tools/audit/check_recheck_consistency.sh`（fail-closed）与
    `python tools/doc/check_md_links.py`；改写时不得在正文复述脚本的禁用短语（§15.2(p) 的既有教训）。
- **依据**：`RuntimeIntegrityDetector.kt:216-229`；[`docs/records/运行完整性检测Frida实测基线.md`](records/运行完整性检测Frida实测基线.md)；
  `SECURITY_RECHECK_2026-09.md` §2.5 `SYS-5` / §10.1 / §15.3。

### ISSUE-P3-234：DAL 出口「IP 字面量」面未定案（`SUPPLY-06` 余项）

- **核实时间点**：2026-09-20（A2 争议结案时登记）。
- **核实方式**：直读 `app/src/main/java/com/keepasskey/app/passkey/DomainMatcher.kt:18-60`——
  `extractDomain` 仅做 scheme / 路径 / 查询 / 片段 / 认证信息 / 端口剥离与归一化，
  **未见 IP 字面量拒绝逻辑**（`isRpIdTrustedForCreation` 的 IP 分支未逐行读到）；
  `SECURITY_RECHECK_2026-09.md` §10.1 亦登记该项为「**本轮未直读**」。
- **背景与影响**：OkHttp 对 **IP 字面量主机名不经过 `Dns` 接口**（直接 connect），
  ⇒ `SsrfGuardDns` 覆盖不到该面，`DalVerifierModule` 的 `SsrfGuardSocketFactory` 是唯一覆盖层位
  （这正是 2026-09-20 A2 裁决「机制保留、叙事回归纯纵深防御」的依据）。若 `rp.id` 可为 IP 字面量，
  该面须保持；若上游已被拒绝，则应登记为「不可达」以防未来被误判为冗余而拆除。
- **验收标准**：AC① 直读 `DomainMatcher` 全文与 `DalEndpointResolver`，判定 `rp.id` 取
  `"127.0.0.1"` / `"169.254.169.254"` / IPv6 字面量时的行为（直读或补宿主用例）；
  AC② 结论按性质分流——「不可达」入 [`已知工程限界.md`](architecture/已知工程限界.md)、
  「取舍」入 [`产品裁决登记.md`](architecture/产品裁决登记.md)，并在 `DigitalAssetLinksVerifier` KDoc 就地声明；
  AC③ 不改动 TLS-only 与 `SocketFactory` 现状（A2 已结案）。
- **依据**：`DomainMatcher.kt`；`SECURITY_RECHECK_2026-09.md` §6.8 `SUPPLY-06` / §10.1；
  `DigitalAssetLinksVerifier.kt`（`@DalHttpClient` 加固客户端 KDoc）。

### ISSUE-P3-235：`RC-02` 敏感缓冲所有权收口（设计先行，禁止一次性大改）

- **核实时间点**：2026-09-20（核对 `ACTIVE_ISSUES` 与复核报告 `RC-02` 时发现**无跟踪条目**）。
- **核实方式**：全仓 `fill(0)` 直读命中 100+ 处；`SECURITY_RECHECK_2026-09.md` §8 `RC-02` 载
  「缺失的不是**意识**而是**收口点**」并给出同文件内实证（`CbcStreams.kt` 的 `:84/:88/:89` 已清零而
  `:99` 漏清）；同报告 `R-CLEAR-2` 载「共享引用 → 就地清零会误伤」是 5 条清零类条目**反复无法简单修复**
  的共同原因；§9.6 载 **7 条「字面实施会写坏数据库」的修复陷阱**。
  另 [`已知工程限界.md`](architecture/已知工程限界.md) §1.6 的**解除条件**（池内擦除须先补齐
  `BinaryItem` 所有权规则）与本条**同源**。
- **背景与影响**：清零义务目前散落在各处的 `Arrays.fill`，所有权语义从未定义 ⇒ 逐个打补丁必然再漏，
  且每次补丁都存在「写坏库 / 误伤共享引用」的负期望风险。本条是**架构层收口**，不是逐处补丁。
- **验收标准**：
  - AC① **先交设计**（自包含在本条或其批次正文）：敏感缓冲类型 / 所有权规则 +
    **非秘密数据豁免下界清单**（如内容哈希中间量、同步结构体等——须逐项给理由）；
  - AC② 设计评审通过后再**逐处迁移**，禁止一次性大改；迁移须同时覆盖 `§1.6` 池内擦除的解除条件评估；
  - AC③ 迁移过程不得触碰 §9.6 的 7 条写坏库红线（`P3-105` 的 `copyOf`、`P2-48` 的逐引用物化、
    `P2-60` 的清理时机等）；
  - AC④ `.\gradlew.bat test` 全绿；涉及原生 / 会话路径的改动按测试资产纪律跑相应设备侧层。
- **依据**：`SECURITY_RECHECK_2026-09.md` §8 `RC-02` / `R-CLEAR-2` / §9.6；
  [`docs/architecture/已知工程限界.md`](architecture/已知工程限界.md) §1.6 / §1.7。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，§224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。
