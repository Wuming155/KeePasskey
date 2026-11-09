# 内置 Public Suffix List 刷新（ENG-20261028-39）

内置 PSL 是域授权与同站比较的数据基础。本工具只做两件事：**校验**候选表是否满足
`PublicSuffixList` 的装载前提，以及在维护者显式要求时**原子替换**随仓资源。
它不提供运行时在线更新——表随 APK 版本发布，运行期不联网、不读远端。

## 用途

- 固定来源、版本身份与刷新步骤，使「表是否过期、来自哪里、适用于哪个版本」可核对。
- 把装载前提（纯 LF、规则已小写、首尾无空白、保留 `VERSION` / `COMMIT` 与许可声明）
  前移到刷新入口，避免手工编辑 `.dat` 静默改变判定口径。

## 前提

- Python 3（仅标准库）。`--check` 未给 `--source` 时需要访问官方地址。
- 来源只允许 `https://publicsuffix.org/list/public_suffix_list.dat`。脚本拒绝最终响应
  被重定向到其它主机；本地文件也只应来自该地址，不使用镜像或其它 VCS 副本。
- 资源路径固定为 `app/src/main/resources/publicsuffix/public_suffix_list.dat`。

## 命令

```bash
# 只校验随仓资源（离线；提交前自查）
python tools/publicsuffix/refresh_psl.py --verify

# 与官方当前版本比对（只读，不写盘）
python tools/publicsuffix/refresh_psl.py --check

# 离线复核一份已下载的官方文件（同样不写盘）
python tools/publicsuffix/refresh_psl.py --check --source <path>/public_suffix_list.dat

# 校验通过后原子替换随仓资源
python tools/publicsuffix/refresh_psl.py --apply
```

刷新后按受影响范围复跑既有测试（Gradle 由主流程执行）：

```bash
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.passkey.*"
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 产物

- 原地替换 `public_suffix_list.dat`；临时件写在同目录，替换成功后不残留，失败时清理并保留原文件。
- stdout 为稳定键值，便于人工核对与脚本取用：
  - `PSL_VERSION` / `PSL_COMMIT` / `PSL_RULES`：随仓资源；
  - `PSL_CANDIDATE_VERSION` / `PSL_CANDIDATE_COMMIT` / `PSL_CANDIDATE_RULES`、
    `PSL_UPDATE_AVAILABLE`、`PSL_RULES_ADDED` / `PSL_RULES_REMOVED`：`--check` / `--apply` 的候选；
  - `PSL_WRITTEN`：`--apply` 是否实际写盘（内容一致时为 `0`）。

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 校验通过、比对完成或替换成功（内容未变亦为 0，以 `PSL_WRITTEN=0` 表达） |
| 1 | 内容或网络失败：候选不满足前提、读取失败、写入后复读不一致 |
| 2 | 参数错误（argparse） |

## 来源、版本适用性与过期边界

| 事实 | 记录位置 / 判定方式 |
|---|---|
| 来源 | 文件头的官方拉取地址说明；脚本拒绝非 `publicsuffix.org` 的最终响应地址 |
| 版本身份 | 文件头的 `// VERSION:` / `// COMMIT:`；由 `PublicSuffixListResourceTest` 机检 |
| 适用性 | 该快照随 APK 版本发布，服务 RP ID / 条目域的可注册域下限与同站比较；不承诺覆盖发布后新增的后缀 |
| 刷新时机 | 每个 release 前运行 `--check`；有更新时按上节流程刷新，并在变更说明记录 `VERSION` / `COMMIT` 与规则增删数 |

过期边界：未收录的新公共后缀会落到 PSL 默认规则 `*`，末标签被当作公共后缀，同一新后缀下的
不同租户可能被算作同一个可注册域（授权与同站分组**偏宽**）；反向情况（已删除后缀仍在表内）
只会**偏严**，表现为部分 RP ID 或条目域被拒。IDN 规则的 punycode 归一依赖运行时 `IDN.toASCII`
（宿主 JVM 与 Android ICU 版本可能不同），刷新后须在设备侧复跑 `DomainMatcherAndroidRuntimeTest`。
