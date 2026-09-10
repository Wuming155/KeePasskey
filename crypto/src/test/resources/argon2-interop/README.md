# Argon2 互操作语料目录（`crypto/src/test/resources/argon2-interop/`）

> 归属条目：**ISSUE-P3-11**（Rust Argon2 原生内核的真机 instrumented 验证，P2-14 遗留）
> 本 README 说明**该目录已有什么 / 缺什么 / 缺的语料如何生成 / 为何尚未入库**。
> **严禁**将非 KeePass 官方产物冒称为「真实 KeePass 语料」。

---

## 1. 本目录现有内容（已入库）

| 文件 | 性质 | 说明 |
|---|---|---|
| `argon2-bc-vectors.json` | **BC 冻结向量**（非 `.kdbx`） | Batch 0 由 BouncyCastle 1.85.2 `Argon2BytesGenerator` 生成，覆盖 Argon2d/id × version `0x10`/`0x13` × 含/不含 `secret`(K) + `associatedData`(A) × `p=1`/`p=4` × 现实内存档位，另有 2 条 64B 长 AD 探针（R2）。由 `crypto/src/test/java/com/keepasskey/crypto/kdf/Argon2BcVectorTest.kt` 以「防漂移锁」守护：默认只读并重算校验，`-DexportArgon2Vectors=true` 时才重写。 |

**该 JSON 是向量（参数 + 期望输出 hex），不是数据库文件**，不能用于「端到端解锁」验证。
它已被 `NativeArgon2HostJniTest`（宿主侧）与 `NativeArgon2InstrumentedTest`（设备侧）作为冻结真相源复用。

---

## 2. 缺口：真实 KeePass / KeePassXC Argon2 `.kdbx` 语料 —— **待补**

ISSUE-P3-11 验收标准 2 要求使用**真实 KeePass 2.61.1 / KeePassXC 生成的 Argon2d（`0x10`）/ Argon2id
（`0x13`）`.kdbx`** 完成端到端解锁验证。**截至本次交付，该语料尚未入库，验收标准 2 未达成**
（同时受「无 arm64 真机」与「`crypto` 模块无 `.kdbx` 读写能力」两重阻塞，详见 §5）。

---

## 3. 语料生成方法（有设备/有环境后照此补入）

### 3.1 统一要求

1. **必须用一次性口令生成**：为语料专门造一个从不用于任何真实场景的口令，且库内**不得包含任何真实条目**
   （只放 `Sample` 之类占位条目）。这样即使入库也不构成敏感数据泄漏——这是本目录入库的**硬前提**。
2. **必须显式固定全部 KDF 参数**（`t`/`m`/`p`/版本），不得依赖工具默认值：KeePass 与 KeePassXC 的
   Argon2 默认值不同（KeePass 默认 `m=64MiB, t=2, p=2`；KeePassXC 默认 `m=64MiB, t=10, p=2`），
   依赖默认值会让语料不可复现。
3. **AD（associatedData）与 K（secret）留空**：真实 KeePass/KeePassXC 建库不写这两个字段，
   它们的覆盖由 `argon2-bc-vectors.json` 的向量族负责。
4. 生成后**立即用本仓库 `database` 模块的解锁路径自测一次**（宿主单测即可），确认该 `.kdbx`
   确实可被本工程解析，再入库。

### 3.2 KeePass 2.61.1（官方 C#，`.kdbx` v4 事实标准）

1. `File → New`，勾选 KDBX 4 格式；
2. `Database → Database Settings → Security → Key derivation function: Argon2d`；
   点 `1 second delay` 旁的参数按钮，**取消自动**并手工填 `Iterations (t)` / `Memory (MiB)` / `Parallelism (p)`；
3. 保存为 `argon2d-v19-t<..>-m<..>-p<..>-keepass2611.kdbx`（命名约定见 §4）；
4. 重复步骤 2 并把 KDF 改为 `Argon2id`，另存为 `argon2id-...-keepass2611.kdbx`。
   > 注：KeePass 2.61.1 的 Argon2 版本写死为 `0x13`(19)；`0x10` 需要 KeePassXC 或
   > 用本仓库 `-DexportArgon2Vectors` 路线另行构造，故 `0x10` 语料在 KeePass 侧不产出。

### 3.3 KeePassXC（算法级参考实现）

1. `Database → Database Settings → Security → Encryption: Argon2`；
2. `Advanced` 中把 `KDF variant` 选 `Argon2d` / `Argon2id`，
   手工设定 `Iterations` / `Memory (MiB)` / `Parallelism`（KeePassXC 允许选 Argon2 版本 `1.0`/`1.3`，
   对应 `0x10`/`0x13`，这是本目录补齐 **`0x10` 语料**的正规来源）；
3. 另存为 `argon2id-v16-t<..>-m<..>-p<..>-keepassxc.kdbx`。

### 3.4 落地位置（**关键工程约束**）

| 消费方 | 语料应放的位置 | 原因 |
|---|---|---|
| **宿主单测**（`./gradlew test`，桌面 JVM） | `crypto/src/test/resources/argon2-interop/`（本目录） | JVM 单测资源由 `src/test/resources` 提供 |
| **设备侧 instrumented 测试**（`connectedAndroidTest`） | **`<模块>/src/androidTest/assets/`** | `src/test/resources` **不会**被打进 androidTest APK；设备侧只能读 `assets`/`res`/`files` |

> ⚠️ 验收标准 2 原文写「语料需先补入 `crypto/src/test/resources/argon2-interop/`」，
> 该位置**只服务于宿主单测**。若要在**设备上**做端到端解锁，语料必须**另行复制/软链**到
> androidTest 的 `assets` 目录（或在测试内从 `assets` 读取）；二者不可互替。

---

## 4. 命名与伴生元数据约定

```
<kdf>-v<版本十进制>-t<迭代>-m<内存MiB>-p<并行度>-<来源>.kdbx
```

示例：

```
argon2d-v19-t2-m64-p2-keepass2611.kdbx
argon2id-v19-t2-m64-p4-keepassxc.kdbx
argon2id-v16-t3-m64-p2-keepassxc.kdbx      # 0x10 版本只能由 KeePassXC 产出
```

每个 `.kdbx` **必须**配一个同名 `.json` 伴生文件，记录可复现所需的全部非敏感元数据：

```json
{
  "source": "KeePassXC 2.7.x",
  "kdf": "argon2id",
  "version": 19,
  "iterations": 2,
  "memoryKib": 65536,
  "parallelism": 2,
  "saltHex": "<32B hex，从文件头 KdfParameters 的 S 字段读出>",
  "passphraseIsThrowaway": true,
  "containsRealData": false,
  "note": "一次性口令生成、库内仅占位条目；口令见 passphraseNote"
}
```

> **口令本身不写入伴生文件**：语料口令固定为**测试专用一次性口令**，由测试代码内常量提供
> （`CharArray` + 用后清零，遵守敏感数据铁律）；`passphraseNote` 只写「非敏感测试口令」的定位说明。

---

## 5. 为何尚未入库（如实登记）

1. **无设备/无 GUI 环境**：本轮交付环境的 `adb devices` 初始为空（后经启动本机 AVD 才获得
   x86_64 模拟器，见 `docs/原生Argon2真机验证记录.md`），且**无 arm64 真机**；
   KeePass/KeePassXC 均需交互式 GUI 手工建库，无法在无人值守流水线内自动产出。
2. **模块单向依赖的限制（更本质的原因）**：模块依赖严格单向
   （`app → database → crypto → core`），**`crypto` 模块不依赖 `database`，因而不具备 `.kdbx` 读写能力**。
   因此「`.kdbx` 端到端解锁」用例**不可能**落在 `crypto` 模块的 androidTest 内，
   只能落在 `database` 模块（`database` 目前同样**没有 `androidTest` 源集**）。
   本目录中的 `.kdbx` 只能被 `database` 模块的测试消费。
3. **脱敏成本与体量**：任何「已用过」的真实 `.kdbx` 都不能入库（口令/密钥材料/真实条目）。
   合规做法只有 §3.1 的「一次性口令 + 零真实条目」重建，这需要人工 GUI 操作与逐个人工复核。

### 5.1 仓库内既有的 `.kdbx` 现状（**不是**本次要补的语料）

| 文件 | KDF 实测 | 性质 | 能否充当验收标准 2 的语料 |
|---|---|---|---|
| `database/src/test/resources/fixtures/test_vault.kdbx` | **Argon2d**，`V=19`(`0x13`)、`I=89`、`M=67108864`(64MiB)、`P=4`、32B 盐 | **本工程自建的合成夹具**，非 KeePass/KeePassXC 官方产物 | **不能**。既非官方工具产出，也只覆盖 Argon2d 单一组合，且位于 `database` 模块 |
| `KeePasskey测试/测试.kdbx`、`KeePasskey测试_backup/测试.kdbx` | 头部同为 Argon2d（`0x13`） | 开发期手工测试库（**工作区未跟踪目录**，非测试资源） | **不能**。属个人测试库，可能含真实数据，禁止入库 |
| `参考项目/keepassxc-develop/tests/data/*.kdbx` | 多数为 AES-KDF 旧格式 | **第三方参考项目**资产 | **不能**。许可证约束（AGENTS.md §3.3 严禁复制参考项目文件入库） |

---

## 6. 补入语料后的验证方式（一条命令）

```powershell
# 设备侧（需先连真机 / 起模拟器）
.\gradlew.bat :crypto:connectedDebugAndroidTest --console=plain
# 宿主侧
.\gradlew.bat :crypto:test --console=plain
```

`.kdbx` 端到端解锁用例落地到 `database` 模块后，对应命令为：

```powershell
.\gradlew.bat :database:connectedDebugAndroidTest --console=plain
```

> 该命令**当前不可用**：`database` 模块尚无 `androidTest` 源集（需按 `crypto/build.gradle.kts`
> 的同一模式补 `testInstrumentationRunner` 与 `androidTestImplementation(androidx.test.*)`）。

---

## 7. 变更纪律

- 本目录新增 `.kdbx` 前，必须逐条满足 §3.1 的「一次性口令 + 零真实条目」硬前提，并在伴生 `.json`
  中登记 `containsRealData: false`；
- `argon2-bc-vectors.json` 是**冻结真相源**，只允许经 `:crypto:test -DexportArgon2Vectors=true`
  用 BC 重算覆写，禁止手工编辑；改动需与 `Argon2BcVectorTest` 的防漂移锁同批通过。
