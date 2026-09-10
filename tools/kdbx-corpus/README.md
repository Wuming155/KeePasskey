# `.kdbx` 互操作语料生成 / 校验脚本（ISSUE-P3-38）

> 归属条目：**ISSUE-P3-38**（为 ISSUE-P3-23 消除「人工 GUI 建库 + 逐条复核 + 手写伴生 JSON + 双落位」这段阻塞）
> 上游约定：[`crypto/src/test/resources/argon2-interop/README.md`](../../crypto/src/test/resources/argon2-interop/README.md)
> —— 本脚本实现其 §3（生成）、§6.1（命名）、§6.2（伴生元数据 schema）、§3.4（双落位）四节。

---

## 1. 它做什么 / 不做什么

**做**：

| 能力 | 说明 |
|---|---|
| 环境自检（`--check`） | 探测 `keepassxc-cli` 是否存在、版本可解析、`db-create` 子命令可用，以及两个落位目录可写 |
| 只读核验（`--verify`） | 用**纯标准库**解析 `.kdbx` 文件头的真实 KDF 参数；给出伴生 JSON 时逐字段比对 |
| 复核入库（`--ingest`） | 按**文件头实测参数**推导规范文件名与伴生 JSON，并把两份文件复制到两个落位目录 |
| CLI 生成（`--generate`） | 调用官方 CLI 建库 + 追加占位条目，再走 `--ingest` 的同一条复核路径 |

**不做**（如实声明）：

- **不产出「互操作证据」**。本脚本产出的只是**语料文件与元数据**；「验证通过」必须由
  `:database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest` 在真实设备上跑出来。
  `NotImplemented`-式宣称（脚本跑通 ≠ 验收通过）在本仓库属禁止行为。
- **不用任何第三方 KDBX 实现**。`THIRD_PARTY_PRODUCERS` 显式拒绝 `pykeepass` / `kdbxweb` 一类
  名称（它们**含**子串 `keepass`，能骗过 README §6.2 的宽子串规则）。
- **不解析、不解密载荷**。只读 KDBX4 **明文外层头**（规范如此），不接触主密码与条目内容。

---

## 2. 用法

```bash
# 1) 环境自检（缺 keepassxc-cli 即非零退出并给出安装指引）
python tools/kdbx-corpus/generate_corpus.py --check

# 2) 看一眼计划，不写盘
python tools/kdbx-corpus/generate_corpus.py --dry-run

# 3) 核验一个已有 .kdbx（可用 --json 同时核对伴生元数据）
python tools/kdbx-corpus/generate_corpus.py --verify <file.kdbx> [--json <file.json>]

# 4) 复核并落位（推荐路径：官方 GUI 建库 → 本命令复核 + 自动写 JSON + 双落位）
python tools/kdbx-corpus/generate_corpus.py \
    --ingest <file.kdbx> --source keepassxc --force

# 5) 直接用官方 CLI 生成（注意下方 §3 的能力限制）
python tools/kdbx-corpus/generate_corpus.py --generate --source keepassxc
```

退出码：

| 码 | 含义 |
|---|---|
| `0` | 成功 |
| `2` | 用法错误（缺参数 / 文件不存在） |
| `3` | 环境缺失（CLI 不可用、落位目录不可写） |
| `4` | 一致性失败（文件名或伴生 JSON 与文件头不符、冒充语料） |
| `5` | 拒绝执行（目标已存在且未 `--force`） |

---

## 3. 已知能力限制（**必读**）

KeePassXC 官方 CLI 的 `db-create` **历来不提供** Argon2 变体（d/id）、Argon2 版本（1.0/1.3）
与 `t`/`m`/`p` 的直接开关。因此：

- `--check` 会**如实探测**并打印它在 `db-create --help` 中实际看到的 KDF 相关开关（可能为空）；
- `--generate` 建出的库**很可能**与本目录要求的参数不符。此时脚本**不会**产出「文件名撒谎」
  的语料——它会因为「文件名 ≠ 文件头」或「参数不符」而**非零退出**并给出指引；
- 因此**推荐路径是 §2 的第 4 步**：用官方 GUI 按 README §3.2 建库（GUI 可以精确设定全部参数），
  再用 `--ingest` 完成复核、命名、写 JSON 与双落位。

> 这正是本脚本的价值定位：把「易错的人工步骤」压缩为「GUI 建库（无法自动化）→ 一条命令复核落位」，
> 而不是假装 CLI 能做它做不到的事。

---

## 4. 安全纪律

- 口令为**公开的一次性测试常量** `Test-Vector-Only-2026!`（与 `RealKdbxCorpusUnlockTest` 同值），
  经 **stdin** 传给子进程，**不落 argv**（避免出现在进程列表 / shell 历史里）；
- 库内只放占位条目（`Vector-Sample-1..3` / `vector-user` / `vector-not-a-real-secret`）；
- 伴生 JSON 强制 `passphraseIsThrowaway=true`、`containsRealData=false`，任一不成立即失败；
- **严禁**把任何真实密码库改个名当作语料入库。

---

## 5. 文件构成

| 文件 | 职责 |
|---|---|
| `kdbx_header.py` | KDBX4 外层头 + 变体字典的**只读**解析（零第三方依赖，仅标准库） |
| `generate_corpus.py` | CLI 驱动、复核、命名、伴生 JSON 生成与双落位 |
| `README.md` | 本文件 |
| `_drafts/` | `--generate` 的草稿目录（**不入库**，见 `.gitignore`） |

`kdbx_header.py` 的解析结果已用仓库既有夹具
`database/src/test/resources/fixtures/test_vault.kdbx` 交叉验证，输出与其在
`crypto/src/test/resources/argon2-interop/README.md` §5.1 中登记的参数
（Argon2d / V=19 / I=89 / M=64 MiB / P=4 / 32B 盐）逐项一致。
