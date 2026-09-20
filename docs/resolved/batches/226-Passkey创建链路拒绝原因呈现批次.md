# §226 Passkey 创建链路拒绝原因呈现批次（`ISSUE-P2-220`）

> **起因**：2026-09-20 用户真机（小米，Release 构建）复现报告 + 全链路代码走查
> （`KeePasskeyCredentialProviderService` → `CredentialCreateEntries` → `PasskeyCreateActivity`）。
> 现象为「系统确认弹窗点『继续』后 KeePasskey 无任何界面，浏览器侧流程直接中断」。
> **主题**：创建链路的 fail-closed 拒绝由「静默 `finish()`」改为「**在受保护窗口内呈现明确原因**、
> 用户确认后收尾」；拒绝原因的词汇表、判定投影、文案与接线全部单测锁定。
> **本环境边界（先声明，后文不再重复）**：本批**未改动** `*/src/androidTest/**`、未改
> `crypto/src/main/rust/**` 与任何 `Native*` 绑定 / 探活，故按 `AGENTS.md` §5 的验证义务**无设备侧必跑项**；
> 拒绝页在真实系统 CM 流程下的观感（含 Via 浏览器场景复跑）**未做真机核对**，如实登记于 §4。

---

## 1. 条目原文

### ISSUE-P2-220：Passkey 创建链路 fail-closed 拒绝时零用户反馈（用户视角为「点了继续就断」）

- **核实时间点与方式**：2026-09-20，用户真机（小米，Release 构建）复现报告 + 全链路代码走查
  （`KeePasskeyCredentialProviderService` → `CredentialCreateEntries` → `PasskeyCreateActivity`）；
  无真机日志，根因由代码路径与用户问答（Via 浏览器 / 密码库已解锁 / KeePasskey 无任何界面露面）唯一收敛。
- **现象**：Via 浏览器打开 passkeys.io 创建通行密钥，系统确认弹窗（「创建通行密钥…将存储在 KeePasskey 中」）
  正常出现；点「继续」后 KeePasskey 无任何界面，浏览器侧流程直接中断。
- **根因**：Via 不在特权浏览器白名单 → `CallingOriginResolver.resolveTrustedOrigin` 降级为
  `android:apk-key-hash:` origin → `PasskeyCreateActivity.passesRegistrationGates` 走普通应用路径执行
  DAL 远程校验 → passkeys.io 无对 Via 包名的 assetlinks 声明 → `NOT_VERIFIED` → fail-closed
  `failAndFinish()`（RESULT_CANCELED）。该拒绝发生在**任何 UI 呈现之前**，用户无法区分
  「功能坏了」与「被安全门控拒绝」。用户侧临时解法（均不改代码）：① 设置 → 自动填充 →
  「特权浏览器白名单」启用 Via（若 Via 请求确实填充 origin 则走浏览器路径豁免 DAL）；
  ② 开启「跳过 DAL 校验」开关（削弱防线，仅限个人设备自担风险）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt`
  （`passesRegistrationGates` 各拒绝分支 / `failAndFinish`）、`app/src/main/java/com/keepasskey/app/passkey/BaseCredentialActivity.kt`
  （统一收尾）。
- **AC**：
  1. 创建链路任一 fail-closed 拒绝分支（缺系统注入请求 / 缺注册参数 / DAL 未通过 / `excludeCredentials`
     命中 / 锁定态复核失败）在受保护窗口内**呈现明确拒绝原因**、由用户确认后关闭，不再静默 `finish`；
  2. 拒绝文案沿用预定义字符串资源（ISSUE-P1-10 口径），不得携带 rpId / 包名等敏感标识；
  3. 对系统的回传契约不变（仍 `RESULT_CANCELED`，浏览器侧仍收到创建失败）；
  4. 单测覆盖各拒绝分支的文案选择与结果回传；`.\gradlew.bat test` 全绿。

---

## 2. 整改明细

### 2.1 缺陷的本质：**判定与呈现分离**（判定被保留、呈现从未实现）

整改前 `PasskeyCreateActivity` 的六处 fail-closed 分支（`onCreate` 两处、`awaitRegistration`
两处、`createAndReturn` 两处）一律调用 `failAndFinish()`——它只有两个动作：`setResult(RESULT_CANCELED)`
与 `finish()`。也就是说：**门控判定是对的，缺的是把判定结果交付到用户眼前的那一步**。
根因链（Via 场景）中的每一步都是**设计内**行为（非特权浏览器不做 origin 豁免 → 走普通应用 DAL 校验 →
站点无声明 → 拒绝），唯一缺陷在于该拒绝**只对系统可见、对用户不可见**。

### 2.2 拒绝原因词汇表与判定投影（AC② 的结构性落点）

新增 `CredentialRejectionReason`（枚举，每个条目携带一条 `@StringRes` 文案），并配套**纯函数**
`fromDalResult(dalResult)` 把 DAL 三种结论投影为原因（`VERIFIED` → `null` = 放行）：

| 原因 | 触发分支 | 文案资源 |
|---|---|---|
| `MISSING_REQUEST` | 系统未注入创建请求（`PendingIntentHandler.retrieve*` 返回 null） | `passkey_reject_missing_request` |
| `MISSING_PARAMETERS` | `rpId` / `userName` 为空（沿用既有资源） | `passkey_error_missing_register_params` |
| `VAULT_LOCKED` | 解锁流程结束后复核仍未解锁 | `cred_error_vault_locked` |
| `CALLER_UNKNOWN` | 取不到系统背书的调用包名（沿用既有资源） | `passkey_error_caller_unknown` |
| `CALLER_CERT_UNREADABLE` | 无 `CallingAppInfo` 或签名摘要为空 ⇒ DAL 无从执行 | `passkey_reject_caller_cert_unreadable` |
| `DAL_UNVERIFIED` | DAL 判定 `NOT_VERIFIED` | `passkey_reject_dal_unverified` |
| `DAL_NETWORK_UNAVAILABLE` | DAL 判定 `NETWORK_UNAVAILABLE`（**与上者区分**，用户可对症重试） | `passkey_reject_dal_network_unavailable` |
| `CREDENTIAL_ALREADY_EXISTS` | 命中 `excludeCredentials` | `passkey_reject_credential_exists` |
| `REQUEST_INVALID` | 注册请求携带 `evalByCredential`（规范禁止） | `passkey_reject_request_invalid` |
| `INTERNAL_ERROR` | 创建过程异常兜底（沿用既有资源） | `cred_error_unknown` |

**AC② 的保障方式是结构性的**：这 8 条新增文案**不含任何 `%` 占位符**——拒绝原因要说清「为什么」，
而承载 rpId / 包名 / 域名 / 凭据 id 的唯一通道就是格式插值，取消插值即从构造上排除外泄
（由 `CredentialRejectionFeedbackTest` 逐条断言中英文资源皆无 `%`）。
原先散落在 `PasskeyCreateActivity` 的日志仍然只记拒绝**类别**（`gateRejection.name`），不记标识。

### 2.3 门禁抽为可注入对象（判定与原因同源）

原 `passesRegistrationGates(...): Boolean` 只有「通过 / 不通过」，无原因可言。本批抽为
`PasskeyRegistrationGate.evaluate(...)`：返回 `null` = 放行，非 null = **用户可见的拒绝原因**。
判定顺序与 fail-closed 口径**逐字沿袭**（浏览器委派豁免 → 包名缺失 → 跳过 DAL 的用户显式取舍 →
摘要不可读 → DAL 投影），差别只在「拒绝时返回什么」。

抽出的另一收益是**可穷举单测**：`verifyDal` 以参数注入，用例即可断言「哪些分支**不得**发起 DAL 请求」
（浏览器豁免 / 包名缺失 / 已跳过 / 摘要不可读四处均为 0 次），以及「门禁把系统背书的包名原样转交 DAL」。
同时 Activity 行数由 **399 → 388**（<400，未越过巨型类阈值）。

### 2.4 呈现载体：**整页**而非对话框（受保护窗口缺口）

新增 `CredentialRejectionScreen`（`internal` Composable），由基类新增的
`rejectAndFinish(reason)` 经 `setContent` 渲染，首条语句仍是 `ApplyObscuredTouchFilter()`
（ISSUE-P2-09 / P3-12 的遮挡触摸过滤）。

**为何不用 `AlertDialog`**：Compose 的 `AlertDialog` 会另开一个对话框窗口，其 `FLAG_SECURE`
**不会**从 Activity 窗口传播（仓库既有结论，见 `SecureDialog`）。而拒绝页要渲染的正是
「本次被安全门控拒绝」这一敏感上下文，故选择在基类自己那个已施加 `FLAG_SECURE` +
`setHideOverlayWindows(true)` 的窗口内**整页**渲染，不留缺口。

页面语义刻意最简：只呈现原因 + 单一「知道了」按钮，**不存在第二条出路**——被门控拒绝的请求
不可能被用户点通；确认与返回键都走 `failAndFinish()`，即回传契约与整改前**完全一致**（AC③）。

### 2.5 各分支接线（AC① 与 AC③）

| 位置 | 整改前 | 整改后 |
|---|---|---|
| `onCreate` 缺系统注入请求 | `failAndFinish()` | `rejectAndFinish(MISSING_REQUEST)` |
| `onCreate` 缺 rpId / userName | `failAndFinish()` | `rejectAndFinish(MISSING_PARAMETERS)` |
| `awaitRegistration` 锁定态复核失败 | `failAndFinish()` | `rejectAndFinish(VAULT_LOCKED)` |
| `awaitRegistration` 门禁不通过 | `failAndFinish()` | `rejectAndFinish(gateRejection)`（原因由门禁给出） |
| `awaitRegistration` 命中 `excludeCredentials` | `failAndFinish()` | `rejectAndFinish(CREDENTIAL_ALREADY_EXISTS)` |
| `createAndReturn` flags 不可签发 / `evalByCredential` / 异常 | `failAndFinish()` | `rejectAndFinish(INTERNAL_ERROR / REQUEST_INVALID / INTERNAL_ERROR)` |
| **`requestCreationUserVerification` 的 `onRejected`** | `failAndFinish()` | **保持 `failAndFinish()`（刻意）** |

最后一行是本批的**唯一豁免**：`onRejected` 是用户**主动取消**（或未通过）用户验证，
属非 fail-closed 路径——用户自己刚做了选择，再弹一页「为什么被拒绝」是噪音。守卫用例据此断言
「静默收尾在剔除注释后的源码里**恰好 1 处**，且必须落在 `onRejected` 内」。

### 2.6 接线守卫同步

`ObscuredTouchWiringTest` 的 `setContentWindows` 清单加入 `BaseCredentialActivity.kt`
（拒绝页渲染于其中），使既有「`setContent` 首条语句必须是 `ApplyObscuredTouchFilter()`」
的守卫锁住本批新增的 `setContent`——ISSUE-P2-220 的呈现载体因此自动纳入纵深防御清单。

---

## 3. 验证

> 全部计数**现跑**，不抄上一批。

| 项 | 命令 | 结果 |
|---|---|---|
| 宿主全量单测（AC④） | `.\gradlew.bat test --rerun-tasks --max-workers=1` | **BUILD SUCCESSFUL in 2m21s**；`xml=332 tests=2321 failures=0 errors=0 skipped=13`（§225 基线 `xml=331 / tests=2309` ⇒ **+1 类 / +12 例**，与新增 `CredentialRejectionFeedbackTest` 的 12 例**逐例可对**） |
| 新增用例明细 | `app/src/test/.../passkey/CredentialRejectionFeedbackTest.kt` | **12 例全过**（门禁判定穷举 6 例 + 文案选择 2 例 + 接线与回传契约 3 例 + DAL 投影单射 1 例） |
| 接线守卫 | `ObscuredTouchWiringTest`（本批扩清单） | 与新增用例合计 **14 例全过**（定向复跑已确认） |
| 行数分档 | `python tools/doc/count_line_tiers.py` | `files_scanned=520`（§225 的 517 + 本批 3 个新生产文件）；tier1(>500)=**2** 不变、tier2(400~500)=**29** 不增（`PasskeyCreateActivity` 399 → **388**，未入档） |
| 超长函数 | `python tools/doc/long_functions.py` | `functions_ge_100=**3**` 不变 |
| `docs/` 相对链接 | `python tools/doc/check_md_links.py` | **`BROKEN_MD_LINKS=0`** |
| 归档分册索引双向一致 | `python tools/doc/check_resolved_index_sync.py` | **`RESOLVED_INDEX_SYNC=OK`** |
| 复核报告一致性 | `bash tools/audit/check_recheck_consistency.sh` | `PASS: 无残留禁用短语（已扫描 1308 行，11 条禁用短语）`（本批未改任何审计 / 复核报告） |
| 截图测试包装编译门禁 | `python tools/export_previews/generate_screenshot_test_wrappers.py` + `.\gradlew.bat :app:compileDebugScreenshotTestKotlin --rerun` | 生成器 `promoted=78 wrappers=78 packages=17`（含新增的 `CredentialRejectionScreenPreview`）；编译任务**真实执行**并 **BUILD SUCCESSFUL in 2s** |
| instrumented 源码编译 | — | **未跑**：本批未改 `*/src/androidTest/**`、未动原生内核 / JNI 绑定，按 `AGENTS.md` §5 无设备侧必跑项 |

---

## 4. 过程缺陷与如实声明

1. **测试自身的「假接线」曾被注释误导**（两处，均在入库前修正）：
   - 断言 `source.contains("passesRegistrationGates")` 命中了 KDoc 里的**整改说明文本**
     （第 131 行提到旧函数名），改为 `contains("fun passesRegistrationGates(")` 并补断言
     `PasskeyRegistrationGate.evaluate(` 存在；
   - 断言「静默收尾恰好 1 处」实际数到 **3 处**——两处在 KDoc 注释里。引入
     `com.keepasskey.app.testutil.stripCommentsOnly`（既有工具，仓库内已有同类先例）后
     一律基于剔除注释的源码文本断言。
   > 这两处正是「接线守卫被注释喂饱」的典型形态（与 §225 污染的机制同源），留痕以便后续同类用例直接复用该纪律。
2. **`const val` 初始化器编译失败**（过程缺陷）：用例内 `const val DIGEST = "AA".repeat(32)`
   被 Kotlin 判为「非编译期常量」⇒ 改为 `val`。
3. **拒绝页的真实观感未做设备核对**（如实声明）：本批的验证面是「判定 / 文案 / 接线」三层，
   均为宿主可覆盖；`CredentialRejectionScreen` 在**真实系统 CM 流程**中的渲染、
   深色模式观感、以及在 Via 浏览器场景下的**端到端**表现**未做真机核对**。
   复现口径（照条目）：Via 打开 passkeys.io → 创建通行密钥 → 点「继续」，整改后应看到
   「无法创建通行密钥 / 无法确认调用应用对本站点的归属声明，已拒绝创建通行密钥。」+「知道了」。
4. **`excludeCredentials` 命中在真实 RP 上难以自然触发**（如实声明）：该分支的验收证据是
   **接线断言 + 门禁/文案单测**，未构造真机端到端场景。
5. **未改动判定口径**（不得误读为放宽）：本批**没有**改变任何门控的严格度——`skipDalVerification`
   仍是用户显式取舍、浏览器豁免仍由 `DomainMatcher` 的 rp.id ↔ origin 归属强校验承担、
   DAL 结论仍 fail-closed。变的只有「拒绝时告诉用户什么」。
6. **新增文案为 8 条**（`cred_reject_title` / `cred_reject_confirm` + 6 条原因），
   中文落 `values/strings_sync_passkey.xml`、英文落 `values-en/strings.xml`（同步以避免
   `MissingTranslation` 使 lint fail）；其余 4 个原因复用既有资源，未新增文案。

---

## 5. 涉及文件

**生产代码**

| 文件 | 改动 |
|---|---|
| `app/.../passkey/CredentialRejectionReason.kt` | **新增**（69 行）：拒绝原因词汇表（枚举 + `@StringRes`）+ `fromDalResult` 纯函数投影 |
| `app/.../passkey/PasskeyRegistrationGate.kt` | **新增**（50 行）：由 `passesRegistrationGates` 抽出的可注入门禁，返回 `null` = 放行 / 非 null = 用户可见原因 |
| `app/.../passkey/CredentialRejectionScreen.kt` | **新增**（92 行）：受保护窗口内的整页拒绝原因呈现（含 `@Preview` 夹具） |
| `app/.../passkey/PasskeyCreateActivity.kt` | 六处 fail-closed 分支改走 `rejectAndFinish(...)`；门禁判定改调 `PasskeyRegistrationGate.evaluate`；删原 `passesRegistrationGates`；KDoc 增列 ISSUE-P2-220 条目；399 → **388** 行 |
| `app/.../passkey/BaseCredentialActivity.kt` | **新增** `rejectAndFinish(reason)`（经 `setContent` 渲染拒绝页，首条语句为 `ApplyObscuredTouchFilter()`）；`failAndFinish()` 原样保留 |
| `app/src/main/res/values/strings_sync_passkey.xml` | **新增 8 条**中文文案（含「无插值」声明注释） |
| `app/src/main/res/values-en/strings.xml` | **同步 8 条**英文文案 |

**测试代码**

| 文件 | 改动 |
|---|---|
| `app/src/test/.../passkey/CredentialRejectionFeedbackTest.kt` | **新增**（298 行，12 例）：门禁判定穷举（注入式 DAL）／文案选择与一一对应／接线与 `RESULT_CANCELED` 回传契约（静态断言，基于剔除注释的源码） |
| `app/src/test/.../security/ObscuredTouchWiringTest.kt` | `setContentWindows` 清单加入 `BaseCredentialActivity.kt`（ISSUE-P2-220 拒绝页） |

**文档**

| 文件 | 改动 |
|---|---|
| `docs/ACTIVE_ISSUES.md` | `ISSUE-P2-220` **整条剪切**；P2 区归零（「1 项」→「0 项」+ 补注本批闭环） |
| `docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` | 本批次整行索引登记 |
| `docs/resolved/README.md` | 「当前最大编号」§225 → **§226** |
| `docs/resolved/batches/226-Passkey创建链路拒绝原因呈现批次.md` | 本文件 |

---

## 6. 更正节（§227 新增，**原文一字未改**）

> 按归档纪律「只搬迁、不改写；确需更正时**新增更正节**并保留原文」，本批正文（尤其 §2.4、§4 的
> 「**不存在第二条出路**」表述）**保持原样**，更正统一记于本节。

**更正事由**：本批交付后用户当场反馈「用户知道问题之后，也应该知道怎么解决问题，比如说添加白名单
什么的。最好能够通过按钮一键跳转到对应位置」，遂立 `ISSUE-P3-221` 并由 §227 批次实施。

**口径收窄（不是推翻）**：原文「不存在第二条出路」的**主体语义仍然成立**，但需按
[`产品裁决登记.md` PD-12](../../architecture/产品裁决登记.md) 精确化为两层含义：

| 维度 | 本批（§226）原状 | §227 起 |
|---|---|---|
| **本次**请求能否被用户点通 | 不能 | **仍然不能**（不变） |
| 页面是否有第二个按钮 | 没有，只有「知道了」 | 仅当原因确有用户可解法时，多一个**收尾之后**的补救动作 |
| 动作发生的时机 | — | `RESULT_CANCELED` + `finish()` **之后**（窗口已闭环） |
| 对系统回传契约 | `RESULT_CANCELED` | **完全一致**（不变） |

即：被否定的「第二条出路」指**让本次被拒请求通过**的路径——这一层**未变**；
§227 新增的是**下一次**发起前的用户引导，它是窗口闭环后的**独立动作**，两者不矛盾。

**落地改动**：`CredentialRejectionScreen` 增加可选 `guidance` 形参（`null` 时布局与本批**逐字一致**）；
`BaseCredentialActivity` 的 `onConfirm` 由 `failAndFinish()` 改为 `settleRejection()`
（幂等收尾原语，行为等价）——故
[`CredentialRejectionFeedbackTest.kt`](../../../app/src/test/java/com/keepasskey/app/passkey/CredentialRejectionFeedbackTest.kt)
中断言 `onConfirm = { failAndFinish() }` 的那一处**已随之更新**为 `onConfirm = { settleRejection() }`。