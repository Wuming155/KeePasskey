# §459 钉版守卫 / proguard 实物 / 预览逐屏 / S3 截断不变式批次（2026-10-06）

**条目**：`ISSUE-P3-505` + `507` + `509` + `510` + `513` **五条整条闭环**（P3 5 → **0**，本轮 P3 全清）
**来源**：`2026-10-06` 三类隐蔽性故障排查报告（[`../../records/三类隐蔽性故障排查报告_2026-10-06.md`](../../records/三类隐蔽性故障排查报告_2026-10-06.md)）；五条均为 **dormant**（零错误效果，缺陷以「约束无执行点 / no-op 规则 / 静默退化管道 / 不变式缺口 / 散文承诺」形态存在）。
**本批性质**：本批是唯一含**生产行为改动**的一批（`S3DirectoryList` 截断降级），并新增四个宿主守卫用例类 / 用例组。

## 459.1 原始条目（原样收录）

### ISSUE-P3-505：material3 双声明的钉版效力寄生于 Gradle 最高版本胜出，`libs.versions.toml:46` 的约束无机检承载

- **核实时间点**：2026-10-06；**核实方式**：实读 `app/build.gradle.kts:273`（BOM 托管无版本）与 `:284`（显式 alpha）并存、`:274` 注释自述「显式覆盖 BOM 映射——受控保留 alpha」、`:281-283` 退出条件；实读 `gradle/libs.versions.toml:103` / `:105`（别名同 `group + name`）与 `:49`（`material3 = "1.5.0-alpha28"`）、`:46`（约束「仅允许 1.5.0-alphaN 内部上调」）；全仓 gradle 文件无 `resolutionStrategy.force/strictly`；实读 `DependencyResolutionDeterminismTest.kt:34`（用例仅拦动态 / 快照版本）与 `:22-29` KDoc 自陈覆盖边界，`tools/` 与五模块 `src/test` grep `compose-bom` **零命中**；触发向量：`.github/dependabot.yml:15` `interval: "weekly"` 覆盖版本目录，git 史 `d53fc93e` / `06341f92` 证明 Dependabot 实际在动这两项。
- **背景**：`toml:46` 的约束读作受控策略，**实际效力仅是版本排序出价**——未来 BOM 的 material3 映射一旦越过钉版即静默改写实际版本。且 **`:284` 并非死开关**：报告实跑 `dependencyInsight` 确认当前解析恰为受控目标 `1.5.0-alpha28`（BOM 映射 1.4.0），双声明与退出条件由 `退役依据承接-ISSUE-P3-09.md:32` 有意登记 ⇒ 属「护栏脆弱」而非「已失效」。
  - **触发状态：dormant**——当前配置（`composeBom=2026.09.00`，映射 1.4.0 < 钉版）下翻转不可达。
  - 报告如实修正：①「静默」仅相对钉版行与门禁——翻转必经 Dependabot PR 且 `libs.versions.toml` 的 `composeBom` 行必有可评审 diff；②最可能触发形态（BOM 映射 1.5.0 stable）恰是已登记退出条件、属良性结果。
- **涉及文件**：`app/build.gradle.kts:273-284`、`gradle/libs.versions.toml:46` / `:49` / `:103` / `:105`、`app/src/test/java/com/keepasskey/app/security/DependencyResolutionDeterminismTest.kt:33-58`。
- **验收标准**：在 `DependencyResolutionDeterminismTest` 补一条断言——「BOM 声明的 material3 版本**不得高于** `libs.versions.toml` 登记的钉版值」，使 `toml:46` 的约束有真实执行点（当前零翻转即绿）。

### ISSUE-P3-507：`proguard-rules.pro` 的 OkHttp keep 规则指向不存在的方法，恒不匹配

- **核实时间点**：2026-10-06（报告方实测；本轮登记仅实读规则原文，javap 已由报告方留痕）；**核实方式**：实读 `app/proguard-rules.pro:49-51`（`-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase { native byte[] findSuffix(java.lang.String[]); }`）与 `:22`（`-dontnote **`）、`:4-7` 文件头「据实最小保留」自立原则；实读 `app/build.gradle.kts:218`（`isMinifyEnabled = true`）与 `:222-225`（`proguardFiles(..., "proguard-rules.pro")`）确认 R8 必吃本文件；报告方已解包实际解析的 `okhttp-android 5.5.0` AAR 实测：`javap -p` 输出成员**无任何 native 方法、无 `findSuffix`**，官方 sources 对应文件 grep `native|findSuffix` 零命中；全仓 `rg "PublicSuffixDatabase|findSuffix"`（排除参考项目）**仅命中规则自身**；`git log -S "findSuffix"` 仅 `b0cdc898`（初版即无依据注释）。
- **背景**：本仓实际解析的 okhttp-android 5.5.0 中 `PublicSuffixDatabase` 无任何 native 方法，该规则**恒不匹配、静默 no-op**，违反文件头自立原则；同型缺陷 `ISSUE-P3-98`（规则 12 签名写错永不匹配）已闭环并由 `AppLogProguardRuleTest` 锁定守卫，而本规则**无任何守卫测试**。
  - **后果面**：无运行期影响——该类无反射消费方、AAR 自带 consumer 规则也不需要它，`-keepclassmembers` 成员模式不匹配时既不保类也不保成员，对产物零影响 ⇒ 属「误导性假配置」而非功能缺陷。
  - **触发状态：dormant**——规则恒不匹配但未产出任何错误效果。
  - **未核实项（如实声明）**：`:22` `-dontnote **` 是否吞掉 R8 未匹配规则提示**未做 R8 A/B 构建证实**（no-op 的核心结论由 javap + 源码 grep + 零反射消费方三面独立证实，不依赖该行）。
  - 登记表核查：两表 proguard / 混淆 / keep / okhttp 关键词仅命中「OkHttp IP 字面量不经 Dns」两条无关项。
- **涉及文件**：`app/proguard-rules.pro:49-51`、`app/src/test/java/com/keepasskey/app/log/AppLogProguardRuleTest.kt`（守卫缺口参照）。
- **验收标准**：删除该 no-op 规则（或按实际存在的成员改写），并**同批扩展 `AppLogProguardRuleTest` 锁定剩余 keep 规则的类名 / 签名在依赖实物中真实存在**（照 ISSUE-P3-98 的守卫模式）；release 规则改动须跑 `.\gradlew.bat assembleRelease` 验证 R8 真实接受。

### ISSUE-P3-509：主屏预览导出白名单按前缀匹配，改名 / 删除后静默 0 命中且无逐屏断言

- **核实时间点**：2026-10-06；**核实方式**：实读 `app/build.gradle.kts:356`（`val mainScreenPreviewPrefixes = listOf(`）并**程序化清点得 20 项**、静态核对 20/20 前缀（去 `ScreenshotExport` 后缀）均命中 `app/src/main/java` 真实预览函数（`missing_count = 0`）；实读 `:396-397`（`include("**/${prefix}_*.png")`，**0 命中不报错**、Sync 仍成功）、`:403-408`（`doLast` 仅 `light` / `dark` 计数并 `logger.lifecycle`，**无逐屏命中断言**）、`:420-421`（次要任务用同一清单 `exclude`——改名的主屏会静默漏入 `secondary`，二次掩盖）；`tools/export_previews/generate_screenshot_test_wrappers.py:122` 包装名由预览函数名派生，与 Gradle 清单**仅靠字符串约定耦合、无同步校验**；实读 `.github/workflows/build.yml:77` hygiene-gate 名称清单确认**不含**预览白名单检查；`export-preview-main.bat:42` 仅 `if exist`；`git log -S "mainScreenPreviewPrefixes" --all` 仅 2 提交（`RESOLVED_LOG` 无漂移条目）。
- **背景**：白名单预览改名 / 删除后该屏静默 0 命中，导出任务与全仓任何脚本 / CI 门禁均不校验逐屏命中数 ⇒ 主屏截图导出管道可静默退化。**当前接线完好**（20/20 静态匹配、全历史无漂移事故）。
  - **触发状态：dormant**——当前 include 模式全部命中、无错误效果产出；触发条件为未来任一白名单预览被改名 / 删除（此时 `:app:compileDebugScreenshotTestKotlin` 仍绿、包装照常生成，只有 Gradle include 模式失配，全链路静默）。
  - **未跑项**：未实际执行 `:app:exportMainPreviewScreenshots` / `updateDebugScreenshotTest`（会写 `build/` 与 `preview-exports/`）——「当前完好」判据是静态函数名核对 + §98 / §438 批次文档产物命名旁证。
- **涉及文件**：`app/build.gradle.kts:356` / `:396-397` / `:403-408` / `:420-421`、`tools/export_previews/generate_screenshot_test_wrappers.py`。
- **验收标准**：`doLast` 补**逐屏命中数断言**（每个 prefix 的 light + dark 各须 ≥1，缺任一即 `throw GradleException`），使「改名即变红」；或按 §98 既有做法把 20 这个数变为**程序化派生**（从真实预览函数名生成清单）而非人工维护。

### ISSUE-P3-510：S3 目录列举在缺 `NextContinuationToken` 时破坏 `RemoteListPage` KDoc 不变式

- **核实时间点**：2026-10-06；**核实方式**：实读 `sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:86-87`（`nextCursor = if (parsed.isTruncated) parsed.nextToken else null, truncated = parsed.isTruncated`）与 `:155`（`nextToken?.takeIf { it.isNotBlank() }` 连空白 token 也归 null ⇒ **矛盾对确定性产出**）；实读 `RemoteListModels.kt:26`（「`truncated` 为 `true` 时 `nextCursor` 非空」）；实读 `RemoteBrowseDialog.kt:291`（`if (truncated)` **无条件**渲染「加载更多」，`RemoteBrowseEntriesList` 仅接收 truncated、无 `nextCursor` 守卫）；实读 `RemoteBrowseSection.kt:59` → `SettingsViewModel.kt:132-136` → `SettingsRemoteBrowseHost.kt:85-88` 原样透传 → `RemoteBrowseController.kt:169`（以 null cursor 重列）与 `:188`（`if (cursor == null) accumulated = …` **累积清回第 1 页**、`lastError = null` 无报错）；对照 `WebDavDirectoryList.kt:87`（`nextCursor = if (truncated) pageItems.lastOrNull()?.name else null` 末项名兜底——**唯独 S3 信任服务器合规**）；测试目录 grep `IsTruncated` / `S3DirectoryList` / `parseListObjects` **零命中**。
- **背景**：矛盾对按构造产出（服务器回 `IsTruncated=true` 缺 / 空 token 即触发），UI 把破坏放大成「加载更多」空转 + 累积回缩、**全程无错误提示**。仅影响远端浏览通道，WebDAV 侧按构造免疫，同步数据路径不走此面。
  - **触发状态：dormant**——AWS ListObjectsV2 规范规定 `IsTruncated=true` 必带 `NextContinuationToken`，合规服务器不可达；引入提交 `a7850648`（§362）至今 git 历史 / `RESOLVED_LOG` / `ACTIVE_ISSUES` / 测试目录均无触发痕迹。
  - **未核实项（如实声明）**：①端点由用户任填，**无法从仓内排除**某个非规范 S3 兼容实现触发此路径，且 §362 批次自述「WebDAV/S3 真实服务器浏览未端到端联调」；②仓内无任何测试锁定此退化对的行为。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:86-87` / `:155`、`sync/.../RemoteListModels.kt:26`、app 侧 `RemoteBrowseDialog.kt:291` / `RemoteBrowseController.kt:169/:188`。
- **验收标准**：`nextCursor` 与 `truncated` 的取值须满足 KDoc 不变式（`truncated = nextCursor != null`，或在 token 缺失时降级 `truncated = false` 并给一次告警）；补宿主用例锁定 `IsTruncated=true` + 缺 / 空 token 的组合行为，并断言 UI 不再渲染可点的空转「加载更多」。

### ISSUE-P3-513：CM 通道 requestCode「避开带」KDoc 漏列次日新增的自动填充通道活常量 `REQUEST_CODE_SAVE=2300`

- **核实时间点**：2026-10-06；**核实方式**：实读 `CredentialPendingIntents.kt:73-74`（「基线 `1000` 刻意避开解锁 Action 旧常量区间与自动填充通道（`100`/`2001`/`2100`/`2200`）的取值段，便于日志与抓包中辨认」）、`:76`（`getAndIncrement()`）与 `:79/:82`（`AtomicInteger(REQUEST_CODE_BASE = 1000)`，进程级无界递增）、`:16-20`（本仓自述匹配键「`(requestCode, Intent.filterEquals)`（extras 不参与匹配）」）；实读 `KeePasskeyAutofillService.kt:471`（`internal const val REQUEST_CODE_SAVE = 2300`）与 `:429-439`（`Intent(this, PasswordSaveActivity::class.java)` 仅 putExtra + `FLAG_MUTABLE or FLAG_UPDATE_CURRENT`）；实读 `CredentialCreateEntries.kt:104-107`（passwordEntry 同为纯 extras Intent 指向 `PasswordSaveActivity`）与 `:151-157`（`entryPendingIntent` 取 `nextRequestCode()`）；确认 CM 通道其余分配点目标组件均不同（`CredentialResponseAssembler.kt:264` → `PasskeyAssertionActivity`、`:349-357` → `PasswordFillActivity`；`KeePasskeyCredentialProviderService.kt:151-155` → `CredentialUnlockActivity`）⇒ **`PasswordSaveActivity` 是唯一活的同组件重合点**；实读 `PasswordSaveActivity.kt:40-57` 确认同时消费 CM fillIn 与同名 `EXTRA_*` 键 ⇒ 覆写即跨通道串扰；实读守卫 `AutofillAuthResultWiringTest.kt:109-128`（只断言内部分配器与 3 处 `FLAG_MUTABLE`）与 `CredentialRequestCodeWiringTest.kt:30-44/76-107/109-131`（只断言 CM 通道内单调、基线 ≥1000、无残留常量）——**无一锁跨通道取值段**；`git log -S` 确认避开带 KDoc 引入于 `4d17f591`、`REQUEST_CODE_SAVE=2300` 引入于 `b4670f27`（**次日**），清单此后未扩充。
- **背景**：KDoc 声明的跨通道避开契约与实际活常量集漂移，且该缺口**无任何守卫覆盖**。**可灭火非不可达**：需单进程生命周期内 CM 通道恰好完成 ≥1301 次分配（`getAndIncrement` 首次返回 1000，2300 为第 1301 次）且该次恰为 passwordEntry，同时与存活的自动填充保存记录（system_server 持久保留至重启 / 卸载）重叠。
  - 报告如实修正两点：①避开带 KDoc 的自述目的是「便于日志与抓包中辨认」（**可读性口径**），其功能性价值（防同组件记录覆写）是合理引申而非原文声明——KDoc 所列常量（100/2001/2100/2200）的「避开」陈述本身**未被 2300 证伪**（缺口是漏列而非陈述为假）；②避开带还漏了 `REQUEST_CODE_INLINE_ATTRIBUTION=2002`（`AutofillInlinePresentationFactory.kt:91`），但组件不重合、无覆写风险。
  - **触发状态：dormant**——当前无任何覆写 / 串扰发生；无 git 历史 / `RESOLVED_LOG` 事故记录。
  - **未跑项**：纯静态审查，未做真机验证；「`filterEquals` 恒真」依据两处 Intent 构造逐字段比对（action / data / type / package / categories 全空、component 同类）与仓内 KDoc 自述语义。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt:73-74`、`app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:471`、app 侧 `CredentialCreateEntries.kt:104-110/151-157`、`app/src/test/.../CredentialRequestCodeWiringTest.kt`。
- **验收标准**：`CredentialPendingIntents.kt:73-74` 的避开带补列 `2300`（并复核是否补列 `2002`），或改写为**可机检的形式**——在 `CredentialRequestCodeWiringTest` 补一条跨通道取值段断言（扫描全部 `REQUEST_CODE_*` 常量，断言无一落在 CM 通道 `getAndIncrement` 的可达区间内），使「避开带」不再只是散文承诺。

## 459.2 前提复核（2026-10-06 直读）

- **P3-505 前提成立**：`DependencyResolutionDeterminismTest` 确只拦动态 / 快照版本；`libs.versions.toml` 钉版 `1.5.0-alpha28` 与 `composeBom=2026.09.00` 在位。**新增实测**：本地 Gradle 缓存中 `compose-bom-2026.09.00.pom` 的 `dependencyManagement` 里 `androidx.compose.material3:material3` = **1.4.0** < 钉版 ⇒ 当前零翻转（绿），与报告 `dependencyInsight` 读数一致。
- **P3-507 前提成立**：规则 `:49-51` 在位；报告方的 javap / sources / 零反射消费方三面证据本轮未重做（采信留痕）。
- **P3-509 前提成立**：20 项白名单与 `doLast` 只有总数打印在位。
- **P3-510 前提成立**：`nextCursor = if (parsed.isTruncated) parsed.nextToken else null` + `truncated = parsed.isTruncated` 在位，`:155` 空白 token 归 null ⇒ 矛盾对按构造产出；测试目录对该组合零覆盖。
- **P3-513 前提成立**：避开带 KDoc 只列 `100/2001/2100/2200`；**全仓扫描**（`grep -rn "REQUEST_CODE_[A-Z_]*" app/src/main/java`）实际活常量为 `100`（`AUTH_REQUEST_CODE_BASE`）/ `2001` / `2002` / `2100` / `2200` / `2300` / `2401`（legacy）/ `3001`~`3006`（通知）⇒ 漏列范围比条目所述**更大**（除 2300 外还有 2002 / 2401 / 3001-3006）。

## 459.3 整改

### ① P3-505：把版本目录的钉版约束接上执行点

`DependencyResolutionDeterminismTest` 新增 `material3 钉版不得被 BOM 映射静默改写` + `版本比较判据具备判别力` 两例，三条判据：
1. 钉版值必须匹配 `1.5.0-alphaN`（`toml:46`「仅允许 1.5.0-alphaN 内部上调，禁止跨 minor 跳跃」的机检化）；
2. `app/build.gradle.kts` 必须保留 `libs.compose.material3.alpha` 显式覆盖（双声明一旦消失，钉版行即失去效力）；
3. **BOM 实物比对**：从 Gradle 缓存（`GRADLE_USER_HOME` 或 `~/.gradle`，`caches/modules-2/files-2.1/androidx.compose/compose-bom/<version>/**`）定位 `compose-bom-<composeBom>.pom`，解析其 `dependencyManagement` 中 `androidx.compose.material3:material3` 的托管版本，断言**不高于**钉版；**fail-closed**——定位不到 POM 即 `error`（`test` 必然已解析过依赖，POM 应当在缓存中）。
版本比较实现语义化（core 三段数字 → 无预发布 > 有预发布 → 预发布分段逐级比较），并配 6 条已知值反校（stable > alpha / 跨 minor / alpha 序号 / 同值 / beta > alpha / patch 位）。

### ② P3-507：删除 no-op 规则 + 新建实物对照守卫

- 删除 `proguard-rules.pro` 的 `PublicSuffixDatabase` `-keepclassmembers` 规则，原位留注释记录核实结论（okhttp-android 5.5.0 无 native 方法 / 无 `findSuffix`）、删除依据与零影响面。
- 新建 `app/src/test/java/com/keepasskey/app/log/ProguardRuleRealityTest`（3 例）：
  1. **精确类名的 keep 规则必须在依赖实物中真实存在**——解析规则文件中所有完全限定类名目标（排除 `**` / `* extends …` 通配），逐个 `Class.forName`；含防空扫断言（≥3 条）；
  2. **已删除的 no-op 规则在依赖实物中确实不存在**——反射取 `PublicSuffixDatabase.declaredMethods`，若将来真的出现 `findSuffix` 则**红并提示恢复规则**（复活条件反查，fail-closed）；
  3. **已删除的 no-op 规则不得复活**——规则正文（剥注释后）不得再出现 `findSuffix`。
  注释剥离为两级：先 `stripCommentsOnly`（Kotlin 风格）再剥 ProGuard 的 `#` 行注释——**否则本批新写的删除说明注释会造成「规则仍在」的假命中**（该陷阱在首跑中真实触发过一次，见 459.4）。

### ③ P3-509：导出任务补逐屏命中断言

`exportMainPreviewScreenshots` 的 `doLast` 在原有 light/dark 计数之前加**逐屏断言**：对 `mainScreenPreviewPrefixes` 的每一项，检查 `preview-exports/main/{light,dark}` 下是否存在以该 prefix 开头的 `.png`；缺任一即 `throw GradleException`（错误消息写明「0 命中不报错」的机理与处置：同步清单与真实预览函数名）。成功日志追加「逐屏断言 20 × 2 全命中」。`app/build.gradle.kts` 补 `import org.gradle.api.GradleException`。

### ④ P3-510：缺 / 空 token 时降级为未截断（含告警）

`S3DirectoryList.parseListObjects`：`nextToken` 提前 `takeIf { it.isNotBlank() }`，并新增 `effectiveTruncated = isTruncated && nextToken != null`；当服务器声明 `IsTruncated=true` 却无有效 token 时**降级为未截断**并打一条 `AppLog.w`（写明「非规范端点」与「不再渲染空转的『加载更多』」）。
取舍说明（写进代码注释）：UI 只看 `truncated` ⇒ 不变式成立即不再渲染空转按钮；宁可少给「加载更多」，也不给一个必然空转、还会把累积结果清回第 1 页并静默吞错的入口。
新增宿主用例 `S3DirectoryListTruncationTest` 5 例：`true`+有效 token 保留分页入口 / `true`+缺 token 降级 / `true`+空白 token 降级 / `false` 不声明截断 / **四种形态合取不变式**（`!isTruncated || nextToken != null`）。

### ⑤ P3-513：避开带改为机检锚点（并补全漏列清单）

`CredentialPendingIntents.nextRequestCode` 的 KDoc 补「**避开带（取值段声明）**」小节：以 `AVOID-BAND: …` 行承载**全仓实际活常量**清单（`100, 2001, 2002, 2100, 2200, 2300, 2401, 3001…3006`），并**如实声明避开带只是可读性口径、不是防覆写边界**（`getAndIncrement` 无上界，终将跨越任何有限常量；自动填充侧认证入口分配器同样自 `100` 无界递增；真正阻止同 requestCode + 同组件覆写的是**目标组件 / Intent 差异**，已知唯一活的跨通道同组件重合点是 `PasswordSaveActivity`）。
`CredentialRequestCodeWiringTest` 新增 2 例：解析 `AVOID-BAND:` 行（**要求该行含数字**，避免命中说明文字行）与全仓扫描到的 `REQUEST_CODE_*` 常量集合**双向比对**（漏列即红、残留已删值即红），并断言 CM 自身基线 1000 不在避开带内。

## 459.4 验证

### ① 宿主用例（实跑）

```
:app:testDebugUnitTest --tests ProguardRuleRealityTest --tests DependencyResolutionDeterminismTest
                       --tests CredentialRequestCodeWiringTest   ⇒  BUILD SUCCESSFUL（14 例中首轮 2 红，见下）
:app:testDebugUnitTest --tests ProguardRuleRealityTest --tests CredentialRequestCodeWiringTest
                                                                ⇒  BUILD SUCCESSFUL
:sync:test（整模块，含新增 S3DirectoryListTruncationTest 5 例）  ⇒  BUILD SUCCESSFUL
```

**首轮两红均为本批新守卫的自身缺陷、当场修好（过程留痕）**：
1. `ProguardRuleRealityTest`「已删除的 no-op 规则不得复活」红——原因正是**本批新写的删除说明注释里出现了 `findSuffix` 字样**，而既有 `stripCommentsOnly` 只剥 Kotlin 风格注释、不剥 ProGuard 的 `#` ⇒ 修法为补第二级 `#` 注释剥离（459.3② 末段）。
2. `CredentialRequestCodeWiringTest`「避开带清单必须覆盖…」红——`firstOrNull { it.contains("AVOID-BAND:") }` 命中的是**说明文字行**（「以 `AVOID-BAND:` 行承载」）而非清单行 ⇒ 修法为加「该行须含数字」的合取条件。

### ② release 构建（R8 真实接受规则删除）

```
$ ./gradlew.bat assembleRelease
> Task :app:minifyReleaseWithR8
…
BUILD SUCCESSFUL in 9m 27s（216 actionable tasks: 212 executed）
```

⇒ 删除该 no-op 规则后 R8 真实接受、发布包正常产出。

### ③ 预览导出逐屏断言（实跑 + 坏样本反校）

正路径（真实渲染 119 个包装、`:app:updateDebugScreenshotTest` 全量跑通）：

```
$ ./gradlew.bat :app:exportMainPreviewScreenshots
> Task :app:updateDebugScreenshotTest
> Task :app:exportMainPreviewScreenshots
已导出 主页面: light=20 dark=20（逐屏断言 20 × 2 全命中） → D:\GithubWorkplace\KeePasskey\preview-exports\main
BUILD SUCCESSFUL in 1m 58s
```

**坏样本反校**（证明断言有判别力，而非恒真）：把白名单首项临时改为
`UnlockContentPreviewScreenshotExportRENAMED`（模拟「预览函数改名而清单未同步」）后重跑 ⇒

```
> Task :app:exportMainPreviewScreenshots FAILED
* What went wrong:
> 主页面预览导出**逐屏断言失败**：以下白名单预览在对应主题下零命中（共 2 项）：
  light/UnlockContentPreviewScreenshotExportRENAMED, dark/UnlockContentPreviewScreenshotExportRENAMED。…
BUILD FAILED in 1m 41s
```

⇒ **改名即变红**（条目 AC 的原话）；反校后清单已还原（`grep -c RENAMED app/build.gradle.kts` = 0）。

### 门禁读数（`gate_readings.py`，原样粘贴）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 457 份；分册登记 459 条；全量索引 459 条；最大 §459）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 581 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

宿主单测读数（全量 `.\gradlew.bat test --rerun-tasks --max-workers=1` 后 `count_test_results.py`）：`xml=523 tests=3405 failures=0 errors=0 skipped=13`（§455 基线 `3393` ⇒ **+12**：app 侧 `ProguardRuleRealityTest` 3 + `DependencyResolutionDeterminismTest` 2 + `CredentialRequestCodeWiringTest` 2 = 7，sync 侧 `S3DirectoryListTruncationTest` 5）。同轮 `preserve_test_failures.py` ⇒ `无失败用例（已扫描 523 份 XML，无需留痕）`。全量 `test` **BUILD SUCCESSFUL**（12m22s，114 tasks）。

### 真机走查（Redmi 4X / LineageOS / `1c859bcc7d24`）

`assembleDebug`（34s）+ `adb install -r` 后跑装机前置读数：

```
$ python tools/device/check_installed_build.py --expect-symbol SettingsUiState
本地 APK        = app\build\outputs\apk\debug\app-debug.apk
  构建时间      = 2026-10-07 05:05:01
设备安装时间    = 2026-10-07 05:05:03  （包 com.keepasskey.debug）
✓ 设备安装时间不早于本地 APK
✓ APK 内符号 SettingsUiState：在
--- check_installed_build=OK ---
```

随后实跑 `:sync:connectedDebugAndroidTest`（37s）与 `:app:connectedDebugAndroidTest`（3m21s），结果读数：

```
=== 设备侧（instrumented）结果非空转断言（ISSUE-P3-493）===
  ✓ app       xml=1 tests=68 failures=0 errors=0 skipped=0
  ✓ sync      xml=1 tests=25 failures=0 errors=0 skipped=0
```

（上表只列本批实跑的两层；`check_connected_device_results.py` 另打印的 `core 4` / `crypto 37` / `database 18` 三层
结果 XML 为 **2026-10-06 的历史遗留文件**，本批未重跑该三层，不属本批读数。）

**如实声明**：本批**未**新增 / 修改 `src/androidTest` 用例，跑这两层是为覆盖本批唯一的生产行为改动
（`S3DirectoryList` 截断降级）与 `SettingsUiState` 字段删除面；`--expect-symbol` 只能传**既有**符号
——本批未新增 app 生产类 / 方法（改动为注释与删字段），故该判据只证明「装的是刚打的这份包」，
不承担「新代码在包里」的证明（后者在本批无对象可验）。

## 459.5 如实声明

- **P3-505**：BOM 映射比对依赖**本地 Gradle 缓存中的 POM**；CI 干净环境首次跑 `:app:test` 时依赖已解析 ⇒ POM 应在缓存中，但若缓存布局变化会 fail-closed 变红（属有意设计，宁红勿假绿）。当前实测值：BOM `2026.09.00` 映射 `1.4.0` < 钉版 `1.5.0-alpha28` ⇒ 绿。
- **P3-507**：no-op 的核心结论（javap + sources grep + 零反射消费方）**采信报告方留痕**，本轮未重做解包；`-dontnote **` 是否吞掉 R8 未匹配提示**仍未做 R8 A/B 构建证实**（与条目登记一致）。
- **P3-510**：降级为「未截断」意味着**非规范端点下用户不会看到「加载更多」**（目录可能不完整且无提示）——这是条目给出的两个方案之一，取它是因为另一方案（保留 `truncated=true`）会产出必然空转且回缩已得结果的入口。真实非规范 S3 端点**无实证样本**。
- **P3-513**：避开带机检化只锁「清单与代码不漂移」，**不改运行时行为**（未调整 `REQUEST_CODE_SAVE` 取值、未给 Intent 加区分标记）；跨通道同组件覆写的**根治**需 Intent 差异化，属独立改动、本批未做（已在 KDoc 写明）。
- 本批**未新增 / 修改设备侧用例** ⇒ 无真机实跑义务（照 `AGENTS.md` §3 测试资产纪律）。
