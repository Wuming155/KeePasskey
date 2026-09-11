# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：本批次发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md`、`plans/rust-enclave-poc.md` 等文件「需要删除」，但这些文件早在
   > `7dba64d`（重构文档体系）与 `d578df7` / `863d81c` 中就已删除；`AGENTS.md` 也已不含相关引用。
   > 条目与代码库演进之间存在时间差，会导致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。
   前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|---|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级与致命安全漏洞（0 项）

> 当前无待办。

---

## P1 高危与核心功能问题（1 项）

> **2026-09-11 追加（零信任全量安全审计批次）**：新登记 **ISSUE-P1-11**（自动填充「受信浏览器」
> 白名单仅按包名信任，未校验签名证书——详见条目）。
> 历史 P1 项（含 ISSUE-P1-10 / ZT-10）已全部闭环，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.5。

---

### ISSUE-P1-11 (新登记): 自动填充「受信浏览器」白名单仅按包名匹配、未校验签名证书，可绕过 webDomain 归属校验泄露任意站点凭据

- **优先级**：P1（高危安全缺陷：跨应用凭据泄露面；零信任审计 #1，严重度 HIGH）
- **核实时间点与核实方式（2026-09-11）**：独立复读
  `app/src/main/java/com/keepasskey/app/autofill/AutofillWebDomainPolicy.kt`（全文）与
  `app/src/main/java/com/keepasskey/app/autofill/AutofillOriginResolver.kt`（全文）复核成立：
  ① `AutofillWebDomainPolicy.kt:81-82` `isTrustedBrowser` 仅做包名精确匹配（大小写不敏感），
  `:97` 命中即 `BROWSER_DELEGATED`；② `AutofillOriginResolver.kt:34` 在读取调用方签名证书指纹
  （同类 `callingAppCertSha256Hex`，`:58-69` 已具备该能力）**之前**就对受信浏览器 `return domain`。
- **背景与攻击链**：设计上非浏览器应用必须经 DAL（`assetlinks.json` 声明包名+证书指纹）才能认领
  `webDomain`；但浏览器分支只看包名。Android 不阻拦侧载占用**未安装**浏览器包名（如 `com.chrome.beta`、
  `org.chromium.chrome`、`com.heytap.browser`）的 APK：攻击者应用在其自控 WebView 中打开目标站点触发
  自动填充 → `webDomain` 归属判定命中白名单 → 候选按域匹配下发 → 用户一次确认后明文凭据写入攻击者
  WebView 表单。这与 Credential Manager 侧 `CallingOriginResolver` 的「包名+证书指纹」双校验形成同仓
  明显落差。缓解事实：下发前有确认对话框展示凭据标题，但攻击者上下文内诱导确认门槛低，不足以降级。
- **修复方向**：浏览器分支同样校验签名证书指纹，白名单升级为「包名+指纹」二元组；
  对白名单外浏览器 fail-closed 降级为既有 DAL 路径。证书指纹初始值取各浏览器官方发布 APK 的
  签名证书 SHA-256，**每个指纹须注明来源与核实方式**。
- **验收标准**：① 新增单测：占用白名单包名但签名不匹配的调用方 → `resolveUsableWebDomain` 返回 null
  （不下发域候选）；② 签名匹配的受信浏览器维持 `BROWSER_DELEGATED`；③ 白名单外浏览器未过 DAL 时
  返回 null（既有行为不回归）；④ `.\gradlew.bat test` 全绿。
- **禁止**：以「功能可用性」为由保留仅包名匹配的兜底分支；在未核实来源的情况下臆写指纹常量。

---

## P2 中危缺陷与协议/测试缺口（2 项）

> ISSUE-P2-05 ~ ISSUE-P2-13 九项已全部整改并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.16 ~ §2.20。
> P2-12 整改中如实登记的残余面 ISSUE-P2-15 / ISSUE-P2-16（受保护值经 String 退化、
> 密码生成引擎出边界仍返回 String）已整改并归档，见 §2.21。
> **2026-09-11 追加（零信任全量安全审计批次）**：新登记 **ISSUE-P2-17**（子库解锁无节流）与
> **ISSUE-P2-18**（同步防回滚缺失）——详见条目。

---

### ISSUE-P2-17 (新登记): 子库解锁路径未接入解锁节流，构成针对子库口令的在线爆破面

- **优先级**：P2（中危安全缺陷；零信任审计 #2，严重度 MEDIUM）
- **核实时间点与核实方式（2026-09-11）**：全仓 `grep UnlockThrottle` 复核——生产消费方仅
  `UnlockViewModel`（其余为 DI 接线与测试）；复读
  `app/src/main/java/com/keepasskey/app/data/childdb/ChildReadOnlySession.kt:131-147`
  （`loadProjection` 直接 `KdbxFile.load`，无闸门调用、失败不登记）成立。
- **背景**：`UnlockThrottleManager`（gate / registerFailure / registerSuccess）全链路只被主库解锁
  ViewModel 消费；子库口令输入 → `open()` → `loadProjection()` → `KdbxFile.load` 完全绕过节流与计次，
  可对子库口令反复在线试探。缓解因素：KDF 计算成本构成物理节流，但尝试次数无界。
- **修复方向**：节流下沉到领域层（`DatabaseSession`/仓库层），按 `mountId`（或子库标识）走
  `UnlockThrottleManager` 三原子操作；UI 层仅保留提示职责。**保留既有语义**：仅认证失败
  （凭据被拒）计次，IO/文件损坏等非认证错误不计次（分流由原 ViewModel 承担，下沉后不得丢失）。
- **验收标准**：① 子库口令连续失败达 `FAILURE_THRESHOLD` 后触发指数退避，锁定期内不进入
  `KdbxFile.load`；② 成功解锁清零计数；③ 非认证失败不计次；④ 单测覆盖上述三分支 + 全量 test 绿。

---

### ISSUE-P2-18 (新登记): 同步缺少防回滚绑定，被入侵云端可重放旧的合法 `.kdbx` 覆盖本地

- **优先级**：P2（中危安全缺陷；零信任审计 #3，严重度 MEDIUM；**设计类任务，先出方案再实施**）
- **核实时间点与核实方式（2026-09-11）**：复读
  `sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt` `openRemote`（约 L100-200）成立；
  另对 sync 模块全文检索 `回滚|rollback|monoton|revision`——仅命中 WebDAV 事务写自身的临时文件回滚注释，
  **无任何本地认证的单调版本校验机制**。
- **背景**：`openRemote` 的「三哈希状态机」（baseEtag / baseVersionHash / 内容哈希）只解决并发一致性
  与数据丢失，不解决**版本回退**：恶意/被入侵的 WebDAV/S3 端点返回一份「旧的但仍能用主凭据解密」的库
  即被采纳（「远端 ≠ base 即下载应用」），可复活已删条目、回退已更新字段。在 Assume Breach 威胁模型下
  「云端不可信」是既定假设，此为真实盲点。
- **修复方向（须先评估再动手）**：引入由本地主密钥（或设备侧 Keystore 密钥）认证的单调版本/清单绑定，
  拒绝低于设备侧已见版本的远端内容。**跨端兼容决策必须先行评估并留痕于本条目内**：其他官方客户端
  （KeePass 2.x / KeePassDX / KeePassXC）写入的库如何处理（回退检测对它们降级为提示还是阻断）。
- **验收标准**：① 旧库重放场景（模拟被入侵端点返回旧版本）被拒绝并给出明确用户提示；
  ② 正常多端同步（含其他客户端写入）不误报；③ 方案与跨端兼容决策记录在本条目内（不另建计划文件）；
  ④ 全量 test 绿。
- **禁止**：以内容哈希或 ETag 冒充版本号；在未评估跨端兼容的情况下直接阻断其他客户端写入的合法更新。

## P3 低危问题、特性接线与体验优化（8 项）

> **背景**：P3 残余批次原 **12 项**（ISSUE-P3-17 ~ P3-28）已于 **2026-09-10** 整体整改。
> 其中 **10 项完整闭环并归档**（P3-17 / 18 / 19 / **20** / 21 / 22 / 25 / 26 / 27 / 28，含逐项代码证据与 15 条过程缺陷留痕），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§4**；**2 项部分达标**（P3-23 / P3-24 —— 均为**本环境物理不可达**的
> 验证类条目：arm64 设备与 GitHub runner）就地更新后保留于本节；
> 另有 **2 项新登记**（**ISSUE-P3-29** 全仓超阈值债务、**ISSUE-P3-30** 子库条目投影未并入根库列表）。
> **2026-09-10 追加一**：**ISSUE-P3-30 已闭环并归档**（子库条目只读投影接入库列表，见 §5）。
> **2026-09-10 追加二**：**ISSUE-P3-29 批次 A 已闭环并归档**（见 §6）——
> 条目正文点名的**优先级 8 项全部降至 400 行阈值内**（`SettingsViewModel` / `VaultListViewModel` /
> `DatabaseSettingsDialogs` / `KdbxFile` / `KeePasskeyApp` / `VaultEntryRows` / `VaultRepository` / `VaultListScreen`），
> 另**增量完成** `KdbxHeader` 与 `SyncCredentialsStore` 两项，并登记 `DicewareWordList` 为**经论证的纯常量例外**；
> 仓库内残余的 **23 个**真逻辑超阈值文件**整体转入 ISSUE-P3-31** 重新登记（附 2026-09-10 实测快照与核实方式）。
> **2026-09-10 追加三（CI 首跑实测 + 供应链残余处置）**：新增 **ISSUE-P3-32**（供应链达阈告警残余）；
> 同一批次内**已闭环**的工作见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§7**：
> `Fast gate` 147 个 lint error 清零、CodeQL 10 条告警处置、`dependency-scan` 的 CVSS 阻断语义
> **静默失效**之实证与硬断言补强、**Kotlin 2.4.20 真修复 `CVE-2026-53914` + 4 族豁免登记**
> （本地真实扫描实测 **188 → 7 条实例、达阈 138 → 0 条**）、以及**合并 PR #5**
> （原 ISSUE-P3-33，已闭环归档）；实测校准追加节见 [docs/ci-静态校准记录.md](ci-静态校准记录.md) **§11**。
> **2026-09-10 追加四（原生内核与工程化扩展批次）**：经全仓性能/架构评审（逐模块读源）新登记的
> **ISSUE-P3-34 ~ P3-38** 五项（AES-KDF 原生内核 / Twofish 原生内核 / 密码强度评估原生引擎 /
> `HmacBlockStream` 摘要收敛 / `.kdbx` 互操作语料生成脚本自动化）**已于同批次全部闭环并归档**，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§8**——Rust 单测 9 → **43**、全仓用例 1200 → **1257**、
> `crypto` lint 告警 4 → **0**；该节如实留痕 **9 条过程缺陷/事实修正**（含 1 处生产缺陷、
> 1 处安全校验冒充漏洞、1 处文档与实测相反的事实修正）与 **3 项未验证项**。
> **2026-09-10 追加六（自动填充能力对标批次整改）**：上述「追加五」登记的 P3-39 ~ P3-45 七项中，
> **6 项已实现并归档**——P3-39（字段识别与候选打分/上次填充优先）、P3-40（手动选择器）、
> P3-41（服务健康自检）、P3-42（会话授权宽限）、P3-44（保存开关真实接线 + 评估结论）、
> P3-45（结构化数据可行性评估结论），见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§9**；
> **P3-43 部分达标**：① 已接线既有 `overrideNoAutofill`（尊重 `importantForAutofill`，同时消除一处
> 假开关），② 字段签名级屏蔽 与 ③ 保存侧独立黑名单 两项因**缺少用户交互写入入口**，
> 就地保留待办并已重写验收标准（严禁先落库无写入方的存储 API）。
> **2026-09-10 追加七（P3-31 批次 B）**：ISSUE-P3-31 验收标准 1 点名的两项
> `RealVaultRepository`（1090 → 372）与 `DatabasePickerScreen`（968 → 319）已按纯结构性拆分完成并归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§10**。
> **2026-09-10 追加八（P3-31 批次 C + P3-43 闭环）**：批次 C 三项
> （`ThemeSettingsScreen` 762 → 155 / `EntryEditScreen` 712 → 388 / `EntryDetailViewModel` 708 → 399）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§11**；**ISSUE-P3-31 本条未闭环**（残余 18 项），清单已就地刷新。
> 同批次 **ISSUE-P3-43 全部闭环并归档**（② 字段签名级屏蔽 / ③ 保存侧独立黑名单，
> 三件套一次性交付，见 §11.3 / §11.4），本文件中该条目已移除；
> 同批次顺带发现并修复一处既有生产缺陷（详情页 `passwordStrengthBits` 无写入方 → 强度条恒不渲染，见 §11.5），
> 其签名抗枚举加固残余新登记为 **ISSUE-P3-46**。
> **2026-09-10 追加九（P3-31 批次 D + P3-46 闭环）**：批次 D 三项
> （`DatabaseSession` 697 → 393 / `SecuritySettingsScreen` 674 → 371 / `KeePasskeyAutofillService` 634 → 334）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§12**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **15 项**，清单已就地刷新）。
> 同批次 **ISSUE-P3-46 全部闭环并归档**（§12）：字段签名密钥来源改为 Android Keystore 内
> **不可导出 HMAC 密钥**，schema `v1 → v2`、盐不再落盘、旧数据一次性保守失效，本文件中该条目已移除。
> **2026-09-10 追加十（P3-31 批次 E）**：验收标准 1 点名的下一批三项
> （`S3SyncProvider` 629 → 372 / `PasskeyCryptoEngine` 596 → 362 / `KdbxMerger` 578 → 207）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§13**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **12 项**，清单已就地刷新）。
> **2026-09-10 追加十一（P3-31 批次 F）**：验收标准 1 点名的下一批三项
> （`SettingsScreen` 569 → 306 / `UnlockScreen` 562 → 275 / `GeneratorScreen` 550 → 177）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§14**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **9 项**，清单已就地刷新）。
> **2026-09-10 追加十二（P3-31 批次 G）**：验收标准 1 点名的下一批三项
> （`WebDavSyncProvider` 510 → 345 / `AutofillSettingsScreen` 504 → 216 / `EntryEditComponents` 494 → 296）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§15**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **6 项**，清单已就地刷新）。
> **2026-09-10 追加十三（P3-31 批次 H）**：验收标准 1 点名的下一批三项
> （`CloudSyncComponents` 481 → 291 / `EntryDetailComponents` 477 → 223 / `EntryEditViewModel` 470 → 400）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§16**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **3 项**，清单已就地刷新）。
> **2026-09-10 追加十四（P3-31 批次 I · 本条闭环归档）**：验收标准点名的最后三项
> （`KeystoreManager` 462 → 239 / `HealthCheckScreen` 453 → 315 / `SyncEngine` 443 → 316）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§17**；
> 门禁 `test --rerun-tasks` **1329 例 / 0 失败 / 13 跳过**、`lint` 5 模块 0 error，
> 公开 API **零丢失零新增**。**全仓重测后 `> 400` 仅剩 2 项，且均为经论证的例外**
> （纯常量词表 `DicewareWordList` 408、因 ISSUE-P3-43 接线产生功能性增量的 `SettingsViewModel` 424），
> 故 **ISSUE-P3-31 达成闭环并整条移出本文件**。
> 本节余 **3 项**（P3-23 / P3-24 / P3-32）。
> **2026-09-11 追加（功能完整性审计批次 A + B）**：以「README 声称功能 → 引擎/仓库 → ViewModel/控制器 → UI 入口」
> 四层逐项做端到端接线审计，两批共发现并**同日整改归档 5 项**（A：全文搜索范围、详情页单条删除；
> B：HOTP 端到端、单条移动分组 / 从模板新建便利入口、`AttachmentManager` 孤儿实现清理），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§20 / §21**（批次 B 验收 1345 例 / 0 失败 / 13 跳过）；
> 本文件中 P3-47 ~ P3-51 均已移出。
> **2026-09-11 追加二（零信任全量安全审计批次）**：对 `app/ core/ crypto/ database/ sync/` 全量
> `src/main`（含 `crypto/src/main/rust/`）按零信任五支柱做静态审计（Assume Breach 威胁模型，
> **未做动态/运行时验证**），7 项正式发现经逐条独立复读源码复核**全部属实、无误报**，按严重度分级登记：
> **ISSUE-P1-11**（P1）、**ISSUE-P2-17 / P2-18**（P2）、**ISSUE-P3-52 ~ P3-55**（P3），
> 次要加固项打包登记为 **ISSUE-P3-56**；各项核实时间点与核实方式见条目内。
> 归档门禁证据（2026-09-10 批次 I 实测，`--rerun-tasks` 强制真实执行）：
> `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**，
> **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；
> 纯结构性拆分，用例数与批次 H 基线持平）；
> `lint`（5 模块 **0 error**）通过；`:database:assembleDebugAndroidTest` 通过（ISSUE-P3-23 验收标准①）。

### 前提复核记录（2026-09-10，依「条目维护规则」第 2 条）

> 已对本节全部条目完成一次前提复核：**核实时间点 2026-09-10**。**逐条的核实方式记录在各条目自身的
> 「核实时间点与核实方式」段落内**（ISSUE-P3-28 确立的格式），此处不再重复列表。
> **复核结论**：P3-23 的「语料未入库」成立，但其「`database` 无 `androidTest` 源集」已不成立
> （该源集已建立并接线），已在条目内就地标注；P3-24 前提完整成立。
> **2026-09-10 追加八复核结论**：P3-31 批次 C 前提成立（三项行数经 `wc -l` 复核与清单一致）；
> P3-43 的两项待办前提成立（`AutofillBlocklistStore` 仍无字段级/保存侧能力，手动选择器已在位可作交互落点）。
> **P3-43 已于同批次闭环归档**（见 §11），条目移出本文件。
> **P3-30 已于 2026-09-10 归档**（前提「生产消费方为零」在开工时成立，整改后消费方落地，见 §5）。
> **P3-29 已于 2026-09-10 批次 A 闭环归档**（见 §6）；其残余 23 项已按「新增条目须附核实时间点与核实方式」
> 转入本节 **ISSUE-P3-31**（2026-09-10 经 `wc -l` 全仓复核）；**该条亦已于同批次 B~I 全部拆分闭环**
> （见 §10~§17），本文件不再保留该条目。

> **ISSUE-P3-20 已闭环**（子库挂载 **UI 接线**：`childDatabasesCount` 去硬编码并接真实 `mountedCount`；
> `ChildDatabaseDialog` 成为真实入口并调用核心层 `mount`/`open`/`unmount`；两条失真文案随能力上线删除；
> 新增 26 例单测），归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §4.2。
> 其接线过程中**由执行者如实发现的「过度声明」残余面**即 **ISSUE-P3-30**，已于 §5 闭环归档
> （投影接入库列表，且只读 / 不并入根库 / 不参与搜索与自动填充）。
>
> **ISSUE-P3-25 已闭环**（3 个点名文件全部降至阈值内：`SyncCoordinator` 965→254、`KdbxXmlGroupReader`
> 407→218、`UnlockViewModel` 979→396）；其「全仓扫描」暴露的整体债务经 **ISSUE-P3-29** 承接，
> 该条**批次 A 已完成优先级 8 项并归档**（见 §6），残余 23 项由 **ISSUE-P3-31** 承接，
> **该条已于 2026-09-10 批次 B~I 全部拆分闭环**（见 §10~§17）——巨型类债务至此清零，
> 全仓 `> 400` 仅余两项**经论证的例外**。


### ISSUE-P3-23 (P3-11 残余): arm64 真机 instrumented 验证与真实 `.kdbx` 语料端到端解锁

- **优先级**：P3（验证覆盖；依赖外部设备与语料资源）
- **核实时间点与核实方式（2026-09-10）**：经 `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse`
  （已安装 system-image 仅 `android-34` / `android-36.1` 的 **x86_64**，**无任何 arm64-v8a**）、
  `adb devices -l`（**空列表**）、`sdkmanager --list_installed`、`emulator.exe -list-avds` +
  `Pixel_10.avd\config.ini`（`abi.type=x86_64`）、`Test-NetConnection dl.google.com -Port 443`（True）、
  `sdkmanager --list | Select-String "system-images;android-36.1;.*arm64"`（**远端有发布、本机未安装**）
  逐项核实；并核实 `database/src/androidTest/**` 与 `database/build.gradle.kts:41-46` 落盘内容。
- **本批次已消除的阻塞（工程侧就绪，非验证结果）**：
  1. `database` 模块**首次建立 `androidTest` 源集与依赖接线**（`database/build.gradle.kts:10-15`、`:41-46`）；
  2. 设备侧端到端解锁用例落地 `RealKdbxCorpusUnlockTest`，**fail-closed**：语料缺失 → `Assume` 显式跳过
     （跳过消息自解释到「照哪个文件生成、放到哪里」，且**明确声明跳过不代表验收达成**）；
     语料在而伴生元数据缺失/非法（含 `containsRealData=true`、`passphraseIsThrowaway=false`、
     `source` 非 KeePass 系、`kdf` 非法、`entryCount<=0`）→ **硬失败**（「有 .kdbx 但无从断言」不可能蒙混成绿）；
  3. `SelfGeneratedRoundTripInstrumentedTest` **四处**声明「不构成与 KeePass 2.61.1 / KeePassXC 的互操作证据」
     （类名 / KDoc / 方法名 / stdout）；
  4. 语料逐步生成清单写入 `crypto/src/test/resources/argon2-interop/README.md`（KeePass 2.61.1 七步 +
     KeePassXC + 双落位 `src/test/resources` ↔ `androidTest/assets` **不可互替** + 伴生 JSON schema + 命名约定），
     并声明语料口令为公开一次性常量、**严禁任何真实主密码**；
  5. `docs/原生Argon2真机验证记录.md` 已加注本次范围与「未取得」项（x86_64 既有数据**原样保留**）。
- **仍未达成的残余面**：
  1. **arm64 真机 / arm64 模拟器数据未取得** —— 本机无 arm64-v8a 镜像、无真机连接；安装 arm64 镜像属大体积下载
     （超出本批次范围），且 x86_64 宿主上的 arm64 模拟器数据按纪律**不得**与「arm64 真机」同表登记；
     `docs/原生Argon2真机验证记录.md` §4.3 表格**保持待填**；
  2. **真实 KeePass 2.61.1 / KeePassXC `.kdbx` 语料仍未入库** —— 需人工 GUI 建库 + 逐条复核，无人值守流程无法产出；
     语料未入库前设备侧用例按设计**跳过**（注意：**该项不依赖 arm64**，现有 x86_64 AVD 即可执行）。
- **2026-09-11 复核（阻塞前提再确认；核实方式：读 `tools/kdbx-corpus/README.md` §3 并实跑脚本）**：
  1. `python tools/kdbx-corpus/generate_corpus.py --check` 实测 **exit 3**：本机无 `keepassxc-cli`；
  2. 且即便安装，README §3 明确 **KeePassXC CLI `db-create` 无法设定 Argon2 变体/版本与 t/m/p**，
     故真实语料**必须由官方 GUI 建库**（脚本 `--ingest` 只做复核 / 命名 / 写伴生 JSON / 双落位）——
     「无人值守产出真实语料」在本环境**不成立**（推翻先前「脚本自动化已可产出」的期待）；
  3. 脚本自身能力已本地实测通过：`--dry-run` 正常列出双落位目录；`--verify database/src/test/resources/fixtures/test_vault.kdbx`
     正确解析 `argon2d / v19 / t=89 / m=65536KiB / p=4 / 32B salt`，与 `crypto/.../argon2-interop/README.md` §5.1 逐项一致。
  **结论**：本条两处阻塞（GUI 语料、arm64 真机）均为**外部资源依赖**，本地不可消除。
- **验收标准**：① `.\gradlew.bat :database:assembleDebugAndroidTest` 编译通过（**2026-09-10 已实测通过**，
  见批次 A 归档 §6.4 门禁证据）；② 真实语料（含同名 `.json`）
  入库两处后 `:database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest` **不再是 skip** 且全绿；
  ③ arm64 真机（`adb shell getprop ro.product.cpu.abi` = `arm64-v8a`）上取到 §4.3 表格数据并与 §4.1/§4.2 **分表**归档；
  ④ 归档文件「待填」表按实际情况回填（**严禁编造数据**）。
- **禁止**：以 x86_64 模拟器或宿主侧数据填充 §4.3；把「用例就绪」表述为「验证通过」；
  用自生成 `.kdbx` 往返冒充互操作证据。

---

### ISSUE-P3-24 (P3-09 残余): CI 门禁首跑校准

- **优先级**：P3（供应链安全；依赖联网 CI 环境）
- **核实时间点与核实方式（2026-09-10）**：完整留痕见新建 **`docs/ci-静态校准记录.md`**
  （每项结论均附核实时间点与核实方式）。本批次**已修 3 处「首次必红」缺陷**：
  1. `build.yml:52` `platforms;android-37` → **`android-37.0`**（`sdkmanager --list --channel=0` 与本机
     `platforms/android-37.0/package.xml` 双证据：远端**不存在** `platforms;android-37`）；
  2. 签名断言**两处必然误红**：`apksigner` 不自动读同目录 `.idsig`（不传参时 v4 恒 `false`）→ 补
     `--v4-signature-file "${apk}.idsig"`；且 minSdk 36≥28 且 v3 同开时 AGP **省略 v2 块**（`AGENTS.md` §1 基线）
     → 原「v2:true」断言**不可能成立**，改为「v2 块必须缺席（出现即 error）」并新增 `v1: false` 断言（**未削弱**）；
  3. JDK 17 → 21，与 `gradle/gradle-daemon-jvm.properties: toolchainVersion=21` 对齐
     （**残留不确定性已如实标注**：Gradle 对 daemon JVM criteria 不满足时「失败还是自动供给」未实测）。
  另经核实：7 个 Action SHA **全部真实存在**且与声明版本一致（**未遇 403/限流**）；
  **Rust 1.97.1 确认真实已发布**（推翻「未发布必红」担忧）；`cargo-ndk 4.1.2` / `cargo-deny 0.20.2` 真实存在；
  `material3` **1.5.0 stable 核实不存在** → 退出条件未满足、维持 `1.5.0-alpha27`（**未改** `libs.versions.toml`）；
  **`cargo deny check` 本机实跑通过**（`advisories/bans/licenses/sources ok`，advisory-db 当日真实拉取，
  `curl 28` **未复现**）；Linux 侧「疑似首次即红」静态判定 **0 例**。
- **2026-09-10 实测更新（原「仍未达成」条已部分取代）**：CI **已在 GitHub 托管 runner 上真实运行**，
  核实方式 `gh run view 34463116293` / `gh run list --workflow dependency-scan.yml`：
  1. `Rust supply chain` ✅ **success**（`cargo test` + `cargo deny check` 全绿）→「CI 网络下 advisory-db 拉取」**已验证通过**；
  2. `Native gate` ✅ **success**（4 ABI 交叉编译、Debug/Release 打包、v1/v2/v3/v4 签名断言、4 ABI 入包断言全部通过）；
  3. `Fast gate` ❌ **failure** —— 失败点为 **Android Lint 147 个 error**，**已整改并归档**
     （见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §7.2）；`unit tests` 步骤自身通过；
  4. `dependency-scan` 的「真实阻断行为」**已实测且结论为否定**：运行 `34335443660` conclusion=**success**，
     而报告含 138 条 CVSS ≥ 7.0 → `failBuildOnCVSS` 在 aggregate 任务上**不生效**；
     **已补硬断言**（`.github/check_dependency_cvss.py` + workflow 接线），见 §7.4。
- **2026-09-11 追加（Fast gate 偶发红根因修复，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §18）**：批次 H / I 推送后
  CI `build` 的 **Fast gate 转红**（`UncaughtExceptionsBeforeTest`，根因为 `EntryDetailViewModelTest`
  未注入测试调度器致真实 `Dispatchers.Default` 线程在 `resetMain()` 后回跳 Main）；
  修复后 **连续两次** CI 运行 `34552887844`（提交 `6b09b6f`）与 `34554214053`（提交 `bc23cc8`）
  **build 三 job 全 success**（`Fast gate` ✓ / `Native gate` ✓ / `Rust supply chain` ✓），CodeQL 同期 ✓。
  该项证明 Fast gate 已可稳定转绿。
- **仍未达成（不得据此认为 CI 已跑通）**：
  1. ~~`Fast gate` 在 lint 修复后的下一次真实 CI 运行~~ → **已于 2026-09-10 完成并转绿**：
     运行 `34470024328`（提交 `c25ac51`）`build` 工作流**三 job 全 success、exit 0**，
     其中 `Fast gate` 的 `Android Lint` ✓ 与 `单元测试（全模块）` ✓、`Native gate` 的
     `Assemble Release` 与签名/入包断言 ✓、`Rust supply chain` ✓（**本仓 CI 史上 fast-gate 首次转绿**）；
     CodeQL 同期复跑 success，**开放告警 10 → 0**；
  2. ~~`dependency-scan` 在硬断言接入后的首次运行~~ → **已实测：该工作流确实运行过**
     （2026-09-10 运行 `34477320673`，headSha `8131dcf`，`workflow_dispatch`）→ **失败**，
     但失败点为**外部 NVD 数据源 503**（31 次重试后 `NvdApiException: 503` → `dependencyCheckAggregate FAILED`），
     且因 GitHub 默认跳过后续步骤，**硬断言被 skip、无判定**；**已修复可观测性**（断言步骤加 `if: always()`，
     无报告时按 fail-closed 明确判失败），见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §19。
     原文「本环境被拒 403、从未运行」系滞后前提，**就地修正**。**仍未取得**：一次 aggregate 成功前提下的
     断言 pass/fail（受 NVD 503 间歇性阻塞，需 `NVD_API_KEY` 或数据缓存，属维护者决策）；
  3. ~~`github/codeql-action/upload-sarif` **v4** 的真实执行~~ → **已真实执行**（同上运行 `34477320673`），
     因上游未产出 SARIF 而失败；**仍未取得**的是「有 SARIF 时的成功上传」；
  4. runner 镜像实际预装 API 级别（R2）、`ubuntu-latest` 指向的镜像版本（R1）；
  5. Gradle 对 daemon JVM criteria 不满足时的失败/自动供给行为（§1#3 残留不确定性）；
  6. 3 条 POSIX 断言的**最终结果**：`Fast gate` 的单元测试步骤确已通过，但该 job 整体因 lint 失败，
     `N/A` 待转绿后的完整运行确认。
- **已登记未改的风险点（含补丁草案，见留痕 §7）**：R1 `ubuntu-latest` 浮动标签（建议固定 `ubuntu-24.04`）；
  R2 fast-gate 不装 SDK；R3 runner 文件系统不支持目录 fd fsync；R4 wrapper 指向腾讯镜像且无
  `distributionSha256Sum`（**未改**：改动会影响国内网络下的本地构建，仅登记）；R5 `~/.cargo` 未缓存；
  R6 GHAS 前提与 `if-no-files-found: error` 的二次失败；R7 `dependency-check.init.gradle.kts:49` 注释与代码不符
  （**本批次已修正**）；R8 一次性 CI 密钥口令明文（自洽）；R9 `ls *.apk | head -n 1` 未来歧义。
- **验收标准**：三 job 在 CI 上真实跑通并按实际结果校准；`dependency-scan.yml` 首次以 CVSS≥7 运行后
  按实际命中处置（修依赖或登记 suppression，**不得回调阈值**）；`cargo deny check advisories` 在 CI 上真实通过。
  **不得据此认为 CI 已跑通。**

---

### ISSUE-P3-32 (新登记): 供应链达阈告警残余处置

- **优先级**：P3（供应链安全；阻断面仅在**手动触发**的 `dependency-scan`）
- **核实时间点与核实方式（2026-09-10）**：以
  `gh run download 34335443660 --name dependency-check-report` 取得 engine 13.0.0 的报告 JSON 后，
  以脚本统计 `dependencies[].vulnerabilities[]`：**188 条实例 / 132 个唯一（构件 × CVE）组合**，
  其中 **138 条实例、98 个唯一组合 CVSS ≥ 7.0**（47 条 `CRITICAL` 严重度；51 条分数 ≥ 9.0）；
  各族的依赖来源经 `.\gradlew.bat :app:dependencyInsight --configuration <cfg> --dependency <pkg>` 逐族确认。
- **背景**：本批次已**实证** `failBuildOnCVSS = 7.0f` 在 `dependencyCheckAggregate` 上不生效
  （见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§7.4**），并已补硬断言
  `.github/check_dependency_cvss.py`，且**已接线为 `dependency-scan.yml` 的独立门禁步骤**（提交 `37e609d`）。
- **2026-09-10 处置结果（本地真实扫描实测，本条主要面已消解）**：
  1. **真修复（非豁免）**：Kotlin 2.4.10 → **2.4.20**（即 `CVE-2026-53914` 的修复版本）——
     实测 `test --rerun-tasks` **1200/1187/0/13 零退化**、`lint` **5 模块 0 error**、
     运行时面 `kotlin-stdlib` 解析为 **2.4.20**；
  2. **豁免登记 4 族**（每族附血缘 + 运行面证据）：`org.jline/*@3.24.1`、`protobuf-java@2.6.1`、
     `analytics-library:*@32.4.0`、`org.jetbrains.kotlin/*`（**仅绑定 `CVE-2026-53914` 单一 CVE**）；
  3. **实测结果**：本地 `dependencyCheckAggregate -I .github/dependency-check.init.gradle.kts`
     → 漏洞实例由 **188 条降为 7 条**，其中 **CVSS ≥ 7.0 由 138 条降为 0 条**，
     硬断言 **exit 0 通过**，`CVE-2026-53914` **已消除**；
  4. **残余 7 条**全部为 **CVSS 5.3 MEDIUM**（`commons-lang3@3.16.0`、`httpclient@4.5.6`、
     `kotlin-reflect@1.6.10`、`kotlin-stdlib-jdk7/jdk8@1.8.x` 的 `CVE-2020-29582`），
     皆属构建工具链且**不达阈值**，**如实保留可见**，不做无依据的批量豁免。
- **2026-09-11 追加（硬断言「fail-closed」逻辑本地实测；核实方式：以合成报告调用脚本并读退出码）**：
  `python .github/check_dependency_cvss.py <report.json>` 语义已逐例验证——
  CVSS 9.8 → **exit 1**；CVSS 5.3 → **exit 0**（未达阈）；仅 `severity=HIGH`（无 CVSS 分数）→ **exit 1**（fail-closed 兜底）；
  报告缺 `dependencies` 字段 → **exit 1**；报告文件不存在 → **exit 1**（**无报告 ≠ 通过**）。
  即「达阈必红、无结论必红、未达阈放行」的闸门逻辑成立；
  **仍未验证的仅是它在 CI runner 上的首次真实执行**（需 Actions 写权限手动触发，见下）。
- **CI 侧首跑实测（2026-09-11 复核；更正原文「本环境被拒 403、从未运行」的滞后前提）**：
  该工作流**已在托管 runner 上运行过**——2026-09-10 运行 `34477320673`（`workflow_dispatch`，headSha `8131dcf`）
  **失败**，失败点为**外部 NVD 数据源 503**（31 次重试 → `NvdApiException: NVD Returned Status Code: 503`
  → `dependencyCheckAggregate FAILED`，**未产出报告**），进而导致：
  **硬断言被 GitHub 默认 skip（断言自身无判定）** + 报告/SARIF 上传二次失败。
  详见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §19。
  **已修复该可观测性缺陷**：硬断言步骤加 `if: always()`，无报告时按 fail-closed 明确判失败（**不削弱**闸门，只会增加失败信号）。
  **仍未取得**：一次「aggregate 成功 + 报告产出」前提下的断言 pass/fail —— 受 NVD 503 间歇性阻塞；
  处置路径为配置仓库 Secret `NVD_API_KEY` 或增强 NVD 数据缓存，属**维护者决策**，
  **不得**回调阈值或关闭 `failOnError` 来换取变绿。
- **未豁免残余（2026-09-10 核实 → 同日处置后已消解，结论以上方「处置结果」为准）**：
  1. `CVE-2026-53914` 的处置方式为**真修复**：Kotlin 已升级至 **2.4.20**
     （2026-09-11 复核 `gradle/libs.versions.toml`：`kotlin = "2.4.20"`），本地同参数扫描下该 CVE 已消除、
     达阈（CVSS ≥ 7.0）由 138 条降为 0 条。原先「本仓当前 2.4.10、不可在本环境安全验证」的**前提已不成立**，就地修正。
  2. 构建工具链族（jline × 11 构件 / `protobuf-java@2.6.1` / `analytics-library:protos@32.4.0`）：
     仍属构建期 shade 暴露，已按 [RESOLVED_LOG.md](RESOLVED_LOG.md) §7 登记 suppression（每族附血缘 + 运行面证据），
     属**风险接受决策**，不由本批次单方面压制。
  3. Code Scanning 依赖类 open 告警：待 CI 侧首次运行后按实际结果收敛；**不得**以批量 dismiss 清空。
- **验收标准**：
  1. `dependency-scan` 的硬断言步骤在**真实运行**中给出确定的 pass/fail（而非静默通过）；
  2. 每个达阈族满足下列二者之一并留痕：**升级到修复版本**，或**写入 suppression 并附核实依据**
     （按 `.github/owasp-dependency-suppressions.xml` 维护纪律 + 本文件登记）；
  3. 处置后 Code Scanning 依赖类 open 告警数与该族结论**一致**（禁止以 dismiss 替代修复依据）。
- **禁止**：回调 `failBuildOnCVSS` 阈值以换取变绿；删除或注释掉硬断言步骤；
  在没有核实依据的情况下批量写入 suppression。

---

### ISSUE-P3-49 ~ P3-51（2026-09-11 功能完整性审计批次 B · 已闭环）

> 三项（HOTP 端到端 / 单条移动分组与从模板新建便利入口 / `AttachmentManager` 孤儿实现清理）
> 已于 **2026-09-11** 同日整改并归档，实施细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§21**
> （门禁：`test --rerun-tasks` **1345 例 / 0 失败 / 13 跳过**）。此处留索引，正文已移出本文件。

---

### ISSUE-P3-52 (新登记): 自动填充确认/选择器生物识别未绑定 `CryptoObject`

- **优先级**：P3（低危安全缺陷；零信任审计 #4，严重度 LOW）
- **核实时间点与核实方式（2026-09-11）**：复读
  `app/src/main/java/com/keepasskey/app/security/BiometricAuthManager.kt:165-169`（`cipher == null`
  分支走无 CryptoObject 的 `authenticate(promptInfo)`）成立；复读
  `app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt:87-97` 与
  `AutofillPickerActivity.kt:122-135`——两处调用均未传 cipher，`Success` 分支直接
  `completeAuthResult()` / `deliver(credentials)` 放行明文凭据。
- **背景**：不绑 `CryptoObject` 时生物识别仅证明「用户在场」，未与本次放行操作密码学绑定；
  与快速解锁路径（走 Keystore Cipher）不一致。缓解：两窗口已有 `FLAG_SECURE` + `setHideOverlayWindows`
  + 遮挡触摸过滤，实际可利用性有限。
- **修复方向**：两 Activity 改走 Keystore 密钥的 `init()` Cipher 认证（对齐快速解锁路径），
  `Success` 分支校验 `cryptoObject?.cipher` 非空方放行；Keystore 密钥不可用时 fail-closed 退化为
  受保护窗口内手动确认（既有退化策略）。
- **验收标准**：① 两处认证均以 CryptoObject 绑定且校验非空；② 无硬件/未录入时退化行为不回归；
  ③ 单测 + 全量 test 绿；④ 若经论证不绑定，须在本条目留痕理由（评估结论同样算闭环证据）。

---

### ISSUE-P3-53 (新登记): 运行完整性探测一次性执行、结果永久缓存，不门控主密码/通行密钥路径

- **优先级**：P3（低危安全缺陷；零信任审计 #5，严重度 LOW，置信度 0.90）
- **核实时间点与核实方式（2026-09-11）**：复读
  `app/src/main/java/com/keepasskey/app/security/RuntimeIntegrityDetector.kt:58-76`——`refresh()`
  存在但仅由 `start()`（单例 init 一次性）调用，`_report` 此后不再更新；`Debug.isDebuggerConnected()`
  等时变信号缓存后必然失真（冷启动后附加调试器/Frida 无重扫）。**部分留痕**：「风险态仅禁用生物识别
  与自动填充、不门控主密码路径」这半句未逐消费方追踪，实施前须先复核 `RuntimeIntegrityGate` 全部
  消费方再动手。
- **修复方向**：敏感操作（解锁、自动填充下发）前对关键时变信号（调试器附加、钩子框架）重扫或
  定期刷新；`debuggerAttached` 类信号改为实时求值不落缓存。
- **验收标准**：① 冷启动后附加调试器的场景能被后续判定捕获；② 首扫 fail-closed（UNDETERMINED）
  语义不回归；③ 消费方清单经复核并留痕；④ 全量 test 绿。

---

### ISSUE-P3-54 (新登记): 解锁节流计数落普通 `SharedPreferences`，无完整性绑定

- **优先级**：P3（低危安全缺陷；零信任审计 #6，严重度 LOW，置信度 0.90）
- **核实时间点与核实方式（2026-09-11）**：复读
  `app/src/main/java/com/keepasskey/app/security/UnlockThrottle.kt:59-84`——
  `SharedPrefsUnlockThrottleStore` 使用普通 `MODE_PRIVATE` SharedPreferences（`com.keepasskey.unlock_throttle`），
  无完整性保护，root/adb/备份恢复删除该文件即清零计数与锁定截止。
- **背景与取舍**：威胁前提为 root/文件级写权限——该前提下攻击者本有更强手段（Hook 进程等），
  本项属纵深防御卫生问题。且 `allowBackup=false` 已就位，备份恢复面已收窄。
- **修复方向（二选一，均可闭环）**：① 计数文件经 Keystore 密钥加密或 HMAC 绑定，
  删除/篡改后 fail-closed（视为已达阈值锁定或拒绝解锁）；② 经书面论证接受该残余面并在本条目留痕
  （写明威胁模型边界），登记为「经论证的例外」。
- **验收标准**：① 按选定方向实施或论证留痕；② 若实施，节流状态机既有单测不回归，跨进程重启
  持久化语义（`UnlockThrottle.kt:41-42` 设计约束）保持。

---

### ISSUE-P3-55 (新登记): 复合密钥派生「密码+密钥文件」分支的 UTF-8 密码副本未清零

- **优先级**：P3（低危安全缺陷；零信任审计 #7，严重度 LOW，置信度 0.90；**整改成本最低，可随手修**）
- **核实时间点与核实方式（2026-09-11）**：复读
  `database/src/main/java/com/keepasskey/database/file/KdbxKeyDerivation.kt:50`——
  `HashUtil.sha256(charsToUtf8(passwordChars))` 中 `charsToUtf8` 产出的 UTF-8 中间字节数组
  未被捕获、无法擦除（`passwordHash` 本身在 `:62` 已清零，但明文口令字节副本随 GC 驻留）。
  同文件「仅密码」分支 `:80-82` 已正确捕获并 `Arrays.fill` 清零——同一文件内两种写法并存，
  证明修复无障碍。**本项违反 AGENTS.md §3「敏感数据铁律」**。
- **修复方向**：捕获 `charsToUtf8(passwordChars)` 中间字节数组，在 `finally` 中 `Arrays.fill` 清零，
  对齐同文件仅密码分支的既有写法。
- **验收标准**：① 中间口令字节副本全路径清零；② 不得引入 `String` 中间态；
  ③ 既有 KDBX 派生单测不回归（含复合密钥正确性断言）+ 全量 test 绿。

---

### ISSUE-P3-56 (新登记): 零信任审计次要加固项打包（release 诊断日志 / S3 objectKey / Provider 测试参数 / TOTP 剪贴板等）

- **优先级**：P3（次要 / 加固项打包；零信任审计「次要项」节，置信度低于正式阈值，仅供排期参考）
- **核实时间点与核实方式（2026-09-11）**：来源为同一审计报告次要项节；**各项实施前须独立复核前提**
  （按本文件「条目维护规则」第 2 条，行号以真实内容为准）。子项清单与处置方式：
  1. **诊断日志未按构建类型硬关闭**：`DiagnosticLogGate` 仅由用户偏好门控，release 可开启并记录
     子库别名/异常 message 等非凭据 PII。处置：release 下强制关闭或仅 `BuildConfig.DEBUG` 放行
     （实施前复读 `app/src/main/java/com/keepasskey/app/data/logger/DiagnosticLogGate.kt` 复核现状）；
  2. **S3 `objectKey` 未过滤 `.`/`..` 段**（WebDAV 侧已过滤），属自伤型配置问题。
     处置：对齐 WebDAV 既有过滤逻辑；
  3. **Provider 构造参数 `client: OkHttpClient? = null` 未标 `@VisibleForTesting`**：传入即跳过
     TLS-only 与 SSRF 校验（当前生产 DI 不传，属潜在回归面）。处置：标注 + 注释声明仅测试用途；
  4. **TOTP 复制默认开启**：`EXTRA_IS_SENSITIVE` 只影响系统预览，其他前台应用可在 30 秒窗口内
     读取剪贴板（平台固有）。处置：**评估类**——出评估结论（默认关闭 / 缩短超时 / 维持现状+理由），
     评估结论留痕于本条目即算该子项闭环；
  5. **`skipDalVerification` 用户开关**（开启后任意应用可为任意可注册域注册 Passkey，默认关、
     属显式降级）与 **KDF 边界**（Argon2 下限偏弱、上界构成有界 DoS、纵深防御缺口当前不可达）：
     **登记留痕即可，不强制整改**；如后续整改须先评估兼容性影响。
- **验收标准**：① 子项 1-3 完成代码整改；② 子项 4 出具评估结论并留痕；③ 子项 5 确认留痕无遗漏；
  ④ 全量 test 绿。

