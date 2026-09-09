# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

> **实时状态与任务唯一看板**：详见 [**docs/STATUS.md**](docs/STATUS.md)。
> 所有未完成任务、当前版本基线、历史改动索引与已完成发现项跟踪均以 `STATUS.md` 为单一真相源（Single Source of Truth）。

---

## 项目概述

KeePasskey 是一款使用原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 `.kdbx`（v4）格式，内置 WebDAV 与 S3 兼容协议同步；以 Android 16+（API 36+）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

---

## 硬约束（任何操作都必须严格遵守）

1. **模块依赖严格单向**，禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
3. **参考项目只读与文档优先铁律（禁止盲目翻看源码）**：
   - 严禁对 `参考项目/` 源码目录执行无目标的全局 `grep`、`glob` 或大面积扫源码；
   - 5 个参考项目均已完成详尽的架构分析，集中存放在 **`docs/references/`**；
   - **参考项目优先级层级（严格遵照执行）**：
     1. 🥇 **核心参考**：**KeePassDX** — 与本项目技术栈最贴近（Android 原生 Kotlin），优先参考其 `database`/`crypto` 领域模型、`DatabaseSession` 生命周期与 WebAuthn/Passkey；
     2. 🥈 **次核心参考**：**keepass2android** — 重点参考云同步架构（WebDAV/S3 适配）、文件存储抽象（`IFileStorage`）、本地缓存机制与冲突合并；
     3. ⚖️ **标准实现参考（格式与协议裁决者）**：**KeePass-2.61.1 官方 C#** — `.kdbx` 格式（v4）事实标准，仅在文件格式细节、二进制 Header 字段、加密管线或 XML 树结构歧义时作终极裁决；
     4. ⚖️ **算法级参考**：**KeePassXC (C++/Qt)** — `Merger` 条目级合并与墓碑复活规则是 `KdbxMerger` 的直接算法参考，`KPEX_PASSKEY_*` 属性 schema 对照 `PasskeyData` 设计；
     5. 🥉 **辅助参考（不做重点）**：**Monica** — 仅作现代 Compose UI/UX 与本地优先思路的补充对照；
   - **凡涉及实现思路借鉴，必须先读对应架构分析文档，严禁直接翻原始代码**；
   - 严禁修改 `参考项目/` 下任何文件，严禁复制其代码入库（许可证约束）。
4. **工程规则**（单一职责与巨型类阈值、魔法数字、依赖倒置、 Result 错误处理等）详见 `.codebuddy/rules/engineering-rules.md`，写代码前必须遵守。
5. **单一真相源维护纪律（实时、增量）**：**任何任务状态变更、阶段完成、或新发现问题，都必须即时回写 `docs/STATUS.md`**，保持看板实时准确：
   - **增量更新**：计划/批次未全部完成、仅完成某个阶段或里程碑，也须立即更新 STATUS 与相关文档，**不攒到全部做完才写**；
   - **新问题必登记**：工作中发现的任何缺陷 / 风险 / 待办（**即使暂不整改**），必须写入对应文档——可行动任务登记为 `STATUS.md §2` 新 TASK；属代码审计类发现补登 `FINDINGS_TRACKER.md`；**严禁只记在聊天或脑中而不同步到文档**。
6. **改动提交纪律**：每次完成一组相关修改、且 `.\gradlew.bat test` 通过后，须及时**提交并推送到 GitHub 远端**——`git commit` 后**立即 `git push`**（除非因网络原因推送失败，可暂缓并在 STATUS/提交信息中记录，待网络恢复后立即补推）。提交信息遵循本仓库约定：以 `TASK-xx` / `批次 X` 引用任务并简述主题，例如 `fix(TASK-22): 修复旋转屏幕时 AutoLock 单例被销毁`；**勿让改动长时间堆积在工作区**，避免丢失或与后续提交混淆。
7. **计划文档生命周期纪律**：新建方案前先查 `docs/plans/` 等目录有无过期计划（**当前在库方案：`docs/plans/REPAIR_PLAN.md`，承载 TASK-02 / 19 / 43 / 48~49 的执行细节**）；方案落地或被 `STATUS.md` 看板吸收后，**须实时回写 `STATUS.md` 并删除已冗余的独立计划文件**，杜绝与 SSOT 多头并存。仍承载执行细节（范围 / 依据 / 风险 / 验收）的方案（如 `HEALTH_CHECK_ROADMAP.md`）可保留，但其「完成状态」一律以 `STATUS.md §2` 看板为准，**不在计划文件内重复维护状态**。
8. **问题闭环流程（发现 → 登记 → 整改+验证 → 更新记录并推送）**：任何改动须走完以下闭环，缺一环不算完成：
   1. **发现**：工作中识别到缺陷 / 风险 / 待办（含审计发现）；
   2. **登记**：即时写入 SSOT——可行动任务登 `STATUS.md §2` 新 TASK，审计类发现补登 `FINDINGS_TRACKER.md`；暂不整改也须登记，**严禁只记聊天或脑中**；
   3. **整改 + 验证**：修复代码，且 `.\gradlew.bat test` 全绿（含相关回归用例）方准入库；
   4. **更新记录并推送**：状态变更即时回写 STATUS（标记完成），与代码**同一次 `git commit`**，并**立即 `git push`**；审计类任务回写 `FINDINGS` 代码证据；计划被吸收后按 #7 删冗余文件。
   > 文档与代码须**原子提交**，确保看板与实现永不失联。
9. **跨文档口径一致性纪律**：同一事实在多处出现时（版本基线、测试例数、发现项总数、看板项数、功能勾选状态），**一律以 `STATUS.md` §1/§2 为准**；基线或任务状态变更后，须同步刷新 `AGENTS.md`（构建命令 / 文档索引）、`README.md`（读者向入口：特性亮点 / 构建运行 / 基本使用）、`STATUS.md` §5 功能清单与 §6 已知局限、`ARCHITECTURE.md` §6 技术栈与模块目录、`FINDINGS_TRACKER.md`（物理状态与代码证据），杜绝同一事实多头失真。**看板表按 TASK ID 升序维护，不得残留同 ID 的失效副本**。

---

## 详细文档索引

> **文档存放约定**：所有项目文档统一归档于 `docs/`。仅 `.codebuddy/rules/engineering-rules.md` 为 **Agent 功能配置**（工程规则），由系统自动加载、须保留在原位；原 `.codebuddy/skills/` 下的参考项目分析实为文档，已移入 `docs/`（`docs/reference-projects.md`、`docs/references/`）。架构指南（`architecture-guide.md`）因与 `docs/ARCHITECTURE.md` 内容重叠，已删除。

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| [**docs/STATUS.md**](docs/STATUS.md) | **单一真相源**：当前版本基线、未完成任务唯一看板（53 项：49 ✅ / 2 📋 / 2 ❌）、历史提交日志索引 | **开始任何工作前、检查进度时** |
| [**docs/FINDINGS_TRACKER.md**](docs/FINDINGS_TRACKER.md) | **历史审查发现跟踪表**：125 项发现的物理核对状态与代码证据 | **确认历史 Bug 是否已修时** |
| [**docs/HEALTH_CHECK_ROADMAP.md**](docs/HEALTH_CHECK_ROADMAP.md) | **体检批次落地规划**：批次 A–H 的官方依据、范围与验收（对应 TASK-01/03~07） | **执行体检批次整改时** |
| [**docs/plans/REPAIR_PLAN.md**](docs/plans/REPAIR_PLAN.md) | **残余任务执行方案**：TASK-02（真机回归）/ 19（zxing 评估）/ 43（进阶偏好消费方，拆 43a–43f）/ 48~49 的范围 · 依据 · 风险 · 验收 | **动手实现残余任务前** |
| [**docs/ARCHITECTURE.md**](docs/ARCHITECTURE.md) | 模块依赖拓扑、关键架构决策与目录约定（原根目录 `ARCHITECTURE.md`） | **跨模块改动、新增功能落位前** |
| [**docs/reference-projects.md**](docs/reference-projects.md) | 参考项目地图：各功能应参照哪个项目的哪些文件、阶段对照（原 `skills/reference-projects.md`，已归档） | **实现算法/格式兼容时** |
| [**docs/references/**](docs/references/) | 5 个参考项目架构分析（KeePassDX / keepass2android / KeePass-2.61.1 / KeePassXC / Monica） | **实现思路借鉴前** |
| [**docs/KDBX4与复合密钥实战互操作排查日志.md**](docs/KDBX4与复合密钥实战互操作排查日志.md) | KDBX4 + 复合密钥（密码+KeyFile）真机互操作排查记录 | **排查 KDBX 解析/密钥兼容性时** |
| `.codebuddy/rules/engineering-rules.md` | **工程规则（功能配置，保留原位）**：单一职责、敏感数据、Compose 规范、原子写盘、协程调度、防御性安全 | **编写/修改任何代码前** |

> 另有 `tools/local-sync/README.md` 为本地同步联调工具的使用说明，随工具保留在 `tools/` 目录，未纳入 `docs/`。

---

## 构建与测试命令

统一使用 Gradle Wrapper（**Gradle 9.7.1**，AGP 9.4.0 / Kotlin 2.4.10 / Hilt 2.60.1 / **KSP 2.3.11**，版本集中于 `gradle/libs.versions.toml`）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint
- `.\gradlew.bat test` — 单元测试（全模块 `src/test`；当前 **506 例：494 通过 / 0 失败 / 12 跳过**，跳过项需 `-DliveSyncTest` 才启用）
- `.\gradlew.bat test -DliveSyncTest` — 追加启用 `LiveSyncServersTest` 真实联调用例（默认跳过 12 例，需先起 `tools/local-sync` 服务）

---

## 已知工程限界

- **KDBX 对象树仍整体驻留内存**：解析已流式化，但 `KdbxGroup`/`KdbxEntry` 树仍在内存（增量加载/进度 Flow 为远期项）。
- **条件写依赖服务端**：AWS S3 原子生效，少数未实现 `If-Match` 覆写的兼容存储降级为 HEAD 预检 + 无条件 PUT；WebDAV `uploadAtomic` 预条件在个别极简 DAV 服务端可能被忽略。
- **`ProtectedString` 驻留加密为纵深防御层**：对抗堆扫描与崩溃转储中的明文暴露；取得进程密钥或具备任意代码执行能力者仍可在读取瞬间截获明文。
- **浏览器特权白名单**：内置 Chrome 稳定版签名指纹，证书轮换或白名单外浏览器 fail-closed 降级为 apk-key-hash 路径。
- **外部库导入策略**：经导入复制进内部存储后原地编辑（不写回外部原文件），为当前设计取舍。
