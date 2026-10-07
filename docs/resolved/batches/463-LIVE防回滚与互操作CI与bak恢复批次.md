# 批次 463：LIVE 防回滚端到端、互操作 CI 接线与 .bak 恢复路径批次（`ISSUE-P2-518` + `ISSUE-P2-519` + `ISSUE-P2-521` 三条整条闭环，P2 3 → 0）

> 批次来源：[`records/六专题综合研究与实测报告_2026-10-07.md`](../../records/六专题综合研究与实测报告_2026-10-07.md) §9.3 草稿 1 / 2 与报告正文 §4（.bak 可达性 / 损坏无恢复路径）。

## §1 条目与整改

### ISSUE-P2-518：LIVE 层无「防回滚」端到端用例

- **整改**：`LiveSyncServersTest` 新增两例（WebDAV / S3 各一），场景＝防回滚存在意义的原型：
  建库 v1 上板 → 采纳 → 远端更新 v2 → 采纳（摘要环含 v1/v2）→ **服务端整体回滚为 v1**（真实字节重新上板、
  真实 ETag 刷新）→ 断言 `SyncOpenResult.RollbackRejected` 且携远端旧字节、防回滚状态文件原样保留。
  每条用例独立 `Files.createTempDirectory` 缓存/状态目录与随机远端路径，结束清理。
- **实跑证据**：`tools/local-sync` 真服务（wsgidav:9443 + MinIO:9000）经 `python run_lab.py` 一体化执行，
  `sync/build/test-results/testDebugUnitTest/TEST-com.keepasskey.sync.LiveSyncServersTest.xml`
  **tests=14 skipped=0 failures=0 errors=0**（§460 报告基线 12 例 ⇒ +2）。
- **口径说明**：用户提示链（`sync_error_rollback_rejected` 的 snackbar/通知）不在此重复——app 层既有单测
  （`SyncCycleRunner` / `SyncConflictController` 组，5 处消费点）已锁定，LIVE 层补的是「真实服务端响应形态
   + 摘要环跨引擎实例」两件 mock 给不了的事。

### ISSUE-P2-519：verify_interop.py 无自动调用点

- **整改**（`.github/workflows/build.yml` fast-gate，紧跟「单元测试（全模块）」）：
  ① 新步骤「安装对拍依赖（keepassxc-cli + pykeepass/cryptography）」：`apt-get install keepassxc`
  （官方 CLI 腿）+ `pip install --break-system-packages pykeepass cryptography`（两条独立实现腿）；
  ② 新步骤「通行密钥互操作对拍（KPEX 双实现判据，fail-closed）」：先 `ls -la database/build/interop-probe/`
  留痕探针产物，再 `python3 tools/passkey-interop/verify_interop.py`——退出码非 0 即整步红。
  探针产物由上一测试步骤的 `PasskeyInteropProbeTest`（JVM 单测，无 Assume 门控）产出；
  缺 pip 依赖时脚本以非 0 硬退出（`verify_interop.py:56-60`）⇒ 依赖安装失败必红。
  **无条件吞码**（无 `|| true` / `continue-on-error`）；缺 keepassxc-cli 退 2 与判据失败退 1 一律红。
- **验证**：workflow YAML 反校解析通过（`jobs=5`，fast-gate 10 步含新两步）。
- **如实声明**：本机无 CI 环境，**未实跑**该 workflow；apt 提供的 keepassxc-cli 版本（Ubuntu 24.04 ≈ 2.7.6）
  与本机对拍所用 2.7.12 的差异未验证——若版本敏感判据首跑红，按 fail-closed 口径可见并处置（不静默）。

### ISSUE-P2-521：库文件损坏时无 .bak 恢复路径

- **整改**（损坏分型 → 用户可见入口 → 原子恢复，四层）：
  ① **分型**：`KdbxError.UNLOCK_CORRUPT_FILE`（core）+ `SessionOpener` 按 `KdbxCorruptFileException` 族
  与凭据错误分型 + `KdbxErrorTexts` 映射 + zh/en 文案 `err_open_corrupt_file`；
  ② **读数**：`DatabaseSession.rollingBackupExistsFor(targetFile)`（解锁失败时会话 `activeFile` 未设置，
  须按目标文件探测；命名复用 `AtomicFileWriter.backupFileFor` 单一来源）；
  ③ **恢复**：`SessionFileWriter.restoreFromRollingBackup`（备份字节经「临时文件 → fsync → 原子替换」写回主文件，
  **不轮换备份**〔createBackup=false：绝不把损坏主文件覆盖成新 .bak〕且**不删除备份**〔可再次恢复〕；
  失败记语义化告警返回 false，原子替换降级路径按上层口径拒绝无保护覆盖 ⇒ fail-closed 两侧均无损伤）；
  `SessionPersistence` 挂 IO 调度；`DatabaseSession` 门面；
  ④ **UI/会话**：`UnlockUiState.canRestoreFromBackup`；`MasterPasswordUnlockSession`
  （损坏分型且备份在场才解析候选〔`content://` SAF 流式通道恒 null〕，凭据错误恒无入口〔AC③〕；
  恢复＝用户显式确认后的动作，成功置「已从备份恢复为上次成功保存的版本，请重新输入主密码」并撤下入口，
  失败保留入口可重试且备份原样；解锁成功同步清入口）；解锁页 `UnlockPasswordSupportingText` 渲染
  `TextButton`「从备份恢复」，`hasUnlockPasswordSupportingText` 判据同批对齐（`ISSUE-P3-472` 契约：改渲染分支必须同改判据）。
  回调经 `UnlockContent` / `UnlockStandardUnlockContent` 透传（默认空实现，预览与既有调用方零改动）；
  生产接线 `UnlockScreen → viewModel.masterPasswordSession.restoreFromRollingBackup()`（`internal` 暴露会话，
  `SettingsViewModel` 暴露控制器同型先例）。
- **测试**：新增 `RollingBackupRestoreTest`（database，3 例：恢复写回且备份保留不轮换不残留 tmp；
  无备份返回 false 且主文件一字节不动；主文件缺失时从备份重建）；`KdbxErrorTextsTest` 反射遍历覆盖
  由新映射维持（全量回归绿）。

## §2 验证

- 全量宿主：`./gradlew.bat test --rerun-tasks --max-workers=1` BUILD SUCCESSFUL（5m01s，114 任务全执行），
  `count_test_results.py`＝`xml=524 tests=3413 failures=0 errors=0 skipped=15`
  （§462 基线 3408 ⇒ **+5** = LIVE 防回滚 2 + 恢复 3；skipped 13 → 15 = 2 例 LIVE 用例在未开联调时按设计跳过）。
- LIVE 真服务实跑：`run_lab.py`（wsgidav:9443 + MinIO:9000，凭据经 `WEBDAV_USER/WEBDAV_PASSWORD/MINIO_ROOT_USER/MINIO_ROOT_PASSWORD`
  环境变量两侧注入）→ `LiveSyncServersTest` **14/14 全绿**（Gradle 退出码 0）；服务进程已回收。
- `check_user_visible_cjk.py`：`user_visible_cjk_sites=0`（新增跨层文案未触红线）。
- workflow YAML 反校解析通过；fast-gate 步骤序：单元测试 → 装对拍依赖 → 互操作对拍 → Lint。

## §3 门禁读数（结案前现跑，原样粘贴）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 460 份；分册登记 462 条；全量索引 462 条；最大 §462）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 582 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

## §4 过程缺陷与如实声明

- **联调首跑 14 例全红（环境装配缺陷，已处置）**：`run_lab.py` 未把服务端凭据转发给测试 JVM（服务端读
  `WEBDAV_*` / `MINIO_ROOT_*`，测试缺省回落随机口令）⇒ 首跑 401/403 全红。按 README 口径两侧注入同一组
  环境变量后全绿。**该缺口留作后续观察项**：run_lab.py 是否应自行导出凭据（本轮未改工具脚本）。
- **新用例首轮断言错误（自查缺陷，已修正）**：防回滚状态文件落点为「注入的状态目录本身 + `<sha256>.rollback` 后缀」，
  首轮误写为 `cacheDir/rollback` 子目录（那是生产装配的目录名约定，非守卫内部再建子目录）⇒ 2 例红、当场修正。
- **行数棘轮两次触线（已收口）**：结果链穿信号方案令 `RealVaultRepository` 501 行触 tier1（§461 已记录）；
  本批首轮 `DatabaseSession` 400 / `UnlockViewModel` 402 入 tier2（37 > 35），改「单行门面 + 注释内联 +
  internal 暴露会话（0 行）」压回 399 / 399，tier2 回到预算 35。
- **§462 归档脚本把 `ACTIVE_ISSUES.md` 切坏并随 `0dfb72b7` 入库（批次 463 归档时发现并修复）**：
  §462 的「摘除最后一条目」分支在找不到后继标题时回落到 `s[0:]`，把整份文件复制到切点之后
  （头部与 P0/P1/P2 段整段重复、P3-523 错位到 P2 区）。本批已从 §461 干净提交（`455011d4`）
  重建正确终态（P0/P1/P2 各 0 项、P3 仅存 `P3-523`，61 行）并复跑链接与索引自检。
  **教训**：批量摘除条目的脚本必须以「文件尾」为显式终止条件，且每次归档后须结构 grep 复核，
  不能只看脚本自身退出码。§460 / §461 的同类脚本未触发该分支纯属「被摘除条不是最后一条」的偶然。
- **设备侧/真机**：本批未改 `*/src/androidTest/**` 与原生面，LIVE 用例为 JVM 层；**恢复入口的真机 UI 走查
  （损坏库 + .bak 在场的实际呈现与点击链路）未做**，由 `ISSUE-P3-523` 的同一真机走查窗口一并覆盖为宜。
- **CI 未实跑**：见 §1 之 P2-519 如实声明。
