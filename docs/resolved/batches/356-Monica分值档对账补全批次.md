<a id="s356"></a>
## §356 Monica 分值档对账补全批次（ISSUE-P3-377）

**范围**：用户对账 Monica `BitwardenLikeAutofillMatcherNg` 分值档（精确域名 140 / 精确包名 120 /
子域 115 / 基域 100 / 应用名 95 / 包域叠加 +30）——补上**未吸收的基域 100 档**、子域档分值
对齐 115，并以对账表裁决包名档维持本仓 130。**验收**：全量 test 绿
`xml=456 tests=3012 failures=0 errors=0 skipped=13`（§355 基线 456/3007 ⇒ +5 例）；
门禁 8/8（读数见 §356.4）。

---

### §356.1 条目原样收录（自 `ACTIVE_ISSUES.md` 剪出）

#### ISSUE-P3-377：Monica 分值档对账——基域 100 档未吸收（兄弟子域同 eTLD+1 不命中）

- **核实时间点与核实方式（2026-09-28）**：用户对账命题（Monica `BitwardenLikeAutofillMatcherNg`
  分值档：精确域名 140 / 精确包名 120 / 子域 115 / 基域 100 / 应用名 95 / 包域叠加 +30）→
  定向直读本仓 `AutofillCandidateRanker.kt` 现档位（140/130/120/**110**/95/+30）与
  `applySameSiteTiers` 三分支（去 www 归一 / 子域后缀），逐档对账：
  ① 精确域名 140 ✓ 同值；② 精确包名 120 → 本仓 130（P3-39 先于吸收的自有档位）；
  ③ 子域 115 → 本仓 110（§352 新增，差 5 分）；④ **基域 100 ✗ 未吸收**——
  `entry=accounts.example.com` vs `origin=shop.example.com`（兄弟子域、同 eTLD+1）
  在本仓严格匹配与两档同站加性档下**均不命中**（实测构造用例即可证空）；⑤ 应用名 95 ✓ 同值；
  ⑥ 包域叠加 +30 ✓ 同值（P3-39 既有）。
- **背景**：§352 吸收时只落地了「子域后缀」单向档，漏掉 Monica 层级中的**基域**（同可注册域）
  档——Monica 三档为 精确 140 > 子域 115 > 基域 100，本仓现为 140 > 130(包) > 120(父域) >
  110(子域)，缺 100 档。**安全口径**：基域判定必须经 **PSL 计算 eTLD+1**（`foo.github.io` 与
  `bar.github.io` 的可注册域不同，不得按「末两标签」近似）；仍为**加性排序档**，不改准入语义之外
  的任何严格匹配（`DomainMatcher` 红线不动）。
- **涉及文件**：`passkey/PublicSuffixList.kt`（补 registrableDomain 取值函数）、
  `autofill/AutofillCandidateRanker.kt`（SAME_BASE_DOMAIN 档 + 子域分值对齐 115）、
  `AutofillCandidateRankerTest.kt`（正反例：兄弟子域命中 / 私有段兄弟不命中 / 分值断言）。
- **验收标准**：AC① `PublicSuffixList.registrableDomain(host)`：可注册域字符串或 null
  （资源不可用 fail-closed；`foo.github.io` → `foo.github.io`、`a.b.example.com` → `example.com`）；
  AC② ranker 新增 `SAME_BASE_DOMAIN`（100，Monica 同值）：前序档（精确 / 父域 / 子域 / 去 www）
  均未命中且两侧 eTLD+1 相等时命中，PSL 私有段负例（`foo.github.io` ≠ `bar.github.io`）锁定；
  AC③ `SCORE_SUBDOMAIN_OF_ORIGIN` 110 → 115（Monica 同值对齐），既有用例断言同步；
  AC④ 精确包名维持 130 不回退（降 120 将与父域档并列、破坏严格序，属本仓自有档位，
  以批次文档对账表留痕裁决）；AC⑤ `test` 全绿 + `gate_readings.py` 全 PASS。

---

### §356.2 分值档对账表（终态）

| Monica 档位 | Monica 分值 | 本仓对应 | 本仓分值 | 判定 |
|---|---|---|---|---|
| 精确域名 | 140 | `EXACT_DOMAIN`（含去 www 归一，§352） | 140 | ✓ 同值吸收 |
| 精确包名 | 120 | `EXACT_PACKAGE`（P3-39 既有） | **130** | ✓ 维持 130（裁决：降 120 与父域档并列破坏严格序，维度等价、档位自有） |
| 子域 | 115 | `SUBDOMAIN_OF_ORIGIN`（§352） | **115** | ✓ 本批 110 → 115 同值对齐 |
| 基域 | 100 | `SAME_BASE_DOMAIN`（**本批新增**） | 100 | ✓ 本批吸收（PSL eTLD+1） |
| 应用名 | 95 | `APP_TITLE_MATCH`（§352） | 95 | ✓ 同值吸收 |
| 包域叠加 | +30 | `PACKAGE_DOMAIN_COMBO`（P3-39 既有） | +30 | ✓ 同值（既有条件维持：EXACT/PARENT 与包名叠加） |

> 附：本仓另有 Monica 层级中没有的 `PARENT_DOMAIN`（120，条目是目标域父域）——与子域档构成
> 双向后缀覆盖，属本仓 §352 既有设计，不回退。终态序：140 > 130 > 120 > 115 > 100（严格单调）。

### §356.3 整改内容

1. **AC① `PublicSuffixList.registrableDomain`**：按公共后缀之上一个标签 + 后缀计算 eTLD+1
   （`takeLast(suffixSize + 1)`）；**与 `isRegistrableDomain` 的两处刻意差异留痕**：
   ① IP 字面量返回 null（IPv4 全数字标签 / IPv6 含冒号）——既有判定对多标签 IP「一律放行」
   是历史行为，但 `192.168.1.1` 与 `192.168.1.2` 的「末段剥离相等」绝不构成同站（基域档负例
   判据）；② 不可注册 / 资源不可用返回 null（fail-closed 同款）。
   **施工留痕**：初版误写 `take(size - suffixSize)`（取错端），单测首轮即红
   （`example.com` → `example`），改 `takeLast(suffixSize + 1)` 回绿——正是 AC① 正反例用例的
   鉴别力所在。
2. **AC② 基域档**：`MatchReason.SAME_BASE_DOMAIN` + `SCORE_BASE_DOMAIN = 100`；
   `applySameSiteTiers` 第三档（前序档未命中且两侧 `registrableDomain` 非空相等）。
   用例：兄弟子域 `accounts.example.com` ↔ `shop.example.com` 命中 100 分；私有段兄弟
   `foo.github.io` ≠ `bar.github.io` 不命中；异域 `example.org` ↔ `example.com` 不命中
   （专打 eTLD+1 维度，与既有 `evilgithub` 字面量负例互补）。
3. **AC③ 子域分值 110 → 115**：既有断言同步改 115。
4. **AC⑤ PSL 取值用例**（`PublicSuffixListTest` +3 例）：常规与多层子域取值、私有段兄弟
   互不相同、不可注册 / IP / 空白返回 null。

### §356.4 验证与门禁读数

- 全量 `.\gradlew.bat test --rerun-tasks --max-workers=1`：**BUILD SUCCESSFUL**，
  `count_test_results.py` = `xml=456 tests=3012 failures=0 errors=0 skipped=13`
  （§355 基线 456/3007 ⇒ **+5 例** = PSL 取值 3 + 基域档 2）。
- **过程红绿留痕（两条）**：① 首轮 PSL/打分器 3 红——`registrableDomain` 初版取错端
  （见 §356.3 第 1 点），修正后两测试类隔离全绿；② 全量首轮 1 红 =
  `AlgorithmSelectionApplyTest`（期望 ARGON2D 实得 ARGON2ID）——**与本批零触碰面**
  （本批仅改 `PublicSuffixList` / `AutofillCandidateRanker` 及其测试）：该例隔离复跑全绿、
  全量复跑亦全绿，判定为既有序依赖偶发（体例同 §333 首轮 `SyncCacheEvictorTest` 偶发留痕），
  本批不改该面。

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴该脚本输出的读数块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=37  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 354 份；分册登记 356 条；全量索引 356 条；最大 §356）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 514 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

> 上述读数为**归档完成后**（本批次文档、`RESOLVED_LOG` 行、分册行与 README 最大编号均已登记）复跑采集。

### §356.5 如实声明

- **真机面未测**：兄弟子域在系统填充面板的实际排序感知未真机核对（无设备；`androidTest`
  零改动 ⇒ 无设备侧必跑项）。AC⑤ 口径为宿主 `test` 全绿 + 门禁全 PASS，已满足。
- **精确包名 120 未对齐**：属裁决（AC④）——维持 130 而非照抄 120，理由见对账表；
  若后续要与 Monica 逐分对齐须另行裁决（会改变包名档与父域档的相对序）。
- **包域叠加条件未扩**：本仓 +30 沿用 P3-39 既有条件（EXACT/PARENT 与包名叠加），
  未扩展到子域/基域档——`android://` 包名与 Web 域档同时命中的形态仅可能经 passkey rpId
  路径出现，属既有设计而非本批遗漏；对齐 Monica「任意域档皆可叠加」须另立条目评估。
- **`AlgorithmSelectionApplyTest` 偶发**：见 §356.4 第 2 点留痕，本批未触碰该面、未改其用例。

---

（§356 完 —— Monica 自动填充吸收全系列收口：§352 ~ §356，6 条 ISSUE 全闭环）
