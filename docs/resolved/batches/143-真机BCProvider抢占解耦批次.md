# §143 · 真机 BC Provider 抢占解耦批次（ISSUE-P2-92 闭环）

> **批次主题**：修复「平台剥离版 BC 抢占 `"BC"` 注册名」导致的**真机 ChaCha20 / Twofish 全路径不可用**。
> **闭环条目**：`ISSUE-P2-92`。
> **实测数据来源**：[`records/真机吞吐实测记录_2026-09-17.md`](../../records/真机吞吐实测记录_2026-09-17.md)（本批发现过程与红证的完整登记）。

---

## 1. 条目原文（ACTIVE_ISSUES 登记，含整改中补充的影响面）

### ISSUE-P2-92 平台剥离版 BC 抢占 provider 名：真机 ChaCha7539 不可用（宿主单测全绿的仅真机失败）

- **现象（真实失败）**：真机上 `Cipher.getInstance("ChaCha7539", Security.getProvider("BC"))` 抛
  `NoSuchAlgorithmException: Provider BC does not provide ChaCha7539`——任何经
  `ChaCha20CipherEngine` 的加解密路径（选 ChaCha20 为外层 cipher 的建库 / 保存 /
  读取他人创建的 ChaCha20 KDBX）必然 fail-fast。
- **根因**：Android 平台自带**剥离版** BC provider（注册名同为 `"BC"`，无 `ChaCha7539`）；
  `ensureBouncyCastle()` 以 `Security.getProvider("BC") == null` 判定是否注册完整
  BouncyCastle，真机上恒判「已注册」⇒ 完整版永不注册。宿主 JVM 无平台 `"BC"`，故宿主单测全绿。
- **整改方向**：引擎内不查注册表、直接传完整 provider 实例——2026-09-17 真机已实证该形态可用
  （探针传入 `BouncyCastleProvider()` 实例后 `ChaCha7539` 可用）；**严禁** `removeProvider("BC")`
  （平台组件可能依赖）。
- **核实时间点与方式**：2026-09-17 Redmi 4X（`santoni` / arm64-v8a / Android 17）真机
  instrumented 探针首跑的真实异常与复跑通过，数据与证据文件见实测记录 §0 / §3；
  `grep app/src` 确认无任何 `removeProvider` / 完整版注册点。

## 2. 整改中的影响面补充发现（比登记时更大，如实留痕）

整改前全仓 `bouncyCastleProvider()` 调用点核对发现：**Twofish 路径同样踩中**，且形态更隐蔽——

1. `NativeTwofish.available` 探活的 BC 对照（`Twofish/CBC/NoPadding`）在真机上抛
   `NoSuchAlgorithmException`，被 `catch (_: Throwable) { false }` **吞掉** ⇒ 探活恒 `false`
   ⇒ **原生 Twofish 内核在真机上被静默弃用**；
2. 随后 `TwofishCipherEngine` 走 JCE 兜底（`Twofish/CBC/PKCS7PADDING`），该算法同样不在平台
   剥离版 BC 内 ⇒ **再挂**。

**净效果：真机上三种 KDBX 外层 cipher 中只有默认 AES 可用，ChaCha20 与 Twofish 整路径全不可用**
（且 Twofish 的故障形态是「静默降级后失败」，比 ChaCha20 的 fail-fast 更难定位）。

## 3. 修复实现

**唯一定案：`bouncyCastleProvider()` 与注册表解耦**（`ChaCha20CipherEngine.kt` companion）：

- 新增私有持有实例 `private val fullBouncyCastle: Provider by lazy { BouncyCastleProvider() }`；
- `bouncyCastleProvider()` 由「`ensureBouncyCastle()` + `Security.getProvider("BC")`」改为
  直接返回持有实例——**函数签名不变**，三个生产调用点
  （`ChaCha20CipherEngine.initCipher` / `TwofishCipherEngine.initCipher` / `NativeTwofish.bcCbcEncryptBlock`）
  自动修复，零调用点改动；
- `ensureBouncyCastle()` **原样保留**（宿主测试的按名查找依赖其注册行为；真机上其「无则注册」
  判定对生产路径已无影响），KDoc 明确标注「仅供注册表式查找，生产路径不得依赖」；
- KDoc 写明根因与「**禁止**按 `Security.getProvider("BC")` 取用」的禁令。

**刻意不做**：`removeProvider("BC")` / 以其它名注册完整版（`insertProviderAt`）——前者威胁平台组件，
后者引入「同一算法双 provider 名」的长期歧义；持有实例方案已完全覆盖需求且零注册表副作用。

## 4. 验证

| 层 | 用例 | 结果 |
|---|---|---|
| 宿主回归（模拟真机环境） | `BcProviderCollisionTest`：注册表注入同名「空服务」假 BC ⇒ ① 负向对照（注册表确实取不到 ChaCha7539，防环境构造失效）② 解耦断言（`bouncyCastleProvider() !== 注册表条目`）③ ChaCha20 生产引擎完整往返（修复前必挂）④ Twofish JCE 解析可用 | **1 例绿**（`tests=1 failures=0`） |
| 真机 instrumented | `BcProviderDeviceTest`（Redmi 4X / arm64-v8a / Android 17）：① ChaCha20 生产引擎真机往返 ② `NativeTwofish.available` 必须为 `true`（静默弃用症状的钉死）③ Twofish JCE 解析单分组变换 | **3 例全绿**（`tests=3 failures=0`，`BUILD SUCCESSFUL`） |
| 全量回归 | `.\gradlew.bat test --rerun-tasks --max-workers=1` | **绿**（2m 27s，114 tasks executed），聚合 **`tests=2116 skipped=13 failures=0 errors=0`**（§142 基准 2115 + 本批新增 1 例 `BcProviderCollisionTest`，吻合） |

**红证留痕**：修复前的真机红证即本条目的登记依据（探针首跑真实异常，logcat 产物
`logcat-com.keepasskey.crypto.perf.DeviceThroughputProbeTest-probeChaCha20_BC____.txt`，
2026-09-17 22:04）；修复后同路径由 `BcProviderDeviceTest` 生产引擎用例跑绿。

## 5. 如实声明

- 本批**未跑** `lint` / `assembleRelease`；
- `BcProviderCollisionTest` 的「假 BC」是空服务 Provider，与真机剥离版 BC 的**服务清单并不逐项
  相同**（真机剥离版仍含部分轻量算法如部分摘要），但对本条目涉及的 `ChaCha7539` / `Twofish`
  两个缺失算法的模拟是**等价的**（都不提供）；
- 真机验证在**单设备**（Redmi 4X）完成；其它 ROM 上平台 `"BC"` 的服务清单差异未实测
  （但修复方案与注册表完全解耦，理论上对任何注册表形态免疫）；
- 修复未触碰任何 KDBX 格式面 / 字节布局，宿主全量单测覆盖既有回归。

## 6. 过程缺陷与批外语动

- 无生产代码返工；`fullBouncyCastle` 用 `by lazy` 保证多测试并发下的单次构造与线程安全；
- 本批先于 `ISSUE-P3-153` 立项与 `ISSUE-P3-155` 整改执行（用户裁定顺序：P2-92 → P3-155 → P3-153 立项）。
