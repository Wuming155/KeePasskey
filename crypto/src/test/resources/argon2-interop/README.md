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
| `argon2d-v19-t89-m64-p4-keepassxc.kdbx` | **真实 `.kdbx` 语料** | **KeePassXC 官方产物**（内层 `Meta/Generator=KeePassXC`）；Argon2d · v19 · t=89 · m=64 MiB · p=4 · AES-256-CBC；14 条条目**全部**为「模拟账号」占位数据。设备侧 `RealKdbxCorpusUnlockTest` 消费（双落位见 §3.4）。 |
| `argon2d-v19-t89-m64-p4-keepassxc.json` | 伴生元数据（非语料） | 与 `.kdbx` **同名**；声明 KDF 参数 / 条目数与标题 / 口令来源；schema 见 §6.2。 |
| `111.keyx` | **XML KeyFile v2.0**（密钥文件因子） | 与上述 `.kdbx` 配套的复合密钥分量，由伴生 `.json` 的 `keyFile` 字段引用。 |

**该 JSON 是向量（参数 + 期望输出 hex），不是数据库文件**，不能用于「端到端解锁」验证。
它已被 `NativeArgon2HostJniTest`（宿主侧）与 `NativeArgon2InstrumentedTest`（设备侧）作为冻结真相源复用。

---

## 2. 真实 KeePass / KeePassXC Argon2 `.kdbx` 语料 —— **已入库（2026-09-11）**

ISSUE-P3-23 要求使用**真实 KeePass 2.61.1 / KeePassXC 生成的 Argon2d / Argon2id `.kdbx`**
完成端到端解锁验证。**2026-09-11 起本目录已入库一份真实 KeePassXC 语料**，验收标准 2 达成：

| 语料 | 来源 | KDF（文件头实测） | 条目 |
|---|---|---|---|
| `argon2d-v19-t89-m64-p4-keepassxc.kdbx`（+ 同名 `.json` + `111.keyx`） | **KeePassXC 官方产物**（`Meta/Generator=KeePassXC`） | Argon2d · v19 · t=89 · m=64 MiB · p=4 | 14 条（全为「模拟账号」占位数据） |

**设备侧证据**：`.\gradlew.bat :database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest`
**2/2 pass、0 skip、0 failure**（此前为 skip）。原始证据见 `docs/RESOLVED_LOG.md` §25。

> ⚠️ 仍然成立：**「用例写好了」不等于「验证通过了」**。本条的绿证由「语料在库 + 设备侧用例真实跑绿」
> 两者同时成立才给出；若语料被移除，用例会退回 skip，届时验收标准 2 应重新记为**未达成**。


---

## 3. 语料生成方法（**逐步可执行**清单）

### 3.0 一次性前置：三条硬前提（违反即事故）

1. **口令必须是公开的、一次性测试向量口令**：语料默认使用
   `Test-Vector-Only-2026!`（**虚构口令**，见 §4 说明）；某份语料若自带专用一次性口令，
   可在其伴生 `.json` 以 `passphrase` 声明（见 §4 / §6.2）。
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

### 3.5 自动化入口（ISSUE-P3-38 起）

`tools/kdbx-corpus/generate_corpus.py` 把上面 §3.1~§3.4 中**可自动化的部分**收敛为一条命令，
并在**落盘前**用纯标准库解析 `.kdbx` 文件头，核对「文件名所声明的参数」与真实参数是否逐项相等
（§6.1 规定「文件名就是声明」，靠人工回读极易出错）：

```bash
# 推荐路径：仍由官方 GUI 按 §3.1 / §3.2 建库（GUI 才能精确设定 KDF 参数），
# 随后由脚本完成核验 + 规范命名 + 写伴生 JSON + 双落位：
python tools/kdbx-corpus/generate_corpus.py --ingest <file.kdbx> --source keepassxc
```

脚本的能力边界、退出码与安全纪律见 [`tools/kdbx-corpus/README.md`](../../../../tools/kdbx-corpus/README.md)。
**注意**：脚本产出的是**语料与元数据**，不是互操作证据——「验证通过」仍只能由设备侧用例跑出来。

---

## 4. 测试用口令（公开常量，**仅供测试向量**）

```
Test-Vector-Only-2026!
```

- 该口令是**公开写入仓库的一次性测试口令**，由设备侧用例内常量提供
  （`RealKdbxCorpusUnlockTest.CORPUS_PASSPHRASE`，以 `CharArray` 承载、用后清零）；
- **例外**：某份语料若自带**专用**一次性口令（例如由官方工具建库时即已设定），可在其同名伴生
  `.json` 中以可选字段 `passphrase` 声明**该语料专用值**（见 §6.2）。该值**必须同为公开的一次性
  测试常量**，执行时同样转 `CharArray` 并在 `finally` 中清零；它只对该份语料生效，不改变本默认值。
- 它**默认不写入**伴生 `.json`（伴生文件默认只登记非敏感元数据）；仅上一条例外允许登记公开测试口令；
- **严禁**任何真实库使用上述任一口令；**严禁**把真实库的口令替换成测试口令后入库
  （那会连带把真实条目数据带进仓库）。

---

## 5. 入库过程与残余（如实登记）

1. **语料来源已落实（2026-09-11）**：本仓**既有**一份由 **KeePassXC 官方建库**的真实 Argon2d `.kdbx`
   （`KeePasskey测试/测试.kdbx`，工作区本地库，被 `.gitignore` 忽略），经**解密探针逐条核对**——
   内层 `Meta/Generator=KeePassXC`、14 条条目**全部**为「模拟账号」占位数据、零真实数据——
   后复制入本目录与 `database/src/androidTest/assets/argon2-interop/`（双落位，§3.4），并配同名伴生 `.json`。
   本次**未**依赖 GUI 手工建库（既有真实语料已满足验收标准 2）。
2. **「不得使用开发期测试库」这条前提被证据部分推翻**：`KeePasskey测试/测试.kdbx` 经逐条复核确为
   **全占位测试数据**（并非含真实数据的个人库），故可用；但**仍严禁**把任何含真实数据或真实口令的库入库。
3. **`crypto` 模块不可能承载该用例（结构性原因）**：模块依赖严格单向
   （`app → database → crypto → core`），**`crypto` 不依赖 `database`，因而不具备 `.kdbx` 读写能力**。
   因此该用例只能落在 `database` 模块 —— 其 `androidTest` 源集与用例已就位。
4. **过程留痕（如实）**：语料落位前，本文档曾把「官方 GUI 建库」视为唯一不可自动化步骤；
   本次因本机已有官方 KeePassXC 产出物，该步骤实际**无需**发生——「GUI 建库」不再是本条阻塞。

### 5.1 仓库内既有的 `.kdbx` 现状与本次处置

| 文件 | KDF 实测 | 性质 | 本次处置 |
|---|---|---|---|
| `KeePasskey测试/测试.kdbx` + `111.keyx` | **Argon2d**，`V=19`(`0x13`)、`I=89`、`M=67108864`(64MiB)、`P=4`、32B 盐 | **KeePassXC 官方产物**（`Meta/Generator=KeePassXC`）；14 条条目**全部**为「模拟账号」占位数据；源文件位于 **gitignore 的工作区目录** | ✅ **已入库为验收标准 2 语料**（复制为 `argon2d-v19-t89-m64-p4-keepassxc.kdbx` + `111.keyx` + 同名 `.json`，双落位）。原件仍保持未跟踪 |
| `database/src/test/resources/fixtures/test_vault.kdbx` + `111.keyx` | **Argon2d**，`V=19`、`I=89`、`M=67108864`(64MiB)、`P=4`、32B 盐 | 工程既有的真实 KeePass 4.0 夹具（复合密钥；据 `RealKdbxInteroperabilityTest` KDoc） | **未使用**：位于 `src/test/resources`（**不进** androidTest APK） |
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
  "passphrase": "<可选：本语料专用的一次性测试口令；缺省即用 README §4 的默认常量>",
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
| `passphrase` | 可选 | 非空时作为本语料主口令（**公开一次性测试常量**，见 §4）；缺省回退 `CORPUS_PASSPHRASE` |
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
