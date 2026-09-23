# §283 复核一致性机检移植 Python 单源批次

> 本批次由 2026-09-23 全仓工程卫生梳理触发（**非** `ACTIVE_ISSUES.md` 既有条目整改），
> 收口「`tools/audit/check_recheck_consistency.sh` 仍是 bash-only，PowerShell 会话不可直接跑」。
> 取向＝**Python 单源**（与仓内其余机检一致），`.sh` 降为薄封装以保留历史命令入口；
> 同步挂进 `hygiene-gate` 第六条。本批**零测试增减**、**零生产代码改动**。

## §1 背景

该闸门自 §69 路径更正后一直是 **bash + awk + mktemp** 实现，调用方式固定为
`bash tools/audit/check_recheck_consistency.sh`。2026-09-23 工程卫生核对时实测：
本机 PowerShell 会话 **`Get-Command bash` 无输出**（无 Git Bash / WSL 挂进 PATH），
脚本**完全不可跑**——与 `AGENTS.md` §5「改审计 / 复核报告后必跑」直接冲突，
Windows 开发面事实上没有可用闸门入口。同目录 `check_tautological_assertions.py`
已是 Python，其余 `tools/doc/*.py` 亦全是 Python，唯此一件掉队。

## §2 整改内容

### 2.1 移植 `tools/audit/check_recheck_consistency.py`（单源）

| 面 | 原 `.sh` | 现 `.py` |
|---|---|---|
| 禁用短语 | heredoc 11 条，awk ERE | `PHRASES` 列表 11 条，Python `re`（导入期 `PHRASE_RES` 预编译） |
| 豁免标记 | `EXEMPT='已撤销\|…'` | 同词表 `re.compile` |
| 章节豁免 | awk `^## 15\.2` / `^# 12\.` 进，`^## 15\.3` / `^# 13\.` 出 | 同判据 |
| 默认路径 | `docs/security/SECURITY_RECHECK_2026-09.md` | 同（相对 `REPO_ROOT` 解析，cwd 无关） |
| 退出码 | 0 PASS / 1 残留 / 2 报告不存在 | 同 |

**移植踩坑（如实留痕）**：短语 `Zeroizing` 全路径（**除` 含 `**`。awk ERE 按字面吞下；
Python `re` 视 `**` 为 **multiple repeat**，导入即 `re.error`。该条改为显式 `\*\*`，
并在注释与 docstring 写明——禁用短语清单**除第 2 条**（`2²⁴…GiB`，保留 `.{0,10}` ERE）外
一律按字面匹配。

### 2.2 `.sh` 降为薄封装

`check_recheck_consistency.sh` 只做 `exec python3|python "$here/check_recheck_consistency.py" "$@"`。
历史批次文档里的 `bash …/check_recheck_consistency.sh` **无需回改**（归档只搬迁不改写），
命令仍可执行；逻辑永不漂移（单源）。

### 2.3 入口与 CI 同步

- `AGENTS.md` §4 / §5：主入口改为 `python tools/audit/check_recheck_consistency.py`（PowerShell 直接可跑），并注 `.sh` 为薄封装。
- `docs/security/SECURITY_RECHECK_2026-09.md` 两处用法同步为 `.py`；**顺带更正**示例路径
  `docs/SECURITY_RECHECK_2026-09.md` → `docs/security/SECURITY_RECHECK_2026-09.md`（§69 后的现行落位）。
- `.github/workflows/build.yml` **`hygiene-gate` 第六条**追加 `python3 tools/audit/check_recheck_consistency.py`（五→六条，fail-closed 不变）。

## §3 验证（一律现跑）

| 判据 | 命令 | 读数 |
|---|---|---|
| 真实报告绿态 | `python tools/audit/check_recheck_consistency.py` | `PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）`；EXIT **0** |
| **红态（判别力）** | 临时样本含禁用短语「小时级」 | `FAIL: 发现 1 处残留` + `L1 禁用短语「小时级」`；EXIT **1** |
| 缺文件 | 传不存在路径 | `FAIL: 报告不存在`；EXIT **2** |
| 豁免标记 | 样本行含「已更正」+ 禁用短语 | `PASS`；EXIT **0** |
| 文档链接 | `python tools/doc/check_md_links.py` | `BROKEN_MD_LINKS=0` |
| JVM 单测 | 本批未触 `*/src/**` | **零测试增减** |

红 / 缺文件 / 豁免三路与原 bash 退出码语义一致；真实报告 1331 行 / 11 条与近期批次读数对齐。

## §4 如实声明

- **未在有 `bash` 的环境对拍 `.sh` 薄封装**（本机无 bash）；封装仅 4 行 `exec`，逻辑单源在 `.py`。
- 禁用短语第 10 条由 `**` 改 `\*\*` 属**字面等价**修正（awk 本就当字面量）；第 2 条 ERE 语义原样保留。
- 本批**未改**任何生产代码 / 测试资产；未触 `crypto/src/main/rust/**` / `*/src/androidTest/**` / `参考项目/` ⇒ **无设备侧必跑项**。
- 未跑 `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍 / 真机。
- `hygiene-gate` 未在真实 runner 复跑（与 §281 同口径：纯步骤串接既有脚本）。

## §5 改动清单

| 路径 | 性质 |
|---|---|
| `tools/audit/check_recheck_consistency.py` | **新建**：逻辑单源 |
| `tools/audit/check_recheck_consistency.sh` | 改薄封装（历史入口保留） |
| `AGENTS.md` | §4 / §5 主入口与描述 |
| `docs/security/SECURITY_RECHECK_2026-09.md` | 两处用法 + 示例路径更正 |
| `.github/workflows/build.yml` | `hygiene-gate` 五→六条 |
| `docs/resolved/batches/283-复核一致性机检移植Python单源批次.md` | 本文件 |
| `docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` / `docs/resolved/README.md` | 归档索引 |
