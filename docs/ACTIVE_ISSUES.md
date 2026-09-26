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

> **暂无开放项**。2026-09-24 同步/加密/passkey 安全审计批五条（`ISSUE-P2-308` ~ `ISSUE-P2-312`）
> 已全部闭环：§315（309 / 312）、§317（308）、§318（310）、§319（311）、§320（313）；
> 2026-09-25 CI 设备门禁与供应链扫描两条（`ISSUE-P2-314` / `ISSUE-P2-315`）闭环见 §325。

## P3 低危问题、特性接线与体验优化（1 项）

> **开放项 1 条**（`ISSUE-P3-337` 扫码导入通行密钥，2026-09-26 立项）。
> 2026-09-26 相册导入读尺寸判空修复（`ISSUE-P3-336`）闭环见 §341；
> 更早的 P3 闭环流水见 `RESOLVED_LOG.md` §326 ~ §340。

### ISSUE-P3-337：PD-08 扫码导入通行密钥落地——顶栏扫码按载荷分流（TOTP / 通行密钥），确认在先、字节通道解析、落 `KPEX_PASSKEY_*`

- **优先级理由**：属「进阶特性接线」（P3），非缺陷；**不含**新协议面（hybrid / caBLE 跨设备注册另案，见「关联」）。
- **核实时间点**：2026-09-26（本条目立项当日）。
- **核实方式**（逐条可复跑）：
  1. 全仓 `grep -rn --include=*.kt -lE "PasskeyImport|importPasskey|CredentialExchange|PasskeyDictionary|cxf"`
     于 `app|database|core` 的 `src/main` → **零命中**；`res/values/strings.xml` 无 passkey 导入/导出/CXF 文案
     ⇒ **`PD-08` 已裁决但未实现**（非"部分实现"）。
  2. `core/.../model/PasskeyKeyText.kt:107 derToPemChars(der: ByteArray)` **已存在**（PKCS#8 DER → PEM，
     零 `String` 中间量、`finally` 清零）；`:81 pemToDer`、`:145 sniffAlgorithmId`（PEM / hex 标量 / Base64 /
     裸 DER 四级嗅探，`ISSUE-P3-214`）均现成 ⇒ **无需新写 DER→PEM**。
  3. `app/.../passkey/SimpleJson.kt:37 parse(text: String)` 入参为 `String` ⇒ **不得**用于 CXF 载荷：
     CXF 的 `key` 字段是 PKCS#8 私钥，经 `String` 即物化为不可擦除对象，违反 `AGENTS.md` §3 敏感数据铁律
     （同文件既有先例见 `core/.../PasskeyKeyText.kt:20-24` 的「全部 API 只接受/返回 `ByteArray`」纪律）。
  4. 顶栏扫码链现状（`VaultListViewModel.kt:451 onQrCodeDecoded` → `VaultListActionController.kt:296
     addEntryFromScannedOtpauth`）：只读门槛 → **`otpauth://` 前缀强校验**（`:288-290` 记有教训：扫码面对
     任意二维码，宽容解析器会把纯字母单词当 Base32 种子）→ CharArray→UTF-8 字节（含编码器内部 buffer
     一并清零）→ 解析 → 元数据先取、种子副本即刻擦 → 建条目 → `saveEntry(totpSecretChars=…)` 擦除契约
     + 协程体 `finally` 幂等兜底 ⇒ **本条目的通行密钥分支按同族模板写**。
  5. 取景与相册通路（`TotpScanDialog`：CameraX + `MultiFormatReader`(POSSIBLE_FORMATS=QR, TRY_HARDER)
     + 四朝向重试 + `MAX_GALLERY_IMAGE_DIMENSION=2400` 有界位图解码，§340/§341 已合入**同一对话框**）
     ⇒ **相机与相册两个入口均现成，本条目零改动**（PD-08 要求的"双入口"由此满足）；
     **两条通路共用同一解码器**：`TotpGalleryImport.kt:211-253` 与 `TotpScanDialog.kt:357-380` 都是
     「灰度字节 → `PlanarYUVLuminanceSource` → `HybridBinarizer` → `rotateYPlane90` 四朝向」，
     相册侧仅多一步 ARGB→亮度灰度（`Y=(299R+587G+114B)/1000`）与透明像素合成白底（§341）。
     ⇒ **本条目不需要新增任何解码代码**（早前一版正文误写为「相册走 `RGBLuminanceSource`」，已更正）。
  5b. 字节通道原语核对：`WebAuthnRequestOptions.kt:146 base64UrlDecode(text: String)` 为 **String 入参**，
     不可用于私钥；须直接调 `Base64.getUrlDecoder().decode(ByteArray)`（仓内先例
     `PasskeyRegistrationPayload.kt:86`）。
  5c. 编辑页 KPEX 写入口：`PasskeyEntryCoordinator.kt:40 saveOrReplacePasskeyEntry(data, boundPackage)`
     （内部 `:83 findReusablePasskeyEntry` 实现「找到可复用条目则整体替换」，`:111 saveNewPasskeyEntry` 新建）
     ⇒ **Q1 编辑页入口须复用 `saveOrReplacePasskeyEntry`，不走通用 `saveEntry`**；其 `PasskeyData`
     私钥的清零义务归属须在开工首步核实（与顶栏 `saveEntry(totpSecretChars=…)` 的擦除契约不同源）。
  5d. 导航能力核对：`Screen.EntryEdit.createRoute(id)` 与路由的 `entryId` 参数**均已存在**
     （`KeePasskeyNavGraphRoutes.kt:186` 详情页→编辑页就在用；`:193-212` 声明 `entryId`/`groupId`/`templateId`
     三个可空参数）⇒ 顶栏只需把回调透传进 `VaultListScreen`，**无需新建导航机制、无需 ViewModel 新消费链**；
     仅「打开后聚焦通行密钥区块」需要新增一个可选字符串参数。
     ⚠️ **不得**借 `templateId` 那类路由参数承载私钥（参数只带 id、目标页自查库的模式对"尚未入库的私钥"不适用）。
  6. **CXF v1.0 PS 规范原文逐字核对（2026-09-26，直取 `cxf-v1.0-ps-20250814.html` 全文本地解析）**：
     - §3.3.12 `Passkey` 字典 ABNF **逐字**为
       `{ type:"passkey", credentialId:b64url, rpId:tstr, username:tstr, userDisplayName:tstr,
          userHandle:b64url, key:b64url, ?fido2Extensions:Fido2Extensions }`
       —— **`alg` 成员不存在**；`username`（小写 n）与 `userDisplayName` **无 `?` 前缀，即必填**；
       唯一可选成员是 `fido2Extensions`。
     - 文档结构：`Document{ … accounts:[*Account] }` → `Account{ … collections:[*Collection], items:[*Item] }`
       → `Item{ … credentials:[*Credential] }`；而 `Collection.items` 是
       **`LinkedItem{ item:b64url, ?account:b64url }`（仅引用，不含凭据）**
       ⇒ **凭据实际路径 = `accounts[].items[].credentials[]`**；`PD-08` 原文所写
       `collections[].items[].credentials[]` **系规范引用错误**（本条目初稿照抄，已一并更正，见「关联」）。
     - §3.3.12.2 `Fido2Extensions{ ?hmacCredentials, ?credBlob, ?largeBlob, ?payments }`，其中
       `hmacCredentials{ algorithm, credWithUV, credWithoutUV }` 原文写明「holds the information necessary
       for either the [webauthn-3] **prf extension** or the [FIDO-V2.1] hmac-secret extension」
       ⇒ **CXF 确实承载 PRF 秘密**（本条目初稿「CXF 不承载 prf」为错，见整改口径 6）。
     - §3.3.12.1 原文：「All other members of the `Passkey` dictionary MUST NOT be user editable
       as they are required for the WebAuthn ceremonies to be successful.」

  7. `PD-08` 正文原写「相机扫码复用 **`SecureCaptureActivity`** 受保护取景」（现第 2 项），该组件已随
     `ISSUE-P3-319` 退役（`app/src/main/AndroidManifest.xml:244-246` 注释在案）
     ⇒ **裁决文档内的过时组件引用，已随本条目 2026-09-26 改写第 2 项时一并勘误**。
  8. `PopupSecureFlagInventoryTest`（`app/src/test/.../security/`，2026-09-26 盘点结论：调用点 4 处 /
     菜单项 11 个，全部静态文案）与 `PD-47`（扫码对话框 `FLAG_SECURE` 跟随设置开关）为本条目的守卫账目。

- **背景**：`PD-08`（2026-09-18）已裁决载荷 = **FIDO CXF v1.0 单凭据 `Passkey` 字典 JSON**（`credentialId` /
  `userHandle` / `key` 为 Base64URL，`key` = PKCS#8 ASN.1 DER），解析器须兼容三级形态（裸 Passkey 对象 →
  CXF 凭据数组 → CXF 完整文档 `accounts[].items[].credentials[]`，取首个有效凭据）并兼容 KeePassXC
  `.passkey` 单对象 JSON；落库口径 = `KPEX_PASSKEY_*`（细则 `PD-09`），私钥走受保护字段 CharArray 链路、
  还原为 **PKCS#8 PEM** 驻留格式，URL / 用户名留白以 rpId / userName 补齐（⚠️ 该「留白补齐」口径与规范
  §3.3.12 的必填要求**冲突**，裁决见整改口径 3 与「未决」第 1 项），条目已有通行密钥时整体替换。
  本次用户已定两点：**Q1 编辑页通行密钥区块保留导入入口（挂到当前条目、可整体替换）**；
  **Q2 顶栏扫到通行密钥时不得静默建条目，须先经用户确认**。

- **整改口径**：
  1. **分流**：`onQrCodeDecoded` 内先做纯函数字节判定 `ScanPayloadClassifier.classify(bytes): Totp | Passkey | Unknown`
     （跳过前导空白后：`otpauth:` 前缀（忽略大小写）→ `Totp`；`{` 或 `[` 起始 → `Passkey`；其余 → `Unknown`）。
     **禁止回退式猜测**（不得"先按 TOTP 解析、失败再按通行密钥"）；`Unknown` 复用现键
     `vault_scan_invalid_qr` 如实提示、不落库、不回显内容。**TOTP 分支一字不改**，其现有失败路径与
     前缀强校验口径原样保留。
  2. **解析器（新增 `app/.../passkey/PasskeyCxfReader.kt`，纯 Kotlin 零 Android 依赖）**：入参 `ByteArray`
     （UTF-8 载荷字节），**手写最小 JSON 定位扫描器**，按规范白名单字段取值——
     `type` / `credentialId` / `rpId` / `username` / `userDisplayName` / `userHandle` / `key` /
     `fido2Extensions`（含其子项 `hmacCredentials{algorithm, credWithUV, credWithoutUV}` / `credBlob` /
     `largeBlob` / `payments`）；**全部值以 `ByteArray` 产出**，非敏感字段转 `String` 供 UI，
     私钥永不进 `String`（Base64URL 解码须 `Base64.getUrlDecoder().decode(ByteArray)` 字节直调，见核实 5b）。
     嵌套定位三条：裸 Passkey 对象、凭据数组、完整文档 `accounts[].items[].credentials[]`
     （**`collections[].items[]` 是 `LinkedItem` 引用，不含凭据，不得当取凭据路径**）。
     KDoc 须写明「为何不用 `SimpleJson` / 不用 `org.json`」（前者入参 `String` 违反 §3；后者在宿主单测为
     未实现桩，同 `CallingOriginResolver.kt:136-138` 既有理由）。
  3. **算法与字段判据（fail-closed，逐值写死）**：**规范无 `alg` 成员** ⇒ 算法一律由
     `PasskeyKeyText.sniffAlgorithmId(der)` **从 PKCS#8 DER 的 OID 判定**，判定为空（无已知 OID）即拒；
     **不得**再写「`key.alg` 与嗅探交叉核对」（该判据对 CXF 载荷恒不触发，属虚构）。
     必填缺失判据分两层（规范 `username` / `userDisplayName` 亦为必填，但只具展示语义）：
     ① **参与 WebAuthn 仪式的字段**（`type`=="passkey" / `credentialId` / `rpId` / `userHandle` / `key`）
     任一缺失、Base64URL 非法、DER 无已知 OID、嵌套层级不符 ⇒ **一律拒绝入库**；
     ② 仅 `username` / `userDisplayName` 缺失 ⇒ **容错补齐**（以 rpId / 空串占位），并在条目上
     **如实标注「来源未提供」**，不拒收（**2026-09-26 用户裁决 `PD-48` 裁决一**：展示字段不参与仪式，
     为其拒收等于白扔一把完好的私钥，且一刀切会误伤 `PD-08` 已裁决兼容的 KeePassXC `.passkey` 对象）。
     补齐值**只进展示字段**，**不得**回填 `userHandle` 等仪式字段。
     错误一律只报静态错误码文案，不回显载荷。
  3b. **字段只读口径（规范 §3.3.12.1 原文要求）**：`Passkey` 字典除 `username` / `userDisplayName` 外的成员
     「MUST NOT be user editable」⇒ 导入后的条目在编辑页**不得**允许手改 `rpId` / `credentialId` /
     `userHandle` / `key`（现编辑页若这些字段可写，须同批锁为只读并在 UI 上如实标注原因）。
  4. **Q2 的实现取向（关键设计）**：**私钥不跨页承载**。顶栏分支在**当前作用域**内解析成功后弹一个
     轻量确认对话框，只呈现**非敏感元数据**（rpId / 算法 / credentialId 摘要 / 拟用标题），
     用户确认 → 以与 `addEntryFromScannedOtpauth` 同族的 `addEntryFromScannedPasskey(chars)` 落库 →
     成功后按 id 打开该条目编辑页；用户取消 → 擦除、不落库、不导航。**不引入 draft / SavedStateHandle
     承载敏感值**（`P2-105` 立过「不得经框架缓存敏感值」的规矩）。
     导航能力已核实为现成（见核实 5d：`Screen.EntryEdit.createRoute(id)` + `entryId` 参数已在用），
     本条目只需把回调透传进 `VaultListScreen`；「打开后聚焦通行密钥区块」至多新增一个**可选字符串参数**
     （取值如 `passkey`），**不得**在路由参数里承载任何凭据类值。
     ⚠️→✅ 确认对话框的 `FLAG_SECURE` 归类**已裁决**（2026-09-26 用户，`PD-48` 裁决三）：
     **跟随「禁止截屏与录屏」开关**，与 `PD-47` 同一口径，**不**列入 4 类无条件强制遮罩对话框；
     另两层防护（反 overlay / 点击劫持过滤）始终施加。
  5. **Q1 编辑页入口**：通行密钥区块新增「扫码 / 相册导入」，复用同一对话框与同一 `PasskeyCxfReader`，
     落库**须复用 `PasskeyEntryCoordinator.saveOrReplacePasskeyEntry`**（`:40`，其 `:83
     findReusablePasskeyEntry` 已实现「找到可复用条目则整体替换」，正是 Q1 语义），
     **不走通用 `saveEntry`**；KDBX 历史快照可回滚。两条写入口的私钥清零义务归属不同源，须分别立断言（AC③）。
     **可选收敛（不作本条目硬要求）**：顶栏新建分支亦改走 `PasskeyEntryCoordinator.saveNewPasskeyEntry`
     （`:111`，其 `toCustomFields` 自带逐键保护位与 `passkeyTitle` 口径）⇒ 两条写入口合成一条、
     AC④ 的保护位断言可只测一处。采纳前须先核两点：① 其 `parentGroupId = null` 时的**落组语义**是否等于
     「当前分组」（顶栏要求落在用户当下所在分组）；② `PasskeyData` 内私钥材料的**清零义务归属**。
  6. **落库**：`key` 的 Base64URL DER → `PasskeyKeyText.derToPemChars` → 构造 `PasskeyData` →
     `KPEX_PASSKEY_*` 字段（明文：`_RELYING_PARTY` / `_USERNAME` / `_FLAG_BE` / `_FLAG_BS`；
     **受保护仅四键**：`_USER_HANDLE` / `_CREDENTIAL_ID` / `_PRIVATE_KEY_PEM` / `_PRF`）；
     **PRF 不是"留空"**——规范 `fido2Extensions.hmacCredentials{algorithm, credWithUV, credWithoutUV}`
     明确承载 webauthn-3 `prf` / FIDO `hmac-secret` 秘密，而本库 `_PRF` 只有**单一秘密**字段，
     存在真实 schema 落差 ⇒ 须裁决「双值取哪一个 / 是否扩 schema」（见「未决」第 2 项）；
     载荷含 `credBlob` / `largeBlob` / `payments` 等本库不承载的扩展时，**必须如实提示丢弃项，禁止静默丢弃**；
     `_FLAG_BE` / `_FLAG_BS` 按 `PD-09` 口径置位；标题 = **rpId**（`userDisplayName` 是不可信自由文本，
     只进用户名字段，不得作为标题——避免二维码决定给用户看的字）。
  7. **零网络**：全程不发起任何请求（载荷内出现 URL 也不解析、不访问）。
  8. **同批文档流转**：改写 `PD-08` 的入口口径（编辑页双入口 → **顶栏分流为主入口 + 编辑页保留附加入口**）
     并登记改写日期与理由；修正其「复用 `SecureCaptureActivity`」过时引用为现件；`PD-34` 类型名有界性
     机检若命中新类型名则同批扩登记。

- **验收标准**：
  - **AC①** 分流纯函数表驱动用例穷举 `Totp / Passkey / Unknown` × 前导空白 × 大小写 × `{`/`[` 起始 ×
    纯字母文本（不得被当成种子）⇒ **三条路径均可判定且无回退猜测**。
  - **AC②** `PasskeyCxfReader` 用例覆盖：三级 CXF 形态各正例（完整文档正例**必须**用
    `accounts[].items[].credentials[]` 构造，并加一条「凭据挂在 `collections[].items[]` 下」的**负例**，
    锁死不再照抄错误路径）、KeePassXC `.passkey` 正例、`fido2Extensions.hmacCredentials` 正例、
    拒绝路径 **≥8 条**（缺 `credentialId` / 缺 `userHandle` / 缺 `key` / 非法 Base64URL / DER 无已知 OID /
    未知 `type` 值 / 嵌套畸形 / 超长载荷），且断言实参一律取自被测返回值（过 `check_tautological_assertions.py`）。
    ⚠️ 夹具纪律：KeePassXC `.passkey` 正例**须取真实产物**（由本机 `keepassxc-cli` 导出或取仓库语料），
    **不得**按文档臆造字段。
    ⚠️ 第 2 片与「未决 2」的边界：PRF 落库口径**已定 (`PD-48` 裁决二)**，故本片不仅要**检出载荷含
    `fido2Extensions.hmacCredentials` 及其双值**（供 AC⑪② 的丢弃清单与后续扩展键落库复用），
    双值本身也须随本片一并解析出来（供第 3 片写 `Passkey.PrfNoUv` 使用）。
  - **AC③ 私钥不经 `String`**：解析与落库全链只出现 `ByteArray`/`CharArray`；加一条断言核验
    「私钥字节在回调返回后已被清零」（仿 §341 相册整改的擦除断言口径），并核验取消路径同样已清零。
  - **AC④ 无原始载荷残留 + 保护位逐键断言**：① `FakeVaultRepository` 观测点断言落库条目仅含
    `KPEX_PASSKEY_*` 字段，**不含**整段 JSON、不含备注/附件任何形式的载荷副本；
    ② 逐键断言保护位为「明文四键 `_RELYING_PARTY` / `_USERNAME` / `_FLAG_BE` / `_FLAG_BS`；
    受保护四键 `_USER_HANDLE` / `_CREDENTIAL_ID` / `_PRIVATE_KEY_PEM` / `_PRF`」。
    ⚠️ 该断言**前提是观测点真的携带保护位**：`FakeVaultRepository` 目前只记 `lastSavedTotpByEntry`
    （`:49`，且在 `:215` 转成 `String`），须新增 `lastSavedPasskeyByEntry` 并**保留 `isProtected`**，
    否则「逐键受保护」断言会因观测点丢位而空转通过（真假绿路径）。
    写通路本身已逐键尊重声明位（`VaultEntryWriteCoordinator.kt:188` `if (cf.isProtected) …`、
    `:205` `isProtected = cf.isProtected`），且该划分在 schema 路径已有锁
    （`core/src/test/.../PasskeyDataSchemaInteropTest.kt:41-68`）——**本 AC 只补导入通路这一层**。
  - **AC⑤ TOTP 零回归**：`ISSUE-P3-332` 顶栏扫码链与编辑页 TOTP 扫码的既有用例（含
    `VaultListScanEntryPointTest`）不改一字仍绿；`TotpScanDialog` 的取景/相册实现只允许**参数化**，
    不允许复制第二份。
  - **AC⑥ 守卫账目同步**：新增确认对话框后，`PopupSecureFlagInventoryTest` 重新盘点（计数与结论注释
    写明「2026-09-26 分流不新增菜单项 / 新增 1 个确认对话框」），静态文案判据继续成立
    （提示语无凭据类插值）；`PD-47` 的 `FLAG_SECURE` 接线在确认对话框与两个入口均落实；
    确认对话框的**归类已定稿**（`PD-48` 裁决三：跟随开关），本 AC 只核验接线，不再涉及归类裁决。
  - **AC⑦ 互操作对拍（`AGENTS.md` §5 / 规则 8）**：导入得到的凭据入库后跑
    `:database: PasskeyInteropProbeTest` → `python tools/passkey-interop/verify_interop.py`（114 判据），
    ES256 必过；`Ed25519` / `RS256` 按白名单实际放行范围读数原样进批次文档。
  - **AC⑧ 真机端到端（只有真机能证伪）**：在 **AVD `Pixel_10` 或实验机**上（⚠️ 当前在连的小米 M332BF
    装着真实密码库与 `hyperpasskey` 模块，**禁跑 `connectedDebugAndroidTest`**）验证「导入的通行密钥
    能经 CM 通道对真实 RP 完成 GetAssertion」（RP 渠道按 `PD-32` / `PD-33`）；**并**用真机实拍的高密度
    QR 取样相册识码（§340 留痕「相册通路真机冒烟未做」，而 CXF JSON 远长于 otpauth URI、码密度更高，
    合成图断言不足以证明可用）。
  - **AC⑨ 全量与门禁**：`.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿
    （计数只用 `python tools/doc/count_test_results.py`）+ `python tools/doc/gate_readings.py` **7/7 PASS**
    且读数块**原样**贴入批次文档 §3（逐条 EXIT）+ `check_md_links.py` + `check_resolved_index_sync.py`。
  - **AC⑩ 文案纪律（`§292`）**：导入成功/失败文案一律写「FIDO2 软件密钥（库内加密存储）」口径，
    **禁用**「芯片 / 硬件」类表述。
  - **AC⑪ 规范符合性两条**：① 导入后的条目在编辑页**不可手改** `rpId` / `credentialId` / `userHandle` /
    私钥（§3.3.12.1「MUST NOT be user editable」），须有用例锁住（若现页本就只读，也要有用例证明之）；
    ② 载荷携带本库不承载的扩展（`credBlob` / `largeBlob` / `payments`）时，**须有可见提示逐项如实列出被丢弃
    扩展名**，禁止静默丢弃（`§292` 同源纪律）。注：`credWithoutUV` 按 `PD-48` 裁决二**全量存入本仓扩展键**，
    **不属**丢弃项，故提示里**不得**出现它。

- **未决与风险（开工时逐项收口）**：
  1. ~~必填边界~~ —— **已裁决（2026-09-26 用户，`PD-48` 裁决一）：取 (b)**。密码学五字段
     （`type` / `credentialId` / `rpId` / `userHandle` / `key`）缺失照旧**一律严拒**；
     仅 `username` / `userDisplayName` 缺失时**容错补齐 + 条目如实标注「来源未提供」**。
     口径 3② 已按本次裁决**同批改写为定稿口径**（不再存在"默认 (a)"的并存指令）。
  2. ~~PRF 双值落差~~ —— **已裁决（`PD-48` 裁决二：采 (c′) 全存）**；上轮两条错误论断的更正记录保留在下方
     「连带更正」（其价值在于：两处误判均因未回读 WebAuthn L3 §10.1.4 原文，留作方法论教训）。
     事实基座（三条均逐字核过）：
     - CXF §3.3.12.3：`Fido2HmacCredentials = { algorithm, credWithUV, credWithoutUV }` 三者必填；导入方
       「**MUST store and use these credential as-is during the HMAC operation. There MUST NOT be any
       additional derivation or domain separators**」；「exporting provider 只支持一个值时 MUST 生成补齐另一值」。
     - **WebAuthn L3 §10.1.4 原文（本轮补读，前两轮双方都漏）**：「The `hmac-secret` extension provides two PRFs
       per credential … **This extension only exposes a single PRF per credential and, when implementing on top
       of `hmac-secret`, that PRF MUST be the one used for when user verification is performed.
       This overrides the `UserVerificationRequirement` if necessary.**」
       ⇒ WebAuthn `prf` 层**无条件只用 withUV**；`credWithoutUV` 在 prf 语义下**零消费方**，
       只承载 CXF 的 MUST-store 保真义务。
     - 本库实现与规范同式，**"须核 computeValue 是否含额外派生"这一取证项就此关闭**：
       `crypto/.../passkey/PasskeyPrf.kt:70-83 computeValue` 把种子**原样**作 HMAC 密钥（无额外派生），
       `:86-96 clientSideProcess` = `SHA-256("WebAuthn PRF" ‖ 0x00 ‖ input)` 正是 §10.1.4 规定的
       **客户端 salt 构造**（注册与断言两侧同式），不属「additional domain separators」。
       `PasskeyAssertionPayload.kt:134-135` 的分支仍在，但那是"缺失即不返回 prf"的如实降级，**不是错值风险**。
     候选处置（**2026-09-26 用户裁决，`PD-48` 裁决二：采 (c′)**；决定性理由是**不可逆性**——
     导入是一次性捕获，第二枚种子这次不存事后永远补不回）：
     - **目标 (c′)**：`KPEX_PASSKEY_PRF` 继续承载 withUV（生态兼容，**断言链与 probe 现用字段零改动**），
       新增本仓扩展键存 `credWithoutUV`（命名如 `Passkey.PrfNoUv`；先例与机制见 `PD-09` 第 3 项本仓扩展键，
       `产品裁决登记.md:320` 的 `Passkey.Algorithm` 等），同批更新 `PasskeyInteropProbeTest` 的
       `KPEX_PASSKEY_*` 键集精确判据并重跑 keepassxc-cli / pykeepass 对拍。
       ⚠️ **(c′) 不含任何「按 UV 选种子」的断言改造**——那既非规范要求，也与 §10.1.4 的 override 语义相悖。
     - ~~**兜底 (a) + 强制披露**~~ —— **已否决，仅留档备查**（不再是待选）：只存 withUV 虽与 (c′) 的
       `prf` 功能等价，但会**永久放弃一个不可恢复的值**并须在 `PD-48` 记 deviation；
       用户裁「半天成本换 CXF 完全合规与免返工」。
       **`credWithoutUV` 因此不属"丢弃项"**——AC⑪② 的点名披露只适用于本库确不承载的扩展。
     - **(b)（整项丢弃）否决**：与 (a) 同样丢 withoutUV，却额外把 `prf` 全灭，无任何收益 ⇒ **被 (a) 严格支配**。
     **连带更正（删除上轮两句错误论断）**：① 「(a) 会算出错值、错值比缺失更糟」——不成立，恒用 withUV
     正是 §10.1.4 的 MUST；② 「(c) 的范围含断言链按 UV 选种子」——不成立，断言逻辑零改动；
     ③ 「宁取 (b) 不取 (a)」——收回，(b) 被 (a) 支配。
  3. 「聚焦通行密钥区块」需给 `Screen.EntryEdit` 加一个可选字符串参数（导航能力本身已核实为现成，见 5d）。
  4. 高密度 QR 的实际解码成功率（AC⑧ 实拍取样后才可声称"相册导入通行密钥可用"）。
  5. ~~`key` 成员"PKCS#8 DER 的 Base64URL"未逐字取到~~ —— **已关闭**（2026-09-26 取到 §3.3.12 原文：
     「The private key associated to this passkey instance. The value MUST be **PKCS#8 ASN.1 DER** formatted
     byte string which is then Base64url encoded.」，与 `PD-08` 第 1 项一致，已同步写入 PD-08 第 4 项补记）。

- **粗估**：**5–7 人日**。构成：手写字节扫描器（含转义与三条嵌套定位）为最大项；**新增** `PD-48` 裁决二
  带来的本仓扩展键（`Passkey.PrfNoUv`）+ `PasskeyInteropProbeTest` 键集判据更新 + 对拍重跑；
  **减少** 相册解码器（零新增，见核实 5）与路由搭建（现成，见 5d）两处。

- **关联**：`PD-08`（载荷与入口裁决。本条目**同时勘误其两处规范引用错误**：凭据路径应为
  `accounts[].items[].credentials[]`、取景组件已由 `SecureCaptureActivity` 更为 `TotpScanDialog`；
  并把「入口」按 Q1/Q2 改写为「顶栏分流为主 + 编辑页保留附加入口」）/ `PD-09`（`KPEX_PASSKEY_*` schema，
  按 `PD-48` 裁决二新增本仓扩展键（命名如 `Passkey.PrfNoUv`）**须同批回写并重跑对拍**）/
  **`PD-48`**（2026-09-26 用户裁决，承载本条目三项裁决：必填边界取 (b)、PRF 双值取 (c′)、
  确认对话框 `FLAG_SECURE` 跟随开关）/
  `PD-34`（类型名有界性机检）/ `PD-47`（扫码对话框 `FLAG_SECURE`）/ `PD-10`（特权浏览器白名单，
  导入路径**不**继承其 origin 保证，须按实际 rpId 归属如实呈现）/ `P2-105`（禁经框架缓存敏感值）/
  `ISSUE-P3-214`（`PasskeyKeyText` 四级嗅探）/ `ISSUE-P3-319`（`SecureCaptureActivity` 退役）/
  `ISSUE-P3-332`（顶栏扫码入口）/ §340 §341（相册识码通路与其真机冒烟留痕）/
  **跨设备扫码注册（hybrid / caBLE）不在本条目范围**——它需要自建隧道中继与 CTAP2 传输栈，
  结论与判据另见后续裁决条目。
