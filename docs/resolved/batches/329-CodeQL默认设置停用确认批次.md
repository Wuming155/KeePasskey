# §329 CodeQL 默认设置停用确认批次（`ISSUE-P3-317` 闭环）

> **批次性质**：运维动作确认批次——停用本身系用户在 GitHub 网页完成的人工操作（2026-09-25），
> 本批由代理完成**API 侧旁证取证**并归档闭环。无生产代码改动（探针为一次空提交）。

---

## 1. 整改对象

| 条目 | 判据来源 | 本批处置 |
|---|---|---|
| `ISSUE-P3-317` CodeQL 默认设置未按 ISSUE-P3-57 前置条件停用 | 用户确认网页停用完成 + API 旁证（§3） | **闭环** |

## 2. 整改内容

用户于 2026-09-25 在 GitHub 网页（Settings → Code security and quality → Code scanning →
CodeQL analysis → Default setup → Disable）完成停用。代理侧取证限制如实声明：现细粒度 PAT
仍无「Code scanning alerts」权限，`GET /code-scanning/default-setup` 实跑仍 **403**
（与本条目核实方式记录一致），无法直接读回 state 字段——闭环判据改由以下三项 API 旁证承托
（原 AC② / ③ 不变，AC① 的面板观感由用户确认 + 旁证共同承托）：

## 3. 验证读数

1. **默认设置分析缺席（探针验证）**：推送空提交探针后等待 150 秒，
   `GET /code-scanning/analyses` 过滤 `analysis_key` 前缀 `/language`：**仅剩停用前最后一条**
   `{"analysis_key":"/language:c-cpp","created_at":"2026-09-25T05:09:59Z"}`，停用后零新增
   （启用状态下每次 push 到 main 均触发默认设置扫描）。
2. **Actions 运行列表交叉验证**：停用后的三次推送（`9ba45d33` / `082f8cd0` / 探针提交）
   均未产生任何默认设置 CodeQL 运行；`gh run list` 中 CodeQL 仅剩自管工作流的三次
   manual dispatch（36108259019 / 36119079938 / 36096034804）。
3. **自管高级配置不受影响（AC②）**：`codeql.yml:analyze` 的 rust / python / actions 三语言
   分析持续产出、`error` 全空，`analysis_key` 保持 `.github/workflows/codeql.yml:analyze`。
4. **安全面板旁证**：open 告警中 `CVE-2020-29582` 五条（PD-25 有意保留项）维持原状，
   py/redos 两条已 fixed（§328），无默认设置来源的新告警。

## 4. 验证读数（原样粘贴）

```
xml=412 tests=2774 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 327 份；分册登记 329 条；全量索引 329 条；最大 §329）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 476 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

## 5. 涉及文件

文档：`docs/ACTIVE_ISSUES.md`（P3-317 移出闭环，P3 1 → **0**，全量待办归零）、
`docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` / `docs/resolved/README.md`（§329 登记）。

## 附录：`ISSUE-P3-317` 原文（自 ACTIVE_ISSUES.md 整条剪切，原样收录）

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
  > **代办复核实录（2026-09-25，§327 批次代理）**：`gh api repos/…/code-scanning/default-setup`
  > 实跑仍 **403**（`Resource not accessible by personal access token`，读均不可得）——现令牌
  > 无「Code scanning alerts」权限，API 化停用仍被阻塞；告警**列表读数**（`GET …/alerts`）可用，
  > 同日实读 #355 / #356 仍 `state=open`。须由用户完成网页停用或增授权限后由代理复跑。
- **验收标准**：① Security → Code scanning 不再出现默认设置（c-cpp / java-kotlin）的错误条目；
  ② `.github/workflows/codeql.yml` 每周巡检与手动触发仍正常上传 SARIF（analyses 列表 `error` 为空、
  `analysis_key` 仍为 `codeql.yml:analyze`）；③ 闭环批次文档记录停用后的面板或 API 读数。
