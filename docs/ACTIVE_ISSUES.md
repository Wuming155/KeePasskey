# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。
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

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-24 同步/加密/passkey 安全审计批五条（`ISSUE-P2-308` ~ `ISSUE-P2-312`）
> 已全部闭环：§315（309 / 312）、§317（308）、§318（310）、§319（311）、§320（313）；
> 2026-09-25 CI 设备门禁与供应链扫描两条（`ISSUE-P2-314` / `ISSUE-P2-315`）闭环见 §325。

## P3 低危问题、特性接线与体验优化（2 项）

### ISSUE-P3-321 GitHub Code Scanning 5 条 CVE-2020-29582 陈旧告警未随 PD-25 扫描面收口闭合

- **优先级**：P3（告警定位面均已不入扫描面，陈旧状态未闭合；非生产面缺陷）。
- **核实时间点**：2026-09-25（`gh api /code-scanning/alerts` 枚举 + 本地 `dependencies --all` 全模块全配置反查）。
- **核实方式**：① Code Scanning 面板 5 条 open（#46 ~ #50）全部为 dependency-check 的
  CVE-2020-29582（Kotlin < 1.4.21 临时文件/目录创建权限缺陷），告警打开于 2026-09-11；命中的
  purl/定位文件为 `kotlin-reflect@1.6.10` 与 `kotlin-stdlib-jdk7/jdk8@1.8.x`——版本**均高于修复版
  1.4.21**，且定位文件全部位于 runner 的 Gradle 缓存（AGP 工具链配置族解析的构件）；
  ② `ISSUE-P2-219`（2026-09-20，裁决 `PD-25`）已将该 AGP 工具链配置族移出 dependency-check 扫描面，
  但收口后 `dependency-scan.yml` **从未重跑**（Actions 运行历史仅剩 CodeQL 3 次手动 dispatch），
  故 GitHub 侧告警维持陈旧 open（§329 也仅旁证「维持原状」）；③ 2026-09-25 本地 5 模块
  `./gradlew dependencies --all` 反查：`kotlin-reflect` 与 `kotlin-stdlib-jdk7/jdk8` 在全部可解析
  配置中**零命中**——构件已不在扫描面内。
- **处置（2026-09-25 重扫实况后就地修正）**：首次重扫（run 36123416214，10:22:58Z SARIF）**推翻了本条目
  原前提**「构件已不入扫描面、重扫即自动 fixed」——报告（`dependency-check-report.json`）实况：
  ① CVE-2020-29582 仍 ×5：`kotlin-reflect@1.6.10` 经 `hilt-compiler@2.60.1` / `kotlin-build-tools-impl`
  承载于 `kotlinCompilerClasspath` / `kspClasspath` / `hiltAnnotationProcessor` 等**编译器工具链配置**
  （PD-25 收口的 AGP 工具链族之外）；`kotlin-stdlib-jdk7/jdk8@1.8.x` 另经 `debugRuntimeClasspath`
  （androidx 构件以旧 Kotlin 工具链构建、POM 声明 stdlib-jdk8 1.8.22 传递）。这些构件版本全部
  **≥ 修复版 1.4.21**，命中的根因是 NVD 对该 CVE 的 CPE 上界写成 `versionEndExcluding: 2.1.0`
  （与其自身描述「before 1.4.21」矛盾，2026-09-25 NVD API 实查）——**版本不可达误报**；
  ② 同次重扫新增 2 组 CPE 名称碰撞误报（4 条，来自 §327 扫码栈迁移引入的 `androidx.camera`
  生产依赖）：`camera-lifecycle@1.6.2` × `CVE-2007-4234`（受影响产品为 **Camera Life** PHP 相册
  Web 应用，MITRE 记录无 CPE 数据）与 `camera-video@1.6.2` × `CVE-2015-3362`（**Drupal Video
  模块**，CPE `video_project:video`）——语义零关联。
  依据 ISSUE-P3-32 先例（**不扩** PD-25 skip 名单，边界②禁止），处置改为：
  **人工核实后写入 `.github/owasp-dependency-suppressions.xml`**（kotlin 三构件 × CVE-2020-29582、
  camera 两构件 × 两 CVE，逐条附核实依据与范围护栏）→ 再次重扫 → GitHub 对不再产出的告警自动
  `state=fixed`。
- **AC（按修正后处置重写）**：
  1. suppression 两条入库（XML 形态与 ISSUE-P3-32 已过检条目同构：notes + packageUrl regex + cve），
     CI 重扫 run 成功且 `check_dependency_cvss.py` 硬断言通过；
  2. 5 条 `CVE-2020-29582` 告警 state 全部变为 `fixed`（API 读数回填批次文档）；
  3. 4 条 camera 误配告警（#1667 ~ #1670）同样转 `fixed`；
  4. 重扫后 open 告警集合清零（无 medium 及以上新增）。

### ISSUE-P3-322 Dependabot 积压 3 个 PR（#8 / #9 / #10）按登记口径验证合并

- **优先级**：P3（依赖健康巡检积压；均非安全紧急项）。
- **核实时间点**：2026-09-25（`gh pr list --state open` 枚举 + 逐 PR diff 审阅）。
- **核实方式**：三个 PR 开于 2026-09-18，因 `ISSUE-P3-316` 后 `build.yml` 不再对 PR 触发 CI，
  积压无检查读数；逐 PR 内容：
  - **#10** github-actions-all 组 4 项：`actions/setup-java` v6.0.0→v6.0.1、
    `github/codeql-action` v4.37.9→v4.38.0（patch/minor，CI 侧运行自证）；
  - **#9** material3 `1.5.0-alpha27`→`1.5.0-alpha28`：属 `ISSUE-P3-09`（ZT-20）AC3 登记的
    「本值仅允许在 1.5.0-alphaN 内部随安全修复上调，禁止跨 minor 跳跃」**允许范围**
    （不跨 minor、不退出 alpha 渠道；裁决注释核对 maven-metadata 时点 latest 即 alpha28）；
  - **#8** gradle-minor-patch 组 6 项：AGP `9.2.1`→`9.4.1`（minor×2 跳跃，最大风险面——须验证
    与 Gradle 9.7.1 / compileSdk 37 兼容）、KSP `2.3.11`→`2.3.12`、Compose BOM
    `2026.08.00`→`2026.09.00`、navigation-compose `2.10.0`→`2.10.1`、bouncycastle `1.85.2`→`1.86`。
- **处置**：按 #10 → #9 → #8 顺序 squash 合并（#8/#9 同文件不同 hunk，顺序合并不冲突）；
  合并后拉取 main 跑全量宿主验证。AGP 9.4.1 若引入**新的** AGP 工具链配置名，按 `PD-25`
  口径处置（先落入分类口径再补 skip 名单与 `SupplyChainScanSurfaceTest` 清单，不得静默放宽）。
- **AC**：
  1. 3 个 PR 全部 squash 合并、分支清理；
  2. 合并后 main `.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿（含
     `SupplyChainScanSurfaceTest` 对 AGP 9.4.1 工具链面的 fail-closed 验证）；
  3. `python tools/doc/gate_readings.py` 7/7 PASS（读数块原样贴入批次文档）；
  4. `.\gradlew.bat assembleDebug` 编译全模块通过（AGP 9.4.1 兼容性实证）。
