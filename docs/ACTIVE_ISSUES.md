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

> 2026-09-25 CI 触发频率治理（`ISSUE-P3-316`）闭环见 §326。

### ISSUE-P3-318：CodeQL py/redos 两条高严重度告警未登记未处置——截图包装生成器 `PREVIEW_BLOCK` 正则指数回溯

- **优先级**：P3（开发者本机工具链脚本，输入源为本仓源码文件、非不可信运行时输入；真实可利用面极小，但属 CodeQL **high** 且 Security 面板计入徽标计数）。
- **核实时间点**：2026-09-25。
- **核实方式**：① `GET /repos/Wuming155/KeePasskey/code-scanning/alerts?state=open` 实测：#355 / #356（rule `py/redos`，CodeQL 2.27.1，security_severity_level **high**）创建于 2026-09-16，`most_recent_instance` 停留于 main 分支 commit `cd29e7ef`（当前 HEAD）——**每轮扫描仍在复检出现，非孤儿告警**；
  ② 两条告警的实例路径均为 `tools/export_previews/generate_screenshot_test_wrappers.py:30`，即 `PREVIEW_BLOCK` 的预览注解参数组 `r"(?:\((?:[^()\n]|\([^()\n]*\))*\))?"`——CodeQL 判其存在嵌套量词交替的指数回溯（「strings starting with `@Preview\n@` and containing many repetitions of `00\n@`」形态）；
  ③ 全仓 `grep` `docs/`（含 ACTIVE_ISSUES / RESOLVED_LOG / 已知工程限界 / records）确认该问题**从未登记**，亦无任何 dismiss 记录（`dismissed_at` 为空）。
- **背景**：该正则用于解析 `app/src/main/java` 下 Compose 预览函数声明，形如 `(?:A|B)*` 且 `B` 可整体匹配 `A` 前缀的嵌套回溯形态；实际输入是本仓受版本控制的源码、不含对抗性内容，故风险为理论面，但 CodeQL 高严重度条目长期挂账且零登记，违反「新问题即时补登」纪律（本条即为补登）。同批排查结论（本条无关但同一面板）：Security 徽标其余 5 条为 `CVE-2020-29582`（kotlin 工具链构件，CVSS 5.3 低于 7.0 硬阈值），系 `ISSUE-P2-219` / PD-25 明文裁决「低于阈值，如实保留可见」的**有意保留项**，不属未闭环缺陷。
- **整改方案**：① 将 `PREVIEW_BLOCK` 中预览注解参数组改写为线性时间等价形态（如展开嵌套组或利用 Python 3.11+ 原子组 `(?>...)`，消除「同一起点可被两条交替分支重复消费」的回溯结构），行为零变更——以 `promoted` / `wrappers` 计数与生成产物逐字节不变为证；② 复跑 `python tools/export_previews/generate_screenshot_test_wrappers.py` 与 `.\gradlew.bat :app:compileDebugScreenshotTestKotlin --rerun`；③ 不采用 dismiss：告警系真实回溯结构，修复后待下一轮 CodeQL 巡检自动判定 fixed。
- **验收标准**：① CodeQL 巡检（`.github/workflows/codeql.yml`，每周 + 手动）后 #355 / #356 状态转为 `fixed`（或闭环批次文档记录 API 读数证明已消除）；② 生成器输出 `promoted=79 wrappers=79 packages=17` 计数与改动前一致，截图测试包装编译门禁绿；③ 批次文档 §3 原样粘贴 `python tools/doc/gate_readings.py` 读数块。

### ISSUE-P3-317：CodeQL 默认设置未按 ISSUE-P3-57 前置条件停用，Security 面板双语言配置持续报错

- **优先级**：P3（面板红 / 运维残留；**不削弱实际有效覆盖**——rust / python / actions 由自管高级配置正常产出分析）。
- **核实时间点**：2026-09-25。
- **核实方式**：① Security → Code scanning 面板实测截图：`language:c-cpp` 与 `language:java-kotlin` 两配置均报
  「CodeQL exited with errors」+「No code scanning results」，last scan 为 2 周前（commit `6941b89b`）；
  ② `GET /repos/Wuming155/KeePasskey/code-scanning/analyses` 实测 2026-09-25 当日多条
  `.github/workflows/codeql.yml:analyze` 分析 `error` 为空——**自管高级配置工作流本身运行正常**；
  ③ `GET` / `PATCH /repos/.../code-scanning/default-setup` 实测 403（当前细粒度 PAT 无
  「Code scanning alerts」写权限），故停用动作无法经现令牌 API 化。
- **背景**：`ISSUE-P3-57`（§23.4，`docs/resolved/batches/23-*.md` 第 78 行）切换到 advanced setup 时立有
  **启用前置条件（人工运维动作）**：须在 Settings → Code security → Code scanning → CodeQL analysis →
  **Default setup → Disable** 关闭默认设置。该动作至今未完成，残留的默认设置对 java-kotlin / c-cpp
  做周期扫描——二者在本仓无真实构建支撑（Android/Gradle 与 C/C++ 工具链均未在默认设置中配置），
  恒以「构建失败 → 空分析（rules=0, results=0）」告终并令面板报错；且两套设置并存时，advanced 配置的
  分析上传会被 GitHub 拒绝处理、告警会被默认设置反复重新登记（`.github/workflows/codeql.yml` 头部注释）。
- **整改方案**（人工运维动作）：GitHub 网页 Settings → Code security and quality → Code scanning →
  CodeQL analysis → Default setup → **Disable**。若需代理代办：为 `GITHUB_TOKEN` 增授细粒度权限
  「Repository permissions → Code scanning alerts → Read and write」后，由代理执行
  `PATCH /repos/Wuming155/KeePasskey/code-scanning/default-setup`（body `{"state":"disabled"}`）。
- **验收标准**：① Security → Code scanning 不再出现默认设置（c-cpp / java-kotlin）的错误条目；
  ② `.github/workflows/codeql.yml` 每周巡检与手动触发仍正常上传 SARIF（analyses 列表 `error` 为空、
  `analysis_key` 仍为 `codeql.yml:analyze`）；③ 闭环批次文档记录停用后的面板或 API 读数。
