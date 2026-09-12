# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`；本文件**不保留**已闭环条目的正文或索引段，历史实现与验收证据一律以归档库为单一真相源。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：曾发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md` 等文件「需要删除」，但这些文件早已先行删除，`AGENTS.md` 也已不含相关引用。
   > 条目与代码库演进之间存在时间差，会导致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。
   前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
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

## P0 阻断级与致命安全漏洞（0 项）

> 当前无待办。**ISSUE-P0-04**（库内 ≥1 条目时库列表渲染必崩）于 2026-09-11 修复归档，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §26。

---

## P1 高危与核心功能问题（0 项）

> 当前无待办。**ISSUE-P1-13**（写侧 Argon2 `P` UInt64 违反 KDBX4 规范）于 2026-09-11 整改归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §27.1；**ISSUE-P1-14**（后台自动锁定）复核为行为符合
> 设计结案，见 §27.2；**ISSUE-P1-15**（明文导入 KeePass XML 在 Android 运行时全量失败）同日
> 发现并整改归档，见 §27.3。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> 当前无待办。**ISSUE-P2-24**（大附件整库常驻内存，无独立磁盘缓存池）于 2026-09-12 按分阶段方案
> **阶段 1/2/3 全量落地**并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §35；
> **ISSUE-P2-27**（app / sync 无设备侧验证源集）由 §34 + §36 收口。
> **历史批次**：**ISSUE-P2-23**（带密钥文件解锁后指纹快速解锁不可用，复合封印整改）于 2026-09-12
> 整改归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §29.1；**ISSUE-P2-22**（「Argon2 参数」应用无
> 真实效果）同日归档于 §28.1；P2-19 / P2-20 / P2-21 已于 §27 归档；
> **ISSUE-P2-25 / P2-26** 经产品裁决为「不排期」，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §33。

---

## P3 低危问题、特性接线与体验优化（1 项）

> **状态（2026-09-12）**：历史 P3 批次 **ISSUE-P3-01 ~ P3-68** 除 P3-23（经产品裁决「不排期」）外
> 已全部闭环并归档，逐条实现细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)（§3 ~ §32）。
> 2026-09-12 存量修复批次闭环 P3-63 / P3-65 / P3-67（§28.2 ~ §28.4）；
> **ISSUE-P3-68**（重试节流开关与自定义最长锁定时长）同日闭环归档（§29.2）；
> **外部安全审计整改批次 P3-69 ~ P3-72** 同日闭环归档（§30）；
> **文档类存量整改批次 P3-75 / P3-77** 同日闭环归档（§31）；
> **CSV 导入 / 导出扩充 P3-73** 同日闭环归档（§32）。
> **设备侧验证缺口 P2-27 收口**：§34（app 导入解析设备侧骨架）+ §36（app 域解析 / 解锁落盘、
> sync 权限基线）同日闭环；**工程整洁收口批次**（死文案 / 假开关 / `AGENTS` §6 更正）同日归档（§37）。
> **产品裁决「不排期」**：P3-58 / P3-66 / P3-74（见 §33）。
> **保留待办**：**ISSUE-P3-76**（框架阻塞的已接受残余风险，保留跟踪与解除条件）。

---

### ISSUE-P3-76（新登记）：密码 / 主密码输入未禁用输入法个性化学习

- **优先级**：P3（低危隐私加固）
- **核实时间点与核实方式（2026-09-12）**：全仓 `app/src/main` 检索 `IME_FLAG_NO_PERSONALIZED_LEARNING`
  与 `InputMethodService` **零命中**；敏感输入现仅依赖 `KeyboardType.Password` 等常规配置。
- **问题描述**：第三方输入法可能对用户输入做个性化学习 / 候选记忆，主密码与条目口令存在被输入法
  词库记录的风险面。
- **参考做法（据 `docs/references/keepass2android-架构分析.md`）**：`Util.SetNoPersonalizedLearning`
  显式关闭输入法学习。
- **验收标准（待整改）**：① 主密码、条目口令、生成器口令预览等**全部敏感输入路径**显式禁用个性化学习
  （`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`，Compose 侧经 `PlatformImeOptions` 等机制下发）；
  ② 有回归断言覆盖各敏感输入组件；③ 非敏感输入（如搜索框）行为不变。
- **禁止**：仅在部分页面接线导致旁路；以自定义 `VisualTransformation` 冒充已关闭输入法学习
  （防的不是同一威胁）。
- **框架阻塞实测（2026-09-12，读取本机 Compose 源码核实）**：本仓 `androidx.compose.*` 为
  **1.11.4**（BOM 2026.08.00）。逐一核对 Compose 的 `EditorInfo` 构造链后确认**无任何公开 API 可下发
  该整型标志**：
  1. `foundation/androidMain/.../text/input/internal/EditorInfo.android.kt` 的 `EditorInfo.update(...)`
     仅由 `imeAction` 枚举构造 `this.imeOptions`，并附加 `IME_FLAG_FORCE_ASCII` / `IME_FLAG_NO_ENTER_ACTION` /
     `IME_FLAG_NO_FULLSCREEN`，**不设也无可传入 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的入口**；
  2. `ui-text/androidMain/.../input/PlatformImeOptions.android.kt` 的 `PlatformImeOptions` **仅**暴露
     `privateImeOptions: String?`（映射 `EditorInfo.privateImeOptions` 自由字符串，主流输入法**不解析**该
     字符串来禁用学习），**无 imeOptions 位域**；
  3. `KeyboardOptions.toImeOptions()`（`foundation/commonMain`）亦仅承载 `ImeAction` / `singleLine` 等，
     不含原始位域；
  4. 官方路线（`ui/androidMain/.../platform/PlatformTextInputMethodRequest.android.kt` 的
     `createInputConnection(outAttributes: EditorInfo)`）允许拦截 `EditorInfo`，但它属**平台文本输入会话**
     私有扩展点，需以 `PlatformTextInputSession.startInputMethod` 自行实现整个输入会话，**无法与
     M3 `OutlinedTextField` 组合**（等于重造文本输入控件）；
  5. 参考项目外证：开源项目 spela（PR #1114）对同类诉求的结论一致——「Compose 不在公开 Kotlin API 暴露
     这些 int 标志，只能下沉到 Android View 系统包裹真实 `EditText`」。
- **本次处置：登记为框架阻塞的已接受残余风险，不实施**（与 ISSUE-P3-58 同属「本地不可消除、保留跟踪」）：
  - **残余风险已部分缓解**：敏感输入经 [SecurePasswordField](../app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt)
    统一走 `KeyboardType.Password`（→ `TYPE_TEXT_VARIATION_PASSWORD`），主流输入法（Gboard / SwiftKey /
    Samsung / HeliBoard）对该 inputType **默认不做个性化学习**；显式标志只是更强一层的提示。
  - **不采用高风险代偿**：把安全关键的 `SecurePasswordField` 整体改写为 `AndroidView(EditText)`
    会牺牲 M3 外观 / 无障碍 / 现有 CharArray 桥接与擦除契约，风险与 P3 收益不成比例，**本次不做**。
  - **AC③ 语义**：因未接线，非敏感输入行为天然不变（未产生任何旁路）。
- **解除条件（可复现配方）**：若 Compose 后续版本在 `PlatformImeOptions` / `KeyboardOptions` 暴露
  `imeOptions` 位域（或提供 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的公开入口），则在 `SecurePasswordField`
  统一接线并补回归断言（覆盖主密码 / 条目口令 / TOTP / 同步凭据各调用点），届时即可闭环本条。

---
