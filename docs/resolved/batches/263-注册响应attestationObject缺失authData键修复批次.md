<a id="s263"></a>

# §263 注册响应 `attestationObject` 缺失规范键 `authData` 修复批次

> `ISSUE-P2-265` **整条闭环**（触发＝用户命题 2026-09-22：「通行密钥注册本地显示添加成功，网站一律说没验证成功；
> 同设备 / 同站点 / 同浏览器，参考实现 Monica **100% 成功**而本仓失败」）。
> 同批一并收口同一对照面下的四项互操作对齐，并**回退**两条已证伪的假设。
> 定位全过程留痕见 [`records/注册响应CBOR键误用定位记录.md`](../../records/注册响应CBOR键误用定位记录.md)。

---

## 1. 缺陷本体：CBOR 键误用（单点根因）

### 1.1 形态

`PasskeyRegistrationPayload.kt` 组装 `attestationObject` 时，把**响应 JSON 的字段名** `authenticatorData`
（`WebAuthnJson.AUTHENTICATOR_DATA`，17 字符）当成了 **CBOR map 的键**。CTAP2 §6.5.4 规定该 map 的字节串键是
**`authData`**（8 字符）——二者只是形近，位置与语义完全不同。

```kotlin
// 改前（缺陷）
WebAuthnJson.ATTESTATION_STATEMENT to emptyMap<String, Any>(),
WebAuthnJson.AUTHENTICATOR_DATA to authDataBytes      // ← "authenticatorData"
// 改后
WebAuthnJson.AUTH_DATA to authDataBytes               // ← "authData"（新增常量，KDoc 写明与 AUTHENTICATOR_DATA 的区别）
```

### 1.2 字节级证据（真机取出，逐字节）

改前（设备 `M332BF`，Android 17 / user build / Google WebView + GMS，2026-09-22 22:02 抓取）：

```
a3                     map(3)
63 66 6d 74            "fmt"
64 6e 6f 6e 65         "none"
67 61 74 74 53 74 6d 74 "attStmt"
a0                     {}
71 61 75 74 68 65 6e 74 69 63 61 74 6f 72 44 61 74 61   ← text(0x11 = 17)
58 94                  bytes(148) …
```

改后（同日 22:12 / 22:13 复测）：

```
a3                     map(3)
63 66 6d 74            "fmt"
64 6e 6f 6e 65         "none"
67 61 74 74 53 74 6d 74 "attStmt"
a0                     {}
68 61 75 74 68 44 61 74 61                              ← text(0x08 = 8)，规范键
58 94                  bytes(148) …
```

—— 承载同一份 **148 字节** authData，唯 map 键不同。同期旁证：该 authData 头 32 字节
`c46cef82ad1b546477591d008b08759ec3e6d2ecb4f39474bfea6969925d03b7` 与
`SHA256("demo.yubico.com")` **逐字节一致**，`flags = 0x5D`（UP|UV|BE|BS|AT）、`signCount = 0`、
AAGUID `d8a7de40…35 7f`、`credIdLen = 0x0010` —— 即**除这一个键名外，响应材料处处正确**。

### 1.3 为何表现为「系统侧成功、网站失败」（误导性来源）

CredMan 对 provider 回传的注册响应**只校验 JSON 合法性**，**不解析 CBOR** ⇒ 系统侧全程绿灯：

```
I/CredentialManager: Final credential received from: com.keepasskey/com.keepasskey.app.passkey.KeePasskeyCredentialProviderService
Got provider activity result: {provider=com.keepasskey/…, resultCode=-1        ← -1 = RESULT_OK
```

浏览器侧把该 JSON 转成 WebAuthn 对象时**按规范查找 `authData`**，找不到即失败：

```
E chromium: [ERROR:components/webauthn/android/fido2credentialrequest_native_android.cc:59]
  MojoClassFromJSON failed to convert JSON: field missing or invalid: attestationObject
E cr_ChromiumWebauthn: [CredManHelper] Failed to convert response from CredMan to Mojo object: {…}
```

⇒ 网页收到 `UnknownError`（passkeys.io 文案「timed out, was canceled…」；Yubico 文案「An unknown error
occurred while talking to the credential manager」）。**依赖方从未收到任何凭据**，故「一律未成功验证」，
且与浏览器品类、站点、是否原生 App **全部无关**——这正是该缺陷的误导性所在，也是此前多轮定位屡次走偏的原因。

### 1.4 核实时间点 / 核实方式

- **核实时间点**：2026-09-22（真机 `M332BF`，Android 17 / user build）。
- **核实方式**：① 设备侧 `logcat` 直读上列两行 Chromium 错误；② 以「我们**实际交给系统**的完整响应」（一次性
  诊断日志）为输入，对 `attestationObject` 做 base64url 解码 + `od -An -tx1` 逐字节还原；③ 交叉核对 Chromium
  源——`MojoClassFromJSON` 只是包装器，真正报错处是它调用的 `webauthn::MakeCredentialResponseFromValue`
  （`components/webauthn/json/value_conversions.cc`）。

---

## 2. 同批一并收口的互操作对齐（同一对照面：「同机 Monica / KeePassDX 成功」）

| 项 | 改前 | 改后 | 依据 |
|---|---|---|---|
| `response.publicKey` 编码 | COSE_Key CBOR | **SPKI DER** | W3C 把 `AuthenticatorAttestationResponse.getPublicKey()` 定义为 DER-encoded SubjectPublicKeyInfo；Monica 用 `keyPair.public.encoded`；KeePassDX 虽用 COSE 但该字段通常无人读 |
| credentialId 长度 | 32 字节 | **16 字节** | 两个**可用**实现的共同取值（KeePassDX `HashManager.generateRandom(16)`、Monica `ByteArray(16)`）；规范只要求 1..1023 字节，不限定长度 |
| AAGUID | 16 字节**全零** | 登记**真实值**（UUIDv5） | 全零的规范语义是「该认证器**没有** AAGUID」⇒ RP 无法识别提供方 |
| `response.transports` / `crossOrigin` / `authenticatorData` / `publicKeyAlgorithm` | 缺失或未对齐 | 一并补齐 | 同上对照；`publicKeyAlgorithm` 为 COSE alg 号（可负） |
| `WebAuthnJsonWriter` 整数写入 | 无该能力 | 新增 `int()` | `publicKeyAlgorithm` 必须是十进制无引号，须与平台 `JSONObject.put(String,int)` 逐字节一致 |

配套：`PasskeyKeyCodec.toSubjectPublicKeyInfo`（ES256 未压缩点 / Ed25519 raw → SPKI；RS256 库内已是 SPKI ⇒ 原样返回）、
`PasskeyCryptoEngine.publicKeySubjectInfoFor`（与 `coseKeyFor` 分工：**同一公钥的两种编码，分属两个字段，不可互换**）。

---

## 3. 已证伪并**回退**的假设（留痕，防复发）

本批定位过程产生过四条假设，均被实测或源码证伪，相关改动**已全部回退**（不得以「当时也为修 bug」为由保留）：

| 假设 | 证伪依据 | 处置 |
|---|---|---|
| **origin 降级（`apk-key-hash`）导致 RP 拒收** | 修复后**原生应用路径**（`origin=android:apk-key-hash:0_Dgaxh…`）与 web 路径**同时**成功 ⇒ 与 origin 无关 | 无需改动；原「降级所以失败」结论撤销 |
| **需要 `https://{rpId}` 兜底**（`ISSUE-P2-266` 名义新增） | 两个可用实现（KeePassDX `PassHelper.getOrigin`、Monica `PasskeyOriginResolver`）在调用方未携带 origin 时**一律颁发 `apk-key-hash`**；且该兜底会推翻设备侧已固化断言「白名单未命中的委派调用方不得产出 web origin」 | **整条回退**：`rpIdFallback` 参数、`degradeWithRpIdFallback`、调用点开关与 `WebAuthnRequest.isDiscoverable` + 三个仅服务于它的常量全部删除；`CallingOriginResolver` KDoc 改写为「降级口径：为什么不做 `https://{rpId}` 兜底」 |
| **`getOrigin` 返回值末尾斜杠致 origin 不匹配** | 该规范化本身**独立成立**（WebAuthn origin 定义不含路径 / 末尾斜杠；KeePassDX 同样 `removeSuffix("/")`），但**不是本次病因** | **保留** `normalizeOrigin`（理由与病因分离，KDoc 已如实标注） |
| **白名单来源太窄（应改用 Google GPM 列表）** | 本机 Via 走的是**已取得系统背书 origin**分支（命中白名单），与被测失败面无关 | 未改动；如日后要扩面，属独立安全口径变更 |

---

## 4. 验证

### 4.1 新增防线（宿主，可判此缺陷）

- `PasskeyRegistrationMaterialInteropTest`：对 `attestationObject` 的 CBOR **原始字节**断言
  「必须含 ASCII `authData`」且「**不得**出现 `authenticatorData`」，键名逐字用**规范字面量**而非 `WebAuthnJson` 常量。
  > **为什么必须用字面量**：本批揭出的深层教训是——原用例用 `WebAuthnJson.AUTHENTICATOR_DATA` 做断言，
  > 与被测代码**同源**，实现与测试一起错，故此缺陷此前**从未被任何用例拦住**（镜像自证盲区）。
- `PasskeyKeyCodecSpkiTest`（新增）：SPKI 导出四件事（ES256 独立解析器读回逐字节一致 + 算法 OID；Ed25519 OID
  `1.3.101.112`；RS256 幂等；**防混淆金标**：SPKI 输出**不得**等于 `coseKeyFor` 的 COSE）。
- `PasskeyRegistrationPayloadBuildTest`（新增）：响应补齐字段 / authData 与内嵌 authData 同源 / challenge 原样透传 +
  `crossOrigin` 显式 false / credProps 仅在请求携带时回传。
- `WebAuthnJsonWriterParityDeviceTest`（设备）：新增整数写入与平台 `JSONObject` 逐字节一致（含 `0` / `±2147483648` 边界）。
- `CallingOriginResolverAttributionTest`（新增）：归属来源三类判定的用例锁定。

### 4.2 读数

- 全量 `.\gradlew.bat test --max-workers=1` → **BUILD SUCCESSFUL**；
  `python tools/doc/count_test_results.py` → **`xml=361 tests=2524 failures=0 errors=0 skipped=13`**。
- 真机端到端（`M332BF`，用户真实指纹确认，**两条路径同日同时通过**）：
  - `mark.via`（浏览器委派，`origin=https://demo.yubico.com`）→ **成功**；
  - `com.ss.android.lark`（原生应用，`origin=android:apk-key-hash:…`）→ **成功**；
  - 修复后的日志侧判定：`MojoClassFromJSON failed` / `Failed to convert response from CredMan` 命中 **0** 次；
    `attestationObject` 字节含 `authData` **1** 次、含 `authenticatorData` **0** 次。

### 4.3 如实声明（须一并读）

- **`androidTest` 未执行**：本批修改了 `WebAuthnJsonWriterParityDeviceTest`（新增整数 parity 用例），按
  `AGENTS.md` 测试资产纪律本应真机实跑，**本批未执行**。原因：设备上安装的是 **release 签名包**（用户日常使用、
  内含真实密码库），而 debug 版 `connectedDebugAndroidTest` 因签名不符**必须先卸载**——本次执行中**确实发生了卸载**
  （见下条），不可再犯。**已知可用 AVD**：`Pixel_10`（未启动）。
- **⚠️ 操作事故（如实登记）**：2026-09-22 22:30 执行 `:app:connectedDebugAndroidTest` 时，测试平台在
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 后**卸载了设备上的 `com.keepasskey`**，导致应用内部密码库
  （含本轮测试期间登记的通行密钥）**永久丢失**（该应用 `android:allowBackup="false"` ⇒ 无系统备份可恢复）。
  用户裁决「密码库不需要恢复」。**立规**：见 `AGENTS.md` §5 新增的 connected 测试前置检查条。
- `PasskeyAssertionPayload`（断言侧）的 `authenticatorData` 是**响应 JSON 字段**，本就正确，**未改动语义**；
  本批只核对了它与注册侧的口径一致性。
- 未触原生面（`crypto/src/main/rust/**`、JNI 绑定）、未改 `参考项目/`、未新增依赖、未改构建脚本。
  ⇒ 按测试资产纪律②**无四层 `connectedDebugAndroidTest` 义务**。
- 未跑 `lint` / `assembleRelease`（本轮为定位与互操作修复，发布包另行执行）；未跑截图门禁与 KPEX 对拍
  （未改 `PasskeyData` schema / `PasskeyPkcs8Codec` / KPEX 字段，故非必跑项）。
- 「同设备 Monica 100% 成功」为用户现场对照结论，本批以其为**对照事实**使用，未独立复跑 Monica。

---

## 5. 文档同步

- 新增 [`records/注册响应CBOR键误用定位记录.md`](../../records/注册响应CBOR键误用定位记录.md)（定位全过程 + 字节证据 + 五条走偏路径留痕）。
- `docs/README.md`（`records/` 分区登记新文档）、`RESOLVED_LOG.md`（本行）、`resolved/README.md`
  （最大 §262 → **§263**，下一批 §264）、`BATCH_158_PLUS.md`（§263 行）。
- `AGENTS.md` §5：新增 connected 测试前置检查条（针对上述操作事故）。
- 代码内 KDoc：`WebAuthnJson.AUTH_DATA`（键名与 JSON 字段名的区分 + 本缺陷完整成因）、
  `PasskeyRegistrationPayload`（CBOR 键必须为 `authData`）、`CallingOriginResolver`（降级口径与证伪理由）。
