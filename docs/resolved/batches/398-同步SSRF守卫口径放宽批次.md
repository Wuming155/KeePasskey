# §398 同步 SSRF 守卫口径放宽批次（`ISSUE-P2-425` 整条闭环：加密即可、明文全拒）

> **触发**：2026-10-01 用户指示整改 `ISSUE-P2-425`。用户确认产品口径＝
> 「只要是加密协议的就可以，明文传输的全部拒绝」，要求放宽构造期对内网 / 自建 HTTPS
> 端点的拦截，并同步更新 SSRF 纵深防御表述与 PD-02。
> **结案条目**：`ISSUE-P2-425`（整条闭环，P2 区 1→0）。

---

## 1. 裁决落点

- **PD-02 重写**（[`产品裁决登记.md`](../../architecture/产品裁决登记.md)）：同步端点仅 HTTPS
  （明文全拒）；自建内网 HTTPS 端点默认可用；云元数据 `169.254.0.0/16` 红线任何路径不放行；
  连接期守卫拦非预期内网到达。旧口径（2026-09-16「默认拒绝内网 / 不提供终端用户可配置入口」）
  在裁决登记表内**存照**防复用。
- **README 三处同步**：特性亮点行、基本使用第 3 步、「已知局限」段——删除
  「仅限公网端点 / 不支持自建 / 无终端用户可配置入口」误述。
- **`已知工程限界.md` §25 增补**：IP 字面量旁路面在放宽后的新语义——已配置端点为字面量时，
  可达性由工厂**预登记** `SsrfAddressApprovals` 承担（元数据不预登记）；拆除预登记 = 自托管
  IP 端点可用性回归。

## 2. 整改内容（涉及文件）

### 构造期放宽（`SyncEndpointGuard.kt`）

- `validateEndpointHost` 收窄为三类拒绝（均 fail-closed）：**明文 scheme**（`http://` 等）、
  **userinfo 注入**、**云元数据字面量**（`169.254.0.0/16`，含 IPv4-mapped 形态——
  新增 `isCloudMetadataAddress`）；RFC1918 / ULA / 链路本地（非元数据）/ `localhost` /
  `.local` / `.internal` 及一切私网字面 IP 的 **HTTPS** 端点放行。删除 `assertHostNotBlocked`
  （保留名 / 内网网段的构造期拒绝整体退役）。
- 返回值 `Unit → String`（规范化主机）：调用方须把该主机传给工厂登记连接期豁免。
- `parseIpLiteral` 转 public（工厂对字面量端点做预登记判定所需）。
- `SsrfGuardDns` 豁免分支补红线：豁免主机解析出 `169.254.0.0/16` 时仍整体拒绝
  （豁免只覆盖「内网自建可用性」意图，不延伸到元数据）。

### 连接期豁免接线（`SyncHttpClientFactory.kt`）

- `createSyncClient` 新增 `configuredEndpointHost: String? = null`：
  ① 主机名端点 → 并入 `SsrfGuardDns` 豁免集，其解析地址经豁免分支获批（元数据除外）；
  ② IP 字面量端点 → OkHttp 路由层对字面量短路、不经自定义 Dns，故在工厂处直接
  `approvals.approveAll(...)` 预登记（元数据不预登记）。
  **仅此一台主机豁免**——重定向 / DNS 重绑定跳到的其他地址仍在两层连接期复核之列。
- 未传 `configuredEndpointHost` 时行为与整改前逐位一致（默认客户端对回环仍连接期拒）。

### Provider 接线（`WebDavSyncProvider.kt` / `S3SyncProvider.kt`）

- `httpClient` 改为 init 内构建：生产路径经 `validateEndpointHost` 取回规范化主机后传入工厂；
  测试注入客户端分支不变。
- KDoc 口径同步（「不支持自建内网服务器」等表述退役）。
- `SyncNetworkOptions.kt` / `SsrfGuardSocketFactory.kt` KDoc 同步；
  `ssrfAllowedHosts` 降格描述为**高级逃生通道**（默认空集不变，AC⑤）。

### 测试（只增改、无删除）

- `SyncEndpointGuardTest`：端点段重写——元数据字面量（含 `::ffff:169.254.169.254`）仍拒；
  私网 / 环回 / `.local` / `.internal` / `fe80::` / `fc00::` HTTPS 端点放行；明文 `http://` 拒；
  白名单用例保留。`SsrfGuardDns` 新增：豁免主机解析到元数据仍拒（红线）；豁免解析地址
  获批登记进 `SsrfAddressApprovals`。
- `SyncHttpClientFactoryTest` 新增三例：已配置主机名的 Dns 豁免（对照默认客户端仍拒）；
  已配置字面量预登记放行 + 未获批内网目标（`127.0.0.2`）建连前即拒；元数据字面量即便
  作为已配置端点也不预登记（连接期仍拒）。
- `WebDavSyncProviderTest` / `S3SyncProviderTest`：原「内网与云元数据端点构造期被拒」按新口径
  拆分重写（元数据仍拒 + 自建 HTTPS 构造通过）；userinfo 用例不变。
- 设备侧 `SsrfRedirectBypassDeviceTest` 新增**对向用例**（AC⑦）：
  「已配置端点主机经生产工厂客户端放行至 TLS 阶段」——自签证书下以异常**类型**判别：
  `SSLException` = TCP 已建立（守卫放行）；`IOException("SSRF 防护")` = SYN 之前被拒（回归）。
  重定向目标仍被连接期拦截由既有用例（真实工厂客户端对字面量即拒 / 302 重定向两组）锁定。
- `app` 两处测试注释陈旧口径更正（`SyncAssemblyOffMainThreadTest` 为 JVM 用例随批实跑；
  `SyncAssemblyScaleDeviceTest` 为**注释级**更正、无用例行为变更，本批未重跑 `:app:` connected——
  依 §263 立规规避真机卸载 `com.keepasskey`（设备在线且装有两包）的风险）。

## 3. 验证与门禁读数（原样粘贴 `python tools/doc/gate_readings.py` 输出）

- 全量 JVM 单测：`.\gradlew.bat test --rerun-tasks --max-workers=1` BUILD SUCCESSFUL；
  `python tools/doc/count_test_results.py` → `xml=498 tests=3228 failures=0 errors=0 skipped=13`。
- 设备侧：`:sync:connectedDebugAndroidTest` 真机（设备名 `M332BF`，见读数文件）BUILD SUCCESSFUL，
  `TEST-M332BF - 17.xml`：`tests=25 failures=0 errors=0 skipped=0`，含新增对向用例
  「已配置端点主机经生产工厂客户端放行至 TLS 阶段（ISSUE-P2-425 对向用例）」真实执行通过。
- 门禁读数（归档后复跑，含本批次 §398）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=36  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 396 份；分册登记 398 条；全量索引 398 条；最大 §398）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 556 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

## 4. 如实声明

- 设备侧仅跑 `:sync:connectedDebugAndroidTest`（库模块独立测试 APK，不触碰主应用数据）；
  `:app:` connected 未跑（理由见 §2 末条）。
- S3 virtual-host 风格 + 自建**主机名**端点（`bucket.nas.local`）的 Dns 豁免按主机名**精确匹配**，
  不含子域名——该组合用户须用 path 风格（`usePathStyle = true`）；未在 AC 内，未扩豁免匹配规则，
  留待真实需求出现再裁。
