# §163 条目 TOTP 面下沉批次（`ISSUE-P3-188` 第二档：`VaultEntryMapper` 490 → 385 行）

> **起因**：`ISSUE-P3-188` 剩余清单第 3 项头部文件之一 `data/repository/VaultEntryMapper.kt`（490 行）。
> **本批未改任何生产逻辑**：TOTP 三者是**无状态纯函数**，整族搬出后母文件只留三行委托。

---

## 1. 切分依据与形态

| 成员 | 依赖 | 处置 |
|---|---|---|
| `parseTotpConfig`（字段 / 自定义字段来源定位 + URI 解析） | `KdbxConstants.Fields.OTP`、`VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX`、`TotpKeyUriParser` | 移入新对象，母文件留一行委托（**公开 API 不变**，`VaultEntrySecretReader` 调用点无需改） |
| `computeTotpCode`（按时刻取码） | `Base32Decoder` / `OtpEngine`，取码时刻由入参给出（ISSUE-P2-90） | 同上（含默认参数） |
| `projectTotpFields` + 其投影类型 | 上面两者 + `OtpEngine.getRemainingSeconds` | 移入新对象；`private data class TotpProjection` → `VaultEntryTotpMapping.Projection`（同模块 `internal` 可见） |

新增 `data/repository/VaultEntryTotpMapping.kt`（128 行，`internal object`）；
母文件 **490 → 385 行**，两文件均低于 400 阈值；三个 `DEFAULT_TOTP_*` 常量随被搬代码同迁
（它们在母文件内**仅**被这一段使用，已核对）。

## 2. 安全语义随代码同迁、未放宽

TASK-46 的字节态与擦除约束原样保留：种子 `config.secret` 与 `Base32Decoder` 的解码产物 `secretBytes`
**在成功 / 失败 / 早退三条路径上均由 `finally` 显式 `fill(0)`**，全程不还原为 `String`；
`projectTotpFields` 内对 `parsedTotp.secret` 的擦除同样未动。⇒ 本批**不**改变秘密治理面，
只改变它所在的文件（`docs/architecture/已知工程限界.md` §1.6 的「树外可达性」清单**不受影响**：
搬运前后被搬代码接触的明文种类与生命周期完全一致）。

## 3. 过程留痕（三处编译期暴露，均未入库）

1. **新对象里的投影类型漏改可见性**：`private data class TotpProjection` 被搬走后，母文件的委托
   签名无法访问它（`Cannot access 'Projection': it is private`）；改为对象成员默认（`internal` 对象内可见）。
2. **新文件 import 只写了猜测的三条** ⇒ 一串 `Unresolved reference`；改为**沿用母文件 import 全集**
   再交给编译器与剪枝脚本收敛（母文件 −4、新文件 −17）。
3. **伴生常量随代码搬家后要显式限定**：`TOTP_CUSTOM_FIELD_PREFIX` 仍留在母文件伴生对象
   （它是公开 `const`、可能被别处引用，刻意**不**跟着搬），新对象改以 `VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX` 引用。
   ⇒ 教训：**搬代码前先分类「只有这一段用的常量」与「对外契约常量」**，前者同迁、后者留驻并限定访问。

`AttachmentDataOwnershipTest` 对 `VaultEntryMapper` 的引用是**运行时构造**（非源码文本扫描），
故无需改其清单；`AlgoHotPathGuardsTest` 未把本文件列为扫描目标，也无需扩清单。

## 4. 验证

| 层 | 命令 | 结果 |
|---|---|---|
| 定向（受影响面） | `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --max-workers=1 --tests "*VaultEntryMapper*Test*" --tests "*TotpCodeCacheTest*" --tests "*Totp*Test*" --tests "*AttachmentDataOwnershipTest*"` | **全绿**（TOTP 取码与缓存、附件数据所有权均按原语料断言） |
| 宿主单测全量 | `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` | **`BUILD SUCCESSFUL`，114 actionable tasks: 114 executed**，`tests=2194 failures=0 errors=0 skipped=13`、`uncaught_files=0` |
| Lint | `.\gradlew.bat :app:lintDebug` | **0 error**、`:app:` `<issue>` **216 条**（与 §153 ~ §162 基线持平） |

## 5. 第二档现状（本批后重测）

- 口径：五模块 `src/main` 全部 `.kt` 逐文件 `wc -l`；
- **400 ~ 500 行：38 个**（§161 测得 40、§162 后 39、本批后 38）；头部：
  `RealVaultRepository` 489、`VaultRepository` 478、`AutofillConfirmActivity` 475、`EntryEditScreen` 472、
  `RuntimeIntegrityDetector` 470、`SyncConflictController` 461、`KdbxHeader` 461、`KdbxXmlParser` 454、
  第一档余量 `EntryDetailViewModel` 435 / `EntryDetailScreen` 426；
- `RealVaultRepository` / `VaultRepository` 一族**刻意排在后面**：它们的内聚块多与整树读写和擦除边界相关，
  拆动会触及限界 §1.6 的可达性穷举，属需要单独论证的一段；`.kdbx` 格式面同理须过对拍回归。
