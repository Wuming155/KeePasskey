<a id="s80"></a>
## §80 解锁节流「删键复位」与 Keystore HMAC 真机失效批次（2026-09-16）：ISSUE-P2-45 / ISSUE-P0-10

> **本批次缘起**：按 `ACTIVE_ISSUES.md` 顶部（P2 首条）认领 `ISSUE-P2-45` 的 AC②，
> 并按 `AGENTS.md` §5「Android CLI 调试约定」在**真机**（Redmi 4X / LineageOS / Android 17 / API 37）
> 上部署、实测。施工期间为「删键复位」补设备侧回归时**撞出同文件内一条独立的阻断级缺陷**，
> 即 `ISSUE-P0-10`——两项同一文件、同一通道，故并入同一批次（体例同 §74：同轮登记并闭环，
> 不在 `ACTIVE_ISSUES.md` 留存行）。

### 80.1 `ISSUE-P2-45`：删键复位旁路（判定 → 设计 → 实施）

**整改前的缺陷形态**：`SharedPrefsUnlockThrottleStore.read()` 把「三个键（计数 / 锁定截止 / MAC）
全缺」直接等同于**全新安装**。于是任何具备节流 prefs 文件写权限的攻击者，只要删掉这三个键，
失败计数即归零——**MAC 对此无能为力**（无记录即无 MAC 可校验）。

**为什么不能用「哨兵值」**（第四轮定版批注已否决）：哨兵若同样落在 SharedPreferences 内，
与三个键处在**同一删除面**上，删键者一并删除即可，原理上不可闭环。

**本批设计（Keystore 绑定的存在性证明）**：

| 改动 | 说明 |
|---|---|
| 新增 `UnlockThrottleExistenceMarkerAlias`（纯函数） | 别名 = 专用前缀 + `SHA-256(databaseId)` 前 16 字节的十六进制。**按库独立**（否则 A 库标记会误伤从未写入的 B 库），且与 MAC 密钥别名**不同域**（否则老版本安装会被误判为「记录被删除」而误锁） |
| `UnlockThrottleIntegrity` 增两条契约 | `ensureExistenceMarker(dbid)` / `existenceMarkerPresent(dbid)`；语义为「本机曾为该库写入过节流记录」 |
| `AndroidKeystoreUnlockThrottleIntegrity` 落地 | 标记 = AndroidKeyStore 内**每库一条** HmacSHA256 密钥条目。标记**不在 `shared_prefs` 文件内**，故文件级删除无法把它一并抹掉——这正是原「哨兵」方案做不到的一步 |
| `read()` 三键全缺分支 | 改由标记裁决：**标记在案 ⇒ 记录是被删除的 ⇒ `integrityIntact = false`**（fail-closed）；标记不案 ⇒ 真正的全新安装 |
| `reset()` 改写零值记录 | **不再删键**，改写一条带有效 MAC 的 `UnlockThrottleRecord()` ⇒「三键全缺」在首次写入之后**不再有任何合法来源**，删键成为唯一可产生该状态的行为 |
| `write()` 先落标记后落记录 | 顺序刻意选择 fail-closed 侧：若两步之间进程终止，留下「标记在案 + 记录缺失」⇒ 后续按篡改 fail-closed（有界锁定）；反序会留下「记录在案 + 标记缺失」的 fail-open 残局 |

**威胁模型边界（如实声明）**：标记只挡**文件级删除**。具备同 UID 代码执行 / Hook 能力的攻击者
可直接删除 Keystore 条目；**「清除应用数据」亦会同时移除标记与三键**（等同全新安装，且会一并
清空库列表），不在本条覆盖范围。本项属纵深防御卫生层。

**升级兼容**：标记是新引入的**独立别名**，老版本安装（只有 MAC 密钥、没有任何标记条目）
在「三键已删」状态下仍按全新安装放行，**不会**因升级被误锁——该场景已由宿主用例锁定。

**存量安装的过渡行为（如实声明）**：在 §80.2 整改前，任何**曾经输错过一次主密码**的安装，
其落盘 MAC 都已是**空串**（`mac()` 返 null），故这些用户在本批之前**已经处于永久 fail-closed 状态**
（每次 `gate()` 都被判篡改并再锁 30 分钟）。本批上线后他们的序列变为：
首次 `gate()` 仍判篡改 → 写入一条**带有效 MAC** 的锁定记录 → 等待 ≤ `MAX_BACKOFF_MS`（默认 30 min）
→ 锁定期满后恢复正常，且此后所有记录 MAC 有效。即**从「永久锁死」变为「一次性有界等待」**，
方向严格变好；这是存量 MAC 无法追溯重算的必然代价（空串不携带任何可校验信息）。

### 80.2 `ISSUE-P0-10`：节流 MAC 在实机恒为空（施工中的附带发现）

**发现方式**：为 `ISSUE-P2-45` 补设备侧用例时，**前置断言**「在案记录应通过 MAC 校验」
在真机上失败（同实例 `write` 后立即 `read` 即判为篡改）。宿主 JVM 用例以其自身的 HMAC 假实现
运行，**永远看不到**该失效——这正是「涉及平台 API 的逻辑不可只靠宿主单测」的实例。

**真机取证（一次性诊断用例，已删除）**：

```
[AndroidKeyStore 是否提供 Mac/HmacSHA256] false
[B Mac.getInstance("HmacSHA256","AndroidKeyStore")]  失败 NoSuchAlgorithmException:
        no such algorithm: HmacSHA256 for provider AndroidKeyStore
[D 同上且带 setDigests]                                失败（同上）
[E Mac.getInstance("HmacSHA256") + Keystore 密钥]      成功 => 32 字节
[F 同上且带 setDigests]                                成功 => 32 字节
```

**根因**：`mac()` 写的是 `Mac.getInstance(HMAC_ALGORITHM, KeystoreManager.ANDROID_KEY_STORE)`，
而 **AndroidKeyStore provider 只提供 KeyStore / KeyGenerator 等，并不注册 `Mac.HmacSHA256` 服务**。
官方 `KeyGenParameterSpec` 的 HMAC 样例同样是 `Mac.getInstance("HmacSHA256")`（默认 provider）+ Keystore 密钥
（已按 `AGENTS.md` 规则 7 经 `google-developer-knowledge` 核对）。

**影响（阻断级）**：`mac()` 吞掉 `Throwable` 返回 `null` ⇒ 写入的 MAC 恒为空串 ⇒ `verify()` 恒 false
⇒ 一旦存在记录，`read()` 恒判 `integrityIntact = false` ⇒ `UnlockThrottleManager.gate()` 恒 fail-closed。
而 `gate()` 的 fail-closed 分支**又回写一条同样空 MAC 的锁定记录**，故锁定期满后**再次**被判篡改——
**单次输错主密码即永久无法解锁**（`ThrottleConfig.enabled = false` 也拦不住：完整性分支位于开关判定之前）。

**修复**：`Mac.getInstance(HMAC_ALGORITHM)`——去掉 provider 参数，密钥仍由 AndroidKeyStore 持有与运算。

**诚实归因**：本条**不是**本批引入的缺陷（整改前 `UnlockThrottleIntegrity.kt` 即为此写法，
见 commit `dfda33d`），而是本批**首次在真机上执行该路径**时暴露的存量缺陷。`mac()` 静默吞异常的写法
是其长期未被发现的原因；本次以「设备侧必须断言 `mac() != null`」的常驻用例封堵复发。

### 80.3 新增覆盖

**宿主（JVM）用例**：

| 文件 | 覆盖点 |
|---|---|
| `SharedPrefsUnlockThrottleStoreTest`（新，10 例） | 删键复位**被判定为篡改**；重置后再次删键同样被拦；`reset` 后三键在案且计数归零；标记按库隔离；老版本升级不误判；Keystore 异常按 fail-closed；计数/ MAC 篡改回归 |
| `UnlockThrottleIntegrityTest`（7 例，含新增 3 例） | 存在性标记别名：稳定、按库区分、定长十六进制、**不与 MAC 密钥别名同域** |
| `FakeUnlockThrottleIntegrity`（新） | 以内存集合模拟 Keystore 条目，并与 prefs 替身**互不隶属**——这是被测语义得以成立的前提 |

**设备侧（instrumented）用例**：`UnlockThrottleDeletionBypassDeviceTest`（新，5 例）——
走**真实 AndroidKeyStore + 真实 SharedPreferences + 生产实现**：
① `mac()` 必须非 null 且可自校验（**封堵 `ISSUE-P0-10` 复发**，宿主用例结构上无法覆盖）；
② 整组删除三键后判为篡改；③ 重置后三键在案；④ 重置后删键同样判篡改；⑤ 标记按库隔离。

### 80.4 验证证据（2026-09-16，真机 Redmi 4X / Android 17 / API 37）

**自动化**：

- `.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL**（2m03s，114 tasks 全部真实执行）。
- `.\gradlew.bat :app:connectedDebugAndroidTest` → **BUILD SUCCESSFUL**，
  `TEST-Redmi 4X - 17.xml`：`tests="22" failures="0" errors="0" skipped="0"`
  （整改前同命令：`tests="21" failures="3"`，失败点即 §80.2 的 MAC 前置断言）。

**真机端到端（Android CLI + ADB，应用级）**：

| 步骤 | 观察结果 |
|---|---|
| 新建 `passwords.kdbx` 后**输错一次**主密码 | `com.keepasskey.unlock_throttle.xml`：`fail_count=1`、`lock_until=0`、`mac=YIDCIqBfmS76Ioy1x2TsWDIkDii+z76cdZsVKTsCmQ4=`（**非空 32 字节 HMAC**；§80.2 整改前此处为空串） |
| 随后以**正确**主密码解锁 | **成功进入库**（`密码库` / `验证码` / `生成器` / `设置`）⇒ 不存在整改前必然发生的永久锁定 |
| 成功解锁后复查记录 | `fail_count=0`、`lock_until=0`、`mac=1OTF5xpzUhigdb5f0/ao84Nn4Lq0...` ⇒ **三键仍在案**（§80.1 的 `reset` 语义） |
| `force-stop` 后 `rm` 掉整个节流 prefs 文件（模拟删键复位），再以**正确**主密码解锁 | 拒绝解锁并提示「**连续多次解锁失败，密码库已临时锁定，请在 30 分钟后重试**」；回写记录 `fail_count=5`、`lock_until = now + 1 769 163 ms`（≈ `MAX_BACKOFF_MS` = 30 min）、MAC 有效 ⇒ **fail-closed 成立且锁定有界、可恢复** |

**否定对照**：整改前的同一条设备侧链路（§80.2 诊断）显示 `mac()` 返回 null、
`MAC 密钥别名是否存在 = false`（异常发生在 `getOrCreateKey()` 之前，密钥从未被创建）。

### 80.5 残余与后续

- **`mac()` 仍静默吞异常**（`catch (Throwable) → null`）。本批以设备侧断言封堵复发，
  但**未**改为显式告警通道——如需可观测性，应作为独立条目评估（避免在此批牵入日志通道依赖）。
- 设备侧无「删除 Keystore 条目 / 清除应用数据」的防绕过主张，边界见 §80.1。
- 本批**未**触及 `ACTIVE_ISSUES.md` 中 `ISSUE-P2-83`（CM 通道签名绑定）、`ISSUE-P2-47`（回滚防护）
  等其余 P2 项；`P2-45` 的 AC① 维持第四轮复核的**撤销**结论（默认关闭为用户裁决，非缺陷）。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§80）

解锁节流「删键复位」与 Keystore HMAC 真机失效批次：以**每库一条 AndroidKeyStore 存在性标记**封住「删掉三个 prefs 键即复位计数」（并令 `reset` 改**写零值记录**而非删键）；**施工中撞出并同日闭环** `ISSUE-P0-10`——`Mac.getInstance(..., "AndroidKeyStore")` 在实机必然抛 `NoSuchAlgorithmException`（该 provider 不注册 Mac 服务）⇒ MAC 恒为空 ⇒ **单次输错主密码即永久 fail-closed**，改用官方默认 provider 写法
