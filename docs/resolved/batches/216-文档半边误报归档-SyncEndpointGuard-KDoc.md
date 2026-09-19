# §216 批次：`ISSUE-P3-207` 判为误报归档 —— SSRF 文档半边（残句并入 `ISSUE-P2-208`）

> 日期：2026-09-19　|　条目：`ISSUE-P3-207`（**归档：判为误报**，理由见 §3；无生产代码改动）
> 关联：`ISSUE-P2-208`（机制半边，本轮由符号级升级为**源码级 + 设备级**）；
> 本轮真机取证用例 4 个（见 §4），失败面另有新立 `ISSUE-P3-209`。

## 1. 原条目（自 `ACTIVE_ISSUES.md` 剪切，原样收录）

> ### ISSUE-P3-207：`SyncEndpointGuard` KDoc 生效面声明与实际双层防线不符（主机名重定向由客户端级 `SsrfGuardDns` 全跳覆盖，未在文档声明）
>
> - **背景与证据**（2026-09-19 对抗审计候选「SSRF 未覆盖重定向目标」的文档半边；**第二轮修正其失实表述并剥离 IP 字面量半边至 ISSUE-P2-208**）：
>   防线实为**两层**——构造期 `SyncEndpointGuard.kt:81-101` 校验配置端点 + `SyncHttpClientFactory.kt:35` 把 `SsrfGuardDns`（`:241-259`）装在客户端。
>   第二轮经 OkHttp 5.5.0 `RouteSelector.nextRoutes` 源码取证修正口径：**该 Dns 防线覆盖全部重定向跳的「主机名」目标（含解析到内网 IP 的域名）**，
>   但 **IPv4 字面量目标不经 `Dns.lookup`**（旁路面单立 P2-208，本条不再声称「每一跳含 IP 字面量」「唯一生效面」——原表述失实）。
>   原审计员因 KDoc 只声明第一层而误判缺口。**残余真缺陷 = 声明与生效面不符**：改 SsrfGuardDns 接线的人无从知道它是主机名重定向场景的第二层防线，
>   测试中「302/Location 用例缺失」也正因该契约从未写明。
> - **涉及文件**：`sync/.../network/SyncEndpointGuard.kt`（KDoc）、`SyncHttpClientFactory.kt`。
> - **验收标准**：① KDoc 补「第二层：客户端级 Dns 防线覆盖全部重定向跳的**主机名**目标（IPv4 字面量不经此层，见 P2-208），禁止降级为仅构造期校验」；② 补 MockWebServer `302→内网主机名` 断言用例（与 P2-208 用例②第一组共用），把隐含防线变显性契约。

## 2. 归档依据（四轮循环对抗审查的收敛过程）

| 轮次 | 结论 | 关键证据 |
|:--:|---|---|
| 一 | `误报`：KDoc 已明写双层 | `SyncEndpointGuard.kt:23-27` 逐字「防线分两层，均 fail-closed：构造期…；连接期（DNS 解析后校验）：经 `[SsrfGuardDns]` 拦截**主机名解析结果**」 |
| 二 | `部分推翻`：指控是「重定向跳的覆盖契约未写明」，非「第二层未声明」 | 两份 KDoc **全文无**「重定向 / 302 / Location / 全部跳」字样 |
| 三 | `部分成立需修正后保留`：标题与背景断言均被证伪，仅剩一句可读性澄清；AC② 与 P2-208 纯冗余 | `SsrfGuardDns` 自身 KDoc（`:233-239`）已含「包装系统 DNS / 仅装配于生产客户端」⇒「接线人无从知道」不成立 |
| 四（终裁） | **归档（判为误报）** | 「声明范围 = 实际生效范围」；残余 nit **无独立安全语义、无独有交付物**（其唯一交付物 302 用例与 P2-208 AC② 完全重合） |

**项目口径依据**：`docs/ACTIVE_ISSUES.md` 条目维护规则第 2 条——「完全无对象可改的条目应直接归档并注明原因」；
残余那一句 KDoc 澄清**不单立条目**，改为并入 `ISSUE-P2-208` 的整改一并落笔（`ISSUE-P2-208` 第三轮终裁 ③）。

## 3. 是否「有对象可改」的判定

- **可改对象**：仅 `SyncEndpointGuard` / `SsrfGuardDns` 的 KDoc **加一句「含全部重定向跳的主机名目标」**。
- **判为不足以免立条**：该句属**可读性澄清**（其语义是已写明契约「拦截主机名解析结果」的**下位推论**，而 `.dns(...)` 是客户端级接线 ⇒ 每次主机名解析必经此层）；且该面的**真实威胁证据**（重定向确为威胁面）由 `ISSUE-P2-208` 承载，其 AC② 已经要求补 `302→内网主机名` 用例。
- **未登记重复**：`docs/architecture/已知工程限界.md`（20 节标题）与 `产品裁决登记.md`（PD-02）均未登记此面 ⇒ 非「已登记限界的重复条目」，归档纯因**断言失实 + 无独有交付物**。

## 4. 本轮真机取证用例（一并随批入库，Redmi 4X / Android 17 / API 37 全绿）

| 用例 | 模块 | 例数 | 解决的悬置前提 |
|---|---|:--:|---|
| `SsrfRedirectBypassDeviceTest` | `sync/src/androidTest/` | 3 | P2-208 **机制**（源码级 + 设备级）；`MockWebServer.url()` host = `localhost`（裁决 `CleartextPolicyDeviceTest` 归因之争） |
| `RawFileWritePermissionDeviceTest` | `sync/src/androidTest/` | 2 | P3-202 支点：裸 `FileOutputStream` 新建文件 **0600**、`mkdirs()` 目录 **0700**、私有目录 **0771**（others 仅可穿越） |
| `InlineCompressedBinaryBudgetDeviceTest` | `database/src/androidTest/` | 2 | P2-200 落点①：`maxHeap = 192 MiB`，单节点满额内联压缩附件 **`OutOfMemoryError`** |
| `PendingIntentMatchKeyDeviceTest` | `app/src/androidTest/` | 3 | P2-199 匹配键：同组件仅 extras 不同必 `filterEquals`、跨组件不 `filterEquals`、action 参与 |

设备侧结果：`:sync:` **22/22**、`:database:` **17/17**、`:app:` **67/67** 全绿（0 skipped / 0 failed）。

## 5. 过程留痕（如实登记）

1. **首版同步权限探针断言写错**：按「应用私有目录 = 0700」预设断言 `cacheDir`，真机实测为 **0771**（`rwxrwx--x`）⇒ 用例红。**该失败本身即是取证**（平台给应用私有目录的是可穿越位、others 无读写），随后把断言改为真正的安全不变量（others 不得读写）+ 自建目录的 umask 证据；**未放宽被测量的事实，只修正了错误前提**。
2. **首版 PendingIntent 用例依赖广播投递**：动态 receiver 的显式广播未送达（拿到 `null`），**该形态的投递语义受实现细节影响**、会把用例的判别力污染成「投递机制是否可用」。改为**直接验证 `Intent.filterEquals`**（匹配键本身，可静态判定），旧用例已删除。
3. **本批自身纠错记录**：主代理最初提交的「`CleartextPolicyDeviceTest` 假阳性归因」指控，经上游源码（`mockwebserver3/MockWebServer.kt`：`hostName = socketAddress.address.hostName`、`start()` 以 `InetAddress.getByName("localhost")` 绑定）与真机用例双双证伪 ⇒ 改判为「用例有效但判别力偏弱」，另立 `ISSUE-P3-209`。**证伪发生在自方主张上，按纪律如实留痕**。
