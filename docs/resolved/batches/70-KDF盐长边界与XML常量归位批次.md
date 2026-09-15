<a id="s70"></a>
## §70 KDF 盐长边界与 XML 常量归位批次（2026-09-15）：ISSUE-P3-78 / ISSUE-P3-80

> **本批次缘起**：两项均为 §38 批次落地时**主动留痕**的低危项，此后一直在表内——
> `ISSUE-P3-78` 是「同类 fail-closed 硬化」（Argon2 `S` 长度未按官方上下界校验），
> `ISSUE-P3-80` 是「常量归位 / 代码整洁」（`Compressed` 属性名散落在实现内私有常量）。
> 两者互不依赖，同批闭环。

### 70.1 交付清单

| 编号 | 缺陷（一句话） | 关键改动 | 依据（**直读核实**） |
|---|---|---|---|
| `ISSUE-P3-78` | 解码侧**完全不校验** Argon2 盐长 ⇒ 会接受官方拒绝的退化盐（如 0 字节） | `KdbxKdfParameterCodec` 新增 `ARGON2_MIN_SALT_BYTES = 8` / `ARGON2_MAX_SALT_BYTES = 0x3FFFFFFF` 与 `validateArgon2SaltBounds()`；接线到 Argon2 解码分支（取到 `S` 后**立即**校验，越界抛 `KdbxCorruptFileException`） | 官方 `参考项目/KeePass-2.61.1-Source/KeePassLib/Cryptography/KeyDerivation/Argon2Kdf.cs`：`:57 MinSalt = 8`、`:58 MaxSalt = 0x3FFFFFFF`、`:143-144` 越界即抛 `ArgumentOutOfRangeException`（2026-09-15 定点直读该文件核实，非转述） |
| `ISSUE-P3-80` | `Compressed` 属性名以实现内私有常量（`KdbxXmlBinaryNode.ATTR_COMPRESSED`）就近承载，与「XML 节点 / 属性名统一收敛于 `KdbxConstants.Xml`」的既有约定不一致 | `KdbxConstants.Xml.COMPRESSED` 上收（**取值不变**，仍为 `"Compressed"`）；`KdbxXmlBinaryNode` 删除私有常量并改引用；既有守卫用例 `KdbxBinaryNodeValueFormTest` 改断言新常量 | 官方 `KdbxFile.cs:194 AttrCompressed = "Compressed"`（该取值由既有守卫用例持续锁定） |

### 70.2 边界与如实声明

1. **`P3-78` 属接受域一致性修复，非可利用缺陷**：`S` 的最大长度早由变体字典值长度上限（1 MiB）
   间接约束，**无内存风险**；本次仅把「本仓接受域」对齐到官方。
2. **上界实际不可达**：`MaxSalt = 0x3FFFFFFF`（约 1 GiB）远超变体字典上限，保留官方值**仅为对齐**，
   不构成任何真实约束。
3. **本批不扩大范围到 AES-KDF 的 `S`**：本条目只针对 Argon2；AES-KDF 维度官方无对应盐长上下界，
   且 `S` 已受同一变体字典上限约束——**不得**据此推断 AES-KDF 已做同类校验。
4. **`P3-80` 为零行为变更**：纯常量搬迁；`KdbxXmlBinaryNode` 保留的 companion 中其余常量
   （如 `INLINE_REF_INDEX`）未动，既有不变量 KDoc 保持不变。

### 70.3 验证证据（2026-09-15）

- **新增回归** `database/src/test/java/com/keepasskey/database/file/Argon2KdfSaltBoundsTest.kt`（**4 例**）：
  官方下界 `len=8` 通过 / `len=7` 拒绝 / `len=0`（退化盐）拒绝 / 本仓 32 字节盐通过（防误拒）。
  该用例走「**写侧序列化 → 读侧反序列化**」的真实字节流，而**不是**直调校验函数——
  防止「加了校验却没接线」的假绿。
- **全量单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL in 2m 1s`，
  **1774 例 / 0 失败 / 0 错误 / 13 跳过**
  （app 980 / core 68 / crypto 131 / database **392**（较 §68 基线 +4）/ sync 203；13 例跳过全在 sync）。
- **发布产物**：`.\gradlew.bat assembleRelease --rerun-tasks` → `BUILD SUCCESSFUL in 3m 30s`
  （全量日志 263 行，`w:` 命中数 **0**），产物
  `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`
  （**15 482 951 字节**，2026-09-15 19:46 构建；已配置 release 签名，故非 `-unsigned`）。

### 70.4 过程缺陷（如实留痕）

1. **「留痕待办」的滞留成本**：两项自 §38 批次（2026-09-13）即登记在表内，
   实质改动量分别约为「两个常量 + 一个校验函数」与「一个常量搬迁」，却滞留两个批次才被认领。
   ⇒ 纪律：**登记时若已明确改动面与判据，应同时评估是否当场实施**；
   仅在「会扩大拒绝面 / 当轮无法验证」等真实理由下才留作待办（`P3-78` 当时的留痕理由正是前者，
   本轮已具备全量验证条件，理由消失即应动手）。
2. **守卫用例的断言对象随常量搬迁而更新**：`KdbxBinaryNodeValueFormTest` 原断言
   `BinaryNode.ATTR_COMPRESSED == "Compressed"`，常量上收后改为断言 `KdbxConstants.Xml.COMPRESSED`
   ——**断言强度未降低**（仍锁定取值 == 官方拼写），只是唯一权威定义点随之迁移。
