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

> **暂无开放项**（本区归零：§246 闭环 `ISSUE-P1-241`（「移除密码库关联」的确认文案承诺「不会删除物理文件」，而应用私有库的文件**会被真的删除**）——整改＝确认弹窗文案与动作按**存储类型**分列两套、判据落纯函数并单点化、数据层只在「应用私有库」分支删物理文件（产品口径落 `PD-17`）；真机逐字实证「界面声明与文件系统结果一致」（私有库删除后文件确已消失，外部库确认后文件原样在）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/246-移除密码库确认文案与真实行为一致批次.md`](resolved/batches/246-移除密码库确认文案与真实行为一致批次.md)。）

---

> **本区近期变动**：§244 闭环 `ISSUE-P1-238`（release 生产包真机 10 轮冷启动应答实测：
> `Start proc` → `onBeginCreateCredentialRequest` 最小 185 / 最大 247 / 中位 213 ms，
> 10/10 零超时，AC①~⑤ 全部满足；debug 无 odex 的平台属性按 AC⑤ 口径保持登记）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/244-release生产包冷启动验证与P1-238闭环批次.md`](resolved/batches/244-release生产包冷启动验证与P1-238闭环批次.md)。

> **本区历史上一次归零**：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)。

---

## P2 中危缺陷与协议/测试缺口（3 项）

> **本区上一次归零**（本区归零：§247 闭环 `ISSUE-P2-239`（凭据提供者通道「系统未登记本应用」的失效完全静默且用户无法自救）——整改＝新增该通道健康检查：三态判定落纯函数（`REGISTERED` / `NOT_REGISTERED` / `UNKNOWN`，**「读不到」不得呈现为「正常」**）、平台查询经**公开 API** `CredentialManager.isEnabledCredentialProviderService` 单点化、设置页健康卡给出用户可见状态与系统设置指引（action 不可解析时如实降级为纯文案）；真机两态实证「未登记」与系统 `TYPE_NO_CREATE_OPTIONS` 同态、已登记则请求被正常路由。新增限界 §27（本机 ROM 缺该设置页 activity ⇒ 一键入口如实降级）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/247-凭据提供者通道健康检查批次.md`](resolved/batches/247-凭据提供者通道健康检查批次.md)。）（本区随后补登 §248 复核发现的 3 项，见下）

### `ISSUE-P2-243`：子库只读会话未传 `BinaryStore` ⇒ 子库附件全量内联驻留

- **核实时间点**：2026-09-21（对 `已知工程限界.md` 全文独立复核时发现）。
- **核实方式**：直读 `app/src/main/java/com/keepasskey/app/data/childdb/ChildReadOnlySession.kt:131-143`
  （`KdbxFile.load(input, password, keyFile)` 三参调用）与
  `database/src/main/java/com/keepasskey/database/file/KdbxFile.kt:118-123`（`binaryStore: BinaryStore? = null`）；
  对照 `database/src/main/java/com/keepasskey/database/model/InnerHeader.kt:369-378`
  （仅当 store 非空且长度 > `BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`（1 MiB）才落盘）。
- **背景与影响**：限界 §1.1 声明「附件字节**除外**——超阈值经 `BinaryStore` 落盘、池中只留引用」，
  读者据此认为大附件库的内存峰值已消除。子库路径下该结论**不成立**：子库内任何大小的附件都内联进
  `InnerHeader.binaries`（单字段上限 64 MiB、池累计 ≤128 MiB 仍可容纳），解析期为**全量明文峰值**
  （投影完成后 `clearSensitiveData()` 只擦条目树）。§52 曾以「新增调用方无法再忘记传参」立
  `ISSUE-P2-67`，本路径（2026-09-10 引入）即反例，且此前未在任何限界条目登记。
- **验收标准**：
  - AC① 子库装载传入 `BinaryStore`；或明确论证「子库不落盘」属有意取舍，并作为**边界**登记限界 §1.1。
  - AC② 两者取一均需宿主用例锁定（落盘：断言超阈值附件不进池；取舍：断言现行为并注明理由）。
  - AC③ 不得改动主库（`DatabaseSession` / `SyncDatabaseCodec`）既有装配。
- **依据**：`ChildReadOnlySession.kt` / `KdbxFile.kt` / `InnerHeader.kt` 直读；
  `docs/architecture/已知工程限界.md` §1.1；
  `docs/resolved/batches/52-同步解析落盘与内存池擦除边界批次.md`。

### `ISSUE-P2-244`：S3 覆盖路径在远端无 ETag 时**一个条件头都不发**（退化为无条件 PUT）

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：直读 `sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt:240-300`：
  `expectedEtag` 为空且 HEAD 成功时 `precheckEtag = metaResult.getOrThrow().etag`；随后
  `precheckEtag?.takeIf { it.isNotBlank() }?.let { … header("If-Match", …) }` ⇒ ETag 为空/空白时
  **既不 `If-Match` 也不 `If-None-Match`**（该分支 `isFirstUpload = false`，故也走不到原子创建），
  实为**无条件 PUT**。
- **背景与影响**：限界 §1.3 的边界只声明「少数未实现条件写的兼容存储会忽略 `If-Match`」——那是
  「服务端不校验」；此处是**客户端根本没发条件头**，预检保护与服务端校验**同时缺失**，
  远端被他人更新时会被静默覆盖（数据丢失面）。触发前提：HEAD 探测成功但未返回 ETag（合理存在于部分兼容存储）。
- **验收标准**：
  - AC① 该分支改为 fail-closed：HEAD 成功但 ETag 缺失/空白 ⇒ 与「HEAD 非 404 失败」同口径上抛；
    或给出显式取舍（接受「所见即所覆」）并登记限界 §1.3 边界。
  - AC② 宿主用例锁定「无 ETag 时不得发出无条件的覆盖 PUT」（可用 mock provider 注入）。
  - AC③ 不得放宽既有 412 / 401 语义与 SigV4 头构造。
- **依据**：`S3SyncProvider.kt:240-300` 直读；`docs/architecture/已知工程限界.md` §1.3；
  `sync/src/androidTest/.../S3TransferDeviceTest.kt`（既有传输层证据）。

### `ISSUE-P2-245`：Compose 对话框窗口未接遮挡触摸过滤（限界 §3.3「无盲区」不成立，含主密码输入面）

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：全仓 `grep -rn "ApplyObscuredTouchFilter"` 调用点清单 ∩ `setFilterTouchesWhenObscured` 接线面，
  与对话框文件比对：`app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt:184`
  （新建库向导，**含主密码字段**）、`.../settings/MasterKeyChangeDialog.kt:75`、
  `.../settings/subscreens/ChildDatabaseDialogs.kt:85/176`、`.../detail/EntryDetailPreviewDiffComponents.kt:81/253`
  只经 `SecureDialogWindowEffect` 施 `FLAG_SECURE`（`app/.../security/SecureDialog.kt:98-118`），
  **无**遮挡触摸过滤接线；Compose `Dialog` 是独立窗口，不继承 Activity `decorView` 的过滤。
- **背景与影响**：限界 §3.3 标题为「接线面（**无盲区**）」。上述窗口承载主密码 / 密钥文件等高价值输入，
  若上层存在遮挡窗口（tapjacking）而无过滤，属既有加固面上的空档。**可利用性未在真机验证**，本条先以
  「如实登记 + 补接线或改判标题」处置。
- **验收标准**：
  - AC① 直读当前 Compose 版本（BOM 钉 `ui 1.12.0`）的 `Dialog` 窗口 API，判定能否对 dialog window
    施加 `setFilterTouchesWhenObscured`（或等价加固），**结论就地落 KDoc**。
  - AC② 按结论接线；或改判限界 §3.3 标题与「无盲区」结论（二者取一，不得两处并存）。
  - AC③ 宿主用例锁定接线存在性（对齐 `ObscuredTouchWiringTest` 的静态守卫口径）；涉及窗口行为的改动
    需真机冒烟 tapjacking 场景。
  - AC④ 不得移除既有任何一处的过滤接线。
- **依据**：`SecureDialog.kt` / `security/SecureTouchCompose.kt` 与四个对话框文件直读；
  `app/src/test/java/com/keepasskey/app/security/ObscuredTouchWiringTest.kt`；
  `docs/architecture/已知工程限界.md` §3.3。

---

> **本区近期变动**：§245 闭环 `ISSUE-P2-242`（条目列表与详情页标题过长时 Passkey 徽标被挤压变形或消失缺陷：`PasskeyBadge` 锁定单行不软折行、列表与详情页标题 Text 增加 `Modifier.weight(1f, fill = false)` 自适应让位约束，真机实测 46 字符超长标题项与中长项均完整水平横向呈现 `[Passkey]`；新增 `PasskeyBadgeLayoutWiringTest` 锁定布局契约）——证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`resolved/batches/245-条目行Passkey徽标防折行与标题自适应让位批次.md`](resolved/batches/245-条目行Passkey徽标防折行与标题自适应让位批次.md)；§243 闭环 `ISSUE-P2-240`（设置页「跳过 DAL 校验」开关的文案按其**真实语义**更正为
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

## P3 低危问题、特性接线与体验优化（5 项）

> **本区上一次归零**（本区归零：§238 闭环 `ISSUE-P3-235`（`RC-02` 敏感缓冲所有权收口）——
> AC① 设计交付 [`architecture/敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md)；
> AC② 逐处迁移：`G1`（§235）/ `G2`（§238）闭环、`G3`（池内擦除）维持已登记限界 §1.6、
> `G4`（命名统一）降为「按需」、`G5` / Step 1 已核实；`test` 343 类 / 2402 例全绿。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/238-待决冲突解析树身份判定擦除批次.md`](resolved/batches/238-待决冲突解析树身份判定擦除批次.md)）。（本区随后补登 §248 复核发现的 5 项，见下）

### `ISSUE-P3-246`：`SyncCacheAndroidRuntimeTest` 的「目录已清空」判据未按限界 §7 的实体口径

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：`sync/src/androidTest/java/com/keepasskey/sync/engine/SyncCacheAndroidRuntimeTest.kt:76`
  仍写 `assertTrue("清理后不得残留任何文件", (dir.listFiles() ?: emptyArray()).isEmpty())`
  ——用**原始目录枚举返回值**判定「目录已清空」；限界 §7 边界 1 明令不得如此（须以 `isFile` / NIO 实体复核），
  并声明 `SyncCacheTest` / `SyncCacheEvictorTest` 已按该口径更正；限界 §7 未说明该处为何豁免
  （`docs/records/SyncCache大写CACHE临时文件定位记录.md` §6.3 自陈扫描范围只含 `*/src/test`）。
- **背景与影响**：该判据在宿主产物上与 §7 口径不一致；用例只在真机跑，日后复现同形红会被读作新缺陷。
- **验收标准**：
  - AC① 该断言改为实体口径（`walkTopDown().filter { it.isFile }` 或 `File(dir, name).isFile`），
    与 `SyncCacheEvictorTest` 的写法一致。
  - AC② 改后按「测试资产纪律」②真机跑 `:sync:connectedDebugAndroidTest`。
  - AC③ **不得**放宽「真残留必红」的判别力。
- **依据**：`SyncCacheAndroidRuntimeTest.kt:76`；`docs/architecture/已知工程限界.md` §7 边界 1；
  `docs/records/SyncCache大写CACHE临时文件定位记录.md` §6.3。

### `ISSUE-P3-247`：sync 测试 APK 的明文放行未收窄到回环（与限界 §4.1 表述不一致）

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：直读 `sync/src/androidTest/AndroidManifest.xml`，其中为
  `<application android:usesCleartextTraffic="true" />`——**整个测试 APK 进程**放行明文（非仅回环），
  与同文件注释自述的「只放开测试 APK 进程的回环明文」不一致；限界 §4.1 亦写作
  「sync 测试 APK 经 `androidTest/AndroidManifest.xml` 声明 INTERNET 权限与回环明文豁免」。
- **背景与影响**：**生产侧不受影响**（`app/src/main/res/xml/network_security_config.xml`：
  `base-config cleartextTrafficPermitted="false"` + 信任锚仅系统 CA；OkHttp 层
  `SyncHttpClientFactory` 另有 TLS-only `ConnectionSpec`）。但测试侧口径宽于文档声明：
  该进程内任何主机（含非回环 LAN 地址）都允许明文。属**测试资产**口径债，不是产品缺陷。
- **验收标准**：
  - AC① 测试 APK 改用 network security config，**只对** `127.0.0.1` / `localhost` 放行明文，其余域仍禁。
  - AC② 改后按「测试资产纪律」②真机跑 `:sync:connectedDebugAndroidTest` 与 `:app:connectedDebugAndroidTest`
    （`CleartextPolicyDeviceTest` 依赖生产禁令保持生效）。
  - AC③ 生产 `network_security_config.xml` **一行不得放宽**；`CleartextPolicyDeviceTest` 必须仍绿。
- **依据**：`sync/src/androidTest/AndroidManifest.xml`；`app/src/main/res/xml/network_security_config.xml`；
  `docs/architecture/已知工程限界.md` §4.1。

### `ISSUE-P3-248`：`DatabasePickerViewModel` KDoc 与限界 §8 结论相反（该路径的提示**永不出现**）

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：`app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerViewModel.kt:236-259`
  （导入方法旁 KDoc 声称弱因子提示「统一落在退栈后的落点——解锁页 `UnlockViewModel.importExternalDatabase`」，
  实现只调仓库、不写 `infoMessage`）；`UnlockViewModel.importExternalDatabase` 的**唯一**调用点是
  `app/.../ui/screens/unlock/UnlockScreen.kt:114-120`（解锁页自身按钮）；`assessKdfStrength` 的唯一消费点
  亦在 `UnlockViewModel.kt:216-237`；进入选择器的路径唯一（`app/.../ui/KeePasskeyNavGraph.kt:60-62`），
  其 `onDatabaseSelected` 恒为 `popBackStack()`（`:70-72`，落点固定为解锁页）。
- **背景与影响**：限界 §8 已把「选择器『从来源打开』路径不给提示」登记为已接受残余；但代码 KDoc
  **反向**声称该路径由落点承接提示，两处结论相反。限界 §8 的**理由②**（「退栈落点不固定，无法交给落点显示」）
  亦与代码不符（落点唯一）。
- **验收标准**：
  - AC① 二者取一后一致：或接线（把弱因子评估接到选择器导入路径，并按限界 §8 解除条件①/② 选定呈现面），
    或改 KDoc 如实说明「本路径不给提示」。
  - AC② 若改 KDoc，须同步更正限界 §8 的理由②与「依据」。
  - AC③ 出口：接线则补断言；仅改文案则补静态守卫或人工复核留痕。
- **依据**：`DatabasePickerViewModel.kt` / `UnlockViewModel.kt` / `KeePasskeyNavGraph.kt` 直读；
  `docs/architecture/已知工程限界.md` §8。

### `ISSUE-P3-249`：复核报告四行与代码现况不一致（需同步或标注取代）

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：逐行对拍 `docs/security/SECURITY_RECHECK_2026-09.md` 与 HEAD 现状：
  ① `:232` 以「**全仓无锁态驱动的 UI 导航**」作为 `P2-65` 的判定依据，而
  `app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt:287-295`（`lockEvents` → Unlock + `popUpTo(0)`）
  正是锁态驱动导航；
  ② `:799` 表项 10 仍写 `P2-73` AC② 改 `FLAG_MUTABLE`「**不可照做**（本仓这两条路径不消费 fillIn extras）」，
  该前提已随 §110 / §111 修复失效（现行代码即 `FLAG_MUTABLE` + 经 `EXTRA_AUTHENTICATION_RESULT`
  回传完整数据集，真机验证通过）；
  ③ `:609-610` 仍把「CM 通道 `UNBOUND` 越权面**未进**限界登记表」列为残余，而限界 §6 已于 §82 批次补登；
  ④ `:850` 仍记 `SUPPLY-06`「本轮未直读」，而 §236 已直读定案并把结论落限界 §25。
- **验收标准**：
  - AC① 四行按现状更正；或加「已由 §NNN 取代」标注并**保留原文**（本项目留痕口径）。
  - AC② 改后必跑 `bash tools/audit/check_recheck_consistency.sh` 与 `python tools/doc/check_md_links.py`。
  - AC③ 改写时**不得**在正文复述脚本的禁用短语（§15.2(p) 的既有教训）。
- **依据**：`SECURITY_RECHECK_2026-09.md` 上述四行；`docs/architecture/已知工程限界.md` §6 / §25；
  `docs/resolved/batches/82-…`、`110-…`、`111-…`、`236-…`。

### `ISSUE-P3-250`：限界 §18 / §20 两条「行数下限」的成立前提已变，需重新裁定

- **核实时间点**：2026-09-21（同上轮复核，逐值现跑）。
- **核实方式**：
  ① `wc -l app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt` = **600**（限界 §18 记 543），
  `grep -cE "^ +(fun|val|var) "` = **118**（限界 §18 记「约 90」），且**并非全部为一行委托**——
  `verifyConnectionThenSync`（`:346`）、`refreshPrivilegedBrowsers`（`:447`）、`setPrivilegedBrowserEnabled`（`:455`）、
  `clearBlockedFields`（`:477`）、`enableBreachCheckAndScan`（`:537`）各含实现体，另有 `init{}`（`:258-276`）编排；
  而 §18 **自身**的边界① 写「任何仍含实现体的超长文件**一律不适用**」；
  ② `python tools/doc/long_functions.py 40` 显示 `app/.../autofill/KeePasskeyAutofillService.kt::processFillRequest`
  = **65** 行（限界 §20 记 55），**已越过 §20 自己写的解除条件①（`>60 行` 须在同一次改动里顺带达标）**；
  其内部结构亦已变（现 4 处早退，其中黑名单命中改走命名谓词 `rejectsDatasetDelivery`，非原记的「三段 `?: run`」）。
- **背景与影响**：两条限界都被引用为「不必再拆」的授权，但**前提值均已失效**：§18 的保护对象已含实现体
  （按本条自订边界即应移出本条），§20 的对象已触发自己的解除条件却仍挂「接受为限界」。
  继续按原文引用会得到**与实际相反的授权**。
- **验收标准**：
  - AC① SettingsViewModel：或在**同一次改动**内把 5 个薄编排成员下沉到控制器（回到纯门面形态），
    或把该文件移出 §18 并按「巨型类专项」第二档重新裁定（限界 §18 与批次正文同步更正）。
  - AC② `processFillRequest`：在同一次改动内降到 ≤50 行（含把早退改写为可观测的拒绝计数等达标手段，
    需设备侧 `AutofillAuthChainDeviceTest` 证伪风险），或**重新裁定**并改写 §20 的判据与解除条件。
  - AC③ 涉及应用内行为改动时，按「测试资产纪律」②真机跑 `:app:connectedDebugAndroidTest`；
    纯结构下沉（行为不变）则以宿主全量 + 既有守卫用例为准，并在批次正文写明证据。
  - AC④ **不得**以本条为由放宽 `.codebuddy/rules/engineering-rules.md` 的巨型类 / 长函数阈值。
- **依据**：`wc -l` / `grep -cE` / `python tools/doc/long_functions.py 40` 现跑读数；
  `docs/architecture/已知工程限界.md` §18 / §20；
  `docs/resolved/batches/182-合并层冲突对下沉与早退守卫限界批次.md`。
>
> **本区近期变动**：§236 闭环 `ISSUE-P3-233`（复核报告 `P3-120` 状态陈旧——更正报告
> §10.1 / §3.3.2 / §2.3 / §2.4 / §6.7 / §9.2 并增补 §15.3 方法学第 12 条）与 `ISSUE-P3-234`
> （DAL 出口 IP 字面量面定案，登记 [`已知工程限界.md`](architecture/已知工程限界.md) §25）；
> §237 闭环 `ISSUE-P3-230`（已有 SAF 库授权失败不再静默——提示 + 列表状态 + 重授入口，
> 限界 §24 的残余段同步收口）。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，§224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。
