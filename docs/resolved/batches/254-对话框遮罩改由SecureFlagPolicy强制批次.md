# §254 对话框遮罩改由 SecureFlagPolicy 强制批次

> 对应条目：`ISSUE-P2-246`（**整条闭环**；定案＝该条目 AC① **方案①**——调用点传
> `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`）
> 日期：2026-09-21
> 涉及文件：`app/src/main/java/com/keepasskey/app/ui/screens/` 四个对话框文件（AC① 调用点）、
> `app/src/main/java/com/keepasskey/app/security/SecureDialog.kt`（AC① KDoc）、
> `app/src/test/java/com/keepasskey/app/security/SecureDialogFlagPolicyTest.kt`（AC②）、
> `app/src/androidTest/java/com/keepasskey/app/security/DialogWindowHardeningDeviceTest.kt`（AC③）、
> `app/src/debug/java/com/keepasskey/app/security/DialogWindowHardeningHostActivity.kt`（AC③ 宿主探针）、`docs/`
> 性质：**加固接线 + 自陈如实化 + 守卫 + 真机实证**；不改加密、协议语义、数据格式与
> `SecureDialogFlagPolicy` 的既有裁决语义

---

## 1. 前提复核

| 序号 | 定案前提 | 现跑复核 | 结论 |
|:--:|---|---|:--:|
| ① 缺口形态（设备读数） | §252 实测：宿主窗口不带 `FLAG_SECURE` 时对话框窗口 `dialogFlags=0x1800002`（**缺** `0x2000`）而同一 `decorView` 的 `filterTouchesWhenObscured=true`；宿主带时 `0x1802002`（**含**） | 复核 §252 批次文档 §4.3 与落盘结果 XML 的 UTP logcat 原文：两组读数**逐字一致**（`hostFlags=0x81810100, dialogFlags=0x1800002` / `flags=0x1802002`） | 成立 |
| ② 机制（Compose 源） | `Dialog` 默认 `SecureFlagPolicy.Inherit`，继承源是**调用方（宿主）窗口**，宿主不带时 Compose 会**清除**本包装的施加 | 直读 `~/.gradle/caches/.../ui-android-1.12.0-sources.jar`：`AndroidDialog.android.kt:251/263` 以**调用方** composition 的 `LocalView` 作 `composeView` 传入 `DialogWrapper`；`:675` `securePolicy.shouldApplySecureFlag(composeView.isFlagSecureEnabled())`；`:676-683` `window!!.setFlags(if (…) FLAG_SECURE else FLAG_SECURE.inv(), FLAG_SECURE)`；`View.isFlagSecureEnabled()` 实现见 `AndroidPopup.android.kt:1110-1116`（读 `rootView.layoutParams` 的该位） | 成立 |
| ③ API 可用性 | `SecureFlagPolicy` 与 `DialogProperties.securePolicy` 是否需 `@OptIn(ExperimentalComposeUiApi::class)` | 直读同源：`SecureFlagPolicy.android.kt:22` 为**公开** `enum class SecureFlagPolicy`（`Inherit` / `SecureOn` / `SecureOff`），**无** `@ExperimentalComposeUiApi`；`AndroidDialog.android.kt:130-139` 的 `DialogProperties` 仅标 `@Immutable`，`securePolicy` 为**公开 val 参数** | **无需任何 `@OptIn`**（实测编译通过且零新增警告） |
| ④ 调用点清单 | 「全部敏感对话框调用点」的现查集合（上一轮实测 4 文件 7 处，本轮以现查为准） | `grep -rn "    AlertDialog(" <四文件>` = **7**；`grep -rn "SecureDialog {\|SecureDialogWindowEffect()"` 去注释后**逐处**能与之一一对应（清单见 §2 AC①） | 成立（**7 处 / 4 文件**） |
| ⑤ 现有 `properties` | 调用点是否已有 `DialogProperties` 需合并 | 四个文件**原本都没有** `properties` 参数（`grep -n "DialogProperties\|properties"` 零命中，只有 import 行）⇒ 本批**新增**该参数而非合并 | 成立（新增，非合并） |
| ⑥ 既有语义不可动 | `SecureDialogFlagPolicy` 两条不变式与触摸过滤逻辑须一行不改 | 本批对 `SecureDialog.kt` 的改动**全部在 KDoc 内**（正文 `SecureDialogWindowEffect` 的 `addFlags` / 过滤段 / `onDispose` 与 `SecureDialogFlagPolicy` 逐字未动） | 成立 |

---

## 2. 整改内容（逐条对 AC）

### AC①-1 生产代码：7 处调用点逐处显式要求 `SecureOn`（**逐处清单**）

| # | 文件（`app/src/main/java/com/keepasskey/app/ui/screens/`） | 行 | 对话框 | 原 `properties` |
|:--:|---|:--:|---|:--:|
| 1 | `database/CreateVaultWizardDialog.kt` | `:60` | `KeyFileOneTimeSaveDialog`（一次性密钥文件保存提示） | 无 ⇒ 新增 |
| 2 | `database/CreateVaultWizardDialog.kt` | `:180` | `CreateVaultWizardDialog`（**含主密码 + 确认主密码**） | 无 ⇒ 新增 |
| 3 | `settings/MasterKeyChangeDialog.kt` | `:69` | `MasterKeyChangeDialog`（**主密钥更改，两个 `SecurePasswordField`**） | 无 ⇒ 新增 |
| 4 | `settings/subscreens/ChildDatabaseDialogs.kt` | `:84` | `ChildDatabaseDialog`（子库挂载，含子库主密码） | 无 ⇒ 新增 |
| 5 | `settings/subscreens/ChildDatabaseDialogs.kt` | `:179` | `ChildDatabaseCredentialDialog`（子库凭据补录） | 无 ⇒ 新增 |
| 6 | `detail/EntryDetailPreviewDiffComponents.kt` | `:72` | `RevisionVisualDiffDialog`（修订差异，**密码明文对比**） | 无 ⇒ 新增 |
| 7 | `detail/EntryDetailPreviewDiffComponents.kt` | `:232` | `SafeAttachmentPreviewDialog`（附件明文预览） | 无 ⇒ 新增 |

- 每处均为 `properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),`，插入位置紧随
  `onDismissRequest` 参数之后；每处上方留一行说明「`Inherit` 会按宿主窗口清掉本窗 flag（`ISSUE-P2-246`）」。
- 四个文件各新增两条 import（`androidx.compose.ui.window.DialogProperties`、
  `androidx.compose.ui.window.SecureFlagPolicy`）。
- **`@OptIn` 处理结论**：`SecureFlagPolicy` 为公开枚举、`securePolicy` 为公开参数（§1 ③），**无需**任何 opt-in；
  **未**使用反射或私有 API。
- **`SecureDialogFlagPolicy` 与过滤逻辑一行未改**（§1 ⑥）。

### AC①-2 `SecureDialog.kt` KDoc 改写为如实

- **删除**旧自陈「本包装**无条件**施加 dialog 窗口的 `FLAG_SECURE`……（有意的 fail-closed 偏离）」与
  「本包装与它（官方 `properties.securePolicy`）语义等价」两处**已不成立**的表述。
- **改写为**（要点）：
  1. 对话框窗口的 `FLAG_SECURE` **实际由 Compose 的 `SecureFlagPolicy`** 决定，默认 `Inherit` 以**宿主窗口**
     为准，宿主不带时 Compose 会 `setFlags(FLAG_SECURE.inv(), FLAG_SECURE)` **清除**它
     ⇒ 本包装的 `addFlags` **单独不足以**保住该 flag（附真机实测两组读数）；
  2. 故**各对话框调用点必须传 `SecureOn`**（本仓 7 处已全部如此，由静态守卫）；
  3. 本包装保留为**同窗内的一次防御性显式施加** + 继续承担**遮挡触摸过滤**；
  4. **保留**「与 `flagSecureEnabled` 开关无关」的**意图**陈述，并写明其**现在的实现方式**是调用点 `SecureOn`；
     若日后要恢复「尊重开关」，须**改判该意图**并同步 KDoc 与调用点（明确标注为产品裁决而非内部实现）。
- 「用法与语义」节补一句：调用点必须同时传 `SecureOn`（指向上述节）。
- 「可测性」节按新用例形态改写（三态断言 + 两条**不**证明的边界：截屏是否真被拦截 / 遮挡触摸是否真被丢弃）。

### AC② 守卫：`SecureDialogFlagPolicyTest` 新增静态不变式

- **落点选择＝既有文件**（不新开文件）：该文件已持有 `readSource` / `windowedCountOf` 与
  「调用点计数清单」的完整体例，新增清单可直接复用且**不引入跨文件重复的仓库根定位法**；
  新开文件会把这套定位法复制第二份，反而增加漂移面。
- 新增 `敏感对话框调用点全部显式要求 SecureOn`：按文件计数
  `CreateVaultWizardDialog.kt` **2** / `MasterKeyChangeDialog.kt` **1** / `ChildDatabaseDialogs.kt` **2** /
  `EntryDetailPreviewDiffComponents.kt` **2**，最少处数不足即红；失败信息列出逐文件实际计数。
- **既有判据未放宽**：原 `SECURE_DIALOG_CALL_SITES` 清单与 `敏感对话框全部接入对话框窗口防护` 用例逐字保留，
  新清单 `SECURE_POLICY_CALL_SITES` **独立**（失效方式不同：一条锁「本包装接线存在」，一条锁「Compose 策略被判为 SecureOn」）。

### AC③ 设备侧：把「锁定缺口」翻转为「锁定修复」

- **第 1 例**（`对话框窗口获得遮挡触摸过滤并携带遮罩且关闭后撤销遮罩`）：保留——宿主窗口带 `FLAG_SECURE`；
  三条断言（过滤已施加 / 窗口含该 flag / 关闭后已清）。
- **第 2 例**（`宿主窗口未携带FLAG_SECURE时对话框仍强制遮罩`）：**翻转**——本态下对话框窗口**仍必须**带
  `FLAG_SECURE`；KDoc 写明「宿主持有开关状态不影响本类对话框」（该包装自陈意图的实现）与判据理由
  （Compose 的 `Inherit` 会按宿主窗口清除，§254 的修复＝调用点 `SecureOn`）。
- **第 3 例（新增，对照留证）**（`宿主窗口未携带FLAG_SECURE且策略为Inherit时对话框不遮罩`）：
  **同一宿主、仅把 `securePolicy` 参数改为 `Inherit`** ⇒ 断言该窗口**不含**该 flag
  —— 证明第 2 例**真的在判策略参数**、该参数**承重**（否则第 2 例可能恒真而失去判别力）。
- 宿主探针新增 `secureOn` 开关（`app/src/debug/.../DialogWindowHardeningHostActivity.kt` 的 `DialogProperties`
  按该开关取值），供测试切换策略。
- 三条用例的断言消息与 KDoc 均引用 `ISSUE-P2-246` 并写明判据理由。

### AC④ 文档与归档

- 限界 §3.3（`docs/architecture/已知工程限界.md`）：新增「**`FLAG_SECURE` 的保持已改由调用点 `SecureOn` 强制**
  （2026-09-21 §254，`ISSUE-P2-246`）」条 + **产品可见行为**说明（宿主关闭「防截屏」开关时本类对话框仍强制遮罩，
  依据＝该包装自陈意图；要恢复「尊重开关」须改判），§252 的原边界句**保留留痕**；
  明细落入 §253 外迁的 [`docs/records/限界表证据与实测数据汇录.md`](../../records/限界表证据与实测数据汇录.md)
  对应条（原「未整改」缺口描述**保留**，其后追加「§254 的整改与真机复核」条）。
- `docs/ACTIVE_ISSUES.md`：剪切 `ISSUE-P2-246`（P2 **1 → 0**，标题计数 + 「暂无开放项」+ 「本区近期变动」摘要同步）。
- 本文件；`RESOLVED_LOG.md` §254 行；`docs/resolved/README.md` 最大 §253 → **§254**（下一批 §255）；
  `docs/resolved/BATCH_158_PLUS.md` §254 行。

---

## 3. 反向反校（逐次注入后立即还原；结果均为真实输出）

| # | 注入动作 | 预期 | 实测 |
|:--:|---|---|---|
| ① | 把 `MasterKeyChangeDialog.kt` 的 `securePolicy = SecureFlagPolicy.SecureOn` 改回 `Inherit`（等价「某调用点漏传」） | 宿主守卫**必红** | **红**：`SecureDialogFlagPolicyTest > 敏感对话框调用点全部显式要求 SecureOn FAILED` —— `AssertionError: 以下敏感对话框调用点未显式要求 SecureOn（宿主窗口不带 FLAG_SECURE 时其对话框窗口将不被遮罩，见 ISSUE-P2-246）：[app/src/main/java/com/keepasskey/app/ui/screens/settings/MasterKeyChangeDialog.kt：期望 ≥1 处 \`securePolicy = SecureFlagPolicy.SecureOn\`，实际 0 处]`；`6 tests completed, 1 failed` |
| ② | 把设备用例第 2 例的断言改回旧口径（`flags and FLAG_SECURE == 0`，即断言「不带」） | 在修复后的代码上**必红** | **红**：`宿主窗口未携带FLAG_SECURE时对话框仍强制遮罩 FAILED` —— `AssertionError: …本次读到 hostFlags=0x81810100、dialogFlags=0x1802002（FLAG_SECURE=0x2000）…`（读数**含**该位 ⇒ 旧口径断言失败，证明该用例真的在判修复） |
| ③ | 把设备用例第 2 例的 `DialogWindowProbe.reset(…, secure = false)`（即策略改回 `Inherit`） | 第 2 例**必红** | **红**：同一断言 `…本次读到 hostFlags=0x81810100、dialogFlags=0x1800002（FLAG_SECURE=0x2000）…`（**不含**该位） |

三次注入均已**逐次还原**：复核 `grep -n "SecureFlagPolicy.Inherit" …/MasterKeyChangeDialog.kt` **零命中**、
设备第 2 例的 `reset(hostWindowSecure = false)` 无 `secure = false`、断言恢复为
`flags and FLAG_SECURE == FLAG_SECURE`；还原后宿主全量 `test` **BUILD SUCCESSFUL**、设备 3 例全绿（§4.2）。

> **注入 ③ 的形态说明（如实）**：定案要求的原文形态是「临时把某**调用点**改回 `Inherit` 并跑设备侧该类」。
> 该形态**不可达**——设备用例的对话框由**测试宿主**的 `Dialog` 承载（只有测试自己的 composition 能插入探针去
> 解析对话框窗口，见 §5 ②），生产 7 处调用点**不在**该用例的组合路径上。故反校 ③ 以**同一策略参数**
> （`DialogProperties.securePolicy`）实施，等价证明「策略参数由 `SecureOn` 改为 `Inherit` ⇒ 对话框失去遮罩」。

---

## 4. 工程验证（现跑）

### 4.1 宿主侧

| 命令 | 真实输出 | 判定 |
|---|---|:--:|
| `.\gradlew.bat test --rerun-tasks --max-workers=1` | `BUILD SUCCESSFUL in 2m 26s`、`114 actionable tasks: 114 executed` | **通过** |
| `python tools/doc/count_test_results.py` | `xml=353 tests=2467 failures=0 errors=0 skipped=13`（脚本自行排除非 JVM 单测 XML：`{'debug': 5, 'updateDebugScreenshotTest': 1}`） | **通过**；§252 基线 **353 类 / 2466 例** ⇒ 本批 **+0 类 / +1 例**，与改动**逐条可对**（`SecureDialogFlagPolicyTest` 新增 1 例） |
| `.\gradlew.bat :app:testDebugUnitTest --tests "…SecureDialogFlagPolicyTest" --rerun-tasks` | `BUILD SUCCESSFUL`（6 例全绿，含反校后的还原态） | 通过 |
| `python tools/doc/count_line_tiers.py` | `tier1(>500)=2`（`SettingsViewModel.kt` 598 / `DatabaseSession.kt` 535）、`tier2(400~500)=29` | **与 §252 持平** |
| `python tools/doc/long_functions.py` | `functions_ge_100=3`（`keepasskeySettingsNavGraph` 254 / `CreateVaultWizardDialog` 201 / `keepasskeyNavGraph` 188） | **与 §252 持平**（**施工中留痕**：加注释后 `ChildDatabaseDialog` 与 `MasterKeyChangeDialog` 曾**恰好顶到 100 行**使计数升为 5，遂把两处两行注释各压为**单行**，回落至 3） |

### 4.2 设备侧（Redmi 4X / Android 17 · API 37，2026-09-21；先确认 UiAutomation 槽位空闲）

| 层 | 结果文件 | `tests` | `failures` | `errors` | `skipped` |
|---|---|:--:|:--:|:--:|:--:|
| `:app:connectedDebugAndroidTest`（**全量**） | `app/build/outputs/androidTest-results/connected/debug/TEST-Redmi 4X - 17-_app-.xml` | **74** | **0** | **0** | **1** |

- **对比 §252 基线（73 例）**：**+1 例**，即本批新增的对照用例，逐条可对。
- **唯一 skipped**：`com.keepasskey.app.passkey.CredentialSaveChainDeviceTest > 系统保存请求必须能路由到本应用的凭据提供者`
  （环境前提 `Assume`，`ISSUE-P2-239` / 限界 §27 登记的 ROM 面，与本批无关）。
- **本批 3 例**：`DialogWindowHardeningDeviceTest` 的 `对话框窗口获得遮挡触摸过滤并携带遮罩且关闭后撤销遮罩` /
  `宿主窗口未携带FLAG_SECURE时对话框仍强制遮罩` / `宿主窗口未携带FLAG_SECURE且策略为Inherit时对话框不遮罩`
  —— **均 `0 failed / 0 error / 0 skipped`（真实执行）**。

### 4.3 设备侧字段级读数（真机 `System.out` 原文）

```
[对话框窗口加固设备侧实测·宿主窗口带 FLAG_SECURE] filterTouchesWhenObscured=true,
  flags=0x1802002, decorView=com.android.internal.policy.DecorView
[对话框窗口加固设备侧实测] 关闭后 flags=0x1800002（FLAG_SECURE 已清）
[对话框窗口加固设备侧实测·宿主窗口无 FLAG_SECURE] hostFlags=0x81810100,
  dialogFlags=0x1802002, filterTouchesWhenObscured=true
[对话框窗口加固设备侧实测·宿主无 FLAG_SECURE + 策略 Inherit] hostFlags=0x81810100,
  dialogFlags=0x1800002, filterTouchesWhenObscured=true
```

- **修复生效的直接证据**：第 3 行与第 4 行**同一宿主**（`hostFlags` 完全相同、均为 `0x81810100`，
  `filterTouchesWhenObscured` 均为 `true`），唯一差异是 `securePolicy` 参数——
  `SecureOn` ⇒ `0x1802002`（**含** `FLAG_SECURE`）；`Inherit` ⇒ `0x1800002`（**不含**）。
- §252 在同一宿主下读到的是 `0x1800002`（缺口），本批同态下读到 `0x1802002` ⇒ **修复在真机成立**。

### 4.4 三项自检

| 命令 | 真实输出 | 判定 |
|---|---|:--:|
| `bash tools/audit/check_recheck_consistency.sh` | `PASS: 无残留禁用短语（已扫描 1326 行，11 条禁用短语）` | 全绿 |
| `python tools/doc/check_md_links.py` | `BROKEN_MD_LINKS=0` | 全绿 |
| `python tools/doc/check_resolved_index_sync.py` | `RESOLVED_INDEX_SYNC=OK（批次正文 252 份；分册登记 254 条；最大 §254）` | 全绿 |

---

## 5. 如实声明

1. **本批未证明「截屏确实被拦截」**，只证明该 flag 在真机上**真实施加并保持**（`flags` 位域读数）；
   「截屏确实被拦截」需在真机上实际截图比对，仍为**手工冒烟项**（本仓无该自动化基建）。
2. **产品可见语义变化（须一并接受）**：这三个（实为四类）对话框此后**无视用户「防截屏」开关而始终遮罩**。
   依据＝`SecureDialog.kt` 自陈的产品意图（强制遮罩、不读用户开关），**本批不是新增产品决定**，
   而是**让实现追上它自己写明的意图**；若日后要恢复「尊重开关」，须**改判**该意图、下传
   `SettingsUiState.flagSecureEnabled` 并按开关求值，同步 KDoc 与调用点（已在 KDoc 中写明该入口）。
3. **设备用例的作用域（本批最重要的边界）**：用例中的对话框由**测试宿主**的 `Dialog` 承载——只有测试自己的
   composition 能插入探针去解析对话框窗口（生产对话框是黑盒，无法从外部取得其窗口句柄）。
   故用例验证的是「**本包装 + `DialogProperties(securePolicy = …)` 在真实对话框窗口上的行为**」，
   **不覆盖生产 7 处调用点本身**；调用点的接线由宿主静态守卫 `SecureDialogFlagPolicyTest` 覆盖，两者互补。
   由此，定案要求的反校 ③ 形态（改**生产调用点**回 `Inherit` 并跑设备用例）**不可达**，已以同一策略参数
   等价实施并如实登记（§3 末尾）。
4. **未触其它层 / 未触原生面**：本批只改 `app` 模块的生产 UI 文件、`app/src/test/**`、`app/src/androidTest/**`
   与 `app/src/debug/**`（宿主探针）以及 `docs/`；**未触** `crypto/src/main/rust/**`、`jni_bridge_ext.rs`、
   任一原生绑定、`参考项目/`、构建脚本与依赖清单（**未新增任何依赖**）；`:crypto:` / `:database:` / `:sync:` /
   `:core:` 四层既无源码改动亦无 `androidTest` 改动 ⇒ 按「测试资产纪律」②**无四层设备侧义务**；
   本批**修改**了 `app/src/androidTest/**` 的既有用例（第 2 例翻转 + 新增第 3 例）⇒ 已按该纪律在真机上实跑
   （§4.2，3 例 `skipped=0`）。
5. **未跑**：`lint` / `assembleDebug` / `assembleRelease` / 截图测试包装编译门禁（本批未改 `@Preview` 与
   `tools/export_previews/`，这些任务不构成判别性证据）；KPEX 互操作对拍 `verify_interop.py`
   （未触 `.kdbx` 产物格式、`PasskeyData` schema 与 `PasskeyPkcs8Codec`，按 `AGENTS.md` 规则 8 不适用，
   **不对其作任何声称**）。
6. **`SecureDialogFlagPolicy` 的两条不变式与遮挡触摸过滤逻辑一行未改**——本批对 `SecureDialog.kt` 的改动
   **全部落在 KDoc**；`SecureDialogFlagPolicyTest` 的既有用例与既有清单亦**未放宽**。
7. **Devx/宿主探针属 debug 变体**：`app/src/debug/**`（§252 建立）在本批新增 `secureOn` 开关；
   `release` 变体不含 `src/debug` ⇒ 生产包与生产清单**零改动**。
