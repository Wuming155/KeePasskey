# ISSUE-P2-604：DAL声明响应严格校验方案

> 编制日期：2026-10-13（Asia/Shanghai）；源码核对基线：`65cc39f8`。
> 状态：方案已编制，尚未实施；登记条目继续开放。以下测试均为实施时的验收要求，不是本次实测结果。

## 1. 问题与依据

来源为 AUD-16。2026-10-13 直读 `app/src/main/java/com/keepasskey/app/di/DalVerifierModule.kt` 与 `passkey/DigitalAssetLinksVerifier.kt`：DAL 使用跟随跳转的客户端，仅判断 isSuccessful，未核对声明媒体类型。Google 官方要求原声明地址直接返回 200、application/json，不能跟随重定向。

## 2. 目标与边界

仅接受目标 HTTPS 主机 well-known 地址的直接合规响应；失败不得写 VERIFIED 缓存。继续保留 TLS-only、系统 CA、SSRF 双层保护和响应大小预算；不改变用户配置 WebDAV/S3 的通用跳转策略。

## 3. 实施步骤

1. 在加固工厂得到的客户端上派生 DAL 专用配置，关闭 followRedirects 和 followSslRedirects；不能另建裸客户端导致丢失 DNS/连接期守卫。
2. 在读取正文前严格检查 response.code==200，并确认响应链/最终请求仍对应原地址；301/302/303/307/308 一律失败，包括同主机跳转。
3. 用媒体类型解析检查 type=application、subtype=json，允许合法 charset 参数；缺失、text/plain、HTML 及格式非法均拒绝。
4. 保留原有有界流式读取、JSON 结构、relation、包名、证书匹配及关闭响应体；Accept 请求头不等于服务端返回 Content-Type 合规。
5. 区分协议拒绝与网络不可用，均不能授权；正负缓存沿用既有键和 TTL，旧进程内正缓存通过重启/版本策略失效。
6. 核对 catch 的取消语义，改动时通过对应机检；不为协议问题扩大放行域或允许内网声明。

## 4. 验收矩阵

| 响应 | 必须观察的结果 |
|---|---|
| 原地址 200、正确 JSON 类型和匹配声明 | VERIFIED |
| 同主机/跨主机重定向到合法声明 | NOT_VERIFIED，目标地址未被访问 |
| 201/204/206，正文看似合法 | 不授权 |
| 200+缺失/错误类型；JSON+charset | 前者拒绝，后者合法时允许 |
| 超限、证书不可信、SSRF、错误包/摘要 | 既有拒绝行为保持，不写正缓存 |

扩展 `DigitalAssetLinksVerifierTest` 与出口加固测试；测试服务器计数验证没有实际发起第二次请求，不能只断言结果失败。

## 5. 顺序与实施验收

可独立整改，先 app 定向测试并回读 XML，再独立 AVD 的真实 TLS/DAL 路径和全量门禁。官方依据：[Google 声明列表位置与响应要求](https://developers.google.com/digital-asset-links/v1/create-statement)。真实域名可利用条件尚未证明，方案不将协议偏差夸大为已验证远程攻击。

## 6. 关联入口

- [待办登记](../../../../ACTIVE_ISSUES.md)、[全面审计证据](../../../../security/全面软件审计_2026-10-13.md)。
- [实施与验证约定](../../../../README.md#整改方案实施与验证约定)、[敏感缓冲所有权](../../../敏感缓冲所有权契约.md)、[已知工程限界](../../../已知工程限界.md)、[产品裁决](../../../产品裁决登记.md)。
