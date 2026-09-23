# §285 Manager/Util/Helper 命名豁免登记与有界性机检批次

> 本批次由 2026-09-23 全仓工程卫生梳理触发（**非** `ACTIVE_ISSUES.md` 既有条目整改），
> 收口「Manager/Util/Helper 命名面既未改名也未登记豁免」：判定为**域前缀有界类型维持现名**，
> 落 `PD-34` 豁免登记 + `check_bounded_type_names.py` 白名单棘轮；顺带去重限界表 §2.7 重复节。
> **零生产代码改名**（改名即约 786 处引用 churn，零行为收益）。

## §1 背景

工程卫生排查（T1.2）报出：

> `crypto/…/HashUtil.kt:14` / `database/…/LittleEndianUtil.kt:12` / `KdbxXmlWriteUtil.kt:9` /
> `KdbxXmlValueUtil.kt:10` / `KdbxXmlTimeHelper.kt:33` 及多个 `*Manager` 类：Util/Helper/Manager 命名

全局工程原则原文为「避免**无边界**的 `Manager`、`Util`、`Helper`、`Common` 命名」——
判据是**无边界**（God Util / 杂物间 Manager），不是「后缀一律禁止」。此前既未改名、也未登记豁免，
处于规则悬空状态。

## §2 整改内容

### 2.1 全量清点与豁免判定

五模块 `src/main` 共 **13** 个生产类型使用该族后缀，**每一个都有域前缀 + 单一职责**
（KDoc / 源码自陈），不构成「无边界」：

| 类型 | 职责边界 |
|---|---|
| `HashUtil` | SHA-256 / SHA-512 / HMAC-SHA256 纯函数 |
| `LittleEndianUtil` | KDBX 二进制 LE 编解码 + 16 MiB 读护栏 |
| `KdbxXmlWriteUtil` | KDBX XML 写侧元素助手 |
| `KdbxXmlValueUtil` | KDBX XML 值编解码（UUID / Base64 容空白） |
| `KdbxXmlTimeHelper` | KDBX XML 时间戳（.NET 秒）编解码 |
| `HistoryManager` | 条目历史快照 / 修剪 / 回滚 |
| `KeystoreManager` | Android Keystore 密钥生成 / 封印 / 解封 |
| `AutoLockManager` | 自动锁定 Android 注册管道 |
| `ClipboardSecurityManager` | 剪贴板敏感复制 + 定时擦除 |
| `BiometricAuthManager` | 生物识别检测与认证 |
| `UnlockPasskeyManager` | 解锁通行密钥断言门控 |
| `UnlockThrottleManager` | 解锁失败节流闸门 |
| `ChildDatabaseSessionManager` | 子库挂载与只读会话门面 |

**不改名的理由**：全量改名牵动约 **786** 处引用与若干路径锚定用例，纯机械 churn、零行为收益。

### 2.2 `PD-34` 豁免登记

`docs/architecture/产品裁决登记.md` 新增 **PD-34**：上表 13 名维持现名入白名单；
**新增**该族后缀类型默认红；不得外推为「豁免真正的无边界命名 / 巨型类」。

### 2.3 有界性机检（白名单棘轮）

新增 `tools/doc/check_bounded_type_names.py`：

- 扫五模块 `*/src/main/**/*.kt` 的 `class|object|interface` 简单名；
- 后缀命中 `Manager|Util|Helper|Common` 且不在 `ALLOWED`（13 名）即退出码 1；
- `--selftest` 为口径反校（白名单放行 / 未登记拦截 / 无后缀不误判）。

挂进 CI `hygiene-gate` **第 7 条**（原 6 条；`AGENTS.md` §5 同步七条口径，
并补上 §283 时漏写的 `check_recheck_consistency`）。

### 2.4 顺带：限界表 §2.7 去重

§284 并行 edit 曾把 `已知工程限界.md` 的 **§2.7** 整节插入两份（逐字相同）。本批删去重复副本，保留一份。

## §3 验证（一律现跑）

| 判据 | 命令 | 读数 |
|---|---|---|
| 类型名有界性 | `check_bounded_type_names.py` | `files_scanned=543 allowed=13 unregistered=0` EXIT 0 |
| 口径反校 | `check_bounded_type_names.py --selftest` | `selftest=OK` EXIT 0 |
| **判别力** | 临时注入 `object CommonUtil` | EXIT **1**（精确报 `…:3: CommonUtil`）；删除后恢复 EXIT 0 |
| 规模闸门 | `count_line_tiers.py` / `long_functions.py` | `tier1=0 tier2=37 budget=37` / `functions_ge_100=0` |
| 文档链接 | `check_md_links.py` | `BROKEN_MD_LINKS=0` |
| 归档索引 | `check_resolved_index_sync.py` | `OK（…最大 §285）` |
| 重言 / 复核 | `check_tautological_assertions.py` / `check_recheck_consistency.py` | 0 命中 / PASS |

## §4 如实声明

- **零生产代码改名、零测试增减**——13 个类型名与全部调用点一字未动；未重跑全量 `test`
  （本批无 `*/src/**` 变更）。
- 豁免只覆盖「已有域前缀 + 单一职责」的 13 名；**新建** `CommonUtil` / `AppManager` 之类仍违规（机检红）。
- 机检是静态启发式（简单名 + 后缀）：`typealias` / 同名跨包可能漏报或误报，命中后须人工复核；
  **不得**据其绿推定「命名面已全面合规」。
- 顺带修复 §284 遗留的限界表 §2.7 重复节（文档面）。
- 未触 `*/src/**` / 原生面 / `androidTest` / `参考项目/` ⇒ **无设备侧必跑项**。
- 未跑 `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍 / 真机。
- CI 未在真实 runner 复跑（纯 YAML 追加一行既有脚本）。

## §5 改动清单

| 路径 | 性质 |
|---|---|
| `tools/doc/check_bounded_type_names.py` | **新增**有界性机检（白名单棘轮 + `--selftest`） |
| `docs/architecture/产品裁决登记.md` | 新增 `PD-34` |
| `docs/architecture/已知工程限界.md` | §2.7 重复节去重 |
| `.codebuddy/rules/engineering-rules.md` | 「命名有界」长期规则一句 |
| `.github/workflows/build.yml` | `hygiene-gate` 第 7 条 |
| `AGENTS.md` §5 | 七条机检口径 + 新脚本条目 |
| `docs/README.md` | `PD-01`~`PD-14` → `PD-01`~`PD-34` |
| `docs/resolved/batches/285-ManagerUtilHelper命名豁免登记与有界性机检批次.md` | 本文件 |
| `docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` / `docs/resolved/README.md` | 归档索引 |
