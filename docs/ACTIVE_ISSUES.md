# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。
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

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项（全量待办归零）**。2026-09-25 解锁节流完整性层 fail-closed 缺陷与冗余性裁决
> （`ISSUE-P1-277`，完整性层整体移除 / 基础节流保留，`PD-46`）闭环见 §334。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-27 两条真机手测报出的凭据可用性缺陷（`ISSUE-P2-341` 回收站凭据仍被供给 /
> `ISSUE-P2-343` 只读开关在指纹路径被静默忽略）分别收口于 §343 与 §344；
> 更早的 P2 闭环流水见 `RESOLVED_LOG.md` §315 ~ §325。



## P3 低危问题、特性接线与体验优化（3 项）

> **开放项 3 条**（这里只列**各条还欠什么**；已完成的实施细节留在条目正文与 commit 里，不重复登记）：
> ① `ISSUE-P3-345` —— 全条未开工（2026-09-27 由用户命题「匹配不到时不能像 KeePassDX 那样新建一条」
>    调研立项；前提已按参考项目源码纠偏，写库原语与受保护窗口范式均已具备，缺的是入口与预填管线）。
> ② `ISSUE-P3-340` —— 仅剩一条未决：普查是否升级为 `hygiene-gate` 第九条闸门（前置：先给脚本加带理由的
>    显式豁免清单，否则主题包装器一类合理豁免会被长期判红）。
> ③ `ISSUE-P3-339` —— **用户指示暂时搁置**（浏览器半环需外部域名与信任链资源）。
> 2026-09-28 UI 现代化批次（`ISSUE-P3-346` ~ `ISSUE-P3-351` 六条，含两条登记即复核否决的
> 350 / 351 中 350 归档注明）收口于 `RESOLVED_LOG.md` §345；
> 更早的 P3 闭环流水见 `RESOLVED_LOG.md` §326 ~ §344（`ISSUE-P3-337` / `ISSUE-P3-342` 收口于 §343，
> `ISSUE-P2-343` 收口于 §344）。

### ISSUE-P3-345：凭据请求匹配不到条目时两条通道都是**死路**——无「就地新建并用于本次填充」入口（对齐同类项目）

- **优先级理由**：P3（功能缺口，不涉安全边界）；但它是同类应用的**常规能力**，且本仓写库原语、
  受保护窗口落地页、`PendingIntent` 契约**三者齐备** ⇒ 增量只剩「入口 + 预填管线」，代价可控。
- **用户命题的纠偏（登记以免按错误前提开工）**：命题「KeePassDX 匹配不到就能新建一条」**部分成立**——
  入口**不在系统下拉**：
  - KeePassDX 无匹配分支只挂认证/选择条目，**没有任何 Create 型数据集**
    （`参考项目/KeePassDX-master/.../autofill/KeeAutofillService.kt:191-197`；
    provider 侧 `passkey/PasskeyProviderService.kt:268-291` 无候选只给一条 SELECTION 性质条目，
    `CreateEntry` 仅出现在 create 流 `:320-405`）；
  - 真通路在其**应用内选择页**：`GroupActivity.kt:758-800` 的「新增条目」在 SELECTION 模式下调
    `EntryEditActivity.launchForSelection(…, searchInfo)` → `EntryEditActivity.kt:200-203` 把
    `SearchInfo.toRegisterInfo()` 当注册信息 → `ContextualDatabase.kt:101-135`（`:127`）预填新条目 →
    保存后 `:541-550` 回传 id、`AutofillLauncherViewModel.kt:183-190` 用同一 `AutofillHelper` 下发**真实数据集**。
    ⇒ 「当场新建并用于本次填充」确实做得到；
  - **最贴近用户表述的是 keepass2android**：无匹配也恒挂占位数据集
    （`AutofillServiceBase.cs:228-235`、`:354-373`），并在结果页底栏放**「Create entry for URL」**
    （`ShareUrlResults.cs:157-203`，文案 `Resources/values/strings.xml:29`，仅库可写时可见）→
    `GroupActivity.Launch(new CreateEntryThenCloseTask { Url = searchUrl })` 整表预填。
- **本项目现状（核实 2026-09-27，方式：逐文件读代码，非推定）**：
  1. **CM 通道**：已解锁但零候选 ⇒ `CredentialResponseAssembler.buildUnlockedGetResponse` 返回**空响应**，
     系统选择器上本应用什么都不呈现；唯一的 `AuthenticationAction` 只在**锁库**分支产出
     （`KeePasskeyCredentialProviderService.kt:150-166`）。create 流的 `CreateEntry` 已具备
     （`CredentialCreateEntries.kt:41-85`），但那要求站点/应用**主动发起注册**——登录页只发 get，
     用户撞上的仍是死路。
  2. **Autofill 通道**：手动选择器已能「查全部条目」（`AutofillPickerViewModel.kt:84` 取整库、
     `AutofillEntrySearch.filter` 空查询返回全量），但空结果**只有一行文案**
     `autofill_picker_empty`「没有匹配的条目」（`AutofillPickerScreen.kt:172-179`），**零动作**；
     提交后捕获（`applySaveInfoIfNeeded` `AutofillDatasetBuilders.kt:444-474` + `onSaveRequest`）是
     「事后保存」，不是「当场新建」。
  3. **可复用资产**：`saveAutofillCredential` 已按「域名/包名 + 用户名」命中则**原地更新**、未命中则新建
     （`VaultEntryWriteCoordinator.kt:315-332`）；`PasswordSaveActivity` / `CredentialUnlockActivity` 是
     受保护窗口内落地页的现成范式；选择器 Intent 已带 `EXTRA_WEB_DOMAIN` / `EXTRA_CALLING_PACKAGE`
     （`AutofillPickerActivity.kt:365-377`）⇒ 预填数据源现成；应用内新建页 `Screen.EntryEdit`
     （`entryId` 为空即新建，`EntryEditViewModel.kt:161-168`）**尚无 url / 用户名预填入参**。
- **官方契约（已取 API 原文，改动前须以原文为准，勿凭记忆）**：
  `androidx.credentials.provider.Action`（Added in 1.2.0；本仓 credentials=1.6.0 ⇒ 可用）**正是**为此场景设计——
  官方举例标题即 `Add a new Password`。经 `BeginGetCredentialResponse.Builder.addAction` / `setActions`
  呈现于选择器**独立的「Actions」类目**；落地 Activity 用 `PendingIntentHandler.retrieveBeginGetCredentialRequest`
  取回原请求，完成后 `setResult(RESULT_OK, …)` 并以 `PendingIntentHandler.setGetCredentialResponse` 回**凭据本身**
  （**不同于**解锁链回 `BeginGetCredentialResponse`）；**放弃时 `RESULT_CANCELED` 会重新弹出选择器**
  （挂这个入口不会把用户推出登录流程）；`PendingIntent` 必须 `FLAG_MUTABLE`、**不得** `FLAG_ONE_SHOT`、
  requestCode 逐条目唯一（本仓集中于 `CredentialPendingIntents`，ISSUE-P1-01 / P2-199 已满足）。
  `CreateEntry` 按定义只属于 create 流，**不得**塞进 get 响应。
- **整改口径**：
  - **A. CM 通道（只做客观能做的：口令维度）**：零候选且请求含 `BeginGetPasswordOption` 时追加**一条** `Action`
    → 新建落地页在受保护窗口内从系统背书请求取 origin / 包名 / 用户名做预填 → 用户设密码并显式确认 →
    `saveAutofillCredential` 落库 → `setGetCredentialResponse` 完成本次登录；放弃 ⇒ `RESULT_CANCELED`。
    文案**不得插值域名 / 包名**（ISSUE-P1-10 纪律沿用）。
  - **B. Autofill 通道**：把选择器空态文案升级为动作（照 KP2A 的「为该 URL 新建条目」形态），复用同一预填落地页，
    保存成功后经既有 `EXTRA_AUTHENTICATION_RESULT` 通路（`AutofillPickerActivity.kt:323`）把新条目作为**本次数据集**交付。
    只读会话（`isSessionReadOnly()`）/ 库不可写 ⇒ **不呈现该动作**（`PD-50`「控件不许骗人」口径）。
  - **C. 通行密钥维度明确不做**：本应用不能凭空造出一把对某 rpId 有效的凭据 ⇒ **禁止**出现「新建通行密钥」按钮；
    公钥请求零候选时只能给纯引导文案（或不挂条目）。该取舍须登记为 `PD-51`（属取舍而非缺陷，不入待办面）。
- **不得双向声称的边界**：`Action` 类目在 Android 16 系统选择器上的**实际呈现与点击回传**属系统 UI 行为，
  宿主单测无法证伪 ⇒ AC 必须含真机读数。且 `AuthenticationAction` 在仓内**零测试覆盖**
  （2026-09-27 `grep AuthenticationAction app/src/test app/src/androidTest` = 0 命中）
  ⇒ **不得**以「解锁 Action 一直能用」推定 Action 类目可用（§143 / §147 两次「宿主全绿、真机失败」的教训形态）。
- **AC**：
  ① CM 零候选（含口令请求）产出**且仅产出 1 条** Action；锁库分支不得同时产出；通道总开关 / 黑名单 /
     只读会话三条 fail-closed 路径**不得**产出（与 §232 同口径，用**计数**断言防「只接一处」）；
  ② 新建保存后本次登录直接拿到凭据（真机读数：`RESULT_OK` + 调用方收到 `PasswordCredential`）；
  ③ 放弃新建 ⇒ 选择器重新出现（真机读数）；
  ④ 预填字段只来自系统背书请求，**不得**读调用方可控的 `candidateQueryData`（H1 同源纪律）；
  ⑤ Autofill 侧空态不再是纯文本死路，且新条目能作为本次数据集回填；
  ⑥ 成对断言（打开→出 Action ／ 关闭·只读→不出）+ `check_tautological_assertions` +
     `check_box_slot_children` + 全量 `test` 绿 + `gate_readings.py` 8/8 + `check_md_links.py` 0 断链；
  ⑦ 新增可见性开关的 `@Preview` 两态齐（`ISSUE-P3-340` 规则条文第二次生效）。
- **关联**：`PD-05`（锁库填充改链确认页）/ `PD-12`（拒绝页**就地补救动作**先例）/ `PD-50` / `ISSUE-P3-337`。


### ISSUE-P3-340：`@Preview` 状态覆盖无机检——新增可见性开关未同步补预览，布局缺陷在编译期与预览里双双隐形

- **优先级理由**：P3（不是线上缺陷，是**闸门缺口**）；但它是「只有用户能看见的缺陷」的固定漏口，
  且**完全不依赖真机**即可整改 ⇒ 排在 339 浏览器半环之前做。
- **核实时间点与方式（2026-09-27，方式：真机截图 + 本批预览差分 + 机检读数，非推定）**：
  1. **事故现场**：编辑页 Passkey 区块里 Q1 的「扫码 / 相册导入通行密钥」按钮与说明文字**在真机上重叠**
     （用户截图，原话「被折叠在一起了」）。根因是 `BentoCard` 内容槽为 `Box`、同层兄弟互相叠放
     （已由同批新增的 `tools/doc/check_box_slot_children.py` 做成机检，全仓 59 站点 0 命中）；
  2. **但本条要登记的不是叠放本身，而是「两道现成闸门都没看见它」**：
     ① `@Preview` 当时只画了 `canImportPasskey = false` 那一态 ⇒ 预览导出图**完全正常**；
     ② `:app:compileDebugScreenshotTestKotlin` 全绿 ⇒ 它只验包装**能编译**，不验布局。
     补上 `true` 态后（本批）重叠立刻在渲染图里显现 ⇒ 反证：**「预览有没有覆盖到那一态」是唯一在守的关口，
     而这个关口目前无人守**（无判据、无机检、无规则条文）。
- **风险面（为什么不是 cosmetic）**：带默认值布尔参数的可见性开关是 Compose 组件最常见的扩展形态，
  每加一态就多一个「预览不覆盖 ⇒ 闸门失明」的窗口；后果不止重叠，还有文字截断、触控热区挤压、
  暗色对比度不达标——这些**都只在真机肉眼可见**，等于把回归测试外包给用户。
- **整改口径**：
  1. **先普查、不先挂闸门**：写 `tools/doc/check_preview_state_coverage.py`，口径＝对每个「带默认值布尔 /
     可空参数的 `@Composable`」，检查其 `@Preview` 调用是否覆盖到**该参数为 true（或非空）那一态**；
     首版**只出报告读数**（组件数 / 已覆盖数 / 覆盖率 / 未覆盖清单），**不**进 `hygiene-gate`——
     覆盖率类判据既易假绿（同义反复地"算上"）又易误报（组件组合爆炸），必须先拿到真实读数再定阈值；
  2. **规则条文**：写进 `.codebuddy/rules/engineering-rules.md` 的 Compose 一节——
     「**给组件新增可见性开关参数时，同一批必须补该态的 `@Preview`**；做不到（组合态过多）就在批次文档里
     写明该态由哪条设备读数覆盖」，与本批 ① 的普查读数配套；
  3. **机检须自带反校**：与 `check_box_slot_children.py` 同形态提供 `--selftest`（内嵌「只画 false 态」的
     已知坏样本必须命中、「两态齐」的好样本必须不命中），**不得**只凭目测登记判据成立——
     本条立项过程中写的**第一版**叠放扫描器就连续三处自身有 bug（只认 `Name(` 不认裸 `Name {`、
     没跳过标识符本身、把 `build/` 排除口径套到自检临时目录上），**三次都是拿内嵌坏样本才照出来的**。
- **AC**：
  ① 普查脚本对**当前全仓**给出可读数量（带默认布尔参数的 Composable 数 / 预览覆盖 true 态的比率 /
     未覆盖清单前 N 条），读数**原样**入档；
  ② 至少把编辑页这一族（`EntryEdit*Section`）补到两态齐，并给出补前 / 补后的覆盖率差分；
  ③ **禁止**为凑覆盖率删断言、虚报预览或把未覆盖态写成「已覆盖」；
  ④ `gate_readings.py` 全 PASS（当前 **8/8**）+ `check_md_links.py` 0 断链。
- **留痕（2026-09-27，①②③ 已做，全程不依赖真机）**：
  - **① 普查脚本已落地**：`tools/doc/check_preview_state_coverage.py`（判据＝「带字面量默认值的 `Boolean`
    参数，其组件的 `@Preview` 调用里有没有一处把它设成**与默认值相反的字面量**」；只认字面量 ⇒ 保守，
    只会低估覆盖率、**不会**反向假绿）。`--selftest` 三向反校（只画默认态 ⇒ 必报漏态；补了反向态 ⇒ 必不报；
    组件根本无预览 ⇒ 必落「无预览」桶）**3/3 OK**。
    **工具自身被反校照出的两处缺陷（不修就会静默漏掉本条要抓的那一个）**：
    (a) 调用点检测误用了声明正则（要求 `fun ` 关键字）⇒ 预览里的调用一个都认不出，读数会是一片假红；
    (b) 参数表切分把 `->` 的 `>` 计入尖括号深度 ⇒ 深度变负，**第一个函数型参数之后的所有参数都不再被切开**，
    第一版因此完全没数到 `EntryEditPasskeySection.canImportPasskey`（正是本条的靶子）。
    两向都用**真实历史文件**反校过：对 `HEAD~1` 的 `EntryEditComponents.kt` 跑，脚本准确报出
    「`canImportPasskey` 默认 false ⇒ 缺 true 态预览」，对改后树则报「已覆盖」。
  - **普查读数（改前 → 改后，`app/src/main`）**：带默认值的 `Boolean` 开关参数 **19** 个；
    缺反向态 **10 → 2**；完全无预览 **7**（未动，见下）；两态齐 **2/19 = 10.5% → 10/19 = 52.6%**。
  - **② 补态清单（6 处 / 5 文件）**：`SecurePasswordField`（`isError` + `isPasswordVisible` + `enabled`
    三态合画成「错误 + 明文 + 禁用」这一最恶劣可读性组合）、`CredentialFillConfirmScreen.confirmEnabled = false`
    （**安全相关**：归属校验未通过时确认钮禁用）、`SettingsContent.showBackButton = true`、
    `BreachCheckToggleRow.isScanning = true`、`ThemePaletteItemCard.enabled = false`（主题锁定态）、
    `VaultListSearchTopBar.autoActivateSearch = true`。整屏组件一律**另开 `@Preview` 函数**（同一张图里叠两个
    整屏会把各自高度压没，导出图反而没法比对）；小卡片 / 行才在同一预览里用 `Column` 并排两态。
  - **补完立刻照出两条新读数**（这正是补态的意义，不是凑覆盖率）：
    ① `SecurePasswordField` 退化态渲染图里，**禁用时错误提示文字与错误描边一起降成灰**，字段看起来与
    「普通禁用字段」无异 ⇒ 判为**与 M3 禁用色阶口径一致、不整改**，但把事实记在这里：
    **只读会话里的校验失败在视觉上不可辨**；
    ② `CredentialFillConfirmScreen` 禁用态里「确认填充」（灰底）与「取消」（描边）**仍可分辨**
    ⇒ 安全侧可读性通过，用户不会把「填不进」当成「填好了」。
  - **剩余 2 条判为不补（附理由，不是漏网）**：`KeePasskeyTheme.oledBlack` / `dynamicColorEnabled`
    —— 二者是**主题包装器**参数而非「某一屏的可见性开关」，要覆盖就得把每个既有预览按两种主题重画一遍
    （收益远低于代价）；`SecureDialogWindowEffect.flagSecure` 一类落进「无预览」桶的是**无可见输出的
    副作用函数**，给它加预览等于造假覆盖。⇒ 本条判据只对「有布局输出的组件级开关」有效。
  - **③ 规则条文已落** `.codebuddy/rules/engineering-rules.md` §Compose UI 规则：新增可见性开关须同批补该态
    预览；并同批写入「卡片内容槽是 `Box` 时多子节点必须自己包 `Column`/`Row`」那条（附机检名）。
  - **验证读数（原样粘贴）**：`:app:compileDebugKotlin` + `:app:compileDebugScreenshotTestKotlin --rerun`
    编译通过（包装 79 → **83**，即本批新增 4 个 `@Preview` 函数）；`:app:exportSecondaryPreviewScreenshots`
    渲染出 4 张新态；`test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL in 3m36s、114/114 executed**，
    `count_test_results.py` = `xml=413 tests=2749 failures=0 errors=0 skipped=13`；
    `gate_readings.py` **8/8 PASS**（`tier1(>500)=0` / `tier2=36 budget=37` / `long_functions=0` /
    `BROKEN_MD_LINKS=0` / `RESOLVED_INDEX_SYNC=OK` / 重言 0 命中·471 文件 / `allowed=12` /
    `check_box_slot_children` 站点 59·命中 0）。
- **未决**：普查是否升级为 `hygiene-gate` 第九条。**已有真实读数支撑判断**：全仓只有 19 个开关参数、
  补态成本极低（本批 6 处一次做完），剩 2 条是**应当豁免**而非**应当整改**——直接挂闸门会把「主题包装器」
  这类合理豁免也判红，故须先给脚本加**带理由的显式豁免标记**（形如 `check_bounded_type_names` 的 `ALLOWED`
  清单：豁免必须可见、可审计，不得静默跳过）再谈挂闸。**在此之前它只出读数，不进 CI。**
- **粗估**：0.5–1 人日（普查脚本 + 编辑页一族补态 + 规则条文）。
- **关联**：`ISSUE-P3-337`（本缺陷的发现现场，其「真机手测读数」留痕块记着事故全貌）/
  `tools/doc/check_box_slot_children.py`（同批新增的 Box 叠放机检，本条 ③ 的形态范本）/
  `AGENTS.md` §5（截图测试包装编译门禁那条已注明「编译绿 ≠ 布局对」）/
  `.codebuddy/rules/engineering-rules.md`（Compose 规范一节，本条 ② 的落笔处）。

### ISSUE-P3-339：仿冒域能否唤醒通行密钥（本地 RP 实验室 + 四类仿冒 origin 的「该醒 / 不该醒」）——**代码层已闭环，浏览器半环搁置**

- **状态（2026-09-27 用户指示）**：**暂时搁置**。剩余部分需要外部资源（自有域名 + ACME DNS-01，
  或企业策略放行自建 CA，或换自带信任库的浏览器并相应重述判据），用户裁「先精简描述、暂不做」。
- **威胁模型（本条的立规前提，勿在后续复用时被悄悄替换）**：**真实攻击者只有公开 DNS 与公网可信证书，
  没有任何设备篡改能力**。⇒ 早期"靠 `adb root` 注系统 CA + 改 hosts"的做法**已判为不成立并撤销**
  （用户质疑「真实场景中你怎么可能安装别的 CA 证书，还弄到系统目录去？」）；
  一切结论必须出自**攻击者可达的**配置。
- **已闭环的半环（代码层，2026-09-27）**：表驱动用例首跑即红两条，都是实测越界、随批修掉——
  ① `KeePasskeyCredentialProviderService.findMatchingEntries` 的 URL 兜底不区分条目类型
  ⇒ 「rpId 属 A 域、条目 URL 写了 B 域」的 passkey 会被列进 B 域候选（**凭据存在性跨域泄露**，
  签名侧另有 rpId 复核故断言交不出去，但用户一点就在签名处撞上拒绝）；收紧为
  「URL 兜底只服务口令/密码条目，passkey 在域维度只认 `passkeyRpId` 一个真相源」；
  ② `PublicSuffixList.normalizeHost` 去 DNS 根点而 `DomainMatcher.extractDomain` 不去
  ⇒ 同一主机名在两个归一器手里答案不同（尾点 origin 的合法凭据不出候选，公共后缀查找却判同域）；
  现由 `extractDomain` 统一剔除末点。新增 `CredentialProviderLookalikeMatchTest` 5 例
  （真域/子域正向 ＋ 后缀堆叠/异域/punycode 同形/前缀粘连负向 ＋ IP origin  尾点大小写），
  **每条负向都配同源正向对照**。⇒ 细节见 commit `0199bc69`。
- **一条设计更正（登记以免复发）**：`rp.testlab.xyz` 与 `rp.testlab.xyz.phish.testlab.xyz` 的 eTLD+1
  同为 `testlab.xyz` ⇒ 对 WebAuthn 是**同站**，原设计的仿冒矩阵在该形态下无效
  （代码层不受影响，因 `isDomainMatch` 是标签后缀判定，三条堆叠负向实测均正确拒绝）。
- **未闭环的半环（浏览器 → 系统 CM → 本应用 provider）**：**未做仿冒域矩阵**，且已判死三条候选路线——
  ⚠️ **但「能不能到达 provider」这一项已由真机读数排除**（2026-09-27 19:55，`mark.via` →
  系统 CM → 本应用 provider → 断言，RP 侧 AAGUID 等于本应用 `DEFAULT_AAGUID`；
  见 `ISSUE-P3-337` 的「19:55 真实 RP 的 PRF 端到端读数」）⇒ 剩余阻塞**只剩**域名与信任链。
  AVD 上 GMS 自带的 FIDO 栈会截走请求（provider 日志命中 0 次）、实验机上 Firefox 的 `get()` 永不返回、
  `adb shell cmd credential` 无 shell 实现 ⇒ 无入口直接下发 `GetCredentialRequest`。
  实验室载体已落地可复用：`tools/passkey-phish-lab/`（自建 CA + 多 SAN 证书、HTTPS RP 自跑页、
  只读 CBOR 子集、6 用例矩阵驱动；`rp_server` 默认只绑 127.0.0.1，局域网零暴露）。
  ⚠️ 该矩阵的判据本身被实测纠过一次：原版把「六例全无浏览器读数」判成 PASS——**环境假绿**，
  已改为「无浏览器读数 ⇒ 无效，不得计为通过」。
- **红线（不变，优先级高于任何进度）**：**在破不通真实信任锚之前，本条目下不得出现任何
  「仿冒域不会唤醒」的结论**。今天能声称的只有「本应用对给定 rpId 的接受/拒绝判定」，
  **不是**「Chrome 不唤醒仿冒站」（那是上游 origin 校验，未证）。
- **载体顺带承接**：`ISSUE-P3-337` 的 AC⑧(b)（对真实 RP 完成 GetAssertion）与未决 6 计数器跳变实测出口。
- **关联**：`ISSUE-P3-337` / `PD-32`、`PD-33`（DAL 与 caller origin 归因）/ commits
  `db332b78`、`8e2a7700`、`fa283ea2`、`0199bc69`、`dc741803`（完整核实与读数）。


