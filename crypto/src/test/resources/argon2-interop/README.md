# Argon2 互操作语料目录（`crypto/src/test/resources/argon2-interop/`）

> 归属条目：**ISSUE-P3-23**（承接 ISSUE-P3-11 验收标准 2：真实 KeePass 2.61.1 / KeePassXC
> Argon2 `.kdbx` 语料的端到端解锁验证）
> 本 README 说明**该目录已有什么 / 缺什么 / 缺的语料如何生成（逐步可执行）/ 生成后放哪里**。
> **严禁**将非 KeePass 官方产物冒称为「真实 KeePass 语料」，**严禁**使用任何真实主密码。

---

## 1. 本目录现有内容（已入库）

| 文件 | 性质 | 说明 |
|---|---|---|
| `argon2-bc-vectors.json` | **BC 冻结向量**（非 `.kdbx`） | Batch 0 由 BouncyCastle 1.85.2 `Argon2BytesGenerator` 生成，覆盖 Argon2d/id × version `0x10`/`0x13` × 含/不含 `secret`(K) + `associatedData`(A) × `p=1`/`p=4` × 现实内存档位，另有 2 条 64B 长 AD 探针（R2）。由 `crypto/src/test/java/com/keepasskey/crypto/kdf/Argon2BcVectorTest.kt` 以「防漂移锁」守护：默认只读并重算校验，`-DexportArgon2Vectors=true` 时才重写。 |

**该 JSON 是向量（参数 + 期望输出 hex），不是数据库文件**，不能用于「端到端解锁」验证。
它已被 `NativeArgon2HostJniTest`（宿主侧）与 `NativeArgon2InstrumentedTest`（设备侧）作为冻结真相源复用。

---

## 2. 缺口：真实 KeePass / KeePassXC Argon2 `.kdbx` 语料 —— **仍未入库**

ISSUE-P3-23 要求使用**真实 KeePass 2.61.1 / KeePassXC 生成的 Argon2d / Argon2id `.kdbx`**
完成端到端解锁验证。**截至本次交付（2026-09-10），本目录下仍无任何 `.kdbx` 语料，
该验收标准仍未达成** —— 阻塞原因见 §5。

本轮**已完成的是工程侧就绪**（不是验证通过）：

| 本轮完成项 | 位置 | 性质 |
|---|---|---|
| `database` 模块 androidTest 源集与 Gradle 接线 | `database/build.gradle.kts`（`defaultConfig.testInstrumentationRunner` + `androidTestImplementation`） | **工程就绪**（源集存在、依赖就位） |
| 设备侧端到端解锁用例（fail-closed） | `database/src/androidTest/java/com/keepasskey/database/RealKdbxCorpusUnlockTest.kt` | **代码就绪**；语料缺失时**显式跳过**，跳过 ≠ 通过 |
| 设备侧语料落位目录 | `database/src/androidTest/assets/argon2-interop/` | 目录已建（内含落位说明 README，**非语料**） |

> ⚠️ 「用例写好了」**不等于**「验证通过了」。在语料入库并真实跑出绿证之前，
> 验收标准 2 一律记为**未达成**。

---

## 3. 语料生成方法（**逐步可执行**清单）

### 3.0 一次性前置：三条硬前提（违反即事故）

1. **口令必须是公开的、一次性测试向量口令**：语料固定使用
   `Test-Vector-Only-2026!`（**虚构口令**，见 §4 说明）。
   **严禁**使用任何真实主密码，也**严禁**把任何真实库复制进来后「改个名」充当语料。
2. **库内零真实数据**：只放 2~3 条占位条目（如标题 `Vector-Sample-1`），
   条目里的用户名/密码/备注一律写明显的假值（例如 `vector-user` / `vector-not-a-real-secret`）。
   生成后请**人工复核一遍**：确认没有任何来自真实生活的字符串。
3. **显式固定全部 KDF 参数**（`t` / `m` / `p` / 版本），不得依赖工具默认值 ——
   KeePass 2.61.1 默认 `m=64MiB, t=2, p=2`，KeePassXC 默认 `m=64MiB, t=10, p=2`，
   依赖默认值会让语料不可复现、也无法与本用例的断言对齐。

### 3.1 KeePass 2.61.1（Windows，官方 C# 实现）

1. 启动 KeePass 2.61.1，`File → New…`，在向导中确认格式为 **KDBX 4**（`.kdbx`）；
2. 主密码填 `Test-Vector-Only-2026!`（**不勾选**密钥文件；本批语料先只覆盖「仅主密码」路径，
   复合密钥路径已由 `database/src/test/resources/fixtures/` 的既有夹具覆盖）；
3. `Database → Database Settings… → Security` 页：
   - `Key derivation function` 选 **Argon2d**；
   - 点 `1 second delay` 右侧的参数按钮，**取消自动调整**，手工填：
     `Iterations (t) = 2`、`Memory (MiB) = 64`、`Parallelism (p) = 2`；
4. 建 2~3 条占位条目（例如标题分别为 `Vector-Sample-1` / `Vector-Sample-2` / `Vector-Sample-3`）；
5. `File → Save As…`，文件名
   **`argon2d-v19-t2-m64-p2-keepass2611.kdbx`**（命名约定见 §5）；
6. 重复步骤 3 但把 KDF 改为 **Argon2id**、`Parallelism (p) = 4`，
   另存为 **`argon2id-v19-t2-m64-p4-keepass2611.kdbx`**；
7. 在 `Database → Database Settings → Security` 里**回读一遍**参数，确认与文件名一致
   （文件名就是声明，必须与文件头真实参数逐项相等，否则设备侧用例会硬失败）。

> 注：KeePass 2.61.1 的 Argon2 版本写死为 `0x13`(19)；**`0x10` 版本语料只能由 KeePassXC 产出**
>（见 §3.2），故 `argon2d-v16-...` 一律标注来源为 `keepassxc`。

**生成后请顺手记录两个数字**：文件头 KDF 的 **32B 盐（hex）** 与实际**条目总数**（含子分组）。
盐可用本仓库既有 JVM 单测路径读出，或由 KeePass 的 `Tools → Database → …` 之外的第三方工具读出；
取不到盐时伴生 `.json` 的 `saltHex` 可留空（该字段为**可选**，缺失只会少一条「文件未被替换」的旁证断言）。

### 3.2 KeePassXC（跨平台，算法级参考实现）

1. KeePassXC 新建数据库，主密码同样填 `Test-Vector-Only-2026!`；
2. `Database → Database Settings… → Security`：
   - `Encryption` 选 **Argon2**；
   - `KDF variant` 选 **Argon2d** 或 **Argon2id**；
   - **手工设定** `Iterations = 2`、`Memory = 64 MiB`、`Parallelism = 2`（或 `4`）；
   - KeePassXC 允许选 **Argon2 版本 `1.0` / `1.3`**（对应 `0x10` / `0x13`）——
     这是本目录补齐 **`0x10` 版本语料**的正规来源；
3. 加 2~3 条占位条目；
4. 另存为 **`argon2id-v16-t2-m64-p2-keepassxc.kdbx`** 等（同样遵守 §5 命名约定）。

### 3.3 命名约定与伴生元数据

见 §5、§6。**入库前必须**按 §6 的 schema 写好同名 `.json`。

### 3.4 落地位置（**关键工程约束：两个位置都要放，不可互替**）

| 消费方 | 语料应放的位置 | 原因 |
|---|---|---|
| **宿主单测**（`.\gradlew.bat test`，桌面 JVM） | `crypto/src/test/resources/argon2-interop/`（本目录） | JVM 单测资源由 `src/test/resources` 提供 |
| **设备侧 instrumented 测试**（`connectedAndroidTest`） | **`database/src/androidTest/assets/argon2-interop/`** | `src/test/resources` **不会**被打进 androidTest APK；设备侧只能读 `assets`/`res`/`files` |

> ⚠️ 同一份 `.kdbx` 需要**各放一份**（或由构建脚本在打包前复制），**二者不可互替**。
> 设备侧那份是 `RealKdbxCorpusUnlockTest` 唯一的读取来源。

---

## 4. 测试用口令（公开常量，**仅供测试向量**）

```
Test-Vector-Only-2026!
```

- 该口令是**公开写入仓库的一次性测试口令**，由设备侧用例内常量提供
  （`RealKdbxCorpusUnlockTest.CORPUS_PASSPHRASE`，以 `CharArray` 承载、用后清零）；
- 它**不写入**伴生 `.json`（伴生文件只登记非敏感元数据）；
- **严禁**任何真实库使用该口令；**严禁**把真实库的口令替换成该口令后入库
  （那会连带把真实条目数据带进仓库）。

---

## 5. 为何尚未入库（如实登记，2026-09-10）

1. **无 GUI / 无人工干预环境**：KeePass 2.61.1 与 KeePassXC 均需交互式 GUI 手工建库，
   无法在无人值守流水线内自动产出，也不能由本仓库代码「生成」——自生成产物**不是**互操作证据。
2. **不得使用本机既有的开发期测试库**：工作区内 `KeePasskey测试/测试.kdbx` 等属个人测试库，
   可能含真实数据，**禁止**入库（见 §7.1）。
3. **`crypto` 模块不可能承载该用例（结构性原因）**：模块依赖严格单向
   （`app → database → crypto → core`），**`crypto` 不依赖 `database`，因而不具备 `.kdbx` 读写能力**。
   因此该用例只能落在 `database` 模块 —— 本轮已为其补齐 `androidTest` 源集与用例（见 §2 表）。
4. **`database` 侧的 `androidTest` 已接线（本轮完成），故「工程阻塞」已消除**；
   现在剩余的阻塞**只是语料本身**：需要人工用官方 GUI 工具生成并复核后入库。

### 5.1 仓库内既有的 `.kdbx` 现状（**不是**本次要补的语料）

| 文件 | KDF 实测 | 性质 | 能否充当验收标准 2 的语料 |
|---|---|---|---|
| `database/src/test/resources/fixtures/test_vault.kdbx` | **Argon2d**，`V=19`(`0x13`)、`I=89`、`M=67108864`(64MiB)、`P=4`、32B 盐 | **本工程自建的合成夹具**，非 KeePass/KeePassXC 官方产物 | **不能**。既非官方工具产出，也只覆盖 Argon2d 单一组合，且位于 `database` 模块的 `src/test/resources`（**不进** androidTest APK） |
| `KeePasskey测试/测试.kdbx`、`KeePasskey测试_backup/测试.kdbx` | 头部同为 Argon2d（`0x13`） | 开发期手工测试库（**工作区未跟踪目录**，非测试资源） | **不能**。属个人测试库，可能含真实数据，禁止入库 |
| `参考项目/keepassxc-develop/tests/data/*.kdbx` | 多数为 AES-KDF 旧格式 | **第三方参考项目**资产 | **不能**。许可证约束（AGENTS.md §3.3 严禁复制参考项目文件入库） |

---

## 6. 命名与伴生元数据约定

### 6.1 文件名

```
<kdf>-v<版本十进制>-t<迭代>-m<内存MiB>-p<并行度>-<来源>.kdbx
```

示例：

```
argon2d-v19-t2-m64-p2-keepass2611.kdbx
argon2id-v19-t2-m64-p4-keepass2611.kdbx
argon2id-v19-t2-m64-p4-keepassxc.kdbx
argon2id-v16-t2-m64-p2-keepassxc.kdbx      # 0x10 版本只能由 KeePassXC 产出
```

### 6.2 伴生元数据（**同名 `.json`，必需**）

设备侧用例会**硬失败**（不是跳过）当伴生文件缺失或必需字段不全。

```json
{
  "source": "KeePass 2.61.1",
  "kdf": "argon2d",
  "version": 19,
  "iterations": 2,
  "memoryKib": 65536,
  "parallelism": 2,
  "entryCount": 3,
  "entryTitles": ["Vector-Sample-1", "Vector-Sample-2", "Vector-Sample-3"],
  "saltHex": "<32B hex，可选；从文件头 KdfParameters 的 S 字段读出>",
  "keyFile": "<可选：与本 .kdbx 配套的密钥文件名（放同一目录）>",
  "passphraseIsThrowaway": true,
  "containsRealData": false,
  "note": "一次性口令生成、库内仅占位条目；口令为公开测试常量，见 README §4"
}
```

字段语义与用例断言对应关系：

| 字段 | 必需 | 用例如何用 |
|---|---|---|
| `source` | ✅ | 必须含 `keepass`（忽略大小写），否则**硬失败**——禁止用自建夹具冒充互操作语料 |
| `kdf` | ✅ | 仅 `argon2d` / `argon2id`，与文件头 KDF 类型比对 |
| `version` | ✅ | 与 `KdfParameters.Argon2.version`（16 / 19）比对 |
| `iterations` / `memoryKib` / `parallelism` | ✅ | 分别与 `t` / `m`（KiB→B 换算） / `p` 比对 |
| `entryCount` | ✅ | 与 `rootGroup.allEntries().size` 比对（**含所有子分组**；回收站中的条目也计入） |
| `entryTitles` | 可选 | 非空时与解锁出的标题集合比对（标题是占位内容，非敏感） |
| `saltHex` | 可选 | 非空时与文件头 KDF 盐比对（旁证「文件未被替换」） |
| `keyFile` | 可选 | 非空时从同一 assets 目录读取该密钥文件并作为复合密钥分量传入 |
| `passphraseIsThrowaway` | ✅ | 必须为 `true`，否则**硬失败** |
| `containsRealData` | ✅ | 必须为 `false`，否则**硬失败**（含真实数据的库严禁入库） |

> 伴生文件由平台内置 `org.json` 解析，**不引入任何第三方依赖**。

---

## 7. 补入语料后的验证方式（两条命令）

```powershell
# 1) 宿主侧（桌面 JVM）
.\gradlew.bat :crypto:test --console=plain

# 2) 设备侧（需先连真机 / 起模拟器；语料在 database/src/androidTest/assets/ 下）
.\gradlew.bat :database:connectedDebugAndroidTest --console=plain
```

设备侧结果判定：
- **`skip`（跳过）** = 语料仍缺失 → 验收标准 2 **未达成**（跳过不是通过）；
- **红（失败）** = 语料在但断言不成立（伴生元数据不完整、KDF 参数不符、条目数不符、解锁失败）；
- **绿（通过）** = 该 `.kdbx` 在设备上真实解锁成功且与声明逐项一致 → 方可将该条目标记为达成。

---

## 8. 变更纪律

- 本目录新增 `.kdbx` 前，必须逐条满足 §3.0 的「一次性口令 + 零真实条目」硬前提，并在伴生 `.json`
  中登记 `containsRealData: false`、`passphraseIsThrowaway: true`；
- **严禁**把「本工程自己写出的 `.kdbx` 再自己读回」当作互操作证据；设备侧的
  `SelfGeneratedRoundTripInstrumentedTest` 已显式命名为 round-trip 并在 KDoc 中声明
  **不构成与 KeePass 2.61.1 / KeePassXC 的互操作证据**；
- `argon2-bc-vectors.json` 是**冻结真相源**，只允许经 `:crypto:test -DexportArgon2Vectors=true`
  用 BC 重算覆写，禁止手工编辑；改动需与 `Argon2BcVectorTest` 的防漂移锁同批通过。
