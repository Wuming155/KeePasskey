# KeePasskey 安全问题与整改方案

> **文档性质**：整改执行清单。本文件是 [`SECURITY_AUDIT_2026-09.md`](SECURITY_AUDIT_2026-09.md) 的可执行摘要，面向开发者。
> **审计基线**：提交 `d32f3e7b087d7bc85589b8a2d6a4ea6260449715`（2026-09-12）。
> **覆盖范围**：**29 项已复核发现**。§10「待复核区」中证据充分但首席审计员未逐条复核的条目**不纳入本文档**，以免把未经核实的结论当成待办。
> **配套文档**：完整证据、误报排除论证、25 条攻击链、15 类攻击者模型见审计报告；威胁建模细节见 [`THREAT-MODEL-AUDIT-d32f3e7.md`](THREAT-MODEL-AUDIT-d32f3e7.md)。

---

## 0. 修复优先级总览

| 优先级 | 数量 | 含义 | 建议排期 |
|---|---:|---|---|
| **P0** | 2 | 数据破坏或流程性高危缺口，应立即处理 | 本批次 |
| **P1** | 6 | 高危 / 中危且修复成本低 | 紧随 P0 |
| **P2** | 10 | 中危或纵深防御缺口 | 正常排期 |
| **P3** | 11 | 低危 / 信息类 / 代码卫生 | 渐进收尾 |

### 按优先级排列的完整清单

| 优先级 | ID | 问题 | 严重度 |
|---|---|---|---|
| **P0** | F-09 | Salsa20 内层流 nonce 常量错误 → 静默且不可逆的数据损坏 | MEDIUM |
| **P0** | F-05 | 依赖 CVSS 闸门未接入任何自动触发路径 | HIGH |
| **P1** | F-13 | 解密附件明文 / KDBX 密文快照无冷启动清理 | MEDIUM |
| **P1** | F-23 | 同步防回滚状态随锁库被清除 → 云侧可重放旧库 | HIGH |
| **P1** | F-10 | 附件引用放大 → 解析期 OOM | HIGH |
| **P1** | F-11 | 外层头部无总长 / 字段数上限（pre-auth） | MEDIUM |
| **P1** | RUST-03 | 口令强度评估二次复杂度 → 主线程 ANR | MEDIUM |
| **P1** | F-06 | 发布密钥库口令即仓库公开的示例口令 | MEDIUM |
| **P2** | F-01 | 解锁失败重试节流生产默认关闭 | MEDIUM |
| **P2** | F-12 | KDF 无墙钟预算（认证前执行、无超时） | MEDIUM |
| **P2** | F-24 | CM 通道从不查询 `RuntimeIntegrityGate` | MEDIUM |
| **P2** | F-14 | DAL 响应体先物化后检查大小 | LOW |
| **P2** | F-18 | 剪贴板不随锁定清理 + 会误清他处内容 | LOW |
| **P2** | F-22 | 自动填充选择器锁定后崩溃 | LOW |
| **P2** | F-04 | Chrome 指纹白名单条目永不匹配 | LOW |
| **P2** | RUST-01 | Argon2 工作内存释放前未擦除 | LOW |
| **P2** | RUST-02 | 派生密钥栈副本残留 | LOW |
| **P2** | RUST-06 | KDBX KDF secret `K` 常驻且无清零点 | LOW |
| **P2** | RUST-05 | 原生 Argon2 路径缺内存上界预检 | LOW |
| **P3** | F-02 | 明文导出缓冲未清零 | LOW |
| **P3** | F-03 | 明文导出确认仅在 UI 层强制 | LOW |
| **P3** | F-15 | 解密后的受保护值明文未清零 | LOW |
| **P3** | F-16 | `HmacBlockStream.readAll` 使用非常时比较 | LOW |
| **P3** | F-19 | 调用方证书仅取首个签名者 | LOW |
| **P3** | F-21 | 自动填充确认不校验会话锁定 | LOW |
| **P3** | F-25 | 解锁失败日志含库标识与密钥文件长度 | LOW |
| **P3** | RUST-07 | Kotlin CBC 加密流遗留未擦除明文分块 | LOW |
| **P3** | RUST-09 | CI 从不执行 JNI 边界测试；符号表核对未实现 | LOW |
| **P3** | F-07 | 审计摘要被截断至 32 位 | INFO |
| **P3** | F-08 | 公开死函数 `OtpEngine.parseOtpAuthUri` 缺参数校验 | INFO |
| **P3** | F-17 | UI 把 ChaCha20 误标为「ChaCha20-Poly1305」 | INFO |
| **P3** | F-20 | 合并清单携带冗余与废弃权限 | INFO |

> 说明：表中 32 行 > 29，因 P2/P3 中 RUST 与 F 系列编号独立计数（F-01…F-25 中 22 项 + RUST-01…RUST-09 中 7 项 = 29；F-07/F-08/F-17/F-20 为 INFO 级同属 F 系列）。

---

## P0 — 立即处理

### 1. F-09 Salsa20 内层流 nonce 常量错误（数据破坏·不可逆）

**问题**
`crypto/src/main/java/com/keepasskey/crypto/stream/InnerRandomStreamCipher.kt:51-54` 的 Salsa20 固定 nonce 第 6–8 字节为 `0x61, 0x98, 0xB0`，而 KDBX 4.1 规范要求 `0x20, 0x5D, 0x2A`（已对照 keepass.info《KDBX File Format Specification》§Inner Encryption 原文核实）。密钥（SHA-256）正确，nonce 错误 ⇒ 整个密钥流错误 ⇒ 所有 `Protected="True"` 字段解出乱码。

**为什么严重**：长度与 Base64 校验照常通过，**不报错**；而保存侧硬编码 `innerRandomStreamId = CHACHA20`（`KdbxFile.kt:273-274`）会把刚解出的乱码用正确的 ChaCha20 重新加密写回，**原密文被覆盖，破坏不可逆**。

**修复（两步，顺序重要）**

第一步——先止血，遇到 Salsa20 内层流的库拒绝保存：

```kotlin
// KdbxFile.save 入口（或 DatabaseSession 保存前）
require(innerHeader.innerRandomStreamId != KdbxConstants.InnerRandomStream.SALSA20) {
    "当前版本尚不支持保存 Salsa20 内层流的数据库（内层流实现待修），已拒绝以避免覆写原始密文"
}
```

第二步——修正常量：

```kotlin
// InnerRandomStreamCipher.kt:51-54
val salsaIv = byteArrayOf(
    0xE8.toByte(), 0x30.toByte(), 0x09.toByte(), 0x4B.toByte(),
    0x97.toByte(), 0x20.toByte(), 0x5D.toByte(), 0x2A.toByte()
)
```

**验证**
① 新增 KAT：固定 `streamKey`，断言 Salsa20 前 64 字节密钥流与参考实现一致（该分支当前**零测试覆盖**，是缺陷长期潜伏的根因）；② 端到端：构造 `InnerRandomStreamID = 2` 的 KDBX4 fixture 并解锁，断言受保护字段正确解出——**注意仓库内目前没有此类语料**，需自行构造（见审计报告 D-8）。

**风险**：低（不影响 ChaCha20 / NONE 路径）。**但已受影响的库无法靠此修复恢复**——若已有用户遭遇覆写，只能从备份恢复，需在更新说明中明确提示。

---

### 2. F-05 依赖 CVSS 闸门未接入自动触发路径

**问题**
`.github/workflows/dependency-scan.yml:21-22` 只有 `workflow_dispatch`，即**仅手动执行**。而 `build.yml`（确实在 PR/push 上运行）不含任何依赖扫描步骤。`AGENTS.md` §5 把该命令与 `assembleRelease`、`test` 并列呈现，读者会误以为它是每次变更的门禁。

**影响**：引入 CVSS ≥ 7.0 依赖的 PR 只会在 diff 上被审查，全绿的 `build` 不含依赖扫描；对一个密码管理器而言与项目自身「带病发布不得放行」的政策相矛盾。

**修复**

```yaml
# .github/workflows/dependency-scan.yml
on:
  workflow_dispatch:
  pull_request:
  push:
    branches: [main]
```

**建议同时**：把该 job 设为分支保护的必需检查。为控制耗时（文件自述全量 NVD 同步约 37 分钟），可拆分为「PR 快速任务（固定依赖清单）」+「main 每日全量任务」。

**验证**：开一个测试 PR，确认 `dependency-scan` 自动运行；并验证缺失报告时 fail（脚本 `:74-88` 已 fail-closed）。

**风险**：中。首次运行可能因既有依赖需补抑制项而变红；须遵循项目既有政策（不降阈值、记录已验证误报）。

---

## P1 — 紧随 P0

### 3. F-13 派生缓存无冷启动清理（唯一免口令的内容读取路径）

**问题**
`cacheDir/attachments`（**解密后**的 >1 MiB 附件明文）与 `cacheDir/sync`（完整 KDBX 密文快照）**仅**由 `SessionLockObserver` 清理（`FileBinaryStore.kt:61-65`、`SyncCacheEvictor.kt:37-39`）。进程在解锁态被 force-stop / LMK 回收 / 崩溃时该回调不执行，且**冷启动不补清**（全仓无 `deleteRecursively` 于 `MainApplication`）。

**这是本次审计中唯一无需口令、无需密钥即可读取库内容的已确认路径。**

**修复**

```kotlin
// MainApplication.onCreate() —— 冷启动对账，必须先于任何 UI/会话
File(cacheDir, "attachments").deleteRecursively()
File(cacheDir, "sync").deleteRecursively()
```

（或为两个 `@Singleton` store 各加一个 `purgeOnColdStart()`，语义相同。）

**同时修正文档**：`FileBinaryStore.kt:24-25` 的 KDoc 与 `AGENTS.md` §6/§35 声称「锁定即闭环，无附件明文可挖」——该断言在非正常终止路径不成立，应如实收紧措辞。

**验证**：设备侧用例——解析含 >1 MiB 附件的库 → `adb shell am force-stop` → 重启应用 → 断言两个目录为空。

**风险**：低（冷启动时不存在合法引用者；引用只存在于内存）。

---

### 4. F-23 同步防回滚状态随锁库被清除

**问题**
防回滚的「已见高水位」状态存放于 `cacheDir/sync/*.rollback`，而 `SyncCache.kt:285-286` **显式把 `SyncRollbackGuard.SUFFIX_STATE` 列入删除清单**，该方法经 `SyncCacheEvictor.onSessionLocked()` 在**每次锁定**时调用。状态清空后 `SyncRollbackGuard.kt:79` 因 `state.current == null` 直接返回 `Accept`。

**结果**：云端（或 MITM）在用户**正常锁定过一次**后即可重放一份旧但仍然有效的库，复活已删条目、回退已更新字段——正是该防护宣称要阻止的威胁。`SyncRollbackGuard.kt:44-52` 的 KDoc 与 `docs/同步层记录级完整性威胁建模.md` **均未说明**这一语义。

**修复**

```kotlin
// 1) 状态目录从 cacheDir 移出，改用 filesDir（跨锁定保留）
class SyncRollbackGuard(
    private val stateDir: File,   // 生产：File(context.filesDir, "rollback")
    private val integrityMac: SyncIntegrityMac
)

// 2) SyncCache.clear() 的删除清单移除 SyncRollbackGuard.SUFFIX_STATE
// 3) 文档写明状态生命周期语义
```

**建议同时**：启用已被持久化却**从未参与任何裁决**的 `sequence` 字段（`SyncRollbackGuard.kt:113,122,183`）作为单调性依据；重新评估 `recent` 窗口（当前仅 32 条）。

**验证**
① 单测：触发 evictor 后，对**曾接受过的历史内容** `inspect()` 应返回 `ReplayDetected` 而非 `Accept`；② 集成：接受 A → 接受 B → 锁定 → 重放 A 应被拒绝。

**风险**：低—中。状态文件移入 `filesDir` 后不再随锁定清除，需注意其内容仅为 SHA-256 摘要 + Keystore HMAC（无明文），可接受；升级时首次运行的「无历史」状态可容忍。

---

### 5. F-10 附件引用放大 → 解析期 OOM

**问题**
`KdbxXmlBinaryNode.kt:70` 对**每一次** `<Binary Ref="n"/>` 引用执行 `item.data.copyOf()`，产生一份独立完整副本。二进制池的累计上限（`InnerHeader.kt:235-236`，≤128 MiB）约束的是**池内字节**，**不约束引用次数**；且落盘豁免是严格的 `>` 阈值（`BinaryStore.kt:55-56`），故 ≤1 MiB 的池条目**恒不落盘**。内存占用 = 池字节 × 引用次数。

**前提**：需用户打开攻击者构造的库并输入正确口令，故非 pre-auth。子库（`ChildReadOnlySession.kt:134`）与同步解码（`SyncDatabaseCodec.kt:55`）传 `binaryStore = null`，放大幅度更大。

**修复**——按累计**被引用**字节做预算，并取消逐引用物化：

```kotlin
// KdbxXmlBinaryNode.end() 内
if (refIndex in binariesPool.indices) {
    val item = binariesPool[refIndex]
    if (!binaryBudget.tryConsume(item.size)) {          // 新增：引用累计预算
        throw KdbxCorruptFileException("附件引用累计字节超出解析预算")
    }
    onDone(
        KdbxAttachment(
            name = key, refIndex = refIndex, isProtected = isProtected,
            source = item                                // 复用既有落盘分支的按需读取语义
        )
    )
}
```

**验证**：新增负例——单池条目 + 数千条引用应被预算拒绝而非 OOM（当前无此测试）。

**风险**：低—中。改动附件交付语义，须保持 `KdbxAttachmentAliasIsolationTest` 的别名隔离 4 例通过；`source` 路径每次 `load()` 本就返回独立副本。

---

### 6. F-11 外层头部无总长 / 字段数上限（pre-auth）

**问题**
`KdbxHeader.kt:159` 把头部每个字段累积进 `recordingStream`，**无总长上限、无字段数上限**（仅单字段 1 MiB，`:106`）。峰值堆 ≈ 2× 头部大小，且**全部发生在头部 HMAC 校验之前**（`KdbxFile.kt:125` 派生 → `:137` 校验）。这是唯一**无需口令**即可造成的解析期 DoS。

**修复**

```kotlin
internal const val MAX_HEADER_TOTAL_BYTES = 16L * 1024 * 1024
internal const val MAX_HEADER_FIELDS = 256

// deserialize 循环内：
require(fieldCount <= MAX_HEADER_FIELDS) { "头部字段数超限" }
require(recordingStream.size() + fieldSize <= MAX_HEADER_TOTAL_BYTES) { "头部总长超限" }
```

**验证**：新增负例——超量字段 / 超大头部应被拒绝且不 OOM（当前无此测试）。

**风险**：低（真实头部远小于 1 MB）。

---

### 7. RUST-03 口令强度评估二次复杂度 → 主线程 ANR

**问题**
`strength.rs:406-430` 的 `longest_keyboard_walk` **逐起点重扫**内层循环，且相邻判定允许往复（`asasas…` 每步成立），复杂度 Θ(n²)；`:491-499` 的 `unique_char_count` 为 Θ(n·u)。而 `estimate`（`:132-220`）**不对输入长度设任何上限**，以全量 `chars` 调用二者。

**已确认三条要件**：① 内层迭代数实测 = n²/2（n=32000 → 511,984,000）；② 无长度上限；③ 两个生产调用方 `SettingsHealthController.kt:73-77`（健康检查）与 `EntryDetailRevealController.kt:137-139`（展开口令）均经 `viewModelScope` 运行在**主线程**。

**修复**

```rust
// 1) estimate 入口加长度上限（超出则只按长度评分，不做模式扫描）
const MAX_EVAL_LEN: usize = 1024;
let scan: &[char] = if chars.len() > MAX_EVAL_LEN { &chars[..MAX_EVAL_LEN] } else { &chars };

// 2) longest_keyboard_walk 改单趟（保持「同排且 |Δcol| == 1」语义）
// 3) unique_char_count 改用 [bool; 128] / HashSet 实现 O(n)
```

```kotlin
// 4) 纵深防御：移出主线程
withContext(Dispatchers.Default) { PasswordEntropyEstimator.estimateBits(chars) }
```

**验证**：保留既有 `keyboard_walk_does_not_bridge_rows` 负例；新增性能回归用例（长输入应在线性时间内完成）。

**风险**：低。长度上限只影响荒谬长度下的评分；单趟化必须保持既有语义。

---

### 8. F-06 发布密钥库口令即仓库公开的示例口令

**问题**
`keystore.properties.example:6,8` 发布 `storePassword=keepasskey123` / `keyPassword=keepasskey123`，而**实际未入库的 `keystore.properties` 使用了完全相同的值**（经比对确认）。

**关键区分**（勿误读）：**密钥材料从未入库**——`git rev-list --all --objects` 全历史仅存在 `keystore.properties.example` 一个相关对象。但一旦 `release.jks` 经 git 之外的渠道泄露（工作站备份、云同步目录、旧设备），口令是公开知识，无需破解。签名密钥是更新信任锚，故构成「对全部已安装副本的应用接管」前提。

**修复**

```properties
# keystore.properties.example —— 改为不可误用的占位符
storePassword=<REPLACE_WITH_32_CHAR_RANDOM>
keyPassword=<REPLACE_WITH_32_CHAR_RANDOM>
```

```kotlin
// app/build.gradle.kts —— 构建期断言拒绝示例/弱口令
require(releaseStorePassword !in setOf("keepasskey123", "changeme")) {
    "拒绝示例/弱口令作为发布签名口令"
}
```

**执行**：用高熵随机口令**重新加密既有密钥库**（PKCS#12 re-key，身份不变，升级路径不受影响），新口令仅记录于密码管理器/CI secret。

**风险**：中。**只改口令，不要换密钥**——换密钥会使已安装用户无法覆盖升级，除非走 Play 密钥轮换。

---

## P2 — 正常排期

### 9. F-01 解锁失败重试节流生产默认关闭

**问题**：三处默认值一致为「关」——`SettingsRepository.kt:53`（`= false`）、`RealSettingsRepository.kt:113`（`?: false`）、`UnlockThrottle.kt:199`（`ThrottleConfig(enabled = false)`）。`gate()` 因此在 `enabled == false` 时无条件返回 `Allowed`（`:274`），忽略锁定截止时间。而同文件 `ThrottleConfig` 数据类的默认值是 `true` 且注释写「安全默认不得放松」——**声明的安全默认在真实进程中不生效**。

**修复**：三处统一改为 `true`，把「退出节流」保持为设置页的显式选择（写入前保留风险确认）。

**验证**：单测断言 `UserSettings()` 默认为 `true`、`UnlockThrottleConfigProvider` 在设置流为空时 `current.enabled == true`；设备侧：默认安装后连续 5 次错误解锁应观察到退避。

**风险**：低—中（行为变更，需更新说明）。**注**：此项属 2026-09-12 用户裁决（`AGENTS.md` §1/§29.2），落地前需产品确认是否推翻原裁决。

---

### 10. F-12 KDF 无墙钟预算

**问题**：`KdbxKdfParameterCodec.kt:28,112` 允许 `I ≤ 2²⁴` 且内存下限仅 1 MiB 的**组合**（单次派生可达分钟—小时级），`:34,129` 允许 `AES_KDF_MAX_ROUNDS = 2²⁸`（JCE 兜底可达数百秒）。这些计算在**认证之前**执行、持有会话互斥锁、**无超时与取消**。

**修复**：对参数**组合**设工作量预算（如 `I × M` 上限），使最坏情况限制在可接受墙钟（如 ≤10 秒）；并为解锁派生加超时/取消。

**风险**：低—中（可能拒绝极端但"合法"的库）。项目已接受"有界 DoS"（`RESOLVED_LOG.md:633`），本项是把"有界"收紧为"有预算"。

---

### 11. F-24 CM 通道从不查询 `RuntimeIntegrityGate`

**问题**：`RuntimeIntegrityGate` 在 `app/src/main` 中**仅两个消费者**（经 grep 穷尽确认）：`KeePasskeyAutofillService.kt:53,123,230` 与 `BiometricAuthManager.kt:73,115`。Android 16+ 的**主**凭据通道（`KeePasskeyCredentialProviderService` / `CredentialResponseAssembler`）**零命中**——完整性裁决在自动填充通道 fail-closed，却在 CM 通道 fail-open。而 `RuntimeIntegrityPolicy.kt:47` 自述其效果包含"禁止下发自动填充数据集"。

**修复**

```kotlin
// KeePasskeyCredentialProviderService.onBeginGetCredentialRequest
val enforcement = runtimeIntegrityGate.awaitEnforcement()
if (enforcement.disableDatasetIssuance) {
    AppLog.i(TAG, "设备完整性风险，拒绝下发凭据候选")
    callback.onResult(BeginGetCredentialResponse.Builder().build())
    return
}
```

（`onBeginCreateCredentialRequest` 同样处理；或在 `CredentialResponseAssembler` 统一收口。）

**风险**：低。需确认 `awaitEnforcement()` 耗时不影响 CM 响应预算——自动填充侧已有相当用法可参考。**注**：在已 root/hook 的设备上攻击者本已具备内存读取能力，故这是**策略一致性与纵深防御**问题，而非独立高危绕过。

---

### 12. F-14 DAL 响应体先物化后检查

**问题**：`DigitalAssetLinksVerifier.kt:107-111` 先执行 `response.body?.string()` 把响应体**完整读入内存**，随后才检查 `body.length > MAX_BODY_BYTES`（256 KiB）。上限对内存占用**无效**；且检查的是字符数而非字节数。

**修复**——改为有界流式读取：

```kotlin
val bytes = response.body?.source()?.let { src ->
    if (src.request(MAX_BODY_BYTES + 1L)) src.buffer.snapshot().toByteArray() else null
} ?: return DalResult.NOT_VERIFIED
if (bytes.size > MAX_BODY_BYTES) return DalResult.NOT_VERIFIED
```

**验证**：单测注入返回超大响应体的 MockWebServer，断言未物化完整响应且返回 `NOT_VERIFIED`。

---

### 13. F-18 剪贴板不随锁定清理 + 会误清他处内容

**问题**（两个独立缺陷）
① 超时擦除是**进程内协程**（`ClipboardSecurityManager.kt:44-56`），force-stop / 回收 / 重启即失效，`system_server` 继续持有剪贴板；全仓 grep 确认 `clearClipboard()` **仅**由 `performClearIfMatching` 调用，未接锁定或 `ACTION_SCREEN_OFF`，也无冷启动清理。
② 后台读取被拦（Android 10+）时 `lastSensitiveHash` 不会因**其他应用**的复制而更新，陈旧匹配导致**无条件清空用户刚在别处复制的内容**（良性数据丢失）。

**修复**

```kotlin
// ① 注册为 SessionLockObserver
override fun onSessionLocked() {
    clearJob?.cancel()
    lastSensitiveHash?.let { performClearIfMatching(it) }
    lastSensitiveHash = null
}
// 并订阅 ACTION_SCREEN_OFF；冷启动对账（可用 WorkManager 一次性任务写入截止时间）
```

```kotlin
// ② 空读分支改为「仅当已观察到变化或仍在超时窗口内」才擦
```

**风险**：低—中。两处改动都触及既有的刻意行为（`EXTRA_IS_SENSITIVE` 与"读不到即擦"规则），建议产品确认。

---

### 14. F-22 自动填充选择器锁定后崩溃

**问题**：`AutofillPickerViewModel.kt:26-40` 在 `init` 中缓存 `vaultRepository.getKdbxEntries()` 返回的**活对象树引用**，并在 `:56-59` 于 `try` 之外读取 `entry.userName`（内部走 `ProtectedString.readString()`）。锁定时 `clearSensitiveData()` **就地**清零这些 `ProtectedString`，`checkNotCleared()`（`ProtectedString.kt:172-174`）抛 `IllegalStateException`；该 VM 既非 `SessionLockObserver` 也未捕获该异常。

**修复**：选择器观察会话状态（锁定即清空 / 解锁即重载），或改为按选取经仓库读取。

**重要**：**不得**削弱 `ProtectedString.clear()`——正因就地清零使保留引用失效，才使锁定后无法从残留引用取回明文。这是该设计的负载承载点。

---

### 15. F-04 Chrome 指纹白名单条目永不匹配

**问题**：`BrowserSigningFingerprints.kt:34-37` 的 Chrome 第一条指纹为 **65** 个十六进制字符（SHA-256 恒为 64），故 `isTrusted()` 对该条目**永不匹配**。第二条为 64 字符合法值且与 Google 官方 allowlist 的 Chrome release 指纹一致（经核对），应能正常工作。

**另注**：同一证书在 `CallingOriginResolver.kt:41` 以冒号形式给出（95 字符 = 32 字节，合法），而 `:42` 的裸写形式为 65 字符——**同一仓内两种写法字符数自相矛盾**，独立证明存在誊写错误。

**根因**：`AutofillWebDomainPolicyTest.kt:19-20` **复用了同一个 65 字符值**，故测试通过而控制失效。

**修复**

① 从 Google 官方 `gpm-passkeys-privileged-apps/apps.json` 重新核对并录入真实指纹（**必须 sourced，不可 retype——本缺陷的成因正是誊写**）；
② 新增格式断言单测：

```kotlin
@Test fun allFingerprintsAreWellFormed() {
    BrowserSigningFingerprints.TRUSTED.values.flatten().forEach {
        assertEquals(64, it.length, "指纹长度必须为 64：$it")
        assertTrue(it.all { c -> c in "0123456789ABCDEF" }, "指纹必须为大写 hex：$it")
    }
}
```

**方向说明**：该缺陷为 **fail-closed**（仅使指纹捷径失效、退回 DAL），**无机密性影响**，故未评 CVSS。

---

### 16. RUST-01 Argon2 工作内存释放前未擦除

**问题**：`Cargo.toml:30` 宣称 "zeroize：argon2 内部秘密缓冲擦除"，但根据上游 `argon2-0.6.0/src/block.rs`，`Blocks::new`/`Drop` 仅 `dealloc`；只有 `initial_hash`/`blockhash`/`blockhash_bytes` 被清零。**m_cost KiB 的秘密派生状态（默认 64 MiB）在释放前未擦除。**

**修复**——自行持有内存矩阵并 `Zeroizing`：

```rust
let len = builder.build().ok()?.block_count();
let mut mem = Zeroizing::new(vec![argon2::Block::new(); len]);
ctx.hash_password_into_with_memory(password, salt, out.as_mut_slice(), mem.as_mut_slice()).ok()?;
```

（两个 API 在 argon2 0.6.0 中均为 public；`Block: Zeroize`，`Vec<Block>` 清零全部容量。）

**替代方案**：若不愿改动，则**更正 KDoc/Cargo.toml 的宣称**，使保证与实际相符——不可两者都不做。

**风险**：低（多一次与内部等大的分配 + 一次清零）。

---

### 17. RUST-02 派生密钥栈副本残留

**问题**
① `aes_kdf.rs:82-88` 与 `lib.rs:108-111` 以普通 `[u8;32]` 按值返回 KDF 输出，`Zeroizing::new(...)` 接管的是**移动后的副本**，无法擦除源槽位；
② `Cargo.toml:41` 的 `sha2` **未启用 `zeroize` feature**，故 SHA-256 哈希器状态（含转换后缓冲的最后 64 字节）与 `GenericArray` 摘要从未清零。

**修复**

```toml
# Cargo.toml
sha2 = { version = "0.11.0", default-features = false, features = ["zeroize"] }
```

```rust
// 消除普通副本
pub fn derive_into(..., out: &mut Zeroizing<[u8; 32]>) { ... }
```

**风险**：低（该 feature 仅增加一个清零 `Drop`，无算法变更）。

---

### 18. RUST-06 KDBX KDF secret `K` 常驻且无清零点

**问题**：`KdfParameters.kt:35` 的 `secretKey: ByteArray?` 由 `KdbxKdfParameterCodec.kt:85,95` 从文件解析后长期驻留于头部对象，**全仓无任何 `.fill(0)`**。`equals`/`hashCode` 还刻意忽略该字段（`:60-61`），掩盖了它是独立秘密载体的事实。

**修复**：新增 `KdfParameters.clearSensitive()`（擦除 `secretKey`），接入既有会话锁定/关闭路径；确保仅在最后一次需要 `K` 的派生之后调用（保存时会用同一头部重新派生）。

**风险**：低。

---

### 19. RUST-05 原生 Argon2 路径缺内存上界预检

**问题**：`lib.rs:77` 与 `jni_bridge.rs:45` 仅强制**下界** `m ≥ 8p`；有效上界位于**另一模块**的解析期校验（`KdbxKdfParameterCodec.kt:122-125`：≤4 GiB 且 ≤ `maxMemory()/2`）。而 `Argon2KdfEngine.kt:73` 的 `isMemoryParamFeasible` **仅覆盖 JVM 兜底路径**（`:52` 的原生分支直接返回，不经过该检查）；`:59` 的 `(memoryInBytes / 1024L).toInt()` 存在静默窄化。

**当前不可达**（经 `.kdbx` 解析路径时校验先行），属**未来调用方的潜在风险**。

**修复**：在 `derive` 内镜像 Kotlin 的上界（`MAX_MEMORY_KIB`/`MAX_ITERATIONS`/`MAX_PARALLELISM`），并把 Kotlin 的 `.toInt()` 改为越界抛异常而非截断。

**风险**：低（上界须 ≥ 一切合法用户配置）。**注**：失败模式当前是 fail-closed（argon2 0.6.0 的 `Blocks::new` 用 `alloc_zeroed` + `NonNull::new(...)?` 映射为 `Error::OutOfMemory`，**不使用会 abort 的 `vec![]`**）。

---

## P3 — 渐进收尾

| # | ID | 问题 | 修复要点 | 位置 |
|---|---|---|---|---|
| 20 | F-02 | 明文导出（XML/CSV）字节缓冲写盘后未清零 | `finally { bytes?.fill(0) }`；或改为流式签名 `suspend (OutputStream) -> Unit` 从源头消除整库明文物化 | `SettingsExportController.kt:169-184` |
| 21 | F-03 | 明文导出的二次确认仅在 UI 层（`ExportConfirmationPolicy.allows(..., confirmed = true)` 是恒真调用） | 确认令牌下沉到仓库层：`exportVaultXmlBytes(confirm: PlaintextExportConfirmation)`，该类型构造器 `internal` | `SettingsExportController.kt:107-127`、`DatabaseSettingsScreen.kt:368-372` |
| 22 | F-15 | 解密后的受保护值明文副本未清零 | `val s = ProtectedString(true, plainBytes); Arrays.fill(plainBytes, 0); onDone(key, s)` | `KdbxXmlStringNode.kt:50-51` |
| 23 | F-16 | `HmacBlockStream.readAll` 用短路 `contentEquals`（生产流式路径已正确用 `MessageDigest.isEqual`） | 改用 `MessageDigest.isEqual`；或标注为测试专用。**当前无生产调用者** | `HmacBlockStream.kt:120,128` |
| 24 | F-19 | 调用方证书仅取 `apkContentsSigners.firstOrNull()`，签名轮换期结果随顺序变化 | 遍历全部签名者（含 `signingCertificateHistory`）任一匹配即通过。方向 fail-closed | `AutofillOriginResolver.kt:64-71`、`CallingOriginResolver.kt:77,92` |
| 25 | F-21 | 填充确认返回 `RESULT_OK` 前不检查会话是否已锁定（CM 各路径均有该判定） | `if (vaultRepository.isLocked()) { finish(); return }`。注：明文此时已离开进程，属"锁定时不予完成"的严谨性 | `AutofillConfirmActivity.kt:138-164` |
| 26 | F-25 | 解锁失败日志含库 id、密钥文件长度、异常原文，且缓冲**用户可导出** | 收敛为 `invalidCreds=$invalidCreds, err=${result.error.javaClass.simpleName}`；并在 `exportSanitizedText()` 补路径/标识脱敏 | `UnlockViewModel.kt:293` |
| 27 | RUST-07 | Kotlin CBC 加密流遗留未擦除明文分块（`chunk` / `buffer.copyOf` 临时副本） | 两处缓冲均加 `finally { Arrays.fill(..., 0) }`。`chunk` 可达 64 KiB/次刷新 | `CbcStreams.kt:96-104`（对照 `:27` 的 KDoc 宣称） |
| 28 | RUST-09 | CI 三个 job 无一**同时**具备 Rust 工具链与 `test`；`jni_bridge.rs:152` 声称的 `.so` 符号表核对未实现 | `native-gate` 中 `cargoNdkBuild` 后追加 `./gradlew :crypto:test` 与 `readelf -sW … \| grep -c Java_com_keepasskey`（实测**若实现将会通过**：恰好 5 个导出） | `crypto/build.gradle.kts:114-120`、`build.yml` |
| 29 | F-07 | `ExportAuditSanitizer.shortDigest` 每字节仅取低 4 位、只取前 4 字节 ⇒ 实际仅 **32 位**摘要（注释暗示 64 位） | 改用完整 64 hex，或如实改写注释。**无实际安全影响**，属语义与命名不一致 | `SettingsExportController.kt:298-307` |
| 30 | F-08 | 公开的 `OtpEngine.parseOtpAuthUri` 对 `period`/`digits` 无钳制，**零调用点**（生产用 `TotpKeyUriParser`，其 `:203-204` 已钳制） | 删除该死函数；或改 `internal` 并补齐同等钳制。属代码卫生（本报告初期即被它误导） | `OtpEngine.kt:88-121` |
| 31 | F-17 | UI 把 KDBX 的 ChaCha20 标注为「ChaCha20-Poly1305 (256-bit)」，而该 AEAD **在本仓不存在**；真实性由外层 HMAC 块流提供 | 改为「ChaCha20 (RFC 8439, 无 AEAD 标签)」；同步修正 `RESOLVED_LOG.md:1080` | `SettingsPreferencesController.kt:36`、`SettingsUiState.kt:69`、`DatabaseAlgorithmDialogs.kt:42` |
| 32 | F-20 | 合并清单中 `ACCESS_NETWORK_STATE`/`USE_BIOMETRIC` 由库重复注入；`USE_FINGERPRINT` 为 API 28 前废弃权限 | 删除冗余声明，对废弃权限加 `tools:node="remove"`（保留 zxing 注入的 `CAMERA`）。最小权限卫生 | 合并清单 `:11-14,28-31` |

---

## 附：非缺陷类产品决策（需产品确认，非代码修复）

| 项 | 内容 | 说明 |
|---|---|---|
| A-1 | **新建库 KDF 默认强度偏弱** | 默认 Argon2id `m=64 MiB, t=2, p=2`（`KdbxHeader.kt:131-140`），约为本仓自带 KeePassXC 语料（`t=89`）的 **1/44**。项目已有 `KdfBenchmark` 引擎可推荐更强值（`KdfBenchmark.kt:44-52,80-104`），但**建库路径未消费该建议**。建议提升至设备约 1 秒的目标（通常 `t ∈ [10, 100] @ m=64 MiB`）。 |
| A-2 | **导入无工作量下限** | 读取路径接受 `m=1 MiB, t=1, p=1`（`KdbxKdfParameterCodec.kt:22,112-117`）与 AES-KDF `R=1`（`:129`），且保存时**原样保留**弱参数（`KdbxFile.kt:296-307` 仅刷新盐）。建议对过低工作量给出警告或升级。 |
| A-3 | **F-13 配套文档收紧** | `FileBinaryStore.kt:24-25` 与 `AGENTS.md` §6/§35 的「锁定即闭环」措辞需在 F-13 修复后（或修复前）如实调整。 |
| A-4 | **`mapping.txt` 可见范围** | 该产物（77.5 MB）是维护者 retrace 必需品，非漏洞；但若对公众开放即等同公开去混淆映射。建议确认 CI 产物可见性。 |

---

## 附：修复顺序建议与依赖关系

1. **F-09 第一步（拒绝覆写保护）应最先落地**——即便常量尚未修正，也可立即阻止持续的数据破坏，且改动极小。
2. **F-09 第二步与 RUST-03** 可并行（不同模块）。
3. **F-13 与 F-23 建议同批**——两者同源（派生状态的生命周期收口位置不当），一并梳理可避免再次遗漏第三个目录；F-23 涉及 `cacheDir` → `filesDir` 的迁移，需考虑升级路径。
4. **F-05 与 RUST-09 同属 CI 改动**，可合并为一次工作流调整。
5. **F-10 / F-11 同属 KDBX 解析器资源预算**，建议同批设计（引用预算 + 头部总量闸门），并一并补齐对应的负例测试——当前**完全没有 fuzz harness**。
6. **擦除类（F-15 / F-02 / RUST-01 / RUST-02 / RUST-06 / RUST-07）建议作为一次一致性收敛**，统一"谁拥有缓冲谁负责清零"的契约，避免逐处打补丁。

---

## 附：每项修复的验收基线

按项目既有纪律（`AGENTS.md` §3.6）：**修改代码后须 `.\gradlew.bat test` 全绿方准入库**，文档与代码同一次 commit 并立即 push。

除各条已给出的专项验证外，建议统一补充：

- **负例测试缺口**（当前完全缺失）：多头字段、单池条目 + 大量引用、超大扁平条目数、AES-KDF seed 长度、KDF `$UUID` 长度、KDBX DTD/XXE、Salsa20 内层流分支。
- **设备侧（instrumented）**：CI **从不运行** `connectedAndroidTest`（RUST-09 / SUPPLY-07），而项目自身记录的致命缺陷类别正是「JVM 过、Android 运行时挂」。建议至少为 `:crypto` 与 `:database` 建立托管设备任务。
- **模糊测试**：KDBX 解析器当前无任何 fuzz harness，上述两项解析期发现（F-10/F-11）正是该缺口的表现形式。
