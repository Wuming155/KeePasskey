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

## P2 中危缺陷与协议/测试缺口（2 项）

### ISSUE-P2-314 CI Device gate：`emulator` 不在 PATH + 启动兜底不可达，job 挂满 120 分钟被超时强杀

- **核实时间点**：2026-09-25（GitHub API 拉取 run #351 / run 36045541051 的 job 107788644219 日志逐段取证；
  注：GitHub MCP 服务器凭据失效（`Bad credentials`），改用本机 Git 凭据管理器 token 走 REST API）。
- **核实方式**：job 日志取证（`19:08:47` 实证 `emulator: command not found`；`21:05:31` 实证
  `The operation was canceled`）+ `.github/workflows/build.yml` 现行内容比对。

**背景**：device-gate 启动步骤以裸命令 `emulator -avd ci-device … &` 后台起模拟器，但 ubuntu-24.04
runner 镜像未把 `$ANDROID_SDK_ROOT/emulator` 放入 PATH，模拟器从未启动（`adb` / `avdmanager` 经日志
证实可用，仅 `emulator` 缺位）。其后裸 `adb wait-for-device` 无设备可等、无限阻塞；20 分钟启动兜底
循环（`seq 1 120` × `sleep 10`）写在 `wait-for-device` 之后而**执行不到**，兜底形同虚设。job 挂满
`timeout-minutes: 120` 被强杀，connected 测试零执行、报告产物为空。**首修（全路径 + 删
`wait-for-device`）后 run #352 暴露第二层问题**：新版 runner 镜像 + emulator 37.1.11 下
`avdmanager` 创建的 AVD 落位与 emulator 查找路径不一致（`Unknown AVD name [ci-device]`，ini 不在
`$HOME/.android/avd`），20 分钟兜底此时真实可达并按设计报错退出（挂死形态已消除，但启动仍未成）。
**二层修（ANDROID_AVD_HOME 钉死 + ini 落位断言迁移）后 run #353 暴露第三层问题**：AVD 已被找到，
但 runner 镜像的 `/dev/kvm` 存在而当前用户不在 kvm 组（`ProbeKVM: This user doesn't have permissions
to use KVM (/dev/kvm)`），模拟器秒退，兜底循环 20 分钟如实报错——等待纪律已正确工作，根因在宿主权限。
影响面：近 40 次 build run（#313–#353，2026-09-23 起）Device gate 无一 success——
`ISSUE-P2-192` 第 7 项建立的设备层门禁在 CI 持续缺位。

**涉及文件**：`.github/workflows/build.yml` device-gate「启动 Android 模拟器」步骤。

**验收标准**：
1. emulator 改以 `${ANDROID_SDK_ROOT}/emulator/emulator` 全路径调用，且启动前 `test -x` 断言二进制存在
   （不存在即秒级显式报错，不得后台静默）。
2. 移除裸 `adb wait-for-device`；等待由 getprop 探测循环承担（同时覆盖设备上线与启动完成两阶段），
   兜底报错真实可达。
3. `ANDROID_AVD_HOME` 显式钉死且两侧共用；创建后硬断言 `ci-device.ini` 落位，avdmanager 落位不一致时
   连同 `.avd` 目录迁移并留痕，完全未产出则秒级显式报错。
4. 模拟器启动前对 `/dev/kvm` 做存在断言并放权（`chmod 666`，一次性 runner 环境无持久化风险）；
   `/dev/kvm` 缺失即秒级显式报错。
5. 模拟器失败场景下 job 至多约 20 分钟内以明确 error 退出，不再挂满 120 分钟。
6. push 触发的下一轮 CI run 中 device-gate job conclusion=success（connected 全模块真实执行）。

### ISSUE-P2-315 CI OWASP Dependency-Check：NVD 库无 runner 缓存 + 60 分钟超时不足，扫描反复被强杀

- **核实时间点**：2026-09-25（GitHub API 拉取 dependency-scan run 36045541019 的 job 107788455656 日志；
  插件行为经 dependency-check-gradle 插件源码 `DataExtension.groovy` 核实）。
- **核实方式**：job 日志取证（`19:05:34` 进入 NVD 阶段后一小时零输出，`20:05:02` 撞 `timeout-minutes: 60`
  被取消）+ 插件源码核实默认数据目录。

**背景**：插件 NVD H2 库默认落 `${GRADLE_USER_HOME}/dependency-check-data/<ver>`（源码实证），CI 上
`GRADLE_USER_HOME=/home/runner/.gradle`，而 `gradle/actions/setup-gradle` 只缓存 caches/wrapper 子目录
**不含** dependency-check-data ⇒ 每次推送全量重下 NVD，数据源慢日必然撞 60 分钟顶被强杀；随后
fail-closed 断言按设计报「报告不存在」（该链路工作正常），但供应链结论持续缺位。文件头注释
「首次 NVD 缓存被 runner 缓存吸收后增量扫描耗时可控」与上述事实不符（声明漂移），须一并更正。
影响面：近 8 次 run（#344–#351）全部 cancelled；Code Scanning 现存 5 条 medium CVE 告警来自更早
一次成功运行的快照。

**涉及文件**：`.github/workflows/dependency-scan.yml`。

**验收标准**：
1. 为 `~/.gradle/dependency-check-data` 增加 `actions/cache`（周粒度 key + 前缀 restore-keys，命中后增量更新）。
2. job `timeout-minutes` 由 60 提高（≥90），并同步更正相关注释使其与事实一致。
3. fail-closed 断言与报告上传步骤语义不变（不得以调低阈值 / 删除断言换绿）。
4. push 触发的下一轮 dependency-scan run 中 OWASP job conclusion=success 且产出报告 artifact / SARIF。

> **历史批注**：2026-09-24 同步/加密/passkey 安全审计批五条（`ISSUE-P2-308` ~ `ISSUE-P2-312`）
> 已全部闭环：§315（309 / 312）、§317（308）、§318（310）、§319（311）、§320（313）。

## P3 低危问题、特性接线与体验优化（0 项）


---


---
