<a id="s306"></a>

# §306 导出侧 CharArray 写出通道批次

> `ISSUE-P3-303` **整条闭环**（P3 9 → **8 项**）。
> 覆盖面：`ISSUE-P3-303` 第 1 类（导出侧 `readString()` **可收口**面；第 2 类模型层 `String` 不在本条，已由 §284 登记限界 §2.7）。
> 生产改动＝三处 `readString()` 改走 `useChars` + **新增 CharArray 写出通道**；契约 §4 #19 由「覆盖限界」改记为「**已收口**」；
> 新增守卫 2 处（导出器零 `readString()` 静态锁 + 双通道**逐字节等价**行为锁）。

---

## 1. 条目正文（原样收录）

### `ISSUE-P3-303`：不可擦 `String` 明文残留分层——导出侧 `readString()` 可收口，模型层字段 `String` 不可在导出器层解决

- **核实时间点**：2026-09-23（§278 整改当日，就「不可擦 `String` 能不能解决」命题逐层核对模型与写出口）。
- **核实方式**：
  1. **导出侧 `readString()`（可解）**：`KdbxCsvExporter.kt:76` 的 `entry.password?.readString().orEmpty()`、`KeePassXmlExporter.kt:75/91` 的 `password.readString()` / `field.value.readString()` 三处，物化 JVM `String` 后驻留至 GC。`ProtectedString` 已提供 `useChars` 闭包（`ProtectedString.kt:132-139`，`CharArray` 用毕自动 `fill`）；写出口现状只收 `String`——`KdbxCsvExporter.writeField(writer, value: String)`、`KdbxXmlWriteUtil.textElement(..., text: String)`。⇒ **技术上可解**：加 `CharArray` 写出口（含 XML 转义 / CSV RFC 4180 引号化的 `CharArray` 版），三处改走 `useChars`，产物字节不变。
  2. **模型层字段 `String`（导出器层不可解）**：`KdbxEntry` 的 `title` / `userName` / `url` / `notes`（`KdbxEntry.kt:28-40`）与 `KdbxGroup.name` / `notes`（`KdbxGroup.kt:10`）在**模型上就是 `String`**，导出器只是读取既有 `String`——`readString()` 只出现在 `ProtectedString` 字段（口令、自定义字段值）。清这批须改模型层形态（`ProtectedString` / `CharArray`），牵动映射 / 合并 / 比较 / UI / 序列化全链，**不在导出器层可解**；其性质与限界 §2.4（Compose 文本状态不可擦 `String`）/ RC-01 同族。
- **背景与根因**：§278 按 AC② 二选一取了「登记」分支（限界已接受 + 可收敛点是一致性），把「改 `useChars`」留作摘除条件未实施。用户 2026-09-23 追问「不可擦 `String` 能不能解决」后逐层核实：**一半能、一半不能**，须分开登记，避免「已登记限界」被读作「两类都动不了」或「改 `useChars` 就全干净了」。
- **涉及文件**（仅第 1 类）：`database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt`、`database/src/main/java/com/keepasskey/database/xml/KeePassXmlExporter.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlWriteUtil.kt`（`textElement` 增 `CharArray` 出口）；契约 [`敏感缓冲所有权契约.md`](../../architecture/敏感缓冲所有权契约.md) §4 #19。
- **验收标准**：
  - **第 1 类（导出侧 `readString()`，可解，本条主整改面）**：AC① 三处 `readString()` 改走 `useChars` + `CharArray` 写出口（`writeField` / `textElement` 须有 `CharArray` 重载或等价路径，XML 转义与 CSV 引号语义逐字节等价）；AC② 产物字节与改前**逐字节等价**（`KdbxCsvExporterTest` 4 例 + XML 既有产物断言原样通过，必要时补对照例）；AC③ 完成后**摘除契约 #19**（或就地改写为「已收口」并留痕），禁「代码已改、契约仍记残留」；AC④ 新增守卫锁定「导出器不得再出现 `readString()`」（静态接线，口径同 `WipableByteArrayOutputStreamTest`）。
  - **第 2 类（模型层字段 `String`，导出器层不可解）**：AC⑤ **不在本条整改**——按限界口径处置：若维持现状，须在限界表登记（或扩写 §2.4 同族）并写明「模型层 `title`/`url`/`userName`/`notes`/分组名以 `String` 驻留，进程内取证在信任边界外」；若要收口，须**另立条目**评估模型层改造（`ProtectedString` 化或 `CharArray` 字段）的牵动面，禁在本条顺手改模型。
    **（2026-09-23 §284 补全登记）**：AC⑤ 的「维持现状 + 限界登记」分支已落地——限界表新增 **§2.7**，单列**展示层**（`GeneratorScreen` `readString()` / `EntryDetailSecrets` `toDisplayString()` 与三个 `String` 状态）与**模型层**（`KdbxEntry` / `KdbxGroup` 元数据字段）两类驻留，并与 §2.4 / §2.6 分列；解除条件仍须另立条目。第 1 类（导出侧 `useChars`）**仍未实施**，本条继续开放。
  - **通则**：AC⑥ 两类的结论与去向必须**分列写明**，禁以「不可擦 `String` 都是已接受限界」一句带过（第 1 类恰恰**可以**收口）。

**开工前前提复核（2026-09-24）**：成立。① 三处 `readString()` 确在（CSV 口令 1 处、XML 口令 1 处、XML 自定义字段值 1 处）；② `ProtectedString.useChars` 已存在（`CharArray` 用毕 `fill('0')`）；③ 写出口确只收 `String`（`writeField` / `textElement` / `KdbxXmlStreamWriter.text`），需新增 `CharArray` 重载；④ 第 2 类已由 §284 在限界 §2.7 登记，**本条不触碰模型层**。

---

## 2. 整改

### 2.1 新增 CharArray 写出通道（AC①）

| 层 | 新增 | 说明 |
|---|---|---|
| 流写入器 | `KdbxXmlStreamWriter.text(value: CharArray)` | 走同分支表的转义写出 |
| 转义内核 | `KdbxXmlStreamWriter.escape(value: CharArray, escapeNewLines)` | **刻意逐字节复刻** String 版，见下 |
| 元素助手 | `KdbxXmlWriteUtil.textElement(writer, tag, text: CharArray)` | 与 String 重载逐字节等价 |
| CSV | `KdbxCsvExporter.writeField(writer, value: CharArray)` | RFC 4180 引号化 / 双写转义同规则 |

**为何 `escape` 不抽公共内核**：该函数位于**保存热路径**（大库每个文本节点都过），`ISSUE-P3-151` 正是把逐字符
`write(int)` 改成「无需转义区间批量写出」。抽公共内核需传 `codePointAt` / 写出区间两个 lambda ⇒ 每次调用
各分配一次闭包，与 §151 的方向相反。故两版并存并**互相声明需同步维护**（KDoc 已写明），
由 §3.2 的双通道等价用例把「同步」变成机检。

### 2.2 三处调用点（AC①）

- `KdbxCsvExporter.writeEntry`：由「组 `List<String>` 再 `writeRow`」改为**逐字段写出**，口令经
  `entry.password?.useChars { writeField(writer, it) }`；字段顺序、分隔符位置与引号语义逐字节等价，
  口令缺失时仍写出空字段（与改前 `?.readString().orEmpty()` 同义）。
- `KeePassXmlExporter`：口令与自定义字段值两处改 `useChars { textElement(writer, "Value", it) }`。

### 2.3 契约 #19 收口（AC③）

[`敏感缓冲所有权契约.md`](../../architecture/敏感缓冲所有权契约.md) §4 #19 由「**覆盖限界（登记，不另立用例）**」
改写为「**已收口（2026-09-24 `ISSUE-P3-303` / §306）**」，并就地写明：三处已改走 `useChars` + CharArray 通道、
产物逐字节等价、守卫位置；同时**明确模型层 `title`/`url`/`userName`/`notes` 不在此列**（分层判据见限界 §2.7）——
避免「已收口」被读作「不可擦 `String` 全清了」。

---

## 3. 验证

### 3.1 静态守卫（AC④）：`ExportWritePathHygieneTest.导出器不得再物化不可擦 String`

两个导出器源码（剔注释后）**不得**出现 `readString()`，且**必须**出现 `useChars`；
反空转正控制：同一次扫描必须在 `KdbxXmlWriteUtil` / `KdbxXmlStreamWriter` 中看到两个 CharArray 重载，
否则「零命中」不具判别力。

### 3.2 行为守卫（AC①②）：`database/src/test/java/com/keepasskey/database/xml/ExportTextWriteChannelParityTest.kt`（2 例）

1. `字符串与字符数组两条写出通道逐字节等价`——8 组样本（`&<>"`、CRLF、TAB、中文 + emoji、
   非法控制字符 U+0001、空串、单引号）逐组 `assertArrayEquals`；
2. `转义与非法码点剔除在字符数组通道同样生效`——**正控制**：证明第 1 例的「等价」不是
   「两条都没转义」造成的假等价（分别断言 `&amp;` / `&lt;` / `&gt;` / `&#xD;`、剔除 U+0001、emoji 代理对完整）。

### 3.3 既有产物断言（AC②）

`KdbxCsvExporterTest`（含逐字节整行断言，覆盖口令字段）与 `:database:test` 全量**原样通过**——
即产物字节未变。

### 3.4 全量回归与机检

- `.\gradlew.bat test --max-workers=1` → **BUILD SUCCESSFUL**；
- 聚合计数：见 §4 补记（`xml=398 tests=2693`）。
- `check_md_links.py` / `check_resolved_index_sync.py` / `check_bounded_type_names.py` /
  `check_tautological_assertions.py` / `check_recheck_consistency.py` → EXIT 0；
  `count_line_tiers.py` / `long_functions.py` 仍 EXIT 1（**既存**，见 `ISSUE-P3-305`）。

---

## 4. 如实声明

1. **只收口第 1 类**：模型层 `title` / `url` / `userName` / `notes` / 分组名的 `String` 驻留**未动**
   （AC⑤ 明确不在本条；限界 §2.7 已登记）。**不得**把本条读作「不可擦 `String` 已全部解决」。
2. **`escape` 双版并存是刻意取舍**（热路径不引 lambda），以双通道等价用例替代「抽公共内核」的实现层去重。
3. **未跑**：`lint` / `assembleRelease` / KPEX 对拍（未触 passkey schema）/ `.kdbx` 语料校验（未改 KDBX 格式语义）。
4. **`hygiene-gate` 两条仍为红**（`tier1=4` / `functions_ge_100=2`），属 `ISSUE-P3-305` 范围，本批未触碰。
5. **无设备侧必跑项**：未触 `*/src/androidTest/**`、未改原生内核。
