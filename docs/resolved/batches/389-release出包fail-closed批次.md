# §389 release 出包 fail-closed 批次（`ISSUE-P3-422` 整条闭环）

> **触发**：2026-10-01 向用户讲解签名解析链现状时指出「签名未配置时 `assembleRelease` 静默产出未签名包」的口子，用户指示「那个口子解决一下」。
> **结案条目**：`ISSUE-P3-422`（整条闭环，同日登记同日结案）。

---

## 0. 原文登记

> **ISSUE-P3-422**：release 出包 fail-closed——签名未配置时中止构建，杜绝静默未签名 APK。
>
> - **状态（2026-10-01 用户指示，同时认领整改）**：进行中。
> - **核实时间点**：2026-10-01。
> - **核实方式**：向用户讲解解析链现状时指出：`hasReleaseSigning=false` 时（凭证管理器凭据缺失 / 环境变量未注入 / properties 缺口令行），`assembleRelease` 此前会**静默产出未签名 APK 且 BUILD SUCCESSFUL**（`app/build.gradle.kts` signingConfigs 块注释自述「AGP 照常产出未签名包，assembleRelease 不会因此失败」）——「构建成功」与「产物可分发」背离，误分发即翻车。
> - **整改方案**：`app/build.gradle.kts` 增加 taskGraph 就绪时的 fail-closed 判定：实际调度了 `:app` 的 `assemble*/bundle*Release` 产物任务且 `hasReleaseSigning=false` 时 `error(...)` 中止构建；`test` / `tasks` / `testReleaseUnitTest` 等不产分发产物的任务不受影响。同步修正 signingConfigs 块中「不会因此失败」的过时注释。
> - **涉及文件**：`app/build.gradle.kts`。
> - **验收标准**：AC① `:app:tasks` 与全量 `test` 不受影响（不误伤）；AC② 伪造签名缺失场景（`KEYSTORE_FILE` 指向不存在路径）下 `:app:assembleRelease` 构建中止且报错指明补救通道；AC③ 真实签名链路下 `:app:assembleRelease` 仍 BUILD SUCCESSFUL；AC④ 门禁 8/8。

---

## 1. 整改

| 层 | 变更 |
|---|---|
| `app/build.gradle.kts` | 新增 `gradle.taskGraph.whenReady` fail-closed 判定（置于 P2-55 断言块之后）：`hasReleaseSigning=false` 且实际调度了 `:app` 的 `assemble*/bundle*Release` 任务时 `error(...)` 中止，报错文本按优先级列明三通道补救路径并标注 `ISSUE-P3-422`；判定按**实际调度任务**而非配置期一刀切，`test` / `tasks` / `testReleaseUnitTest` / lint 等不产分发产物的任务零误伤；signingConfigs 块中「AGP 照常产出未签名包，assembleRelease 不会因此失败」的过时注释同步改写为 fail-closed 语义 |

**设计取舍**：判定放在 taskGraph 就绪而非配置期——release 变体的编译/单测/lint 日常照跑（它们不需要签名）；只有真正要产「可分发产物」的时刻才要求签名就绪，与「未配置签名＝出未签名包」旧语义的告别点被精确收窄到分发面。

---

## 2. 验证

- **AC①（不误伤）**：`.\gradlew.bat :app:tasks` **BUILD SUCCESSFUL**（8s）；全量 `test --rerun-tasks`（内含 `testReleaseUnitTest`）绿。
- **AC②（缺失场景中止）**：`KEYSTORE_FILE=/nonexistent/x.jks .\gradlew.bat :app:assembleRelease` → **BUILD FAILED in 3s**，报错原文：
  `release 签名未配置（hasReleaseSigning=false）却调度了 [assembleRelease]——拒绝产出未签名包。口令通道（按优先级）：Windows 凭证管理器（tools/signing/write-cred.ps1）/CI Secret 环境变量/keystore.properties；密钥文件路径见 storeFile（ISSUE-P3-422）。`
  （用环境变量伪造缺失而非动用户真实凭证管理器凭据。）
- **AC③（真实链路不回退）**：`.\gradlew.bat :app:assembleRelease` **BUILD SUCCESSFUL**（1m43s，增量 193 up-to-date）——真实签名链路照常出包。
- **AC④（全量回归）**：`.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**（114 tasks executed）；聚合 `xml=498 tests=3222 failures=0 errors=0 skipped=13`（基线一致）。

### 2.1 门禁读数（原样粘贴，归档后采集）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=36  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 387 份；分册登记 389 条；全量索引 389 条；最大 §389）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 556 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

---

## 3. 如实声明

1. AC② 用 `KEYSTORE_FILE` 指向不存在路径伪造「签名缺失」，未触碰用户真实凭证管理器凭据；Windows「空环境变量≈未设置」的语义边界因此绕开，不影响判定逻辑本身（判定只看 `hasReleaseSigning` 布尔值）。
2. `bundleRelease`（AAB）路径未实跑（本仓无 AAB 发布流程），其纳入判定为同构扩展，逻辑与 assembleRelease 同一过滤条件。
