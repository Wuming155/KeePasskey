# KeePasskey 已整改问题与历史任务归档（Resolved Log）

> **文档定位**：已完成修复的缺陷、已落地特性与已闭环技术债的**归档入口与近况台账**——保留全量批次索引与最近 10 个批次正文，更早批次按分册下沉，用于防回退与历史溯源。
> **维护规则**：`ACTIVE_ISSUES.md` 的任务经整改并通过测试后，整条移入本文件末尾（新批次）。**如实留痕**：整改中真实发生的生产/测试/编排缺陷均不美化、不隐去（详见各批次「过程缺陷」一行）。
> **分册归档（2026-09-15 立规）**：本文件只保留**全量批次索引**与**最近 10 个批次**正文（当前 §58 ~ §67）；§1 ~ §57 正文已下沉 [`docs/resolved/`](resolved/)。下沉规则：每满足 10 个新批次，把最旧的批次整节移入对应分册或按同粒度新建分册，并同步本文件索引。
> **§ 引用定位**：仓内其它文档以「`RESOLVED_LOG.md` §NN」形式引用批次；§1 ~ §57 的正文现在分册文件中——**按下方索引的章节链接直接跳转**，主文件不再重复收录。

---

## 批次索引（全量 §1 ~ §67）

### §1 ~ §30 —— 分册 [docs/resolved/RESOLVED_LOG_BATCH_01_30.md](resolved/RESOLVED_LOG_BATCH_01_30.md)

| 章节 | 批次 | 条目范围 |
|---|---|---|
| [§1](resolved/RESOLVED_LOG_BATCH_01_30.md#s1) | 已完成核心任务 | `TASK-01` ~ `TASK-53` |
| [§2](resolved/RESOLVED_LOG_BATCH_01_30.md#s2) | 历史全量代码审计（93 项 + 4 类专项 + ZT/P1/P2 逐项） | 2.1 ~ 2.22 |
| [§3](resolved/RESOLVED_LOG_BATCH_01_30.md#s3) | P3 批次（16 项） | ISSUE-P3-01 ~ P3-16 |
| [§4](resolved/RESOLVED_LOG_BATCH_01_30.md#s4) | P3 残余批次（12 项） | ISSUE-P3-17 ~ P3-28 |
| [§5](resolved/RESOLVED_LOG_BATCH_01_30.md#s5) | P3-30 单条（子库条目只读投影） | ISSUE-P3-30 |
| [§6](resolved/RESOLVED_LOG_BATCH_01_30.md#s6) | P3-29 批次 A（全仓超阈值债务） | ISSUE-P3-29 |
| [§7](resolved/RESOLVED_LOG_BATCH_01_30.md#s7) | CI 首跑实测整改（lint / CodeQL / 供应链闸门） | ISSUE-P3-32 |
| [§8](resolved/RESOLVED_LOG_BATCH_01_30.md#s8) | 原生内核扩展与工程化（AES-KDF / Twofish / 口令强度 / HMAC / 语料） | ISSUE-P3-34 ~ P3-38 |
| [§9](resolved/RESOLVED_LOG_BATCH_01_30.md#s9) | 自动填充能力对标 | ISSUE-P3-39 ~ P3-45 |
| [§10](resolved/RESOLVED_LOG_BATCH_01_30.md#s10) | P3-31 批次 B（超阈值债务） | ISSUE-P3-31 |
| [§11](resolved/RESOLVED_LOG_BATCH_01_30.md#s11) | P3-31 批次 C + P3-43 闭环 | ISSUE-P3-31 / P3-43 |
| [§12](resolved/RESOLVED_LOG_BATCH_01_30.md#s12) | P3-31 批次 D + P3-46 闭环 | ISSUE-P3-31 / P3-46 |
| [§13](resolved/RESOLVED_LOG_BATCH_01_30.md#s13) | P3-31 批次 E（超阈值债务） | ISSUE-P3-31 |
| [§14](resolved/RESOLVED_LOG_BATCH_01_30.md#s14) | P3-31 批次 F（超阈值债务） | ISSUE-P3-31 |
| [§15](resolved/RESOLVED_LOG_BATCH_01_30.md#s15) | P3-31 批次 G（超阈值债务） | ISSUE-P3-31 |
| [§16](resolved/RESOLVED_LOG_BATCH_01_30.md#s16) | P3-31 批次 H（超阈值债务） | ISSUE-P3-31 |
| [§17](resolved/RESOLVED_LOG_BATCH_01_30.md#s17) | P3-31 批次 I（超阈值债务 · **本条闭环**） | ISSUE-P3-31 |
| [§18](resolved/RESOLVED_LOG_BATCH_01_30.md#s18) | CI Fast gate 偶发红根因修复（测试调度器污染） | 测试基础设施 |
| [§19](resolved/RESOLVED_LOG_BATCH_01_30.md#s19) | dependency-scan CI 侧首跑留痕 + 断言可观测性修复 | ISSUE-P3-24 / P3-32 |
| [§20](resolved/RESOLVED_LOG_BATCH_01_30.md#s20) | 功能完整性审计批次 A（全文搜索范围 / 详情页单条删除） | ISSUE-P3-47 / P3-48 |
| [§21](resolved/RESOLVED_LOG_BATCH_01_30.md#s21) | 功能完整性审计批次 B（HOTP 端到端 / 便利入口 / 孤儿实现清理） | ISSUE-P3-49 / P3-50 / P3-51 |
| [§22](resolved/RESOLVED_LOG_BATCH_01_30.md#s22) | 存量问题由易到难整改闭环批次（P1-11 / P2-17 / P2-18 / P3-52~P3-56） | ISSUE-P1-11 / P2-17 / P2-18 / P3-52 ~ P3-56 |
| [§23](resolved/RESOLVED_LOG_BATCH_01_30.md#s23) | CI 侧真实跑通归档 + CodeQL Rust 误报治理（P3-24 / P3-32 / P3-57 闭环） | ISSUE-P3-24 / P3-32 / P3-57 |
| [§24](resolved/RESOLVED_LOG_BATCH_01_30.md#s24) | 设备侧实测发现的致命缺陷修复（Android 端 KDBX XML 解析全量失败） | ISSUE-P1-12 |
| [§25](resolved/RESOLVED_LOG_BATCH_01_30.md#s25) | 设备侧互操作语料入库与端到端解锁跑绿（真实 KeePassXC `.kdbx`） | ISSUE-P3-23 |
| [§26](resolved/RESOLVED_LOG_BATCH_01_30.md#s26) | 设备侧手工实操发现的 P0 崩溃修复（字段引用正则在 Android ICU 上非法） | ISSUE-P0-04 |
| [§27](resolved/RESOLVED_LOG_BATCH_01_30.md#s27) | 设备侧功能实测收口 + 9 项缺陷整改（2026-09-11） | 见 §27 |
| [§28](resolved/RESOLVED_LOG_BATCH_01_30.md#s28) | 存量问题修复批次（快捷脱敏 / 关闭校验 / 外部存储清理） | ISSUE-P2-22 / P3-63 / P3-65 / P3-67 |
| [§29](resolved/RESOLVED_LOG_BATCH_01_30.md#s29) | 用户报告修复批次（复合封印指纹解锁 / 重试节流可配置 / FLAG_SECURE 语义修订） | ISSUE-P2-23 / P3-68 |
| [§30](resolved/RESOLVED_LOG_BATCH_01_30.md#s30) | 外部安全审计核实与整改批次（Wrapper 哈希 / 字节清零 / 扫码防截屏 / 许可证注释） | ISSUE-P3-69 ~ P3-72 |

### §31 ~ §45 —— 分册 [docs/resolved/RESOLVED_LOG_BATCH_31_45.md](resolved/RESOLVED_LOG_BATCH_31_45.md)

| 章节 | 批次 | 条目范围 |
|---|---|---|
| [§31](resolved/RESOLVED_LOG_BATCH_31_45.md#s31) | 文档类存量整改批次（隐私政策 / 同步层威胁建模） | ISSUE-P3-75 / P3-77 |
| [§32](resolved/RESOLVED_LOG_BATCH_31_45.md#s32) | 存量功能整改批次（CSV 导入 / 导出扩充） | ISSUE-P3-73 |
| [§33](resolved/RESOLVED_LOG_BATCH_31_45.md#s33) | 产品裁决：不排期 / Won't Do（对标项与外部依赖项） | ISSUE-P2-25 / P2-26 / P2-27 / P3-23 / P3-58 / P3-66 / P3-74 |
| [§34](resolved/RESOLVED_LOG_BATCH_31_45.md#s34) | app 设备侧验证骨架与导入解析回归 | ISSUE-P2-27 / P3-66（部分收窄） |
| [§35](resolved/RESOLVED_LOG_BATCH_31_45.md#s35) | ISSUE-P2-24 大附件磁盘缓存池（阶段 1/2/3 全量落地） | ISSUE-P2-24 |
| [§36](resolved/RESOLVED_LOG_BATCH_31_45.md#s36) | ISSUE-P2-27 设备侧验证缺口收口（app + sync） | ISSUE-P2-27 |
| [§37](resolved/RESOLVED_LOG_BATCH_31_45.md#s37) | 工程整洁与文档准确性收口 | 工程整洁 / 文档准确性 |
| [§38](resolved/RESOLVED_LOG_BATCH_31_45.md#s38) | KDBX 互操作与安全整改批次（P0×3 / P1×6 / P2×14 + 文档纪律） | ISSUE-P0-05~07 / P1-16~21 / P2-28~41 / P3-81 |
| [§39](resolved/RESOLVED_LOG_BATCH_31_45.md#s39) | 红队攻击路径批次处置归档（报告退役 + 存量项转登 ACTIVE_ISSUES） | 转登 ISSUE-P1-22~24 / P2-43~47 / P3-82~85 |
| [§40](resolved/RESOLVED_LOG_BATCH_31_45.md#s40) | 外部安全审计报告退役批次（报告退役 + 存量项转登 ACTIVE_ISSUES） | 转登 ISSUE-P2-48 ~ P2-60 / P3-86 ~ P3-97 |
| [§41](resolved/RESOLVED_LOG_BATCH_31_45.md#s41) | 敏感数据流审计报告退役与分流（`SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`） | 转登 ISSUE-P2-61 ~ P2-74 / P3-98 ~ P3-115 |
| [§42](resolved/RESOLVED_LOG_BATCH_31_45.md#s42) | 威胁建模与架构评估报告退役批次（`THREAT-MODEL-AUDIT-d32f3e7.md` 退役 + 存量项转登） | 转登 ISSUE-P2-76 ~ P2-79 / P3-116 ~ P3-124 |
| [§43](resolved/RESOLVED_LOG_BATCH_31_45.md#s43) | 安全问题与整改方案报告退役批次（`SECURITY_AUDIT_REMEDIATION.md` 退役 + 附录 A–F 留存） | 处置归档（正文 32 项核对无缺口，附录 A–F 留存 §43.3 ~ §43.8） |
| [§44](resolved/RESOLVED_LOG_BATCH_31_45.md#s44) | P0 双项整改批次：字段引用消费点白名单 + 同步崩溃面遏制 | ISSUE-P0-08 / ISSUE-P0-09 / ISSUE-P2-75 |
| [§45](resolved/RESOLVED_LOG_BATCH_31_45.md#s45) | P1 双项整改批次：确认页调用方归属与首次绑定授权 + 剪贴板口令面引用敏感通道 | ISSUE-P1-24 / ISSUE-P1-25 |

### §46 ~ §57 —— 分册 [docs/resolved/RESOLVED_LOG_BATCH_46_57.md](resolved/RESOLVED_LOG_BATCH_46_57.md)

| 章节 | 批次 | 条目范围 |
|---|---|---|
| [§46](resolved/RESOLVED_LOG_BATCH_46_57.md#s46) | P1 双项整改批次：软件级 Keystore 快速解锁降级确认 + 重打包威胁告知留痕 | ISSUE-P1-22 / ISSUE-P1-23 |
| [§47](resolved/RESOLVED_LOG_BATCH_46_57.md#s47) | 设备侧真机基线批次：两条「模拟器环境假设」用例整改 + arm64 真机全量实测 | 设备侧用例缺陷（无编号） |
| [§48](resolved/RESOLVED_LOG_BATCH_46_57.md#s48) | 存量安全整改批次：KDF 预算 + TOTP 保护 + 剪贴板闭环 + 明文持有者锁观察者 + 换库前置释放 + 附件引用预算 + 完整性门控对称化 + Passkey 归属与验证绑定 | ISSUE-P2-48 / P2-51 / P2-53 / P2-61 / P2-63 / P2-65 / P2-72 / P2-76 / P2-77 / P3-84 / P3-109 / P3-117（+ P2-49 AC①③ 进展） |
| [§49](resolved/RESOLVED_LOG_BATCH_46_57.md#s49) | 存量安全整改批次（续）：密钥文件纯字节解析 + DAL 有界流式读取 + 选择器会话锁定对齐 + CM 保存 URL 分流 + KDF 参数与秘密治理 + 依赖扫描触发面 | ISSUE-P2-50 / P2-52 / P2-54 / P2-55 / P2-56 / P2-57 / P2-58 / P2-59 / P2-60 / P2-62 / P2-78 |
| [§50](resolved/RESOLVED_LOG_BATCH_46_57.md#s50) | 日志与对象字符串化卫生批次：四类 `toString()` 明文泄漏面 + 日志抽样口径按「日志调用」特征跨行抽取 | ISSUE-P2-68 / ISSUE-P2-69 |
| [§51](resolved/RESOLVED_LOG_BATCH_46_57.md#s51) | 自动填充默认值与内存保护口径批次：TOTP 复制 / IME 内联建议默认关闭 + 内存密封与写出标志口径分离 + unlink-only 边界登记 | ISSUE-P2-43 / P2-64 / P2-66 / P2-71 |
| [§52](resolved/RESOLVED_LOG_BATCH_46_57.md#s52) | 同步解析落盘与内存池擦除边界批次：远端大附件解析期落盘 + 树外可达性收口 + 单次解析产物显式擦除 | ISSUE-P2-67 / P3-119 |
| [§53](resolved/RESOLVED_LOG_BATCH_46_57.md#s53) | 自动填充请求方归属与授权宽限收窄批次：选择器页强制展示请求方身份 + 不可归属域不再享受免重复确认 | ISSUE-P2-70 / P2-81 |
| [§54](resolved/RESOLVED_LOG_BATCH_46_57.md#s54) | 包可见性与序列化缓冲擦除批次：最小 `<queries>` 恢复调用方指纹可读 + 整库序列化缓冲具名擦除 | ISSUE-P2-74 / P3-118 |
| [§55](resolved/RESOLVED_LOG_BATCH_46_57.md#s55) | 多签名者匹配批次：签名轮换期以「调用方全部签名摘要」参与判定（任一命中即通过） | ISSUE-P3-93 |
| [§56](resolved/RESOLVED_LOG_BATCH_46_57.md#s56) | 清单权限口径与确认回传门控批次：弃用权限 `tools:node="remove"` + 会话锁定即丢弃未决响应 | ISSUE-P3-94 / P3-95 |
| [§57](resolved/RESOLVED_LOG_BATCH_46_57.md#s57) | 清零与规则一致性批次：CBC 加密流明文副本清零 + `AppLog` 剥离规则签名修正 + 换密密钥快照副本清零 | ISSUE-P3-96 / P3-98 / P3-99 |

### §58 ~ §67 —— 本文件正文

| 章节 | 批次 | 条目范围 |
|---|---|---|
| [§58](#s58) | 凭据中间量清零批次：远端路径解析凭据擦除 + S3 签名拼接缓冲擦除 + 口令 SHA-1 改字节态 | ISSUE-P3-100 / P3-101 / P3-102 |
| [§59](#s59) | 附件字节所有权与敏感窗口接线批次：TOTP 取景窗口补遮挡触摸过滤 + `KdbxAttachment` KDoc 口径更正 + 附件读取双拷贝消除与写出侧交付副本清零 | ISSUE-P3-103 / P3-104 / P3-105 |
| [§60](#s60) | 退路面加固与来源登记批次：备份 / 迁移排除域穷举补全 + SAF 删除前文档 URI 归属判定 + Gradle 分发镜像来源登记 | ISSUE-P3-106 / P3-112 / P3-115 |
| [§61](#s61) | fail-closed 可观测性批次：字段屏蔽签名密钥不可用由静默转为健康自检显式告警 | ISSUE-P3-113 |
| [§62](#s62) | 导出确认令牌下沉与文案一致性批次：明文导出令牌不可伪造 + 剪贴板 / 备份开关文案按实现更正 + `.kdbx.bak` 保留期登记 | ISSUE-P3-107 / P3-110 / P3-114 |
| [§63](#s63) | 密钥文件导出确认批次：密钥文件导出纳入令牌门控 + 二次确认弹窗（§62 附带发现的收口） | ISSUE-P3-128 |
| [§64](#s64) | 第一轮审计 F 系列收尾批次：整库导出缓冲清零 + 明文导出令牌下沉（同根因）+ 审计短摘要熵修正 + 删除未加固 OTP URI 解析副本 + ChaCha20 标签纠偏 | ISSUE-P3-86 / P3-87 / P3-89 / P3-90 / P3-91 / P3-92 |
| [§65](#s65) | 浏览器指纹格式守卫批次：Chrome 签名指纹 65-hex 笔误三处同批修正 + 格式与双写法一致性断言 | ISSUE-P3-88 |
| [§66](#s66) | 构建 fail-closed 与版本策略声明批次（**两条目的 ③ 子项**）：原生内核构建失败不再静默降级 + KDBX 版本策略显式声明与死常量/死字段清理 | ISSUE-P3-125 ③ / P3-126 ③ |
| [§67](#s67) | CI 声明一致性与 DAL 不可重定向批次：CI 产物标注「非发布签名」+ 豁免清单说明去数字漂移 + DAL 端点/时钟改构造注入只读策略 | ISSUE-P3-126（整体）/ P3-125 ② |

> **各批次验收证据**（用例数 / 通过 / 失败 / 跳过）位于各自批次的「验收证据」小节：§1 ~ §57 见对应分册，§58 ~ §67 见本文件正文。

### 归档分册一览

| 分册 | 覆盖批次 | 正文 |
|---|---|---|
| 分册 01 | §1 ~ §30 | [`resolved/RESOLVED_LOG_BATCH_01_30.md`](resolved/RESOLVED_LOG_BATCH_01_30.md) |
| 分册 02 | §31 ~ §45 | [`resolved/RESOLVED_LOG_BATCH_31_45.md`](resolved/RESOLVED_LOG_BATCH_31_45.md) |
| 分册 03 | §46 ~ §57 | [`resolved/RESOLVED_LOG_BATCH_46_57.md`](resolved/RESOLVED_LOG_BATCH_46_57.md) |
| 本文件 | §58 ~ §67（滚动保留最近 10 个批次） | `docs/RESOLVED_LOG.md` |

---

<a id="s58"></a>
## §58 凭据中间量清零批次（2026-09-15）：P3-100 / P3-101 / P3-102

> **本批次缘起**：认领三条**同族（RC-02 凭据中间量驻留）**存量项——`ISSUE-P3-100`
> （同步远端路径解析解密出完整凭据却不擦）、`ISSUE-P3-101`（S3 签名拼接缓冲含派生密钥材料
> 且未擦）、`ISSUE-P3-102`（口令 SHA-1 全文以不可擦 `String` 驻留）。三者共同特征：
> **函数只消费凭据的一小部分，却把整体物化在堆上，随后静默等待 GC。**

### 58.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-100** | P3 | `SyncProviderResolver.resolveRemotePath` 只取 `remotePath`（WebDAV）/ `objectKey`（S3），但 `loadWebDavConfig()` / `loadS3Config()` 会**解密出完整凭据**（含口令 / secretKey），方法返回后这些凭据无任何清零动作 | 两个分支各加 `try/finally`：WebDAV 分支擦 `cfg?.password`；S3 分支擦 `cfg?.accessKey` + `cfg?.secretKey`。**返回值只在 `try` 内计算**，`finally` 与返回值无关 | 审计 L5 / RC-02；`AGENTS.md` §3.2 |
| **ISSUE-P3-101** | P3 | `S3RequestSigner.getSignatureKey` 的 `combined`（`"AWS4"‖secretAccessKey`）是**派生密钥材料**，`prefix` / `keyBytes` 已在 `finally` 擦除而 `combined` 漏擦 | 返回值改为 `combined.copyOf()`（**独立副本**），`finally` 中补 `combined.fill(0)`——因返回的是副本，擦除原数组不影响签名结果 | 审计 L5 / RC-02 |
| **ISSUE-P3-102** | P3 | `BreachHasher` 的 `sha1HexUpper` 把 40 位摘要拼成 `String` 再 `splitPrefixSuffix` 切分 → **完整摘要以一个不可擦 `String` 全程驻留**；且 `MessageDigest.digest()` 返回的字节数组未清零 | 重写为 **字节态 API** `splitPrefixSuffixOfSha1(data: ByteArray)`：① `digest` 字节数组在 `finally` 中清零；② 改用 `UpperHexDigits` 逐半字节查表写入 `StringBuilder`（避免 `HexFormat` / `toHexString` 产生中间 `String`）；③ 仅在**最后一步**切出 `prefix` / `suffix` 两个分片，`StringBuilder` 在 `finally` 中逐字符置 `'\u0000'` 后清空；调用方 `BreachCheckCoordinator.collectCandidates` 改为传入 `passwordBytes` | 审计 L5 / RC-02；k-anonymity 边界见 58.3.1 |

### 58.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.sync.CredentialIntermediateWipeTest"
# → BUILD SUCCESSFUL；tests=5 skipped=0 failures=0 errors=0
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.data.breach.BreachHasherTest"
# → BUILD SUCCESSFUL；tests=4 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 12s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1721 failures=0 errors=0 skipped=13
#   （app 938 / core 65 / crypto 129 / database 386 / sync 203）——较上批 +5
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 3m 18s；216 actionable tasks: 28 executed, 188 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,159 字节，2026-09-15 14:08:44）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**产物鲜度核验（因字节数与前批相同而追加）**：`assembleRelease` 实测 28 个任务真实执行；
产物 `LastWriteTime` = 14:08:44，**晚于**本次 `:app:compileReleaseKotlin` 产物
（`app/build/intermediates/classes/release/transformReleaseClassesWithAsm/dirs/.../BreachHasher.class`
的 14:05:50）。对上述 release 期 class 逐字节检索确认：**新增方法
`splitPrefixSuffixOfSha1` 存在、旧私有方法 `sha1HexUpper` 已消失、
`SyncProviderResolver.resolveRemotePath` 存在**——即产物确由本批源码编译而来。
（**如实声明**：本批与 §57 的 APK 字节数恰好同为 15,463,159；§57 已声明其规则修正为零行为变更，
本批三项改动为「同尺寸语义替换」（`try/finally` 边界与查表循环的 dex 增量被压缩层的字节对齐吸收），
字节数一致**不构成**未重编译的证据，故此处以任务执行数 + class 内容检索双证据定论。）

**新增/重写用例（本批净 +5 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `CredentialIntermediateWipeTest`（+5，新文件） | **静态接线守卫**（源码正则，断言前剔除注释）：① `resolveRemotePath` 的 WebDAV 分支必须存在擦 `password` 的 `finally`；② S3 分支必须存在擦 `accessKey` + `secretKey` 的 `finally`；③ `getSignatureKey` 必须存在 `combined.fill(0)`；④ 且该擦除语句必须**位于** `combined.copyOf()` **之后**（防「先擦后拷贝 → 签名为全零」的顺序反转）；⑤ `getSignatureKey` 不得以 `return combined`（而非 `return combined.copyOf()`）返回——否则 `finally` 清零会破坏签名输入 |
| `app` | `BreachHasherTest`（重写，4 例） | ① 分片可**无损还原**完整 40 位摘要（前缀 5 + 后缀 35，证明字节态实现与 `String` 版语义等价）；② 输入 `ByteArray` **不被本函数修改**；③ 源码守卫：不得重新引入 `sha1HexUpper` / `toHexString(`（防回归到不可擦 `String` 路径）；④ 前缀必须为**大写**十六进制（HIBP 接口要求，小写会被服务端判为非法输入） |

### 58.3 已知边界与口径（如实声明）

1. **P3-102 的边界（重要，不得过度宣称）**：k-anonymity / HIBP Pwned Passwords 协议**本身要求**
   提交 5 位十六进制前缀、并在本地比对 35 位后缀——**后缀与前缀拼接即为完整摘要**，
   故无法消除「分片本身以 `String` 存在」。本批消除的是：① **完整 40 位摘要的单一 `String` 物化**；
   ② `MessageDigest.digest()` 字节数组的驻留；③ 十六进制编码过程中的中间 `String`（`HexFormat` 路径）。
2. **前缀必须是 `String` 与网络层签名一致**：`prefix` 需进入 HTTP URL 路径，`suffix` 需用于
   响应文本比对；两者均无法以 `CharArray` 形态交给 `OkHttp` / 文本比对 API，属**协议层不可消除**的驻留。
3. **P3-100 的擦除时机**：`finally` 与返回值**无关**——`resolveRemotePath` 的返回值为
   `String` 路径，在 `try` 块内已完成计算；凭据对象（`cfg`）在 `finally` 中擦除后不再被读取。
   该结构由用例① / ② 静态锁定，另由 `SyncProviderResolverTest` 既有行为用例保证路径解析语义未变。
4. **P3-101 的「先拷贝后擦除」顺序是不可交换的**：`combined.copyOf()` 必须在 `combined.fill(0)`
   **之前**求值（Kotlin `finally` 在 `return` 表达式求值之后执行），故语义安全；
   但若未来有人把 `copyOf()` 移入 `finally` 或改写为 `return combined`，签名将变为全零 `key`——
   用例④ / ⑤ 即为此设的**形状锁**。
5. **本批未触及的相邻项**：`ISSUE-P3-103`（`SecureCaptureActivity` 遮挡触摸过滤接线）、
   `ISSUE-P3-104`（`KdbxAttachment.data` KDoc 与实现口径）、`ISSUE-P3-105`
   （`VaultEntrySecretReader.getAttachmentData` 双重拷贝）、`ISSUE-P3-107`（`.kdbx.bak` 保留口径）
   等同族低危项仍为开放条目。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **39 → 36**（表行 32 → 29，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

### 58.4 与 P3-108 的交叉记录

本批全量单测**首次**观测到 `SyncCacheTest.clearAll 清空全部远端路径的缓存且目录为空` 失败，
残留物为 `cache/<hash>.BASEVERSION.tmp`；**单独复跑即通过**，随后的全量复跑（58.2 ②）亦通过。
按「不得静默放宽断言」纪律，本批**未**修改该断言，而是把实测证据（现象 / 触发条件 / 非确定性判定）
追加入 `ISSUE-P3-108` 正文，与其原有 `AtomicFileWriter` `.tmp` 残留窗口合并处置。

---

<a id="s59"></a>
## §59 附件字节所有权与敏感窗口接线批次（2026-09-15）：P3-103 / P3-104 / P3-105

> **本批次缘起**：认领三条经第一轮独立复核确认成立的低危项——`ISSUE-P3-103`
> （TOTP 取景窗口游离于加固体系外，缺遮挡触摸过滤）、`ISSUE-P3-104`
> （`KdbxAttachment` 类 KDoc 宣称「不与任何持有者共享可变引用」，与内存附件的**借用视图**实现矛盾）、
> `ISSUE-P3-105`（附件按需读取路径的双重拷贝，第一份副本无人清零）。
> 三条共同主题：**「文档宣称」与「实现口径」必须逐一对应，不一致处要么改实现、要么改文档。**

### 59.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-103** | P3 | `SecureCaptureActivity`（TOTP 二维码取景）只接了 `FLAG_SECURE` + `setHideOverlayWindows(true)`，**未**接遮挡触摸过滤；该类继承 zxing `CaptureActivity`，游离于 `FlagSecureGuard` 的 attach 体系（仅覆盖 `MainActivity` / `BaseCredentialActivity`）之外 | `onCreate` 中补 `window.decorView.filterTouchesWhenObscured = true`（与 `FlagSecureGuard.applyObscuredTouchFilter` 同口径——正确入口是 **View 层**，compileSdk 37 的 `android.view.Window` 无该方法）。**采「叠加」而非「留痕豁免」**：`setHideOverlayWindows` 只阻断**新绘制**的悬浮窗，对已存在的遮挡窗口无效 | 审计 L6；`AGENTS.md` §6 |
| **ISSUE-P3-104** | P3 | `KdbxAttachment` 类 KDoc 宣称「无论哪种来源，`data` / `openStream` 交付的字节都不与内层二进制池、也不与其它附件共享可变引用」，但内存附件实现是 `get() = source?.load() ?: inlineData`——**直接返回实例内部数组**（属性 KDoc 已如实修正，类 KDoc 仍过度宣称） | 类 KDoc 改为**逐项声明**：① 与**内层池**隔离（两种来源均成立）；② 与**其它持有者**隔离**仅落盘附件成立**，内存附件的 `data` / `resolveData` 是**刻意的零拷贝借用视图**；③ `openStream` 两种来源都不交出可变视图。`resolveData` KDoc 同步补「返回所有权与 `data` 一致」。**未改实现**（改返回副本会引入逐次分配，且与「写侧误清零」历史缺陷的修复方向相反） | 审计 L7；ISSUE-P3-07 借用语义 |
| **ISSUE-P3-105** | P3 | `VaultEntrySecretReader.getAttachmentData` 一律 `attachment.data` 后再 `.copyOf()`：落盘路径上 `BinarySource.load()` **已返回独立副本**，第二份 `.copyOf()` 纯属冗余，且**第一份副本无人持有、无人清零**（解密附件明文随 GC 静默留存） | ① 按来源分流：`source != null` → 直接交出 `source.load()` 的副本；内存来源 → `data.copyOf()`（借用视图必须复制，否则调用方清零污染库内字节）；② **同批附带**：唯一生产调用方 `EntryDetailAttachmentExporter.export` 补 `try { … } finally { bytes.fill(0) }`（覆盖三条早退分支与写出异常路径，且**晚于** `os.write(bytes)`）；③ `VaultRepository.getAttachmentData` KDoc 声明返回「调用方独占副本」 | 审计 L8 + RC-02 |

### 59.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :core:testDebugUnitTest --tests "com.keepasskey.core.model.KdbxAttachmentOwnershipTest"
# → BUILD SUCCESSFUL；tests=3 skipped=0 failures=0 errors=0
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.data.repository.AttachmentDataOwnershipTest" --tests "com.keepasskey.app.security.SensitiveWindowHardeningTest"
# → BUILD SUCCESSFUL；tests=5 skipped=0 failures=0 errors=0（4 + 1）

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 15s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1729 failures=0 errors=0 skipped=13
#   （app 943 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +8
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 3m 42s；216 actionable tasks: 39 executed, 177 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,159 字节，2026-09-15 14:27:44）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**产物鲜度核验（连续三批字节数相同，故升级为逐项证据链）**：§57 / §58 / §59 的 APK 字节数均为
15,463,159，仅凭字节数**不能**证明产物未被刷新，故本批改为核验**输入→输出链路**：

| 环节 | 证据 | 结论 |
|---|---|---|
| 任务真实执行 | `assembleRelease` 输出 `39 executed, 177 up-to-date` | 非全量 up-to-date |
| release 期 class 重编 | `app/build/intermediates/classes/release/…/BreachHasher.class`、`…/SyncProviderResolver.class` 时间戳 14:05:50（§58 构建内） | §58 的源码变更进入了 release 编译 |
| R8 输出重跑 | `app/build/intermediates/dex/release/minifyReleaseWithR8/classes.dex`（10,796,184 字节）时间戳 **14:27:26** | 本批 R8 真实重跑 |
| R8 输入即当前源码 | `app/build/outputs/mapping/release/mapping.txt`（78,260,376 字节，**14:27:30**）中 `VaultEntrySecretReader.getAttachmentData(...)` 的**调试行号**出现 `:170` / `:171` / `:172`——正是 §59 新增的 `binarySource()` 分流三行；`BreachHasher.splitPrefixSuffixOfSha1` 的行号 `42..57` 亦与 §58 重写后的当前源码一致 | R8 的输入**就是**当前工作区源码，dex 含 §58/§59 变更 |
| APK 由该 dex 打包 | 产物 `LastWriteTime` 14:27:44 **晚于** dex 的 14:27:26 | 产物确含上述变更 |

（**如实声明**：三批 APK 字节数完全相同这一现象，本批未能给出解释（推测为 zipalign 对齐填充吸收了
dex 的字节级增量）；但「产物含最新源码」已由**行号级**证据独立证明，
故不以字节数变化作为鲜度判据。此项不影响交付正确性，仅登记以免后人误用字节数当验证手段。）

**新增用例（本批 +8 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `core` | `KdbxAttachmentOwnershipTest`（+3，新文件） | **行为级双口径锁**：① 内存附件 `data` / `inlineBytes` / `resolveData` 必须返回**同一实例**（借用视图），且 `clear()` 后内容消失；② 落盘附件两次 `data` 必须**各自独立**（`assertNotSame`）、不得是 store 内部数组、清零交付副本后来源完好；③ 落盘附件 `clear()` 必须不动作（字节由多引用者共享）。任一侧被反向改写（内存改返回副本 / 落盘改返回共享数组）即失败 |
| `app` | `AttachmentDataOwnershipTest`（+4，新文件） | ① **非空跑**：落盘路径 `source.load()` 只调用一次，且返回值必须**就是**该副本实例（旧实现再 `copyOf()` ⇒ 实例不同 ⇒ 必红）；② 内存路径必须 `assertNotSame`（不得外流库内数组），且清零交付副本后库内字节完好；③ 名称不匹配 / 空字节 / uuid 非法均返回 null；④ 静态接线：`EntryDetailAttachmentExporter` 必须存在 `finally { bytes.fill(0) }`，且清零点**晚于** `os.write(bytes)`（写前清零会导出全零附件） |
| `app` | `SensitiveWindowHardeningTest`（+1，新文件） | 静态接线：`SecureCaptureActivity` 必须**同时**存在 `FLAG_SECURE`、`setHideOverlayWindows(true)`、`decorView.filterTouchesWhenObscured = true` 三处调用形态。断言前剔除块注释与行注释（整改说明自身含关键字） |

### 59.3 已知边界与口径（如实声明）

1. **P3-104 采「改文档」而非「改实现」，理由是历史缺陷方向**：内存附件若改为返回副本，
   则「写侧以为恒为副本、在 `finally` 中 `fill(0)`」这一类**误清零**缺陷将更难被发现
   （借用视图下误清零会立刻表现为「同一实例再次保存即全零」，已被既有测试捕获）。
   零拷贝借用视图是本仓**刻意的**性能设计，代价以 KDoc 逐条声明并由用例正向锁定。
2. **P3-105 的两条分支不可合并**：内存来源必须复制（否则调用方清零污染库内字节），
   落盘来源必须**不**再复制（否则产生无人清零的第二份明文副本）。两条分支的断言互为对方的
   「非空跑」证据——删任一条分支都会使对应断言失败。
3. **P3-105 附带改动的范围声明**：`EntryDetailAttachmentExporter` 的清零属**同批附带整改**
   （原条目 AC 未要求），已如实列入 59.1 交付清单；其失效形态（接线被删）由静态守卫锁定，
   **未**做行为级验证——该路径需要真实 `ContentResolver` + 输出流，JVM 侧无法构造
   （`appContext` 为空时方法在写出前即早退）。
4. **P3-103 的「叠加而非豁免」是本批的显式裁决**：条目 AC 允许「留痕说明 `setHideOverlayWindows`
   已覆盖，无需叠加」，本批**不采纳**该口径——`setHideOverlayWindows(true)` 只阻断后续绘制的
   悬浮窗，对调用时刻**已存在**的遮挡窗口（系统级无障碍覆盖 / 旧式 overlay）不生效，
   故两者为**互补**而非替代；采纳豁免将放弃一层真实防护。
5. **仍未在设备侧验证**：本批三项中，仅 P3-104 / P3-105 有 JVM 行为级证据；
   P3-103 的**运行时效果**（遮挡态触摸确被丢弃、取景窗口确不进截屏）需设备侧构造遮挡窗口，
   与既有 `ISSUE-P2-42` 同类，保留为设备侧待验面。
6. **本批未触及的相邻项**：`ISSUE-P3-106`（`data_extraction_rules` 未排除 `external` 域）、
   `ISSUE-P3-107`（`.kdbx.bak` 保留口径）、`ISSUE-P3-110`（导出确认令牌未下沉）、
   `ISSUE-P3-112`（`SafDocumentCleanup` 无条件删除）等仍为开放条目。
7. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **36 → 33**（表行 29 → 26，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

---

<a id="s60"></a>
## §60 退路面加固与来源登记批次（2026-09-15）：P3-106 / P3-112 / P3-115

> **本批次缘起**：认领三条「单点改动 + 静态守卫」类低危项——`ISSUE-P3-106`
> （数据提取规则未穷举排除域）、`ISSUE-P3-112`（SAF 清理无条件删除）、
> `ISSUE-P3-115`（构建分发来源未登记）。三条共同主题：
> **把「当前恰好没有暴露」与「结构上不可能暴露」区分开**——前者是靠运气，后者才是配置。

### 60.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-106** | P3 | `res/xml/data_extraction_rules.xml` 只排除 `root` / `file` / `database` / `sharedpref` 四域，**未排除** `external` 与 `device_*` 四域。当前暴露为零（外部存储 API 全仓零使用），属「靠运气」而非「结构上排除」 | 两个块（`<cloud-backup>` / `<device-transfer>`）各补 5 条排除：`external`、`device_root`、`device_file`、`device_database`、`device_sharedpref`，实现**域穷举**；并补注释说明「域取值以官方语法为准」 | 审计 L10 |
| **ISSUE-P3-112** | P3 | `SafDocumentCleanup.deleteCreatedDocument` **无条件** `deleteDocument`——当前安全仅因全部 7 处调用点恰好都传 `CreateDocument` 的返回值；任一处误传 `OpenDocument` 结果或既有文件 URI 即**静默删除用户数据** | 删除前补**归属判定**：`if (!DocumentsContract.isDocumentUri(context, targetUri)) return`——非 DocumentsProvider 文档 URI（`file://`、媒体库等）一律早退。判定与删除同在 `runCatching` 内，判定自身抛异常（解析器不可用）时同样**不删除**（fail-safe 方向）；KDoc 显式写明调用契约 | 审计 L18 |
| **ISSUE-P3-115** | P3 | `gradle-wrapper.properties` 的 `distributionUrl` 指向第三方镜像 `mirrors.cloud.tencent.com`，`AGENTS.md` §1 只记「锁定 SHA-256」而未记**镜像来源**与**锁定值**——后人无法判断该哈希对应哪个分发源 | `AGENTS.md` §1 补记：镜像来源（非 `services.gradle.org`）、Gradle 版本 **9.7.1**、锁定摘要 `acd53f1e…f804d20a`、来源链接与核实日期，并给出「改回官方源」的一行操作（哈希不变） | 审计 L21 |

### 60.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.security.BackupAndSafHardeningTest"
# → BUILD SUCCESSFUL；tests=3 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 12s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1732 failures=0 errors=0 skipped=13
#   （app 946 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +3
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 21s；216 actionable tasks: 23 executed, 193 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,295 字节，2026-09-15 14:39:34）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e

# ③ 排除域语法的本地权威校验（AGENTS §5 命令）
.\gradlew.bat :app:lint
# → BUILD SUCCESSFUL in 2m 46s；172 actionable tasks: 49 executed
#   报告 app/build/reports/lint-results-debug.{xml,txt,html,sarif}（14:43:36 重写）
#   → `FullBackupContent` / `data_extraction_rules` 命中数均为 **0**（域取值合法、规则文件有效）
```

**新增用例（本批 +3 例）**：`app` → `BackupAndSafHardeningTest`（新文件）

| 用例 | 覆盖 |
|---|---|
| 清单必须引用数据提取规则文件 | `AndroidManifest.xml` 必须含 `android:dataExtractionRules="@xml/data_extraction_rules"`——规则文件存在但未被引用=零效果 |
| 云备份与设备迁移必须逐域排除 | 对 9 个 domain（`root`/`file`/`database`/`sharedpref`/`external`/`device_root`/`device_file`/`device_database`/`device_sharedpref`）逐一断言「两个块各 ≥1 处排除」。**非空跑**：旧文件对 `external` 与四个 `device_*` 命中数为 0 ⇒ 必红 |
| SAF 清理必须在删除前做文档 URI 归属判定 | ① 必须存在 `DocumentsContract.isDocumentUri(context, targetUri)`；② 必须存在删除调用；③ 归属判定**先于**删除；④ 非文档 URI 必须**早退**（正则锁定 `if (!…isDocumentUri(context, targetUri)) return`）。**非空跑**：旧实现 `isDocumentUri` 命中为 0 ⇒ 必红 |

### 60.3 已知边界与口径（如实声明）

1. **P3-106 的域取值依据**：以官方 `<data-extraction-rules>` 语法为准——`<cloud-backup>` 与
   `<device-transfer>` **两个块**均接受 `file | database | sharedpref | external | root |
   device_file | device_database | device_sharedpref | device_root`。本批除静态用例断言外，
   另跑 `:app:lint`（`FullBackupContent` 为 **Fatal** 级检查）作为本地权威校验，命中数 0。
2. **P3-106 的语义边界**：`allowBackup="false"`（源清单）已关闭云备份面；
   `dataExtractionRules` 的 `<device-transfer>` 块在 Android 12+ 语义下是**独立面**，
   故两份排除并存、不依赖单一开关（官方文档：某模式缺规则时该模式对全部内容**默认开启**）。
   本批**未**改用 `tools:node` 覆盖合并清单——排除规则是资源而非清单属性，无合并语义问题。
3. **P3-112 的判定强度**：`isDocumentUri` 只保证「URI 属 DocumentsProvider 文档」，
   **不**保证「该文档由本流程创建」——第二个条件由调用点契约承担（全仓 7 处调用点均为
   `CreateDocument` 返回值，已逐点核对）。本批**未**引入「创建令牌」式的强证明，
   原因是导出路径的 URI 仅在 Compose 局部状态中流转，引入令牌会牵动 7 处 UI 接线与相应用例；
   如后续需要更强保证，应作为独立条目评估。
4. **P3-112 的行为变更面**：对**非文档 URI** 由「先删再吞异常」变为「不删」。
   该变更方向为**更保守**（少删而非多删），且与全部调用点的既有输入形态一致，
   故不改变任何现有用户可见行为（导出取消仍清理空文档）。
5. **P3-115 为纯登记项**：**未**改变任何构建行为（`distributionUrl` 与哈希均未改），
   仅把「第三方镜像 + 官方哈希锁定」这一事实及其解除方法写入 `AGENTS.md` §1。
   第三方镜像本身被接受，理由是 `distributionSha256Sum` 已锁定官方 `-bin` ZIP 摘要。
6. **本批未触及的相邻项**：`ISSUE-P3-107`（`.kdbx.bak` 保留口径）、`ISSUE-P3-110`
   （导出确认令牌未下沉）、`ISSUE-P3-113`（字段屏蔽 fail-closed 的可观测性）、
   `ISSUE-P3-114`（剪贴板文案与实现口径）等仍为开放条目。
7. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **33 → 30**（表行 26 → 23，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。
8. **对 §59.2「字节数异常」的补充观测**：本批仅改资源（`data_extraction_rules.xml`）与一处 Kotlin
   判定，APK 字节数由 15,463,159 → **15,463,295（+136）**；`mapping.txt` 由 78,260,376 →
   78,263,090（+2714）。即「仅代码面增量」的三批未改变 APK 总字节数，而「资源面增量」改变了它。
   该现象**指向** zip 对齐 / 压缩层吸收了代码面增量（dex 条目在 APK 内的对齐填充随之变化），
   但**本批未予实证**，故仍按 §59.2 的口径：**不以字节数作为产物鲜度判据**。

---

<a id="s61"></a>
## §61 fail-closed 可观测性批次（2026-09-15）：P3-113

> **本批次缘起**：认领 `ISSUE-P3-113`——`AutofillFieldBlocklistStore.isBlocked` 在字段签名
> 无法计算时按 **fail-closed** 返回 `true`（方向正确），但副作用是**静默放弃填充**：
> 用户只看到「不出候选」，无从判断是「没有可用条目」还是「屏蔽判定链路坏了」。
> 本批不改判定方向，只把该故障**显式化**。

### 61.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-113** | P3 | Keystore HMAC 密钥不可用时，`isBlocked` 恒返回 true ⇒ 字段级屏蔽判定恒为真 ⇒ 填充侧静态放弃下发候选，用户无任何可归因提示 | ① **区分两类原因**：新增探针 `hmacKeyUnavailable()`，仅当**密钥本身**无法完成 MAC 时才置位；「输入非法（包名不符合规范）」不置位（否则会对用户报出无关告警）。探针只在签名已返回 null 的路径上执行，且置位后不再重复——正常路径零额外开销；② 仓库新增 `signatureUnavailable: StateFlow<Boolean>`；③ 健康自检链路新增异常项 `AutofillHealthIssue.FIELD_BLOCK_SIGNATURE_UNAVAILABLE`（`AutofillHealthProbe` 注入仓库读取该标志），由既有 `AutofillHealthCard` 以「警告 + 可操作指引」呈现；④ 中英文案各一条 | 审计 L19；ISSUE-P3-41 健康自检体系 |

### 61.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.autofill.AutofillFieldBlocklistStoreTest" --tests "com.keepasskey.app.autofill.AutofillHealthPolicyTest"
# → BUILD SUCCESSFUL；13 + 6 = 19 例 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 5s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1737 failures=0 errors=0 skipped=13
#   （app 951 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +5
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 41s；216 actionable tasks: 27 executed, 189 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,464,139 字节，2026-09-15 14:59:32）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +5 例）**：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `AutofillFieldBlocklistStoreTest`（+3） | 密钥不可用时置位可观测标志而判定仍 fail-closed | **非空跑**：初始态断言「不得误报」后，`isBlocked` 返回 true 且标志置位——旧实现无该标志字段，编译期即失败 |
| | 非法包名不置位可观测标志 | 负向边界：非法包名同样 fail-closed，但**不得**置位（否则健康卡片会对用户误报密钥故障）。这是探针存在的**唯一理由**，缺它则两类原因重新混为一谈 |
| | 正常判定路径不置位可观测标志 | 覆盖命中 / 未命中两条正常分支均不置位（防止标志被写成恒真） |
| `AutofillHealthPolicyTest`（+2） | 字段屏蔽密钥不可用时单独列出且不影响传统链路可用性 | 报告须含该项、`isFullyOperational=false`，而 `isLegacyAutofillOperational` 仍为 true——二者不可混淆，否则会给出错误修复指引 |
| | 未传字段屏蔽项时按可用处理 | 默认参数保持既有语义（改动前后报告完全一致） |

### 61.3 已知边界与口径（如实声明）

1. **本批不改判定方向**：`isBlocked` 仍是 fail-closed（返回 true）。改动仅在「把原因显式化」，
   故**不**涉及填充行为变更——`AutofillFieldBlocklistStoreTest` 既有 10 例全部保持通过。
2. **探针的必要性（设计要点）**：`AutofillFieldSignature.of` 把「输入非法」与「密钥不可用」
   折叠为同一个 `null`。若直接以「签名 == null」置位，用户在浏览器 / 非规范包名的正常干扰下
   就会看到「密钥库不可用」告警。故本批引入非秘密常量探针把两类原因分开：
   **只有密钥本身失败**才置位。探针原文不参与签名规范化，不构成跨设备可关联特征。
3. **可观测出口的选择**：采「健康自检卡片告警」而非「调试日志」。理由是诊断日志受
   `ExtendedSettings.debugLogEnabled` 约束（默认关闭），若只写日志，故障对绝大多数用户
   仍然不可见，与本条「对用户不透明」的原始诉求不相符。
4. **未覆盖（需设备侧）**：Keystore 密钥真正不可用的**故障注入**（如密钥被系统 invalidate）
   与卡片实际渲染效果，JVM 侧无法构造，保留为设备侧待验面（与 `ISSUE-P2-42` 同类）。
   本批以「探针可被注入的假密钥来源驱动」证明判定链路正确（`unavailableHmacFieldSignatureSource`）。
5. **未覆盖（如实声明）**：`AutofillHealthProbe` 新增对 `AutofillFieldBlocklistStore` 的依赖，
   其 Hilt 图连通性由**编译期**（`:app:assembleRelease` / 单测装配）保证，
   本批**未**新增针对探针本身的用例（探针读系统状态，JVM 不可测）。
6. **相邻未触及项**：`ISSUE-P3-114`（剪贴板文案与实现口径）、`ISSUE-P3-107`（`.kdbx.bak` 保留口径）、
   `ISSUE-P3-110`（导出确认令牌未下沉）、`ISSUE-P3-111`（provider 请求未交叉核对）等仍为开放条目。
7. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **30 → 29**（表行 23 → 22，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

---

<a id="s62"></a>
## §62 导出确认令牌下沉与文案一致性批次（2026-09-15）：P3-107 / P3-110 / P3-114

> **本批次缘起**：认领三条「**声明与实现不一致**」类存量项——`ISSUE-P3-110`
> （明文导出的二次确认只活在 UI 层，控制器入口无确认参数）、`ISSUE-P3-114`
> （剪贴板文案承诺了 `EXTRA_IS_SENSITIVE` 无法提供的保证）、`ISSUE-P3-107`
> （`.kdbx.bak` 行为与保留期无文档，且设置页开关文案描述的是**不存在**的实现）。
> 三条共同的失效形态：**文档/文案承诺 A，实现只做 B，而没有任何机制阻止二者漂移**，
> 故本批除改正本身外，一律补「文案 ↔ 实现」双向守卫。

### 62.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-110** | P3 | `ExportConfirmationPolicy` 是纯决策内核，但**只有 UI 调用它**：`SettingsExportController.exportVaultXmlTo/CsvTo` 不带任何确认参数，`SettingsViewModel` 直接透传 ⇒ 任何调用点（含未来新增）调一次方法即可把整库明文写出，「二次确认」只是 UI 侧约定 | ① 新增 **`ExportTicket`**：`sealed interface`，唯一实现 `private class IssuedExportTicket`（**文件私有 ⇒ 全仓无法伪造**）；② 令牌**只能**由 `ExportConfirmationPolicy.confirm(kind, userConfirmed)` 签发（未确认时返回 null）；③ 控制器入口签名改为**必填令牌**（`exportVaultXmlTo(targetUri, ticket)`），并在**序列化之前**用 `ticketMatches(ticket, kind)` 校验——令牌缺失或跨制品复用（拿免确认的加密令牌套明文导出）一律 fail-closed：不产生任何明文字节 + 失败审计 + 清理空目标文档；④ UI 在确认弹窗内签发令牌后下传（`onExportXml: (Uri, ExportTicket) -> Unit`） | 审计 L16 |
| **ISSUE-P3-114** | P3 | `sec_clipboard_sub` 宣称「复制的敏感内容**不进入剪贴板历史与云同步**」，而实现只注入 `ClipDescription.EXTRA_IS_SENSITIVE`。按平台文档该标记是**渲染提示**——「Adding this extra **does not change clipboard behavior or add additional security** to the ClipData」，实际效果为 Android 13+ 系统复制视觉确认不显示明文；历史 / 云同步属系统 / 厂商实现面，本应用无法强制 | ① 中英文案改为落在**可强制**的两件事上：「标记为敏感（系统复制提示不显示明文）+ 按设定时长自动擦除」；② `ClipboardSecurityManager` KDoc 按官方语义重写（并显式声明「不据此宣称进入历史 / 云同步」）；③ 新增双向守卫用例：文案不得再出现历史 / 云同步式承诺，且两条敏感通道必须真设该标记、普通通道不得设 | 审计 L20 |
| **ISSUE-P3-107** | P3 | `.kdbx.bak` 的语义与**保留期**无文档（内容为用写入当时凭据加密的**完整库副本**、滚动保留一份、仅换密成功后删）；且设置页开关文案写作「**同步前**自动备份 / 备份至**安全目录**」——描述的是全仓**不存在**的「同步前上传备份」实现 | ① 行为与保留期写入 `AGENTS.md` §6 与 `DatabaseSession.createBackupBeforeSave` KDoc（含**残余**：在本应用之外换密时 `.bak` 仍可用旧口令解开，直至下次本应用内写入）；② 开关文案更正为「保存前保留上一版备份 / 每次保存前把上一版本留为同目录的 .kdbx.bak（成功更换主密码后自动删除）」；③ 新增用例：开关必须下发到 `createBackupBeforeSave`（证明该文案所述行为真实存在），文案不得再出现「安全目录 / 上传前 / 同步前」 | 审计 L13 |

**同批附带发现（已登记，未在本批整改）**：**`ISSUE-P3-128`**——密钥文件导出**无二次确认**：
`ExportConfirmationPolicy.riskOf(KEY_FILE)` 已把密钥文件归入 `PLAINTEXT` 风险等级，但
`exportKeyFileTo` 不接受令牌、调用点亦无确认弹窗。本批在 `exportKeyFileTo` 的 KDoc 显式标注
「**尚未**受确认门控，不得据此认为已闭环」，条目已登入 `ACTIVE_ISSUES.md` 按独立批次排期。

### 62.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.security.ClipboardSensitiveMarkConsistencyTest"
# → BUILD SUCCESSFUL；tests=2 failures=0 errors=0
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.ui.screens.settings.BackupToggleCopyConsistencyTest"
# → BUILD SUCCESSFUL；tests=3 failures=0 errors=0
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicyTest" --tests "com.keepasskey.app.ui.screens.settings.ExportTicketSinkGuardTest"
# → BUILD SUCCESSFUL；tests=13 + 3 = 16 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 1m 45s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1749 failures=0 errors=0 skipped=13
#   （app 963 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +12
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 38s；216 actionable tasks: 38 executed, 178 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,464,971 字节，2026-09-15 15:34:07）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +12 例）**：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `ExportTicketSinkGuardTest`（+3，新文件） | 令牌唯一实现类必须文件私有且全仓无第二实现 | 遍历 `app/src/main/java` **全部** `.kt`：断言只有 `SettingsExportController.kt` 含令牌实现声明（正则 `\)\s*:\s*ExportTicket`，避免误命中 `ticket: ExportTicket` 参数写法）。这是「不可伪造」的**机制**证据——新增任何第二实现即失败 |
| | 令牌构造只能出现在策略签发函数内 | 断言 `IssuedExportTicket(` 全文件恰好出现 2 次（class 声明 + `confirm` 内唯一签发点），且签发点落在 `confirm` 的定义区间内 ⇒ 「令牌 ⇒ 已走过确认决策」的蕴含关系成立 |
| | 明文导出入口必须要求令牌且校验先于序列化 | 断言两个入口签名为 `(targetUri: Uri, ticket: ExportTicket)`（**缺令牌无法编译**），且统一入口内 `ticketMatches` 的索引**先于** `exportAndWrite`——校验后置即形同虚设 |
| `ExportConfirmationPolicyTest`（+4） | 未确认时不签发明文导出令牌 / 确认后令牌绑定制品类型 / 加密制品免确认令牌不得用于明文导出 / 缺失令牌即视为不匹配 | 覆盖签发侧的 fail-closed、令牌-制品绑定，以及「拿免确认的加密令牌套明文导出」这条**提权路径**的封堵 |
| `ClipboardSensitiveMarkConsistencyTest`（+2，新文件） | 敏感复制通道必须真实设置敏感标记而普通通道不得设置 | 按**函数体**（花括号配对）提取三条通道，断言敏感两条含 `EXTRA_IS_SENSITIVE`、普通通道不含——防止「标记退化为无差别标记」使文案所述能力失真 |
| | 剪贴板文案不得再宣称不进入历史或云同步 | 中英文案均不得出现 history / cloud sync 式承诺，且必须落在「敏感标记 + 自动擦除」两件**可强制**的事上 |
| `BackupToggleCopyConsistencyTest`（+3，新文件） | 开关必须下发到会话的滚动备份偏好 | 断言 `setCreateBackupBeforeSave` 同时做「持久化」与「下发 `databaseSession?.createBackupBeforeSave`」——这是文案所述行为真实存在的证据 |
| | 备份开关文案必须描述滚动备份与换密删除 | 中英文案均须点明 `.kdbx.bak` 与「换密后删除」（保留期终点） |
| | 备份开关文案不得再描述不存在的同步前上传备份 | 中英文案均不得出现「安全目录 / 上传前 / 同步前 / safe folder / before sync」 |

### 62.3 已知边界与口径（如实声明）

1. **P3-110 的「直接调用应失败」以编译期 + 静态守卫表达，而非运行时异常**：
   控制器入口**必填** `ExportTicket`，故「不带确认直接调用」在同模块内**无法编译**；
   运行期仍有校验（令牌-制品不匹配 → fail-closed）。本批**未**做控制器级行为用例，
   原因是该路径需要真实 `android.net.Uri` 与 `ContentResolver`——JVM 单测下 `Uri.parse`
   即抛 `Stub!`（该模块未启用 `unitTests.isReturnDefaultValues`，全仓测试亦无 Uri 构造先例），
   故以「签名 + 校验顺序」的静态守卫与被断言的真实决策内核共同覆盖；该缺口如实登记于此。
2. **P3-110 的令牌可见性权衡（如实声明）**：令牌需作为**公开 composable** 的参数类型，
   故 `ExportTicket` 与 `ExportArtifactKind` 由 `internal` 提升为 `public`。
   不可伪造性**不**依赖可见性，而由「唯一实现类文件私有」承担（见 62.2 第一条用例）——
   这比 AC 所述「构造器 `internal`」更强：`internal` 构造器在同模块内仍可被任意文件调用。
3. **P3-110 未覆盖的相邻路径**：`exportKeyFileTo`（密钥文件）与附件导出**未**纳入本批令牌门控——
   前者属新发现并登记为 `ISSUE-P3-128`；后者已在 VM 层校验（`EntryDetailViewModel`，审计原文即如此记载）。
   **不得**据本批声称「全部导出路径均已受确认门控」。
4. **P3-114 的口径**：本批采「修正文案」而非「补足实现」——平台**没有**任何 API 能阻止
   第三方剪贴板工具读取内容（JSsec 安全编码指南同样结论：不存在根本性对策），
   故把「历史 / 云同步」写进承诺属不可兑现的表述；真正的强制面是「自动擦除」。
5. **P3-107 为「文档化 + 文案更正」**：`.kdbx.bak` 的滚动保留是**既有设计**
   （`createBackupBeforeSave` 默认开启、用作崩溃兜底），本批不动其行为，只把语义、
   保留期与**残余**（应用外换密）写进 `AGENTS.md` §6 与 `DatabaseSession` KDoc，
   并把设置页开关文案改成与实现一致；其行为回归由既有 `DatabaseSessionBackupPreferenceTest`（3 例）承担。
6. **本批未触及的相邻项**：`ISSUE-P3-111`（provider 请求未交叉核对）、`ISSUE-P3-108`
   （`.tmp` 残留口径）、`ISSUE-P3-128`（密钥文件导出确认）等仍为开放条目。
7. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **29 → 27**（表行 22 − 3 闭环 + 1 新增
   `ISSUE-P3-128` = **20**，标题条目仍 7），按「表行 + 标题条目」双形式口径复算；
   P2 计数不变（9）——**本批为净 −2**（闭环 3 项、新增登记 1 项）。

---

<a id="s63"></a>
## §63 密钥文件导出确认批次（2026-09-15）：P3-128

> **本批次缘起**：§62 施工时**附带发现**——`ExportConfirmationPolicy.riskOf(KEY_FILE)` 早已把
> 「会话绑定密钥文件」归入 `PLAINTEXT` 风险等级（该枚举 KDoc 原文：「单独备份，泄漏即可配合密文开库」），
> 但控制器入口 `exportKeyFileTo` 不接受任何确认、UI 侧 `exportKeyFileLauncher` 的结果
> 更是直接 `uri?.let(onExportKeyFile)` —— **策略声明与调用点不一致**，且是「第二把钥匙」
> 被写进用户可能同步到云端的目录而无任何提示。§62 只下沉了明文 XML / CSV 的令牌，
> 该路径当日登记为 `ISSUE-P3-128`，本批收口。

### 63.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-128** | P3 | 密钥文件导出既无确认也无令牌门控：`exportKeyFileTo(targetUri)` 可直接调用，UI 在 SAF 选定目标后**立刻写出**——与 `riskOf(KEY_FILE) = PLAINTEXT` 的策略声明矛盾 | ① 控制器入口改为 `exportKeyFileTo(targetUri, ticket: ExportTicket)` 并**复用** §62 的 `exportPlaintextTo` 统一入口（与明文 XML / CSV 完全同口径：校验先于序列化、失败即审计 + 清理空目标文档）；② `SettingsViewModel` 同步要求令牌；③ UI 新增**密钥文件导出确认弹窗**（对话框 6d）：SAF 结果只落「待确认」状态，确认时经 `ExportConfirmationPolicy.confirm(KEY_FILE, …)` 签发令牌后下传，取消分支清理空目标文档；④ 中英文案各两条（明示「密钥文件是第二重凭据、需与 .kdbx 分开存放、需自行删除」） | §62 附带发现；审计 L16 同族 |

### 63.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.ui.screens.settings.ExportTicketSinkGuardTest" --tests "com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicyTest"
# → BUILD SUCCESSFUL；tests=4 + 14 = 18 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 16s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1751 failures=0 errors=0 skipped=13
#   （app 965 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +2
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 55s；216 actionable tasks: 27 executed, 189 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,466,567 字节，2026-09-15 15:47:43）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +2 例，均为对既有守卫的**扩展**）**：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `ExportTicketSinkGuardTest`（+1） | 密钥文件导出必须先经二次确认弹窗签发令牌 | ① 控制器入口签名必须为 `(targetUri: Uri, ticket: ExportTicket)`（并入原有三入口断言列表）；② **UI 侧反向断言**：`DatabaseSettingsScreen` 中不得再出现 `uri?.let(onExportKeyFile)` 这一「SAF 返回即导出」形态（正则在**剔除注释后**匹配）；③ SAF 回调必须落到 `pendingKeyFileUri = uri` + `showKeyFileExportConfirm = true`；④ 确认分支必须存在 `ExportConfirmationPolicy.confirm(kind = ExportArtifactKind.KEY_FILE, …)` |
| `ExportConfirmationPolicyTest`（+1） | 未确认时不签发密钥文件导出令牌 | 覆盖签发侧 fail-closed、令牌-制品绑定，并断言密钥文件令牌**不得**用于明文 XML 导出（跨制品复用封堵在密钥文件路径同样成立） |

### 63.3 已知边界与口径（如实声明）

1. **本批未做 UI 行为级验证（如实登记）**：确认弹窗的实际渲染与「取消后空目标文档被清理」
   需设备 / Compose 测试环境（JVM 侧无法构造 `ActivityResultLauncher` 与 `ContentResolver`）。
   本批以「接线形态静态守卫 + 决策内核行为断言」覆盖；弹窗结构与既有明文 XML / CSV 弹窗
   **逐字对齐**（同一 `AlertDialog` + `SafDocumentCleanup` + 清理分支），降低渲染形态差异风险。
2. **密钥文件导出的确认文案是「风险告知」而非「强制分离存储」**：本应用**无法**控制用户把
   密钥文件写到何处（可与 `.kdbx` 同处云端），文案只如实告知「单文件即可配合库文件尝试开库，
   请与 .kdbx 分开存放」——**不得**据此声称本应用实现了「密钥文件与库文件强制分离」。
3. **与附件导出的关系**：附件导出仍走 `EntryDetailViewModel` 的 VM 层校验（审计原文即如此记载），
   本批**未**把它并入令牌体系——两条路径的调用面与 UI 形态不同，合并属独立重构；
   **不得**据本批声称「全部导出路径已统一为令牌体系」（当前为 3 条走令牌 + 1 条 VM 校验）。
4. **本批未触及的相邻项**：`ISSUE-P3-111`（provider 请求未交叉核对）、`ISSUE-P3-108`
   （`.tmp` 残留口径）等仍为开放条目。
5. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **27 → 26**（表行 20 → 19，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。
   **说明**：`ISSUE-P3-128` 于 §62 登记、当日 §63 闭环，故在 `ACTIVE_ISSUES.md` 中
   净效果为「未在表内留下痕迹」，其完整沿革（发现 → 登记 → 闭环）记录于 §62.1 与本节。

---

<a id="s64"></a>
## §64 第一轮审计 F 系列收尾批次（2026-09-15）：P3-86 / P3-87 / P3-89 / P3-90 / P3-91 / P3-92

> **本批次缘起**：认领第一轮外部审计转登项（`docs/ACTIVE_ISSUES.md` 的「审计 F-系列」表）
> 中**仍成立**的六条——其中 `ISSUE-P3-86` 已被第四轮复核**升格 MEDIUM / 修 P2**
> （本批因此优先处理）。逐条复核前提后：五条需整改、一条（P3-91）**前提已不成立**。

### 64.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-86** | **P2（升格）** | `SettingsExportController.exportAndWrite` 把整库序列化结果写盘后**不清零**——明文 XML / CSV 路径下该数组是**整库全部字段值**的明文副本，随局部变量出栈静默留存至 GC | 写盘段包进 `try { … } finally { bytes?.fill(0) }`：覆盖「写盘成功 / 写盘失败 / 解析器抛异常」三态，且**晚于** `os.write(bytes)`（写前清零会导出全零内容） | 审计 F-02（第四轮升格） |
| **ISSUE-P3-87** | P3 | 明文导出二次确认**仅在 UI 层**（`ExportConfirmationPolicy.allows(..., confirmed = true)` 为恒真调用）——与 `ISSUE-P3-110` **同根因**，属同一缺陷在两轮审计中的两次登记 | §62/§63 已把确认结果物化为不可伪造的 `ExportTicket` 并下沉为控制器入口必填参数（未确认不可编译、跨制品复用被拒）。本批**不重复改动**，仅按「同根因合并归档」处置并在此留痕 | 审计 F-03 ≡ ISSUE-P3-110（§62.1 / §63.1） |
| **ISSUE-P3-89** | P3 | `ExportAuditSanitizer.shortDigest` 每字节先 `and 0x0F` 再 `ushr 4` ⇒ 高半字节**恒为 0**，8 个 hex 字符中 4 个恒为 `'0'`，有效熵被削到 **≤16 bit**（与「关联同一目标 / 区分不同目标」的用途不符） | 改为取字节**全 8 bit**（`and 0xFF`）：前 4 字节 → 8 个 hex 字符 = **32 bit**；**未**扩为完整 64 hex（审计缓冲每行长度受限，且该标记非抗碰撞用途）。KDoc 同步写明有效熵与用途边界 | 审计 F-07（第四轮更正） |
| **ISSUE-P3-90** | P3 | `OtpEngine` 另有一份**公开**的 `parseOtpAuthUri` 副本，对 `period` / `digits` **无任何钳制**（生产已走 `TotpKeyUriParser` 的加固版），零调用点却公开可见——后来者「按名字找到它」即可接入未加固路径 | 按 AC「删除该函数」分支处置：整体删除 `parseOtpAuthUri` 及其专属 `OtpParameters` 数据类；`OtpEngine` KDoc 显式写明职责边界（引擎只算令牌，URI 解析在 `TotpKeyUriParser`，含钳制与校验） | 审计 F-08 |
| **ISSUE-P3-91** | P3 | 原记 `HmacBlockStream.readAll` 用短路 `contentEquals` 比较 HMAC | **复核结论：前提已不成立**——该文件当前两处比较均为 `MessageDigest.isEqual`（`:129`、`:137`），KDoc 亦已注明「F-16：原 `contentEquals` …已随本路径一并替换」。按「认领前复核前提、无对象可改即归档并注明原因」处置 | 审计 F-16（前提已消解） |
| **ISSUE-P3-92** | P3 | UI 把 KDBX 的 ChaCha20 标注为「**ChaCha20-Poly1305**」——本仓**没有**该 AEAD（KDBX4 外层只做 ChaCha20 流加密，完整性由独立 HMAC-SHA256 块流承担），属向用户声明一层不存在的认证保护；且该错误标签在**三处独立硬编码** | ① 新增单一词汇表 `CipherLabels`（含 KDoc 说明「无 Poly1305 标签」），三处引用同一常量；② 标签更正为 `ChaCha20 (256-bit)`（其余两条不变）；③ `RESOLVED_LOG.md` §27.4 历史记录**就地加更正批注**（不改写历史结论）；④ 相关单测同步 | 审计 F-17 |

### 64.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.ui.screens.settings.ExportWritePathHygieneTest" --tests "com.keepasskey.app.ui.screens.settings.DatabaseConfigHeaderMappingTest"
# → BUILD SUCCESSFUL；tests=3 + 7 = 10 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 3s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1756 failures=0 errors=0 skipped=13
#   （app 970 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +5
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 3m 42s；216 actionable tasks: 42 executed, 174 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,466,567 字节，2026-09-15 16:08:44）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e

# ③ 产物**内容**核验（AGENTS §3.8 要求回传产物；字节数不作为鲜度判据，见 §59.2）
#    解 classes*.dex 逐字节检索标签字面量：
#      "ChaCha20 (256-bit)"            → True   （新标签已进入发布产物）
#      "ChaCha20-Poly1305 (256-bit)"   → False  （错误标签已消失）
#      "Twofish-CBC (256-bit)"         → True
```

**新增用例（本批 +5 例）**：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `ExportWritePathHygieneTest`（+3，新文件） | 写盘管线必须在写出后清零整库序列化缓冲 | **源码形态守卫**：按花括号配对取出 `exportAndWrite` 函数体，断言存在 `bytes?.fill(0)`、其索引**晚于** `os.write(bytes)`、且形态为 `finally { bytes?.fill(0) }`（旧实现三者皆不成立）。该路径需真实 `Uri` / `ContentResolver`，JVM 无法构造行为用例（缺口见 64.3.1） |
| | 审计标记短摘要不得退化为每字节仅取低四位 | **行为级**：取 16 个不同目标的高半字节集合，断言存在非 `'0'` 字符——旧实现下高半字节恒为 `'0'`（集合恒等 `{'0'}`）⇒ 必红；同时断言摘要段恒为 8 个 hex 字符 |
| | 审计标记仍保持脱敏与稳定性 | 负向保护：标记不得含文件名，且同一目标必须稳定（防止为修熵而破坏关联语义） |
| `DatabaseConfigHeaderMappingTest`（+2） | 主源码剔除注释后不得再出现 Poly1305 字样 | **全仓主源码扫描**（`app/src/main/java` 全部 `.kt`，剔除注释）：出现 `Poly1305` 即失败——防止 UI 再次声明本仓不存在的 AEAD |
| | 加密算法标签必须收敛到单一词汇表 | 三个标签字面量在仓库主源码中只允许各出现一次（即 `CipherLabels.kt` 定义处）——这正是「三处一致地错」的机制性防线 |
| `DatabaseConfigHeaderMappingTest`（改 1） | `ChaCha20 与 Twofish 文件头映射各自标签` | 期望值由 `ChaCha20-Poly1305 (256-bit)` 更正为 `ChaCha20 (256-bit)` |

### 64.3 已知边界与口径（如实声明）

1. **P3-86 无行为级验证（如实登记）**：`exportAndWrite` 的入参是 `android.net.Uri`，
   而本模块未启用 `unitTests.returnDefaultValues`，JVM 单测下 `Uri` 的**任何方法**（含
   `toString()`，审计路径会调用）均抛 `Stub!` ⇒ 无法构造该路径的行为用例。
   本批以「取真实函数体的形态守卫」+ 既有 `ExportTicketSinkGuardTest` 的入口守卫共同覆盖，
   并**未**声称已在该路径上观测到清零动作本身。
2. **P3-90 的「防误接入」以删除实现，而非加壳**：删除后任何调用点都会**编译失败**；
   但本批**未**新增「禁止重新引入未加固解析器」的源码守卫——若未来确有需要，
   应直接在 `TotpKeyUriParser` 扩展（该处已有钳制与域校验），不得另起副本。
3. **P3-91 按「前提已不成立」归档，不计入整改功劳**：其替换发生在此前批次（本文件
   `HmacBlockStream` KDoc 已留痕），本批只做了前提复核与流转，**不**重复改动代码。
4. **P3-92 的标签口径**：采用 `ChaCha20 (256-bit)` 而**非** AC 示例的
   「ChaCha20 (RFC 8439，无 AEAD 标签)」——理由：① 与同组 `AES-256-CBC (256-bit)` /
   `Twofish-CBC (256-bit)` 形态一致（UI 列表）；② 仓内既有注释统一用 RFC 7539 编号
   （`ChaCha20CipherEngine`），若 UI 写 8439 会引入编号不一致；③ 「无 AEAD」这一关键信息
   已写入 `CipherLabels` KDoc 与 `dbset_cipher_chacha_desc` 文案（该文案仅描述性能特征，
   不含认证声明）。
5. **本批未触及的相邻项**：`ISSUE-P3-88`（Chrome 指纹 65 hex 笔误，需按官方 `apps.json`
   sourced 核对）、`ISSUE-P3-97`（CI 原生用例真实执行 + `.so` 导出符号断言）等仍为开放条目。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **26 → 20**（表行 19 → 13，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）——本批闭环 **6 条**
   （其中 1 条为「前提不成立」归档）。

---

<a id="s65"></a>
## §65 浏览器指纹格式守卫批次（2026-09-15）：P3-88

> **本批次缘起**：认领第一轮审计 `F-04`——`com.android.chrome` 的首条签名指纹在**无冒号副本**里
> 多写了一个 hex 字符（**65** 位，而 SHA-256 恒 64 位）⇒ 该条目**永不匹配**（浏览器委派对它形同不存在）。
> 该笔误在仓内共 **4 份副本**（2 份生产白名单 + 2 份测试常量），其中测试常量与生产同源，
> 使「错误互相印证、测试恒绿」——即审计所称的「仓库自我证明笔误存在却零失败」。

### 65.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-88** | P3 | Chrome 首条指纹 65-hex（SHA-256 恒 64）⇒ 该条目永不匹配；且**两份生产副本 + 两份测试副本同源**，缺少格式断言使其长期零失败 | ① **三处无冒号副本同批**修正为仓内**冒号分隔规范副本**的 64 位形式（`BrowserSigningFingerprints.TRUSTED`、`CallingOriginResolver.PRIVILEGED_BROWSER_ALLOWLIST`、`DigitalAssetLinksVerifierTest.fpNoColon`；另 `AutofillWebDomainPolicyTest.chromeFingerprint` 亦同源同批修正——不修为则该用例在修正生产白名单后**立即变红**，正是「双向印证」的直接证据）；② 新增 `BrowserFingerprintFormatTest`：全部受信指纹须为 **64 位大写 hex**，且白名单「带冒号 / 不带冒号」两写法的**规范化集合必须完全相等**；③ 相关 KDoc 逐处留痕（含「两副本必须同批修改」纪律） | 审计 F-04 |

### 65.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.security.BrowserFingerprintFormatTest" --tests "com.keepasskey.app.autofill.AutofillWebDomainPolicyTest" --tests "com.keepasskey.app.passkey.DigitalAssetLinksVerifierTest"
# → BUILD SUCCESSFUL；tests=3 + 9 + 20 = 32 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 14s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1759 failures=0 errors=0 skipped=13
#   （app 973 / core 68 / crypto 129 / database 386 / sync 203）——较上批 +3
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 18s；216 actionable tasks: 14 executed, 202 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,466,567 字节，2026-09-15 16:54:08）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e

# ③ 产物**内容**核验（字节数不作为鲜度判据，见 §59.2）
#    解 classes*.dex 逐字节检索该指纹：
#      64 位（修正后）"32A2FC74…6D6BABB6652849F" → True
#      65 位（修正前）"32A2FC74…6D6BABBB6652849F" → False
```

**新增用例（本批 +3 例）**：`app` → `BrowserFingerprintFormatTest`（新文件）

| 用例 | 覆盖 |
|---|---|
| 受信浏览器指纹必须全部为 64 位大写 hex | 对 `BrowserSigningFingerprints.TRUSTED` 全体取值断言 `[0-9A-F]{64}`。**非空跑**：修正前 Chrome 首条为 65 位 ⇒ 必红 |
| 受信浏览器指纹不得出现同一包内的重复值 | 防止「为修笔误而改出重复项」导致集合去重后条目数缩水（`setOf` 会静默吞掉重复） |
| passkey 白名单的带冒号与不带冒号副本必须规范化一致 | 从 `CallingOriginResolver.kt` 解析全部 `cert_fingerprint_sha256`，去冒号转大写后断言「冒号写法集合 == 无冒号写法集合」——比长度断言更强：**只改一处即失败**（修正前两集合不等 ⇒ 必红） |

### 65.3 已知边界与口径（如实声明）

1. **上游再核对未执行（本批唯一残余，如实登记）**：AC① 要求按官方
   `gpm-passkeys-privileged-apps/apps.json` **sourced** 核对。本次施工环境**无对外网络**
   （GitHub MCP `get_file_contents` 返回 Not Found、公网检索无结果），故**未**执行上游再核对。
   本批修正**不是** retype：其值取自仓内**同一白名单的冒号分隔副本**（`CallingOriginResolver.kt`
   的 `cert_fingerprint_sha256` 首条，仓库 KDoc 声明其来源为官方列表且经 H1 整改固化），
   该副本是仓内**唯一**长度为 64 的形态，且与另一条 Chrome 指纹共同支撑浏览器委派。
   **复现方式（供后续有网环境复核）**：拉取官方 `apps.json`，比对
   `com.android.chrome` 的 `cert_fingerprint_sha256` 集合是否等于
   `{32A2FC74…B6652849F, F0FD6C5B…2D60DB83}`（大小写与冒号不敏感）。
2. **该笔误的影响面（如实评估，不夸大）**：Chrome 在
   `BrowserSigningFingerprints.TRUSTED` 中**同时**收录两条指纹，另一条（`F0FD6C5B…`）格式正确，
   故「Chrome 委派」整体并未失效——受影响的是**该单条条目**（永不匹配）与其误导性
   （让维护者以为已覆盖两个签名）。方向 fail-closed，无机密性影响。
3. **测试恒绿的机制已封堵**：本批新增的两条断言分别覆盖「长度/字符集」与「双写法一致性」，
   二者都**不依赖**被测常量与生产常量是否同源——即便将来再次出现「生产与测试同源笔误」，
   长度断言仍会失败（这正是修正前该组合所缺的唯一防线）。
4. **未覆盖**：指纹与**真实 Chrome 安装包签名**的一致性需在设备/真实 APK 上核对（不在本批范围，
   历史上亦未做过）；本批只保证**格式正确**与**仓内副本自洽**。
5. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **20 → 19**（表行 13 → 12，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

---

<a id="s66"></a>
## §66 构建 fail-closed 与版本策略声明批次（2026-09-15）：ISSUE-P3-125 ③ / P3-126 ③

> **本批次缘起**：认领 `ISSUE-P3-125` 与 `ISSUE-P3-126` 各自的**第 ③ 子项**——两者共同主题是
> **「看起来在保护、实则在沉默」**：一个是构建失败被吞成「用例跳过、构建全绿」，
> 一个是版本校验看起来存在（有掩码、有常量）实则只查 major 且**没有任何声明**。
> 两条目的 ①② 子项（选择器零匹配门槛 / `endpointOverride` 生产可达 / CI 签名产物标注 /
> suppression 文案）**仍开放**，故两行保留在待办表内，本批只翻转 ③。

### 66.1 交付清单

| 编号 | 子项 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-125** | ③ | `crypto/build.gradle.kts` 的宿主构建任务设 `isIgnoreExitValue = true`，使「cargo **缺失**」与「cargo **构建失败**」结果不可区分——两者都表现为「宿主库未产出 ⇒ JNI 用例 `Assume` 跳过 ⇒ 构建全绿」，形成「Rust 内核编译失败仍全绿」的假绿通道 | ① 移除 `isIgnoreExitValue = true`（**仅**保留 `onlyIf` 的「cargo 缺失即跳过」降级路径）；② 加 `doFirst` 显式日志声明「失败将终止构建」；③ 新增 `NativeBuildFailClosedTest` 守卫：断言脚本中**不得**再出现吞码开关、必须保留缺失跳过与真实 `cargo build` 命令；④ `AGENTS.md` §5 的旧口径（「未装 cargo 或失败则自动降级」）更正为「缺失跳过 / 失败 fail-closed」 | 复核 `B03-N1` |
| **ISSUE-P3-126** | ③ | KDBX 版本策略**无声明**：`KdbxHeader` 只比对 major 位却无任何说明，同时并存死常量 `KdbxConstants.Version.VERSION_4_1`（全仓零引用）与死字段 `SettingsUiState.kdbxFormat`（零消费方，字面量宣称 "KDBX 4.1" 而**写侧恒为 4.0**）——三者叠加易被读成「已支持/已校验 4.1」 | ① `KdbxHeader` 解析处**显式声明**策略：仅校验 major，4.x minor 一律接受（拒之反而打不开官方新写的库），并说明该声明用于消除「看起来校验了版本」的误读；② `KdbxConstants.Version` 删除死常量 `VERSION_4_1` 并在 KDoc 写明策略与「若将来按 minor 分流须补判定与用例」；③ 删除死字段 `SettingsUiState.kdbxFormat`，并在原位注明「若将来展示格式行必须取自活动库真实 `header.version`，不得写静态字面量」；④ `KdbxHeaderFieldSecurityTest` 追加**双向用例**（4.1 接受 / 3.1 拒绝） | 复核 `NEW-B02-2` |

### 66.2 验收证据

```powershell
# ① fail-closed 分支**实测**（临时向 cargo 注入非法参数，随后立即还原）
#    注入后：
.\gradlew.bat :crypto:cargoHostBuild
# → > Task :crypto:cargoHostBuild FAILED
#   error: unexpected argument '--keepasskey-fail-closed-probe' found
#   BUILD FAILED in 3s        ← 构建**失败**（旧实现在此处会被 isIgnoreExitValue 吞掉并保持 SUCCESSFUL）
#    还原后复跑：BUILD SUCCESSFUL（下述全量运行即含该项）

# ② 定向验证
.\gradlew.bat :database:testDebugUnitTest --tests "com.keepasskey.database.file.KdbxHeaderFieldSecurityTest" :crypto:testDebugUnitTest --tests "com.keepasskey.crypto.NativeBuildFailClosedTest"
# → BUILD SUCCESSFUL；tests=21 + 2 = 23 failures=0 errors=0

# ③ 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 37s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1763 failures=0 errors=0 skipped=13
#   （app 973 / core 68 / crypto 131 / database 388 / sync 203）——较上批 +4
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 3m 20s；216 actionable tasks: 48 executed, 168 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,466,567 字节，2026-09-15 17:12:10）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +4 例）**：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `NativeBuildFailClosedTest`（+2，新文件，`crypto` 模块） | 宿主 cargo 构建不得吞掉非零退出码 | 剔除注释后断言 `crypto/build.gradle.kts` **不含** `isIgnoreExitValue = true`，且**保留** `onlyIf { … cargo --version … }` 的缺失跳过路径。**非空跑**：改动前该断言必红 |
| | 宿主 cargo 构建任务仍真实执行 cargo build | 防止「为过守卫而把命令换成占位」——断言脚本仍含真实 `commandLine("cargo", "build", "--release")` |
| `KdbxHeaderFieldSecurityTest`（+2） | 4.1 头部的 minor 版本被接受（策略：仅校验 major） | **行为级**：构造版本字 `0x00040001` 的头部并完整解析，断言 major 仍为 4.0。若将来有人偷偷加 minor 白名单，本用例立即失败，迫使其显式决策 |
| | 3.1 头部的 major 版本被拒绝 | 反向边界：`0x00030001` 必须抛 `KdbxUnsupportedVersionException`（避免「放宽 minor」被误做成「放宽 major」） |

### 66.3 已知边界与口径（如实声明）

1. **本批为「部分闭环」**：`ISSUE-P3-125` 的 ①② 与 `ISSUE-P3-126` 的 ①② **未整改**，
   两条目**保留在 `ACTIVE_ISSUES.md`**（仅在行内以 `~~…~~ **【已闭环】**` 标注 ③），
   **不得**据本批声称这两条已整体闭环。
2. **fail-closed 分支的实证方式**：以「临时注入非法 cargo 参数 → 观察 `BUILD FAILED` → 立即还原」
   完成，属**一次性实测**（不留在仓库中，故无法由后人复跑）；长期防线由
   `NativeBuildFailClosedTest` 的静态守卫承担。**未**覆盖「cargo 缺失」分支的实测
   （该分支需卸载工具链，代价高于收益），其存在性由脚本 `onlyIf` 结构与既有离线降级注释佐证。
3. **`cargoNdkBuild`（Android 侧 .so）**：核查确认其**本就未**设 `isIgnoreExitValue`，
   即已是 fail-closed，本批未改动；但同时说明 `AGENTS.md` §5 原文「未装 cargo 或失败则自动降级」
   对 Android 构建而言**不准确**，已随本批更正。
4. **`kdbxFormat` 为死字段（前提更正，如实声明）**：原条目称「UI 仍标注 KDBX 4.1」，
   本次以全仓检索核实该字段**零消费方**（`SettingsUiState` 内部的死数据），
   即用户**看不到**该文案——故真实缺陷是「死字段携带失真声明」，而非「UI 展示失真」。
   本批按前者处置（删除字段）。若将来要展示格式行，须取自 `KdbxDatabase.header.version`。
5. **未覆盖**：`SettingsUiState` 中其余疑似死字段（如 `appVersion` / `buildNumber` 是否有消费方）
   **未**在本批核查——不属本条目范围，不据此扩大结论。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 **条数不变（19 项）**——两条目为**部分闭环**、
   行保留在表内；本批无新增条目。P2 计数不变（9）。

---

<a id="s67"></a>
## §67 CI 声明一致性与 DAL 不可重定向批次（2026-09-15）：ISSUE-P3-126（整体）/ P3-125 ②

> **本批次缘起**：认领两组「声明与实现/可达性不一致」项——`ISSUE-P3-126` 的 ①②（CI 用**一次性密钥**
> 签出并上传「release 形态」APK，且 workflow 说明与豁免清单实际内容**互相矛盾**）、
> `ISSUE-P3-125 ②`（DAL 校验器的「测试注入点」在生产同模块内**可写**，即可把校验整体导流到任意端点）。
> 本批后：`ISSUE-P3-126` **整体闭环**（行移出待办表），`ISSUE-P3-125` 仅剩 ①（选择器零匹配门槛）。

### 67.1 交付清单

| 编号 | 子项 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-126** | ① | CI 用 `keytool -genkeypair` 生成**一次性临时密钥**签出 `assembleRelease` 产物并作为 artifact 上传（名为 `native-gate-artifacts`），**没有任何标注**——下游取用者极易把它当作官方发布包（签名身份完全混淆） | ① 签名步骤更名并前置标注「**非发布签名**」；② 新增步骤在 APK 同目录写出 `NOT-FOR-RELEASE.txt`（说明其用途、与官方证书无关、无法覆盖安装官方版本、重新签名的要求），并纳入归档路径；③ artifact 更名为 `native-gate-artifacts-ci-signed-NOT-FOR-RELEASE`（**不删产物**——签名方案断言仍依赖它，仅消除身份混淆） | 复核 `B07-N1` |
| **ISSUE-P3-126** | ② | `dependency-scan.yml` 说明称 suppression 白名单**没有条目**，而 `.github/owasp-dependency-suppressions.xml` 实际含 **6 条** `<suppress>`（豁免 **37** 条 CVE，逐条计数见下）——声明与事实相反，任何人据此核对都会得出错误结论 | ① 说明改为**只声明纪律**（每条豁免必须附 `<notes>` 核实说明、不得通配豁免）并**明确不复述条目数**（数字必然漂移）；② 新增 `SupplyChainSuppressionPolicyTest` 守卫两条不变式：workflow 不得再出现「清单为空」式断言、清单内每个 `<suppress>` 必须带动 `<notes>`。**实测（2026-09-15）**：`<suppress>` 6 / `<cve>` 37 / `<notes>` 6（原审计记「5 条 / 36 条 CVE」系其基线时刻的快照，已漂移） | 复核 `B07-N2` |
| **ISSUE-P3-125** | ② | `DigitalAssetLinksVerifier` 把「测试注入点」做成 `@Singleton` 上的 `@Volatile internal var endpointOverride` / `clockMs`——`internal` 只限制**模块外**可见，本模块内任意生产代码都可把 DAL 拉取改写到任意 URL，从而整体架空「RP 站点显式声明授权该应用」的唯一依据 | ① 抽出 `DalEndpointResolver` / `MillisClock` 两个 `fun interface` 策略；② 校验器改为**构造注入**且**无任何 setter**（运行期无改写入口）；③ 新增 `DalVerifierModule` 提供**唯一生产实现**（官方 well-known 路径 / 系统时钟）；④ 单测改为构造时注入 fake；⑤ `DalVerifierNoRuntimeOverrideTest` 守卫：不得再出现可写字段、必须构造注入、生产策略**行为**正确（Official 解析到官方路径、SystemClock 偏差 <5s） | 复核 `NEW-N2 ②` |

### 67.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.passkey.DigitalAssetLinksVerifierTest" --tests "com.keepasskey.app.passkey.DalVerifierNoRuntimeOverrideTest" --tests "com.keepasskey.app.security.SupplyChainSuppressionPolicyTest"
# → BUILD SUCCESSFUL；tests=20 + 2 + 2 = 24 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 12s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1767 failures=0 errors=0 skipped=13
#   （app 977 / core 68 / crypto 131 / database 388 / sync 203）——较上批 +4
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 3m 1s；216 actionable tasks: 18 executed, 198 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,466,567 字节，2026-09-15 17:34:31）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
#   注：DAL 构造注入的**Hilt 图连通性**由本次 release 构建（含 Hilt 代码生成）与单测装配共同保证。
```

**新增用例（本批 +4 例）**：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `DalVerifierNoRuntimeOverrideTest`（+2，新文件） | 不得再存在可写的端点或时钟注入点 | 剔除注释后断言源码**不含** `var endpointOverride` / `var clockMs`。**非空跑**：改动前该断言必红 |
| | 端点与时钟必须为构造注入且生产策略唯一 | 断言构造参数形态存在、生产策略常量存在、`DalVerifierModule` 存在；并以**行为断言**收尾（`Official.resolve("example.com") == "https://example.com/.well-known/assetlinks.json"`、`SystemClock.now()` 与系统时钟偏差 <5s）——比源码文本断言更强 |
| `SupplyChainSuppressionPolicyTest`（+2，新文件） | 工作流说明不得再断言豁免清单为空 | 断言 `dependency-scan.yml` 不含「清单为空」式短语（正则覆盖中英变体）且指向清单文件 |
| | 每条豁免必须附带人工核实说明 | 解析清单全部 `<suppress>` 块，逐一取出 `<notes>`（剥 CDATA）断言非空白——杜绝静默豁免 |

### 67.3 已知边界与口径（如实声明）

1. **CI 改动无法在本地执行验证（如实登记）**：本批对 `build.yml` / `dependency-scan.yml` 的修改
   只能由 CI 运行时验证；本地证据为**语法与静态断言**（YAML 内容断言 + 新增的守卫用例），
   **未**在真实 Actions 上跑过。`NOT-FOR-RELEASE.txt` 的写入使用 GitHub Actions `run` 块的
   heredoc（`<<'EOF'`），其块缩进依赖 YAML 块标量语义——**该形态未经 CI 实跑**，
   如首次运行失败，属形态问题而非逻辑问题，修正成本为一次缩进调整。
2. **artifact 改名是向后不兼容的接口变更（如实声明）**：`native-gate-artifacts` →
   `native-gate-artifacts-ci-signed-NOT-FOR-RELEASE`；本仓内检索确认**无其它工作流或脚本**按名消费。
   若外部（如维护者本地脚本）按旧名下载，需同步改名。
3. **P3-125② 的可见性边界**：改为构造注入后，**运行期**已无改写入口；但**构建期**仍可
   通过替换 `DalVerifierModule` 的 `@Provides` 改变生产行为——这是 DI 的固有性质，
   与「同模块任意生产代码一行赋值即可重定向」相比已是数量级收紧，**不得**表述为「绝对不可改」。
4. **测试夹持（seam）保留在构造参数**：单测仍需指向 MockWebServer，故策略接口本身是公开类型；
   `DalEndpointResolver.Official` 是唯一生产实现，但**类型**公开意味着代码仍可自行 `DalEndpointResolver { … }`
   并在**自己的**调用点构造校验器——本批只保证「Spring/DI 装配出来的那一个实例」不可被重定向，
   **不**保证「任何人不得构造自定义校验器实例」（后者需收窄构造函数可见性，属独立议题）。
5. **P3-126 的 ①② 已在同一批次内闭环**（不同于 §66 的部分闭环），故该行已从待办表移出；
   `ISSUE-P3-125` 因 ① 仍未整改而**保留**在表内，且其 ② ③ 已在 §66/§67 分别标注闭环。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **19 → 18**（表行 12 → 11，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

