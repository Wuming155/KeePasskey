<a id="s264"></a>

# §264 AES-KDF 种子定长校验与 CI 口令卫生及 KDoc 笔误批次

> `ISSUE-P3-267` / `ISSUE-P3-265` / `ISSUE-P3-269` **三条整条闭环**（P3 6 → **3 项**）。
> 触发＝用户命题 2026-09-22「整改 ISSUE-P3-267、ISSUE-P3-265、ISSUE-P3-269」。
> 三条均为低危项：一条格式层解析期校验缺口 + 一条 CI 口令卫生 + 一条纯文档笔误，同批闭环。

---

## 1. `ISSUE-P3-267`：AES-KDF 的 `S` 未按规格校验 32 字节

### 1.1 前提复核

开工前逐项直读 HEAD 复核条目正文前提，**全部成立**：

- `KdbxKdfParameterCodec.kt` AES-KDF 分支对 `S` 仅做非空检查，长度零校验；同文件 Argon2 分支有
  `validateArgon2SaltBounds`（`ISSUE-P3-78`），两侧不对称；
- 派生侧 `AesKdfJce.kt` `SecretKeySpec(seed)`：恰 16 字节 seed 静默实例化为 AES-128，
  其它非法长度在解锁期抛 JCE 异常而非 `KdbxCorruptFileException`；
- 外层 Header 的 MasterSeed 有定长 32 字节对称先例（`KdbxHeader.kt`）。

### 1.2 整改

`KdbxKdfParameterCodec.kt`（database 模块）：

1. 新增常量 `AES_KDF_SEED_BYTES = 32`（KDoc 写明「恰 16 字节静默按 AES-128 派生」的成因）；
2. 新增 `validateAesKdfSeedBounds(seedLength: Int)`：非 32 字节即抛 `KdbxCorruptFileException`
   （消息风格对齐 `validateArgon2SaltBounds`，格式为「AES-KDF 种子长度非法: N 字节（规范固定 32 字节）」）；
3. `deserialize` 的 AES-KDF 分支在 `validateAesKdfBounds(rounds)` 之后接线该校验；
4. 类 KDoc 的参数对照表新增一行：`AES-KDF S 长度 | 固定 32 字节（规范 Byte[32]） | 32 | 与规范一致（ISSUE-P3-267）`。

### 1.3 新增用例

`AesKdfSeedBoundsTest`（database/src/test，**5 例**）——比照 `Argon2KdfSaltBoundsTest` 形态，
走「写侧序列化 → 读侧反序列化」的**真实字节流**而非直调校验函数，防止「加了校验却没接线」的假绿：

- 规范定长 32 字节种子**通过**（防误拒本仓自身写出的库）；
- 0 字节退化种子**拒绝**；
- **恰 16 字节不得静默按 AES-128 派生**（条目 AC② 点名的回归项）；
- 31 字节**拒绝**；33 字节**拒绝**。

---

## 2. `ISSUE-P3-265`：CI 工作流硬编码固定临时签名口令

### 2.1 前提复核

- 全树检索 `keepasskey-ci-ephemeral` / `CI_KEYSTORE_PASSWORD` 确认仅命中 `.github/workflows/build.yml`；
- workflow 级 env `CI_KEYSTORE_PASSWORD: "keepasskey-ci-ephemeral"` 与同 job `keytool` / `GITHUB_ENV` 段逐一在位。

### 2.2 整改（`.github/workflows/build.yml`）

1. **删除**顶层 env `CI_KEYSTORE_PASSWORD: "keepasskey-ci-ephemeral"` 及其注释行；
2. 「生成一次性 CI 测试密钥」步骤改为在 `keytool` 之前**一次性**生成运行时随机口令：
   `CI_KEYSTORE_PASSWORD="$(openssl rand -base64 24)"`（32 字符 ≥ 16，满足 `app/build.gradle.kts`
   发布签名口令闸门；base64 字符集不会命中 `FORBIDDEN_RELEASE_PASSWORDS` 黑名单与
   `__REPLACE_WITH` 占位符标记，闸门本身兜底）；
3. 同一次生成的口令同时用于 `keytool` 的 `storepass`/`keypass` 与 `GITHUB_ENV` 的
   `KEYSTORE_PASSWORD`/`KEY_PASSWORD` 导出（**未分两次随机**，杜绝 keystore 口令与 Gradle
   读到的口令不一致导致签名失败）；`CI_KEYSTORE_PASSWORD` 现仅作为该步骤内的 shell 局部变量存在；
4. 步骤内注释同步改为「口令运行时生成、非机密、不可用于发布」口径，并写明「只生成一次」的原因。

**核验**：全仓检索 `keepasskey-ci-ephemeral` 零命中（仅 `ACTIVE_ISSUES.md` 条目正文本身的引述，
随本批归档后全仓零命中）。

---

## 3. `ISSUE-P3-269`：`InnerHeader.kt` 类 KDoc「解密后、GZip 解压前」笔误

### 3.1 整改

- `InnerHeader.kt` 类 KDoc 改为「KDBX 4 内层 Header（**解密后、GZip 解压后——位于压缩流之内、XML 之前**）」；
- **AC② 全仓检索**「解压前 / 解压之前」：代码侧同形笔误仅此一处（已修）；
  `KdbxXmlBinaryNode.kt` 两处「在解压之前/之中生效/调用」指**预算记账时机**，语义正确，非笔误，不动；
  检索另揭出 [`references/KeePass-2.61.1-架构分析.md`](../../references/KeePass-2.61.1-架构分析.md)
  载荷层次图把「内层头」画在「GZip 压缩流」**之外**（`内层头（解密后、GZip 解压前）`）——同形事实错误，
  按「条目维护规则 2」一并更正：图中内层头与 XML 一并移入 GZip 压缩流层之内，
  内层头行改注「解密后、GZip 解压后，XML 之前」。

---

## 4. 验证

- 新增用例首跑：`:database:testDebugUnitTest --tests …AesKdfSeedBoundsTest --rerun` → **5 例 / 0 失败**
  （`TEST-…AesKdfSeedBoundsTest.xml`：`tests="5" skipped="0" failures="0" errors="0"`）；
- 全量 `.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL in 3m 21s / 114 executed**；
  `python tools/doc/count_test_results.py` → **`xml=362 tests=2529 failures=0 errors=0 skipped=13`**
  （§263 基线 `xml=361 tests=2524` ⇒ **+1 类 / +5 例**，恰为 `AesKdfSeedBoundsTest`，逐条可对）；
- `python tools/doc/check_md_links.py` → `BROKEN_MD_LINKS=0`；
- `python tools/doc/check_resolved_index_sync.py` → `RESOLVED_INDEX_SYNC=OK`。

## 5. 如实声明

- 未触原生面（`crypto/src/main/rust/**`、JNI 绑定）、`参考项目/`、依赖清单；
  `app/build.gradle.kts` 口令闸门本体一行未动（只消费其闸门语义）⇒ 无设备侧必跑项；
- CI 工作流改动**未在真实 runner 上复跑**（本机无法验证 `openssl rand` 分支的端到端行为；
  改动为纯 shell 变量替换，形态与原步骤除口令来源外逐字一致；随机值经 `app/build.gradle.kts`
  闸门约束静态论证）；
- `AesKdfSeedBoundsTest` 的「16 字节」回归项验证的是**解析期拒绝**（修复目标），
  不复现旧版「静默按 AES-128 派生」的派生结果差异（那需要走派生引擎，超出本条 AC 范围）；
- 未跑 `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍（未改 `PasskeyData` schema /
  `PasskeyPkcs8Codec` / KPEX 字段）。

## 6. 文档同步

- `ACTIVE_ISSUES.md`：剪出三条（P3 6 → **3 项**，标题计数与文末「本区最近一次归零记录」同步）；
- `RESOLVED_LOG.md`（本行）；`resolved/README.md`（最大 §263 → **§264**，下一批 §265）；
  `BATCH_158_PLUS.md`（§264 行）。
