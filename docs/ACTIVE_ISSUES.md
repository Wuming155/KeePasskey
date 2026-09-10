# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|---|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级与致命安全漏洞（0 项）

> 当前无待办。

---

## P1 高危与核心功能问题（0 项）

> 当前 P1 级别无待办。历史 P1 项（含 ISSUE-P1-10 / ZT-10）已全部闭环，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.5。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> ISSUE-P2-05 ~ ISSUE-P2-13 九项已全部整改并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.16 ~ §2.20。
> P2-12 整改中如实登记的残余面 ISSUE-P2-15 / ISSUE-P2-16（受保护值经 String 退化、
> 密码生成引擎出边界仍返回 String）已整改并归档，见 §2.21。
> 当前 P2 级别无待办。

---

## P3 低危问题、特性接线与体验优化（10 项）

> **背景**：原 P3 批次 16 项（ISSUE-P3-01 ~ ISSUE-P3-16）已于 2026-09-10 整体整改完成并归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§3「P3 批次整改归档」**（含逐项裁决、24 个新增生产文件、
> 13 条过程缺陷与事实修正留痕、921 例测试门禁证据）。
> 其中**未完全达成验收标准**的残余面已按「严禁只记聊天或脑中」纪律**全部回登为本节条目**（P3-17 ~ P3-26）。

---

### ISSUE-P3-17 (P3-03 残余): 43c UI 显示偏好接线（7 键）

- **优先级**：P3（功能完整性 / 拒绝假开关）
- **分类**：设置消费 / UI 偏好
- **背景与现象**：
  TASK-12 已完成下列 7 个 UI 偏好的持久化，但**消费方至今未接线**（接线前经全仓 grep 核实：仅有设置页自身回显，
  无任何真实消费点）。ISSUE-P3-03 整改时因消费方全部落在**其他并行工作组的文件范围**内而一行未改，
  仅在设置页补齐「（预留，暂未生效）」中英双语诚实标识——**该标识本身即「尚未接线」的用户可见凭据**。
- **待接线的键与落点**（ISSUE-P3-03 交接文档 §四 R-0 已给出可直接执行的配方）：

  | 键名 | 应落文件 |
  |---|---|
  | `maskPasswordsDefault` | `app/src/main/java/com/keepasskey/app/ui/screens/detail/**` |
  | `maskTotpDefault` | 同上 |
  | `showGroupInEntry` | 同上 |
  | `listDensity` | `app/src/main/java/com/keepasskey/app/ui/screens/vault/**` |
  | `autoActivateSearchOnOpen` | 同上 |
  | `showGroupInSearchResult` | 同上 |
  | `showKillAppOption` | `app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt` |

- **整改依据**：各功能预留开关设计；避免「假开关」。参考既有正确接线范式：
  `VaultListViewModel.kt:331-335`（`SettingsRepository` 的 `showUsernameInList` / `showOtpInList` / `hideFabOnScroll`
  → `VaultListUiState` → `VaultListScreen` 消费）。
- **⚠️ 语义红线（务必遵守）**：`maskPasswordsDefault` / `maskTotpDefault` 的语义是「**默认值**」而**非**「强制覆盖」——
  实现为字段的**初始**遮掩态，**不得**覆盖用户本次会话的显式展开/收起操作。误实现为强制覆盖属功能回归。
- **验收标准**：
  1. 7 键各自具备真实消费点，且在 UI 上可观察生效（非仅状态字段赋值）；
  2. **同步移除设置页对应开关的「（预留，暂未生效）」标识**——否则 UI 会反过来低报已生效功能，属反向的不诚实；
  3. 每个键补对应单元测试（把决策抽为可测纯函数或断言 UiState 映射，避免仅靠 UI 交互验证）；
  4. `.\gradlew.bat test` 全绿且用例数不低于本批基线（921 例）。

---

### ISSUE-P3-18 (P3-03 残余): 通知基础设施与 2 个通知类偏好接线

- **优先级**：P3（特性接线）
- **分类**：通知 / 设置消费
- **背景与现象**：
  `showUnlockedNotification`（解锁后显示通知）与 `autofillShowTotpNotification`（自动填充显示 TOTP 通知）
  两个偏好已完成持久化，但**全仓不存在任何通知基础设施**（`app/src/main/java` 内零 `NotificationChannel` /
  `NotificationManagerCompat`），且 manifest 未声明 `POST_NOTIFICATIONS`。ISSUE-P3-03 整改时如实登记为未接线，
  并在设置页补齐诚实标识。
- **整改依据**：Android 13+ 通知权限模型；拒绝「假开关」。
- **验收标准**：
  1. 建立通知通道（`NotificationChannel`，Android 8+ 必需）并声明 `POST_NOTIFICATIONS` 权限；
  2. 运行时权限请求流程（用户拒绝时**不得**崩溃或反复弹窗）；
  3. `showUnlockedNotification` 与 `autofillShowTotpNotification` 各自真实控制对应通知的发送；
  4. **通知内容严禁出现敏感明文**（键名/条目名亦需评估，遵循 `.codebuddy/rules/engineering-rules.md`「防御性安全边界」）；
  5. 同步移除设置页这两个开关的「（预留，暂未生效）」标识；
  6. 补单元测试（通道建立、权限缺失时的降级、偏好关闭时不发送）。

---

### ISSUE-P3-19 (P3-03 残余): 明文导入框架与 4 源解析器

- **优先级**：P3（功能缺口）
- **分类**：数据导入 / 解析器
- **背景与现象**：
  设置页「导入数据源」提供 4 个选项（1PUX / Bitwarden / KeePass XML / 浏览器 CSV），但**没有任何解析实现**：
  选中后仅执行 `operationFeedback = UiMessage(R.string.dbset_import_preparing, listOf(source))`
  （提示「正在解析 X 数据...」），属**假回执**。ISSUE-P3-03 整改时未硬凑 5 个解析器，已把该提示改为
  `dbset_import_reserved_note` 诚实说明（「解析器预留，暂未生效」）。
- **口径修正（如实登记）**：ISSUE-P3-03 原文称「5 源码导入解析器」，实际 UI 只有 **4 个选项**；
  归档时已按 4 源登记。
- **整改依据**：既有 TASK-36「假数据/假回执如实化」先例；参考 keepass2android / KeePassDX 的导入架构分析（`docs/references/`）。
- **拆分建议（每源一个子批，按工作量与依赖排序）**：
  1. **导入框架**子批：`ImportSource` 密封类型 + `EntryImporter` 接口 + 落库适配（复用 `VaultRepository.saveEntry`）
     + 冲突/重复策略 + 导入结果报告 UI；
  2. **KeePass XML**（`<KeePassFile>` 结构，可与现有 `KeePassXmlExporter` 对称复用字段映射）；
  3. **Bitwarden JSON**（`items[].login`）；
  4. **浏览器 CSV**（Chrome/Edge 表头 `name,url,username,password`）；
  5. **1PUX**（`.1pux` 实为 ZIP + `export.data` JSON，需先接 ZIP 解包）。
- **验收标准**：
  1. 每源独立单测：解析条目数、字段映射、编码/换行、**异常输入 fail-closed**；
  2. 导入过程不得把密码明文落 `String`/日志（遵循敏感数据铁律）；
  3. 导入完成后移除设置页的「预留，暂未生效」说明。

---

### ISSUE-P3-20 (P3-03 残余): 子库挂载支持

- **优先级**：P3（大特性）
- **分类**：多库 / 存储模型
- **背景与现象**：
  `DatabaseSettingsDialogs.kt:200` 的 `ChildDatabaseDialog.onSelectFile` **无落地实现**；
  `SettingsViewModel.kt:134` 的 `childDatabasesCount` **硬编码为 `0`**（「已挂载子库数量」永远显示 0）。
  ISSUE-P3-03 整改时判定属大特性、未实现，已加 `dbset_child_db_reserved_note` 诚实说明。
- **整改依据**：KeePass2Android 的「子库/复合数据库」模式；`.codebuddy/rules/engineering-rules.md` 原子写盘与凭据隔离纪律。
- **设计要点（须在开工前定案）**：
  1. **挂载点抽象**：在根库 `KdbxGroup` 上定义挂载点（如专用分组 + 自定义属性记录子库 URI/别名），
     与 `KdbxMerger` 的 UUID/墓碑语义**隔离**，避免子库条目被根库同步误删；
  2. **只读/可写分级**：首版建议**只读挂载**（子库条目在根库中呈现为只读投影）；可写需解决
     「一次保存写两个文件」的原子性（现有 `DatabaseSession` 单文件事务模型不覆盖）；
  3. **凭据隔离**：子库主密码 / KeyFile 必须独立于根库会话（独立 `useCredentials` 通道 + 独立清零路径），
     **严禁**与根库派生密钥混用；挂载期间的锁库 / 超时熔断需同时终止子库会话；
  4. **同步交互**：根库同步**不得隐式上传子库文件**（`SyncCoordinator` 仅处理 `currentFile`）；
     挂载元数据若写入根库需评估与 `KdbxMerger` 的兼容性。
- **验收标准**：按上述四点逐项落地；`childDatabasesCount` 不再硬编码；挂载/卸载/凭据失效均有单测覆盖。

---

### ISSUE-P3-21 (P3-04 残余): 建库侧「生成附属密钥文件」为假开关

- **优先级**：P3（功能缺口 / 诚实性）
- **分类**：密钥管理 / 建库流程
- **背景与现象**（ISSUE-P3-04 整改中实测发现，属该批次**第二个假开关**）：
  新建库弹窗的「同时生成附属密钥文件」勾选项，把 `keyFile: Boolean` 传给
  `DatabasePickerViewModel.createDatabase(..., keyFile, ...)`，但 **`RealVaultRepository.createDatabase`
  完全未使用该参数**，且 `DatabaseSession.create` **无 `keyFileData` 形参** → 建库时无法落入密钥文件因子；
  `DatabasePickerViewModel` 的 `SELECT_EXISTING` 路径选中的密钥文件同样被丢弃。
  即：**勾选后产生的库实际不含密钥文件因子，用户以为有、实际没有**——属安全语义上的欺骗。
- **整改依据**：复合密钥正确性（对齐官方 `CompositeKey` 三分支）；拒绝假开关。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/data/repository/RealVaultRepository.kt`（`createDatabase` 的 `keyFile` 形参）
  - `database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt`（`create` 需新增密钥文件因子形参）
  - `database/src/main/java/com/keepasskey/database/file/KdbxFile.kt`（`deriveKeys` 已支持三分支，应复用）
  - `app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerViewModel.kt` / `DatabasePickerScreen.kt`
- **注意事项**：KeyFile 解析**已有唯一实现** `database/.../file/KdbxKeyFile.kt`（`internal`，经 `KdbxFile.deriveKeys` 暴露），
  **严禁在 app 层重写**；密钥文件字节全程 `ByteArray` 且用毕清零。
- **验收标准**：
  1. 勾选「生成附属密钥文件」后，产出的 `.kdbx` 确实以「主密码 + 密钥文件」复合密钥加密，可用同一密钥文件解锁；
  2. 生成的密钥文件经既有导出通道可交付给用户，且有明确的一次性保存提示（丢失即无法解锁）；
  3. `SELECT_EXISTING` 选中的密钥文件真实参与复合密钥；
  4. 补单测：建库后以（密码 + 密钥文件）解锁成功、（仅密码）解锁失败；
  5. 若最终判定不实现，则**必须移除该勾选项**（不得保留误导性 UI）。

---

### ISSUE-P3-22 (P3-02 残余): 分组自定义图标渲染

- **优先级**：P3（渲染完整性）
- **分类**：UI 渲染 / KDBX Meta
- **背景与现象**：
  ISSUE-P3-02 已让**条目**的自定义图标在列表与详情页渲染，并让删除路径清理全树引用（含分组）以避免悬挂引用；
  但 `KeePassGroupRow` 仍只画标准矢量图标，**KDBX 分组同样支持的 `CustomIconUUID` 未渲染**。
- **整改依据**：KeePass 2.x 自定义图标规范（分组与条目共用 Meta 图标池）。
- **验收标准**：
  1. 已绑定自定义图标的分组在分组列表中渲染对应位图，并与条目共用同一 `IconBitmapCache`（避免重复解码）；
  2. 图标池中缺失时回退为缺图占位（**不谎报**为标准图标，沿用条目侧 `EntryIcon.Missing` 语义）；
  3. 补单测覆盖分组投影判定。

---

### ISSUE-P3-23 (P3-11 残余): arm64 真机 instrumented 验证与 Argon2 真实语料端到端解锁

- **优先级**：P3（验证覆盖；依赖外部设备与语料资源）
- **分类**：crypto 原生 KDF / 测试基础设施
- **背景与现象**（ISSUE-P3-11 整改后的**如实残余**）：
  该批次已建立 `crypto/src/androidTest/` 源集，并在 **x86_64 模拟器（Android 16 / API 36）**取得真实绿证
  （7 例 0 失败 exit 0：`.so` 自 APK 内加载 477,976 B、`available==true`、与 BC 冻结向量逐字节一致；
  性能 p=2 **4.98×**、p=4 **8.42×**，R1 闸门通过）。但以下两项**仍未达成**：
  1. **arm64 真机路径未验证**——本机 SDK 仅有 x86_64 system-image（`android-36.1` / `android-34`），
     **无 arm64 镜像亦无真机**；x86_64 模拟器数据**不可冒充** arm64 真机数据。
  2. **真实 `.kdbx` 语料端到端解锁完全未达成**——阻塞与设备无关：
     - 语料未入库（`crypto/src/test/resources/argon2-interop/` 目前仅有生成方法 `README.md`）；
     - **模块依赖单向**：`crypto` 不依赖 `database`，不具备 `.kdbx` 读写能力 → 该用例只能落 `database` 模块，
       而 `database` 亦无 `androidTest` 源集；
     - `src/test/resources` **不进** androidTest APK，设备侧语料须放 `androidTest/assets/`（与验收原文位置不可互替）。
- **整改依据**：AGENTS.md §6 已知工程限界「原生 Argon2 为 Rust 内核…真机 arm64 instrumented 验证待设备可用时补」。
- **涉及核心文件**：
  - `crypto/src/androidTest/java/com/keepasskey/crypto/kdf/`（既有 `NativeArgon2InstrumentedTest`，可扩展）
  - `database/src/androidTest/**`（**需新建**，用于端到端 `.kdbx` 语料解锁）
  - `crypto/src/test/resources/argon2-interop/`（语料存放约定）
- **验收标准**：
  1. 在 **arm64 真机或 arm64 模拟器镜像**上运行 `connectedDebugAndroidTest`，断言 `NativeArgon2.available == true`
     且派生结果与 BC 冻结向量逐字节一致；
  2. 取得 arm64 真机性能数据（t=2/m=64MiB/p=2 与 p=4），与 x86_64 模拟器及宿主侧 Batch 4 数据**并列归档**，
     复核 R1 决策闸门（原生不得慢于 BC 的 2 倍）；
  3. 用真实 KeePass 2.61.1 / KeePassXC 生成的 Argon2d/id（0x10/0x13）`.kdbx` 语料，在设备上完成端到端解锁；
  4. 归档文件 `docs/原生Argon2真机验证记录.md` 中的「待填」表格补齐（**严禁编造数据**）。

---

### ISSUE-P3-24 (P3-09 残余): CI 门禁首跑校准

- **优先级**：P3（供应链安全；依赖联网 CI 环境）
- **分类**：构建 / CI
- **背景与现象**（ISSUE-P3-09 整改后的**如实残余**）：
  该批次已落地 `failBuildOnCVSS=7.0f` / `failOnError=true`、7 个 Action 全部 SHA 钉死、
  新增 `.github/workflows/build.yml` 三 job（fast-gate / native-gate / rust-supply-chain）与版本固定
  （NDK 28.2.13676358 / Rust 1.97.1 / cargo-ndk 4.1.2 / cargo-deny 0.20.2）。但**全部 CI 流程在本环境从未真实运行过**：
  1. `build.yml` 三条 job 首次运行未验证（含一次性 CI 密钥签名断言、Action 拉取）；
  2. `dependency-scan.yml` 在 CVSS≥7 下的真实阻断结果未验证（需完整 NVD 数据通道）；
  3. `cargo deny check advisories` **本机实测复现 ISSUE-P3-09 现象 6**：
     `curl 28 Failed to connect to github.com:443`（无法拉取 rustsec/advisory-db）→ 该子检查的判定逻辑
     仅能以「CI 阻断语义」静态保证（`deny.toml` 无 ignore 列表、`yanked="deny"`）。
- **⚠️ 可预判的首次运行风险**：fast-gate 在 **Linux** 上的行为与本机（Windows）不同——
  `AtomicFileWriterTest` 的 POSIX 目录 fsync 断言、`sync` 的缓存权限断言在 Linux 上会**真实执行**而非跳过，
  **存在首次即红的可能**（本机无法预判）。
- **整改依据**：SLSA 供应链分级；CI 不得停留在「结构存在但从未运行」状态。
- **验收标准**：
  1. `build.yml` 三 job 在 CI 上真实跑通（首次失败按实际结果校准 `sdkmanager` 组件名、Rust 版本可用性、
     Linux 侧测试表现）；
  2. `dependency-scan.yml` 首次以 CVSS≥7 运行后，按实际命中处置（修依赖或登记 suppression，**不得回调阈值**）；
  3. `cargo deny check advisories` 在 CI 上真实执行并通过（或在确无可用通道时登记明确豁免理由）；
  4. material3 alpha 退出条件复核：当 Google Maven 出现 **1.5.0 stable** 时，删除 `libs.versions.toml` 的
     `material3` 版本项与 `compose-material3-alpha` 别名、删除 `app/build.gradle.kts` 中对应 `implementation` 一行，
     回归 Compose BOM 托管（判据：`assembleDebug` 通过且 `Theme.kt` 的 `MotionScheme.expressive()` 无需改动即可编译）。

---

### ISSUE-P3-25 (P3-03 / P3-04 残余): 巨型类拆分

- **优先级**：P3（代码整洁度）
- **分类**：重构
- **背景与现象**：
  `.codebuddy/rules/engineering-rules.md` 规定单文件超过约 400 行必须拆分。以下文件在**本批次接线前即已超标**
  （本批次新增逻辑刻意收敛为短方法、未加剧问题，但超标本身未解决）：
  | 文件 | 行数（整改后） | 说明 |
  |---|---:|---|
  | `app/.../sync/SyncCoordinator.kt` | ~965 | 建议按「同步周期编排 / 冲突决策 / Provider 构建」三职责拆分 |
  | `app/.../ui/screens/unlock/UnlockViewModel.kt` | ~979 | 建议拆出 `BiometricUnlockCoordinator` / `KeyFileSessionCoordinator` |
  | `database/.../xml/KdbxXmlGroupReader.kt` | 407 | 轻微超标 |
- **整改依据**：工程规则「拒绝巨型类 / 巨型函数」。
- **验收标准**：
  1. 上述文件均降至阈值内（或就「为何不可拆」给出经论证的例外说明并登记）；
  2. 拆分为**纯结构性**改动，不改行为——`.\gradlew.bat test` 全绿且用例数不减；
  3. 优先采用依赖倒置（抽接口 + Hilt 绑定），不得为拆分引入循环依赖。

---

### ISSUE-P3-26 (P3-13 残余): `deleteBackup` 删除 `.bak` 后未做目录 fsync

- **优先级**：P3（崩溃安全完整性）
- **分类**：原子写 / POSIX crash-safety
- **背景与现象**：
  ISSUE-P3-13 已把 `AtomicFileWriter` 的**四条**目录 fsync 路径（`.bak` copy 后、主路径 `Files.move(ATOMIC_MOVE)` 后、
  降级 `renameTo` 成功后、降级 `Files.copy` 覆盖后）全部改为经可注入的 `DirectorySync` 抽象触达。
  但 `deleteBackup` 在**删除 `.bak` 文件后未再执行目录 fsync**——同属「目录项变更后需 fsync 父目录」的崩溃安全面。
- **整改依据**：POSIX crash-safety（rename/unlink 后均需 fsync 父目录）。
- **涉及核心文件**：
  - `database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt`
  - `database/src/test/java/com/keepasskey/database/session/AtomicFileWriterTest.kt`
- **验收标准**：
  1. `deleteBackup` 删除 `.bak` 后调用注入的 `DirectorySync`；
  2. 复用既有假实现计数断言「删除路径确实触达目录同步钩子」（沿用 ISSUE-P3-13 的断言范式）；
  3. 保持 Windows 上的降级不阻断语义不变。

---

### ISSUE-P3-27 (P3-10 残余): 解压上限不自洽与并发签名计数器假说

- **优先级**：P3（健壮性 / 一致性）
- **分类**：输入验证 / 并发
- **背景与现象**（ISSUE-P3-10 整改中如实登记的残余）：
  1. **上限不自洽**：`KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES` 本轮由 512 MiB 下调为 **128 MiB**，
     而 `InnerHeader` 单字段上限为 **256 MiB** → 存在「单字段合法上限高于整包上限」的语义不自洽
     （当前不构成缺陷，因整包必然包含多个字段；但两者的取值关系应显式定义并注释）；
  2. **并发签名计数器**：`PasskeyEntryCoordinator` 已改为 `updateDatabaseMeta` 受控事务 + 「库内现值 + 1」单调下界，
     但**并发递增时断言签名值可能重复**（两个并发递增得到同一值的情形未被完全排除），
     该假说未经并发压力测试证实或证伪。
- **整改依据**：CWE-190 整数溢出；WebAuthn 签名计数器单调性语义。
- **验收标准**：
  1. 显式定义并注释两级上限的取值关系（或收敛为单一真源常量），补边界单测；
  2. 以并发用例（真实多协程并发递增）证实或证伪「签名值可能重复」；若确实可能重复，
     改为真正原子的读-改-写（如会话级互斥 + 版本号），并补回归锁。

---

### ISSUE-P3-28 (文档治理): 待办条目应附「核实时间点」以降低前提滞后

- **优先级**：P3（工程纪律）
- **分类**：文档治理
- **背景与现象**：
  本批次发现 **ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时已不成立**——两者都声称
  `docs/plans/`、`STATUS.md`、`plans/rust-enclave-poc.md` 等文件「需要删除」，但这些文件早在
  `7dba64d`（重构文档体系）与 `d578df7` / `863d81c` 中就已删除；`AGENTS.md` 也已不含相关引用。
  条目与代码库演进之间存在时间差，导致执行者可能去做已经完成的工作。
- **整改依据**：单一真相源的可信度；避免「认领后发现无对象可改」的浪费。
- **验收标准**：
  1. `docs/ACTIVE_ISSUES.md` 顶部「维护规则」补充约定：**新增条目须附「核实时间点」与「核实方式」**
     （例如「2026-09-10 经 `Test-Path` / 全仓 grep 核实」）；
  2. 对现存条目做一次前提复核，把已不成立的前提就地修正或标注；
  3. 该约定同步写入 `AGENTS.md` §3 极简闭环工作流（认领步骤）。
