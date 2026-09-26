# 342. 注册响应 `transports` 撤回 hybrid 声明批次（`ISSUE-P3-338`）

> **批次性质**：`ISSUE-P3-337` 立项同批登记的**如实呈现缺陷**（`ISSUE-P3-338`）按既定开工顺序①独立先行闭环。
> 改动面 = 一处取值 + 两处断言 + 两段注释（生产代码零行为变更，除注册响应 `transports` 元数据本身）；
> **零新增用例、零删除用例**，全量计数与 §341 基线持平。
> **未触**原生内核 / `*/src/androidTest/**` / `参考项目/` / 构建脚本 / `KPEX_PASSKEY_*` schema
> ⇒ 无设备侧必跑项、无对拍重跑义务（判据核实读数见 §1.4）。
> 本批是 `ISSUE-P3-337`（扫码导入通行密钥）任何 hybrid 工作的**前置清洁项**：撤回后，
> 「声明一条本仓做不到的传输」不再可能被 KDoc 里的伪依据重新生成。

---

## 342.0 原文收录（`ACTIVE_ISSUES.md` 条目正文，原样剪切）

> ### ISSUE-P3-338：注册响应 `transports` 声明 `hybrid` 而本仓无任何 hybrid / 蓝牙实现——撤回为 `internal`
>
> - **优先级理由**：P3（不影响既有认证成败，属「对外自述能力与实际能力不符」的如实呈现缺陷）；
>   但它是任何 hybrid / caBLE 工作的**前置清洁项**，故排在同 section 顶部。
> - **核实时间点**：2026-09-26（本机会话内完成）。
> - **核实方式**（逐条可复跑）：
>   1. **代码事实**：`app/.../passkey/PasskeyRegistrationPayload.kt:180` 无条件交出
>      `listOf(WebAuthnJson.TRANSPORT_INTERNAL, WebAuthnJson.TRANSPORT_HYBRID)`；
>      同函数 KDoc `:149` 的理由是「**参考实现均声明 `hybrid`**（跨设备扫码），本仓此前只声明 `internal`」，
>      且该 KDoc 段落整体出自 `ISSUE-P2-265`「与两个参考实现对齐，补齐三项被消费方读取的字段」。
>      锁该值的断言两处：`app/src/test/.../PasskeyRegistrationMaterialInteropTest.kt:239`
>      （`assertTrue("transports 必须含 hybrid", …)`）、`PasskeyRegistrationPayloadBuildTest.kt:115`。
>   2. **实现事实**：`AndroidManifest.xml` 权限清单一行蓝牙都没有（现声明仅
>      `USE_FINGERPRINT` / `INTERNET` / `ACCESS_NETWORK_STATE` / `USE_BIOMETRIC` / `CAMERA` /
>      `POST_NOTIFICATIONS` / `HIDE_OVERLAY_WINDOWS`，见 `:17-37`），五模块 `src/main` 内
>      `bluetooth` / `BLE_ADVERTISE` / `websocket` 符号**零命中**（唯 6 处 `cable` 子串命中
>      属 Diceware 词表一类噪声）。CTAP2.2 §11.5 的 hybrid 传输**必须**有 BLE 广播
>      （`BluetoothLeAdvertiser` + `BLUETOOTH_ADVERTISE`）与会话隧道 ⇒ **本仓在权限层面即不可能完成 hybrid**。
>   3. **对照取证**（[`references/扫码导入通行密钥的参考项目对照.md`](../../references/扫码导入通行密钥的参考项目对照.md) §2.4）：
>      `fenris-authenticator` 的 `credentialprovider/webauthn/CreateResponse.kt:66` 同样硬编码
>      `listOf("internal", "hybrid")`，而该仓**全仓无一行蓝牙代码**（12 处 `cable` 命中经逐条核验
>      全是 `Cancelable` 一类子串）⇒ 本仓 KDoc 那句「参考实现均声明 hybrid」**恰好能在这一类实现上找到出处，
>      但被参照者自身并无该能力，不构成规范依据**；
>      反例是 `Authnkey`：`CredentialProviderActivity.kt:1030-1050` 只上报
>      「当前实际所用传输 ∪ `authenticatorGetInfo` 声明」，`HYBRID` / `BLE` 在其仓内是
>      **从未被引用的死常量**（`TransportType.kt:17,19`）。
> - **背景**：`transports` 是注册响应 `response` 里的**能力自述元数据**，供 RP / 平台决定
>   「下一步该引导用户走哪条传输」。声明一条不存在的传输，等于让对端为不可能完成的路径做 UI 与重试。
> - **整改口径**：
>   1. `PasskeyRegistrationPayload.kt:180` 降为 `listOf(WebAuthnJson.TRANSPORT_INTERNAL)` 单值。
>   2. 同函数 KDoc `:149` 那句「参考实现均声明 `hybrid`」**必须删除并改写**为可核验的表述：
>      本仓无 hybrid 传输（无蓝牙权限、无 BLE 广播、无会话隧道）⇒ 不得声明；
>      恢复条件是 **CTAP2.2 §11.5 全链可用**，而非「有实现参考」。
>      ⚠️ 该 KDoc 是**本缺陷的再生成器**：留着它，下一个人还会照抄。
>   3. 两处断言（核实 1 所列）同批改锁 `[internal]` 单值，失败消息须点名 `ISSUE-P3-338`。
> - **验收标准**：
>   - **AC①** 上述两断言改后仍绿，且**反向反校**：把 `TRANSPORT_HYBRID` 加回即红。
>   - **AC②** 全量 `.\gradlew.bat test --rerun-tasks --max-workers=1` 绿（计数只用
>     `python tools/doc/count_test_results.py`）+ `python tools/doc/gate_readings.py` **7/7 PASS**
>     且读数块原样贴入批次文档 §3。
>   - **AC③** 互操作面无回归：`transports` 属**注册响应元数据**、不写进 `KPEX_PASSKEY_*`，
>     故预期 `verify_interop.py` 判据不受影响 —— **开工首步先核实该预期**（若 probe 判据确含 `transports`，
>     须在本条目内登记并按新值重取基线，不得默默改判据）。
> - **未决与风险**：具体 RP / 平台是否因 `hybrid` 声明改变可观察行为（如提示"用另一台设备"路径），
>   **本条目未取证** ⇒ 不作为撤回的前提，也不构成「无害」的证据。
> - **粗估**：**0.5 人日**（一处取值 + 两处断言 + 一段 KDoc）。
> - **关联**：`ISSUE-P3-337`（同属 passkey 面；hybrid 落地时两条同批复核）/ `ISSUE-P2-265`
>   （本缺陷的引入批次）/ `references/扫码导入通行密钥的参考项目对照.md` §2.4（两种相反先例的取证）。

## 1. 判证与根因

### 1.1 代码事实（开工当日复跑，行号为**改动前**快照）

`app/src/main/java/com/keepasskey/app/passkey/PasskeyRegistrationPayload.kt:180` 在
`buildRegistrationJson(...)` 的 `response` 对象里**无条件**交出两值：

```kotlin
strArray(
    WebAuthnJson.TRANSPORTS,
    listOf(WebAuthnJson.TRANSPORT_INTERNAL, WebAuthnJson.TRANSPORT_HYBRID)
)
```

`WebAuthnJson.TRANSPORTS` 常量的消费点全仓共 **3 处**（该生产处 + 两处测试断言），
即 `transports` 只被**写出**、本仓从不解析入站值 ⇒ 该值是纯对外自述。

### 1.2 实现事实（本仓不可能完成 hybrid）

- `app/src/main/AndroidManifest.xml` 蓝牙类权限（`BLUETOOTH` / `BLUETOOTH_ADMIN` /
  `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE`）**声明数 0**
  （`grep -c "permission.BLUETOOTH"` → `0`）；现声明的 `uses-permission` 为
  `INTERNET` / `ACCESS_NETWORK_STATE` / `USE_BIOMETRIC` / `CAMERA` / `HIDE_OVERLAY_WINDOWS` /
  `POST_NOTIFICATIONS`，另有三个 `BIND_*` 服务绑定权限与 `USE_FINGERPRINT` 的 `tools:node="remove"`。
- 五模块 `src/main` 的 Kotlin 源里 `bluetooth` / `BLE_ADVERTISE` / `BluetoothLeAdvertiser` /
  `websocket` **命中 1 处，且正是本批新写的那行 KDoc**（`PasskeyRegistrationPayload.kt:150`）
  ⇒ **改动前零命中**，无任何 BLE 广播或会话隧道实现。
- CTAP2.2 §11.5 的 hybrid（caBLE）传输**必须**具备 BLE 广播（`BluetoothLeAdvertiser` +
  `BLUETOOTH_ADVERTISE`）与隧道会话 ⇒ 本仓在**权限层面即不可能**完成该传输。

### 1.3 对照取证：伪依据的出处与其失效

[`references/扫码导入通行密钥的参考项目对照.md`](../../references/扫码导入通行密钥的参考项目对照.md) §2.4
（三同类项目只读取证）给出**两种相反先例**：

| 同类实现 | `transports` 口径 | 该实现自身是否有 hybrid 能力 |
|---|---|---|
| fenris-authenticator | 硬编码 `listOf("internal", "hybrid")`（`CreateResponse.kt:66`） | **无**——全仓零行蓝牙代码，12 处 `cable` 命中经逐条核验全是 `Cancelable` 一类子串 |
| Authnkey | 只上报「当前实际所用传输 ∪ `authenticatorGetInfo` 声明」（`CredentialProviderActivity.kt:1030-1050`） | 无 ⇒ 且其 `TransportType.kt:17,19` 的 `HYBRID` / `BLE` 是**从未被引用的死常量** |

⇒ 本仓旧 KDoc「参考实现均声明 `hybrid`」的**出处恰是那个虚报者**：被参照者自身无该能力，
不构成规范依据；Authnkey 的口径（只报实际所用）才是与「能力自述」语义一致的一侧。

### 1.4 `AGENTS.md` 规则 8 / 条目 AC③ 前置核实（对拍判据是否含 `transports`）

条目要求「开工首步先核实该预期」，读数如下（**开工当日实测**）：

| 检索对象 | 命令口径 | 命中 |
|---|---|---|
| `tools/passkey-interop/verify_interop.py` | `grep -n "transports\|TRANSPORT"` | **0 行** |
| `tools/` 全目录（排除二进制） | `grep -rl "transports"` | 仅 `local-sync/bin/mc.exe`、`minio.exe` 两个**二进制文件**（子串巧合，非判据） |
| `database/src`（含 `PasskeyInteropProbeTest.kt`） | `grep -rn "transports"` | **0 处** |

⇒ `transports` 属**注册响应运行时元数据**，不写进 `KPEX_PASSKEY_*`、不参与任何 probe 判据
⇒ 条目 AC③ 的预期成立，**无需**在本条目内登记新基线、**无需**重跑 `verify_interop.py`
（本批零触碰 `.kdbx` 写盘面；全量 `test` 已含 `:database:` 的 probe 产出用例）。

### 1.5 根因定性

该值由 `ISSUE-P2-265`（「与两个参考实现对齐，补齐三项被消费方读取的字段」）引入，
同批把理由写成 KDoc。**缺陷的载体不是那行取值，而是那段注释**：留着它，下一个照抄的人会
把 `hybrid` 加回来——故本批把「再生成器」与「值」一并处理（见 §2.2、§2.3）。

## 2. 整改内容

1. **取值降为单值**（口径 1）：`PasskeyRegistrationPayload.kt` 的 `strArray(TRANSPORTS, …)`
   → `listOf(WebAuthnJson.TRANSPORT_INTERNAL)`。
2. **KDoc 改写**（口径 2）：删去「参考实现均声明 `hybrid`」，改为可核验表述——本仓无蓝牙权限、
   无 BLE 广播、无会话隧道 ⇒ 不得声明；**恢复条件 = CTAP2.2 §11.5 全链可用**，而非「有实现参考」；
   并点名曾被引为依据的同类实现自身亦无该能力（§2.4 对照），防再次以「别人都这么写」立项。
3. **删除 `WebAuthnJson.TRANSPORT_HYBRID` 常量**（**超出条目三条口径的一项，如实登记**）：
   该常量改动后**零消费方**，而其旧注释「参考实现同样声明」是本缺陷的**第二个再生成器**。
   取舍＝**不留「带警告的未引用常量」**：留着它，加回 `hybrid` 只是一次补逗号；删掉它，
   重新引入必须先显式恢复常量、也就必须先把 §11.5 全链做出来——把「无意抄用」变成「有意动作」。
   `TRANSPORT_INTERNAL` 的 KDoc 承接该禁令与恢复前提（指向本批与 §11.5），信息不丢。
4. **两处断言同批改锁**（口径 3）：`PasskeyRegistrationPayloadBuildTest` 与
   `PasskeyRegistrationMaterialInteropTest` 原写法是「含 internal」+「含 hybrid」两条
   `assertTrue`——**存在性断言对多余元素无鉴别力**（去掉 hybrid 后仍绿、加回任意第三条传输也仍绿）。
   一律改为 `assertEquals(listOf(TRANSPORT_INTERNAL), transports)` **精确锁单值**，
   失败消息点名 `ISSUE-P3-338`。
   ⚠️ 连带口径调整：条目 AC① 原文写「把 `TRANSPORT_HYBRID` 加回即红」，因常量已删（§2.3），
   反向反校改以**字面量 `"hybrid"`** 加回，判据不变（读数见 §4.2）。
5. **测试文件头对照表同步**：`PasskeyRegistrationPayloadBuildTest` KDoc 表格
   `response.transports` 行的「本测试断言」列由「✅ 含 `hybrid`」改为
   「✅ **只 `[internal]`**——本仓无 hybrid 传输故不跟随」；两参考实现列**照 §265 原读数保留**
   （未在真实现复核前不改写他人读数）。

**未采用的替代方案（留痕）**：①只改值、保留常量与注释——两个再生成器都不除，等于把复发概率
留给下一个人；②把 `transports` 整个字段删掉——字段本身合规且被消费方读取（RP 据此决定引导路径），
缺的是能力而非声明位，删字段会额外改变对外形状；③改判为「按 `CredentialCreationOptions`
请求的 `transports` 回显」——本仓凭据恒为本机软件凭据，回显请求值等于把 RP 的猜测原样退回，
仍是自述不实。

## 3. 门禁读数（`gate_readings.py` 原样粘贴，§308 立规）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 340 份；分册登记 342 条；全量索引 342 条；最大 §342）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 459 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

> **首轮 6/7 如实留痕**：首采时 `[3/7] check_md_links.py EXIT 1 | BROKEN_MD_LINKS=1`——
> 断链就在本批 §342.0 引用块内那条 `references/…对照.md`：条目原文写于 `ACTIVE_ISSUES.md`（以 `docs/`
> 为基准），原样引用进 `docs/resolved/batches/` 后相对路径少两级。按本仓批次文档既有口径
> （`149` / `264` / `334` 各批均用 `../../references/`）改写该链接目标后转绿；
> 该改写计入下条逐字复核的「有意改写」。**粘贴后复跑 `python tools/doc/gate_readings.py` 仍 7/7 PASS**。

> 粘贴后复跑 `python tools/doc/gate_readings.py` 仍 **7/7 PASS**（读数与上块一致）。
> 逐字搬移复核：`python tools/doc/check_verbatim_move.py <原文件> <本体> <段落文件>` 读数见 §4.5。

## 4. 验证

1. **定向类先跑**：`:app:testDebugUnitTest --tests …PasskeyRegistrationPayloadBuildTest --tests
   …PasskeyRegistrationMaterialInteropTest` → `BUILD SUCCESSFUL in 29s`（全限定类名口径，
   通配形态本机不生效）。
2. **AC① 反向反校**：把 `"hybrid"` 临时加回生产取值后复跑上述两类 → `BUILD FAILED`，
   **两类均红**，失败消息原样：
   ```
   PasskeyRegistrationMaterialInteropTest > 响应体不包含超规范字段且clientDataJSON字段完整 FAILED
     java.lang.AssertionError: transports 必须恰为 [internal] expected:<[internal]> but was:<[internal, hybrid]>
   PasskeyRegistrationPayloadBuildTest > 一 响应补齐参考实现要求的字段 FAILED
     java.lang.AssertionError: transports 只能是 [internal]：本仓无 hybrid / caBLE 传输实现，不得声明（ISSUE-P3-338）
                                            expected:<[internal]> but was:<[internal, hybrid]>
   ```
   随即撤除该临时改动（`grep` 复核生产文件已回到单值 `listOf(WebAuthnJson.TRANSPORT_INTERNAL)`），
   再跑全量套件。**说明**：本批的反向反校对象是「测试断言的鉴别力」，不是安全护栏——
   临时注入的是本批正在撤回的元数据值，窗口内无任何数据/安全面劣化。
3. **AC② 全量**：`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` →
   `BUILD SUCCESSFUL in 4m 42s`、`114 actionable tasks: 114 executed`；聚合计数
   `xml=402 tests=2673 failures=0 errors=0 skipped=13`（**与 §341 基线逐值持平**：
   本批零新增、零删除用例，改动仅两处断言的判据形态；`skipped=13` 同旧，本批零跳过）。
4. **文档机检**：`check_md_links.py` `BROKEN_MD_LINKS=0`、`check_resolved_index_sync.py`
   `RESOLVED_INDEX_SYNC=OK`（批次正文 340 份 / 分册登记 342 条 / 全量索引 342 条 / 最大 §342），
   `check_tautological_assertions.py` 命中 0 处 / 扫描 459 个测试文件
   （改后两处断言的实参含被测返回值 `transports`，非局部可全求值 ⇒ 不属重言形态）——
   三项读数均已原样含在 §3 门禁块内（首采 6/7 的断链与整改见 §3 留痕）。
5. **逐字搬移复核**：`python tools/doc/check_verbatim_move.py build/tmp_active_head_342.md
   docs/ACTIVE_ISSUES.md build/tmp_entry_342_body.md`（原文件＝`git show HEAD:docs/ACTIVE_ISSUES.md`，
   段落文件＝本批 §342.0 剥 `> ` 还原）→ **`orig_content_kinds=496` / `MISSING_KINDS=11` /
   `NEW_ONLY_KINDS=12`**。11 行缺失**逐行核对全部是剪切与进度回写动作本身的有意改写**：
   P3 section 段头 1 行 + 开放项指针引用块 4 行、`ISSUE-P3-337` 关联段 2 行、开工顺序段 3 行、
   §342.0 内那条 `references/` 链接的**相对路径** 1 行（见 §3 留痕）⇒
   **`ISSUE-P3-338` 条目正文 48 行中，除该链接目标一处按批次目录基准改写外，零缺失零改写**。
6. **互操作面**：见 §1.4 判据核实读数——本批不触 `.kdbx` 写盘，未重跑 `verify_interop.py`（理由登记）。
7. **声称范围如实**：本批只撤回对外元数据声明，**未**新增或改动任何传输实现；
   「具体 RP 是否会因 `hybrid` 声明改变可观察行为（如提示『用另一台设备』路径）」条目原记
   **未取证**，本批亦未补取证 ⇒ 该不确定性不作为「撤回无害」的证据，留作 hybrid 立项时的取证项
   （`ISSUE-P3-337` 关联段已按此更新指针）。
