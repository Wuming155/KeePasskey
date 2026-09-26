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

> **暂无开放项（全量待办归零）**。2026-09-25 解锁节流完整性层 fail-closed 缺陷与冗余性裁决
> （`ISSUE-P1-277`，完整性层整体移除 / 基础节流保留，`PD-46`）闭环见 §334。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-24 同步/加密/passkey 安全审计批五条（`ISSUE-P2-308` ~ `ISSUE-P2-312`）
> 已全部闭环：§315（309 / 312）、§317（308）、§318（310）、§319（311）、§320（313）；
> 2026-09-25 CI 设备门禁与供应链扫描两条（`ISSUE-P2-314` / `ISSUE-P2-315`）闭环见 §325。

## P3 低危问题、特性接线与体验优化（1 项）

> 2026-09-25 本地纵深防御四层（节流完整性层 / 防回滚状态 MAC /
> 快速解锁断言层 / 字段签名 HMAC）经用户裁决**全部移除**（`ISSUE-P3-326` ~ `P3-328`，
> `PD-46`），与 `ISSUE-P1-277` 同批闭环见 §334；`PD-34` 白名单棘轮补正（`ISSUE-P3-329` 补登并同批闭环）见 §335；
> 更早的 P3 闭环流水见 `RESOLVED_LOG.md` §326 ~ §333。

### ISSUE-P3-330 UI 冗余提示文本清理（同屏重复说明 + 死字符串 + 未渲染 descRes）

**背景（2026-09-26 用户要求排查「UI 上冗余的提示文本」）**：四类冗余，按处置方式分组——

**A. 同屏重复渲染（真实显示给用户的重复提示，4 处）**

| # | 位置（核实于 2026-09-26） | 冗余内容 |
|---|---|---|
| A1 | `TotpSettingsScreen.kt:107`（`totp_presets_desc`）与 `:293`（`totp_info_desc`） | 同一 TOTP 设置页两处复述同一条查找顺序：「配置的字段名优先 → 未命中回退官方 `otp` 字段与 TOTP 开头的自定义字段」。顶部卡片说明一次、底部信息卡再整段说一次 |
| A2 | `ThemeSettingsSections.kt:119`（`theme_dynamic_sub`）与 `:219`（`:206` 的 `theme_palette_pick_desc` 为第三处） | 动态取色开启时同一屏出现近乎同义的三句话：行副标题含「开启期间下方主题调色盘暂不生效」、调色盘置灰提示又整句重复「动态取色已开启，主题调色盘暂不生效」、`theme_palette_pick_desc` 再述同一互斥关系 |
| A3 | `AutofillPickerActivity.kt:295-296` → `AutofillAuthResultDelivery.kt:59-60` | `menuSubtitle` 恒为 `autofill_picker_title`，`menuTitle` 在用户名为空时也回退同一字符串 ⇒ 系统填充下拉中标题、副标题两行逐字相同（「选择要填充的凭据」×2） |
| A4 | `DatabaseSettingsArgon2DialogSections.kt:111/118`（内存）、`:137/144`（并行度） | 行标签已内嵌当前值（「内存占用：64 MB」/「并行线程数：4」），下方选中 chip 又显示同一数字（「64 MB」/「4 线程」），当前值同屏出现两次 |

**B. 零引用死字符串（35 条）**：解析 `values/strings.xml` 共 1109 条，全仓 `.kt`/`.xml`（含 test 源集，测试独占引用 = 0）扫描 `R.string.*` / `@string/*` 后 35 条零命中。分组：

- 已被同语义键取代：`vault_sort_default/name/date/modified/created`（5，已由 `sort_option_*` 取代）、`health_level_excellent/good/fair/poor`（4，已由 `health_status_*` 取代）、`health_not_scanned` + `health_not_scanned_desc`（后者与在用的 `health_scan_hint_idle` **值完全相同**）、`unlock_empty_open_btn`（与在用的 `unlock_empty_open_title` 近同义）、`db_picker_created_toast` / `db_picker_opened_external_toast`（与 `db_picker_msg_created` / `db_picker_msg_opened` 同义）。
- 功能已移除/未接线：`unlock_error_pin_length`（自研 PIN 已于 ISSUE-P1-08 移除）、`unlock_btn_biometric`、`db_picker_use_keyfile`、`edit_category_label`、`detail_attachment_preview`、`vault_action_sync`、`vault_action_resolve_conflict`、`vault_recycle_bin_title`、`cd_keyfile`、`cd_biometric`、`cred_unlock_action_subtitle`、`cred_password_save_title`、`cred_password_fill_title`、`cred_error_entry_not_found`、`repo_last_opened_not_created`、`unlock_status_local`。
- 疑似通用按钮预留：`btn_confirm`、`btn_edit`、`btn_select`、`btn_open`。

**C. 枚举 `descRes` 声明但从未渲染（5 条）**：`theme_density_compact/normal/comfortable_desc`（`ListDensity.descRes`，全仓无 `density.descRes` 读取方，界面只渲染 `labelRes` + `theme_density_desc`）与 `theme_search_mode_contains_desc` / `theme_search_mode_all_terms_desc`（`SearchMatchMode.descRes` 同样无读取方，`ThemeSettingsListSections.kt:192` 只渲染总述 `theme_search_mode_desc`）——「声明了说明文案却从不显示」，属半接线状态。

**D. 在用但值完全相同的重复键（2 对）**：`vault_op_failed` ≡ `settings_action_failed`（同为「操作失败：%1$s」，各有调用方）；`autofill_confirm_biometric_subtitle` ≡ `cred_fill_confirm_biometric_subtitle`（同为「验证身份后向当前应用填充凭据「%1$s」」）。`btn_*`/`cd_*` 成对同值属无障碍描述复用，**不算**冗余、不在整改范围。

**核实时间点与核实方式（2026-09-26，对工作区当前状态）**：① Python 正则解析 `app/src/main/res/values/strings.xml` 全量键值（1109 条）；② 全仓 `*.kt` + 非 values 目录 `*.xml` 扫描 `R.string.<name>` / `@string/<name>` 引用（排除 `build/` 与 `参考项目/`，含 test 源集）；③ 对 A 组逐处打开调用点文件核对**同屏共现**（非仅「都被引用」）；④ C 组经 `descRes` 全部读取点枚举核实（仅 `CloudSyncComponents.kt:167` / `CloudSyncSections.kt:382` 有读取，分别属 `CloudSyncProvider` / `ConflictResolution`）。行号为核实时刻快照。

**验收标准**：

1. A 组 4 处各收敛为单点说明。**A2 不得整句删除**——「置灰附原因说明」是 `ISSUE-P3-263` AC⑤ 的硬要求，只能合并去重（保留调色盘侧提示，`theme_dynamic_sub` 摘除重复从句），口径不得降级。
2. A3 修复为 title/subtitle 语义分工（如用户名空时 subtitle 用条目标题或省略），系统填充下拉不再出现两行同文。
3. B 组逐条复核仍零引用后删除，`values-en/strings.xml` 对应条目**同批**删除；`btn_confirm` 等 4 条通用按钮键删除前须再确认无未来接线计划（无则删）。
4. C 组择一处置：接线渲染（在选择器中展示选项说明）**或**连 `descRes` 字段一并删除——禁止留死声明。
5. D 组合并为单键或在两条键旁注释说明分立理由。
6. 整改后 `.\gradlew.bat test` 全绿 **且** `python tools/doc/gate_readings.py` 7/7 PASS，读数块原样贴入批次文档 §3（§308）；若删除字符串涉及已渲染界面，另跑 `.\gradlew.bat :app:compileDebugScreenshotTestKotlin --rerun`。
