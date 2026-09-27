# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项（全量待办归零）**。2026-09-25 解锁节流完整性层 fail-closed 缺陷与冗余性裁决
> （`ISSUE-P1-277`，完整性层整体移除 / 基础节流保留，`PD-46`）闭环见 §334。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-24 同步/加密/passkey 安全审计批五条（`ISSUE-P2-308` ~ `ISSUE-P2-312`）
> 已全部闭环：§315（309 / 312）、§317（308）、§318（310）、§319（311）、§320（313）；
> 2026-09-25 CI 设备门禁与供应链扫描两条（`ISSUE-P2-314` / `ISSUE-P2-315`）闭环见 §325。

## P3 低危问题、特性接线与体验优化（2 项）

> **开放项 2 条**：
> ① `ISSUE-P3-339` 仿冒域唤醒验证（2026-09-27 立项，**下一步即做它**）——本地 RP 实验室 +
> 四类仿冒origin，验「真域能唤醒并完成断言、仿冒域不唤醒且不泄露账号存在」；
> ② `ISSUE-P3-337` 扫码导入通行密钥（2026-09-26 立项；开工顺序①~⑥已完成，
> ⑦ 的相册通路端到端取样已完成（见留痕「第 5 片（前半）」），仅剩相机实拍与对真实 RP 的断言，
> 后者与本条 ① 的实验室是同一套载体 ⇒ 339 建好后 337 的 (b) 顺势收口）。
> 2026-09-26 注册响应 `transports` 撤回虚报 `hybrid`（`ISSUE-P3-338`）闭环见 §342；
> 更早的 P3 闭环流水见 `RESOLVED_LOG.md` §326 ~ §341。

### ISSUE-P3-339：仿冒域能否唤醒通行密钥——本地 RP 实验室 + 四类仿冒 origin 的「该醒 / 不该醒」双向验证

- **核实时间点与方式（2026-09-27，AVD `Pixel_10` 直读，非推定）**：
  1. **载体已具备**：`settings list secure` 读到
     `credential_service_primary = com.keepasskey.app/…KeePasskeyCredentialProviderService`
     ⇒ 本 AVD 的通行密钥提供方**已指向本应用**；`pm list packages` 有
     `com.android.credentialmanager` / `com.google.android.gms` / `com.android.chrome`
     ⇒ 「浏览器 → 系统 CM → 本应用 provider」这条链在模拟器上是**完整可跑**的（不需要真机）；
     `ro.debuggable=1` + `userdebug` ⇒ `adb root` 可用，系统 CA 注入这条路**不被 ROM 挡**。
  2. **本仓已证到哪**：`ISSUE-P3-337` 第 5 片（前半）只证到「导入件在**库内**可被消费」
     （PEM/曲线/PRF/保护位 + `pykeepass` / `keepassxc-cli` 双实现读数），
     **从未**跑过一次真实的 `GetAssertion` 唤醒；AC⑧ 的 (b) 项至今空着（见该条留痕）。
  3. **域匹配的既有实现位置**（下一步要逐条对照的代码）：`app/passkey/DomainMatcher.kt`
     （eTLD+1 判定，接 Mozilla PSL）+ `PublicSuffixList.kt` + `CallingOriginResolver.kt`
     （调用方来源归因）+ `PasskeyAssertionRequest` / `findEntriesForRpId`（候选检索：
     ⚠️ 该方法除 `passkey.rpId` 域匹配外**还有「条目 URL 域匹配」兜底分支**，
     仿冒域能否经这条兜底被捞出来，是本条要证的第一号问题）；
     注册侧另有 `CredentialManagerPackageBindingGate` / `BrowserRemedyBuilder`（特权浏览器白名单）
     ⇒ 唤醒面与授权面是**两道不同的门**，验收要分开写。

- **为什么要做（这是 WebAuthn 的核心卖点，不是锦上添花）**：
  通行密钥相对口令的唯一实质优势是「**私钥绑定 origin，仿冒站拿不到断言**」。本仓此前只做过
  域匹配算法的**宿主单测**（`DomainMatcherTest` 一类），从未在「Chrome 真发 `get()` → CM 真问
  provider → 本应用真出候选」这条链上证伪过；一旦某类仿冒形态（同后缀堆叠、同形异码、尾点、
  `android://` 与 web 域混用）在这条链上被放行，用户会在假站按下指纹并把断言交出去 ⇒
  **属可直接被利用的认证层缺陷**。

- **整改口径（实验室设计，全部本地、不接公网、不碰真实密码库）**：
  1. **RP 服务端**：复用 `tools/local-sync/` 的自签 CA 形态，新增一个极简 WebAuthn RP
     （注册 + 断言两个端点 + 一个 challenge 会话），落 `tools/passkey-phish-lab/`；
     端口与既有 9443 / 9000 错开，**只绑回环**——实测成立而非洁癖：QEMU/SLIRP 把模拟器对
     `10.0.2.2:<port>` 的连接转发到宿主 `127.0.0.1`，故 RP 仅监听回环时客户机仍可达
     （`toybox nc -w 3 -z 10.0.2.2 8443` 在宿主只绑 127.0.0.1 的前提下 `rc=0`），
     不必把实验室暴露到局域网。
  2. **名字解析**：模拟器启动加 `-dns-server <宿主IP>`，宿主侧起一个最小 DNS 应答
     `rp.testlab.xyz` / `sub.rp.testlab.xyz` / `rp.testlab.xyz.phish.testlab.xyz` /
     `rр.testlab.xyz`（punycode `xn--…`）/ `rp.testlab.xyz.`（尾点）全部指向宿主
     ⇒ 一套服务多域名，**不动 hosts、不需要公网 DNS**。
     ⚠️ 域名的 TLD **必须选在随仓 PSL 里存在的**（`xyz` / `dev` / `com` 已核实在册），
     不能用 `rp.test` 这类特殊用途域——理由与 fail-closed 后果见下「开工前置读数」第 1 条。
  3. **证书信任**：`adb root` + `-writable-system` 把实验室根 CA 注入系统信任锚
     （`/system/etc/security/cacerts/<hash>.0`）；不可行则退到「Chrome 逐次点 Advanced→Proceed」
     并**如实登记**该退化对结论的影响（安全提示页被点掉本身是一条读数）。
  4. **凭据来源**：一律用 `ISSUE-P3-337` 已跑通的**相册导入**通路把已知凭据放进**测试库**
     （模拟器上新建的 `probeui.kdbx` 一类），不触碰真实库；导入 rpId 与实验室域名逐一对应。
  5. **取样矩阵（正向 2 + 负向 6，缺一项不得声称「双向都验过」）**：
     - ✅ 应唤醒：`rp.testlab.xyz`（精确同 eTLD+1）、`sub.rp.testlab.xyz`（同 eTLD+1 的子域，规范允许）；
     - ❌ 不应唤醒：`rp.testlab.xyz.phish.testlab.xyz`（把真域**堆在后缀**里）、`phish.testlab.xyz`、
       `rр.testlab.xyz` 的同形异码（punycode 形态）、大小写与尾点变体、
       `127.0.0.1`（IP origin）、以及**URL 兜底**分支专用的一条：库里条目 URL 写
       `https://rp.testlab.xyz` 但 `KPEX_PASSKEY_RELYING_PARTY` 是**别的域** ⇒ 在
       `rp.testlab.xyz` 上发断言时该条目**是否**被 `findEntriesForRpId` 的 URL 兜底捞出
       （捞到即为兜底越界，须改代码）。
     - 每条都要留**两侧读数**：Chrome 侧（是否弹账号选择器 / `get()` 成功或 `NotAllowedError`）
       与本应用侧（`dumpsys` / logcat 里 provider 是否被调、`GetCredentialRequest` 的
       `filteringCriteria` 原文、有没有走到签名分支）。
  6. **顺带收口 `ISSUE-P3-337` 未决 6 的实测出口**：在同一条链路上做「先注册、再导入同一把、
     再断言」，回读 RP 侧对**计数器大幅跳变**的实际判定（接受 / 判克隆）；读数须写明
     「实验室自搭 RP 不代表全网 RP 的严判行为」。

- **验收标准**：
  - **AC①** 正向两条均在 Chrome 出候选并完成 `GetAssertion`（含公钥/签名可被 RP 验签通过）；
  - **AC②** 负向五条**一律不出候选**，且**不泄露账号存在性**（不得出现「该域下有 N 个账号」
    之类的可区分响应——假站只应看到「无凭据」，与真站无该凭据时**完全同形**）；
  - **AC③** URL 兜底分支的越界读数单独成条：若仿冒域经条目 URL 被捞出 ⇒ 本条**当场升级**，
    修 `findEntriesForRpId` 的兜底条件（passkey 条目不得按 URL 参与 passkey 检索），
    并补宿主用例锁「passkey 检索只认 `passkey.rpId`」；
  - **AC④** 实验室脚本与夹具**可复跑**（`tools/passkey-phish-lab/` 内含 README 与一条总入口，
    读数原样进批次文档；不得只留手工点击的口头结论）；
  - **AC⑤** 全量 `test --rerun-tasks --max-workers=1` 与 `gate_readings.py` 7/7 PASS；
    改动若涉设备面，按 `AGENTS.md` §5 设备纪律执行——
    ⚠️ **§263 前置闸门适用**：实验一律在 AVD `Pixel_10` 上做；在连的小米 M332BF 装的是
    **真实密码库**，禁跑 `connectedDebugAndroidTest`、也**禁止**往里导入实验室凭据。
  - **AC⑥ 分级规则（写死，防止把缺陷当体验问题拖着）**：AC② / AC③ 任一不成立 ⇒ 本条**立即**
    按 P0/P1 重评并另立缺陷条目（认证层可被绕过不属「低危」），本条只留实验室与读数作证据。

- **初版实验室设计的两处概念错误（2026-09-27 由用户质疑照出，先更正再动手）**：
  1. ⚠️ **威胁模型不真实**：初版靠「`adb root` 注系统 CA + 改 `/system/etc/hosts`」把仿冒域名
     弄进模拟器，可**真实攻击者没有设备篡改能力**——他只有公开 DNS 与一张有效证书
     （免费 ACME 恰恰是钓鱼站的常态）。⇒ 该路线即使跑通也**不构成证据**（它测的是"我改了设备"
     而不是"应用挡得住钓鱼"）。设备侧篡改已全部撤销：注入的 `cfff3353.0` 已删
     （`/system/etc/security/cacerts` 回到 143 条）、`/system/etc/hosts` 的 `testlab.xyz` 条目已清空。
  2. ⚠️ **命名口径把「公共后缀」当成「可注册域」**：`xyz` 才是公共后缀，所以
     `rp.testlab.xyz` 与 `rp.testlab.xyz.phish.testlab.xyz` 的 eTLD+1 **同为 `testlab.xyz`**
     ⇒ 对 WebAuthn 它们是**同站**，拿这套名字去跑浏览器矩阵等于什么都没测
     （正负向会给出同样的结果，又是一次假绿）。
     **代码层不受此影响**：`isDomainMatch` 走的是标签后缀判定（`d2.endsWith("."+d1)`），
     前缀堆叠形态实测三条均正确拒绝；已在 `CredentialProviderLookalikeMatchTest`
     里显式锁住 `rp.testlab.xyz.evil.xyz` 与 `rp.testlab.xyz.phish.testlab.xyz` 两种堆叠。
- **真实场景的取证载体（取代上面两条）**：需要**两个互不为后缀的可注册域 + 公开可信证书**，
  零设备篡改。已核实的低成本途径（依据即随仓 PSL 的私有段：`github.io` / `gitlab.io`
  / `pages.dev` / `vercel.app` / `ngrok.io` 均**在册** ⇒ 这些共享后缀下的每个项目**各自**
  是一个可注册域）：
  - 受害方：`<victim>.github.io`（GitHub Pages 自带公网可信证书）；
  - 仿冒方：另一个独立可注册域，且把受害方域名**整串做成它的前缀**（需要一方我给得起
    通配记录 + ACME DNS-01 的 DNS 区，例如便宜域或支持通配的子域服务商）；
  - ⚠️ 该路线会把实验室页面**发布到公网**（外部服务），须用户明确批准后方可执行；
    不批准则按下面的收窄口径归档。
- **若不做浏览器半环，允许归档的声称范围**（不得写得比这更多）：
  「仿冒 origin 不出候选」这一判据**只在代码层被证**（`CredentialProviderLookalikeMatchTest`）；
  Chrome → 系统 CM → provider 的真链路未证，其风险由上游 Chrome 的 rpId 校验兜住
  ——**这是假设，不是实测**，须在限界表登记为残余风险。

- **真机与公网证书实测（2026-09-27 续，按用户「连上真实手机了，继续测试」）**：
  载体已换成**公网真证书**、零信任篡改——cloudflared 临时隧道给出 `*.trycloudflare.com`
  （该后缀在随仓 PSL 私有段在册 ⇒ 每条隧道**各自**是一个可注册域，两条隧道即矩阵需要的形状），
  宿主 `curl` 不带 `--cacert` 即校验通过（`ssl_verify_result=0`）。之前的 CA 注入与 hosts 篡改
  **已全部撤销**（hosts 里 `trycloudflare` 条目 `grep -c` = 0，`/system` 重新只读）。
  新增 `tools/passkey-phish-lab/static_rp.py` + `web/index.html`（自跑页：免点击、
  结果既渲染进 DOM 又 POST 回 `/report` 落 JSONL）。读数：
  1. **基线**（库内无该域凭据）：页面回报 `outcome=threw`
     `NotAllowedError: The operation either timed out or was not allowed…`（`ms=35379`）。
     这一条是后面所有负向读数的**同形参照**（AC② 要的正是「假站所见与它完全一致」）。
  2. ⚠️ **正向对照取不到，且原因不在本应用**：AVD 先报
     「设置屏锁后，才能使用通行密钥」（平台自身闸门，`locksettings set-pin` 后可继续）；
     再跑即弹「未发现任何通行密钥」——而库内那一刻**确实有**一把 rpId 等于该隧道域的凭据
     （相册导入成功、编辑页 rpId 已回显）。logcat 给出根因：
     `Auth.Api.Credentials(1666): [GetRemotePasskeyOperation] Operation started.` /
     `Remote Entry should be available. Allowlist is null.`（pid 1666 = `com.google.android.gms`），
     而本应用 provider 的日志标签在这份缓冲里**命中 0 次**
     ⇒ `google_apis`（无 Play）镜像上通行密钥请求被 **GMS 自己的 FIDO 栈**接走，
     **第三方 Credential Provider 从未被绑定调用**。也就是说：这台机器连"真域该醒"都递不到我们面前，
     因而**无法**用它判"仿冒域不该醒"。
  3. **真手机（Redmi 4X，LineageOS / Android 17，无 GMS）**：`credential_service_primary` 指向本应用、
     AOSP `com.android.credentialmanager` 在场、公网 DNS 正常（能解析隧道域名并 `ping` 通），
     但唯一的浏览器 Firefox 里 `navigator.credentials.get()` **永不返回**（页面无结局、
     `/report` 零读数、provider 侧零 logcat）⇒ 该 ROM 上没有「会把 WebAuthn 交给平台 CM」的浏览器。
     顺带取到一条 Q1 的真机读数：无障碍转储里出现
     `packageName: com.keepasskey; text: 扫码 / 相册导入通行密钥` ⇒ 编辑页附加入口在真机渲染成立。
  4. 本机网络另实测到：模拟器**出去的 UDP/53 全不可达**（`-dns-server` 给 8.8.8.8、
     给路由器 192.168.1.1、再起宿主转发器三种配法都收不到查询，而宿主自身解析正常）
     ⇒ 名字解析只剩 hosts 一条路，而 hosts 属设备篡改，遂撤；写过的 `dns_forwarder.py`
     因**从未收到一个查询**而删除（不留没跑起来的死代码）。
- **按本条写死的红线收口**：正向对照未成立 ⇒ 本条目下**不出现**任何「仿冒域不会唤醒」的结论；
  浏览器半环判为**未做**（上面三条原因各自带证据）。已证的只有代码层半环（含两处修复）。
  要跑通浏览器半环，需要下列之一：① 换 `google_apis_playstore` 镜像并登录 Google 账号
  （GMS 的 CM 才会去绑第三方 provider）；② 真机上装一个走平台 CM 的 Chromium 系浏览器；
  ③ 自写调用 `androidx.credentials` 的测试 APK（成本最高，且 origin 归因仍要配 DAL）。

- **未决**：
  1. ~~系统 CA 注入后 Chrome 是否采信~~ **已答（2026-09-27 实测）**：不采信——文件确实落进
     `/system/etc/security/cacerts` 仍报 `NET::ERR_CERT_AUTHORITY_INVALID`；而
     `thisisunsafe` 绕过后 Chrome 直接拒绝 WebAuthn（`NotAllowedError: WebAuthn is not
     supported on sites with TLS certificate errors`）⇒ 退化路线对本条判据**无效**，不是偏差；
  2. 实验室是否需要覆盖**注册**面（本应用作为 provider 生成凭据）：本条先做**断言/唤醒**面，
     注册面若同批做须另计工作量（`PD-32` / `PD-33` 的 origin 归因链在假站上也要证一条「不填口令」）；
  3. 是否把这套 lab 长期留在仓里（CI 无模拟器网络，只能本机复跑）——登记为
     「本机可复跑、CI 不覆盖」，避免被误当作门禁。

- **开工前置读数（2026-09-27 同日续探，两条会直接决定实验室可行性的硬事实）**：
  1. ⚠️ **实验室域名不能用 `.test` / `.invalid` / `.local`**：随仓 PSL
     `app/src/main/resources/publicsuffix/public_suffix_list.dat` 里这三条**一条都不存在**
     （`grep -cE "^(test|invalid|local)$"` = 0），而 `PublicSuffixList` 的口径是
     「未知 TLD ⇒ 不可注册，fail-closed」（见该类 KDoc 第 21 行与 `isRegistrableDomain`）
     ⇒ 用 `rp.test` 做实验会让**正向也失败**，于是「仿冒域不出候选」这条负向读数**毫无意义**
     （假绿的典型形态：两种输入给出同一个结果，看起来像通过）。
     已确认在 PSL 内的可用 TLD：`com` / `dev` / `xyz` ⇒ 实验室改用
     **`rp.testlab.xyz`**（正向，eTLD+1 即该域）、`sub.rp.testlab.xyz`（正向，子域允许）、
     `rp.testlab.xyz.phish.testlab.xyz`（负向，eTLD+1 = `phish.testlab.xyz`）、
     `rр.testlab.xyz`（西里尔 р 同形异码，验 punycode 归一）、`127.0.0.1`（IP origin）。
     **顺带这条本身就是本条要证的口径**：本仓对 PSL 缺席的域一律拒绝参与匹配，与浏览器实际
     eTLD+1 计算存在**潜在分歧**（浏览器内置 PSL 与我们的资源版本可能不同步）——
     取样时须比对「Chrome 认为的 rpId」与「本应用认为的 eTLD+1」是否同值。
  2. **信任锚注入这条路比预想的硬**：AVD 上 `adb root` 可用（`uid=0` / `context=u:r:su:s0`、
     `ro.debuggable=1`、`userdebug`），但 `adb remount` 与 `adb disable-verity` 都报
     **`Device must be bootloader unlocked`** ⇒ 现镜像下 `/system/etc/security/cacerts`（143 条）**写不进去**。
     候选路线（下一步逐条试，试不通就换）：
     ① 重启模拟器时加 `-writable-system`（通常同时放行 verity 关闭）后再 `adb root && adb remount`；
     ② 走**用户级 CA** 安装（Settings → 安全 → 凭据 → 安装 CA）——但 Chrome for Android
     对用户级 anchor 的态度需实测，若拒绝则本路线作废；
     ③ 放弃浏览器半环，改测**provider 判定半环**：`adb shell cmd -l | grep -i cred` 确实列出了
     `credential` 服务，**但** `cmd credential help` 回 `No shell command implementation`
     ⇒ **本路线在 AVD 上不通**（2026-09-27 实测），无 shell 入口可直接下发 `GetCredentialRequest`；
     若日后要走这条，只剩「自写一个调用 `androidx.credentials` 的测试 APK」，而 CM 对 caller
     的 origin 归因要吃 Digital Asset Links（`PD-32` / `PD-33`），未配 DAL 的测试 APK 会被
     `CallingOriginResolver` 一侧拒掉 ⇒ 成本高于路线①，除非①②都破不通；
     ⚠️ 采用此路线时**声称范围必须收窄**为「本应用对 rpId 的接受/拒绝判定」，
     不得写成「Chrome 不唤醒仿冒站」（那是上游 Chrome 的 origin 校验，非本仓代码）；
     ④ 兜底：`http://127.0.0.1:<port>` + `adb reverse` 天然算安全上下文、无需任何证书，
     但**所有用例塌成同一个 host**，只能证「本应用对非域 origin（IP）拒绝参与」，
     仿冒矩阵做不出来 ⇒ 它只是③之外的补充负例，不能替代实验室。
  ⇒ 结论：**下一步先破信任锚（①→②），破不通就走③并如实收窄声称**；
  未破之前不得在本条目下写任何「仿冒域不会唤醒」的结论。

- **代码层半环（2026-09-27 已完成，不依赖浏览器）**：新增表驱动用例
  `app/src/test/java/com/keepasskey/app/passkey/CredentialProviderLookalikeMatchTest.kt`
  （5 例：真域 / 子域正向、四类仿冒负向、IP origin、passkey 的 URL 兜底越界、尾点与大小写），
  **首跑即红两条 ⇒ 都是实测缺陷，已随本条修掉**：
  ① `KeePasskeyCredentialProviderService.findMatchingEntries` 的 `urlMatch` 分支不区分条目类型
  ⇒ 「`KPEX_PASSKEY_RELYING_PARTY` 属 A 域、条目 URL 写了 B 域」的 passkey 条目会在 B 域被列进候选：
  签名侧另有 rpId 复核（`PasskeyAssertionActivity:139-141`）⇒ **断言交不出去**，但
  **凭据存在性跨域泄露**，且用户一点就在签名处撞上拒绝。现收紧为「URL 兜底只服务口令 / 密码条目，
  passkey 在域维度只认 `passkeyRpId` 一个真相源」（畸形 / 半迁移而无 rpId 的 passkey 条目本就无法
  完成仪式，不出候选才是对的；同表保留「普通口令条目仍按 URL 命中」的正向对照防收紧过头）。
  ② 根点归一不一致：`PublicSuffixList.normalizeHost` 去 DNS 根点而 `DomainMatcher.extractDomain` 不去
  ⇒ 同一主机名在两个归一器手里答案不同（尾点 origin 的合法凭据不出候选）。现由 `extractDomain`
  统一剔除末点，与 WHATWG URL（浏览器也吃掉末点）同形。
  验证：定向 4 类先红后绿；全量 `test --rerun-tasks --max-workers=1` `BUILD SUCCESSFUL in 3m54s`、
  `114/114 executed`、`xml=413 tests=2749 failures=0 errors=0 skipped=13`（上批 2744 + 本表 5 例）；
  `gate_readings.py` **7/7 PASS**（`tier1=0` / `tier2=36 budget=37` / `long_functions=0` /
  重言断言 0 命中·扫描 **471** 文件 / `allowed=12` 不受新类型名影响）。
- **实验室已落地（`tools/passkey-phish-lab/`，含 README）**：`make_certs.py`（自建 CA + 多 SAN 服务端证书，
  `--install` 注入设备）、`rp_server.py`（`/case/<action>/<rp>` 自跑页 + 验 `rpIdHash`/`origin`/签名 +
  计数器跳变读数）、`minicbor.py`（无 `cbor2` 依赖的只读子集）、`run_matrix.py`（6 用例矩阵驱动 + 汇总）。
  **浏览器半环当前判为「无效」而非通过**：首轮实跑六例全无页面读数（Chrome 不认注入的系统锚），
  而 `run_matrix.py` 原版把它们误判为 PASS——已由本条实测触发改成
  「无浏览器读数 ⇒ 无效，不得计为通过」（这条改动本身就是本条的价值所在：
  P1/P2 FAIL 而 N1~N4 PASS 六个用例同一个原因，就是环境假绿）。
  **剩余阻塞**：需要「两个互不为后缀的可注册域 + Chrome 认的信任链」——自有域名 + ACME(DNS-01)
  最省，或企业策略放行自建 CA，或换自带信任库的浏览器（换浏览器须重述判据）。

### ISSUE-P3-337：PD-08 扫码导入通行密钥落地——顶栏扫码按载荷分流（TOTP / 通行密钥），确认在先、字节通道解析、落 `KPEX_PASSKEY_*`

- **优先级理由**：属「进阶特性接线」（P3），非缺陷；**不含**新协议面（hybrid / caBLE 跨设备注册另案，见「关联」）。
- **核实时间点**：2026-09-26（本条目立项当日）。
- **核实方式**（逐条可复跑）：
  1. 全仓 `grep -rn --include=*.kt -lE "PasskeyImport|importPasskey|CredentialExchange|PasskeyDictionary|cxf"`
     于 `app|database|core` 的 `src/main` → **零命中**；`res/values/strings.xml` 无 passkey 导入/导出/CXF 文案
     ⇒ **`PD-08` 已裁决但未实现**（非"部分实现"）。
  2. `core/.../model/PasskeyKeyText.kt:107 derToPemChars(der: ByteArray)` **已存在**（PKCS#8 DER → PEM，
     零 `String` 中间量、`finally` 清零）；`:81 pemToDer`、`:145 sniffAlgorithmId`（PEM / hex 标量 / Base64 /
     裸 DER 四级嗅探，`ISSUE-P3-214`）均现成 ⇒ **无需新写 DER→PEM**。
  3. `app/.../passkey/SimpleJson.kt:37 parse(text: String)` 入参为 `String` ⇒ **不得**用于 CXF 载荷：
     CXF 的 `key` 字段是 PKCS#8 私钥，经 `String` 即物化为不可擦除对象，违反 `AGENTS.md` §3 敏感数据铁律
     （同文件既有先例见 `core/.../PasskeyKeyText.kt:20-24` 的「全部 API 只接受/返回 `ByteArray`」纪律）。
  4. 顶栏扫码链现状（`VaultListViewModel.kt:451 onQrCodeDecoded` → `VaultListActionController.kt:296
     addEntryFromScannedOtpauth`）：只读门槛 → **`otpauth://` 前缀强校验**（`:288-290` 记有教训：扫码面对
     任意二维码，宽容解析器会把纯字母单词当 Base32 种子）→ CharArray→UTF-8 字节（含编码器内部 buffer
     一并清零）→ 解析 → 元数据先取、种子副本即刻擦 → 建条目 → `saveEntry(totpSecretChars=…)` 擦除契约
     + 协程体 `finally` 幂等兜底 ⇒ **本条目的通行密钥分支按同族模板写**。
  5. 取景与相册通路（`TotpScanDialog`：CameraX + `MultiFormatReader`(POSSIBLE_FORMATS=QR, TRY_HARDER)
     + 四朝向重试 + `MAX_GALLERY_IMAGE_DIMENSION=2400` 有界位图解码，§340/§341 已合入**同一对话框**）
     ⇒ **相机与相册两个入口均现成，本条目零改动**（PD-08 要求的"双入口"由此满足）；
     **两条通路共用同一解码器**：`TotpGalleryImport.kt:211-253` 与 `TotpScanDialog.kt:357-380` 都是
     「灰度字节 → `PlanarYUVLuminanceSource` → `HybridBinarizer` → `rotateYPlane90` 四朝向」，
     相册侧仅多一步 ARGB→亮度灰度（`Y=(299R+587G+114B)/1000`）与透明像素合成白底（§341）。
     ⇒ **本条目不需要新增任何解码代码**（早前一版正文误写为「相册走 `RGBLuminanceSource`」，已更正）。
  5b. 字节通道原语核对：`WebAuthnRequestOptions.kt:146 base64UrlDecode(text: String)` 为 **String 入参**，
     不可用于私钥；须直接调 `Base64.getUrlDecoder().decode(ByteArray)`（仓内先例
     `PasskeyRegistrationPayload.kt:86`）。
  5c. 编辑页 KPEX 写入口：`PasskeyEntryCoordinator.kt:40 saveOrReplacePasskeyEntry(data, boundPackage)`
     （内部 `:83 findReusablePasskeyEntry` 实现「找到可复用条目则整体替换」，`:111 saveNewPasskeyEntry` 新建）
     ⇒ **Q1 编辑页入口须复用 `saveOrReplacePasskeyEntry`，不走通用 `saveEntry`**；其 `PasskeyData`
     私钥的清零义务归属须在开工首步核实（与顶栏 `saveEntry(totpSecretChars=…)` 的擦除契约不同源）。
  5d. 导航能力核对：`Screen.EntryEdit.createRoute(id)` 与路由的 `entryId` 参数**均已存在**
     （`KeePasskeyNavGraphRoutes.kt:186` 详情页→编辑页就在用；`:193-212` 声明 `entryId`/`groupId`/`templateId`
     三个可空参数）⇒ 顶栏只需把回调透传进 `VaultListScreen`，**无需新建导航机制、无需 ViewModel 新消费链**；
     仅「打开后聚焦通行密钥区块」需要新增一个可选字符串参数。
     ⚠️ **不得**借 `templateId` 那类路由参数承载私钥（参数只带 id、目标页自查库的模式对"尚未入库的私钥"不适用）。
  6. **CXF v1.0 PS 规范原文逐字核对（2026-09-26，直取 `cxf-v1.0-ps-20250814.html` 全文本地解析）**：
     - §3.3.12 `Passkey` 字典 ABNF **逐字**为
       `{ type:"passkey", credentialId:b64url, rpId:tstr, username:tstr, userDisplayName:tstr,
          userHandle:b64url, key:b64url, ?fido2Extensions:Fido2Extensions }`
       —— **`alg` 成员不存在**；`username`（小写 n）与 `userDisplayName` **无 `?` 前缀，即必填**；
       唯一可选成员是 `fido2Extensions`。
     - 文档结构：`Document{ … accounts:[*Account] }` → `Account{ … collections:[*Collection], items:[*Item] }`
       → `Item{ … credentials:[*Credential] }`；而 `Collection.items` 是
       **`LinkedItem{ item:b64url, ?account:b64url }`（仅引用，不含凭据）**
       ⇒ **凭据实际路径 = `accounts[].items[].credentials[]`**；`PD-08` 原文所写
       `collections[].items[].credentials[]` **系规范引用错误**（本条目初稿照抄，已一并更正，见「关联」）。
     - §3.3.12.2 `Fido2Extensions{ ?hmacCredentials, ?credBlob, ?largeBlob, ?payments }`，其中
       `hmacCredentials{ algorithm, credWithUV, credWithoutUV }` 原文写明「holds the information necessary
       for either the [webauthn-3] **prf extension** or the [FIDO-V2.1] hmac-secret extension」
       ⇒ **CXF 确实承载 PRF 秘密**（本条目初稿「CXF 不承载 prf」为错，见整改口径 6）。
     - §3.3.12.1 原文：「All other members of the `Passkey` dictionary MUST NOT be user editable
       as they are required for the WebAuthn ceremonies to be successful.」

  7. `PD-08` 正文原写「相机扫码复用 **`SecureCaptureActivity`** 受保护取景」（现第 2 项），该组件已随
     `ISSUE-P3-319` 退役（`app/src/main/AndroidManifest.xml:244-246` 注释在案）
     ⇒ **裁决文档内的过时组件引用，已随本条目 2026-09-26 改写第 2 项时一并勘误**。
  8. `PopupSecureFlagInventoryTest`（`app/src/test/.../security/`，2026-09-26 盘点结论：调用点 4 处 /
     菜单项 11 个，全部静态文案）与 `PD-47`（扫码对话框 `FLAG_SECURE` 跟随设置开关）为本条目的守卫账目。
  9. **同类实现对照（2026-09-26，`参考项目/passkeys参考/` 三副本只读取证；全量读数、检索符号清单与
     未命中声明见 [`references/扫码导入通行密钥的参考项目对照.md`](references/扫码导入通行密钥的参考项目对照.md)）**：
     `Authnkey-main`（MIT，CTAP2 over NFC/USB 硬件密钥）/ `fenris-authenticator-main`
     （⚠️ **根目录无 `LICENSE` 文件**）/ `open-passkey-main`（MIT，RP 侧验证库）三家**均不实现 CXF**
     （`hmacCredentials` / `credWithUV` / `Credential Exchange Format` 三符号全目录**零命中**），
     也**均不消费** `fido2Extensions`；fenris 唯一的「passkey 导入」字样是**无人填充的死钩子**
     （`importformat/ImportFormatDecoder.kt:16` 的 `DecodedImport.passkeys`、`vault/Passkey.kt:94` 的
     `NewPasskey.privateKeyDER`）⇒ **本条通路在开源品类内无可抄对象**，正确性只能由规范原文 +
     规则 8 的互操作对拍担保。同批两条判据级读数：① fenris 相机解码裁「中心 2/3」而取景框画 0.65、
     两常数不同源（`codec/QrCode.kt:55-72` vs `ui/components/QrScanner.kt:236-259`），而本仓相机通路
     **整帧解码不裁剪**（`TotpScanDialog.kt:282-301`）⇒ 本仓的对应风险点是**分辨率**而非裁剪区（核实 11）；
     ② 同类实现**都不存** PRF / `credBlob` / `largeBlob` / signCount（`CreateResponse.kt:83`、
     `AuthResponse.kt:23-24`）⇒ 本条目的扩展字段处理面**无先例**。
  10. **CXF 规范自身的内部不一致 + 三条解析器硬事实（2026-09-26，逐字回读 `cxf-v1.0-ps-20250814` 全文）**：
     - **附录 A 示例**那把 passkey 的扩展写成
       `"fido2Extensions":{"hmacSecret":{"algorithm":"HS256","secret":"c2VjcmV0X2tleV9kYXRh"}}`，
       而 **§3.3.12.2/.3 的 CDDL** 是 `hmacCredentials{algorithm, credWithUV, credWithoutUV}`、
       **§3.3.12.4** 枚举唯一值 `"hmac-sha256"` ⇒ **键名 / 成员数 / 算法值三项全部冲突**，
       且 `hmacSecret` 与 `HS256` 在整份规范里**只出现这一次**（恰在最易被照抄的示例段）。
     - §3.3.12.3 原文「Importing providers that encounter an unknown algorithm **SHOULD ignore this entry**」
       ⇒ 照字面实现「未知 algorithm 即忽略」，**规范自己的示例值 `HS256` 会被判为未知而丢掉 PRF**。
     - 硬事实三条：① §3.1 `Header = {version{major,minor}, exporterRpId, exporterDisplayName, timestamp,
       accounts}` —— **无 `documents` 外层**（本条目初稿臆造的 `{"documents":[…]}` 信封不成立，已作废）；
       ② §3.1.1「Any participant using this format **MUST ignore unknown fields or enumeration values**」
       ⇒ 未知成员**不得**当拒收理由（AC② 原「未知 `type` 值 ⇒ 拒」已按 口径 2″ 分层更正）；
       ③ 附录 A 示例文档混装 **15 条凭据 / 14 种 `type`**（`totp` / `credit-card` / `ssh-key` / `wifi` /
       `passport` …），passkey 只占 1 条 ⇒「扫到合法 CXF 文档但里面没有通行密钥」是**常态分支**、不是畸形载荷。
     - 示例内那把 passkey 的 `key` 实测：b64url 184 字符 → **PKCS#8 DER 138 B**，含
       `06 07 2A 86 48 CE 3D 02 01`（ecPublicKey）与 `prime256v1` 参数、内层带 `[1]` 公钥位
       ⇒ 本仓 `PasskeyKeyText.sniffAlgorithmId` 的 ES256 判据**在该官方样本上逐字节命中**（口径 3 的实证依据）。
  11. **载荷尺寸与 QR 密度实测（2026-09-26，`com.google.zxing:core` 3.5.4 本机直跑，与相机通路同配置：
      `POSSIBLE_FORMATS=[QR_CODE]` + `TRY_HARDER` + `PlanarYUVLuminanceSource` + `HybridBinarizer`）**：
     - 紧凑 JSON 字节：**规范附录 A 单 passkey 对象 471 B**；改按 §3.3.12.3 双值口径的等价对象 **570 B**；
       再套 §3.1 `Header` + `accounts/items` 信封（仍只 1 把凭据）**900 B**；附录 A 全示例（15 凭据）
       **11 863 B**。现状基线 otpauth URI **127 B** 作对照。
     - 单张 QR 容量上限（同库二分实测；已知值反校：v40-L = 2953 B 与 ISO 表一致）：
       **L 2953 / M 2331 / Q 1663 / H 1273 B** ⇒ 附录 A 全示例抛 `WriterException: Data too big`
       ⇒ **文档级多凭据载荷物理上装不进单张 QR**（口径 9 的依据）。
     - 版本与模块数：471 B → v15(77)@L / v20(97)@Q；570 B → v16(81) / v23(109)；
       **900 B → v21(101)@L / v29(133)@Q**。
     - 每模块像素数（px/模块）：最近邻渲染 + 「3×3 均值＝PSF≈1 采样像素」失焦模型 + ±20 灰阶噪声，
       **上述全部载荷的最小可解值一致为 清晰 2 / 失焦 3 / 失焦+噪声 3 px/模块**。
       ⚠️ 该模型是**乐观上界**（理想对齐、无透视、二值对比），真机只会更差 ⇒ 阈值最终由 AC⑧ 实拍定。
     - 落到本仓相机通路：`TotpScanDialog.kt:282-284` 建 `ImageAnalysis` **只设了背压策略、未设分辨率**，
       而官方文档原文为「**ImageAnalysis has a default ResolutionStrategy with bound size as 640x480**
       and fallback rule of `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`」
       （developer.android.com `ImageAnalysis.Builder#setResolutionSelector`）⇒ 短边 480 px 下
       900 B 文档（101 模块）在码占画面 50%/65%/80% 时分别为 **2.4 / 3.1 / 3.8 px/模块（@L）**、
       **1.8 / 2.3 / 2.9（@Q）** —— 前者贴乐观 floor、后者**连乐观模型都不过**；
       同载荷在短边 1080 px 下为 4.1 / 5.3 / 6.5（@Q）与 5.3 / 7.0 / 8.6（@L）⇒ 有余量（⇒ 口径 10）。
     - 相册侧不受此限：`MAX_GALLERY_IMAGE_DIMENSION=2400`（`TotpGalleryImport.kt:67`）下
       101 模块在 50%/65% 填充时为 11.9 / 15.4 px/模块。
  12. **导入凭据的计数器起点（2026-09-26，WebAuthn L3 逐字回读 + 本仓现状核对）**：
     §6.1.1 *Signature Counter Considerations* 原文「Authenticators that do not implement a signature counter
     leave the `signCount` in the authenticator data **constant at zero**. … **If either is non-zero, and the new
     signCount value is less than or equal to the stored value, a cloned authenticator may exist**, or the
     authenticator may be malfunctioning, or a race condition might exist…」；§7.2 断言验证子步同向。
     本仓现状：`PasskeyData.kt:105-107` 以扩展键 `Passkey.SignCount` 承载计数（KPEX schema 无该键）、
     `:319 SIGN_COUNT_UNKNOWN=0`、`:396 nextSignCount` 恒 +1 饱和、断言侧
     `PasskeyAssertionActivity.kt:238 incrementPasskeySignCount(entryId)` 的返回值写进 authData。
     ⇒ 一把**外部注册**的凭据导入后，RP 侧存的是**别家认证器**给的计数（很可能 >0 且我方不可见），
     而我方默认「无该键 → 0 → 首次断言发 1」起步 ⇒ **正落入 §6.1.1 的「≤ stored ⇒ 克隆嫌疑」区间**。
     该判据不是纸面推演：open-passkey 的回滚检测正是 `storedSignCount > 0 && new <= stored`
     （`packages/core-ts/src/authentication.ts:246`）⇒ 需裁决，见**未决 6**。

- **背景**：`PD-08`（2026-09-18）已裁决载荷 = **FIDO CXF v1.0 单凭据 `Passkey` 字典 JSON**（`credentialId` /
  `userHandle` / `key` 为 Base64URL，`key` = PKCS#8 ASN.1 DER），解析器须兼容三级形态（裸 Passkey 对象 →
  CXF 凭据数组 → CXF 完整文档 `accounts[].items[].credentials[]`，取首个有效凭据）并兼容 KeePassXC
  `.passkey` 单对象 JSON；落库口径 = `KPEX_PASSKEY_*`（细则 `PD-09`），私钥走受保护字段 CharArray 链路、
  还原为 **PKCS#8 PEM** 驻留格式，URL / 用户名留白以 rpId / userName 补齐（⚠️ 该「留白补齐」口径与规范
  §3.3.12 的必填要求**冲突**，裁决见整改口径 3 与「未决」第 1 项），条目已有通行密钥时整体替换。
  本次用户已定两点：**Q1 编辑页通行密钥区块保留导入入口（挂到当前条目、可整体替换）**；
  **Q2 顶栏扫到通行密钥时不得静默建条目，须先经用户确认**。

- **整改口径**：
  1. **分流**：`onQrCodeDecoded` 内先做纯函数字节判定 `ScanPayloadClassifier.classify(bytes): Totp | Passkey | Unknown`
     （跳过前导空白后：`otpauth:` 前缀（忽略大小写）→ `Totp`；`{` 或 `[` 起始 → `Passkey`；其余 → `Unknown`）。
     **禁止回退式猜测**（不得"先按 TOTP 解析、失败再按通行密钥"）；`Unknown` 复用现键
     `vault_scan_invalid_qr` 如实提示、不落库、不回显内容。**TOTP 分支一字不改**，其现有失败路径与
     前缀强校验口径原样保留。
  2. **解析器（新增 `app/.../passkey/PasskeyCxfReader.kt`，纯 Kotlin 零 Android 依赖）**：入参 `ByteArray`
     （UTF-8 载荷字节），**手写最小 JSON 定位扫描器**，按规范白名单字段取值——
     `type` / `credentialId` / `rpId` / `username` / `userDisplayName` / `userHandle` / `key` /
     `fido2Extensions`（含其子项 `hmacCredentials{algorithm, credWithUV, credWithoutUV}` / `credBlob` /
     `largeBlob` / `payments`）；**全部值以 `ByteArray` 产出**，非敏感字段转 `String` 供 UI，
     私钥永不进 `String`（Base64URL 解码须 `Base64.getUrlDecoder().decode(ByteArray)` 字节直调，见核实 5b）。
     嵌套定位三条：裸 Passkey 对象、凭据数组、完整文档 `accounts[].items[].credentials[]`
     （**`collections[].items[]` 是 `LinkedItem` 引用，不含凭据，不得当取凭据路径**）。
     KDoc 须写明「为何不用 `SimpleJson` / 不用 `org.json`」（前者入参 `String` 违反 §3；后者在宿主单测为
     未实现桩，同 `CallingOriginResolver.kt:136-138` 既有理由）。
     **2′ 扩展键别名（处置规范自身的不一致，见核实 10）**：白名单**必须同时认** §3.3.12.2 的
     `hmacCredentials`（`credWithUV` / `credWithoutUV`）与**规范附录 A 示例**的遗留形
     `hmacSecret`（单成员 `secret`）——只按 CDDL 拼写取值会让**规范自己的示例**解出「无 PRF」，
     而 `PD-48` 裁决二的前提正是「双值不可恢复，这次不存永远补不回」。
     `algorithm` 认 `hmac-sha256`（§3.3.12.4 唯一枚举）与 `HS256`（示例值）为**同义**（两者都指
     HMAC-SHA-256，与本库 `PasskeyPrf` 同式）；其余值按 §3.3.12.3「unknown algorithm **SHOULD ignore
     this entry**」——**只丢该扩展项、保留凭据**，并进口径 4′ 的确认清单。
     双值长度规范用词是「**SHOULD** be 32 bytes」⇒ 非 32 字节**不拒收**（处置见未决 7）。
     **2″ 未知成员 / 多凭据文档（§3.1.1 与示例实证）**：① §3.1.1「MUST ignore unknown fields or
     enumeration values」⇒ 未知成员一律**忽略而非拒收**（新增字段与新增枚举值按 §3.1.1 明文**不算**
     破坏性变更）；② 一份文档可混装 14 种 `type`（核实 10③）⇒ 非 `passkey` 凭据**跳过并计数**，
     「未知 `type` 即拒」只适用于**裸单对象**形态（AC② 原写法已按此分层更正）；
     ③ 文档形态须读 §3.1 `Header.version.major`，**非 1 即拒**；④ 文档内 **≥2 把 passkey** 时
     「取首个」等于静默丢弃其余，与 AC⑪② 自相矛盾 ⇒ 必须在确认对话框点名「另有 N 把未导入」（未决 8）。
  3. **算法与字段判据（fail-closed，逐值写死）**：**规范无 `alg` 成员** ⇒ 算法一律由
     `PasskeyKeyText.sniffAlgorithmId(der)` **从 PKCS#8 DER 的 OID 判定**，判定为空（无已知 OID）即拒；
     **不得**再写「`key.alg` 与嗅探交叉核对」（该判据对 CXF 载荷恒不触发，属虚构）。
     必填缺失判据分两层（规范 `username` / `userDisplayName` 亦为必填，但只具展示语义）：
     ① **参与 WebAuthn 仪式的字段**（`type`=="passkey" / `credentialId` / `rpId` / `userHandle` / `key`）
     任一缺失、Base64URL 非法、DER 无已知 OID、嵌套层级不符 ⇒ **一律拒绝入库**；
     ② 仅 `username` / `userDisplayName` 缺失 ⇒ **容错补齐**（以 rpId / 空串占位），并在条目上
     **如实标注「来源未提供」**，不拒收（**2026-09-26 用户裁决 `PD-48` 裁决一**：展示字段不参与仪式，
     为其拒收等于白扔一把完好的私钥，且一刀切会误伤 `PD-08` 已裁决兼容的 KeePassXC `.passkey` 对象）。
     补齐值**只进展示字段**，**不得**回填 `userHandle` 等仪式字段。
     错误一律只报静态错误码文案，不回显载荷。
  3b. **字段只读口径（规范 §3.3.12.1 原文要求）**：`Passkey` 字典除 `username` / `userDisplayName` 外的成员
     「MUST NOT be user editable」⇒ 导入后的条目在编辑页**不得**允许手改 `rpId` / `credentialId` /
     `userHandle` / `key`（现编辑页若这些字段可写，须同批锁为只读并在 UI 上如实标注原因）。
  4. **Q2 的实现取向（关键设计）**：**私钥不跨页承载**。顶栏分支在**当前作用域**内解析成功后弹一个
     轻量确认对话框，只呈现**非敏感元数据**（rpId / 算法 / credentialId 摘要 / 拟用标题），
     用户确认 → 以与 `addEntryFromScannedOtpauth` 同族的 `addEntryFromScannedPasskey(chars)` 落库 →
     成功后按 id 打开该条目编辑页；用户取消 → 擦除、不落库、不导航。**不引入 draft / SavedStateHandle
     承载敏感值**（`P2-105` 立过「不得经框架缓存敏感值」的规矩）。
     导航能力已核实为现成（见核实 5d：`Screen.EntryEdit.createRoute(id)` + `entryId` 参数已在用），
     本条目只需把回调透传进 `VaultListScreen`；「打开后聚焦通行密钥区块」至多新增一个**可选字符串参数**
     （取值如 `passkey`），**不得**在路由参数里承载任何凭据类值。
     ⚠️→✅ 确认对话框的 `FLAG_SECURE` 归类**已裁决**（2026-09-26 用户，`PD-48` 裁决三）：
     **跟随「禁止截屏与录屏」开关**，与 `PD-47` 同一口径，**不**列入 4 类无条件强制遮罩对话框；
     另两层防护（反 overlay / 点击劫持过滤）始终施加。
     **4′ 确认在先（同类实现印证的更强口径，取代「导入后告知」）**：fenris 的导入通路把「本实现不支持的项」
     建成显式 `incompatible: List<IncompatibleItem>`（`importformat/ImportFormatDecoder.kt:17,20-27`），
     由 `ConfirmImportSheet` **先列清单、用户确认后才导**（对照见
     [`references/扫码导入通行密钥的参考项目对照.md`](references/扫码导入通行密钥的参考项目对照.md) §2.5）。
     ⇒ AC⑪② 的提示时机**由「导入后告知」提前到「导入前列清单等确认」**，与本口径 4 的确认对话框
     **合用同一个**，不新增对话框、不新增 `FLAG_SECURE` 账目（AC⑥ 的计数不变）。
  5. **Q1 编辑页入口**：通行密钥区块新增「扫码 / 相册导入」，复用同一对话框与同一 `PasskeyCxfReader`，
     落库**须复用 `PasskeyEntryCoordinator.saveOrReplacePasskeyEntry`**（`:40`，其 `:83
     findReusablePasskeyEntry` 已实现「找到可复用条目则整体替换」，正是 Q1 语义），
     **不走通用 `saveEntry`**；KDBX 历史快照可回滚。两条写入口的私钥清零义务归属不同源，须分别立断言（AC③）。
     **可选收敛（不作本条目硬要求）**：顶栏新建分支亦改走 `PasskeyEntryCoordinator.saveNewPasskeyEntry`
     （`:111`，其 `toCustomFields` 自带逐键保护位与 `passkeyTitle` 口径）⇒ 两条写入口合成一条、
     AC④ 的保护位断言可只测一处。采纳前须先核两点：① 其 `parentGroupId = null` 时的**落组语义**是否等于
     「当前分组」（顶栏要求落在用户当下所在分组）；② `PasskeyData` 内私钥材料的**清零义务归属**。
  6. **落库**：`key` 的 Base64URL DER → `PasskeyKeyText.derToPemChars` → 构造 `PasskeyData` →
     `KPEX_PASSKEY_*` 字段（明文：`_RELYING_PARTY` / `_USERNAME` / `_FLAG_BE` / `_FLAG_BS`；
     **受保护仅四键**：`_USER_HANDLE` / `_CREDENTIAL_ID` / `_PRIVATE_KEY_PEM` / `_PRF`）；
     **PRF 不是"留空"**——规范 `fido2Extensions.hmacCredentials{algorithm, credWithUV, credWithoutUV}`
     明确承载 webauthn-3 `prf` / FIDO `hmac-secret` 秘密，而本库 `_PRF` 只有**单一秘密**字段，
     存在真实 schema 落差 ⇒ 须裁决「双值取哪一个 / 是否扩 schema」（见「未决」第 2 项）；
     载荷含 `credBlob` / `largeBlob` / `payments` 等本库不承载的扩展时，**必须如实提示丢弃项，禁止静默丢弃**；
     `_FLAG_BE` / `_FLAG_BS` 按 `PD-09` 口径置位；标题 = **rpId**（`userDisplayName` 是不可信自由文本，
     只进用户名字段，不得作为标题——避免二维码决定给用户看的字）。
  7. **零网络**：全程不发起任何请求（载荷内出现 URL 也不解析、不访问）。
  8. **同批文档流转**：改写 `PD-08` 的入口口径（编辑页双入口 → **顶栏分流为主入口 + 编辑页保留附加入口**）
     并登记改写日期与理由；修正其「复用 `SecureCaptureActivity`」过时引用为现件；`PD-34` 类型名有界性
     机检若命中新类型名则同批扩登记。
  9. **载荷尺寸上限与「不分片」（核实 11 实测）**：单张 QR 的物理上限是 **v40-L 2953 B**（纠错等级越高越低：
     M 2331 / Q 1663 / H 1273），而文档级多凭据载荷（规范附录 A 全示例 11 863 B）**装不进单张 QR**
     ⇒ **v1 明确不做多张装配 / 动画 QR**（三家参考项目亦无分片协议可抄，对照文档 §2.1）。
     同时给手写扫描器一个**硬界**：`MAX_IMPORT_PAYLOAD_BYTES = 4096`（> 任何单张 QR 的解出上限，
     又给解析器常量界）；超界 → 静态错误码文案、不落库、不回显，并在文案里如实指引
     「单张二维码放不下该载荷」（指引的是**换用只含单把凭据的导出**，不得指引用户去访问任何链接）。
  10. **相机分析流分辨率（本条目**唯一**触及 TOTP 共享面的改动）**：`TotpScanDialog.kt:282-284` 建
     `ImageAnalysis` 未设分辨率，官方默认为 **640×480**（核实 11 原文），而 900 B 文档级载荷对应
     101 模块 ⇒ 480 短边只有 2.4–3.8 px/模块，**贴乐观 floor（2–3）、零真实相机余量**。
     ⇒ 给**共享取景器**改设
     `setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 960), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())`；
     **不得**用 `setTargetResolution` / `setTargetAspectRatio`（自 camera 1.3.0 起**已废弃**，且与
     `setResolutionSelector` 互斥、混用 build 时抛 `IllegalArgumentException` —— 官方文档原文）。
     **判据先行**：解码前无从知道载荷类型，该设置**必然同时作用于 TOTP**（127 B / 41 模块在 480 下已有
     5.9–9.4 px/模块，提分辨率只会更好）；代价是每帧像素 ×4 的解码 CPU，由既有
     `STRATEGY_KEEP_ONLY_LATEST` + 单线程解码器吸收。⚠️ 设备硬件等级会限制实际可达尺寸，
     **真机须回读 `ImageProxy.width/height` 实际值**进批次文档（不得以「已设 1280×960」推定拿到）。
     这一改动**不属** AC⑤「TOTP 分支一字不改」的参数化范围 ⇒ 按 **AC⑤′** 登记为唯一例外。
  11. **导入凭据的计数器口径（2026-09-26 依 `PD-49` 裁决一定稿，取代本条前一版「只不写键」的暂定口径）**：
     第 3 片落库时，对导入所得凭据**写入**扩展键 `Passkey.SignCount`，取值 = `[2^20, 2^24)` 区间内
     `SecureRandom` 均匀随机一枚；此后由既有 `PasskeyData.nextSignCount` 单调 +1 ⇒
     **断言链与 `PasskeyAssertionActivity` 零改动**。
     ⚠️ **本条前几版的暂定口径（「裁决前不得改动计数行为」「导入路径只负责不写该键、禁止臆造起点值」）
     已作废**——作废理由与 (α)/(β) 的实测反证见 `PD-49` 裁决一。
     **随第 3 片强制的两个动作**：① 把「导入凭据的计数器不再具备克隆检测语义」登记进
     [`architecture/已知工程限界.md`](architecture/已知工程限界.md)（登记前不得声称本条闭环）；
     ② AC⑧③ 取真实 RP 对「计数大幅跳变」的实际读数，若触发风控或拒绝，按 `PD-49` 重开条件回退「不写键」。

- **验收标准**：
  - **AC①** 分流纯函数表驱动用例穷举 `Totp / Passkey / Unknown` × 前导空白 × 大小写 × `{`/`[` 起始 ×
    纯字母文本（不得被当成种子）⇒ **三条路径均可判定且无回退猜测**。
  - **AC②** `PasskeyCxfReader` 用例覆盖：三级 CXF 形态各正例（完整文档正例**必须**用
    `accounts[].items[].credentials[]` 构造，并加一条「凭据挂在 `collections[].items[]` 下」的**负例**，
    锁死不再照抄错误路径）、KeePassXC `.passkey` 正例、`fido2Extensions.hmacCredentials` 正例、
    拒绝路径 **≥8 条**（缺 `credentialId` / 缺 `userHandle` / 缺 `key` / 非法 Base64URL / DER 无已知 OID /
    **裸单对象 `type` 非 `passkey`**（口径 2″②：文档内混装的非 passkey 凭据是**跳过**，不属拒绝路径）/
    嵌套畸形 / 超 口径 9 上限），且断言实参一律取自被测返回值（过 `check_tautological_assertions.py`）。
    **2026-09-26 依核实 10 追加四组用例（前两组是「规范自己打自己」的必测面）**：
    ① **别名正例**——把规范附录 A 的 `hmacSecret{algorithm:"HS256",secret}` 原样作输入，
    断言 PRF 被**取出**（若解析器只认 CDDL 拼写，该例会红并暴露 `PD-48` 裁决二落空）；
    ② **未知 algorithm 降级例**——`algorithm:"hmac-sm3"` 之类 ⇒ **凭据仍入库、扩展项被丢且出现在确认清单**
    （锁住「ignore this entry」不等于 ignore this credential）；
    ③ **混合类型文档跳过例**——14 类凭据混装、passkey 排第 k 位，断言取到第 k 把且计数如实；
    ④ **版本门**——`version.major=2` 拒、`version.minor=9` 放行（§3.1.1 只增不改）。
    ⚠️ 夹具纪律（**本轮据核实 10 改写**）：**规范附录 A 的示例 JSON 本身即权威夹具**，
    其 `key` 字段已实测为合法 PKCS#8 DER 138 B（ecPublicKey + prime256v1）⇒ 直接取用，
    **不得**再按臆想字段自造「规范示例」；KeePassXC `.passkey` 正例**另须取真实产物**
    （本机 `keepassxc-cli` 导出或仓库语料）作**互操作**夹具，两者不可互相顶替（规则 8）。
    ⚠️ 第 2 片与「未决 2」的边界：PRF 落库口径**已定 (`PD-48` 裁决二)**，故本片不仅要**检出载荷含
    `fido2Extensions.hmacCredentials` 及其双值**（供 AC⑪② 的丢弃清单与后续扩展键落库复用），
    双值本身也须随本片一并解析出来（供第 3 片写 `Passkey.PrfNoUv` 使用）。
  - **AC③ 私钥不经 `String`**：解析与落库全链只出现 `ByteArray`/`CharArray`；加一条断言核验
    「私钥字节在回调返回后已被清零」（仿 §341 相册整改的擦除断言口径），并核验取消路径同样已清零。
  - **AC④ 无原始载荷残留 + 保护位逐键断言**：① `FakeVaultRepository` 观测点断言落库条目仅含
    `KPEX_PASSKEY_*` 字段，**不含**整段 JSON、不含备注/附件任何形式的载荷副本；
    ② 逐键断言保护位为「明文四键 `_RELYING_PARTY` / `_USERNAME` / `_FLAG_BE` / `_FLAG_BS`；
    受保护四键 `_USER_HANDLE` / `_CREDENTIAL_ID` / `_PRIVATE_KEY_PEM` / `_PRF`」。
    ⚠️ 该断言**前提是观测点真的携带保护位**：`FakeVaultRepository` 目前只记 `lastSavedTotpByEntry`
    （`:49`，且在 `:215` 转成 `String`），须新增 `lastSavedPasskeyByEntry` 并**保留 `isProtected`**，
    否则「逐键受保护」断言会因观测点丢位而空转通过（真假绿路径）。
    写通路本身已逐键尊重声明位（`VaultEntryWriteCoordinator.kt:188` `if (cf.isProtected) …`、
    `:205` `isProtected = cf.isProtected`），且该划分在 schema 路径已有锁
    （`core/src/test/.../PasskeyDataSchemaInteropTest.kt:41-68`）——**本 AC 只补导入通路这一层**。
  - **AC⑤ TOTP 零回归**：`ISSUE-P3-332` 顶栏扫码链与编辑页 TOTP 扫码的既有用例（含
    `VaultListScanEntryPointTest`）不改一字仍绿；`TotpScanDialog` 的取景/相册实现只允许**参数化**，
    不允许复制第二份。
  - **AC⑤′ 共享取景器改动的唯一例外登记（2026-09-26 依核实 11 新增，例外已由 `PD-49` 裁决四获准）**：
    AC⑤ 的「一字不改」**只**豁免
    口径 10 的 `ImageAnalysis` 分辨率一处改动。该例外的边界：① 改动**只**限 `ResolutionSelector` 构造，
    **不得**顺带改裁剪、朝向、hints 或线程模型；② 批次文档须写明改前/改后的**实测读数**——
    `ImageProxy.width/height` 真值（证明确实拿到更高帧）+ 同一张高密度 QR 改前改后的取景/相册成功与否对照；
    ③ 若真机因硬件等级拿不到 1280×960，**如实登记降级读数**并按未决 4 的结论调整 AC⑧ 的声称范围，
    **禁止**以「已设分辨率」推定「已拿到分辨率」。
  - **AC⑥ 守卫账目同步**：新增确认对话框后，`PopupSecureFlagInventoryTest` 重新盘点（计数与结论注释
    写明「2026-09-26 分流不新增菜单项 / 新增 1 个确认对话框」），静态文案判据继续成立
    （提示语无凭据类插值）；`PD-47` 的 `FLAG_SECURE` 接线在确认对话框与两个入口均落实；
    确认对话框的**归类已定稿**（`PD-48` 裁决三：跟随开关），本 AC 只核验接线，不再涉及归类裁决。
  - **AC⑦ 互操作对拍（`AGENTS.md` §5 / 规则 8）**：导入得到的凭据入库后跑
    `:database: PasskeyInteropProbeTest` → `python tools/passkey-interop/verify_interop.py`
    （判据数：2026-09-26 第 3 片起为 **161 条 / 4 条目**，原 114 条 / 3 条目——新增第四条
    「Passkey Imported ES256」以实证 `Passkey.PrfNoUv` 对其它管理器无害），
    ES256 必过；`Ed25519` / `RS256` 按白名单实际放行范围读数原样进批次文档。
  - **AC⑧ 真机端到端（只有真机能证伪）**：在 **AVD `Pixel_10` 或实验机**上（⚠️ 当前在连的小米 M332BF
    装着真实密码库与 `hyperpasskey` 模块，**禁跑 `connectedDebugAndroidTest`**）验证「导入的通行密钥
    能经 CM 通道对真实 RP 完成 GetAssertion」（RP 渠道按 `PD-32` / `PD-33`）；**并**用真机实拍的高密度
    QR 取样相册识码（§340 留痕「相册通路真机冒烟未做」，而 CXF JSON 远长于 otpauth URI、码密度更高，
    合成图断言不足以证明可用）。
    **2026-09-26 依核实 10 / 11 / 12 把本条做实（取样夹具与判据都要可复跑）**：
    ① 夹具**必须**是核实 11 实测的两档真实尺寸——**471 B 单凭据对象**与 **900 B 文档信封**
    （规范附录 A 内容生成，EC 等级 L 与 Q **各出一版**，因为导出方的纠错等级我方不可控，
    而它直接决定模块数 101 → 133）；
    ② 每组取样**须记录码在画面中的填充率**（本仓相机通路整帧解码、不裁剪，故填充率就是 px/模块的
    唯一变量，见核实 11 末行）；判据以核实 11 的**乐观 floor 2–3 px/模块**为参照——
    任何实测失败的组合都要把「短边像素 × 填充率 → px/模块」算式一并登记，不得只写「解不出」；
    ③ 断言链须回读**首次 GetAssertion 是否被 RP 判为克隆**（未决 6 的实测出口）；
    RP 侧计数不可见时，改以「同库自造凭据先注册一次、再从相册导入同一把」的对拍取样口径，
    并如实写明该对拍**不能**代表全网 RP 的严判行为。
  - **AC⑨ 全量与门禁**：`.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿
    （计数只用 `python tools/doc/count_test_results.py`）+ `python tools/doc/gate_readings.py` **7/7 PASS**
    且读数块**原样**贴入批次文档 §3（逐条 EXIT）+ `check_md_links.py` + `check_resolved_index_sync.py`。
  - **AC⑩ 文案纪律（`§292`）**：导入成功/失败文案一律写「FIDO2 软件密钥（库内加密存储）」口径，
    **禁用**「芯片 / 硬件」类表述。
  - **AC⑪ 规范符合性两条**：① 导入后的条目在编辑页**不可手改** `rpId` / `credentialId` / `userHandle` /
    私钥（§3.3.12.1「MUST NOT be user editable」），须有用例锁住（若现页本就只读，也要有用例证明之）；
    ② 载荷携带本库不承载的扩展（`credBlob` / `largeBlob` / `payments`）时，**须有可见提示逐项如实列出被丢弃
    扩展名**，禁止静默丢弃（`§292` 同源纪律）。注：`credWithoutUV` 按 `PD-48` 裁决二**全量存入本仓扩展键**，
    **不属**丢弃项，故提示里**不得**出现它。

- **未决与风险（开工时逐项收口）**：
  1. ~~必填边界~~ —— **已裁决（2026-09-26 用户，`PD-48` 裁决一）：取 (b)**。密码学五字段
     （`type` / `credentialId` / `rpId` / `userHandle` / `key`）缺失照旧**一律严拒**；
     仅 `username` / `userDisplayName` 缺失时**容错补齐 + 条目如实标注「来源未提供」**。
     口径 3② 已按本次裁决**同批改写为定稿口径**（不再存在"默认 (a)"的并存指令）。
  2. ~~PRF 双值落差~~ —— **已裁决（`PD-48` 裁决二：采 (c′) 全存）**；上轮两条错误论断的更正记录保留在下方
     「连带更正」（其价值在于：两处误判均因未回读 WebAuthn L3 §10.1.4 原文，留作方法论教训）。
     事实基座（三条均逐字核过）：
     - CXF §3.3.12.3：`Fido2HmacCredentials = { algorithm, credWithUV, credWithoutUV }` 三者必填；导入方
       「**MUST store and use these credential as-is during the HMAC operation. There MUST NOT be any
       additional derivation or domain separators**」；「exporting provider 只支持一个值时 MUST 生成补齐另一值」。
     - **WebAuthn L3 §10.1.4 原文（本轮补读，前两轮双方都漏）**：「The `hmac-secret` extension provides two PRFs
       per credential … **This extension only exposes a single PRF per credential and, when implementing on top
       of `hmac-secret`, that PRF MUST be the one used for when user verification is performed.
       This overrides the `UserVerificationRequirement` if necessary.**」
       ⇒ WebAuthn `prf` 层**无条件只用 withUV**；`credWithoutUV` 在 prf 语义下**零消费方**，
       只承载 CXF 的 MUST-store 保真义务。
     - 本库实现与规范同式，**"须核 computeValue 是否含额外派生"这一取证项就此关闭**：
       `crypto/.../passkey/PasskeyPrf.kt:70-83 computeValue` 把种子**原样**作 HMAC 密钥（无额外派生），
       `:86-96 clientSideProcess` = `SHA-256("WebAuthn PRF" ‖ 0x00 ‖ input)` 正是 §10.1.4 规定的
       **客户端 salt 构造**（注册与断言两侧同式），不属「additional domain separators」。
       `PasskeyAssertionPayload.kt:134-135` 的分支仍在，但那是"缺失即不返回 prf"的如实降级，**不是错值风险**。
     候选处置（**2026-09-26 用户裁决，`PD-48` 裁决二：采 (c′)**；决定性理由是**不可逆性**——
     导入是一次性捕获，第二枚种子这次不存事后永远补不回）：
     - **目标 (c′)**：`KPEX_PASSKEY_PRF` 继续承载 withUV（生态兼容，**断言链与 probe 现用字段零改动**），
       新增本仓扩展键存 `credWithoutUV`（命名如 `Passkey.PrfNoUv`；先例与机制见 `PD-09` 第 3 项本仓扩展键，
       `产品裁决登记.md:320` 的 `Passkey.Algorithm` 等），同批更新 `PasskeyInteropProbeTest` 的
       `KPEX_PASSKEY_*` 键集精确判据并重跑 keepassxc-cli / pykeepass 对拍。
       ⚠️ **(c′) 不含任何「按 UV 选种子」的断言改造**——那既非规范要求，也与 §10.1.4 的 override 语义相悖。
     - ~~**兜底 (a) + 强制披露**~~ —— **已否决，仅留档备查**（不再是待选）：只存 withUV 虽与 (c′) 的
       `prf` 功能等价，但会**永久放弃一个不可恢复的值**并须在 `PD-48` 记 deviation；
       用户裁「半天成本换 CXF 完全合规与免返工」。
       **`credWithoutUV` 因此不属"丢弃项"**——AC⑪② 的点名披露只适用于本库确不承载的扩展。
     - **(b)（整项丢弃）否决**：与 (a) 同样丢 withoutUV，却额外把 `prf` 全灭，无任何收益 ⇒ **被 (a) 严格支配**。
     **连带更正（删除上轮两句错误论断）**：① 「(a) 会算出错值、错值比缺失更糟」——不成立，恒用 withUV
     正是 §10.1.4 的 MUST；② 「(c) 的范围含断言链按 UV 选种子」——不成立，断言逻辑零改动；
     ③ 「宁取 (b) 不取 (a)」——收回，(b) 被 (a) 支配。
  3. ~~「聚焦通行密钥区块」需给 `Screen.EntryEdit` 加一个可选字符串参数~~ —— **已定（实现细节，不属取舍，
     故不入 `PD` 表）**：新增可空路由参数 `focusSection`（取值 `"passkey"`，为空即不定位），
     仅驱动编辑页滚动 / 焦点落位，**不得**承载任何凭据类值（核实 5d 的红线不变）。
     导航能力本身已核实为现成（见 5d），此项零风险。
  4. **高密度 QR 的实际解码成功率**（2026-09-26 依核实 11 收敛为**实测项**；**第 2.5 片已实测，结论见下**）：
     合成图曾给出**乐观 floor 2–3 px/模块**，据此判定「默认 640×480 分析流 + 文档信封载荷」
     **余量为零**（⇒ 口径 10 提分辨率，已落地并经真机回读确认交付 1280×960）。
     ⚠️ **本轮实测把结论收窄**：两种合成退化模型（整数最近邻+3×3均值±20噪声 / 亚像素覆盖±20噪声）
     **互相矛盾且各自非单调**，后者在 1.98 px/模块 仍解得出 ⇒ **合成模型对相机面没有鉴别力**，
     「相机也能扫」**不得**据任何合成表声称，必须由 AC⑧③ 实拍判定。
     相册面：**解码层面已证**（生产解码器 `decodeQrFromPixels` 在 2400 短边、9.92~24.94 px/模块
     的 12 个档位全部可解）；框架通路（`ContentResolver` + `BitmapFactory`）已由 §341 用户真机复验，
     但那是普通 otpauth 码 ⇒ **「同一张高密度 CXF 码经相册导入」仍待 AC⑧② 的实拍取样**。
     实拍夹具已生成并登记于第 2.5 片留痕（`build/qr-probe/`，4 张码含模块数与 sha256）。
  5. ~~`key` 成员"PKCS#8 DER 的 Base64URL"未逐字取到~~ —— **已关闭**（2026-09-26 取到 §3.3.12 原文：
     「The private key associated to this passkey instance. The value MUST be **PKCS#8 ASN.1 DER** formatted
     byte string which is then Base64url encoded.」，与 `PD-08` 第 1 项一致，已同步写入 PD-08 第 4 项补记）。
  6. ~~导入凭据的 signCount 起点~~ —— **已裁决（2026-09-26 代理裁决，`PD-49` 裁决一：采 (γ′) 随机高位起点）**；
     事实基座见核实 12，理由与残余风险全文见 `PD-49`。三候选留档：
     - **(α) 恒发 0**：`stored>0` 时每次断言都满足「either is non-zero 且 new ≤ stored」⇒ **永久**落嫌疑区、
       **永不恢复**；反证已到手（open-passkey 的 RP 侧此时**直接判失败**，`authentication.ts:246`）。
     - **(β) 不写键、从 1 递增**：同落 `≤` 区间；若 RP 存的既有计数为 N，需 N 次成功断言才爬出——
       **N 可达数千，实践上等价于永不恢复**。
     - **(γ′) 随机高位起点**：一步跨过不可见的既有计数，此后单调性由我方真实维护 ⇒ **采纳**。
     ⚠️ **代理前一轮曾推荐 (β) 并称其「会自行爬出」，本轮收回**：收回原因是上一条把 N 的量级估错了
     （重度使用的硬件密钥计数以千计），"能恢复"在实践中等同不成立——留作方法论教训：**候选比较必须带量级**。
  7. ~~PRF 双值的长度与单值遗留形~~ —— **已裁决（`PD-49` 裁决二）**：① 长度**不判、不裁、不拒收**
     （规范是「SHOULD be 32 bytes」），非 32 字节**原样保真存**且**不进**确认清单（用户无法行动，写进 UI 只是噪声）；
     ② 别名形 `hmacSecret{secret}` 只带一枚 ⇒ 存 `KPEX_PASSKEY_PRF`、`Passkey.PrfNoUv` **留空**，
     **禁止**复制出第二枚假种子。AC② 的别名为例须同时锁住「只有一枚时不生成第二枚」。
  8. ~~文档内多把通行密钥的处置~~ —— **已裁决（`PD-49` 裁决三）**：**导第一把 + 确认对话框点名
     「文档内另有 N 把未导入」**，不做逐把选择列表（列表要把多枚私钥同时驻留，成倍扩大 §3 铁律的擦除义务面）；
     重开条件是 AC⑧ 实拍显示主流导出器默认一次给多把。

- **粗估**：**6–8 人日**（2026-09-26 由 5–7 上修）。构成：手写字节扫描器（含转义与三条嵌套定位）为最大项；
  **新增** `PD-48` 裁决二带来的本仓扩展键（`Passkey.PrfNoUv`）+ `PasskeyInteropProbeTest` 键集判据更新
  + 对拍重跑；**本轮再加**三项：口径 2′/2″ 的别名与混合类型处理（含 4 组新用例）、口径 10 的分辨率改动
  及其真机改前/改后对照（AC⑤′）、口径 9 的载荷上限常量与文案；**减少** 相册解码器（零新增，见核实 5）
  与路由搭建（现成，见 5d）两处。

- **关联**：`PD-08`（载荷与入口裁决。本条目**同时勘误其两处规范引用错误**：凭据路径应为
  `accounts[].items[].credentials[]`、取景组件已由 `SecureCaptureActivity` 更为 `TotpScanDialog`；
  并把「入口」按 Q1/Q2 改写为「顶栏分流为主 + 编辑页保留附加入口」）/ `PD-09`（`KPEX_PASSKEY_*` schema，
  按 `PD-48` 裁决二新增本仓扩展键（命名如 `Passkey.PrfNoUv`）**须同批回写并重跑对拍**）/
  **`PD-48`**（2026-09-26 用户裁决，承载本条目三项裁决：必填边界取 (b)、PRF 双值取 (c′)、
  确认对话框 `FLAG_SECURE` 跟随开关）/
  `PD-34`（类型名有界性机检）/ `PD-47`（扫码对话框 `FLAG_SECURE`）/ `PD-10`（特权浏览器白名单，
  导入路径**不**继承其 origin 保证，须按实际 rpId 归属如实呈现）/ `P2-105`（禁经框架缓存敏感值）/
  `ISSUE-P3-214`（`PasskeyKeyText` 四级嗅探）/ `ISSUE-P3-319`（`SecureCaptureActivity` 退役）/
  `ISSUE-P3-332`（顶栏扫码入口）/ §340 §341（相册识码通路与其真机冒烟留痕）/
  **跨设备扫码注册（hybrid / caBLE）不在本条目范围**——它需要自建隧道中继与 CTAP2 传输栈，
  结论与判据另见后续裁决条目。
  **2026-09-26 同批新增依据**：[`references/扫码导入通行密钥的参考项目对照.md`](references/扫码导入通行密钥的参考项目对照.md)
  （三同类项目只读取证：零 CXF 实现、零 hybrid 实现、`transports` 两种相反先例、`IncompatibleItem`
  确认在先模式）/ `ISSUE-P3-338`（本仓注册响应虚报 `hybrid`：**已于 §342 撤回为单值 `internal`**，
  hybrid 落地时按该批 KDoc 登记的恢复前提（CTAP2.2 §11.5 全链）复核）/ CameraX 官方文档 `ImageAnalysis.Builder#setResolutionSelector`
  （核实 11 的 640×480 默认值出处，口径 10 与 AC⑤′ 的依据）/
  **`PD-49`**（2026-09-26 代理裁决，承载本条目「未决 6 / 7 / 8」与 AC⑤′ 例外的定稿：
  计数采随机高位起点、PRF 长度不校验、多把凭据导第一把并点名、共享取景器分辨率例外获准）。

- **开工顺序（2026-09-26 代理裁决，理由附后；2026-09-26 §342 同步进度）**：
  **① `ISSUE-P3-338`（0.5 人日，独立批次先闭环）—— 已完成（§342）**
  它只是撤回一个无实现支撑的元数据值，
  却能把「参考实现都这么做」这类伪依据从 KDoc 里清掉；留着它就是缺陷再生成器。
  **② 第 1 片 `ScanPayloadClassifier` —— 已完成（留痕见下「分片进度留痕」）**
  纯函数、零外部依赖，先把分流边界钉住
  （338 的文案影响已随 §342 消除：`Unknown` 分支复用现键 `vault_scan_invalid_qr`，无新增对外声明）。
  **③ 第 2 片 `PasskeyCxfReader` —— 已完成（留痕见下）**（含 AC② 的别名 / 未知 algorithm /
  混合类型 / 版本门四组新用例，夹具直取规范附录 A）—— 解析器是全条目最大单项，先于任何 UI 与落库改动完成。
  **④ 真机分辨率探针（半日，不入库）—— 已完成（留痕见下「第 2.5 片」；口径 10 已同批落地）**
  —— 第 3 片之前做的原因成立：AC⑤′ 的例外虽已获准，
  但「提分辨率能否真的解出 **749 B 单凭据文档信封 / 472 B 裸对象**码」（原记 900 B；按附录 A 全结构
  套信封则 1 697 B，两档实测校正见第 2 / 第 2.5 片留痕）
  只有真机能证；结论决定 AC⑧ 的声称范围是
  「相机 + 相册」还是「相册为主」，越早拿到越少返工。
  **⑤ 第 3 片 落库接线 —— 已完成（留痕见下：含 `PD-49` 裁决一的随机高位起点 + 限界表 §34 登记
  + `Passkey.PrfNoUv` + 对拍重跑 161 条全绿）**
  —— 本片的计数写入与限界表登记已同批入库。
  **⑥ 第 4 片 两个入口接线 + 确认对话框（含 4′ 的不兼容清单与「另有 N 把」点名）+ 守卫重盘点
  —— 已完成（顶栏见「第 4 片（上）」、编辑页 Q1 与 AC⑪① 只读锁见「第 4 片（下）」）**
  **⑦ 第 5 / 6 片 真机端到端 + 实拍取样 + 全量 test + 门禁 7/7 + 批次归档
  —— 部分完成：AC⑧ 的**相册通路**端到端已在 AVD `Pixel_10` 取样完毕（留痕「第 5 片（前半）」，
  含官方实现读数）；**相机实拍**与**对真实 RP 的 GetAssertion** 两项仍未做（前者需真机 + 实物码，
  后者需可导出 CXF 的注册方 ⇒ 待用户定口径），批次归档随其完成**

- **分片进度留痕（2026-09-26 起逐片追加；条目闭环时随正文一并剪切入批次）**：
  - **第 1 片 `ScanPayloadClassifier`（口径 1 / AC①）—— 已完成**。新增
    `app/src/main/java/com/keepasskey/app/passkey/ScanPayloadClassifier.kt`（`object` +
    `enum class ScanPayloadKind { Totp, Passkey, Unknown }`，零 Android 依赖）与同目录
    `ScanPayloadClassifierTest`（**4 例 / 表驱动 23 行**：三条路径 × 前导空白（空格、`\t\r\n`）×
    大小写（`OTPAUTH://`、`OtpAuth://`）× `{`/`[` 起始 × 纯字母文本与裸 Base32 种子 ×
    方案名缺冒号 / 截断 / 出现在中部 × 空载荷与纯空白；另锁「判定只依赖首部形态、内容含另一种形态
    标记不改归属」「**只读不改入参**（擦除义务归调用链，分流函数写入即破坏上行种子）」
    「畸形与边界载荷（零长数组、`\u0000`）不抛异常」三条契约）。
    ⚠️ **本片不接线**：`VaultListViewModel.onQrCodeDecoded` 与 `addEntryFromScannedOtpauth` 一行未动。
    理由＝CXF 解析器（第 2 片）尚不存在时，把 `Passkey` 分支接到任何现有文案都构成不实陈述
    （合法 CXF 载荷会被报成「二维码无效」），而新增文案又会先于 AC⑥ 的守卫盘点落地
    ⇒ 接线随**第 4 片（两个入口 + 确认对话框）**同批完成，本片只交付「分流边界钉死 + 无回退猜测」的纯函数。
    ⚠️ **实现取向偏离口径 1 原文两处，均登记理由**：① `classify` 取 `CharArray` 而非 `ByteArray`
    （判据只用 ASCII 前缀，字符级与字节级**逐值等价**（非 ASCII 的 UTF-8 首字节 ≥ `0xC2` 不可能命中前缀），
    而字符级不必为注定被拒的任意二维码额外物化一份 UTF-8 缓冲；字节通道在选中 `Passkey` 分支后才开启，
    与 TOTP 现链同口径）；② 分流前缀取 `otpauth:`（**弱于** TOTP 分支自身的 `otpauth://`），
    畸形形态仍由 TOTP 分支如实拒绝 ⇒ 恰满足「TOTP 分支一字不改」，`Totp` 只表示「归 TOTP 链裁决」。
    验证读数：定向类 `BUILD SUCCESSFUL`；全量 `test --rerun-tasks --max-workers=1`
    `BUILD SUCCESSFUL in 3m 34s`、`114 actionable tasks: 114 executed`、聚合
    `xml=403 tests=2677 failures=0 errors=0 skipped=13`（§342 基线 2673 + 本片新例 4）；
    `gate_readings.py` **7/7 PASS**（`tier1=0` / `tier2=34 budget=37` 持平、
    `check_tautological_assertions` 命中 0 处 / 扫描 **460** 个测试文件（+1 新文件）、
    `check_bounded_type_names` `allowed=12` 不受新类型名影响——`*Classifier` 不属 `PD-34` 受限后缀）。
  - **第 2 片 `PasskeyCxfReader`（口径 2 / 2′ / 2″ / 3，AC②③）—— 已完成**（2026-09-26）。
    新增三个生产文件：`app/src/main/java/com/keepasskey/app/passkey/ByteJsonScanner.kt`（359 行，
    字节通道 JSON 扫描器 + `ByteJson` 节点树：值一律 `ByteArray`，仅键名转 `String`；转义含
    `\uXXXX` 与代理对；深度硬界 `MAX_DEPTH=32`；一切畸形输入**返回 null 不抛异常**）、
    `PasskeyCxfReader.kt`（415 行，四形态定位 + 白名单取值 + 扩展账目）、
    `PasskeyCxfOutcome.kt`（167 行，`Parsed/Rejected` + 九个静态错误码 + 形态 / 丢弃项 / 补齐项枚举 +
    `ImportedPasskey` + `Base64Codec`）。
    ⚠️ **行数分档闸门在开发中真实拦下一次**：初版把读取器与结果类型写在同一文件＝**555 行 ⇒ `tier1(>500)=1` 硬红**；
    按「定位与提取」/「结果与判据」拆开后转绿。当前读数 `tier1=0`、`tier2=35`（原 34，本片 +1：
    `PasskeyCxfReader.kt` 415 行），`budget=37` 未越——**如实登记占用的棘轮额度**，后续片不得再新增 tier2 文件。
    用例：`PasskeyCxfReaderTest` **20 例** + `ByteJsonScannerTest` **9 例**（合计 29 例）。AC② 逐项对应：
    三级形态各正例（裸对象 / `$Credential` 数组 / **`accounts[].items[].credentials[]`** 文档）、
    「凭据挂在 `collections[].items[]` 的 `LinkedItem` 引用里 ⇒ 取不到」**负例**（锁死不照抄 `PD-08` 原错路径）、
    KeePassXC `.passkey` 正例、`hmacCredentials` 双值正例、
    **别名正例**（附录 A 的 `hmacSecret{algorithm:"HS256",secret}` 原样作输入 ⇒ PRF 被取出）、
    **未知 algorithm 降级例**（`hmac-sm3` ⇒ 凭据仍入库、扩展项被丢且进丢弃清单）、
    **混合类型文档跳过例**（7 类混装、passkey 居第 5 位 ⇒ 取到它、`skippedCredentialCount=6`）、
    **版本门**（`major=2` 拒、`minor=9` 放行）、拒绝路径 **11 条**（缺 `credentialId`/`userHandle`/`key`/`rpId`、
    `rpId` 全空白、非法 Base64、`len%4==1` 形态、解出零字节、DER 无已知 OID、裸单对象 `type` 非 passkey、
    无词表命中）＋ 7 条层级 / 非法 JSON（截断、裸词、串内裸控制字符、超深、`accounts` 非数组、`version` 非对象、标量根）、
    上限边界（**4096 放行 / 4097 即拒**）、附录 A 全示例整块直读 ⇒ `PayloadTooLarge`、
    展示字段缺失补齐且**不回填 `userHandle`**、多把 passkey ⇒ 导第一把并计数、
    `credBlob`/`largeBlob`/`payments(true)` 逐项点名（`payments(false)` 不点名）、
    PRF 长度不判不裁不拒收、`read` 不改动调用方载荷数组。
    **夹具按条目纪律直取规范原文**：`app/src/test/resources/passkey-import/cxf-appendix-a-example.json`
    （附录 A 整块原样收录，**28 854 B**、LF、SHA-256 与全部读数见同目录 `FIXTURE.md`）；
    用例对它只做「整块直读 / 抽出 passkey 作内核 / 改名搬层」三种变形，**未**按臆想字段自造示例。
    ⚠️ **本轮实测校正条目读数三处**（判据均不变，只如实改数）：① 附录 A 混装 **15 条凭据 / 15 种 `type`**
    （核实 10③ 原记「14 种」）；② 裸 passkey 对象紧凑 JSON 实测 **472 B**（核实 11 原记 471 B）；
    ③ 按 §3.1/§3.2 **完整必填成员**构造的文档信封（仍只 1 把凭据）实测 **1697 B**（核实 11 原记 900 B，
    差因＝原文按最小骨架计数）——三者都远低于 `4096`，上限判据与「文档级多凭据装不进单张 QR」的结论不受影响。
    ⚠️ **被新用例揭出的实现缺陷 1 处（当场修复）**：KeePassXC 形的 rpId 取值误用 CXF 的 `rpId` 键名
    ⇒ 该形态恒判 `MissingCeremonyField`（`PD-08` 第 1 项的兼容目标整条落空）；补 `relyingParty` 常量后转绿。
    这条正是「先写解析器再写落库」的收益：**若直接进第 3 片，缺陷会藏到端到端才现形**。
    ⚠️ **AC② 待补项（不得推定已满足）**：KeePassXC `.passkey` 正例目前用的是**同形自造夹具**——
    本机 `keepassxc-cli` 2.7.12 **无 passkey 导入 / 导出子命令**（该功能只在 GUI 侧
    `gui/passkeys/PasskeyImporter` / `PasskeyExporter`），故无脚本化取物途径。该夹具证明的是
    字段词表与 Base64 归一口径（取自 `PasskeyImporter.cpp:73` 的 6 必需字段读数），
    **不构成** `AGENTS.md` 规则 8 要求的官方实现端到端对拍；补法已登记在 `FIXTURE.md`
    （用户在 KeePassXC GUI 内导出 `*.passkey` 放入该目录 ⇒ 同批追加逐字节用例）。
    AC③ 在本片这一层的可证形态＝**源码级静态守卫**（`stripCommentsOnly` 后扫「私钥节点紧邻 `asUtf8String()`」，
    含口径反校：坏样本必须命中、`rpId` 的正常文本转换不得误报）；
    「私钥字节在回调返回后已被清零」的**行为级**断言依赖确认对话框与落库回调，随第 4 片落地。
    **本片同样不接线**（与第 1 片同因：无消费方时接线只会引入不实文案）。
    验证读数：定向三类 `BUILD SUCCESSFUL`（20 / 9 / 4 例逐类 failures=0）；
    全量 `test --rerun-tasks --max-workers=1` `BUILD SUCCESSFUL in 3m 35s`、`114 actionable tasks: 114 executed`、
    聚合 `xml=405 tests=2706 failures=0 errors=0 skipped=13`（上一片 2677 + 本片新例 29，逐例可对）；
    `gate_readings.py` **7/7 PASS**（`long_functions=0`、`check_md_links=0 断链`、
    `check_tautological_assertions` 命中 0 处 / 扫描 **462** 个测试文件（+2 新文件，
    全部断言实参取自被测 `read()` / `scan()` 返回值）、`check_bounded_type_names` `allowed=12` 不受影响
    （`*Scanner` / `*Reader` 不属 `PD-34` 受限后缀））。

  - **第 2.5 片 真机分辨率实测 + 口径 10 落地（AC⑤′）—— 已完成**（2026-09-26，设备：Redmi 4X
    `1c859bcc7d24`，LineageOS / API 37，用户 2026-09-24 明示的实验机 ⇒ §263 前置闸门豁免适用）。
    **真机读数（`ImageProxy` 原样回读，非推定）**：
    ```
    A_默认配置(不设 setResolutionSelector，官方 bound 640×480) ImageProxy=640x480  format=35 planes=3 rowStride0=640
    B_口径10配置(bound 1280×960)                              ImageProxy=1280x960 format=35 planes=3 rowStride0=1280
    C_极限请求(bound 4000×3000)                               ImageProxy=4000x3000 format=35 planes=3 rowStride0=4032
    device=Redmi 4X api=37   （format 35 = ImageFormat.YUV_420_888）
    ```
    ⇒ **硬件不是瓶颈**：该低端机（1.8 GB RAM）确实交付 1280×960，且往上请求不被降级；
    AC⑤′③ 的「如实登记降级读数」在本设备上无降级可登记。
    **代码落地（口径 10，AC⑤ 的唯一例外）**：`TotpScanDialog.kt` 抽出 `buildQrAnalysis()`，
    以 `ResolutionSelector + ResolutionStrategy(Size(1280, 960), CLOSEST_HIGHER_THEN_LOWER)`
    替代「不设分辨率」；**未**用已废弃的 `setTargetResolution` / `setTargetAspectRatio`
    （与 `setResolutionSelector` 互斥、混用 `build()` 即抛 `IllegalArgumentException`）。
    守卫 `TotpScanCameraResolutionGuardTest` **3 例**：①选择器在场且 bound 走命名常量
    ②废弃 API 不得出现 ③**口径反校**（无选择器的坏样本必须不被认出、含 `setTargetResolution`
    的坏样本必须被抓、正常形态不误报）。
    **密度实测 `QrDecodeDensityTest` 3 例**（喂**生产解码器** `decodeQrFromPixels`）：
    模块数 裸对象 472 B → `EC=L 77 / EC=Q 97`；文档信封 749 B → `EC=L 93 / EC=Q 121`
    （信封尺寸与解析器用例的 749 B 同一口径；附录 A 原样混装 15 条时紧凑 11 863 B、整块 28 854 B）；
    **相册短边 2400 的 12 个档位全部可解**（9.92~24.94 px/模块）⇒「相册导入通行密钥」在解码层面成立。
    ⚠️ **本轮最重要的发现是负面的：合成模型对相机面没有鉴别力。** 先后实现两种退化模型：
    ①「整数 px/模块最近邻 + 3×3 均值 + ±20 噪声」——默认 640×480 全档**不可解**，
    但同一模型在 2400 短边出现**非单调**（0.5 可解 / 0.65 不可解 / 0.8 可解），说明它把
    「码在帧中的整数对齐」当成了变量，不配作定量依据；
    ②「亚像素 3×3 覆盖积分 + ±20 噪声」——从 **1.98 px/模块**（文档信封 EC=Q、480 短边、填充 0.5）
    起几乎全部可解，同样存在个别非单调点。
    两模型互相矛盾 ⇒ **禁止**以合成表声称「相机也能扫」；相机面的结论只能来自 AC⑧③ 实拍。
    该事实被 `QrDecodeDensityTest` 的「二 合成帧在默认短边最坏档位下仍可解 故不得据其声称相机可用」
    显式锁住（断言方向反直觉，作用是防止后来者拿本表当相机可用性证据）。
    ⇒ **声称范围定稿**：相册导入 = 可声称（解码层面，余量 5~25 倍）；相机扫码 = **待实拍**，
    第 6 片端到端前不得写「相机也能扫」。
    **实拍夹具已生成**（`build/qr-probe/`，不入库；配方＝附录 A 载荷紧凑 JSON +
    `qrcode` 库 `box_size=8, border=4`，EC=L/Q 各一版）：
    `bare-472B-ECL.png` 77 模块 680×680 sha256 前缀 `87f483ff49ace46c`；
    `bare-472B-ECQ.png` 97 模块 840×840 `b1b95b75d76f6354`；
    `doc-749B-ECL.png` 93 模块 808×808 `7fe1b83f4f9d4d9e`；
    `doc-749B-ECQ.png` 121 模块 1032×1032 `c5e68f45f22fe491`（`MANIFEST.txt` 同目录，
    含「960/2400 短边 @0.65 填充 → px/模块」换算列）。
    **开发期坑（如实留痕，均已写进设备用例注释）**：①`LifecycleRegistry` 普通构造报
    `Method setCurrentState must be called on the main thread` ⇒ `createUnsafe`；
    ②`ProcessCameraProvider.bind/unbind` 报 `Not in application's main thread` ⇒ `runOnMainSync` 提交绑定、
    在 Instrumentation 线程等帧；③`connectedDebugAndroidTest` 跑完会**卸载宿主包**（随后 `pm grant`
    报 `package not found`），且 AGP 未自动授予 CAMERA ⇒ 相机报 `ERROR_SECURITY_EXCEPTION`、
    一帧都拿不到 ⇒ 设备探针改走 `adb install -g` + `am instrument -w -e class ...`。
    ⚠️ 闸门拦下一次：给 `TotpScanDialog` 直接内联 20 行选择器后 `functions_ge_100=1`
    （`TotpCameraPreview` 104 行）⇒ 抽出 `buildQrAnalysis()` 后回到 0。
    验证读数：新增设备用例 `am instrument` `OK (1 test)`（读数即上表）；
    全量 `test --rerun-tasks --max-workers=1` `BUILD SUCCESSFUL in 3m 24s`、`114/114 executed`、
    `xml=407 tests=2712 failures=0 errors=0 skipped=13`（上片 2706 + 本片 6 例：密度 3 + 守卫 3）；
    `gate_readings.py` **7/7 PASS**（`tier1=0` / `tier2=35 budget=37`、`long_functions=0`、
    `check_tautological_assertions` 命中 0 / 扫描 **465** 文件（+3））。
  - **第 3 片 落库接线（口径 5 / 6 / 11，`PD-48` 裁决二、`PD-49` 裁决一，AC④⑦）—— 已完成**（2026-09-26）。
    **schema 侧**：`PasskeyData` 新增受保护扩展键 `Passkey.PrfNoUv`（常量 `FIELD_PRF_NO_UV` +
    数据类字段 `prfNoUvSecret` + `toCustomFields` 写出 + `fromCustomFields` 读回 +
    **登记进 `SCHEMA_FIELD_KEYS`**——漏这一步则三处「原地替换」都不剥离它、旧值残留）。
    ⚠️ 该文件原已 489 行，直接追加注释把 `PasskeyData.kt` 推过 500 ⇒ `tier1=1` 硬红；
    按 §332 先例**就地压缩自身新增**（长 KDoc 改为指向 `PD-48` / 限界表 §34 的短注）后
    落在 **500 行整**、`tier1=0`、`tier2=35` 不变。
    **构造侧**：新增 `app/passkey/PasskeyImportFactory.kt`——`ImportedPasskey → PasskeyData`，
    三处口径各有出处：① PRF 种子**重新编码为标准 Base64** 才驻留（消费方 `PasskeyPrf.decodeSecret`
    用 `Base64.getDecoder()`，生成侧同器；若原样存载荷里的 Base64URL，含 `+`/`/` 的种子会在断言时
    抛异常 ⇒ 完好凭据的 prf 静默失效）；② 计数器 = `[2^20, 2^24)` 内 `SecureRandom` 随机高位起点
    （`PD-49` 裁决一 (γ′)，断言链零改动）；③ 第二枚种子写 `Passkey.PrfNoUv`、**只存不用**。
    `publicKeyBase64` 留空（外部材料口径），BE/BS 取 true/true 并在 KDoc 写明理由（载荷能到本仓即已
    过一次导出；这两位会经 AuthenticatorData 交给 RP，属我方裁量故不留白）。
    **擦除义务**：工厂**不**清零入参；新增 `ImportedPasskey.wipeSecrets()` 作为调用链的统一擦除动作
    （取消路径不经工厂，故不能靠工厂擦）。
    **写库侧**：`saveNewPasskeyEntry` 加 `parentGroupId` 参数（null＝根组的既定语义不变，
    顶栏需要落当前分组必须显式传）；新增 `replacePasskeyOnEntry(entryId, data)`——
    ⚠️ **既有的 `saveOrReplacePasskeyEntry` 不满足 Q1**：它按 `rpId + userName` 检索条目，
    库里存在同站点同用户名的另一条时会写到**那条**上而非正在编辑的条目；
    用例「四」把这一分岔做成可判定对照（两条同站点条目 + 断言命中的是第一条）。
    新入口走 `DatabaseSession.updateEntryById`（会话 Mutex 内单次原子替换，与计数器补丁同源），
    非 passkey 字段按引用复用，未命中返回 null 且零写入。`FakeVaultRepository` 新增观测点
    `lastSavedPasskeyByEntry: Map<String, List<KdbxCustomField>>`——**必须存字段对象而非 String 映射**，
    否则 AC④② 的逐键保护位断言会因观测点丢位而假绿（条目原文点名的真假绿路径）。
    **互操作面（AC⑦）**：`PasskeyInteropProbeTest` 增设**第四条对拍条目**「Passkey Imported ES256」
    （ES256 + 两枚种子＝导入形状），并在 `assertKpexSchemaShape` 加 `expectPrfNoUv` 判据
    ——自产三条断言**不得写出**该键、第四条断言**必须在场且受保护**且为 32 字节；
    `verify_interop.py` 同步加 `K_PRF_NO_UV` 进 `PROTECTED_KEYS` / `KNOWN_KEYS`
    （**`KNOWN_KEYS` 必加**：否则多行值解析会把 `Passkey.PrfNoUv: …` 当成上一个键的续行吞掉）
    并加 6b 段判据。重跑读数：`pykeepass 4.2.0` 解锁 4 条目、`keepassxc-cli 2.7.12` 四条交叉核对一致、
    **✓ 对拍通过：161 条判据全部成立**（原 114 条 / 3 条目），产物
    `keepasskey-passkey-probe.kdbx` SHA-256 `f6b2752a78a4e80d17dd80bbf8a3a00aa51a9b23ee9e015ebbbbefc99df07355`
    ⇒ 「扩展键对其它管理器是无关属性」这句 PD-09 的立身之本第一次拿到**带新键的**官方实现实证。
    **文档同批**：`已知工程限界.md` 新立 **§34**（① 导入凭据计数器不再具备克隆检测语义；
    ② 非 32 字节 PRF 种子「存得下但用不了」——`decodeSecret` 硬判 32 字节，是本轮实现时才发现的
    既有约束，`PD-49` 裁决二只说了「不判不裁不拒收」，未预见消费方会 fail-closed 拒绝 ⇒ 二者合并登记）；
    `PD-09` 第 3 项补记新扩展键与判据数变化；AC⑦ 文本的「114 判据」就地改为 161/4 条目并留原值。
    ⚠️ **本片仍未接线**：`PasskeyImportFactory` / `replacePasskeyOnEntry` 的生产调用点随第 4 片
    （两个入口 + 确认对话框）出现；AC③ 的「回调返回后已清零」行为级断言亦属第 4 片。
    验证读数：新增 `PasskeyImportFactoryTest` **8 例**（含真实消费路径——本地独立算一遍
    `HMAC-SHA-256(seed, SHA-256("WebAuthn PRF"‖0x00‖input))` 比对 `PasskeyPrf.computeValue` 输出；
    随机区间 200 取样、非 32 字节种子「存得下 + 计算必拒」双向锁）与
    `PasskeyImportWritePathTest` **4 例**；`:database:` probe 用例绿 + 脚本 161 条全绿。
  - **第 4 片（上）顶栏分流接线 + 导入前确认对话框（Q2、口径 1 / 4 / 4′，AC①③⑤⑥⑩⑪）—— 已完成**
    （2026-09-26）。**分流落地**：`VaultListViewModel.onQrCodeDecoded` 改为
    `ScanPayloadClassifier.classify` 三向分派（`Totp` → 原 `addEntryFromScannedOtpauth` **一字未改**；
    `Passkey` → `beginPasskeyImportFromScan`；`Unknown` → `rejectUnknownScannedQr`，
    复用现键 `vault_scan_invalid_qr`、擦除后提示、不回显内容）——**无回退式猜测路径**。
    **草案承载**：新增 `PasskeyImportDraft(credential, notes)`（`PasskeyCxfOutcome.kt`），
    只活在 ViewModel 内存里（⚠️ 路由参数 / `SavedStateHandle` 一律禁用，`P2-105` 同源）；
    确认 → `PasskeyImportFactory` → `repository.saveNewPasskeyEntry(..., parentGroupId=当前分组)`
    → 交出一条性的 `openEntryEditId`（**只带 id**）→ `VaultListScreen` 的 `LaunchedEffect` 消费并调
    `onNavigateToEntryEdit`（新增回调，nav graph 走 `Screen.EntryEdit.createRoute(id)` 现成路）；
    取消 → `dismissPasskeyImport()` 擦除、不落库、不导航。**两条路径的擦除都有断言**（AC③）。
    **确认对话框** `PasskeyImportConfirmDialog.kt`：正文抽成纯函数 `passkeyImportSummary(draft, text)`
    以便宿主逐条机检；`FLAG_SECURE` **跟随开关**（`PD-48` 裁决三），
    `SecureDialogWindowEffect(flagSecure = …)` 始终施加反 overlay / 点击过滤两层。
    ⚠️ **两行语义分开**（本轮实现时收紧，与 AC⑪② 的排除条款对齐）：
    「本库不能保存的扩展」行只列 `credBlob`/`largeBlob`/`payments(true)`/算法值无法识别的 PRF 项；
    `credWithoutUV` 因**全量存入** `Passkey.PrfNoUv` 不得进该行，而「规范形缺一枚种子」
    属**来源缺陷** ⇒ 归「来源未提供 / 不完整」行。用例「二」双向锁住（缺种子出现在来源行、
    且**不得**出现在不会导入行）。
    ⚠️ 文案纪律的**资源面**守卫不可省：哨兵标签只能证明「代码取了哪个资源」，证明不了
    「那个资源说了什么」⇒ 用例「四」直接扫 `strings_sync_passkey.xml` / `values-en/strings.xml`
    断言类型行含「FIDO2 软件密钥」「库内加密存储」且不含「芯片/硬件/chip/hardware/secure element」
    （`§292` AC④ 同源）。双语各新增 19 个 `passkey_import_*` 键（**成对入库**，
    防 §335 那类 `MissingTranslation` 再犯）。
    **AC⑥ 守卫账目已重盘点**：`PopupSecureFlagInventoryTest` KDoc 加 2026-09-26 一行——
    分流**不新增菜单项**（顶栏仍那一个「扫码」项）、新增的是 1 个确认对话框且它**不属于**
    「无条件强制遮罩」那 4 类 ⇒ 调用点仍 4 处、菜单项仍 11 个，`SecureDialogFlagPolicyTest` /
    `SensitiveWindowHardeningTest` 均不改而绿（现跑在案）。
    **本片未做（第 4 片（下）的范围，不得据此条声称 Q1 已落地）**：① 编辑页通行密钥区块的
    扫码 / 相册导入附加入口（Q1，落库须走第 3 片新增的 `replacePasskeyOnEntry`）；
    ② AC⑪① 的「导入后 `rpId`/`credentialId`/`userHandle`/私钥在编辑页不可手改」只读锁与用例；
    ③ AC⑧ 真机端到端（含 `§340` 留痕的实拍取样）。
    验证读数：新增 `PasskeyImportConfirmDialogTest` 4 例 + `VaultListViewModelTest` 追加 5 例
    （原 22 例一字未改，含三条既有扫码用例）；全量 `test --rerun-tasks --max-workers=1`
    `BUILD SUCCESSFUL in 3m 29s`、`xml=410 tests=2733 failures=0 errors=0 skipped=13`
    （上片 2724 + 本片 9 例）；`lint` **0 errors、201 warnings**；`gate_readings.py` **7/7 PASS**。
    ⚠️ **本片的三次闸门拦截如实留痕**（都是「不跑就带病入库」的形态）：
    ① `lint` 报 **error** `LocalContextGetResourceValueCall`——首版在 @Composable 里用
      `context.getString(id)` 取文案，改为「@Composable 里一次性 `stringResource` 解析成表 →
      纯函数查表」后 0 errors（该规则的存在理由是配置变更时文案不重组）；
    ② `count_line_tiers` 报 **tier1=1**——接线把 `VaultListScreen.kt` 推到 503 行，
      将确认环节宿主抽成 `PasskeyImportConfirmationHost`（同文件同职责）后回到 492 行；
    ③ `+1` warning 是**本片新增键** `passkey_import_extra_credentials`（`%1$d more passkey(s)…`）
      触发的 `PluralsCandidate`（本仓既有同类 43 条，属基线内形态，未改 plurals 以免与
      全站「单行 + 计数」文案风格割裂——登记而非掩盖）。
    ⚠️ **行数棘轮额度已接近用尽**：`tier2(400~500)=36 / budget=37`（本片 `VaultListActionController`
    进 492 行占 1 席）⇒ **第 4 片（下）与第 5/6 片不得再新增 tier2 文件**，须先压缩或拆分既有文件。
  - **第 4 片（下）编辑页附加入口 + 字段只读锁（Q1、口径 1 / 5 / 3b，AC①③⑤⑥⑩⑪）—— 已完成**
    （2026-09-26）。**草案承载收拢为一份**：新增 `app/passkey/PasskeyImportDraftHost.kt`
    （字符载荷 → UTF-8 字节（编码器内部 buffer 一并清零）→ 解析器 → 待确认草案 + 静态拒收文案），
    顶栏 `VaultListActionController` 的原内联体改为委托它（492 → 452 行）——AC⑤
    「取景 / 相册实现不允许复制第二份」在**草案层**同口径适用；顶栏 VM 同时补 `onCleared` 兜底擦除
    （确认环节未走完就退页时，私钥明文不得只靠 GC）。
    **Q1 接线**：`rememberEntryEditPickers` 新增第二个触发入口 `scanPasskeyQr`，**同一**
    `TotpScanDialog`（含 `§340` 的相册通路）→ `EntryEditViewModel.onQrPayloadDecoded` 分流 →
    `EntryEditPasskeyImport` 会话 → 确认后 `repository.replacePasskeyOnEntry(entryId, …)`
    （口径 5 的偏离已在第 3 片登记：`saveOrReplacePasskeyEntry` 按 rpId+用户名检索，
    库里同站点同用户名有**另一条**时会写到那条，不满足「挂当前条目」）。
    ⚠️ **编辑页的分流口径与顶栏刻意不同，理由写明**：只有 `Passkey` 形态改道，
    `Totp` **与 `Unknown`** 都原样走本页既有的「回填种子」通路（`onTotpSecretChangeSecure` 一字未改）。
    顶栏那条「Unknown 即拒」在这里**不适用**——本页手填通道本就接受纯 Base32 等宽松形态，
    且回填结果是用户看得见、可撤销的输入框，不是静默建条目；照搬即把「扫一张旧种子二维码」
    这条既有通路做成回归（用例「一」把两侧都钉住：`otpauth:` 与裸 Base32 均仍回填、均不挂草案）。
    ⚠️ **两个前置闸门（拒绝导入）**：`entryId == null`（新建表单没有「当前条目」可挂，
    替换按 id 定位）与**表单有未保存改动**（替换写的是库内条目，随后重载会把用户刚打的字静默顶掉）
    ⇒ 一律拒并提示 `edit_passkey_import_requires_saved`；入口按钮本身只在**已绑定且已落库**的条目上出现。
    ⚠️ **替换成功后必须重载**（`loadEntry`）：表单里那些 passkey 字段此刻属**上一把凭据**，
    不读回新状态，用户随后点保存就把旧凭据写了回去、导入白做。用例「三」以「表单 rpId 栏已是新值 +
    库内旧凭据不残留 + `note_key` 一条不丢」三重读数锁住。
    **对话框复用**：编辑页挂同一个 `PasskeyImportConfirmDialog`，新增 `replacesEntry` 参数
    （正文第三行改说「将替换本条目的通行密钥（标题、备注等其余内容保持不变）」、按钮改说「替换」）——
    沿用「将新建条目」就是当面撒谎，用例「四」以「正文出现 TITLE: 即红」锁住。
    扫码对话框按 AC⑤ 只允许的做法**参数化**：新增 `titleRes`（默认值即原键，既有调用与用例不改一字）；
    ⚠️ 同时把 `edit_scan_dialog_title` 的文案改为中性「扫描二维码（验证码 / 通行密钥）」：
    顶栏与 TOTP 按钮共用同一对话框而现在它**真能**导入通行密钥，仍写「扫描 TOTP 二维码」即失实陈述。
    **AC⑪① 只读锁（§3.3.12.1「除 username / userDisplayName 外 MUST NOT be user editable」）**：
    判据 `isLockedPasskeyFieldKey` 锁**全部** passkey schema 键（含 `Passkey.*` v1 旧键：rpId /
    credentialId / userHandle / 私钥 PEM / 算法 / 公钥 / 计数器 / 两枚 PRF 种子 / BE·BS / 创建时间），
    仅按规范明文豁免 `KPEX_PASSKEY_USERNAME` / `Passkey.UserName` / `Passkey.UserDisplayName`
    （取舍与可逆性已入 `PD-08` 第 5 项）。执行点三层：① 编辑页分节按判据渲染**只读卡片**
    （无键名框 / 无值框 / 无删除钮 / 无保护开关，受保护字段不显值并如实标注原因）；
    ② `EntryEditCustomFieldEditor` 的三条写通路——⚠️ 判锁放在 `updateField` **函数开头**而不是只靠
    投影层挡列表：切换保护标记的分支会先把值物化成 `CharArray` 副本，「状态没变」不等于「没留下明文」；
    投影层的 `withUpdatedCustomField` / `withoutCustomField` 是第二道锁（删除同属「手改」）；
    ③ `loadEntry` **不再按需解密**被锁字段（改不动就没有把私钥物化进编辑态内存的理由）——
    已核实保存侧 `VaultEntryWriteCoordinator.mergeCustomField` 的「受保护且未提交 ⇒ 回填库内原值」
    分支承担写回，不依赖编辑态副本。
    **闸门驱动的两次纯结构性搬移**：`EntryEditViewModel` 原 494 行，本片要加约 22 行 ⇒ 先按 §210 /
    `ISSUE-P3-305` 先例拆出 `EntryEditEntropyRefresh`（强度评估调度）与 `EntryEditCustomFieldEditor`
    （自定义字段四写入口），落 **496 行**、`tier1=0` 且 `tier2` 未增（36/37 不变）。
    **测试替身扩展**（`FakeVaultRepository`）：`replacePasskeyOnEntry` 原只认 `extraKdbxEntries`，
    编辑页用例里的条目是早就在库里的普通条目（落在 `entriesFlow`）⇒ 不补这一支，Q1 的替换语义
    在单测里**永远只走 null 分支**、等于没测；新增 `sessionReadOnly` 开关驱动只读拒绝分支。
    **AC⑥ 守卫账目已重盘点**：零新增对话框、零新增 Popup 调用点、零新增菜单项（只读卡片只渲染
    非受保护值）⇒ 4 处 / 11 项不变，`PopupSecureFlagInventoryTest` KDoc 加 2026-09-26 一行，
    `SecureDialogFlagPolicyTest` / `SensitiveWindowHardeningTest` 不改而绿。
    验证读数：新增 `EntryEditPasskeyImportTest` 5 例 + `EntryEditPasskeyFieldLockTest` 5 例 +
    `PasskeyImportConfirmDialogTest` 追加 1 例（原 4 例**断言**一字未改；其中原「四」因新例插队顺延为「五」，
    共享的哨兵 `render` 加了 `replacesEntry` 参数与一行新键映射）；每条「拒绝」断言都配**正向对照**
    （普通字段仍可改名 / 仍可删 / 仍被解密 / 仍可置 dirty），防「谁都改不动」的假绿；
    全量 `test --rerun-tasks --max-workers=1` `BUILD SUCCESSFUL in 3m 41s`、`114/114 executed`、
    `xml=412 tests=2744 failures=0 errors=0 skipped=13`（上片 2733 + 本片 11 例）；
    `lint` **0 errors、202 warnings**（+1＝新增 `VaultListViewModel.onCleared` 的 `EmptySuperCall`，
    与仓内其余 6 个 ViewModel 的 `super.onCleared()` 同形态，属规则与本仓约定冲突，登记不改）；
    `gate_readings.py` **7/7 PASS**（`tier1(>500)=0` / `tier2=36 budget=37` / `long_functions=0` /
    `check_tautological_assertions` 命中 0 处·扫描 **470** 文件 / `check_bounded_type_names`
    `allowed=12` 不受新类型名影响——`*DraftHost`/`*Import`/`*Editor`/`*Refresh` 均不属 `PD-34` 受限后缀）。
    ⚠️ **本片仍未做（第 5 / 6 片范围）**：AC⑧ 真机端到端与**实拍取样**（「相机能不能扫」至今未证，
    声称范围仍按第 2.5 片定稿：相册可声称、相机待实拍）＋ 全条目批次归档。
  - **第 5 片（前半）AC⑧ 相册通路端到端取样（AVD `Pixel_10`，2026-09-27，依用户指示「模拟器内测试，
    不扫码，仅仅通过相册导入」）** —— 夹具由本轮新写的生成器产出（`build/make_ui_import_qr.py`，
    不入库；私钥为运行时 `secp256r1` PKCS#8 DER → Base64URL，非手抄串），六张 PNG 全部 <4 096 B：
    `bare-passkey-L` 559 B/81 模块、`document-envelope-Q` 703 B/117、`two-passkeys-L` 1 216 B/117、
    `incompatible-extensions-L` 588 B/81、`keepassxc-shape-L` 428 B/69、`totp-regression-L` 69 B/33
    （`box_size=8 border=4`，`build/qr-probe-ui/MANIFEST.txt` 有逐张 sha256 前缀）。
    **库是设备上新建的**（`probeui.kdbx`，主密码仅测试用），全程走**顶栏扫码 → 从相册导入**与
    **编辑页通行密钥区块 → 扫码 / 相册导入**两条入口，逐步以 `uiautomator` 读数取证（非推定）：
    ① **分流正确**：JSON 载荷 → 确认对话框（标题「导入这把通行密钥？」、按钮「导入 / 取消」）；
    `otpauth:` 载荷 → **不弹确认框**、直接建 `Acme` 验证码条目并出码 `730616`（AC⑤ 顶栏零回归在设备面成立）。
    ② **确认在先**：读正文后点「取消」，库内条目数与 rpId 字段一字未动（取消路径同样擦除草案）。
    ③ **口径 4′ 点名逐条命中**：两把凭据的文档 → 「文档内另有 1 把通行密钥未导入」；
    不兼容夹具 → 「不会写入：credBlob、largeBlob、payments」三件齐全，而**缺第二枚种子另起一行**
    「来源未提供 / 不完整：PRF 扩展缺少一枚种子」（PD-48 裁决二的两行分离在设备面成立），
    未知扩展成员 `unknownFutureExt` 按 §3.1.1 **静默忽略**（不点名、不拒收）✓。
    ④ **KeePassXC 兼容形**：正文标 `· KeePassXC`，缺显示名时报「来源未提供 / 不完整：显示名」而非拒收 ✓。
    ⚠️ 本轮顺手把兼容形词表**对官方源码逐字核对**（`参考项目/keepassxc-develop/src/gui/passkeys/PasskeyImporter.cpp:70-89`）：
    必需键为 `relyingParty` / `url` / `username` / `credentialId` / `userHandle` / `privateKey`——
    即**只有 `relyingParty` / `privateKey`（与 `url`）是 KeePassXC 专有名**，其余与 CXF 同名 ⇒
    本读取器 `KPXC_FIELD_*` 只别名这两键的口径**正确**（首版夹具误用 `userName` / `credential` / `userKey`
    是我方夹具拼错，不是读取器缺别名；改正后读数见 ④）。`url` 本库不落条目 URL，故不作必填判据。
    ⑤ **Q1 编辑页入口**：对话框标题为参数化后的「导入通行密钥二维码」（非 TOTP 字样）✓；
    正文第三行说「将替换本条目的通行密钥（标题、备注等其余内容保持不变）」、按钮只有「替换 / 取消」✓；
    点「替换」后同一条目标题仍是 `johndoe@webauthn.io`、而 `KPEX_PASSKEY_RELYING_PARTY` 变为 `w3c.org`
    ⇒ **替换落在正在编辑的这条、且表单已重载**（列表页仍只有 1 条 passkey 条目，未新建）。
    ⚠️ 顺带读数：条目 URL 仍是 `https://webauthn.io`（`replacePasskeyOnEntry` 按「非 passkey 字段不动」
    保留了它）——跨站替换后 URL 与新 rpId 不一致，凭据匹配主看 `passkey.rpId`（URL 只是非 passkey
    条目的兜底），故不构成缺陷，但**如实登记**。
    ⑥ **AC⑪① 在设备面被证伪检验通过**：自定义字段区分节里 13 个 passkey 键全部以**文本节点**呈现
    （键名 + 非受保护值 + 「通行密钥字段由本库托管：注册或导入时自动写入，不可在此编辑」，受保护值另标
    「受保护字段，编辑页不显示明文」）；整页可编辑控件（EditText）里**只有** `KPEX_PASSKEY_USERNAME`
    与 `Passkey.UserDisplayName` 两个展示字段出现，其余（rpId / credentialId / userHandle / 私钥 PEM /
    PRF / PrfNoUv / BE·BS / Algorithm / SignCount / CreatedAt）**无输入框、无删除钮、无保护开关**
    ⇒ 与 §3.3.12.1 的豁免面**逐键吻合**（不是「全锁」也不是「没锁」）。
    ⑦ **落库产物的官方实现读数**（规则 8：把设备上写的 `.kdbx` 经 `run-as` 拉回宿主复核；
    文件 1 957 B、sha256 前缀 `a88da9cd82b1594a`、KDBX4 magic `03D9A29A` + `0xB54BFB67`）：
    `pykeepass 4.2.0` 解锁成功并读出全部字段，**保护位逐键正确**（credentialId / userHandle /
    PRIVATE_KEY_PEM / PRF / PrfNoUv = `Protected="True"`，其余明文）；
    `keepassxc-cli 2.7.12` `show --all -s` 对同一条目读数与 pykeepass 逐项一致（含多行 PEM）；
    `cryptography` 独立解析该 PEM ⇒ 曲线 `secp256r1`（与 `Passkey.Algorithm=-7` 同向）、
    SPKI 公钥 91 B；两枚 PRF 种子按**标准 Base64** 各解出 32 B（头 4 B `00010203` / `0708090a`
    与夹具逐字节相符）⇒ `PasskeyPrf.decodeSecret` 的消费前提在导入件上成立；
    `Passkey.SignCount = 5 239 806` ∈ `[2^20, 2^24)`（PD-49 裁决一的随机高位起点在设备面命中）。
    **本轮踩到并修掉的取样陷阱（不修就会假绿，必须留痕）**：相册选取器按日期排序且**全卷**参与排序——
    只清 `/sdcard/Pictures` 时，其它目录的残留行会排到当轮夹具前面，首轮就点到了上一轮的旧图
    （读数与夹具不符但**看起来成功**）。改为每轮 `content delete` 清空整卷图片索引 + 清文件 +
    把新图 mtime `touch` 到「明天」，并断言「全卷图片行数 = 1」后才继续。
    另一条：确认对话框按钮**必须按精确文本点击**——正文里含「将**替换**本条目…」，
    按子串匹配会先命中那段不可点击的正文，白点一次并把对话框滑掉（等价于取消，本轮据此意外
    取得了一条「取消不写库」的对照读数）。
    ⚠️ **AC⑧ 未做完的部分（不得据本条推定已闭环）**：
    (a) **相机实拍（AC⑧③ / 未决 4 的相机面）未做**——本轮按用户指示不扫码，且 AVD 以
    `-camera-back none` 启动，取景框如实报「相机启动失败（可能被其它应用占用），请关闭后重试」，
    同时**相册入口仍可见可点**（§340 的降级口径在设备面得证）。「相机能扫」仍**无任何证据**，
    该读数也不能外推到真机相机；
    (b) **「导入的通行密钥能经 CM 通道对真实 RP 完成 GetAssertion」未做**——本轮只证到
    「导入件在本库内可被消费」（PEM / 曲线 / PRF / 保护位 / 官方实现读数），
    未证到端到断言。真实 RP 侧还需要一个「能导出 CXF 的注册方」（自搭 RP，或按本条既有口径
    取「同库自造凭据先注册一次、再导入同一把」的对拍），且须写明该对拍不代表全网 RP 的严判行为；
    未决 6 的实测出口同样挂在 (b)。**取哪种口径需用户决定**（自搭 RP 是独立工作量）。
    ⇒ **2026-09-27 已立项 `ISSUE-P3-339`**：本地 RP 实验室 + 四类仿冒 origin 的「该醒 / 不该醒」
    双向验证，(b) 项与未决 6 的实测出口都并入该条载体，本条不再单独排期。
    本条为**取样记录，代码零改动**：复跑 `gate_readings.py` **7/7 PASS**
    （`tier1(>500)=0` / `tier2=36 budget=37` / `long_functions=0` / `BROKEN_MD_LINKS=0` /
    `RESOLVED_INDEX_SYNC=OK` / 重言断言 0 命中·470 文件 / `allowed=12`）。

