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

## P1 高危与核心功能问题（2 项）

### ISSUE-P1-238：冷启动关键路径仍逼近系统凭据创建应答预算（点保存间歇性无反应）

- **核实时间点**：2026-09-21 于真机 Redmi 4X（santoni，`lineage_Mi8937_4_19`，Android 17 / API 37）实测。
- **核实方式**：以测试 APK 内**独立包名**的真实客户端（`CredentialSaveClientActivity`，语义等价第三方应用）
  调用 `CredentialManager.createCredential`，经 `adb logcat` 逐轮比对
  `ActivityManager: Start proc …KeePasskeyCredentialProviderService` 与
  `KeePasskeyCredProvider: onBeginCreateCredentialRequest` 两条时间戳。
  **通行密钥方向同口径复测（§241）**：冷启动轮 `Provider session created 06:51:35.407` →
  回调 `06:51:37.864`（2.46 s）→ `CANCELED 06:51:38.409`（2.99 s = 系统预算）→
  `TYPE_NO_CREATE_OPTIONS`，与密码方向**同源**。
  **10 轮冷启动统计（§242）**：中位 **2.359 s**，超时 0/10。
  **2026-09-21 追加实测（真机浏览器复现 passkeys.io 失败后）**：系统预算复测为
  `Provider session created` → `Remote provider response timed out` = **3.001 ~ 3.002 s**（6 轮全同）；
  整改前 8 轮冷启动 **全部** `Remote provider response timed out`（回调 2.43 ~ 3.40 s）。
  应用侧应答路径经分段计时定位（见「背景与根因」）并整改后复测：应用侧
  （`onBeginCreateCredentialRequest` 进入 → `已向系统返回 Passkey CreateEntry`）
  由 **1.15 s 降至 0.25 ~ 0.32 s**。
- **背景与根因**（2026-09-21 重新归因，三分量）：
  1. **应用侧应答路径（已整改，本批）**：`PublicSuffixList` 首次装载要全量解析 PSL
     （16,475 行 / 1.0 万条规则），实测 **1.00 s**，且它是 `rp.id` 归属校验的**同步依赖**。
     分段计时显示瓶颈是自写的逐字符/逐字节 Kotlin 循环（**解释执行**），
     而 `String` / `HashSet` 等框架方法运行于 boot image 的已编译代码。已改为
     **按末标签懒加载**（查询只命中目标 TLD 的规则桶，`indexOf` 单趟文本定位），
     并把 IDN 规则的 punycode 归一移出热路径、WorkManager 改为
     `Configuration.Provider` 按需初始化（原 androidx.startup initializer 在
     ContentProvider 阶段实测占 0.41 s）、PSL 资源改为不压缩存放（读取 0.13 s → 0.03 s）。
     等价性由 `PublicSuffixListTest`（对 PSL 全量规则与独立引用实现穷举比对）与
     `PublicSuffixListResourceTest`（源文件三条前提机检）锁定。
  2. **平台侧进程启动（未整改，非应用代码所能及）**：`Start proc` → 首条应用侧日志
     实测 **2.35 ~ 2.79 s**，主体是 ART 打开并**运行期校验** debug 构建的
     `classes.dex`（39.77 MB，debug 构建不混淆 ⇒ 无 AOT，`dumpsys package dexopt`
     显示 `status=run-from-apk`）。对照实验：对该包执行
     `adb shell cmd package compile -m verify -f com.keepasskey`（预校验出 odex）后，
     6 轮冷启动整链路 `Provider session created` → `SAVE_ENTRIES_RECEIVED` 全部
     **1.32 ~ 1.58 s、零超时** ⇒ 剩余差距属 **debug 构建类型 × 低端机**的平台属性。
     **本机排障手段**：重装 APK 后执行上述 `cmd package compile`；或以 release 构建实测
     （R8 + baseline profile，冷启动应显著更低）。
  3. `MainApplication.onCreate` 其余同步工作与 Hilt 图初始化仍在关键路径上
     （§240 结论不变）。
- **验收标准**：AC① 真机 `force-stop` 后连续 ≥ 10 轮冷启动触发保存请求，
  `Start proc` → `onBeginCreateCredentialRequest` **全部** ≤ 1.5 s（留 2× 余量）；
  AC② 同轮次内 `logcat` **零** `Remote provider response timed out`；
  AC③ 改善手段**不得**削减任何冷启动安全对账语义（`fileBinaryStore.clear()` 与
  `clipboardSecurityManager.reconcileOnColdStart()` 必须仍在 `onCreate` 中同步执行，
  由 `ColdStartAttachmentPurgeWiringTest` 锁定）；
  AC④ PSL 装载策略改动必须与全量引用实现**逐规则等价**（`PublicSuffixListTest`），
  且 PSL 资源的三条前提（纯 LF / 已小写 / 无首尾空白）由 `PublicSuffixListResourceTest` 机检锁定；
  AC⑤ 若在**无 odex** 的 debug 构建上仍无法稳定满足 AC①②，须在本条目如实登记
  「平台侧占比 + 本机排障手段」，**不得**以放宽系统预算或削弱门控为手段强行达标。
- **当前状态**：应用侧应答路径已整改（AC④ 落地）；在预校验 odex 存在时 AC①② 达标
  （6/6，1.32 ~ 1.58 s）；无 odex 的 debug 构建上仍会间歇性超时（8 轮 3 次超时），
  根因见「背景与根因」第 2 条 ⇒ **保持开放**。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/MainApplication.kt`、
  `app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt`、
  `app/src/main/java/com/keepasskey/app/passkey/CredentialCreateEntries.kt`、
  `app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`；
  §240 批次正文（首轮定位）、§241（通行密钥方向复测）、§242（10 轮统计）。

### ISSUE-P1-241：「移除密码库关联」的确认文案承诺「不会删除物理文件」，但应用私有库的文件**会被真的删除**

- **核实时间点**：2026-09-21 于真机 Redmi 4X（santoni，Android 17 / API 37）实测
  （§243 真机验证收尾、清理该批新建的测试库时触发）。
- **核实方式**：
  ① 界面路径：解锁页「切换密码库」→ 密码库管理 → 对 `e2e-issue240.kdbx` 卡片点删除图标
  （`contentDescription = @string/btn_delete`）；
  ② 弹窗文案经 Android CLI `android layout` 读出：`db_picker_delete_confirm_title`
  「移除密码库关联？」+ `db_picker_delete_confirm_desc`
  「这只会从本机的密码库切换列表中移除该条目，**不会删除物理文件**。」
  （`values-en` 同义："The original physical file will not be deleted."）；
  ③ 点「删除」确认后 `adb shell run-as com.keepasskey ls -la files/` 实测：
  **该库文件已不存在**（目录内仅余 `passwords.kdbx` / `passwords.kdbx.bak` / `profileInstalled`），
  而删除前同一命令可清晰看到 `-rw------- 1017 … e2e-issue240.kdbx`；
  ④ 代码核对：`VaultLifecycleCoordinator.removeDatabase` 的 KDoc 即「摘除注册表条目、**删除沙盒内文件**、
  必要时关闭会话并清空活动库 ID」，实现为 `File(context.filesDir, id).delete()`（无条件尝试，存在即删）。
- **背景与根因**：该文案描述的是**外部库**（`content://` / 绝对路径登记）的真实行为——其物理文件在应用外，
  `File(filesDir, id)` 不存在故不会删除；而**应用私有目录**（建库向导的默认且推荐项）下的库，
  其 `id` 恰是 `filesDir` 内的文件名 ⇒ **真的被删除且不可恢复**（无回收站、无二次确认差异、
  连同 `.bak` 也不在保护之列）。用户按文案理解为「只是从列表移除」，实际把密码库永久删掉。
  这与 §243 刚闭环的 `ISSUE-P2-240`（文案与真实语义无关）同源，但**危害面更大**（不可逆数据丢失），
  故按 P1 登记。
- **验收标准**：AC① 确认弹窗文案必须与真实行为一致，并**按存储类型区分**：应用私有库须明示
  「将**永久删除**应用私有目录中的该文件，**无法恢复**」；外部库方可保留现「仅移除关联」表述；
  AC② 删除私有库须是**明确的破坏性动作**（红色危险按钮 + 指明被删文件名），不得与「仅移除关联」
  共用同一套确认措辞；AC③ 若产品选择「只移除关联、不删文件」，则必须**另行**提供显式的「删除文件」
  入口，且默认动作不得删文件（二选一由产品裁决并登记 `产品裁决登记.md`）；
  AC④ 「存储类型 → 文案/动作」映射落纯函数并由 JVM 单测按类型穷举锁定，中英文同步；
  AC⑤ 真机回归：对应用私有库执行删除后，**界面声明与文件系统结果一致**（`run-as` 观测取证）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerScreen.kt`（确认弹窗）、
  `app/src/main/res/values/strings.xml` / `values-en/strings.xml`（`db_picker_delete_confirm_title/_desc`）、
  `app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt`（`removeDatabase`）；
  触发经过与现场证据见 §243 批次正文 §7。

---

> **本区历史上一次归零**：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)。

---

## P2 中危缺陷与协议/测试缺口（1 项）

### ISSUE-P2-239：凭据提供者通道「系统未登记本应用」的失效完全静默且用户无法自救

- **核实时间点**：2026-09-21 于真机 Redmi 4X（Android 17 / API 37）实测（与 `ISSUE-P1-238` 同一次排查）。
- **核实方式**：`adb shell settings get secure credential_service`（实测为**空**）、
  `credential_service_primary`（实测指向**不存在的包** `com.keepasskey.app`，而实际包名是 `com.keepasskey`）；
  并以四条归因实验（仅改 `credential_service` / 仅改 `primary` / 全新安装仅写 `primary` / `force-stop` 后冷启动）
  分别观察系统侧 `CredentialManager: starting executeCreateCredential` 与 provider 侧回调是否出现。
- **背景与根因**：`credential_service` 为空时，系统**不会**向本应用发起创建请求（框架层直接
  `CreateCredentialException.TYPE_NO_CREATE_OPTIONS`）。而 ① **全新安装后系统不自动登记**本应用；
  ② 本机 ROM 的「首选服务」选择器只写 `credential_service_primary`（且写入命名空间前缀包名，
  为**悬空组件**），**不写** `credential_service`。结果是保存能力对用户**完全不可见地失效**：
  无报错、无提示，只有「点保存没反应」。自动填充通道早有 `AutofillHealthProbe` 与设置页健康卡片，
  CM 通道**既无自检也无引导**。
- **验收标准**：AC① 新增凭据提供者通道健康检查，在「系统未登记本应用」时给出用户可见状态与
  修复指引（跳转系统设置；`android.settings.CREDENTIAL_PROVIDER` 在本机不可解析时**如实降级说明**，
  不得伪造「已开启」）；AC② 检查结果只反映真实系统状态，**读取失败一律呈现「未知」而非「正常」**
  （沿用本项目既有口径）；AC③ 判定落在纯函数上并被 JVM 单测穷举，平台查询单点化便于注入；
  AC④ 设置页文案不得声称「已从系统移除」（组件注册由 Manifest 决定，应用内无法动态摘除）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/**`、设置页安全分区；
  先例 `AutofillHealthProbe`。已执行的处置与真机验证见 §240 批次正文。

---

> **本区近期变动**：§243 闭环 `ISSUE-P2-240`（设置页「跳过 DAL 校验」开关的文案按其**真实语义**更正为
> 「跳过通行密钥站点归属校验」，并与 `DigitalAssetLinksVerifier` KDoc / 字段注释 / 告警日志逐字同锚；
> AC② 裁决「**不新增**独立的『跳过浏览器兼容层』偏好项」落
> [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) `PD-16`）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/243-设置页DAL降级开关文案更正批次.md`](resolved/batches/243-设置页DAL降级开关文案更正批次.md)。

> **本区历史上一次归零**：§236 闭环 `ISSUE-P2-231`（Java 依赖面完整性锁定缺失）/
> `ISSUE-P2-232`（完整性风险升级无主动熔断接线）——前者落**重开决策**判「仍不引入」并交付
> 可机检的替代缓解口径（`PD-14`），后者经前置裁决 `PD-13` 判「维持现状」、残余风险登记限界 §26；
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/) 的 §236 批次）。
> 本区上一次归零为 §230 ~ §233 四批闭环 `ISSUE-P2-226` / `P2-227` / `P2-228` / `P2-229`
> ——即用户 2026-09-20 真机报告的四项问题（自身界面仍出现填充建议 / 生物识别被禁用时归因笼统 /
> 自动填充卡三个假开关且文案谎称需要无障碍 / 新建库无位置选择入口）。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（本区归零：§238 闭环 `ISSUE-P3-235`（`RC-02` 敏感缓冲所有权收口）——
> AC① 设计交付 [`architecture/敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md)；
> AC② 逐处迁移：`G1`（§235）/ `G2`（§238）闭环、`G3`（池内擦除）维持已登记限界 §1.6、
> `G4`（命名统一）降为「按需」、`G5` / Step 1 已核实；`test` 343 类 / 2402 例全绿。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/238-待决冲突解析树身份判定擦除批次.md`](resolved/batches/238-待决冲突解析树身份判定擦除批次.md)）。
>
> **本区近期变动**：§236 闭环 `ISSUE-P3-233`（复核报告 `P3-120` 状态陈旧——更正报告
> §10.1 / §3.3.2 / §2.3 / §2.4 / §6.7 / §9.2 并增补 §15.3 方法学第 12 条）与 `ISSUE-P3-234`
> （DAL 出口 IP 字面量面定案，登记 [`已知工程限界.md`](architecture/已知工程限界.md) §25）；
> §237 闭环 `ISSUE-P3-230`（已有 SAF 库授权失败不再静默——提示 + 列表状态 + 重授入口，
> 限界 §24 的残余段同步收口）。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，§224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。
