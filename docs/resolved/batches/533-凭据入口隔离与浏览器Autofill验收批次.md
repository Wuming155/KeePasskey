# §533 凭据入口隔离与浏览器 Autofill A/B 验收批次

<a id="s533"></a>

## 1. 闭环范围

本批关闭 ISSUE-P2-620。凭据入口具有跨进程唯一身份并在交付前复核最终系统请求；Android Credential Manager 两域进程死亡矩阵、Autofill 认证会话隔离已分别通过既有 AVD/实体机验收。本批补齐真实浏览器网页经 Android 系统 Autofill 取得两域各自合成凭据的端到端证据。

用户确认产品验收不绑定特定浏览器品牌或版本。本次浏览器只作为 Android Autofill 服务的真实网页客户端样本，不以单浏览器样本声称所有浏览器均已逐一测试，也不把 Chrome 的网页 Credential Management API 调用链设为产品要求。

## 2. API 36 AVD：浏览器网页 A/B 填充

- 浏览器：模拟器内实际安装的 Chrome 134.0.6998.135；使用 Android 系统 Autofill，服务为 `com.keepasskey.debug/com.keepasskey.app.autofill.KeePasskeyAutofillService`。
- 凭据提供方：KeePasskey Credential Manager 与 Autofill 服务均在 AVD 启用；本轮仅使用隔离测试数据库和合成账号。
- A 页面：`cm-p2-620-a.localhost` 网页账号/密码字段触发 Android Autofill。框架会话 `req=794` 返回 `TYPE=SUCCESS`、`AUTH_STATUS=DATASET_AUTHENTICATED`；用户选择 A 记录后，页面本地分类为 `RESULT_A`。
- B 页面：独立 host `cm-p2-620-b.localhost` 重复同一流程。框架会话 `req=798` 返回 `TYPE=SUCCESS`、`AUTH_STATUS=DATASET_AUTHENTICATED`；用户选择 B 记录后，页面本地分类为 `RESULT_B`。
- 页面只以本地合成值比较账号/密码并显示分类；没有将密码发送给本机服务端或写入验收回执。脱敏回执位于 `.ci-artifacts/p2-620-browser-autofill/evidence.txt`。

## 3. 经验教训

曾把 Chromium 源码里存在的第三方 Credential Management 转发路径误当成唯一验收方式，造成无效排查。浏览器密码填充验收应先明确产品要验证的接口：Android Autofill 等系统级接口与浏览器专有 API 是不同调用路径；不得将 Chrome 或某个版本设为唯一入口，也不得把单个浏览器样本外推为所有浏览器均已逐一通过。

## 4. 既有隔离验收

- API 36 AVD 系统 Credential Manager 双域普通进程死亡矩阵 run `1013fc759292`，2/2；旧 A 只返回 A 或取消，新 B 只返回 B。
- Redmi 4X API 37 系统 Credential Manager 矩阵 run `14be83999329`，2/2；旧 A 的最终身份复核拒绝交付，新 B 收到 B。
- Redmi 4X API 37 PendingIntent 身份用例 6/6、Autofill PendingIntent 身份用例 1/1；Autofill 全链在 API 36 AVD 与 Redmi 4X 各 1/1；旧 A 认证结果不跨新 B 框架会话的用例 run `dfa47013c631`，1/1。

## 5. 边界

- A/B 端到端结果证明该 AVD 浏览器通过 Android Autofill 系统接口完成了网页账号/密码填充；不代表所有浏览器逐个验收。
- 浏览器品牌/版本不是产品验收条件。系统 Credential Manager 的 origin 与进程死亡边界由真实 Android API 测试矩阵覆盖；本批真实网页样本走 Android Autofill。
- 测试使用合成账号、密码和本机页面；没有外部网站、生产凭据或服务端秘密回传。

## 6. 关联入口

- 整改方案：[凭据入口跨进程隔离与最终请求绑定方案](../../architecture/问题整改方案/P2/ISSUE-P2-620/凭据入口跨进程隔离与最终请求绑定方案.md)
- 当前未闭环事项：[ACTIVE_ISSUES.md](../../ACTIVE_ISSUES.md)
