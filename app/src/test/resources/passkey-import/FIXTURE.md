# 夹具出处与取证口径（`app/src/test/resources/passkey-import/`）

> 服务 `ISSUE-P3-337` 第 2 片（`PasskeyCxfReader`）的 AC② 夹具纪律：
> **规范附录 A 的示例 JSON 本身即权威夹具**，不得按臆想字段自造「规范示例」（规则 8 同源）。

## `cxf-appendix-a-example.json`

| 项 | 读数 |
|---|---|
| 来源 | FIDO Alliance **Credential Exchange Format v1.0**（Proposed Standard，2025-08-14）**附录 A 示例文档** |
| 原文 URL | `https://fidoalliance.org/specs/cx/cxf-v1.0-ps-20250814.html` |
| 取用方式 | 2026-09-26 由 `curl` 落盘该 HTML（839 012 B），再以 `re.findall(r"<pre[^>]*>(.*?)</pre>")` 逐块还原实体后**整块原样收录**（第 43 块，即含 `hmacSecret` 的那段示例）；**未**手工编辑任何键、值或顺序 |
| 行尾 | 原文块含 CRLF（Windows 下 Python 文本写的副作用），入库前统一转 **LF** |
| 字节数 | **28 854 B**（LF） |
| SHA-256 | `cb9cd193a2d6038483e7901c4802dfaf3ba81ce35335bbd2ad7415f2700cd89a` |
| 结构读数 | `version{major:1,minor:0}` + `exporterRpId:"exporter.example.com"` + **1 个 account**，其 `items[].credentials[]` 共 **15 条凭据 / 15 种 `type`**（`basic-auth` `totp` `passkey` `credit-card` `wifi` `note` `drivers-license` `address` `ssh-key` `file` `identity-document` `passport` `person-name` `api-key` `generated-password`），passkey 占 1 条 |
| 那把 passkey | 紧凑 JSON **472 B**；`key` = Base64URL → **PKCS#8 DER 138 B**，前缀 `30 81 87 02 01 00 30 13 06 07 2A 86 48 CE 3D 02 01 06 08 2A…`（ecPublicKey + prime256v1），本仓 `PasskeyKeyText.sniffAlgorithmId` 在该样本上判为 ES256（`-7`）逐字节命中 |
| 扩展形 | 该凭据的 `fido2Extensions` 写的是 **`hmacSecret{algorithm:"HS256", secret:"c2VjcmV0X2tleV9kYXRh"}`**，与 §3.3.12.2~.4 的 CDDL（`hmacCredentials{algorithm:"hmac-sha256", credWithUV, credWithoutUV}`）**键名 / 成员数 / 算法值三项全部冲突**，且 `hmacSecret`、`HS256` 在整份规范里只出现于这一段示例 ⇒ 解析器必须同时认两种拼写，否则规范自己的示例会被解成「无 PRF」（口径 2′；`PD-48` 裁决二的前提） |

**用例对它的三种用法**（`PasskeyCxfReaderTest`）：整块直读（⇒ `PayloadTooLarge`，实测尺寸远超单张 QR）、
抽出其中 passkey 对象作各形态内核（裸对象 / 数组 / 文档信封）、以及**改名与搬层**的负例
（把凭据搬进 `collections[].items[]` 的 `LinkedItem` 引用里 ⇒ 必须取不到）。

## 待补项（如实登记，**不得**以现有夹具顶替）

`PD-08` 第 1 项的兼容目标 **KeePassXC `.passkey` 正例目前用的是「同形自造夹具」**
（字段集与编码取自 `参考项目/keepassxc-develop/src/gui/passkeys/PasskeyImporter.cpp:73` 的 6 必需字段读数
与 `PasskeyExporter.cpp:98-104` 的写出字段，私钥 PEM 由附录 A 那把 DER 经本仓 `derToPemChars` 生成）。
它**证明的是字段词表与归一口径，不是与 KeePassXC 的互操作**——规则 8 只认官方实现端到端对拍。

- 阻塞点：本机 `keepassxc-cli` 2.7.12 **无 passkey 导入/导出子命令**（该功能只在 GUI 侧，
  `gui/passkeys/PasskeyImporter` / `PasskeyExporter`），故无脚本化取物途径。
- 补法：由用户在 KeePassXC GUI 内对含通行密钥的条目执行「导出 passkey」，
  产出的 `*.passkey` 文件放入本目录（建议命名 `keepassxc-<rpId>.passkey`），
  再为该文件追加一条逐字节读取用例；届时同步更新本表的 SHA-256 与读数。
