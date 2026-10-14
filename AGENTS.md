# AGENTS.md

给 AI 编码代理的长期规则与文档入口。项目事实以代码、配置和测试为准；本文件不保存版本基线、项目百科或历史记录。

## 默认读取路径

普通开发任务默认只读以下三份文档：

1. 本文件：工作规则与专项入口。
2. [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md)：当前未闭环问题。
3. [工程规则](.codebuddy/rules/engineering-rules.md)：开始代码修改前必读。

其他文档只在任务相关时读取。`resolved/`、`records/`、历史安全审计、`references/` 和已闭环整改方案均为按需历史资料，默认不读。

## 长期工作规则

- 新发现的未解决问题登记到 `ACTIVE_ISSUES.md`，注明核实时间与方式；不要只留在聊天或脑中。
- 完成任务不自动创建永久 Markdown。需要长期保存的信息优先更新现有权威文档；只有确有持续使用价值且无法放入现有文档、代码、配置或测试时，才新增文档。
- 文档、注释使用简体中文。改文档后运行 `python tools/doc/check_md_links.py`；修改安全审计或复核报告后另运行 `python tools/audit/check_recheck_consistency.py`。
- 代码修改遵守工程规则。模块依赖保持单向，模块边界以 [架构文档](docs/architecture/ARCHITECTURE.md) 为准。
- 敏感数据使用 `CharArray` / `ByteArray` 并在适当生命周期清零；不得将秘密写入日志或普通字符串。Rust 原生秘密缓冲遵守 [敏感缓冲所有权契约](docs/architecture/敏感缓冲所有权契约.md)，不得自行引入 C/C++ 秘密缓冲管理。
- KDBX 互操作结论必须有独立实现端到端对拍；仅校验文件头或本仓自测不足以证明互操作。
- 参考项目仅按明确问题查阅：禁止无目标扫描 `参考项目/`，禁止修改或复制其中代码；需借鉴时再读 [`docs/references/`](docs/references/) 内对应资料。
- 修改 Rust/JNI/Native 加密分派或探活，必须在 `crypto`、`database`、`sync`、`app` 四层实跑 `connectedDebugAndroidTest`；新增或修改 `androidTest` 用例也必须实跑，并确认测试非空。不得把“构建成功”当作测试通过。
- 设备侧测试不得对存有需要保留数据的应用直接运行可能卸载应用的测试包；使用隔离 AVD，或确认设备数据可丢弃后再测。
- Android、Kotlin、Gradle、加密或平台 API 的不确定行为，查官方知识源；第三方库查对应官方文档，不凭记忆猜测。
- 代码与对应文档同批提交；完成任务后按项目约定推送。只暂存本次明确路径，禁止用 `git add -A` 或 `git add .` 纳入他人改动。

## 按任务读取专项资料

- 产品行为或架构取舍：[`产品裁决登记.md`](docs/architecture/产品裁决登记.md)。
- 已接受边界或残余风险：[`已知工程限界.md`](docs/architecture/已知工程限界.md)。判断问题状态仍以 `ACTIVE_ISSUES.md` 为准。
- 同步、合并或防回滚：[`同步层记录级完整性威胁建模.md`](docs/security/同步层记录级完整性威胁建模.md)。
- 安全条目复核、严重度重评或发布安全审查：[`SECURITY_RECHECK_2026-09.md`](docs/security/SECURITY_RECHECK_2026-09.md)。这是历史复核资料，仅在此类任务中读取。
- 原生性能或真机结论：[`原生Argon2真机验证记录.md`](docs/records/原生Argon2真机验证记录.md)。这是证据记录，仅在需要引用其结论时读取。
- 历史实现与验收追溯：从 [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) 按需查找；不作为普通开发入口。
- 脚本的判据、边界和退出码以脚本 docstring 为准。Android、Kotlin、Gradle 或第三方 API 的不确定行为应查官方文档或对应知识源，不凭记忆猜测。

项目文档入口见 [`docs/README.md`](docs/README.md)。
