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

> **在册批次**：**13 条**（`ISSUE-P2-556` / `ISSUE-P2-557` / `ISSUE-P3-558` ~ `ISSUE-P3-568`），
> 来源 **2026-10-09 第二轮多维深度审查**（多代理编排：代码质量 / 架构 / 安全 / 测试与文档四路只读取证
> + 主审逐条独立复核），证据与「被推翻 / 降档的代理结论」留痕见
> [`records/多维深度审查报告_2026-10-09.md`](records/多维深度审查报告_2026-10-09.md)。
> 本轮为**纯登记**（未动代码），**不占用批次编号**——下一批次仍为 **§483**（沿用 `f7c6fefb` 先例）。
> 上一批 2026-10-09 五轴质量审查登记的八条（`ISSUE-P2-548` / `P2-549` / `P3-550` ~ `P3-555`）已于
> **§482** 整批闭环归档——正文见
> [`resolved/batches/482-存量八条整改批次.md`](resolved/batches/482-存量八条整改批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

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

## P2 中危缺陷与协议/测试缺口（**2 项**）

> ### ISSUE-P2-556：两道新增机检的判据存在**可复现的形态性漏检**（门禁有效性缺口）
>
> - **现象**：`§482` 新增的第 13 道（明文口令载体 `toString` 护栏）与第 14 道（异常 message 不进文案槽）
>   门禁，在其**自述判据**下可被**合法 Kotlin 写法**绕过——闸门报绿而真违规静默通过。
> - **核实时间点 / 方式**：2026-10-09，**实跑探针**（临时树，已清理）+ 逐行读判据源码。
>   判据位置：`tools/doc/check_message_not_in_user_text.py:63`（`MESSAGE_ACCESS_RE`）、`:97`（逐行 `scan`）；
>   `tools/doc/check_plaintext_carrier_to_string.py:55`（`DECL_RE`）、`:117-121`（`_class_body`）、`:166`。
> - **实测读数（探针树单文件五形态）**：
>   ① message 护栏——探针含「多行调用」「方法接收者 `result.exceptionOrNull()?.message`」「同行简单接收者 `e.message`」
>   三处真违规，输出 `检查文件 1 个；文案槽 8 类；命中 1 处` ⇒ **只命中第三处，3 处漏 2 处**；
>   ② 明文载体护栏——探针含「无体类 `data class LeakyNoBody(val password: String)`」与
>   「泛型类 `data class GenericCarrier<T>(val password: String)`」两处**真泄漏**，输出
>   `检查文件 1 个；允许清单 0 个；命中 0 处` → **`PASS` / EXIT 0** ⇒ **两处真泄漏全部漏检**。
> - **根因（逐条已读源码确认）**：
>   ① message 判据以**单行**为扫描单元，且要求 `.message` 的接收者是**紧邻的单词字符** ⇒ 多行调用（槽标记行与
>   `.message` 行各在一行）与方法接收者（前驱为 `)`）**双双不命中**；
>   ② `SLOT_MARKERS`（8 类）**不含** `showSnackbar(` / `makeText(`，而仓内二者**在用 5 处**
>   （`AppGlobalSnackbarHost.kt:76` / `ConflictResolutionScreen.kt:82` / `GeneratorScreen.kt:110` /
>   `UnlockedNotificationCopyAction.kt:136` / `ClipboardSecurityManager.kt:289`）；
>   ③ `DECL_RE` 要求类名后**仅空白**紧跟 `(` ⇒ `data class Foo<T>(...)` 与 `data class Foo constructor(...)`
>   整个声明**不进判定**；
>   ④ `_class_body` 取「参数表右括号 → 下一个**行首** `}`」；被判定类**无体**时该区间**越过本类**延到**后随兄弟类**，
>   兄弟体内的 `override fun toString(` 使本类被**误判为「已覆写」**而放行。
> - **与自述的冲突**：两脚本 KDoc 分别声称「文案槽内出现 `.message` 即红」「`data class`……就必须覆写」，
>   对上述形态**不成立**；`§482` 的「先红后绿」反校样本**恰好全落在被覆盖的形态上**，故**证明不了**本条漏检面。
>   （与 `ISSUE-P1-538`「普查口径写死 ⇒ 形态性漏检」同型，在其所引先例上再次复发。）
> - **影响**：`ISSUE-P2-549` 与 `ISSUE-P3-550` 的**自动防线存在已知可绕形态**。
>   **当前无实伤**（现网残留 `.message` 站点均为资源载体 / `UiMessage`；承载 `password: String` 的 data class 均已有覆写），
>   属**未来回归会静默变绿**。
> - **涉及文件**：`tools/doc/check_message_not_in_user_text.py`、`tools/doc/check_plaintext_carrier_to_string.py`
> - **验收标准**：
>   ① `DECL_RE` 兼容类名后可选的 `<...>` 与可选的 `constructor`；
>   ② 类体收口改**花括号配对**，无体 `data class` **不得外延**到兄弟类（无体 ⇒ 视为无覆写）；
>   ③ message 判据改为**按调用括号配对取整段实参文本**（非逐行），接收者正则放宽（如 `([\w)\]]+)\s*\??\.\s*message`）；
>   ④ `SLOT_MARKERS` 增补 `showSnackbar(` / `makeText(`；
>   ⑤ 两条判据的 `--selftest` **必须补入本轮 5 类反样本**并以「反样本必须命中」作为该闸门自身的验收前置
>      （**为护栏本身立「反样本覆盖」纪律**）。
>
> ### ISSUE-P2-557：`SyncFailureNotifier` 唯一收集器**无异常隔离** ⇒ 一次意外异常即**永久**静默失效
>
> - **现象**：合并后的唯一收集协程一旦抛出未被捕获的异常，同步失败通知**永久不再工作**，且 `start()` 无法恢复。
> - **核实时间点 / 方式**：2026-10-09，逐行读源码 + 交叉读 `guardedScope` 语义。
> - **根因**：`app/.../sync/SyncFailureNotifier.kt:194-203` 的 `signals().collect { … }` **整段无 `try/catch`**；
>   `post()`（`:214-243`）只捕 `SecurityException`、`cancel()`（`:245-251`）同；
>   `permissionPrompter.isGranted()`（`:215`）与 `NotificationIntents.openAppForSyncFailure(context)`（`:227`）
>   在 try **之外**。任何**非 `SecurityException`** 的 `Throwable` ⇒ 逃出 `collect` ⇒ `launch` 协程失败终止。
>   宿主 scope 为 `guardedScope(Dispatchers.Default, …)`（`:168`），而 `GuardedScope.kt:44-46` 的
>   `CoroutineExceptionHandler` **只落日志、不重抛**（其 KDoc 自陈「该任务静默失败」）⇒ 无重启、无上抛、无用户可见。
>   **且 `started`（`:170`）是裸 `var`（非 `@Volatile` / 非同步）且在 `launch` 之前置 `true`（`:192-194`）**
>   ⇒ 外部再次 `start()` 会被 `if (started) return` 直接挡掉——**不可自愈**。
> - **影响**：该组件的**唯一存在理由**是让同步失败可见；其自身失效形态是「静默变哑」，
>   **正是 `ISSUE-P3-298` / `ISSUE-P2-548` 要消灭的「库长期未同步成功而用户零感知」从另一扇门复发**，
>   且更隐蔽（无指向「通知已死」的线索，只有一条通用「未捕获的协程异常」日志）。
> - **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncFailureNotifier.kt`、
>   `app/src/main/java/com/keepasskey/app/coroutines/GuardedScope.kt`
> - **验收标准**：
>   ① `collect` 体内对**单个元素**做异常隔离（元素级 `try/catch` + 脱敏日志），使**单元素失败不终止收集**；
>   ② `started` 改 `@Volatile` 或纳入 `SyncFailureNotificationState` 的同步区（与本批刚修掉的 `posted` 竞态同款）；
>   ③ 新增用例：注入 `post()` 必抛非 `SecurityException` 的场景，断言**后续周期仍能正常发 / 撤通知**。
> - **前提边界（如实声明）**：触发需平台层抛出非 `SecurityException` 的 `Throwable`，**静态层面未能穷举其发生率**；
>   按「失效形态最严重 + 修法极廉价」定 P2。若认定概率可忽略可降 P3，但 `started` 的**不可自愈性独立成立**。

## P3 低危问题、特性接线与体验优化（**11 项**）

> ### ISSUE-P3-558：`SyncOpenResult.RemoteRejectedUsingCache` 违反 `equals`/`hashCode` 契约
>
> - **核实时间点 / 方式**：2026-10-09，逐行读源码（主审与质量线**各自独立**发现，结论一致）。
> - **根因**：`sync/.../engine/SyncEngineResults.kt:156-164`——`equals` 只比 `localBytes`（**忽略 `cause`**），
>   `hashCode` 却为 `31 * localBytes.contentHashCode() + (cause?.hashCode() ?: 0)`；`Throwable.hashCode()` 为身份哈希
>   ⇒ 同字节、异 `cause` 的两实例 `equals == true` 而 `hashCode` 不同。
>   同文件其余类型（`RemoteUnreachableUsingCache` / `RemoteUnreachable` 等）**两处口径一致**，本处属**单点改了一半**。
> - **影响**：`HashSet`/`HashMap` 容器查找失配。现网**无** `Set/Map<SyncOpenResult>` 使用点
>   （`RemoteRejectedUsingCache` 全仓仅 4 处引用），**无实伤**，属潜在契约缺陷。
> - **涉及文件**：`sync/src/main/java/com/keepasskey/sync/engine/SyncEngineResults.kt`
> - **验收标准**：`equals` 与 `hashCode` 就 `cause` 取一致口径（都纳入且结构等同判定，或都不纳入并与兄弟类型对齐）；补同字节异 `cause` 用例。
>
> ### ISSUE-P3-559：通知幂等使「用户划掉后，持续失败不再复现通知」——**未登记的行为面变化**
>
> - **核实时间点 / 方式**：2026-10-09，对照整改前源码逐行核实。
> - **根因**：`SyncFailureNotifier.kt:113-121` `transitionTo` **仅在 `posted` 翻转时**给 `POST`；`onEngineFailure()` 恒把目标态设为 `true` ⇒ 已 `true` 时返回 `NONE`（不再 `notify`）。
>   **整改前**（`git show e64a0db7^:…/SyncFailureNotifier.kt`）`post()`（`:107`）**无** `posted` 守卫，**每个**事件都重新 `notify()`（同 ID + `setOnlyAlertOnce(true)`）⇒ 用户划掉后**下一周期会再次亮出**。
> - **失效路径**：库有缓存基线 + 持续鉴权失败 ⇒ 第 1 周期 `POST`；用户点按 / 划掉（`:231 setAutoCancel(true)`）⇒ 通知消失而 `state.posted` 仍 `true`；此后每周期 `onEngineFailure→NONE`、`onOutcome(Error)→NONE` ⇒ **通知再不出现**，直到某次成功同步复位。
>   `state` 无「通知被移除」的回调通道 ⇒ 无法区分「用户划掉」与「逻辑撤销」。
> - **影响**：`ISSUE-P3-298` 的观测窗在一次用户操作后被重新关闭。`§482` 批次正文 §1.6 自述的「本批唯一行为面变化」指剪贴板 `isError`，**本条未登记**，与「行为面变化须如实登记」的纪律不符。
> - **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncFailureNotifier.kt`
> - **验收标准**：二选一并落文档——① 若为有意防抖：在设计 KDoc 与批次 / 限界表登记该口径及复位条件；② 若无意：补「通知被系统 / 用户移除」的观测通道（如 `setDeleteIntent`）并据此允许同因复发。
>
> ### ISSUE-P3-560：`RelativeTimeFormatter` 按 pattern 缓存且**未传 Locale** ⇒ 英文 pattern 混合语言；「与整改前逐字等价」不成立
>
> - **核实时间点 / 方式**：2026-10-09，逐行读源码 + 读资源实值 + 对照整改前三处实现。
> - **根因**：`app/.../ui/model/RelativeTimeFormatter.kt:42` 的 `MONTH_DAY_FORMATTERS` 缓存键**只有 pattern 串**；
>   `:83` `DateTimeFormatter.ofPattern(pattern)` **未传 Locale** ⇒ 取**创建时**的 `Locale.getDefault()`。
>   而英文资源 `app/src/main/res/values-en/strings.xml:1571` 的 `date_pattern_month_day` 为 **`MMM d`（含文本字段 `MMM`）**
>   ⇒ 英文界面 + 非英文默认 Locale 时月名按**系统 Locale** 渲染，产出「**10月 9** 14:30」这类混合语言文案。
> - **行为面差异**：`:23` 声称「与整改前**逐字等价**」。整改前三处（`git show e64a0db7 -- VaultListSyncController.kt SettingsSyncController.kt`）
>   同样未传 Locale，**故错渲染是既有的**；但整改前**每次调用重建** formatter（能跟随默认 Locale 变化），
>   整改后**首次冻结** ⇒ 「逐字等价」在**默认 Locale 中途变化**这一维度**不成立**。
> - **测试面**：`grep RelativeTimeFormatter|formatMillis|formatIsoDate` 在 `*/src/test` **零引用** ⇒ 该新抽出的公共出口**无任何直接回归用例**。
> - **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/model/RelativeTimeFormatter.kt`、`app/src/main/res/values-en/strings.xml`
> - **验收标准**：① 由 `StringsProvider` 或其上游提供 Locale，`ofPattern(pattern, locale)` 显式传参，缓存键改 `(pattern, locale)`；② 修正 KDoc「逐字等价」表述；③ 新增用例（中文 `M月d日` 与英文 `MMM d` 两 Locale × 「今天 / 昨天 / 其余」三支）。
>
> ### ISSUE-P3-561：两道新机检未登记进 `AGENTS.md`
>
> - **核实时间点 / 方式**：2026-10-09，grep 核实（主审与测试线各自确认）。
> - **根因**：`grep -n "check_plaintext_carrier_to_string\|check_message_not_in_user_text" AGENTS.md` **零命中**。
>   `AGENTS.md` 的 `hygiene-gate` 段落逐条枚举仍只列 **12 道**（CI 现跑 **14 道**），§5 命令清单亦无二者条目；
>   而此前同类新增（`check_box_slot_children` / `check_launch_language_seed` / `check_projection_read_safety` /
>   `check_raw_coroutine_scope` / `check_archive_monotonicity`）**均有** AGENTS.md 条目。
> - **缓冲说明**：`§3` 规则 6 明写「清单与条数随 `hygiene-gate` 段落自动解析，**本文件不写死**」——与逐条枚举存在张力，故定 P3。
> - **涉及文件**：`AGENTS.md`
> - **验收标准**：枚举与 §5 命令清单补入第 13 / 14 道（或明确改为「清单以 `build.yml` 为唯一真相源、本文件不再枚举」并删去枚举）。
>
> ### ISSUE-P3-562：两道新机检在**扫描到 0 个文件**时判 PASS（缺非退化断言）
>
> - **核实时间点 / 方式**：2026-10-09，主审**实跑**对照（同一空树，三脚本）。
> - **实测读数**：
>   ```
>   check_message_not_in_user_text.py --root <空树>      → 检查文件 0 个…命中 0 处   EXIT 0
>   check_plaintext_carrier_to_string.py --root <空树>   → 检查文件 0 个…命中 0 处   EXIT 0
>   check_box_slot_children.py --root <空树>             → box_slot_stacked_sites=0
>                                                         「该读数同样不构成证据」   EXIT 1
>   ```
> - **根因**：两道新脚本的 `main()` 拿到 `(hits, checked)` 后**只打印 `checked`、不做任何下限断言**；
>   而 `check_box_slot_children.py` 对「0 站点」**自身判红**——`AGENTS.md` 已把它列为范式。
> - **影响**：`--root` 传错、模块被移动 / 改名时闸门会**以绿掩盖一次空转**（与 `ISSUE-P3-493`「`tests == 0` 空转显绿」同型）。
>   **如实边界**：姊妹脚本 `check_raw_coroutine_scope.py` 在 0 文件时**同样 EXIT 0**（主审复核确认）⇒ 属**沿用的既有宽松**，非本批独创；CI 恒在仓库根执行，**无现网风险**。
> - **涉及文件**：`tools/doc/check_message_not_in_user_text.py`、`tools/doc/check_plaintext_carrier_to_string.py`
> - **验收标准**：`checked == 0` 即返回退出码 1（或 2），并在 `--selftest` 中补「空树必须判红」的负样本。
>
> ### ISSUE-P3-563：架构拓扑文档未同步 `app → crypto` 边
>
> - **核实时间点 / 方式**：2026-10-09，读构建脚本 + 读架构文档 + grep 源码 import。
> - **根因**：`app/build.gradle.kts:265` 本批新增 `implementation(project(":crypto"))`（`ISSUE-P3-554`；此前靠 `database` 的
>   `api(project(":crypto"))` 透传「蹭」到 **9 个文件 / 14 处** `com.keepasskey.crypto.*` 直接引用）。
>   显式声明本身**正确**，但 `docs/architecture/ARCHITECTURE.md:9` 模块表仍写 `app` 依赖为 `core, database, sync`（**无 crypto**），
>   `:18` 拓扑图亦只有 `app ──> database ──> crypto ──> core` / `└───> sync ────────────────> core`；`AGENTS.md` §3 规则 1 同。
> - **影响**：两份「依赖方向」的权威描述与构建脚本**不一致**（按 `ISSUE-P3-81` 索引纪律，依赖拓扑须随实现同步）。
> - **涉及文件**：`docs/architecture/ARCHITECTURE.md`、`AGENTS.md`
> - **验收标准**：两处拓扑补上 `app → crypto` 边（或注明「app 直接使用 crypto 的通行密钥 / 熵估算 / KDF 面」），并说明它与 `database → crypto(api)` 的关系。
>
> ### ISSUE-P3-564：`app/build.gradle.kts:260` 注释计数与实际不符
>
> - **核实时间点 / 方式**：2026-10-09，主审实测。
> - **根因**：注释写「app 有 **11 个文件**直接 `import com.keepasskey.crypto.*`」。
>   **实测**：`grep -rl '^import com.keepasskey.crypto' app/src/main | wc -l` = **9**；`grep -rh … | wc -l` = **14**（行数）。
> - **影响**：装饰性文字失真，不影响编译与门禁；但该注释是「为何要显式声明依赖」的**唯一论据**，数字错会削弱说服力。
> - **涉及文件**：`app/build.gradle.kts`
> - **验收标准**：改为「9 个文件 / 14 处 `import`」或删去数字。
>
> ### ISSUE-P3-565：kdbx 头部密钥 sentinel 裸字面量重复两处，未收敛为命名常量
>
> - **核实时间点 / 方式**：2026-10-09，读源码 + grep 确认无别名常量。
> - **根因**：`database/.../file/KdbxFile.kt:166`（读侧）与 `:427`（写侧）均为
>   `LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong())`。`grep FFFFFFFFFFFFFFFF` 在生产侧仅命中这 2 行，
>   未见具名常量（**2026-10-09 真伪核实更正**：原写「仅命中这 2 行」不完整——`database/src/test/.../KdbxCompatibilityAndSecurityTest.kt`
>   的 `:552` / `:591` 另有同一字面量，合计 **4 处**；`KdbxConstants` 内**无** `Header` 子对象，既有 `object` 为 `HeaderFieldId`）。
>   违反 `.codebuddy/rules/engineering-rules.md` §禁止魔法数字第 3 条（同一数值多处出现须收敛到常量定义）。
> - **影响**：同一 magic 值双点维护，改一侧漏另一侧会造成读写不对称（头部完整性语义）。
> - **涉及文件**：`database/src/main/java/com/keepasskey/database/file/KdbxFile.kt`、`core/.../model/KdbxConstants.kt`
> - **验收标准**：在 `KdbxConstants` 增设具名常量（**2026-10-09 真伪核实更正**：该文件**无** `Header` 子对象，
>   形如 `Header.HMAC_KEY_LE64_SENTINEL` 的路径不存在，宜落 `HeaderFieldId` 同级的新 `object` 或直接置于 `KdbxConstants` 顶层），
>   读 / 写两侧共用。
>
> ### ISSUE-P3-566：`SyncFailureNotifier` 注释过度声明 `merge` 顺序保证（登记**重开条件**）
>
> - **核实时间点 / 方式**：2026-10-09，逐行读注释 + 核对 `merge` 语义。
> - **根因**：`SyncFailureNotifier.kt:82-83` 的「调用方另把两条流合并为单个收集器……使『先事件后结论』的发射顺序
>   **构成**执行顺序保证」与 `:182` 的「`merge` 按发射次序交付」同属**过度声明**；
>   **`merge` 只保证单条上游流内部有序，不保证跨上游的相对到达顺序** ⇒ 上述表述不成立。
>   （**2026-10-09 真伪核实更正**：原引 `:176-183` / `:148-151` 有偏差——「构成执行顺序保证」句实为 `:82-83`（`SyncFailureNotificationState` 的 KDoc），
>   `:182` 只是「按发射次序交付」，而 `:148-151` 仅称「现处理严格串行，终态唯一」、**不含**该过度声明。）
>   结果当前**仍正确**，因同一同步周期内「事件 `cause`」与「结论 `classify`」**用的是同一个异常对象**，两种到达序**终态一致**。
> - **影响**：属注释过度声明；**风险在于后续据此注释推断「顺序已保证」**。
> - **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncFailureNotifier.kt`
> - **验收标准**：① 注释改为「处理严格串行，但两上游相对顺序不保证；终态一致依赖『事件 cause 与结论 classify 同源』」；
>   ② **登记重开条件**：任一降级点若出现「事件 `cause` 与结论 `classify` 不再同源」，本条立即升级为真缺陷。
>
> ### ISSUE-P3-567：key file `Hash=` 自校验使用短路 `Arrays.equals`（非 `MessageDigest.isEqual`）
>
> - **核实时间点 / 方式**：2026-10-09，读源码。
> - **根因**：`database/.../file/KdbxKeyFile.kt:135`：`expected.size > actual.size || !Arrays.equals(expected, actual.copyOf(expected.size))`
>   ——**短路**比较，非常时；与 `docs/security/SECURITY_RECHECK_2026-09.md` 关于「全部密钥材料比较使用 `MessageDigest.isEqual`」的**笼统表述**存在边际偏差。
> - **可利用性评估**：`expected` 来自密钥文件自身的 `Hash=` 属性、`actual = SHA256(key)`，**同源**；攻击者须**已持有该密钥文件**方可触发，比较目标即文件自身内容 ⇒ **时序侧信道无实际收益**。属一致性口径问题，非漏洞。
> - **涉及文件**：`database/src/main/java/com/keepasskey/database/file/KdbxKeyFile.kt`
> - **验收标准**：改用 `MessageDigest.isEqual`（先对齐长度），与同族路径口径统一；或在复核报告中把该路径显式列为例外。
>
> ### ISSUE-P3-568：`SettingsKdfBenchmarkController` 的 `catch (t: Throwable)` 仍吞 `CancellationException`
>
> - **核实时间点 / 方式**：2026-10-09，读源码 + 对照 `ISSUE-P3-555` 的收敛范围。
> - **根因**：`app/.../ui/screens/settings/SettingsKdfBenchmarkController.kt:49` 的 `catch (t: Throwable)` 会把 `CancellationException` 一并归一为 `errorMessage`（**2026-10-09 真伪核实更正**：`catch` 实为 `:49`，原写 `:47` 是 `recommendedParallelism` 赋值行）。
>   `ISSUE-P3-555`（本批闭环）的收敛范围为「`runCatchingCancellable` provider 11 处 + `markResolvedAndUpload` / 上传重试」，**未枚举本处** ⇒ 同型残留。
> - **影响**：基准测试被取消时被记为「失败」并落 UI 状态，与 `runCatchingCancellable` 的立规意图（取消须沿链重抛）相悖。低危。
> - **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsKdfBenchmarkController.kt`
> - **验收标准**：取消重抛（`if (t is CancellationException) throw t`）；并建议一次性普查 `catch (t: Throwable)` 的取消语义，收口同型未枚举点。
