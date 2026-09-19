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

> **暂无开放项**（历史 P1 条目的实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)）。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（历史 P2 条目的实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)）。

---

## P3 低危问题、特性接线与体验优化（3 项）

### ISSUE-P3-197 autofill 链路设备用例在模拟器上的残余环境面（根因已定位，linePid 缺陷已修）

- **核实时间点与方式**：2026-09-19，`ISSUE-P2-192` 结案批次双设备实跑发现（首批登记时成因未定位），
  同日 §204 批次**二分定位**：`git stash` 回滚工作区改动后失败依旧 ⇒ 与 payload 组装改动无关；
  真机 device logcat 中**留痕行存在**（`运行完整性扫描完成: level=ELEVATED`，pid 同测试进程）却未匹配 ⇒
  定位到用例自身的解析缺陷，**已同批修复**。
- **根因（已修）**：`AutofillAuthChainDeviceTest.linePid()` 用 `split(" ")` 解析 threadtime 行——
  pid/tid 为**右对齐 5 位宽**，4 位 pid 前有 2 个空格 ⇒ 连续空格解析成空元素（`getOrNull(2) = ""`），
  「本进程」过滤恒假；只有 5 位 pid 才凑巧正确。实测：真机 pid 5 位时两轮绿、pid 回落 4 位后恒败，
  模拟器 pid 恒 4 位故从未绿过。修复：按连续空白切分（`split(Regex(" +"))`）；另将完整性等待窗口
  `INTEGRITY_WAIT_MS` 15s → 35s（`logcat -c` 已清掉进程启动那拍留痕，等待依赖 30s 周期重扫的
  下一拍，窗口须覆盖一个完整周期——15s < 30s 属采窗缺陷）。修复后**真机恢复全绿**（64 例）。
- **残余面（本条继续跟进）**：模拟器上该用例推进到完整性断言**之后**的新失败点——「系统填充 UI
  未出现认证引导数据集」——属强系统 UI 依赖的环境敏感面（用例设计验证环境为真机，见其 KDoc
  「实测覆盖（真机，非模拟器）」）。处置：CI `device-gate` 以 `notClass` 显式排除该类（workflow 注明
  理由），真机承担其验证；模拟器上能否驱动（解锁屏 / 填充服务设置 / 渲染时序的自动化准备）留待
  后续评估，**不得**为让模拟器变绿而放宽链路断言。
- **验收标准**：① 真机全量 connected 保持全绿（已达成，回归口径继续）；② 模拟器驱动面若要补齐，
  须以「环境准备自动化 + 链路断言不放宽」为前提另行立项；③ CI `device-gate` 首跑确认排除后全绿。
- **边界**：`linePid` / `INTEGRITY_WAIT_MS` 两处修复不触及任何生产代码；限界表 §4.1 的
  「模拟器专有差异」段已同步更新。

### ISSUE-P3-188 巨型类与魔法数字专项整改（工程规则 §单一职责 / §禁止魔法数字 违例收敛）

- **核实时间点与方式**：2026-09-18，主控以 `wc -l` 全量扫描五模块（`app`/`core`/`crypto`/`database`/`sync`）
  `src/main` 下全部 `.kt`；超长函数以**括号配平逐点实测**复核（启发式初筛 + 人工核实，已剔除把类体 / KDoc 误计为函数的假阳性）；
  魔法数字面以 `grep -E '0x[0-9A-Fa-f]{2,}'` 排除常量声明行后按文件计数（**§167 复核：该初筛法假阳性偏多，
  只可用于圈定候选，裁定须逐条看上下文**——见下方第 4 目）。
- **违例清单（核实时刻快照，行号以实现为准）**：
  1. **超 500 行文件（核实时刻 8 个，第一档）** —— **§176 重测**（`python tools/doc/count_line_tiers.py`）：
     全仓 `src/main` 超 500 行**仅余 2 个**（`SettingsViewModel` 543 / `DatabaseSession` 535，均已按限界
     **§18** 登记为门面理由）；其余 6 个已降到 500 以下，其**逐文件 `wc -l` 只在下方「当前进度 · 第一档」
     维护一份**（验收线 ≤400），此处不再重复快照数字；
  2. **400~500 行文件（第二档）**：清单、当前计数与逐批消减**只在下方「剩余清单第 2 项」维护一份**
     （此处不再重复快照数字，避免两处数法各自漂移）；
  3. **超 50 行函数（第一档 ≥100 行）**：核实时刻的 20 处原文快照**已分流**至
     [`resolved/batches/181-长函数度量工具化与快照分流批次.md`](resolved/batches/181-长函数度量工具化与快照分流批次.md) §4
     （含六处在全仓归档内零命中的函数名，检索按 `§号 + 函数名` 命中该文件）；
     当前清单与计数**一律以 `python tools/doc/long_functions.py` 为准**，此处不再抄录（§175 的一次性脚本
     因判据缺陷漏报过 10 条，见 181 §1）。
  4. **内联十六进制字面量**（**§167 复核后重写本目**：原清单以 `grep '0x[0-9A-Fa-f]{2,}'` 计数，
     **假阳性占多数**——同一行里的 `const val` / `val NAME = byteArrayOf(...)` 常量与数据表定义也被算进去。
     复核口径：排除**位掩码**（`and 0xFF` / `and 0x0F`）、**常量与数据表定义**、UI 色板，再逐条人工裁定）：
     - **真实违例只有一族**：`@Preview(uiMode = 0x20)`——**151 处 / 88 份文件**（`src/main` 75 处 / 71 份，
       其余 76 处在 `app/src/screenshotTest/` 的**生成物**内，该目录由 `.gitignore` 排除）。
       **§167 已全部改用平台命名常量** `android.content.res.Configuration.UI_MODE_NIGHT_YES`
       （注解参数须编译期常量，该 Java 字段正是；全仓 `grep 'uiMode = 0x20'` 归零）；
     - **原判为违例、经复核属合法**：`CborEncoder` 的 10 处全是 `and 0xFF` 字节截断掩码（与已登记的
       `LittleEndianUtil` 同族）、`TotpKeyUriParser` 的 16 处全是 `val` 常量定义、
       `PasskeyKeyText` 的 31 处全是 OID/DER 字节串与 ASCII 常量定义、
       `UnlockPasskeyManager` / `SyncEndpointGuard` / `OtpEngine` 各仅余 1~2 处掩码；
     - **§168 已把「仍待裁定」三小项全部裁定完毕**：① `PasskeyKeyText` 的同值异名常量并为一组
       `ASCII_LF` / `ASCII_CR` / `ASCII_SPACE` / `ASCII_TAB`；② 三处 `0x20` 阈值改为包内
       `internal const val ASCII_SPACE`（`<` 表控制字符、`<=` 表可修剪空白，分工写进常量 KDoc）；
       ③ `PasskeyCryptoEngine` 的 6 处十六进制经逐处判定**全为 `FLAG_UP`~`FLAG_ED` 定义本身**（合法），
       其真正的内联 uint16 上限改为 `CREDENTIAL_ID_MAX_BYTES`。**全部只命名、未改任何取值**。
       剩余 169 处内联十六进制的三类定性（UI 色板 / 格式签名字节 / **待逐处判定的 7 份文件**）
       与扫描判据见 [`resolved/batches/168-魔法数字第4目三小项裁定批次.md`](resolved/batches/168-魔法数字第4目三小项裁定批次.md) §2~§3；
       第 3 类集中在 `crypto` / `database` 的格式编解码面，须与 `.kdbx` 对拍同批做，**属独立一段**（§38 证据纪律）——该段已立为 `ISSUE-P3-196` 并**由 §201 结案**。
     - **合法形态登记（不整改）**：UI 色板（`ThemeMode` / `Color.kt`）、位运算掩码
       （`LittleEndianUtil` / `CborEncoder` / 各处 `and 0x0F` 十六进制编码）、数据表
       （`DicewareWordList` / OID-DER 字节串 / `CborConstants`）、BOM 探测字节
       （`ImportTextDecoder` / `BitwardenJsonImporter`）。
- **整改纪律**：
  1. 拆分**不得改变公开 API 与行为**（对齐 `DatabaseSession` 批次 D 先例：门面收敛、职责下沉同包协作类）；
  2. 涉及 `crypto` / `database` 解析面的改动须过既有对拍回归（`.kdbx` 语料 / Parity 套件）；
  3. 常量收敛**只挪定义不改值**，改值即属协议变更，须另行立项；
  4. 每档闭环后 `.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿方准入库；
  5. **不得为凑行数把注释移出文件充当「瘦身」**——以职责拆分为准。
- **验收标准**：第一档 8 文件全部 ≤400 行（或如实登记限界理由，实测口径见下方进度）；
  ≥100 行函数全部拆分至 ≤50 行（达标情况一律以 `tools/doc/long_functions.py` 实测，见下方进度；
  非 Compose 面**已逐条处置完毕**：一处削到 41、一处经**限界 §20** 裁定接受 ⇒ 本目**不以「全表 ≤50」达标**，
  以「无未登记的超限项」达标）；
  第 4 目清单中的协议 / 格式语义字面量收敛为命名常量（**§167~§168 已裁定完毕**：成片真实违例
  `@Preview(uiMode = 0x20)` 归零、三小项全部落地，**全部只命名未改值**；余下的 `crypto` / `database`
  格式面 7 份 26 处**已由 §201 逐处判定完毕**（登记 21 / 命名 5 值，只挪定义未改值）⇒ **本目闭环**，
  见 [`resolved/batches/201-格式面26处字面量逐处判定批次.md`](resolved/batches/201-格式面26处字面量逐处判定批次.md)）；
  剩余第二档渐进消化，未消化部分在批次文档留清单。
- **当前进度（只留结论；逐批改动与验证见 `docs/resolved/batches/155`~`201`，批次细节以彼处为单一真相源，不再在本文件复述）**：
  - **第一档 8 文件**（`wc -l` 实测）：**达标 6**（`SyncCache` 382、`UnlockViewModel` 368、`DatabaseSettingsScreen` 339、
    `EntryEditFormSections` 351、`EntryDetailScreen` 349、`EntryDetailViewModel` 397）；按理由登记 2
    （`SettingsViewModel` 543 / `DatabaseSession` 535，经复核为**纯门面**，理由 / 边界 / 解除条件见限界 **§18**）；
    **仍超 0** ⇒ **第一档维度闭环**。留痕：`EntryDetailViewModel` 是真降到 400 以下（**不满足**限界 §18
    「成员全部为一行委托」的成立前提）；其剩下 6 个含实现体成员（跨条目明文处置顺序、偏好快照刷新、
    fail-closed 导出判定、会话登记与 `init` / `onCleared`）属页面状态层应持有者，**不得**再为「门面感」外搬（§170 §3）。
  - **第 3 目（≥100 行函数）**：**非 Compose 面已全部消除**（`mergeConflictedEntry` 削至 **41**；
    `processFillRequest` 55 经**限界 §20** 裁定接受），表内余项**全部为 Compose 面** ⇒ 见剩余清单第 1 项
    （度量口径与假阳性成因已固化进 `tools/doc/long_functions.py` 文档串，**不再靠一次性脚本**）。
  - **第 4 目（字面量）**：**闭环**——协议 / 格式语义字面量收敛、`@Preview(uiMode = 0x20)` 成片违例归零（§167~§168）、
    格式面 26 处逐处判定完毕（§201），**全部只命名、未改任何取值**（明细见违例清单第 4 目与 168 / 201 批次文档）。
- **剩余清单（本条尚未闭环的部分，逐条自包含）**：
  > **现为 1~3 项**：原第 4 项（断言响应材料的宿主直调用例）已由 §204 结案移出（宿主 JSON 写入口径
  > [`WebAuthnJsonWriter`] 落地 + 注册 / 断言两处 payload 改走它 + 四条宿主断言 + 设备侧
  > `org.json` 逐字节对拍，见 [`resolved/batches/204-断言响应材料宿主写入口径批次.md`](resolved/batches/204-断言响应材料宿主写入口径批次.md)）；
  > 更早的第 4~6 项也已分别结案（§171/§190、§195、§189/§199）——历史编号映射与逐批去向见各批次文档，不再在此复述。
  > 处置结论另见限界 **§20** 与
  > [`resolved/batches/182-合并层冲突对下沉与早退守卫限界批次.md`](resolved/batches/182-合并层冲突对下沉与早退守卫限界批次.md)。
  1. **Compose 面的长函数**（第 3 目的剩余部分）：**§211 闭环**——§200 现跑 18 个全部处置完毕
     （§205~§211 七批共拆 15 处，逐批留痕见
     [`resolved/batches/205-Compose长函数拆分两处批次.md`](resolved/batches/205-Compose长函数拆分两处批次.md) ~
     [`resolved/batches/211-Compose长函数收官批次.md`](resolved/batches/211-Compose长函数收官批次.md)），
     现跑表内余项**全部为已登记豁免**（2 个 PD-11 NavGraph + 限界 §179 `CreateVaultWizardDialog`），
     以「无未登记的超限项」达标；计数今后一律现跑 `python tools/doc/long_functions.py` 核对，
     「逻辑行 ≥5 即重新计入」的 PD-11 重开条件继续有效。
     逐个名单与已出表 9 个的逐批留痕见
     [`resolved/batches/181-长函数度量工具化与快照分流批次.md`](resolved/batches/181-长函数度量工具化与快照分流批次.md) §3
     及各批次文档（§178~§198），此处不再复述；
     **计数今后一律现跑 `python tools/doc/long_functions.py`**（三条判据与其踩坑史写在工具文档串里，
     条目内不再抄录，以免重演 §175 一次性脚本漏报 10 条的失真）。
     **表内豁免项速览**（勿重复尝试拆分）：`CreateVaultWizardDialog` 158（**受限界 §179 下限**——
     主密码 `CharArray` 擦除链的「单一现场」决定）；两个 NavGraph（PD-11，逻辑行 <5，重开条件见裁决）。
     **口径问题已裁决（§184，PD-11）**：两个纯接线装配表 `keepasskeySettingsNavGraph` / `keepasskeyNavGraph`
     （逻辑行 <5）**豁免本目**；裁决全文、分类计数依据与「逻辑行 ≥5 即重新计入」的重开条件见
     [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) PD-11。
     **搬移约束（历史批次实证，勿重蹈）**：① 同包段落组件窄参数、不读 `UiState`、不自持状态
     （§156 / §159 先例）；② 敏感对话框的 `SecureDialogWindowEffect()` 两处**必须留在被
     `SecureDialogFlagPolicyTest` 点名的文件里**、只搬非保密段落（§193）；③ 段落组件「另起新文件 vs
     追加进原文件」按原文件距 400 的余量**逐案判**（§192 与 §196 两案结论相反正是此理）；
     ④ 逻辑段优先下沉为**可 JVM 单测的纯函数**并补用例（§185 先例）；逐字性以
     `python tools/doc/check_verbatim_move.py <原文件> <本体> <段落文件>` 复核；
     安全「清单锁」型守卫与「一次性核对 ≠ 防护」的纪律见下方第 3 项。
     **刻意不搬的三处**（各有锚在现场的守卫或边界，勿重复尝试）：
     ① `DatabaseSettingsScreen` 导出侧四个 `CreateDocument` launcher 与三处 `pending…Uri`——
     `ExportTicketSinkGuardTest` 的定位串锚在「SAF 回调把目标落到待确认态」这一现场，再搬走会让
     「弹了确认框却导出别的对象」失去可断言落点；② `AutofillConfirmActivity.completeAuthResult`——
     唯一行为级证据是设备侧 `AutofillAuthChainDeviceTest`；③ `SyncConflictController.autoMergeAndUpload`——
     承载 `localDbOwned` 擦除判据的一处调用点（§166 §2）。
  2. **第二档（400~500 行文件）渐进消化**：**§196 复跑仍为 28 个**（§189 起连续七批未变；口径：五模块 `src/main` 全部 `.kt`
     逐文件计数、**400 与 500 两端皆含**，脚本 `python tools/doc/count_line_tiers.py` 一次给出两档；
     取数须在**最终写盘后**——§165 §7 校正过一批「测量点早于写盘」造成的 `+1` 漂移）。
     > **2026-09-19 用户裁定（容差口径）**：行数「超过一行两行就不用管了，加减 15% 以内都不用管」——
     > 即 ≤460 行（400+15%）的文件**不**为出表而拆；现档内仅 `RealVaultRepository` 489 /
     > `VaultRepository` 478 超容差，且两者本属「刻意排后」（限界 §1.6 可达性穷举牵动）。
     > 后续批次对第二档的任何拆分以**职责下沉**为准，**不得**为凑行数删注释 / 空行。
     当前头部：`RealVaultRepository` 489、`VaultRepository` 478、`RuntimeIntegrityDetector` 470、
     `KdbxHeader` 461、`KdbxXmlParser` 454、`VaultListScreen` 447、`UnlockScreen` 446。
     **已消化 14 个**（一律「只搬不改逻辑」；逐文件明细与逐批留痕见 `resolved/batches/159`~`186`，
     此处不再复述）。**减而未出档（勿误记为消减）**：§189 让 `DebugSettingsScreen` / `SecuritySettingsScreen` /
     `CloudSyncScreen` 各再省 25~26 行，**三页仍全部留在第二档** ⇒ 28 不变；`CloudSyncScreen` 距出表 1 行。
     **刻意排后（是取舍不是遗漏）**：① `RealVaultRepository` / `VaultRepository`——整树读写与擦除边界，
     牵动限界 §1.6 的可达性穷举；② `KdbxHeader` / `KdbxXmlParser`——`.kdbx` 格式面，须过官方实现
     端到端对拍（§38 证据纪律），属独立一段；③ `DicewareWordList` 408——词表数据文件，
     拆散反害查表语义。
  3. **接线守卫与结构耦合的长期代价（搬家必读）**：结构性搬家会改写静态源码比对断言的**定位范围**——
     首例为三测试类同时失配（`AutofillAuthResultWiringTest` / `AlgoHotPathGuardsTest` /
     `OneTapInteractionWiringTest`，见 §160 留痕），§166 再把 `AlgoHotPathGuardsTest` 的两条判据改为并集，
     §179 把 `CreateVaultPresetTest` 的向导判据扩为「向导本体 + 段落文件」并集（**负向那条一并扩扫**）。
     规则：凡搬家**必须**同批把守卫扫描改为「门面 + 分支文件」**并集**，做到「**只放宽定位串、不降低断言
     强度**」（计数类判据两侧计数保持不变）；**不得**以删除或放宽守卫凑绿（`AGENTS.md` §3 测试资产纪律）。
     并集里少一份文件不会静默通过——`readSource` 对不存在的路径先断言失败。
     **同类代价的第二形态（§189 实测）**：「多处重复、可收敛为一份」也是一个**前提**，目测登记同样会错
     （§188 即因此改掉过一页顶栏配色 ⇒ `ISSUE-P3-195`）；收敛前须跑
     `python tools/doc/scaffold_block_fingerprint.py <rev> <目录> <页名>…` 让差异**自己分桶**。
     **第三形态：安全「清单锁」型守卫（§194 首例）**。`PopupSecureFlagInventoryTest` 断言
     「含 Popup 调用点的**文件集合** == 已核实清单」（`assertEquals`）+「菜单项总数 == 8」。
     把 `DropdownMenu` **原样**搬到段落文件即令集合报红——这是它应有的反应，也是该守卫非摆设的证据。
     处置**只能重新盘点**：核内容仍无凭据类插值 ⇒ 换清单里的**路径**并同步 `SecureDialog` KDoc 的
     「未能覆盖」一节；**不得**删清单条目、不得改记号表、不得放宽 `8` / `≥4` 计数。
     旁证判据：以同样记号自扫一遍 `app/src/main/java`，确认「实际集合 == 清单集合」。
     **第四形态（§199）：一次性核对 ≠ 防护**。`scaffold_block_fingerprint.py` 查出了 §188 的差异，
     但它是**跑一次就完**的核对，挡不住日后参数默认值被改、或例外页的具名实参被当冗余删掉——
     凡是「靠某次工具核对得出的等价性/差异」维持的收敛，**必须**再落一份静态接线守卫
     （先例：`SettingsSubscreenScaffoldWiringTest` 四条，含一条防清单自我空扫的反向哨兵）。
     守卫的区分力可用 `git show <回归态提交>:<文件>` 代入其谓词复核，**不必**注入临时坏码。
- **依据**：`.codebuddy/rules/engineering-rules.md` §高内聚低耦合 / §禁止魔法数字；本条目为 2026-09-18 用户命题「消除巨型类和魔法数字」。

### ISSUE-P3-198 ChaCha20 / AES 直扣（DirectByteBuffer）生产化承接（`ISSUE-P3-187` 评估的定案实施）

- **核实时间点与方式**：2026-09-19，`ISSUE-P3-187` 评估定案（见
  [`records/JNI零拷贝评估_2026-09-19.md`](records/JNI零拷贝评估_2026-09-19.md)）——
  探针 `probeJni零拷贝DirectByteBuffer_对比_连续10轮` 在 Pixel_10 上实测
  **现状 24.05 ms（中位 10 轮，与 2026-09-17 基线 24.7 ms 复现一致）vs 直扣 3.7 ms（≈6.5×）**，
  拷贝 + 分配开销（占单次 JNI 44%）被消除，AC② 达标；正确性前置为两条路径输出逐字节一致。
  原生侧新增导出 `NativeChaCha20.applyKeystreamDirect`（direct `ByteBuffer` 就地变换，
  **非生产路径**探针），4 ABI 已构建、`cargo test` 73 例全绿、符号契约 CI 清单已更新（10→11）。
- **本条承接内容（生产化）**：
  1. `ChaCha20CipherEngine` 与 `CbcStreams` 的调用方 `ByteBuffer` 化（或桥内双形态），
     生产路径切换到直扣；**擦除责任上移**——direct 缓冲的会话级复用与用毕就地归零
     由调用方承担（评估文档 §3 契约）；
  2. AES 族同构探针与生产化（`NativeAes` 直扣，方法学同 ChaCha20）；
  3. Redmi 4X 真机 10 轮对比补测（评估期间真机 USB 断连缺测，探针已入库随批可跑），
     以真机数据复核 AC② 达标结论；
  4. 既有语义回归全绿：`AesNativeParityTest` / `ChaCha20NativeEngineTest` /
     `StreamKeyOwnershipContractTest` / `CipherFallbackParityTest` 等 + 四层设备侧套件。
- **边界**：`CipherSpi` 有状态 Provider 路线**不在本条**（评估定案为长线演进方向，
  与直扣正交；如未来立项须按评估文档 §6 四维对照先行）。
- **依据**：`records/JNI零拷贝评估_2026-09-19.md`；`已知工程限界.md` §15 / §17（已按结论更新）。

