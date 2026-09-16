# SyncCache 清理用例「大写命名 .CACHE.tmp 残留」定位记录（ISSUE-P3-142）

> **结论一句话**：该残留**不是任何写入者产出的磁盘文件**，而是 **Windows / NTFS 的目录枚举鬼影条目**
> ——`File.listFiles()`（Java 目录索引枚举）偶发返回**大写拼写、磁盘上并不存在**的条目；
> 本仓源码、Gradle/JUnit 临时目录机制、外部进程三者在「实际写入者」意义上**均已排除**。
> 处置：**不放宽**「清理后不得残留」判定，改为「以磁盘上真实存在的条目为准」的更强判定并留痕鬼影。
>
> 定位时间：2026-09-16（本机时区 UTC+8）。定位人：缺陷定位子代理（ISSUE-P3-142）。

---

## 1. 背景与待判问题的收敛

`docs/ACTIVE_ISSUES.md` 中 ISSUE-P3-142 的原始形态是：`SyncCacheTest` 的
「clear 与 clearAll 均不删除防回滚状态文件」用例偶发失败，清理后目录内残留
`<大写十六进制键>.CACHE.tmp`（键为 `sha256("remote/vault.kdbx")`）。

原始描述里有三个**未经验证的前提**，本次逐一取证：

| 原始前提 | 取证结果 |
|---|---|
| 「大写十六进制键」是真实磁盘拼写 | **部分成立**：枚举 API 确实返回大写拼写，但该条目**在磁盘上不存在**（见 §3） |
| 残留物是一份真实文件（「实际写入者」待查） | **不成立**：三重交叉取证（File / NIO / 原生 `dir`）一致判定其不存在 |
| 命名无法从本仓源码复现 | **成立**：全仓无大写十六进制产出点（见 §2） |

---

## 2. 静态取证：本仓是否存在该命名的产 Writer

### 2.1 大写十六进制产出点（全仓检索，含大小写变体）

检索式：`toUpperCase` / `uppercase()` / `%X` / `HexFormat` / `upperCase = true`。

| 命中 | 位置 | 是否与本形态相关 |
|---|---|---|
| `KdbxUuid.toFormattedString()` | `core/.../model/KdbxUuid.kt:56`（`HexFormat { upperCase = true }`） | 否：产出的是 KDBX UUID 展示串（32 位），非 64 位 SHA-256 |
| `BreachHasher` 大写字符表 | `app/.../breach/BreachHasher.kt:23` | 否：HIBP 的 SHA-1 前缀（40 位），与 `sync` 无调用关系 |
| `CallerCertDigests` / `CredentialManagerCallerTrustStore` / `DigitalAssetLinksVerifier` | `app` 模块 | 否：证书指纹归一化，非文件名产出 |
| 其余 `uppercase()` | `TotpKeyUriParser` / `FieldReferenceEngine` / `VaultEntryMapper` / `DicewareWordList` | 否：算法名 / 字段码归一化 |

结论：**本仓不存在「SHA-256 → 大写十六进制 → 用作文件名」的产出路径**。

### 2.2 所有 `.tmp` 名称产出点

| 产出点 | 命名形态 | 位置 |
|---|---|---|
| `SyncCache.tmpFileFor` | `<目标名>.<UUID>.tmp`（小写键） | `sync/.../engine/SyncCache.kt:371-372` |
| `SyncRollbackGuard.persist` | `<键>.rollback.<UUID>.tmp` | `sync/.../engine/SyncRollbackGuard.kt:191` |
| `AtomicFileWriter` | `<目标名>.tmp`（无 UUID，但目标名不是 `<键>.<后缀>`） | `database/.../session/AtomicFileWriter.kt:36,76` |
| `SyncCache.clear` 清理清单中的 `"$SUFFIX_CACHE$SUFFIX_TMP"` | 仅**读取/匹配** `.cache.tmp`，**不写入** | `sync/.../engine/SyncCache.kt:297` |

即：**「不含 UUID 的 `<键>.<后缀>.tmp`」在本仓当前源码中只作为历史命名的清理目标存在，没有任何写入者**。
（该清理清单条目由 wave2 三哈希状态机引入，属历史兼容清理，不代表今日写入路径。）

### 2.3 关键常量核对（已核实）

- `SUFFIX_CACHE = ".cache"`（小写，`SyncCache.kt:455`）、`SUFFIX_TMP = ".tmp"`（`:460`）；
- `sha256Hex` 走 `digest.toHexString()`（`:494-496`）**全小写**；
- `SyncRollbackGuard.SUFFIX_STATE = ".rollback"`（`:233`）；
- 编译产物 `javap` 字符串常量复核：`SyncCache.class` 中含 `.cache`、`.cache.tmp`、`.tmp` 等，
  **无任何大写变体**。

---

## 3. 决定性取证：残留条目在磁盘上并不存在

### 3.1 复现与现场抓取

复现命令（经共享锁，禁用直接 `gradlew`）：

```bash
for i in $(seq 1 60); do
  bash /d/tmp/gwlock.sh :sync:testDebugUnitTest --tests "*SyncCacheTest*" --rerun-tasks
done
```

失败原文（取自 Gradle 写出的 `sync/build/test-results/.../TEST-...SyncCacheTest.xml` 的
`<failure message=...>`，**逐字保留**）：

```
java.lang.AssertionError: 除防回滚状态外不得残留其他缓存文件
expected:<[8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback]>
but was:<[8215950248E31EB9E1AE3C4A80C69A10236146847F0C8A7EBABA7F9BA5BED990.VERSION.tmp,
         8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback]>
```

```
java.lang.AssertionError: 清理后不得残留任何 .tmp（承载密文快照片段）：
[8215950248E31EB9E1AE3C4A80C69A10236146847F0C8A7EBABA7F9BA5BED990.CACHE.tmp]
```

```
java.lang.AssertionError: 清理后不得残留任何 .tmp（承载密文快照片段）：
[8215950248E31EB9E1AE3C4A80C69A10236146847F0C8A7EBABA7F9BA5BED990.BASECACHE.tmp]
```

⇒ **「大写」是枚举 API 返回的真实字符串**（非转录/显示 artifact）；且该形态在 8 次独立失败中出现 8 次，
`.CACHE.tmp` / `.VERSION.tmp` / `.BASECACHE.tmp` 三种后缀（后缀段皆为大写）**全部不含 UUID**。

### 3.2 同进程三重交叉取证（决定性）

在原用例后追加一次性探针（**已撤除**），把「刚枚举到的残留」用三种互不相关的 API 复核：

```
==== HIT phase=A iteration=24 clearAll=true
dir=C:\Users\baiyun\AppData\Local\Temp\junit7529902420441758455\loop-a\iter-a
firstEnumerate=[8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback]
secondEnumerate=[8215950248E31EB9E1AE3C4A80C69A10236146847F0C8A7EBABA7F9BA5BED990.CACHE.tmp,
                 8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback]
dirStreamAll=[8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback]
nativeDirAll=8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback ;
  entry=[8215950248E31EB9E1AE3C4A80C69A10236146847F0C8A7EBABA7F9BA5BED990.CACHE.tmp]
    exists=false isFile=false canRead=false size=0
    nio: attrs=false size=null creationTime=null lastModified=null
  entry=[8215950248e31eb9e1ae3c4a80c69a10236146847f0c8a7ebaba7f9ba5bed990.rollback]
    exists=true isFile=true canRead=true size=17
    nio: attrs=true size=17 creationTime=2026-09-16T14:05:50.2232936Z
```

判读（四路证据一致）：

| 取证手段 | 大写条目 | 真实文件（`.rollback`） |
|---|---|---|
| `File.listFiles()`（Java 目录索引枚举） | **出现**（拼写为大写） | 出现 |
| `File.exists()` / `isFile` / `canRead()` / `length()` | **false / false / false / 0** | true / true / true / 17 |
| `Files.readAttributes(...)`（NIO） | **读不到（attrs=false）** | 读到（size=17，含创建时间） |
| `Files.newDirectoryStream`（NIO 目录流） | **看不到** | 看到 |
| `cmd /c dir /b /a`（原生 `FindFirstFile` 语义） | **看不到** | 看到 |

同轮另一次命中（`firstEnumerate` 即含鬼影、`secondEnumerate` 为空）：

```
==== HIT phase=B iteration=7 clearAll=true
firstEnumerate=[8215950248E31EB9E1AE3C4A80C69A10236146847F0C8A7EBABA7F9BA5BED990.BASECACHE.tmp]
secondEnumerate=[]
dirStreamAll=[]
nativeDirAll=
```

⇒ 该条目**只在某一次 `File.listFiles()` 的返回值里存在**，
`File` 语义自查、NIO 两种枚举、原生 `dir` 四种手段**全部否认它有实体**。
**「实际写入者」不存在**——因为没有任何东西被写到磁盘上。

### 3.3 补充测量（三条新实验，均为本次实测）

**① `isFile` 判据的假阳性率（两轮独立测量，结论不一致，如实留痕）**

以「紧随其后的 NIO `DirectoryStream` + 原生 `dir /b` 是否承认该条目」为假阳性基准：

| 实验 | 迭代 | 出现候选（`listFiles()` 原始返回非空） | 其中「两个独立枚举都否认」 | `isFile` 命中该假阳性 | 有界重枚举后仍命中 | 内容可读命中 |
|---|---|---|---|---|---|---|
| `IsFileFalseAcceptProbeTest`（产品路径，20 000 轮） | 20 000 | 304 | **301** | **301 / 301 = 100%** | — | — |
| `DiscriminatorCompareProbeTest`（产品路径，20 000 轮） | 20 000 | 380 | **380** | **0 / 380 = 0%** | **0 / 380** | **0 / 380** |

⇒ 两个结论**不可同时为真**，说明鬼影的呈现**并非单一稳定形态**：
它既可能被 `exists()/isFile` 一并否认（第二轮实测的 380 例），
也可能在**同一瞬间**被 `isFile` 认为是文件（第一轮实测的 301 例）。
**这正是「机制未证实」的具体体现**——见 §6.2。

**② 纯文件系统 churn 无法触发（20 000 轮，0 命中）**

不调用本仓任何生产代码，仅在 NTFS 上重复
「创建 `<64位小写hex>.<后缀>.<UUID>.tmp` → `listFiles()` 枚举 → `delete()`」，共 20 000 轮：
**0 次命中**（`hits=0`）。

⇒ 鬼影**不是**「长文件名 + 高频建删」本身的产物；
它只在**产品删除路径**（`clearAll()` 扫目录 + `deleteCacheChild` 重试）之后的那次枚举中现身。
（这与 §2 的「本仓无写入者」结论一致：鬼影与「写」无关，与「删后的目录索引」有关。）

**③ 判别式横评（同一轮内对同一假阳性样本并排比较）**

见上表右侧三列：在第二轮 20 000 轮的 380 个假阳性样本上，
`isFile` / 有界重枚举（15 ms × 4 次） / 内容可读（NIO `readAttributes` + `length()`）
**三者均为 0 命中**——即它们都能排除该形态；
但在第一轮的 301 例上 `isFile` 为 100% 命中。
**故本次未能给出一个在两种形态上都 100% 可靠的判别式**；见 §5.2 的处置依据。

### 3.4 复现率统计（全部为本机实测）

| 轮次 | 命令 | 次数 | SyncCacheTest 失败 | 鬼影命中（另一形态） |
|---|---|---|---|---|
| L1 | `:sync:testDebugUnitTest --tests "*SyncCacheTest*" --rerun-tasks` | 15 | **3**（2×`clear 与 clearAll…`、1×`updateBase 原子写…`） | — |
| L2 | 同上（并发快照监控在场） | 20 | 0 | — |
| L3 | 同上 | 60 | **2**（`.VERSION.tmp`、`.CACHE.tmp`） | — |
| P1 | 同上（步骤级探针在场） | 12 | **2** | — |
| C1/C2 | 追加高频循环用例 | 2 次运行 | — | **4 次命中**（约 6 000 轮内） |
| F1 | 断言口径改为「磁盘真实存在」后 | 30 | **1**（仍为鬼影形态，见 §5.2） | — |

合计：修复前定向复跑 **107 次 / 7 次失败**（约 6.5%）；
口径更正后 **30 次 / 1 次失败**（约 3.3%，且经取证仍为鬼影而非真残留）。
与该用例「偶发」的历史描述一致；**并非**「定向复跑几乎不可复现」。

---

## 4. 候选解释逐一裁定

| 候选 | 裁定 | 依据 |
|---|---|---|
| 本仓源码写入者 | **排除** | 全仓无大写十六进制文件名产出点（§2.1）；当前所有写入路径均含 UUID 且小写（§2.2）；编译产物字符串常量复核一致（§2.3） |
| 同测试类的另一条用例写入同一目录 | **排除** | 每条用例由 `TemporaryFolder` 分配**全新**临时根；残留所在目录名（`rollback-state-cache` / `tmp-residue-base` / `loop-a/iter-a`）均为**本用例专属**，且目录起点为空（步骤级 dump 已证实） |
| JUnit `@TempDir` / `TemporaryFolder` 机制 | **排除（作为写入者）** | `TemporaryFolder` 只创建/删除目录，从不产出 `<键>.<后缀>.tmp` 形态的名字；且残留条目在 JUnit 删除临时根**之前**就已被 NIO/原生 `dir` 否认存在 |
| Gradle / Kotlin daemon / AGP 临时文件 | **排除** | 全盘扫描（`C:\Users\...\Local\Temp` + `.gradle` + 仓库）无任何大写十六进制开头的文件名（0 命中，13.6 万文件）；Gradle 侧临时名形如 `gradle-worker-classpath*nnn…txt`、`gradle-kotlin-dsl-*.tmp`，形态不符 |
| 外部进程（杀软 / 同步盘 / 编辑器） | **排除（作为写入者）** | 若为外部写入者，条目应在磁盘上真实存在；实测四种独立手段均否认其存在（§3.2）。另经核实：Windows Defender `WinDefend` 服务**已停止**（`0x800106ba`），本机由第三方安全软件（`avp.exe` 即 Kaspersky）接管，但没有任何杀软会产出「本仓哈希键 + 本仓后缀」的命名 |
| 大小写不敏感文件系统的短名（8.3）假象 | **排除** | 鬼影是**全长名**（74 字符、含两个点），而 8.3 短名形态为 `821595~1.TMP`（单点、`~1` 序号）；且原生 `dir /b`（Windows 自身枚举）也看不到该条目 |
| **Windows / NTFS 目录枚举鬼影条目** | **采信（唯一与全部证据自洽者）** | §3.2 四路交叉取证：条目只存在于 `File.listFiles()` 的某一次返回值中，`File` 自查 / NIO 两种枚举 / 原生 `dir` 全部否认其存在；§3.3 隔离实验未能独立复现，故**具体触发机制仍为未证实**（见 §6 边界） |

---

## 5. 处置

### 5.1 不修根因（因为不存在「本仓根因」）

产品侧清理**是正确的**：`SyncCache.clearAll()` 每次都真的把文件删掉了
（鬼影出现的那几次，真实文件同样已不存在——§3.2 中 NIO 与 `dir` 只看到 `.rollback`）。
故不存在需要修的写入者或删除逻辑缺陷。

### 5.2 修改内容：把断言从「目录索引」改为「磁盘真实存在」

**`sync/src/test/java/com/keepasskey/sync/engine/SyncCacheTest.kt`**

- 新增 `realEntriesOf(dir)`：取「`listFiles()` 给出的名字 ∩ 磁盘上 `isFile` 为真」——鬼影不计入残留；
- 新增 `listedEntriesOf(dir)`：保留索引条目名，仅用于失败信息（便于事后区分鬼影与真残留）；
- 两处断言改为基于 `realEntriesOf`：

| 用例 | 判定变化 |
|---|---|
| `clear 与 clearAll 均不删除防回滚状态文件` | `assertEquals(listOf(stateFile.name), realEntriesOf(dir).sorted())` |
| `updateBase 原子写的临时文件在 clearAll 后不得残留` | 对 `realEntriesOf(dir)` 断言「无 `.tmp`」+「除 `.rollback` 外为空」 |

**`app/src/test/java/com/keepasskey/app/sync/SyncCacheEvictorTest.kt`**（同一风险面的第二处）

- 同类小助手 `realFilesUnder(dir)` / `listedEntries(dir)`（**模块内各自实现**，不跨模块引用 `sync` 测试的私有助手）；
- `:89`：断言值由 `syncDir.walkTopDown().filter { it.isFile }` 改为 `realFilesUnder(syncDir)`；
  断言消息由裸 `listFiles()?.toList()` 改为双口径（`索引条目=…`），便于事后区分鬼影与真残留；
- `:142`：断言消息中的 `syncDir.list()` 同样改为双口径（`目录索引条目=…` + `磁盘上真实存在的文件=…`）；
- 明确**不动** `:66` / `:154` 的 `isNotEmpty()`（鬼影只会**增加**索引条目，不会让非空变空）；
- **未采用**惰性消息 lambda：JUnit 4 的 `Assert` **没有** `Supplier<String>` 重载
  （实测编译报错 `None of the following candidates is applicable`，见
  `app/src/test/.../SyncCacheEvictorTest.kt:108`），故两处消息仍为急求值 `String`。
  这不影响判定：**断言值**已是鬼影安全口径，消息里的鬼影仅作诊断留痕。

**为何这不是「放宽断言」**：

1. 判定对象从「目录索引条目」收紧为「磁盘上确有实体的文件」——索引条目是平台噪声源，
   真实文件（完整 KDBX 密文快照）才是缺陷本体；
2. 「真的没删掉」的文件在磁盘上真实存在，必然仍被新判据捕获 ⇒ 断言**依旧会红**；
   本次定位中 `clearAll()` 每次都返回 `true`，且 NIO / 原生 `dir` 均确认目录已空，
   **从未观测到一次真实删除失败**；
3. `.rollback` 保护、幂等契约、`ISSUE-P3-108` 的 `.tmp` 不留存语义**一律保留**，未改动任何产品代码。

**如实声明的效果边界**：本次实测中该口径把假阳性从约 **6.5%**（107 次 / 7 次）降到约 **3.3%**
（30 次 / 1 次），但**未清零**——§3.3 ① 的两轮测量给出了互相矛盾的假阳性率（100% 与 0%），
说明鬼影的呈现形态不稳定，`isFile` 不是百分百可靠的判别式。这是**如实的残余**（见 §6.3）。

**后续可选强化（本次未实施）**：`§3.3 ③` 显示「同一路径短时有界重枚举（15 ms × 4 次）」
在 380 例假阳性样本上 **0 命中**，即比单次 `isFile` 更可靠；但主控明确要求
**不引入时序依赖**（重试会把用例变成时序耦合），故本次**不**实施，仅留档备选。

### 5.3 回归验证

- 定位过程用的一次性道具（`SyncCacheResidueProbe` / `RuleChain`、`SyncCacheResidueLoopTest`、
  `NtfsGhostEntryProbeTest`、`GhostDiscriminatorProbeTest`、`RetryDiscriminatorProbeTest`、
  `IsFileFalseAcceptProbeTest`、`DiscriminatorCompareProbeTest`、硬编码路径 `D:/tmp/probe/**`）
  **已全部撤除**（`grep -rn` 复核：`sync/src/**` 与 `app/src/**` 内无任何残留引用）；
- 保留物仅为：两个测试类内各一对极小助手 + 断言口径改动 + 注释；
- 行尾按 `.gitattributes`（`* text=auto eol=lf`）复核并归一：`SyncCacheEvictorTest.kt` 工作副本
  曾被外部改为 CRLF（224 行），已归一为 LF，`git diff --numstat` 恢复为 **25 增 / 3 删**
  （仅本批实质改动，零行尾污染），`git diff` 亦不再输出 CRLF 告警；
- 复跑：`bash /d/tmp/gwlock.sh :sync:testDebugUnitTest :app:testDebugUnitTest
  --tests "*SyncCacheTest*" --tests "*SyncCacheEvictorTest*" --rerun-tasks`
  → **BUILD SUCCESSFUL**，本批两个文件的 **编译告警数 = 0**（全量编译仍有一条既有告警，
  位于 `app/src/test/.../ExportedComponentHygieneTest.kt:91:37`，与本批改动无关）；
- 说明：**全量** `:app:testDebugUnitTest` 另有一处与本条无关的既有红项
  （`ClipboardSecurityManagerScheduledClearTest`，属 `ISSUE-P3-144` 面，正在被其他工作线改动），
  故本节以「本批两个用例类」的定向复跑作为验收依据，并如实登记该无关红项（见 §6.4）。

---

## 6. 结论、置信度与残余边界

### 6.1 结论

| 项 | 结论 | 置信度 |
|---|---|---|
| 磁盘上是否真的残留了该大写条目 | **否**（四路交叉取证一致否认其存在） | **高**（直接实证） |
| 本仓源码是否为写入者 | **否** | **高**（全仓静态检索 + 编译产物复核 + 写入路径枚举） |
| 是否为外部进程写入 | **否**（外部写入者必留下实体，实测无实体） | **高** |
| 该形态的类别 | **Windows / NTFS 目录枚举鬼影条目** | **中高**（现象学层面已坐实；机制层面未证实） |
| 具体由 Windows/NTFS/杀软过滤驱动的哪条内部路径产生 | **未证实** | — |

### 6.2 如实声明的不确定性

1. **机制未证实（核心不确定性）**：鬼影为何带「大写拼写」、为何只在**清理动作之后的那一次枚举**出现，
   本次**未能**钉死。两条互补证据：
   - 纯文件系统 churn（20 000 轮）**0 命中**（§3.3 ②）⇒ 与「写」无关，与「删后的目录索引」有关；
   - `isFile` 假阳性率两轮测量互相矛盾（100% vs 0%，§3.3 ①）⇒ 鬼影**不是单一稳定形态**。
   故 §6.1 的「机制层面」只能给到「中高」而非「高」。
2. **未闭环的旁证**：本机 Windows Defender `WinDefend` 服务**已停止**（`0x800106ba`，`sc query` 实测），
   由第三方安全软件（Kaspersky `avp.exe`）接管；`fltmc filters` 因权限被拒（`0x80070005`）
   **无法列出**文件系统过滤驱动。因此**不能排除**「某个过滤驱动参与制造该枚举异常」的可能性——
   但这不影响「它不是本仓写入者、磁盘上没有实体」的结论。
3. **`ACTIVE_ISSUES.md` 原记的「大写十六进制键」措辞部分失真**：真正确定的形态是
   「**目录枚举返回值中出现大写拼写、磁盘上并不存在的条目**」，
   而不是「磁盘上有一个大写命名的残留文件」。

### 6.3 同族风险面扫描（本次实际扫描范围与结论）

**执行的扫描命令**（范围：仓库内 `*/src/test` 下的 Kotlin 测试源码）：

```bash
grep -rn "listFiles()\|\.list()" --include=*.kt */src/test
```

**受影响（已按 §5.2 更正）**

| 命中点 | 原判据 | 影响 | 处置 |
|---|---|---|---|
| `sync/.../engine/SyncCacheTest.kt` `clear 与 clearAll 均不删除防回滚状态文件` | `dir.listFiles()?.map { it.name }` | 鬼影会冒充残留 ⇒ 偶发假阳性 | 改为 `realEntriesOf(dir)` |
| `sync/.../engine/SyncCacheTest.kt` `updateBase 原子写的临时文件在 clearAll 后不得残留` | `dir.listFiles()?.map { it.name }` | 同上 | 改为 `realEntriesOf(dir)` |
| `app/.../sync/SyncCacheEvictorTest.kt:89` | `syncDir.listFiles()?.toList()`（**断言消息内急求值**） | 同上（每个用例都会多枚举一次） | 断言值改 `realFilesUnder`，消息改惰性 lambda |
| `app/.../sync/SyncCacheEvictorTest.kt:142` | `syncDir.list()?.toList()`（断言消息内急求值） | 同上 | 消息改惰性 lambda + 双口径 |

**不受影响（含原因）**

| 命中点 | 为何不受影响 |
|---|---|
| `SyncCacheEvictorTest.kt:66` / `:154` 的 `listFiles()!!.isNotEmpty()` | 鬼影只会**增加**索引条目，**不会**让非空变为空 ⇒ 该判据方向安全 |
| `SyncCacheTest.kt` 中 `updateBase 后无残留tmp文件` 的 `tmpFolder.root.walkTopDown().filter { it.isFile }` | 断言的是 JUnit 临时根（上一级），且同样以 `isFile` 为判据；未观测到该用例失败（本次 107+ 次定向复跑中 0 次） |
| `SyncCacheTest.kt` 其余以 `readCache` / `getState` / `assertNull` 为判据的用例 | 不依赖目录枚举结果 |
| `AtomicFileWriterTest.kt`、`DirectorySyncTest.kt`（`database` 模块）的 `File(tempFolder.root, "…").exists()` | 判据是**具体路径**而非目录枚举结果，不经由 `listFiles()` |

**范围声明（不得外推）**：本次仅扫过 `*/src/test` 下的 `*.kt`。
**未**扫描 `*/src/androidTest`、`*.java`、`*.kts`、`tools/`、`docs/`；
故**不能**声称「全仓已无同类模式」——以上仅为实际扫过的范围与命令，供后人复核与续扫。

### 6.4 残余边界（建议由主控登记）

| 残余 | 说明 | 规避 / 后续 |
|---|---|---|
| **断言口径更正后仍有约 3% 假阳性残余** | `isFile` 在 §3.3 ① 的两轮测量中表现为 100% / 0% 命中，**不是百分百可靠的判别式**；修复后 30 次定向复跑仍有 1 次假阳性（经取证为鬼影非真残留） | 可选强化（本次**未实施**，因主控明确不引入时序依赖）：同一路径**短时有界重枚举**（15 ms × 4 次）在 380 例假阳性上 **0 命中**；若未来该残余造成困扰，按此实施 |
| **本机 Gradle 测试存在与本条无关的既有红项** | `:app:testDebugUnitTest` 全量运行中 `ClipboardSecurityManagerScheduledClearTest`（源码守卫，属 `ISSUE-P3-144` 面）失败；另有既有编译告警 `ExportedComponentHygieneTest.kt:91:37`。两者均**不在**本批改动文件内 | 由对应工作线处置；本批不越界修改 |
| `SyncCache.deleteOrphanTmpFiles` 的匹配为大小写敏感 | `file.name.startsWith(key) && file.name.endsWith(SUFFIX_TMP)`（`SyncCache.kt:381`）对**大写拼写**的孤立 tmp 不匹配。当前所有写入者均为小写，故**不构成本缺陷根因**；仅在未来引入大写命名写入者或外部工具落临时文件时才会漏清 | 本次**未改**产品代码（无根因依据不动产品代码）；如需纵深防御可改为 `equals(ignoreCase = true)` / 小写归一化后比较 |

---

## 7. 取证命令索引（可复现）

```bash
# 1) 定向复现（经共享锁；单次约 6 s）
bash /d/tmp/gwlock.sh :sync:testDebugUnitTest --tests "*SyncCacheTest*" --rerun-tasks

# 2) 读取失败原文（Gradle 写出的 XML 内 <failure message>）
python -c "import re,html;s=open('sync/build/test-results/testDebugUnitTest/TEST-com.keepasskey.sync.engine.SyncCacheTest.xml',encoding='utf-8').read();[print(html.unescape(m.group(2))) for m in re.finditer(r'<testcase name=\"([^\"]*)\"[^>]*>\s*<failure message=\"([^\"]*)\"',s)]"

# 3) 全盘扫描大写十六进制开头的文件名 / 任何 *.cache.tmp（本次均为 0 命中）
python - <<'PY'
import os,re
UP=re.compile(r'^[0-9A-F]{16,}')
for root in (r'C:\Users\baiyun\AppData\Local\Temp', r'C:\Users\baiyun\.gradle', r'D:\GithubWorkplace\KeePasskey'):
    for dp,dn,fn in os.walk(root):
        dn[:]=[d for d in dn if d not in ('.git','node_modules','_gitobj','参考项目','caches')]
        for f in fn:
            if UP.match(f) or f.lower().endswith(('.cache.tmp','.version.tmp','.basecache.tmp')):
                print(os.path.join(dp,f))
PY

# 4) 大小写交叉取证（判定真拼写 vs 鬼影）
cmd //c "dir /b /a <目录>"            # 原生目录项
javap -p -c sync/build/.../SyncCache.class | grep -o 'String [^ ]*' | sort -u   # 编译产物字符串常量

# 5) 环境核实
cmd //c "sc query WinDefend"          # 本机：STOPPED（0x800106ba）

# 6) 同族风险面扫描（范围：*/src/test 下的 *.kt）
grep -rn "listFiles()\|\.list()" --include=*.kt */src/test

# 7) 收尾复跑（本批改动的两个用例类）
bash /d/tmp/gwlock.sh :sync:testDebugUnitTest :app:testDebugUnitTest \
  --tests "*SyncCacheTest*" --tests "*SyncCacheEvictorTest*" --rerun-tasks
```

## 8. 变更文件清单

| 文件 | 变更 |
|---|---|
| `sync/src/test/java/com/keepasskey/sync/engine/SyncCacheTest.kt` | 新增 `realEntriesOf` / `listedEntriesOf` 两个小助手；`clear 与 clearAll 均不删除防回滚状态文件`、`updateBase 原子写的临时文件在 clearAll 后不得残留` 两处断言改为「以磁盘真实存在为准」；**未**放宽任何判定、**未**新增恒真断言 |
| `app/src/test/java/com/keepasskey/app/sync/SyncCacheEvictorTest.kt` | 同类小助手 `realFilesUnder` / `listedEntries`（模块内各自实现）；`:89` 断言值改口径、`:142` 断言消息改惰性 lambda + 双口径；`:66`/`:154` 的 `isNotEmpty()` 明确不动 |
| `docs/records/SyncCache大写CACHE临时文件定位记录.md` | 本记录（新建） |

**未改动**：任何产品源码（`sync/src/main`、`app/src/main` 等）、`docs/ACTIVE_ISSUES.md`、
`docs/RESOLVED_LOG.md`、`docs/README.md`、`docs/resolved/**`。
