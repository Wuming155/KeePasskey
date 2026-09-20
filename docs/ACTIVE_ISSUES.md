# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P1 高危与核心功能问题（1 项）

### ISSUE-P1-216：CI 上通行密钥并发签名计数器用例偶发失败——并发路径触达**已清零的 `ProtectedString`**（本地绿 / CI 红，三次推送中两次复现）

- **背景与证据**（核实时间点 2026-09-20；核实方式：GitHub REST API 拉取 Actions job 日志逐字读取 +
  本机 Gradle **实跑对照**；本次经 MCP 通道取数失败——服务器报 `Bad credentials`，其 `${REPOGATE_TOKEN}`
  在 npx 子进程未生效，故改走 REST）：
  - run `35481098419`（`b8bfe09`，job `105998937680`）：`PasskeyEntryCoordinatorSignCountTest > 32 路并发递增返回的计数器互不相同且严格递增 FAILED`，
    堆栈为 `java.lang.IllegalStateException at ProtectedString.kt:173`，`1312 tests completed, 1 failed`；
  - run `35451485390`（`3a45a79`，job `105919300337`）：**同文件 3 例**失败（另含
    `32 路并发递增经既有无返回值入口同样不丢失任何一次递增`、
    `以锁外快照自行计算计数器会产生重复值_断言路径必须使用协调器返回值`），`1303 tests completed, 3 failed`；
  - run `35483557956`（`1e03a46`）：Fast gate 停在 `Android Lint`，**单测未复现** ⇒ 判为**偶发**（非恒定红）。
- **本地对照（不得据此结案）**：2026-09-20 本机
  `.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.data.repository.PasskeyEntryCoordinatorSignCountTest" --rerun-tasks`
  **BUILD SUCCESSFUL**（该类整类通过）⇒ 现象为 **CI 专属**，与宿主调度/负载相关。
- **判据含义**：`ProtectedString.kt:173` 即 `checkNotCleared()` 的
  `check(!isCleared) { "ProtectedString 已经清零，禁止继续访问" }`——断言打印形态说明并发路径上
  **确有实例在被读取前已清零**（而不是"值算错"）。相邻改动面为 §222（`ISSUE-P3-212` 签名私钥擦除单点化：
  `signAssertionConsumingKey` 的 `finally` 无条件清零、活动层私钥副本清理面删除）与
  §119 / §157（计数器补丁改走 `DatabaseSession.updateEntryById` 的单条条目原子变换 + **增量定点擦除**，
  其 AC 明写"旧计数器实例必清零、同条目存活密文与未命中兄弟分组必留"）。
- **边界（如实声明）**：CI 侧 Gradle 控制台**只打印一行**堆栈，**尚未**取到完整堆栈，
  故**未**定位到具体断言行与具体实例、亦**未**判定是"擦除误伤并发读取"还是"测试对环境敏感"；
  不得据此断言"生产必崩"，也**不得**以"重跑变绿"结案。
- **涉及文件**：
  - `app/src/test/java/com/keepasskey/app/data/repository/PasskeyEntryCoordinatorSignCountTest.kt`
  - `core/src/main/java/com/keepasskey/core/security/ProtectedString.kt`
  - 待定位的生产面：`PasskeyEntryCoordinator` / `DatabaseSession.updateEntryById` 的定点擦除路径
- **验收标准 (AC)**：
  1. 从 CI artifact `fast-gate-reports`（`**/build/reports/tests/**` 下的 `TEST-*.xml`）取出**完整堆栈**，
     定位到具体断言行与涉及实例（旧计数器实例 / 活动条目密文 / 兄弟分组三者中的哪一个被清零）；
  2. 给出**机制性**根因；无论结论是"擦除面误伤"还是"用例时序假设不成立"，都须落到**机制性修复或断言口径显式收敛**，
     不得只保留"偶发、重跑即绿"的状态；
  3. 使该竞态在宿主侧**可稳定复现**（提高并发度 / 注入调度点 / 断言共享状态转移），以此证明修复有效；
  4. `.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿，且修复后 CI 连续 ≥3 次 push 不再出现该类失败。

---

## P2 中危缺陷与协议/测试缺口（3 项）

### ISSUE-P2-217：fast-gate 的 `Android Lint` 步骤无判定力——`lint` 触达 `:crypto:cargoNdkBuild`，而该 job 未装 cargo-ndk

- **背景与证据**（核实时间点 2026-09-20；核实方式：GitHub REST 拉取 Actions job 日志 +
  逐行回读 `.github/workflows/build.yml`）：
  - run `35449197609` 的 Fast gate job `105913289558`：`> Task :crypto:cargoNdkBuild FAILED`、
    `error: no such command: ndk`（cargo-ndk 子命令缺失）、`BUILD FAILED in 2s`；
  - 最新 run `35483557956`（`1e03a46`）的 Fast gate **仍停在 `Android Lint`** 步骤 ⇒ 非一次性现象；
  - `.github/workflows/build.yml:64-90`（fast-gate 段）：环境步骤只有 checkout / `wrapper-validation` /
    JDK 21 / `setup-gradle`，随后 `./gradlew test` 与 `./gradlew lint`——**无** Set up Android SDK、
    **无** `cargo install cargo-ndk`（该安装只在 native-gate `:156` 与 device-gate `:453`），
    故 runner 上 `cargo ndk` 子命令**不存在**，而 `lint` 会触达 `:crypto:cargoNdkBuild`。
- **影响**：`Android Lint` 步骤在能产出真实 Lint 结论**之前**即失败——该门禁自 9/17 起长期无判定，
  "lint 214 持平"这类口径只能由本机 `:app:lintDebug` 支撑，CI 侧不构成复核。
- **边界**：本项**不等于**"Lint 零告警"，也不**豁免** Lint 结论本身；它只主张"CI 该步骤必须能给出
  `lint` 任务自身的成功/失败判定"。
- **涉及文件**：`.github/workflows/build.yml`（fast-gate 段；NDK / cargo-ndk 安装写法可对齐 native-gate `:126-157`）；
  `crypto/build.gradle.kts`（`cargoNdkBuild` 任务定义）。
- **验收标准 (AC)**：
  1. 让 fast-gate 具备产出真实 Lint 结论的条件，二选一：① 补装**固定版本** NDK + cargo-ndk（与 native-gate 同口径）；
     ② 以**显式且 fail-closed** 的方式使 `lint` 不依赖 `cargoNdkBuild`——**严禁** `|| true` / `continue-on-error`
     之类吞掉失败的写法；
  2. CI 实跑一次，`Android Lint` 步骤给出的是 `lint` 任务自身判定（成功或真实 Lint 失败），而非工具缺失失败；
  3. 留痕本次 run URL 与 `grep -cE "^ *<issue$" app/build/reports/lint-results-debug.xml` 的计数（口径见 `AGENTS.md` §5）。

### ISSUE-P2-218：native-gate / device-gate 的 `Set up Android SDK` 失败——setup-android（v4.0.1）安装远端已下架的 `tools` 包

- **背景与证据**（核实时间点 2026-09-20；核实方式：GitHub REST 拉取 job 日志 + 与
  `docs/records/ci-静态校准记录.md` 的既有核实基线对照）：
  - run `35481098419` 的 job `105998937765`（Native gate）与 `105998937796`（Device gate）日志：
    `Warning: Failed to find package 'tools'` →
    `Error: The process '/usr/local/lib/android/sdk/cmdline-tools/20.0/bin/sdkmanager' failed with exit code 1`；
  - run `35483557956`（最新）两 job 的失败步骤**仍是** `Set up Android SDK` ⇒ 恒红，非偶发；
  - `.github/workflows/build.yml:124` 与 `:428` 两处 `android-actions/setup-android@40fd30fb…  # v4.0.1`；
  - **旁证（口径已过期）**：`docs/records/ci-静态校准记录.md:42-45` 的用法核实基线是 **v3**（`9fc6c4e9…`，
    默认 `packages: 'tools platform-tools'`）——当前 workflow 已升至 v4.0.1，而 `tools` 包在远端 SDK 仓库
    已不存在，属**环境漂移**：action 默认包清单与本仓钉死版本未同步。
- **影响**：`Native gate`（4 ABI 交叉编译 + `assembleDebug/Release` + 签名断言）与 `Device gate`
  （模拟器 connected 测试）**整体不可达**——B 面设备侧与原生面门禁自 2026-09-17 起持续**无判定**
  （与 PD-04 的"检测而非阻断"口径无关：本项影响的是门禁**能否运行**）。
- **边界**：不得用"反正本地跑过"替代；也不得读取作"已受保护/未受保护"（`产品裁决登记.md` PD-04 已另行裁决）。
- **涉及文件**：`.github/workflows/build.yml:124`、`:428`；`docs/records/ci-静态校准记录.md`（v3 基线需同步更正）。
- **验收标准 (AC)**：
  1. 两处 setup-android 调用在 runner 上不再因 `tools` 包缺失失败，三选一并说明理由：① 钉回 v3 的已核实 SHA；
     ② 显式传 `packages:`（不含 `tools`）；③ 去掉该 action，改手工安装并钉死 cmdline-tools / platform 版本；
  2. 通过后 `native-gate` 与 `device-gate` 至少各跑完一次并给出 job 结论（若仍失败，须是**真实业务失败步骤**，
     而非环境安装步骤）；
  3. `docs/records/ci-静态校准记录.md` 中以 v3 为前提的结论**就地更正**（或显式标注"基线已过期"），
     避免后续维护者据陈旧基线判断。

### ISSUE-P2-219：依赖闸门长期红——948 个未豁免（CVE × 构件）达 CVSS ≥ 7.0，Code Scanning 积压 1318 条 open 告警（主体为工具链侧 netty）

- **背景与证据**（核实时间点 2026-09-20；核实方式：GitHub REST 拉取 dependency-scan job 日志 +
  Code Scanning API 分页翻全（14 页）+ 本机 Gradle `dependencyInsight` 实测）：
  - run `35481098430` 的 job `105998937573` 日志：`已扫描报告 1 份；漏洞实例 1316 条；达阈（CVSS ≥ 7.0）实例 948 条，
    去重后（CVE × 构件）948 条` + `##[error]CVSS 闸门未通过：948 个未豁免的（CVE × 构件）组合达到 CVSS ≥ 7.0`；
  - Code Scanning（`state=open`）：**1318 条**（14 页翻完，末页 18 条）、**63 个不同 CVE**
    （critical 122 / high 614 / medium 421 / low 42），`tool=dependency-check` 100%，`created_at` 起于 2026-09-16；
  - 构件分布：`netty-*@4.1.93.Final` 与 `netty-*@4.1.110.Final` 两批（各构件 57–60 条）为主，
    另有 `grpc-netty-1.57.2`（CVE-2023-44487）、`kotlin-stdlib(-common)-1.9.0`（CVE-2026-53914，CVSS 9.8）、
    `protos-32.2.1`、`protobuf-java-util-3.22.3`；
  - **承载面核实**：本机 Gradle `dependencyInsight --dependency netty` 实测
    `:app:releaseRuntimeClasspath` 与 `:app:debugRuntimeClasspath` **均不含 netty**；CI 报告把该漏洞清单
    挂在 root project `KeePasskey` 名下 ⇒ 属**构建 / 工具链侧**依赖图（init 脚本的插件 classpath、UTP 等），
    而 `skipConfigurations`（`.github/dependency-check.init.gradle.kts:78-84`）仅跳过 androidTest 与 lint 配置；
    源码内**无** netty 直接声明（`*.kts` / `*.toml` 全仓 grep 零命中）。
  - 次生现象：dependency-scan 自 2026-09-17 起每次 push **皆红**（连续 100+ 次 run）——按 PD-04 不阻断推送/合并，
    但"长期红且零处置"使该闸门**无信息量**。
- **纪律边界（不得突破）**：不得回调 `failBuildOnCVSS`、不得删除 CVSS 硬断言步骤
  （`.github/workflows/dependency-scan.yml:87-88` 已立规）；豁免**唯一**通道是
  `.github/owasp-dependency-suppressions.xml`，且每条 `<suppress>` 必须带 `<notes>`（核实人 / 方式 / 归属）、
  不得通配（`SupplyChainSuppressionPolicyTest` 守卫）。
- **涉及文件**：`.github/dependency-check.init.gradle.kts`、`.github/workflows/dependency-scan.yml`、
  `.github/owasp-dependency-suppressions.xml`、`.github/check_dependency_cvss.py`；若升级可控依赖另涉各 `build.gradle.kts`。
- **验收标准 (AC)**：
  1. 逐条定位 948 条的**承载配置与引入者**（`dependencyCheckAggregate` 的 root 汇总输出 + `dependencyInsight`），
     把清单切分为「本仓可控（可升级 / 可排除）」与「工具链侧不可控」两类，逐类给出计数；
  2. 可控者**升级或排除**（如统一 netty 版本、屏蔽 grpc-netty 传递）；不可控者按白名单纪律**逐条**豁免并写明核实依据
     （**不得**通配豁免、不得以"工具链噪声"一条打尽）；
  3. 处置后闸门转绿：本地等价判据 `python .github/check_dependency_cvss.py <report.json>` 退出码 0，
     且重跑后 Code Scanning 的 open 告警计数相应下降（留痕前后计数）；
  4. 若确有若干条判定为"不可控且不宜豁免"，**必须**在 [`docs/architecture/已知工程限界.md`](architecture/已知工程限界.md)
     或 [`docs/architecture/产品裁决登记.md`](architecture/产品裁决登记.md) 登记口径后再从闸门移除，
     **严禁**以放宽阈值或删除断言了事。

> **历史 P2 条目**（§219 闭环的 `ISSUE-P2-199` / `ISSUE-P2-200` / `ISSUE-P2-208`，§220 闭环的
> `ISSUE-P2-210` / `ISSUE-P2-211`，§223 闭环的 `ISSUE-P2-212`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（历史 P3 条目——含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，以及 §224 闭环的 `ISSUE-P3-215`——的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)）。
