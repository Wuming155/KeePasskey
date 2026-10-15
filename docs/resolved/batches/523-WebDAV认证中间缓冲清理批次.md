# §523 WebDAV 认证中间缓冲清理批次

<a id="s523"></a>

## 1. 闭环范围

本批关闭 `ISSUE-P3-630`。原问题为 `WebDavAuthHeader.build()` 曾从 UTF-8 密码编码缓冲复制独立密码字节数组；若副本未纳入同一 `finally`，它会留待 GC 回收。

问题影响要求进程内存读取，超出当前云端无密钥威胁模型。最终 HTTP Basic Authorization 值仍由既有 `String` API 承载；本批不承诺全堆擦除，也不把它描述为云端可利用的口令泄露。

## 2. 实施与验收

- 删除独立 `passwordBytes` 副本。`withWipedPasswordBuffer` 在正常返回、后续拼接异常和分配失败时清除 UTF-8 编码缓冲；`withWipedCredentialBytes` 为拼接数组提供独立 `finally` 清理。
- 调用方借用的口令 `CharArray` 不由构造器清除或改写；Basic 头的 UTF-8 编码行为保持不变。
- `WebDavAuthHeaderBufferWipeTest` 覆盖 ASCII、中文、代理对、空密码、正常清理、拼接异常、拼接分配失败及 16 路并发构造。并发构造结果无串扰，借用数组保持原值。
- 完整 `:sync:testDebugUnitTest` 曾通过 357 项；新增并发用例后再次运行该测试类，5 项通过，0 失败、0 错误。
- 本批未新增或修改 Android 用例，按方案不要求设备测试；API 37 实体机上的其他整改用例不作为本项证据。

## 3. 过程与限制

- 首次定向测试在沙箱中因 Gradle Wrapper 缓存锁拒绝访问未启动；复用已配置缓存后测试通过，未改构建配置。
- 未执行真实凭据请求或堆转储。清理契约以作用域 `finally` 与对虚构缓冲的运行时断言验证。
- 最终 Basic header 的 `String` 驻留属于既有 HTTP API 边界，继续按敏感缓冲所有权契约记录。

## 4. 关联入口

- 原始方案：[WebDAV 认证中间缓冲清理方案](../../architecture/问题整改方案/P3/ISSUE-P3-630/WebDAV认证中间缓冲清理方案.md)
- 敏感缓冲契约：[敏感缓冲所有权契约](../../architecture/敏感缓冲所有权契约.md)
- 当前未闭环事项：[ACTIVE_ISSUES.md](../../ACTIVE_ISSUES.md)
