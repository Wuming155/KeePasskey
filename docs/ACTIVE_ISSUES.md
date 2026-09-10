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

## P1 高危与核心功能问题（0 项）

> 当前 P1 级别无待办。历史 P1 项（含 ISSUE-P1-10 / ZT-10）已全部闭环，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.5。

---

## P2 中危缺陷与协议/测试缺口（2 项）

> ISSUE-P2-05 ~ ISSUE-P2-13 九项已全部整改并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.16 ~ §2.20。
> 下列 2 项为 P2-12 整改中**如实登记的残余面**（当初验收为「部分完成」，不计入九项闭环）。

### ISSUE-P2-15 (P2-12 残余 A): 模型层受保护值经 readString() 退化为不可擦除 String（TOTP 种子/详情/修订路径）

- **优先级**：P2（内存治理）
- **分类**：敏感数据 / 领域模型边界
- **背景与现象**：
  ISSUE-P2-12 已把**解析层与 TOTP 计算链路**字节化，但领域模型/仓库接口的返回通道仍为不可擦除 `String`：
  - `app/src/main/java/com/keepasskey/app/data/repository/RealVaultRepository.kt` **5 处** `readString()`：
    `:741`（`getEntryPassword`）、`:757`（修订密码 `getEntryRevisionPassword`）、`:780`/`:789`（自定义字段回填）、`:785`（修订 TOTP 原文）；
  - `core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt:114` 的 `parse(String)` 兼容重载（生产路径已不走，但重载本身是 String 物化入口）。
  这些 String 一旦生成即驻留堆直至 GC，且**无法显式清零**——与工程规则「能用 Char/Byte 的地方绝不落到 String」冲突。
- **整改依据**：工程规则敏感数据铁律；对齐 `getEntryPasswordChars` / `getEntryTotpSecretChars` 已有的 CharArray 借用契约（调用方用毕清零）。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/data/repository/RealVaultRepository.kt`
  - `app/src/main/java/com/keepasskey/app/data/repository/VaultRepository.kt`（接口契约）
  - `core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt`
- **验收标准**：
  1. 新增/迁移 `CharArray` 借用型接口（如 `getEntryPasswordChars` 已存在，补齐修订与自定义字段的字节通道），消费方在 `finally` 中 `fill('0')`；
  2. 现有 `String` 通道标记 `@Deprecated` 并逐步下线（UI 层若必须 String，收敛在最小作用域并注明不可擦边界）；
  3. `TotpKeyUriParser.parse(String)` 重载收敛为**仅测试可见**（`internal` + `@VisibleForTesting`）或删除；
  4. 单测断言新字节通道的借用与清零契约。
- **已知约束**：本项改动横跨 UI 投影模型（`UiVaultEntry` 等字段类型为 `String`），需与 M1「投影层不物化密码明文」原则一并对齐，属跨模块契约改造，建议独立批次。

---

### ISSUE-P2-16 (P2-12 残余 B): 密码生成引擎出边界仍返回不可擦除 String

- **优先级**：P2（内存治理）
- **分类**：敏感数据 / 生成器
- **背景与现象**：
  `app/src/main/java/com/keepasskey/app/ui/screens/generator/DicewareWordList.kt` 的三个生成函数内部**已用 `CharArray` 计算**，但返回值仍物化为 `String`：
  - `:277` `generateRandomPassword`（`:284` 返回 `String`）
  - `:311` `generatePassphrase`（`:316` 返回 `String`）
  - `:333` `generateMaskedPassword`（返回 `String`）
  连带下游：`GeneratorScreen.kt:213/513` 渲染与剪贴板 `copySensitiveText` 边界。ISSUE-P2-12 已把 `GeneratorUiState` 当前密码与 history 容器改为 `ProtectedString` 并在淘汰/`onCleared` 清零，但**生成瞬间的返回值仍是不可擦 String**，等于在 M1 边界上开了一个明文物化口。
- **整改依据**：工程规则敏感数据铁律；M1「投影层不物化密码明文」；`DicewareWordList.calculateEntropy(CharArray)` 已有先例。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/screens/generator/DicewareWordList.kt`
  - `app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt`
  - `app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorScreen.kt`
- **验收标准**：
  1. 三个生成函数改为返回 `CharArray`（或直接返回 `ProtectedString`），取消 `String` 重载（`calculateEntropy(String)` 一并收敛）；
  2. `GeneratorViewModel` 消费后立即将 `CharArray` 交给既有 `ProtectedString` 容器（避免中间态驻留）；
  3. 复制到剪贴板路径改为「CharArray → 受保护剪贴板 API」直通，不经 `String`；UI 渲染如需 String，须在最小作用域内并注明不可擦边界；
  4. 单测覆盖生成的字节/字符通道与用毕清零，且既有生成器用例全绿。

---

---

## P3 低危问题、特性接线与体验优化（16 项）

### ISSUE-P3-01 (TASK-55): 生物识别解锁开关开启后第二次解锁不默认触发生物识别
- **优先级**：P3（用户体验）
- **分类**：解锁流程 / UX
- **背景与现象**：
  真机实测反馈：在设置中已开启「生物识别解锁」，首次保存凭据后，第二次打开应用解锁时未自动弹出系统的 BiometricPrompt，仍停留在主密码输入界面。
- **期望行为**：
  开关开启态下，进入解锁页时默认自动唤起生物识别认证（硬件支持且已登记）；认证取消或失败时平滑回退为主密码输入。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/unlock/UnlockScreen.kt`
  - `app/src/main/java/com/keepasskey/app/ui/unlock/UnlockViewModel.kt`
  - `app/src/main/java/com/keepasskey/app/security/BiometricAuthManager.kt`
- **验收标准**：
  1. 开关开启 + 有已封印凭据时，进入 UnlockScreen 自动调起 BiometricPrompt；
  2. 生物识别成功直接解密主密码并解锁；
  3. 用户主动点击取消后停留在主密码输入框，不造成死循环。

---

### ISSUE-P3-02 (TASK-49): 自定义图标渲染删除与 Notes/URL 字段引用展示侧接线
- **优先级**：P3（特性残余）
- **分类**：UI 渲染 / 协议引用
- **背景与现象**：
  1. **图标侧（TASK-15 残余）**：`CustomIconCoordinator` 与 KDBX Meta 图标池数据通道已就绪，但条目列表行（`VaultListScreen`）和详情页（`EntryDetailScreen`）尚未根据 `customIconId` 渲染位图，且缺少自定义图标删除入口；
  2. **引用侧（TASK-17 残余）**：`FieldReferenceEngine` 已就绪且已接入自动填充与复制，但 Notes 和 URL 展示侧尚未通过引擎解析 `{REF:...}`。
- **整改依据**：
  KeePass 2.x 自定义图标与字段引用展示规范（保持 M1 投影层不物化密码明文原则）。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/vault/VaultListScreen.kt`
  - `app/src/main/java/com/keepasskey/app/ui/vault/EntryDetailScreen.kt`
  - `app/src/main/java/com/keepasskey/app/ui/vault/EntryDetailViewModel.kt`
- **验收标准**：
  1. 列表和详情页正常渲染条目绑定的自定义 PNG 图标；
  2. 提供图标删除入口，删除时同步清理 Meta 并将引用条目回退为默认图标；
  3. Notes / URL 中的 `{REF:...}` 在展示时正确展开对应条目的公开字段；
  4. 循环引用深度限制生效，不崩溃。

---

### ISSUE-P3-03 (TASK-43): 进阶偏好设置消费方接线（分批落地）
- **优先级**：P3（功能完整性）
- **分类**：设置消费 / 进阶特性
- **背景与现象**：
  设置页预留的进阶开关已随 TASK-12 完成持久化，但底层消费方尚未全面接线：
  - **43a (同步)**：`webdavChunkedUpload` / `webdavChunkSizeMb` / `createBackupBeforeSave`（保存前 `.bak` 备份） / `checkRemoteChangesBeforeSave` / `conflictResolution` 默认策略；
  - **43b (自动填充)**：`autofillCopyTotp` / `inlineSuggestionsEnabled` / `autoReturnFromQuery` / `autofillShowTotpNotification`（`skipDalVerification` 已随 ISSUE-P2-02 接线，见 RESOLVED_LOG §2.13）；
  - **43c (UI 偏好)**：`maskPasswordsDefault` / `maskTotpDefault` / `listDensity` / `autoActivateSearchOnOpen` / `showGroupInSearchResult` / `showGroupInEntry` / `showUnlockedNotification` / `showKillAppOption`；
  - **43d (导入解析器)**：1PUX / Bitwarden / KeePass XML / 浏览器 CSV 5 源码导入解析器；
  - **43e (子库)**：子库挂载支持；
  - **43f (调试)**：`debugLogEnabled` / `verboseSyncLog`。
- **整改依据**：
  各功能预留开关设计；避免「假开关」，每批接线必须真实生效。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/settings/...`
  - `sync/src/main/java/com/keepasskey/sync/...`
  - `app/src/main/java/com/keepasskey/app/autofill/...`
- **验收标准**：
  按子批独立实现并提交，每批接线的功能具备对应单元测试或交互验证。

---

### ISSUE-P3-04 (TASK-54): 导入密钥与 KeyFile 管理功能
- **优先级**：P3（特性）
- **分类**：文件导入 / 密钥管理
- **背景与现象**：
  真机实测反馈：缺少便捷导入外部 KeyFile 密钥文件并将其持久化关联到密码库的功能。
- **整改依据**：
  SAF `OpenDocument` 密钥文件导入与会话缓存管理。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/unlock/UnlockScreen.kt`
  - `app/src/main/java/com/keepasskey/app/repository/RealVaultRepository.kt`
- **验收标准**：
  1. 支持从系统文件管理器选取 KeyFile；
  2. 记住上次成功解锁使用的密钥文件路径/URI（由用户偏好控制）；
  3. 解锁流程对 KeyFile 校验正确。

---

### ISSUE-P3-05 (TASK-19): zxing → CameraX + ML Kit 扫码迁移评估
- **优先级**：P3（依赖治理）
- **分类**：架构现代化 / 依赖评估
- **背景与现象**：
  当前 TOTP 二维码扫描采用 `zxing-android-embedded:4.3.0`，运行稳定。需评估迁移至现代 `CameraX + ML Kit Barcode Scanning` 的收益（更流畅对焦、更小体积、Compose 原生集成）与成本（Google Play 数据安全声明、依赖引入）。
- **整改依据**：
  Android 官方 CameraX 与 ML Kit 最佳实践。
- **验收标准**：
  输出清晰的评估结论与决策（迁移或维持当前稳定方案），回写记录。

---

### ISSUE-P3-06 (P3-7 残余): UI 层冗余 import 清理
- **优先级**：P3（代码整洁度）
- **分类**：代码质量
- **背景与现象**：
  non-UI 层未使用的 import 已清理。UI 层剩余候选约 80 处，由于 Compose 委托语法（`by remember`、`getValue`/`setValue`）存在静态分析误报风险，需人工/IDE 辅助精准清理。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/...`
- **验收标准**：
  清理无用 import，确保全模块编译与测试 100% 通过。

---

### ISSUE-P3-07 (P1-9 残余): 附件读取侧别名共享消除
- **优先级**：P3（内存安全）
- **分类**：KDBX 二进制池
- **背景与现象**：
  保存侧已在去重时对附件字节做克隆保护；读取侧 SAX 解析时仍直接引用池中的 `ByteArray`。只读场景下风险很低，但为保持防御一致性可进行防御性拷贝。
- **涉及核心文件**：
  - `database/src/main/java/com/keepasskey/database/xml/KdbxXmlGroupReader.kt`
- **验收标准**：
  读取附件时返回独立副本，单元测试全绿。

---

### ISSUE-P3-08 (文档梳理与归档): 淘汰旧计划文件并统一单一真相源
- **优先级**：P3（文档治理）
- **分类**：工程纪律
- **背景与现象**：
  随着本文件（`ACTIVE_ISSUES.md`）与 `RESOLVED_LOG.md` 的建立，原 `docs/plans/REPAIR_PLAN.md`、`docs/HEALTH_CHECK_ROADMAP.md` 及 `docs/FINDINGS_TRACKER.md` 已完成历史使命，需物理删除或整合，消除多头维护。
- **验收标准**：
  1. 删除 `docs/plans/REPAIR_PLAN.md` 等冗余计划；
  2. `STATUS.md` 与 `AGENTS.md` 引用全面更新为 `ACTIVE_ISSUES.md` + `RESOLVED_LOG.md`。

---

### ISSUE-P3-09 (ZT-20): 供应链与构建加固批次
- **优先级**：P3（供应链安全）
- **分类**：构建 / 依赖治理
- **背景与现象**（2026-09-09 零信任审计）：
  1. **签名方案不全**：`app/build.gradle.kts:56-57` 仅 `enableV1Signing/enableV2Signing`，minSdk 36 下 v1（JAR 签名）无必要且最弱，缺 v3/v3.1 无密钥轮换能力；
  2. **R8 过度保留**：`proguard-rules.pro:50-51` 对 `passkey.**` / `autofill.**` 整包 `-keep { *; }`（实际只需保留组件类与无参构造），`:43-45` model/file/session 整包保留，`:9` `-dontwarn **` 全局压制警告，`:7` 保留 `SourceFile,LineNumberTable`，`:61-77` Room 规则冗余（本项目无 Room）；
  3. **alpha 依赖进生产**：`material3 = 1.5.0-alpha27`（`app/build.gradle.kts:111` 覆盖 BOM）；`argon2kt 1.6.0` 为孤儿版本（TASK-52 后无消费方）；`zxing-android-embedded 4.3.0` 偏旧；
  4. **CI 无门禁**：`dependency-check.init.gradle.kts:30` `failBuildOnCVSS = 11.0f` + `:39` `failOnError = false` + `dependency-scan.yml:81` `continue-on-error: true` → 扫描纯告警；Action 全部 tag 引用（`actions/checkout@v4` 等）未 SHA 钉死；**无 build / test / lint 流水线**；
  5. `.gitignore:40-43` 缺 `*.p12` / `*.pfx` / `*.pem` / `*.key` 规则。
  6. **Rust 侧供应链闸门未接入 CI**：`crypto/src/main/rust/deny.toml` 已落地且
     `cargo deny check licenses bans sources` 通过，但 `advisories` 子检查需联网拉取
     rustsec/advisory-db（本环境 github 连接被重置），且 CI 无该步骤。
- **整改依据**：SLSA 供应链分级；Google Play 签名与 R8 最佳实践。
- **验收标准**：
  1. 关闭 v1、启用 v3/v4；
  2. 收窄 keep 规则（保留组件与擦除方法即可）、移除 `-dontwarn **` 与冗余 Room 规则、补 Log 剥离；
  3. alpha 依赖降级为稳定版或明确管控；清理孤儿版本；
  4. CI 增加 build/test 门禁、Action SHA 钉死、扫描具备失败阻断阈值；
  5. `.gitignore` 补齐密钥扩展名；
  6. CI 增加 `cargo deny check`（含 advisories）与 4 ABI 原生构建步骤，并固定 Rust/NDK 版本。

---

### ISSUE-P3-10 (ZT-21): 解析与计数器边界加固
- **优先级**：P3（健壮性）
- **分类**：输入验证
- **背景与现象**：
  1. `KdbxFile.kt:66` `MAX_DECOMPRESSED_PAYLOAD_BYTES = 512 MiB` 对移动端偏高，仍可能 OOM（建议 64–128 MiB）；
  2. `PasskeyData.kt:122` `toIntOrNull() ?: 0` + `PasskeyAssertionActivity.kt:111` `signCount + 1` → 恶意 kdbx 置 `Int.MAX_VALUE` 可溢出为负；`PasskeyEntryCoordinator.kt:73-98` 读-改-写无 CAS；
  3. `KdbxXmlParser.kt:46-48` 与 `WebDavSyncProvider.kt:395-398` 的 `setFeature` 失败被空 `catch` 静默吞掉（现有 `resolveEntity` 兜底风险有限）；
  4. `sync/build.gradle.kts:29-41` 硬编码测试凭据默认值 `tester123` / `tester1234`。
- **整改依据**：CWE-190 整数溢出；OWASP MASVS-CODE 输入验证。
- **涉及核心文件**：
  - `database/src/main/java/com/keepasskey/database/file/KdbxFile.kt`
  - `core/src/main/java/com/keepasskey/core/model/PasskeyData.kt`
  - `app/src/main/java/com/keepasskey/app/data/repository/PasskeyEntryCoordinator.kt`
- **验收标准**：
  1. 解压上限下调并单测；
  2. signCount 做上界钳制与溢出防护，写入改为受控事务；
  3. `setFeature` 失败至少落告警日志；
  4. 测试凭据改为随机生成或必须由环境注入。

---

### ISSUE-P3-11 (P2-14 遗留): Rust Argon2 原生内核的真机 instrumented 验证
- **优先级**：P3（验证覆盖；依赖外部设备资源）
- **分类**：crypto 原生 KDF / 测试基础设施
- **背景与现象**：
  ISSUE-P2-14 的 Argon2 内核 C→Rust 迁移已在**宿主侧**完成运行时验证（Batch 4：`cargoHostBuild` 产出宿主
  cdylib，桌面单测经 `-Djava.library.path` 走原生路径，断言原生 ≡ BC 全参数域、≡ libargon2 参考基准，
  并测得宿主侧性能对照）。但计划原定的**真机/模拟器 instrumented 验证**因本机 `adb devices` 为空、
  工程无 `androidTest` 源集而未能执行，`NativeArgon2` 在 Android arm64 上的 JNI 加载与端到端解锁
  目前仅有 `assemble*` 打包期证据（符号/ABI/strip 已核对），缺运行时证据。
- **整改依据**：`plans/rust-enclave-poc.md` §2 Batch 4；风险 R6（桌面单测无 `.so`，原生路径不被覆盖）。
- **涉及核心文件**：
  - `crypto/src/androidTest/java/com/keepasskey/crypto/kdf/`（新增 `NativeArgon2InstrumentedTest`）
  - `crypto/build.gradle.kts`（新增 `androidTestImplementation(androidx.test.*)` 与 `testInstrumentationRunner`）
  - `crypto/src/main/java/com/keepasskey/crypto/kdf/NativeArgon2.kt`（被测对象，不改）
- **验收标准**：
  1. 起模拟器或连真机后，`connectedAndroidTest` 能加载 APK 内 `libkeepasskey_argon2.so`，
     断言 `NativeArgon2.available == true` 且派生结果与 BC 冻结向量逐字节一致；
  2. 用真实 KeePass 2.61.1 / KeePassXC 生成的 Argon2d/id（0x10/0x13）`.kdbx` 语料端到端解锁成功
     （语料需先补入 `crypto/src/test/resources/argon2-interop/`）；
  3. 记录 arm64 真机性能数据（t=2/m=64MiB/p=2 与 p=4），与 Batch 4 宿主侧数据并列归档，
     复核对 R1 决策闸门（原生不得慢于 BC 的 2 倍）。

---

### ISSUE-P3-12 (P2-09 残余): 主 App 敏感 Compose 屏未接遮挡触摸过滤

- **优先级**：P3（UI 防护纵深）
- **分类**：点击劫持防护 / 接线完整性
- **背景与现象**：
  ISSUE-P2-09 已为 autofill 两屏接线遮挡触摸过滤（`autofill_dataset_item.xml` 三视图 + `FlagSecureGuard.applyObscuredTouchFilter` 的 `decorView.filterTouchesWhenObscured` + `SecureTouchCompose.ApplyObscuredTouchFilter`），但**主 App 的敏感 Compose 屏**（解锁页、条目详情、密码生成器、Passkey 相关屏）根节点尚未调用 `ApplyObscuredTouchFilter()`，同进程 overlay / 无障碍注入点击的防护在这些界面仍缺失。
- **整改依据**：OWASP MASVS-PLATFORM-1；ISSUE-P2-09 验收标准 2 的完整覆盖。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/...`（各敏感屏根 Composable）
  - `app/src/main/java/com/keepasskey/app/security/SecureTouchCompose.kt`（现成 API，无需改动）
- **验收标准**：
  1. 在解锁页、条目详情页、生成器页与 Passkey 流程屏根节点调用 `ApplyObscuredTouchFilter()`；
  2. 覆盖面无遗漏，且不引入正常场景下的触摸失效（需真机/交互验证）；
  3. 若判定某些屏不属敏感场景，需在代码注释中写明理由。

---

### ISSUE-P3-13 (P2-05 残余): Windows 宿主下原子写盘父目录 fsync 无运行时验证

- **优先级**：P3（验证覆盖 / 平台差异）
- **分类**：原子写 / 测试有效性
- **背景与现象**：
  ISSUE-P2-05 新增的 `AtomicFileWriter.syncDirectory()` 在 Windows 上**必然降级**：目录 `FileChannel.open(dir, READ)` 抛 `AccessDeniedException`，被捕获后仅记告警。已在宿主单测中记录该事实，但意味着**目录项 fsync 这条崩溃安全路径在 Windows 宿主上从未被真实执行与断言**，仅靠 POSIX 语义与代码审查背书。
- **整改依据**：POSIX crash-safety（rename 后需 fsync 父目录）；测试有效性缺口。
- **涉及核心文件**：
  - `database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt`
  - `database/src/test/java/com/keepasskey/database/session/AtomicFileWriterTest.kt`
- **验收标准**：
  1. 在 Linux/macOS 宿主（或 CI 的 Linux runner）上运行 `AtomicFileWriterTest`，断言 `syncDirectory` 未走降级分支（可通过可注入的告警/探针计数验证）；
  2. 或引入可注入的目录同步抽象（依赖倒置），在单测中以假实现断言「四条路径均调用了目录 fsync」；
  3. 保持 Windows 上的降级不阻断语义不变。

---

### ISSUE-P3-14 (P2-08 残余): 生物识别完整性提示未使用已资源化文案

- **优先级**：P3（文案一致性 / 资源化）
- **分类**：代码整洁度 / 国际化
- **背景与现象**：
  `BiometricAuthManager.kt:187` 仍以硬编码常量 `INTEGRITY_BLOCKED_MESSAGE = "设备完整性风险，已禁用生物识别快速解锁"` 作为 `BiometricResult.Error` 的 `errString`；集成阶段已补入 `strings.xml` / `values-en/strings.xml` 的 `sec_biometric_integrity_blocked`，但**尚无消费方**（该类无 `Context` 入参，需在调用方或结果消费侧解析资源）。
- **整改依据**：TASK-21 硬编码文案资源化约定；中英双语一致性。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/security/BiometricAuthManager.kt`
  - 结果消费侧（UnlockScreen/UnlockViewModel 等）
  - `app/src/main/res/values{,-en}/strings.xml`
- **验收标准**：
  1. 风险态失败文案统一经 `R.string.sec_biometric_integrity_blocked` 输出（在具备 `Context` 的消费侧解析），移除硬编码常量；
  2. 若确需保留与 UI 解耦的失败语义，则在 KDoc 写明「errString 仅内部诊断、不对外展示」并确保无一处将其直接展示给用户。

---

### ISSUE-P3-15 (P2-07 残余): 编辑页屏蔽非法绑定包名后的提示文案边界

- **优先级**：P3（交互边界 / 诚实化）
- **分类**：自动填充 / 文案语义
- **背景与现象**：
  ISSUE-P2-07 把 `AutofillBlocklistStore.isBlocked` 改为 fail-closed（非法包名返回 true）。副作用：条目详情页「屏蔽/恢复自动填充」入口（`EntryDetailViewModel.toggleAutofillBlockForApp` 路径）在遇到**非法绑定包名**时会被判为「已屏蔽」，从而展示「已恢复」语义的文案，与真实状态不符（非安全回退，属交互诚实性问题）。
- **整改依据**：零信任「宁可失败也不欺骗用户」；既有 TASK-36「假数据/假回执如实化」整改先例。
- **涉及核心文件**：
  - `app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailViewModel.kt`
  - `app/src/main/java/com/keepasskey/app/data/repository/AutofillBlocklistStore.kt`
- **验收标准**：
  1. 非法/缺失绑定包名时，屏蔽入口给出明确的「无法识别应用标识」提示，而非「已屏蔽/已恢复」；
  2. 保证 `isBlocked` 的 fail-closed 安全语义不变（不得为文案改变判定）；
  3. 单测覆盖该分支。

---

### ISSUE-P3-16 (文档一致性): AGENTS.md 文档索引中已下架文件的引用

- **优先级**：P3（文档治理）
- **分类**：工程纪律
- **背景与现象**：
  ISSUE-P3-08 要求清理旧计划文件并统一单一真相源，但 `AGENTS.md` §4 仍出现对 `STATUS.md`（TASK-44 条目说明中）与历史 `plans/rust-enclave-poc.md` 的引用；`RESOLVED_LOG.md` §6/§2.17 亦引用 `plans/` 路径。这些文件如已删除，引用即为悬空指针。
- **验收标准**：
  1. 逐条核对 `AGENTS.md` 与 `RESOLVED_LOG.md` 中的 `docs/plans/`、`STATUS.md` 引用，删除或改写为现行文档路径；
  2. 全仓 grep 确认无残留悬空引用。