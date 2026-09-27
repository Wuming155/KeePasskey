# 仿冒域唤醒实验室（`ISSUE-P3-339`）

验一件事：**仿冒站点能不能让本应用把通行密钥交出去**。分两半环：

| 半环 | 判据 | 现状 |
| --- | --- | --- |
| **代码层**（本应用对 `(origin, 凭据 rpId)` 的接受/拒绝） | `app/src/test/.../CredentialProviderLookalikeMatchTest.kt`（表驱动，宿主可跑） | ✅ **已做，并当场揪出两处越界/不一致并修掉**（见下） |
| **浏览器层**（Chrome → 系统 CM → 本应用 provider 真链路 + 不泄露存在性） | 本目录 `rp_server.py` + `run_matrix.py` | ⛔ **被证书信任卡住**（三条实测阻塞见「已证伪的路线」），需要「两个不同可注册域 + 公开可信证书」才能跑 |

## 为什么域名必须是 `.xyz` 而不是 `.test`

随仓 PSL（`app/src/main/resources/publicsuffix/public_suffix_list.dat`）**不含** `test` /
`invalid` / `local`，而 `PublicSuffixList` 的口径是「未知 TLD ⇒ 不可注册（fail-closed）」。
用 `rp.test` 做实验会让**正向用例也拿不到候选** ⇒ 「仿冒域不出候选」这条负向读数
与「什么都不出」不可区分，整张表变成重言断言。`com` / `dev` / `xyz` 经核实在册，本实验室取 `xyz`。

## 组成

| 文件 | 作用 |
| --- | --- |
| `make_certs.py` | 生成实验室根 CA 与服务端证书（SAN 覆盖全部 U-label / A-label + `10.0.2.2`），`--install <serial>` 一键注入设备系统锚 |
| `rp_server.py` | HTTPS RP：`/case/<action>/<rp>` 自跑页面（免点击）、`/api/create/*` `/api/get/*` 验 `rpIdHash` / `origin` / 签名与**计数器跳变**、`/report` 收浏览器侧结局、`/status` 导读数 |
| `minicbor.py` | 极简 CBOR + authData / COSE 公钥读法（本机无 `cbor2` / `py-webauthn`，且只需只读子集） |
| `run_matrix.py` | 逐用例驱动 AVD Chrome、切片 logcat、汇总成表 |

密钥与证书一律 **gitignore**（根 `.gitignore` 已含 `*.pem` / `*.key`）。仅限本机与模拟器网段，
禁止公网暴露；实验室凭据只进**测试库**，禁止导入真实密码库（§263 设备纪律）。

## 已证伪的路线（不要重复踩，都是实测）

1. **注入 `/system/etc/security/cacerts`** —— `adb root` + `-writable-system` +
   `adb disable-verity` + `remount` 全部成功、文件确实落盘（`cfff3353.0`，644，restorecon 已做），
   但 Chrome 仍报 `NET::ERR_CERT_AUTHORITY_INVALID`：**Chromium 用的是编译期内置根集**，
   运行期加系统锚不改变它的判断。
2. **`thisisunsafe` 点掉安全提示** —— 页面确实加载、JS 确实执行（实验室的自跑页把结果 POST 回来了），
   但 WebAuthn 直接拒绝：
   `NotAllowedError: WebAuthn is not supported on sites with TLS certificate errors.`
   ⇒ 绕过路线对**本条判据**无效（不是"有偏差"，是根本跑不出断言）。
3. **`cmd credential ...` 从 shell 下发 `GetCredentialRequest`** —— `cmd -l` 里确有 `credential`
   服务，但 `cmd credential help` 回 `No shell command implementation`，无 shell 入口。
4. **`http://127.0.0.1`（`adb reverse`）** —— 免证书且算安全上下文，但所有用例塌成同一个 host，
   仿冒矩阵表达不出来；只能当「IP origin 不参与域匹配」的补充负例。
5. **URL 参数带 `&` 经 `adb shell` 传参** —— 会把 `\&` 原样带进 URL 或截断查询，
   表现为「未连接到互联网」这种**看起来像网络问题**的假故障 ⇒ 实验室页面改用路径式
   `/case/<action>/<rp>`，少一个可错项就少一类假读数。

## 跑浏览器半环还缺什么

需要**两个互不为后缀的可注册域**且各自有 Chrome 认的信任链，现实选项：

- 用户自有域名 + ACME（DNS-01 最省事，不必开公网端口）：给 `rp.<dom>` 与
  `rp.<dom>.phish.<dom2>` 签真证书；
- 或企业设备策略让 Chrome 接受自建 CA（`adb shell dpm`/work profile，成本高）；
- 或改用**自带信任库**的浏览器（侧载 Firefox 系），但它对 CM 的接线方式与 Chrome 不同，
  换它就得重述判据。

## 代码层已修掉的两处（本轮实测所得）

1. **`KeePasskeyCredentialProviderService.findMatchingEntries` 的 URL 兜底不区分条目类型**
   ⇒ 「`KPEX_PASSKEY_RELYING_PARTY` 属 A 域、条目 URL 写了 B 域」的 passkey 条目会在 B 域被列进候选。
   签名侧另有 rpId 复核（`PasskeyAssertionActivity`），断言交不出去，但**凭据存在性跨域泄露**，
   且用户一点就在签名处撞上拒绝。现收紧为「URL 兜底只服务口令 / 密码条目，passkey 只认 `passkeyRpId`」。
2. **根点归一不一致**：`PublicSuffixList.normalizeHost` 去 DNS 根点，而 `DomainMatcher.extractDomain`
   不去 ⇒ 两个归一器对同一主机名给出不同答案（尾点 origin 的合法凭据不出候选）。现由
   `extractDomain` 统一剔除，与 WHATWG URL（浏览器也吃掉末点）同形。

两条都由 `CredentialProviderLookalikeMatchTest` 钉住（每条负向都配同源正向对照，防「什么都不出」假绿）。
