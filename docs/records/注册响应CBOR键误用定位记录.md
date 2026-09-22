# 注册响应 CBOR 键误用定位记录（`ISSUE-P2-265`）

> **结论一句话**：注册响应的 `attestationObject` 里，承载认证器数据的 CBOR **键**被写成了 `authenticatorData`
> （响应 JSON 的字段名，17 字符），而 CTAP2 §6.5.4 规定的是 **`authData`**（8 字符）。系统侧只校验响应 JSON 的
> 合法性、不解析 CBOR，因此一路「成功」；浏览器侧按规范查找 `authData` 失败，把 `UnknownError` 抛给网页——
> **依赖方从未收到任何凭据**。
>
> 缺陷本体与整改见 [`resolved/batches/263-注册响应attestationObject缺失authData键修复批次.md`](../resolved/batches/263-注册响应attestationObject缺失authData键修复批次.md)。
> 本文件只承载**定位过程、字节级证据与走偏路径留痕**。

---

## 1. 症状与环境

- **症状**（用户报告）：通行密钥注册时本地显示「已添加」，但网站一律判定「未成功验证」；**换浏览器、换站点、
  浏览器与原生 App 表现一致**；同设备 / 同站点 / 同浏览器下，参考实现 **Monica 100% 成功**。
- **设备**：`M332BF`，Android 17 / `user` build（`ro.build.type=user`）/ **Google WebView**（`com.google.android.webview`）
  + 有 GMS；装有 LSPosed 模块把凭据提供者锁定到 KeePasskey（`credential_service_primary` = 本应用）。
- **被调用方**：`com.keepasskey`（release 包，provider = `KeePasskeyCredentialProviderService`）。
- **调用方**：`mark.via`（浏览器委派，`isOriginPopulated=true`）与 `com.ss.android.lark`（原生应用）两条路径。

---

## 2. 决定性证据

### 2.1 浏览器侧的两行错误（唯一真正报出病因的地方）

```
E chromium: [ERROR:components/webauthn/android/fido2credentialrequest_native_android.cc:59]
  MojoClassFromJSON failed to convert JSON: field missing or invalid: attestationObject
E cr_ChromiumWebauthn: [CredManHelper] Failed to convert response from CredMan to Mojo object: {…完整响应 JSON…}
```

`field missing or invalid: attestationObject` 的关键在于：**`response.attestationObject` 明明在 JSON 里**，
Chromium 却说它「缺失或无效」——说明它查找的**不是 JSON 字段，而是该字段解码后 CBOR 内部的键**。

### 2.2 系统侧为何一路绿灯（误导性来源）

```
I/CredentialManager: starting executeCreateCredential with callingPackage: mark.via
I/KeePasskeyCredProvider: onBeginCreateCredentialRequest 收到系统创建请求
I/CallingOriginResolver: 已取得系统背书 origin，无需降级
I/CredentialManager: Remote provider responded with a valid response
I/CredentialManager: Final credential received from: com.keepasskey/…KeePasskeyCredentialProviderService
Got provider activity result: {…, resultCode=-1 }        ← RESULT_OK
```

**CredMan 不解析 CBOR**：`CreatePublicKeyCredentialResponse` 只校验字符串是合法 JSON。故「系统侧成功」与
「网站失败」可以同时成立——这一点是本缺陷最容易被误读之处，也是此前多轮定位走偏的根源。

### 2.3 字节级还原（把推断变成眼见）

把「我们实际交给系统的完整响应」取出后，对 `attestationObject` 做 base64url 解码 + 十六进制还原：

改前（22:02）：

```
a3                        map(3)
63 66 6d 74               "fmt"
64 6e 6f 6e 65            "none"
67 61 74 74 53 74 6d 74    "attStmt"
a0                        {}
71 61 75 74 68 65 6e 74 69 63 61 74 6f 72 44 61 74 61   ← text(0x11 = 17)  ← 就是它
58 94                     bytes(148)
c4 6c ef 82 … 5d 03 b7    (= SHA256("demo.yubico.com")，逐字节一致)
5d 00 00 00 00            flags=UP|UV|BE|BS|AT, signCount=0
d8 a7 de 40 … 35 7f       AAGUID
00 10                     credentialId 长度 = 16
```

改后（22:12 / 22:13）：

```
a3 63 66 6d 74 64 6e 6f 6e 65 67 61 74 74 53 74 6d 74 a0
68 61 75 74 68 44 61 74 61                              ← text(0x08 = 8)，规范键
58 94 …
```

同一份 148 字节 authData、同一台设备、同一个站点，**唯 map 键不同**。

### 2.4 Chromium 侧链路（报错出处）

- `MojoClassFromJSON` 只是「Java String → JSON → Mojo 序列化字节」的包装器
  （`components/webauthn/android/fido2credentialrequest_native_android.cc`）；
- 注册方向的 `parse_func` 是 `webauthn::MakeCredentialResponseFromValue`
  （`components/webauthn/json/value_conversions.cc`）——**「field missing or invalid」这句就来自这里**；
- 故报错语义是：*能把 JSON 读成对象，但按规范在 CBOR 内找不到 `authData`*。

### 2.5 复现取数的命令骨架

```bash
# 1) 触发一次完整注册（真实指纹确认），清空日志后取全量缓冲
adb logcat -b all -c && adb logcat -b all -d -v threadtime > full.log
# 2) 浏览器侧判定（决定性）
grep -aE "MojoClassFromJSON failed|Failed to convert response from CredMan" full.log
# 3) 取我们实际交出的响应并逐字节还原 attestationObject
grep -a "TEMP-RESP" full.log | tail -1 | sed 's/.*TEMP-RESP //' > resp.json
grep -oE '"attestationObject":"[^"]*"' resp.json | sed 's/.*:"//;s/"$//' \
  | tr '_-' '/+' | base64 -d | od -An -tx1 -N 26
```

> 一次性诊断日志（`TEMP-RESP` / `TEMP-DIAG`）**已于本批移除**，不得留在任何构建里（会打印站点域与凭据材料）。

---

## 3. 走偏路径留痕（五条，防止复发）

| # | 走偏的结论 | 为什么错 | 证伪方式 |
|---|---|---|---|
| 1 | Via **未接入** CredMan 委托 | 首次测试时设备 `credential_service` 为空、`credential_service_primary` 为 null（请求在框架层被丢弃），把**环境态**当成了**浏览器能力**结论 | 环境修正后同一 Via 的请求链路全通（`callerPackage=mark.via`） |
| 2 | **`origin` 降级为 `apk-key-hash`** 导致 RP 拒收 | 该假设能自洽解释「本地成功、网站失败」，且与「原生 App 与浏览器都一样」吻合——但它解释的现象**在修复后依然同时出现且两条都成功** | 修复后 web origin 与 `apk-key-hash` origin **同时**通过 |
| 3 | 需要 `https://{rpId}` **兜底**（并据此新增了参数与开关） | 两个**可用**实现在调用方未携带 origin 时**一律**颁发 `apk-key-hash`；该兜底还会推翻设备侧已固化的「降级后不得是 web origin」断言 | 已在 §263 批次**整条回退** |
| 4 | `response.publicKey` 用 COSE 是**病因** | 该字段编码确实偏离规范（W3C 要求 SPKI DER），且是「同机 Monica 成功」对照下唯一可见差异——但它**不是**本次病因 | 改为 SPKI 后仍失败；真因在金标错误的 CBOR 键（SPKI 修正**独立成立**，故保留） |
| 5 | 白名单来源太窄（应改用 Google GPM 列表） | 本机 Via 走的是「已取得系统背书 origin」分支（命中白名单），与被测失败面无关 | 归属诊断日志：`originKind=web` |

**共同教训**：每次都找到了一条「能自洽解释症状」的假设并据此改代码——而**症状可以被多条不同的原因同时解释**。
唯一能终止这种循环的是**外部权威的错误源**（此处＝浏览器自己的 `MojoClassFromJSON` 日志 + 字节级还原），
而不是「与参考实现逐字段比对」。后者能列出差异，但**无法判定哪一条差异是病因**。

---

## 4. 镜像测试盲区（本缺陷长期潜伏的直接原因）

原 `PasskeyRegistrationMaterialInteropTest` 的 CBOR 解析写成：

```kotlin
when (cursor.readTextString()) {
    WebAuthnJson.FORMAT -> …
    WebAuthnJson.ATTESTATION_STATEMENT -> …
    WebAuthnJson.AUTHENTICATOR_DATA -> authData = cursor.readByteString()   // ← 与被测代码共用同一常量
    else -> error("出现规范外的 attestationObject 键")
}
```

断言用的 `WebAuthnJson.AUTHENTICATOR_DATA` 与被测代码**同源** ⇒ 常量本身写错时，**实现与测试一起错**，
该用例恒绿。**修复方式**：断言一律改用**规范字面量**（`"fmt"` / `"attStmt"` / `"authData"`），并再加一条
**不依赖任何共享常量**的原始字节断言（CBOR 必须含 ASCII `authData`、不得含 `authenticatorData`）。

> 补充发现：`response.publicKey` 的旧行为**没有任何用例锁定**（全仓 `grep` 命中 0），
> 故 `ISSUE-P2-265` 将其改为 SPKI 不构成「改测试来迁就实现」。

---

## 5. 操作事故留痕（⚠️ 必读）

2026-09-22 22:30，为执行被修改的设备用例而运行 `:app:connectedDebugAndroidTest` 时，测试平台在
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`（设备上是 **release 签名**包、测试装的是 **debug 签名**包）之后
**卸载了设备上的 `com.keepasskey`**：

```
E/SplitApkInstallerBase: Failed to commit install session … Error: INSTALL_FAILED_UPDATE_INCOMPATIBLE:
  Existing package com.keepasskey signatures do not match newer version; ignoring!
```

- 后果：应用**内部**密码库（含本轮测试期间登记的通行密钥）**永久丢失**——该应用 `android:allowBackup="false"`
  ⇒ 无系统备份可恢复；`su` 在 `adb shell` 下不可达 ⇒ 卸载后无法抢救数据目录。
- 设备共享存储中的用户自有库文件（`Download/keepass.kdbx`、`11.kdbx` + `111.keyx` 等）**未受影响**。
- 用户裁决：**密码库不需要恢复**。
- **立规**（已写入 `AGENTS.md` §5）：对**装有需要保留的应用**的设备，**禁止**直接跑
  `connectedDebugAndroidTest`——`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 会触发卸载。应改用 AVD，
  或先确认设备上无需保留该应用的任何数据。

---

## 6. 环境要素（供复现判读）

| 要素 | 值 | 为什么重要 |
|---|---|---|
| `ro.build.type` | `user` | 无 root shell ⇒ 无法直接读应用数据目录、无法注入生物识别结果 |
| WebView 提供者 | `com.google.android.webview` | **会**产生 `cr_ChromiumWebauthn` / `chromium` 日志——病因正是从这里读到的；AOSP WebView 或旧版本可能不产生同样的报错行 |
| GMS | 有 | `getOrigin` 的白名单判定路径依赖平台实现 |
| LSPosed 模块 | `io.github.howard20181.hyperpasskey`（HyperMonica） | 把凭据提供者**锁定**到 KeePasskey（不做 origin 干预，用户已确认）；不锁定则系统选择器可能落到别的 provider |
| 对照实现 | Monica（`takagi.ru.monica`）、KeePassDX（`com.kunzisoft.keepass.libre`） | 用户现场对照：同环境 Monica 成功 |
