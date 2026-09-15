<a id="s86"></a>
## §86 DAL 出口纵深防御批次（2026-09-16）：ISSUE-P3-124

> **本批次缘起**：认领 `ISSUE-P3-124`（DAL 出口未接纵深防御）。该项**无决策阻塞**故直接闭环。
> 第四轮批注已把「作为漏洞」定为**误报**（scheme 硬编码 `https://`、路径固定、结果不回流三值枚举，
> 可利用性为零），维持 **HARDENING**——本批因此**不按漏洞修**，而是补齐纵深防御并把
> 「为什么做不到的部分」如实登记。

### 86.1 AC①：出口客户端改由 DI 提供，统一走同步侧加固工厂

| 整改前 | 整改后 |
|---|---|
| 校验器内自建 `OkHttpClient.Builder()` + 三个超时 | `DalVerifierModule` 以 `@DalHttpClient` 提供；构建路径统一走 `SyncHttpClientFactory.createSyncClient` |
| 无 `connectionSpecs` 声明（明文回退只靠平台 Network Security Config 一层） | `RESTRICTED_TLS` + `MODERN_TLS`，**显式排除 `CLEARTEXT`** |
| 无解析结果校验（`https://<域名>` 可解析到环回 / `169.254.169.254` / RFC1918 / ULA） | `SsrfGuardDns` 在**连接期**拦截内网 / 保留网段，并抵御「先公网、后内网」的 DNS 重绑定 |

**`ssrfAllowedHosts` 保持默认空集**（刻意为之）：`ISSUE-P3-121` 为自建内网 WebDAV 预留的
逃生通道**不适用**于 DAL——RP 的 `assetlinks.json` 只可能来自公网站点，放行内网目标纯属扩大攻击面。

**限定符是必需的**：应用图中已存在一个**无限定**的 `OkHttpClient` 绑定（`BreachCheckModule`，
用途与信任边界都不同），故本出口自带 `@DalHttpClient`，避免两条出路的超时 / 守卫配置被混用。

### 86.2 AC②：断言落在**生产提供方法**上，而不是测试替身

`DigitalAssetLinksVerifier` 的**逻辑**用例面向 `MockWebServer`（明文本地回环）。加固客户端
**必然**拒绝明文与回环目标，两者在传输层不可共存——故测试注入自建客户端。
这带来一个陷阱：**若把「生产出口是否加固」混在那套用例里断言，断言的其实只是测试替身**。
本批因此新增独立用例 `DigitalAssetLinksEgressHardeningTest`（5 例），**直接对
`DalVerifierModule.provideDalHttpClient()` 求值**：

1. `connectionSpecs` 非空、不含 `CLEARTEXT`、全为 TLS；
2. `dns` 确为 `SsrfGuardDns`；
3. **`lookup("localhost")` 必须抛异常**——证明「已装配守卫」不是纸面配置；
4. 连接 / 读 / 调用超时沿用 DAL 紧预算常量；
5. 接线守卫：校验器必须经 `@DalHttpClient` 注入，且**不得**残留 `OkHttpClient.Builder()`。

**「不覆盖」如实声明**：`SsrfGuardDns` 自身的判定逻辑由既有 `SyncEndpointGuardTest` 覆盖
（内网 / 云元数据字面 IP、保留主机名、DNS 重绑定、白名单豁免等 10+ 例）；本批只保证
**DAL 出口确实接上了它**——这才是本项原本缺失的那一环。

### 86.3 AC④：两侧 skip 开关不对称——留痕结论为「有意设计，不追求对称化」

`SUPPLY-04` 指出：`dalVerifier.verify` 有**两个**生产出口（Passkey 注册侧
`PasskeyCreateActivity` 与自动填充侧 `AutofillOriginResolver.kt:45`），而只有注册侧有
`skipDalVerification` 跳过开关 ⇒ 表面上是「隐私控制不对称」。

**评估结论：不对称是安全性驱动的有意设计。** 两侧虽共用校验器，但语义不同：

- **注册侧**判的是「用户显式创建凭据」。用户当场看得见这次操作，跳过只是接受
  「向未声明授权的 RP 注册」这一**自身**风险，属可授权的降级；
- **自动填充侧**判的是**域归属**——决定凭据**可以被送往哪个域名**。若提供跳过开关，等于让用户在
  **无逐次可见确认**的情况下把 web 域匹配降级为「表单自报域」（即放宽到候选/填充面），
  那是**放松放行面**，不是隐私让步。

故本批**不**把它对称化，而是把该差异与理由写入 `DigitalAssetLinksVerifier` 的 KDoc
（AC④ 的「或留痕」分支），避免后人据「不对称」把它当成待修的疏漏。

### 86.4 AC③：与 `ISSUE-P2-74`（包可见性）的关系

`ISSUE-P2-74` 已于 §54 闭环（清单补最小 `<queries>`，恢复调用方证书指纹可读）。本项不涉及包
可见性判定，**无重复排查面**——DAL 的目标 host 来自 `rp.id` / `webDomain`，与调用方包可见性无关。

### 86.5 验证证据（2026-09-16）

- `:app:testDebugUnitTest --tests "com.keepasskey.app.passkey.DigitalAssetLinks*"` →
  **BUILD SUCCESSFUL**：既有 `DigitalAssetLinksVerifierTest` **20/20**（逻辑面未回归）+
  新增 `DigitalAssetLinksEgressHardeningTest` **5/5**。
- 全量 `test --rerun-tasks --max-workers=1`、`assembleRelease`、真机
  `:app:connectedDebugAndroidTest` 结果见提交信息。

### 86.6 下一批

1. 表内其余 P3：`P3-76`（框架阻塞的已接受残余）、`P3-120`（真机 Frida 实测，**P1/UNVERIFIED**，
   为 4 条目的共同前提）、`P3-121`（内网 WebDAV 产品口径）、`P3-122`（IPC-10 未做）、
   `P3-123` / `P3-125`（CI 与供应链硬化 / 选择器零匹配占位）。
2. P2 三条仍卡决策点（`P2-47` AC②、`P2-73` 数据集回传、`P2-79` 产品口径）。
