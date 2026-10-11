# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。按 **P0 → P1 → P2 → P3** 降序排列，
> 每项含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需另立计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；
> 历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源
> （批次的来源、严重度映射与登记裁决一并归位到**各批次正文**，产品口径落
> [`产品裁决登记.md`](architecture/产品裁决登记.md)，已接受取舍落
> [`已知工程限界.md`](architecture/已知工程限界.md)）。
> **闭环纪律**：条目整改通过后**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），
> 并执行 `git commit & push`。
> **认领前复核前提**：条目正文的 `文件:行号` 一律是**核实时刻的快照**，认领时须先复核前提再开工（规则 6.1②）。

> **在册批次**：**2 条**（2026-10-11 数据流安全审查整批登记 `ISSUE-P3-579` ~ `ISSUE-P3-586` 八条，
> 其中 `ISSUE-P3-579` ~ `ISSUE-P3-584` 已逐一闭环，在册 `ISSUE-P3-585` ~ `ISSUE-P3-586`；六要素报告见
> [`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md)）。新条目附「核实时间点 + 核实方式」。
> 上一批 `ISSUE-P3-584`（两个 Provider 的 `@VisibleForTesting` client 注入参数位于 public 主构造签名，
> 任一生产构造点按名传 `client` 即整体跳过 TLS-only 连接规格、SSRF 连接期两层守卫与构造期 HTTPS 强制，
> 属绕过网络防线的最短单行改动路径）已于 **§499** 整条闭环归档——按 AC① **优先采纳 (a)「把注入通道移出
> public 主构造签名」**：两 Provider 主构造改 `private`（`client` 置于首位以区别于二级构造形参），生产侧仅经
> 无 `client` 形参的 public 二级构造进入，测试注入经 `internal` 二级构造（仅同模块 `src/test` / `src/androidTest`
> 可见，`app` 不可见）；3 个生产构造点 6 处与 androidTest 两处注入点**源码零改动**（androidTest 未改动 ⇒ 依测试
> 资产纪律无需真机实跑，本环境亦禁跑设备）。以**负向编译对照**实测举证：在 `app/src/main` 按名传 `client`
> 由「可编译」变为编译错误（`Cannot access 'constructor(…, client: OkHttpClient)': it is internal`），单行旁路被
> 结构性封死。AC② 以 `:sync:lintDebug` + 临时探针实测：lint **不**报错于 `private val` / 普通构造参数上的
> `@VisibleForTesting`（0 issue），且 `VisibleForTests` 检测器对**跨模块生产源集按名传该构造参数**亦不触发
> （app lint 报告中探针零命中）⇒ lint 对本面**无拦截力**，结构移除是必要而非冗余。正文见
> [`resolved/batches/499-Provider注入通道移出主构造批次.md`](resolved/batches/499-Provider注入通道移出主构造批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P3-583`（`android:allowBackup="false"` 备份面第一道闸门无任何静态守卫锁定——既有三例对
> `allowBackup` 取值零断言，改回 `true` 后无断言变红）已于 **§498** 整条闭环归档——`BackupAndSafHardeningTest`
> 新增一例，与既有「清单必须引用数据提取规则文件」同文件同 `readSource` 范式：先剔 XML 注释、再按
> `android:allowBackup="…"` 取属性值后 `assertEquals("false", …)`（布局无关 / 可分辨 / 注释免疫，未选最小化的
> `contains` 整串字面量）；反校把清单临时改 `true` 实测**仅该例红**（`expected:<[fals]e> but was:<[tru]e>`）后复原；
> 既有三例断言逐字未动、零删除；正文见
> [`resolved/batches/498-allowBackup第一道闸门静态守卫批次.md`](resolved/batches/498-allowBackup第一道闸门静态守卫批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P3-582`（app 模块四点落盘面未做仅属主权限收敛且固定临时名，与 sync 模块 0600/0700 自声明口径漂移）
> 已于 **§497** 整条闭环归档——按 AC① 采纳「**原语下沉 `core` 共享**」路线（未选「app 侧本地等价实现」：
> `SyncCacheFiles` 为 `sync` 内部可见性 `internal`、app 不可复用，而再复制一份权限常量与唯一临时名逻辑等于
> 再造可漂移拷贝、与本条根因相悖）：新增 `core` 的 `SecureFileWrite`（唯一临时名 / `fsync` 写入 / 原子替换 /
> 仅属主 0600、目录 0700），`SyncCacheFiles` 退化为薄委托（缓存域专有逻辑留 sync，行为零变更）；四点
> （`CloudVaultImporter` / `SyncRemoteVaultCopy` / `VaultFileCopy` / `KeyFileVaultCopyStore`）统一改用原语
> （固定名 → 唯一名、打开流即 0600、失败只删自己的 tmp、两下载链补 `fsync`），AC③ 以新拆出的
> `DatabasePickerImportController` 补云端导入提交互斥（busy 同步置位 + `finally` 复位 + `isLoading` 投影）。
> AC② 设备侧 stat 用户裁决走**如实登记**分支：本环境禁跑设备侧且新增 `androidTest` 须真机实跑，二者互斥，
> 故不新增等价 stat 断言，缺口登记 [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§43**。
> 新增用例 8 例（core 原语 5 + 导入互斥 1 + `CloudVaultImporterTest` 2），既有用例零删除；正文见
> [`resolved/batches/497-app四点落盘面权限收敛批次.md`](resolved/batches/497-app四点落盘面权限收敛批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-581`（1PUX 导入解析的 `export.data` 中间副本——整份含全部明文口令的 JSON 字节——用毕不清零）
> 已于 **§496** 整条闭环归档——按 AC②**优先采纳「改造接口使可擦除」**路线（未选「登记限界」）：
> `PuxArchiveReader.readBounded` 弃用内部缓冲对调用方不可达的 `ByteArrayOutputStream`，改用自持可擦缓冲
> `ErasableByteSink`（容量增长切换旧缓冲前先 `fill(0)`，`finally` 中 `wipe()` 覆盖成功 / 超限两路径）；
> 解压分片起始读缓冲同步在 `readEntry` 的 `finally` 擦除；`parseOrThrow` 的 `exportData` 由 `try/finally`
> 包裹、成功与失败路径一致 `fill(0)`（AC①）。故**无需新增限界条目**——原「不可达副本」已由接口改造消除。
> 新增用例 6 例（`PuxIntermediateWipeTest`：3 例 `ErasableByteSink` 行为断言 + 3 例源码接线守卫），
> 反校 5 处变异实测红；正文见
> [`resolved/batches/496-1PUX解析中间副本清零批次.md`](resolved/batches/496-1PUX解析中间副本清零批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-580`（KDBX 并入凭据链「原件清零」契约落空：`KdbxMergeController.submitCredentials`
> 的 KDoc 声称「借用语义：本方法消费后在 finally 清零」，实际正常路径只清 `copyOf` 副本、调用方持有的
> 第二库主密码 `CharArray` 与密钥文件字节在正常路径不清零，且同函数仅两个早退分支代擦原件，语义自相矛盾）
> 已于 **§495** 整条闭环归档——按 AC① 采纳「**改契约**」路线：契约收敛为「**控制器只清副本、原件由调用方清零**」
> （控制器对两入参为 B 类**只读借用**，全路径不清原件，早退分支的顺手代擦一并移除）；
> 原件清零**收口点唯一**落在链首 `SettingsImportPresenter.submitMergeCredentials` 的 `finally`
> （正常 / 异常 / 控制器缺失三路径一致清 `passwordChars` 与 `keyBytes`），三层 KDoc 与
> `ImportMergeCredentialSheet` KDoc 按实现逐处更正；新增纯 JVM 用例 5 例
> （`KdbxMergeCredentialOwnershipTest`，含两层借用负向锁），反校两式均实测红；所有权契约文档
> `architecture/敏感缓冲所有权契约.md` 同步补 §4 #20 收口点登记行与 §8.1 机检行（AC②）；
> 正文见
> [`resolved/batches/495-并入凭据链原件清零收口批次.md`](resolved/batches/495-并入凭据链原件清零收口批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-579`（主密码解锁失败节流出厂默认关闭、三处 KDoc 口径互相矛盾、支撑出厂关闭的
> 「2026-09-12 用户裁决」未按纪律登记）已于 **§494** 整条闭环归档——口径**收敛为一套**：出厂默认 `false`
> 由用户裁决承载（补登 `PD-84`，`find_decision.py` 可检索）、`ThrottleConfig` 无参构造 / 冷启动窗口的 `true`
> 明示为**安全回落**而非出厂设置；冷启动窗口按 AC③ **收口**（回落值改 `enabled = true`，已开启节流者锁定期内
> 杀进程重启不再被放行，设置流抵达后按用户偏好收敛）；本批**未改任何出厂默认值**（改默认属产品裁决）；
> 新增纯 JVM 用例 4 例锁定冷启动回落与偏好收敛；正文见
> [`resolved/batches/494-解锁节流口径收敛与冷启动收口批次.md`](resolved/batches/494-解锁节流口径收敛与冷启动收口批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-578`（HIBP 泄露库查询响应体经 `response.body.string()` 整体物化、无尺寸封顶，与 sync 侧
> `ISSUE-P0-09` 已整改的「无界读取」同型）已于 **§493** 整条闭环归档——响应体读取改为**有界流式**：
> 闸门①申报 `Content-Length` 超限即**不读体**拒绝、闸门②读后累计越界即拒（chunked / 声明失真兜底），
> 两者同用纯函数 `exceedsRangeBodyLimit` 判定，上限 `MAX_RESPONSE_BYTES = 1 MiB`（不跨模块复用 sync 侧
> `SyncDownloadLimits`，按其语义等价实现后转抛 `BreachCheckException`）；纯函数 + MockWebServer 端到端 +
> 源码接线守卫三层锁定；正文见
> [`resolved/batches/493-HIBP响应体尺寸封顶批次.md`](resolved/batches/493-HIBP响应体尺寸封顶批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-577`（自动填充会话授权宽限的豁免判据只排除「携带口令值」的数据集、未把当前 TOTP 直填值计入敏感值，
> 宽限期内「仅用户名 + OTP 框、无口令框」表单免确认直填当前验证码）已于 **§492** 整条闭环归档——
> 敏感值判据由 `datasetCarriesPassword` 扩义为 `datasetCarriesSensitiveValue`（口令 **或** 当前 TOTP 直填值），
> 新增纯函数 `carriesSensitiveValue` / `totpIsDeliverable`（与交付面同口径、排除 HOTP）并令
> `unlockedConfirmationPolicy` 接收 `otpId` 同口径统计；纯策略面用例 + 静态接线守卫双锁定；正文见
> [`resolved/batches/492-自动填充宽限豁免判据纳入TOTP批次.md`](resolved/batches/492-自动填充宽限豁免判据纳入TOTP批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-576`（自定义图标上传通道对 SAF 流无大小闸门、与附件通道防护不对等）已于 **§491** 整条闭环归档——
> 图标通道接入「申报长度先拦（超限不读流）+ 读后实读兜底（超限 `fill(0)` 清零后拒绝）」双闸门；上界由附件上限
> **同源派生**（1/4，≈ 16 MiB − 1，`AttachmentSizeLimits.MAX_CUSTOM_ICON_UPLOAD_BYTES`），判定抽纯函数 +
> 源码接线静态守卫双锁定；正文见
> [`resolved/batches/491-图标上传通道尺寸双闸门批次.md`](resolved/batches/491-图标上传通道尺寸双闸门批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P3-575`（`OpenVaultEntryActivity` 对 URI 无 scheme 校验并静默覆写活动库指针，任意普通 App 零前置可达）已于 **§490** 整条闭环归档——
> 外部打开入口收敛为「仅接受 `content://` scheme（`file://` 等一律拒绝）+ 纯文件名 `.kdbx` 显示名」，登记前**必须用户确认**（外部输入不再静默改状态），
> 且 `unlockActiveDatabase` 对含 scheme 前缀 / 相对路径 fail-closed；白名单理由与代码同步修正；正文见
> [`resolved/batches/490-外部打开URI准入与登记确认批次.md`](resolved/batches/490-外部打开URI准入与登记确认批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P2-574`（CI 全部门禁判定本体无完整性护栏、单行削弱判据后持续假绿）已于 **§489** 整条闭环归档——
> `hygiene-gate` **首步**以受审清单 `tools/doc/gate_scripts.sha256` 钉定 19 个门禁脚本（现列 16 条机检 + 跨
> workflow 三脚本 `.github/check_dependency_cvss.py` / `tools/device/check_connected_device_results.py` /
> `tools/passkey-interop/verify_interop.py`）的 SHA-256（脚本漂移 / 缺失 / 清单缺项 / 清单多项任一即 fail-closed
> 报红），机检条数 15 → 16；残余取舍（清单 + 校验脚本自身同处 PR 可改面、workflow yml 自身与触发面未纳入基线）
> 登记限界表 §42；正文见
> [`resolved/batches/489-门禁脚本完整性基线批次.md`](resolved/batches/489-门禁脚本完整性基线批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P2-573`（Gradle 配置期执行 PR 可改脚本读取签名口令）已于 **§488** 整条闭环归档——
> 凭证管理器通道收窄为「仅命令行显式请求 release 分发产物时启用」，执行前对 `tools/signing/read-cred.ps1`
> 做 SHA-256 完整性基线校验（漂移 / 缺失即 fail-closed 中止构建），并以 `SigningCredentialChainHardeningTest`
> 静态锁定；残余取舍登记限界表 §41；正文见
> [`resolved/batches/488-签名口令链构建期闸门与完整性基线批次.md`](resolved/batches/488-签名口令链构建期闸门与完整性基线批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P2-572`（WebDAV 409/423 兜底「DELETE 后无预条件 MOVE 重试」并发覆盖窗口）已于 **§487** 整条闭环归档——
> 兜底 DELETE 前重探目标元数据（ETag 漂移 ⇒ ConflictError 不 DELETE；探测失败 fail-closed；相同 / 缺失放行），
> 限界表 §28 补记「409/423 服务器类」未实测样本；正文见
> [`resolved/batches/487-WebDAV覆盖兜底重探守卫批次.md`](resolved/batches/487-WebDAV覆盖兜底重探守卫批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-570`（`runCatching` 的协程取消语义与 `catch` 面同型、未逐一收口）已于 **§486** 整条闭环归档——
> 机检并入 `check_cancellation_semantics.py`（runCatching 面 + `LaunchedEffect` 构造补入），红 23 → 绿 0；
> 正文见 [`resolved/batches/486-协程runCatching取消语义并入机检批次.md`](resolved/batches/486-协程runCatching取消语义并入机检批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-571`（自动填充「关联记忆」无感知 / 不随库迁移）已于 **§485** 整条闭环归档——方案 A
> （Kp2a 式询问写回条目 URL）落地，交互口径裁决登记 `PD-83`；正文见
> [`resolved/batches/485-自动填充记住应用关联询问写回批次.md`](resolved/batches/485-自动填充记住应用关联询问写回批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P3-569`（`catch (Throwable)` 的协程取消语义逐处收口）已于 **§484** 整条闭环归档——正文见
> [`resolved/batches/484-协程catch取消语义逐处收口批次.md`](resolved/batches/484-协程catch取消语义逐处收口批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)；该批的逐处判定表（36 处补重抛 / 21 处判不适用并就地标注）
> 与机检读数留痕见该批次正文。
> 再上一批 2026-10-09 第二轮多维深度审查登记的**十三条**（`ISSUE-P2-556` / `ISSUE-P2-557` /
> `ISSUE-P3-558` ~ `ISSUE-P3-568`）已于 **§483** 整批闭环归档——正文见
> [`resolved/batches/483-存量十三条整改批次.md`](resolved/batches/483-存量十三条整改批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)；该批的来源证据与「被推翻 / 降档的代理结论」留痕见
> [`records/多维深度审查报告_2026-10-09.md`](records/多维深度审查报告_2026-10-09.md)。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。（行号一律视为「核实时刻的快照」，见文首。）

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**（`ISSUE-P0-531` 已于 §473 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-537` / `ISSUE-P1-538` 已于 §477 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（`ISSUE-P2-574` 已于 §489 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P3 低危问题、特性接线与体验优化（2 项）

### `ISSUE-P3-585`：fast-gate 互操作对拍依赖浮动拉取（pip/apt 均无版本 pin），与本仓供应链纪律不一致

**背景与影响**：`build.yml:189-192` 在 fast-gate 内 `apt-get install keepassxc` 与
`pip install pykeepass cryptography` **均无版本 pin**，`:194-197` 调用的 `verify_interop.py` 对拍判据
（cryptography 独立解析 PEM `:155-173`、keepassxc-cli 交叉读 `:176-200`、pykeepass 字段与保护位判据 `:208-308`）
完全运行在浮动拉取包所在解释器进程内（`tools/passkey-interop/verify_interop.py:55-60` import 即执行；
`:391` 仅打印版本、无兼容性断言）——上游包被投毒或发布含行为变更的新版本时，可伪造「对拍通过」输出与退出码，
或吞掉判据失败。前提（上游投毒）当前无证据且不在威胁模型能力清单内；与本仓既有纪律
（`build.yml:14` Actions 全 SHA 钉死、`cargo --locked`）显式不一致。

**验收标准**：① `pykeepass` / `cryptography` / `keepassxc` 版本 pin（含留痕的升级流程），与 Actions SHA 钉死纪律同构；
② `verify_interop.py` 的版本兼容性断言口径一并补齐或登记。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 13（verdict=confirmed）。
来源攻击面：CI/CD 与供应链。

### `ISSUE-P3-586`：PD-04 登记口径与代码漂移——「工作流照常在 push/PR 上运行」自 ISSUE-P3-316 起已失真

**背景与影响**：`docs/architecture/产品裁决登记.md:141` 逐字声称「工作流照常在 `push{main}` 与 `pull_request`
上运行」、`:158`「已在位且同时在 push 与 PR 上运行」——但三个 workflow 已于 `ISSUE-P3-316`
（2026-09-25，晚于 PD-04 的 2026-09-17 登记 8 天）全部改为 `schedule`+`workflow_dispatch`
（`build.yml:36-43`、`codeql.yml:31-36`、`dependency-scan.yml:27-31`），登记未同步：PD-04 理由 3 依赖的
「闸门即时运行」面已不存在，合并进 main 的内容**合并前无任何 GitHub Actions 自动检测**、合并后最长 24h
（供应链扫描 / CodeQL 每周）才被巡检；两条各自登记过的取舍（PD-04 + ISSUE-P3-316）组合出的新残余风险
未被任何登记承载（`find_decision.py`「P3-316/巡检」两表 no_match）。

**验收标准**：① PD-04 条目补记 `ISSUE-P3-316` 后的触发面现状与「合并前零自动检测 / 合并后延迟巡检」残余风险
（或迁移登记进 `已知工程限界.md`）；② `find_decision.py` 检索口径与文本一致；
③ 与 `ISSUE-P2-574` 的修复动作汇合处互相引用。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](security/数据流安全审查_2026-10-11.md) 发现 14（verdict=confirmed）。
来源攻击面：CI/CD 与供应链。

