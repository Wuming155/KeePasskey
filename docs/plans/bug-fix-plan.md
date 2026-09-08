# KeePasskey 现存 Bug 修复计划

> **创建时间**：2026-09-07
> **定位**：本文件为「修复现存 Bug」的**执行策略层**（分期路线 + 依赖 + 验收），权威任务看板以 [`docs/STATUS.md` §2](../STATUS.md) 为准。任务完成态一律回写 STATUS，本计划在被看板完全吸收后可按 AGENTS 第 7 条纪律删除。
> **纪律**：每完成一组修改 → `.\gradlew.bat test` 通过 → `git commit` → 立即 `git push`（AGENTS 第 5/6/7 条）。

---

## 1. 目标与原则

1. **先安全/功能债，后现代化/新功能**：当前 42 项待办里新功能仅 `TASK-15~18`（全 P3），绝大多数是安全/功能/质量债；且仍有 2 个 P0 级未清，必须先收敛再谈新功能。
2. **每个阶段结束跑全量测试**：`.\gradlew.bat test` 须全绿（417 例），`LiveSyncServersTest` 仅在起 `tools/local-sync` 后追加 `-DliveSyncTest`。
3. **单点提交、即时推送**：不堆积工作区，提交信息以 `TASK-xx` 引用任务。

---

## 2. 当前基线（起点）

- Git HEAD：`a1423b9`（main）
- 测试：**417 例全绿**（app 99 / core 21 / crypto 42 / database 146 / sync 109）
- 构建：`assembleDebug` + `assembleRelease`(R8) 通过
- P0 阻断项（7 项）：已 100% 修复

---

## 3. 任务分层（按严重度）

| 层级 | 任务 | 说明 |
|---|---|---|
| **P0（阻断/真实 Bug）** | TASK-01、TASK-22 | flaky HMAC 回归锁未锁死；AutoLock 旋转即失效 |
| **P1（安全/验证）** | TASK-09、TASK-23、TASK-02 | 测试代码泄露真实凭据；EC 标量越界；凭据能力实机验证 |
| **P2（安全/功能/质量债）** | TASK-10~14、TASK-24~42、TASK-08 | 见各阶段明细 |
| **P3（整洁/性能/低危）** | TASK-21、TASK-41、TASK-42 | 大文件拆分、死代码、性能调度 |
| **新功能/现代化（低优先）** | TASK-15~18、TASK-03~07 | 自定义图标/克隆/REF/Passkey 解锁；kapt→KSP/DataStore/版本目录/Baseline Profiles/M3 Expressive |

---

## 4. 分阶段执行路线

### 阶段 0：基线确认（每次提交前必做）
- 跑 `.\gradlew.bat test`，确认 417 例仍全绿、无回归。

### 阶段 1：P0 阻断 / 真实 Bug
| ID | 文件 / 证据 | 验收 |
|---|---|---|
| **TASK-01** | `KdbxFile.kt` HMAC 防篡改回归锁 flaky（`testCorruptHmacBlock` 偶发未抛异常） | `testCorruptHmacBlock` **≥20 次全跑零失败**；`!!` 清理已落地 |
| **TASK-22** | `AutoLockManager.kt:36` `@Singleton` 在 `MainActivity.onDestroy` 被 `destroy()` | 旋转屏幕后自动锁定仍生效；加 Activity 生命周期守卫，避免 `onDestroy` 误销毁单例 |

### 阶段 2：P1 安全债
| ID | 文件 / 证据 | 验收 |
|---|---|---|
| **TASK-09** | `Argon2InteropDiagnosticTest.kt:27-52` 含真实主密码/密钥；`KdbxKeyFileTest.kt:33` | 删除文件或用随机自造向量，仓库内**零真实凭据** |
| **TASK-23** | `PasskeyCryptoEngine.kt:364` `parseEcPrivateKey` 缺 `d ∈ [1, n-1]` 校验 | 标量越界 fail-closed，补范围校验单测 |
| **TASK-02** | 凭据能力注册（Wave 16 修正 `meta-data` 名） | 真机「设置→密码、密钥和自动填充」确认 KeePasskey 出现且能力生效 |

### 阶段 3：P2 安全 / 功能债（第一批，安全相关优先）
| ID | 文件 / 证据 | 验收 |
|---|---|---|
| **TASK-11** | `KeePasskeyAutofillService.kt:228` 已解锁分支无 `setAuthentication` | 已解锁分支下发前加二次确认/认证 |
| **TASK-14** | `SyncCredentialsStore.kt:63` 生产测试钩子 | 移除或 `@VisibleForTesting` 保护 |
| **TASK-24** | `KdbxFile.kt:144` `legacyCipherKey` 未清零 | `finally` 补 `Arrays.fill` 清零 |
| **TASK-28** | `AttachmentManager.kt:14` 附件明文缓存无清理 | 加密缓存或用完即删 |
| **TASK-29** | `PasskeyCryptoEngine.kt:173` RSA `certainty=12` | 提升至 ≥80 |
| **TASK-10** | `EntryEditUiState.kt:28` TOTP/自定义字段编辑态 `String` | `CharArray` 化 |

### 阶段 4：P2 功能 / 互操作 / 构建债
| ID | 文件 / 证据 | 验收 |
|---|---|---|
| **TASK-25** | `WebDavSyncProvider.kt:85` ISO-8859-1 致中文密码 401 | 改 `charset=UTF-8` 并 UTF-8 编码 |
| **TASK-26** | `S3SyncProvider.kt:75` SigV4 `*`/`~` 不符规范 | 编码符合 AWS 规范，补已知答案向量 |
| **TASK-27** | `CborEncoder.kt:127` 非 Canonical 键序 | 强制 RFC 8949 键序 |
| **TASK-12** | `SettingsViewModel.kt` 33 个开关纯内存回显 | 持久化或下架假开关（`skipDalVerification` 等） |
| **TASK-13** | 5 个 SAF 动作仅 `UiMessage` 假提示 | 接 `CreateDocument` 真实写盘 |
| **TASK-37** | `SyncCache.kt:142` 两文件非原子写 | 合并原子写 |
| **TASK-38** | `app/build.gradle.kts:24` 未开 `shrinkResources` | 启用 + ProGuard 收紧 |
| **TASK-39** | `UnlockScreen.kt:110` SAF 密钥主线程读 | 移至 `Dispatchers.IO` |
| **TASK-08** | `WorkManager` 周期同步无消费方 | 接入 `periodicBackgroundSyncIntervalMinutes`/`wifiOnlySync` |

### 阶段 5：P2/P3 功能 / 质量收尾
| ID | 文件 / 证据 | 验收 |
|---|---|---|
| **TASK-30** | `ConflictResolutionViewModel.kt:128` 字段级合并塌缩 | 字段级合并或简化 UI |
| **TASK-31** | `EntryDetailViewModel.kt:242` 空快照谎报回滚 | 改错误提示 |
| **TASK-32** | `MockData.kt:106` 强度硬编码 112 | 接真实熵估算或显式标注 |
| **TASK-33** | `AuthenticatorViewModel.kt:82` TOTP 缺失假码 000000 | 显式错误/空白 |
| **TASK-34** | `EntryDetailViewModel.kt:195` 收藏不落库 | 调 Repository 保存 |
| **TASK-35** | `RealVaultRepository.kt:723` 卡条目当普通登录 | category 与卡字段映射 |
| **TASK-36** | `AutofillSettingsScreen.kt:343` 黑名单空 onClick | 实现删除逻辑 |
| **TASK-40** | 测试覆盖缺口（T-03/T-04/P2-35/P2-37） | 补 `SyncCacheTest`/SigV4/`SecurityTest`/真实 Keystore 用例 |
| **TASK-21** | 7 文件超 800 行 + ~250 硬编码中文 | ✅ 已完成（2026-09-08）：7 文件全部拆分达标；用户可见文案全量资源化（`StringsProvider` 通道 + 3 个新 values 文件共 113 键），中文字面量 294→114（余为日志/开发异常/持久化数据），状态与证据见 `STATUS.md` / `FINDINGS_TRACKER.md` |
| **TASK-41** | 低危清理批次（P3-5/7/9...） | 死代码/魔数/空块清理 |
| **TASK-42** | 低-中调度批次（P2-2/12/29...） | Argon2 走 IO / callTimeout / flowOn |

### 阶段 6：新功能 / 现代化（低优先，债清后再做）
- **TASK-15~18**（P3 新功能）：自定义图标上传/选择、条目克隆、KeePass 字段引用 `{REF:...}` 引擎、Passkey 作解锁方式。
- **TASK-03~07**（现代化批次）：kapt→KSP、Preferences DataStore、Gradle 版本目录、Baseline Profiles、compileSdk 37 / Material 3 Expressive。

---

## 5. 执行纪律（引用 AGENTS）

- **第 5 条**：任务状态变更实时回写 `STATUS.md §2`。
- **第 6 条**：改完 → `test` 通过 → `commit` → **立即 `push`**（网络失败可暂缓待补推）。
- **第 7 条**：本计划被看板吸收后删除，杜绝与 SSOT 多头并存。

---

## 6. 进度跟踪

每完成一个阶段，在 `STATUS.md §2` 将对应 TASK 标记完成；本计划仅作路线参考，不维护逐条状态。
