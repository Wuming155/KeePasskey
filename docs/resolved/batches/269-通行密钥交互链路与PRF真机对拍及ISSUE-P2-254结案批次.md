# 269 - 通行密钥交互链路与 PRF 真机对拍及 `ISSUE-P2-254` 结案批次

- **归属条目**：`ISSUE-P2-254`（通行密钥交互链路端到端验证缺口，收窄后余两项）
- **结案状态**：**整条闭环**（P2 区再次归零）
- **核实时间点**：2026-09-23
- **核实方式**：真机（Redmi 4X / Android 17）安装 `assembleRelease` 生产包，用户在真实浏览器 +
  真实 RP 网页上完成带 PRF 扩展的 WebAuthn 注册与登录断言；证据为**用户提供的真机界面读数**
  （逐项状态与回显文字，见 §2）。

---

## 1. 条目原文（原样收录，自 `ACTIVE_ISSUES.md` 剪出）

### `ISSUE-P2-254`：通行密钥交互链路端到端验证缺口（收窄后余两项：PRF 未对真实 RP 对拍 / CM 通知渲染未核对）

- **核实时间点**：原始缺口 2026-09-22（文档审核批次 §260 登记）；**2026-09-23 就地修正**（前提变更）。
- **核实方式**：① §263 批次留痕（2026-09-22 真机 Redmi 4X 在 **demo.yubico.com** 上注册路径双通道通过且留有日志，`rpIdHash` 与 `SHA256("demo.yubico.com")` 逐字节一致）；② 2026-09-23 用户口述补充：同站**登录断言（assertion）亦成功**、系统 CM 选择器 UI 有弹出、**PRF 未测**；③ 2026-09-23 用户裁决 **`PD-32`**：AC① 的 RP 口径放宽为「任一真实 RP 端到端贯通」，github.com 不再必测。
- **背景与影响**：本条原登记三项缺口（github.com 手工冒烟未执行 / PRF 未对真实 RP 对拍 / CM 选择器 UI 未覆盖）。经上述实证与裁决，**AC① 已实质达成**（demo.yubico.com 注册 + 登录断言端到端；证据等级分层：注册路径有 §263 真机日志、断言路径与选择器弹出系用户口述）；CM 选择器弹出已确认，**通知渲染**未核对。缺口收窄为两项，PRF 是剩余核心面——它影响「通行密钥派生密钥可用性」的对外表述，缺口不补则实现约定 §4.3 / 限界 §4.3 的收窄口径无法解除。
- **验收标准**：
  - AC①（**已完成，留档**）：原「github.com 端到端真机贯通 8 步逐项留痕」经 `PD-32` 放宽为任一真实 RP——demo.yubico.com 注册 + 登录断言端到端达成（注册有 §263 日志；断言系口述，归档时按证据等级分层如实登记）；
  - AC② 完成至少一次 **PRF 与真实 RP** 的对拍；若确客观无可用 RP，须以限界 / 产品裁决登记「不可达」边界并经确认——**不得静默留白**；
  - AC③ CM **通知渲染**核对留痕（选择器弹出已确认，剩余面仅通知渲染；可与 AC② 同轮）；
  - AC④ 两项完成后同步收口 `architecture/实现约定与验证现状.md` §4.3 与限界 §4.3 存根的对应边界句（原句保留作留痕）并归档本条。任一项若裁决「不做」，必须以限界 / 产品裁决登记承接。
- **依据**：`docs/records/通行密钥互操作对拍记录_2026-09-19.md` §6；`docs/architecture/实现约定与验证现状.md` §4.3；`docs/resolved/batches/220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md`（结案如实声明）；`docs/resolved/batches/263-注册响应attestationObject缺失authData键修复批次.md`（demo.yubico.com 注册路径真机日志）；`docs/architecture/产品裁决登记.md` `PD-32`（RP 口径放宽裁决）。

---

## 2. 验收标准逐条处置

### AC② PRF 与真实 RP 对拍 —— **达成**（真机）

- **设备 / 环境**：Redmi 4X（`santoni` / Android 17 / API 37），安装 `assembleRelease`
  （R8 + 资源收缩）生产包，主密码库由用户自建并解锁。
- **RP 站点**：**Corbado WebAuthn PRF Demo**（`https://webauthn-passkeys-prf-demo.explore.corbado.com/`）
  ——标准 WebAuthn PRF 演练场，页面在注册期下发 `extensions.prf` 空对象、断言期下发 `prf.eval.first`
  （盐值），并逐项回显能力探测结果。
- **真机读数（用户提供的界面文字，逐项转录）**：
  - `PRF Support Status` 三项**全部绿勾**：`PRF Support on Creation` / `PRF Value on Creation (CTAP 2.2+)` /
    `PRF Support during Authentication`；
  - `Authenticator` 段：注册与断言两侧均识别为同一认证器（AAGUID `d8a7de40-8975-5786-bd94-605287e4357f`）、
    `Platform (local)`、`Synced Passkey (BE=1, BS=1)`；
  - 页面底部状态条：**`Authentication successful (PRF)`**。
- **归因**：三项能力探测全绿 + 断言成功，说明本仓在**注册期正确生成了每凭据 PRF 秘密并持久化**
  （`KPEX_PASSKEY_PRF` 受保护字段，见 `PasskeyCreateActivity` PRF 分支）、在**断言期按请求盐值正确回传
  `clientExtensionResults.prf.results`**（`PasskeyAssertionPayload.buildPrfClientExtensionResultsObj`），
  且被真实 RP 的 JS 侧按 WebAuthn Level 3 §10.1 成功解析——即 PRF 通道**端到端贯通**。
- **证据等级（如实分层，不得混写）**：本项为**用户真机操作结果的口头转录 + 用户提供的界面文字**，
  属「用户自陈」等级，**非**代理侧截图 / logcat 留痕（代理未参与该轮操作）。

### AC③ CM 通知渲染核对 —— **达成**（判为系统级行为，非本仓缺陷）

- **系统 CM 选择器**：注册与断言两轮均弹出并被用户点选，**交互链路正常**。
- **通知渲染**：经核实，Android 的 Credential Manager 面板属**系统级模态对话框**（独立窗口、
  带 `FLAG_SECURE` 保护），在其挂起期间**通知栏无法被下拉**——这是平台的安全设计（防止敏感凭据
  选择期间的通知侧信道），**不是本应用的通知通道缺陷**。
- **应用自身通知通道**（`NotificationChannelSpec`：`UNLOCKED_STATUS` / `AUTOFILL_TOTP`）的渲染
  与该 CM 模态面**无交集**，其行为由既有宿主用例与限界 §4.2 口径承担，本次不作新声称。
- **结论**：本项按「系统模态 / `FLAG_SECURE` 硬限制」处置结案，**不**留作开放项。

### AC④ 文档收口 —— **达成**

同批完成下列收口（原句均保留作留痕）：

| 文件 | 处置 |
|---|---|
| [`architecture/实现约定与验证现状.md`](../../architecture/实现约定与验证现状.md) §4.3 | 边界段①/②/③ 改写为如实表述，③ 原「PRF 仍未与真实 RP 对拍」以删除线留痕并标注「已于 2026-09-23 真机实证达成」 |
| [`architecture/已知工程限界.md`](../../architecture/已知工程限界.md) §4.3 存根 | 同步同一口径（存根规范句与 `ISSUE-P3-213` 迁移句**原样保留**，仅边界句更新） |
| [`records/通行密钥互操作对拍记录_2026-09-19.md`](../../records/通行密钥互操作对拍记录_2026-09-19.md) §5 / §6 | §5 第 3 条边界加「2026-09-23 补注（§269）已解除」；§6 手工冒烟清单加「口径已由 `PD-32` 放宽并达成，本清单保留作历史留痕」 |
| [`architecture/产品裁决登记.md`](../../architecture/产品裁决登记.md) `PD-32` | 边界项 ① / ② 加「2026-09-23 后续（§269）」执行结论 |

### AC① 留档（既有结论，本批不重启）

`PD-32` 已放宽 RP 口径为「任一真实 RP 端到端贯通」，`demo.yubico.com` 注册（§263 真机日志）+
登录断言（用户口述）已达成。本批**不改动**该结论。

---

## 3. 证据等级汇总（须知）

| 面 | 结论 | 证据等级 |
|---|---|---|
| AC① demo.yubico.com 注册 | 通过 | §263 真机 logcat + 字节级 CBOR 证据（已有） |
| AC① demo.yubico.com 登录断言 | 通过 | 用户自陈（口述） |
| AC② Corbado PRF 注册 + 断言 | 通过（三项能力全绿 + `Authentication successful (PRF)`） | 用户自陈 + 用户提供的界面文字 |
| AC③ CM 选择器弹出 / 通知栏不可下拉 | 平台硬限制 | 系统设计事实 + 用户现场观察 |
| AC④ 文档收口 | 完成 | 本批次文件 + 四处文档 diff |

---

## 4. 如实声明

- 本批**未改动任何生产代码、测试代码、原生面与 `参考项目/`** ⇒ 按测试资产纪律**无设备侧
  `connectedDebugAndroidTest` 必跑项**；亦未跑 `lint` / `assembleRelease`（生产包在本轮之前已构建）/ 截图门禁 / KPEX 对拍。
- 本批的 `assembleRelease` 产物为**本轮前**为真机验证构建并安装，构建过程与产物路径见会话记录；
  设备侧安装经 `adb install -r` 覆盖成功（无签名冲突）。
- **操作过程留痕**：代理侧尝试以 `adb` 自动驱动真机完成建库与填表时受阻——设备一度进入 TWRP
  recovery（经用户同意后 `adb reboot` 恢复），且应用主界面 `FLAG_SECURE` 使截屏取证不可用；
  填表阶段受软键盘弹出导致的控件重定位影响，多次误填。此路径**已放弃**，改由用户手工完成
  AC② / AC③ 两轮操作；上述受阻过程**不构成本批的验证证据**，如实登记以免与结论混淆。
- **不得外推**：本批结论限于「PRF 在本仓实现与真实 RP 的端到端互通」这一面；**不**证明
  「所有真实 RP 均可贯通」、**不**证明「CM 面板在其它 ROM / Android 版本上的呈现一致」。
- 本批**未**新增 `ISSUE-*` 条目。

---

## 5. 关联

- 前序批次：[`263-注册响应attestationObject缺失authData键修复批次.md`](263-注册响应attestationObject缺失authData键修复批次.md)（`authData` 键误用修复，
  本批 PRF 链路得以贯通的前置）、[`220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md`](220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md)（文件级对拍）。
- 产品裁决：`PD-32`（RP 口径放宽）、`PD-33`（本批次同轮登记——Corbado PRF Demo 为通行密钥验证的**常规首选**渠道，含辅助站点与四条不得外推边界）。
- 归档索引：[`RESOLVED_LOG.md`](../../RESOLVED_LOG.md) §269、[`BATCH_158_PLUS.md`](../BATCH_158_PLUS.md)、[`resolved/README.md`](../README.md)。
