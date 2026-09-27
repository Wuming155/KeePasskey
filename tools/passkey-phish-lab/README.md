# 仿冒域唤醒实验室（`ISSUE-P3-339`）

验一件事：**仿冒站点能不能让本应用把通行密钥交出去**。分两半环：

| 半环 | 判据 | 现状 |
| --- | --- | --- |
| **代码层**（本应用对 `(origin, 凭据 rpId)` 的接受/拒绝） | `app/src/test/.../CredentialProviderLookalikeMatchTest.kt`（表驱动，宿主可跑） | ✅ **已做，并当场揪出两处越界/不一致并修掉**（见下） |
| **浏览器层**（Chrome → 系统 CM → 本应用 provider 真链路 + 不泄露存在性） | 本目录 `rp_server.py` + `run_matrix.py` | ⛔ **被证书信任卡住**（三条实测阻塞见「已证伪的路线」），需要「两个不同可注册域 + 公开可信证书」才能跑 |

## 域名口径：要的是**两个不同可注册域**，不是同域下的两个名字

两条易踩的坑（都是实测踩到的）：

1. **不能用 `.test` / `.invalid` / `.local`**：随仓 PSL
   `app/src/main/resources/publicsuffix/public_suffix_list.dat` 里这三条**不存在**
   （`grep -cE "^(test|invalid|local)$"` = 0），而 `PublicSuffixList` 的口径是
   「未知 TLD ⇒ 不可注册（fail-closed）」⇒ 正向用例也拿不到候选，
   于是「仿冒域不出候选」与「什么都不出」不可区分，整张表退化成重言断言。
2. **同一注册域下的子域互相当"仿冒域"没有意义**：`xyz` 才是公共后缀，所以
   `rp.testlab.xyz` 与 `rp.testlab.xyz.phish.testlab.xyz` 的 eTLD+1 **同为 `testlab.xyz`**
   ⇒ 对 WebAuthn 它们是**同站**，拿它们跑仿冒矩阵等于什么都没测
   （浏览器层如此；代码层的堆叠拒绝另有真表驱动用例，见文末）。
   正确做法是让每一行落在**不同的可注册域**上——例如两条 `*.trycloudflare.com` 隧道
   （该后缀在 PSL 私有段在册 ⇒ 每条隧道自成一个可注册域）。

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

**已解决**：证书不必自建。`cloudflared tunnel --url http://127.0.0.1:8788` 给的
`*.trycloudflare.com` 是**公网可信**证书；且 `trycloudflare.com` 在随仓 PSL 私有段在册
⇒ 每条隧道**各自**就是一个可注册域（两条隧道即矩阵要的「互不为后缀的两个域」），零设备篡改。

**未解决（2026-09-27 实测；两台设备各有各的阻塞，且都不是本应用的判据）**：

| 设备 | 现象 | 根因（证据） |
| --- | --- | --- |
| AVD `Pixel_10`（`google_apis` 无 Play） | 库内已有该域凭据，仍报「未发现任何通行密钥」 | logcat：`Auth.Api.Credentials(1666): [GetRemotePasskeyOperation] Operation started.`（1666 = GMS），而本应用 provider 的日志标签**命中 0 次** ⇒ 请求被 GMS 自己的 FIDO 栈接走，**第三方 provider 从未被绑定调用** |
| Redmi 4X（LineageOS，无 GMS；AOSP `com.android.credentialmanager` 在场且 `credential_service_primary` 已指向本应用） | `navigator.credentials.get()` 永不返回 | Firefox 不把 WebAuthn 委托给平台 CM；该 ROM 无其它浏览器 |

另两条前置闸门值得记住：平台要求**已设屏锁**才放行通行密钥（`设置屏锁后，才能使用通行密钥`）；
本机所在网络的模拟器**出去的 UDP/53 全不可达**（`-dns-server` 换三种配法 + 宿主转发器都收不到查询）
⇒ 名字解析只剩 hosts 一条路，而 hosts 属设备篡改，已撤（写过的转发器因从未收到查询而删除）。

⇒ 要跑通需要：① 换 `google_apis_playstore` 镜像并登录 Google 账号（GMS 才会去绑第三方 provider）；
② 或真机装一个走平台 CM 的 Chromium 系浏览器；③ 或自写调用 `androidx.credentials` 的测试 APK
（origin 归因还要配 DAL）。**在此之前本实验室只支持代码层结论**，
判据红线见 `docs/ACTIVE_ISSUES.md` `ISSUE-P3-339`。

## 代码层已修掉的两处（本轮实测所得）

1. **`KeePasskeyCredentialProviderService.findMatchingEntries` 的 URL 兜底不区分条目类型**
   ⇒ 「`KPEX_PASSKEY_RELYING_PARTY` 属 A 域、条目 URL 写了 B 域」的 passkey 条目会在 B 域被列进候选。
   签名侧另有 rpId 复核（`PasskeyAssertionActivity`），断言交不出去，但**凭据存在性跨域泄露**，
   且用户一点就在签名处撞上拒绝。现收紧为「URL 兜底只服务口令 / 密码条目，passkey 只认 `passkeyRpId`」。
2. **根点归一不一致**：`PublicSuffixList.normalizeHost` 去 DNS 根点，而 `DomainMatcher.extractDomain`
   不去 ⇒ 两个归一器对同一主机名给出不同答案（尾点 origin 的合法凭据不出候选）。现由
   `extractDomain` 统一剔除，与 WHATWG URL（浏览器也吃掉末点）同形。

两条都由 `CredentialProviderLookalikeMatchTest` 钉住（每条负向都配同源正向对照，防「什么都不出」假绿）。
