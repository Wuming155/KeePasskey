# 06 · passkeys参考（WebAuthn 生态：fenris / open-passkey / Authnkey）踩坑对照详情

> 来源：参考项目 `参考项目/` 下三个 passkey 生态项目（`fenris-authenticator-main/`、`open-passkey-main/`、`Authnkey-main/`）的踩坑标注与主项目 KeePasskey 的逐条对照。
> 扫描日期 2026-09-28。踩坑标注 15 条，对照行 15 条（risk=yes 0 / unclear 1 / no 14）。
> 总表见 [00-对照总表.md](00-对照总表.md)。

## 一、踩坑标注（15 条）

### 1. fenris-authenticator-main/app/src/main/java/se/koditoriet/fenris/credentialprovider/webauthn/AuthResponse.kt:30
- **代码上下文**：组装 WebAuthn 认证响应的 clientDataJSON
- **原文**：
  > clientDataJSON MUST be either the empty string, valid JSON, or some variant on the string "<placeholder>"
- **链接**：无（可达性：none）
- **坑的本质**：GCM 回传 clientDataHash 时 clientDataJSON 只允许空串/合法 JSON/"<placeholder>" 变体，否则远程(hybrid)认证失败；本地认证却不受影响，原因注释自承不明
- **置信度**：confirmed

### 2. fenris-authenticator-main/app/src/main/java/se/koditoriet/fenris/credentialprovider/webauthn/Validation.kt:38
- **代码上下文**：Credential Provider 校验请求 origin 与 rpId
- **原文**：
  > We intentionally do NOT validate rpId against origin, as Credential Manager does it for us
- **链接**：无（可达性：none）
- **坑的本质**：按平台契约不得自校验 rpId↔origin（系统 Credential Manager 已做并明确要求不做），自行校验属重复且违背平台预期
- **置信度**：confirmed

### 3. fenris-authenticator-main/app/src/main/java/se/koditoriet/fenris/Constants.kt:29
- **代码上下文**：定义对称密钥认证有效期常量
- **原文**：
  > Due to how AndroidKeyStore is implemented, it is unfortunately not possible to authenticate symmetric keys
- **链接**：无（可达性：none）
- **坑的本质**：AndroidKeyStore 无法对对称密钥做按单次操作认证（一次用户操作含多次 keymaster 调用），只能设 5 秒认证有效期窗口
- **置信度**：confirmed

### 4. fenris-authenticator-main/app/src/main/java/se/koditoriet/fenris/ui/screens/main/secrets/sheets/ConfirmImportSheet.kt:48
- **代码上下文**：导入确认底部 sheet 防误滑关闭
- **原文**：
  > Horrible, horrible hack: to ensure that the user doesn't accidentally close the bottom sheet
- **链接**：无（可达性：none）
- **坑的本质**：Compose 底部 sheet 无法在用户触屏期间否决 dismissal：触摸列表即禁用滑动关闭，松手后必须延时数毫秒再恢复，否则误关丢选择
- **置信度**：confirmed

### 5. fenris-authenticator-main/app/src/main/java/se/koditoriet/fenris/viewmodel/SetupViewModel.kt:19
- **代码上下文**：首次建库读取账户列表保护开关
- **原文**：
  > we have to use config.first(), because currentConfig() blocks until we have an initialized config
- **链接**：无（可达性：none）
- **坑的本质**：初始化阶段 config 尚未 ready，currentConfig()（await ready）会永久挂起；须用 config.first() 读未就绪值（restoreVault 同型注释 :46）
- **置信度**：confirmed

### 6. fenris-authenticator-main/app/src/main/java/se/koditoriet/fenris/viewmodel/SetupViewModel.kt:39
- **代码上下文**：从 Uri 打开备份文件流并解码
- **原文**：
  > throw an NPE instead of null checking to make sure all error handling goes through the catch
- **链接**：无（可达性：none）
- **坑的本质**：刻意不判空、放任 NPE/ISE 抛出，确保 openInputStream 等失败统一走 catch 错误处理不旁路（同型注释重复于 SettingsViewModel.kt:74、ImportFromFileSheet.kt:46）
- **置信度**：confirmed

### 7. open-passkey-main/packages/core-go/webauthn/tpm_attestation_test.go:253
- **代码上下文**：TPM 证明声明验证的回归测试
- **原文**：
  > Guards the original bug: extraData holding attToBeSigned verbatim.
- **链接**：无（可达性：none）
- **坑的本质**：TPM 证明实现曾把 attToBeSigned 原文（authData||clientDataHash）直接放进 certInfo.extraData；正确实现必须放其 SHA-256 哈希，测试锁定防回归（core-ts 同型测试同注释）
- **置信度**：confirmed

### 8. open-passkey-main/packages/core-ts/src/__tests__/tpm-android.test.ts:341
- **代码上下文**：TPM/Android 证明验证回归测试（TS 版）
- **原文**：
  > Guards the original bug: extraData holding attToBeSigned verbatim.
- **链接**：无（可达性：none）
- **坑的本质**：同 Go 版：extraData 必须是 attToBeSigned 的哈希而非原文，原文形式曾被错误接受，此用例防回归
- **置信度**：confirmed

### 9. open-passkey-main/packages/sdk-js/src/index.ts:326
- **代码上下文**：客户端组装 finish 认证负载
- **原文**：
  > In discoverable flow (no userId), use the challenge as the lookup key —
- **链接**：无（可达性：none）
- **坑的本质**：无 userId 的可发现登录服务端无法按用户查凭据，SDK 以 challenge 作查找键，且 userId=challenge 与显式 challenge 字段双发以兼容新旧服务端（tests/e2e/harness.ts:132 注明镜像此 workaround）
- **置信度**：confirmed

### 10. open-passkey-main/CLAUDE.md:129
- **代码上下文**：PRF 扩展与 Vault 密钥派生文档
- **原文**：
  > There is no workaround. This is a fundamental WebAuthn constraint: PRF evaluation parameters are inputs to the ceremony
- **链接**：无（可达性：none）
- **坑的本质**：可发现认证（无 userId）下服务端无法在仪式前放入每凭据 PRF 盐，PRF 输出必为 undefined、Vault 不可用；userHandle 虽揭示 userId 但为时已晚，WebAuthn 规范层面无解
- **置信度**：confirmed

### 11. open-passkey-main/CLAUDE.md:157
- **代码上下文**：core-py CI 的 liboqs 依赖说明
- **原文**：
  > The wrapper's auto-install is broken (tries to clone a non-existent tag), so CI builds liboqs from source
- **链接**：无（可达性：none）
- **坑的本质**：liboqs-python 自动安装损坏（clone 不存在的 tag），CI 只能从源码按固定 commit 构建；升级 pyproject.toml 中版本时须同步改 ci.yml 的 SHA 与缓存键
- **置信度**：confirmed

### 12. open-passkey-main/packages/server-go/passkey.go:147
- **代码上下文**：BeginRegistration 处理器的入参文档
- **原文**：
  > In backendless mode where emails are passed as UserID, the value is base64url-encoded
- **链接**：无（可达性：none）
- **坑的本质**：以邮箱作 userId 时，该值会 base64url 编码后经未加密 userHandle 字段在每次认证仪式中暴露（PII 泄漏）；应改为不透明 UUID 映射
- **置信度**：confirmed

### 13. Authnkey-main/app/src/main/java/pl/lebihan/authnkey/PinProtocol.kt:177
- **代码上下文**：CTAP2.1 带权限取 PIN token 协议
- **原文**：
  > Fallback to basic method if authenticator doesn't support permissions
- **链接**：无（可达性：none）
- **坑的本质**：带权限的 getPinUvAuthToken 命令被 8 种 CTAP 错误码（INVALID_COMMAND 等）拒绝即降级到无权限基本取 token，兼容不支持 permissions 的旧认证器；:202 处对意外异常同样降级
- **置信度**：confirmed

### 14. Authnkey-main/app/src/main/java/pl/lebihan/authnkey/MainActivity.kt:317
- **代码上下文**：NFC/USB 传输重连处理
- **原文**：
  > Close old transport (NFC tags can't be reused after moving away)
- **链接**：无（可达性：none）
- **坑的本质**：Android NFC Tag 对象在手机移开后再靠近不可复用，重连前必须关闭旧 transport 重建会话，否则后续 CTAP 通信失败
- **置信度**：confirmed

### 15. open-passkey-main/CLAUDE.md:83
- **代码上下文**：ML-DSA-65 各语言验签调用文档
- **原文**：
  > ml_dsa65.verify(signature, message, publicKey) ... (note: signature-first argument order)
- **链接**：无（可达性：none）
- **坑的本质**：@noble/post-quantum 的 verify 参数序为签名在前，与常见「消息在前」直觉相反，跨语言移植时易写反
- **置信度**：suspected

## 二、对照行（15 条）

### 1. AuthResponse.kt:30 → PasskeyAssertionPayload.kt:77
- **坑的本质**：GCM 回传 clientDataHash 时 clientDataJSON 只允许空串/合法 JSON/"<placeholder>" 变体，否则远程(hybrid)认证失败；本地认证却不受影响，原因注释自承不明
- **触发条件**：Credential Manager 传入 clientDataHash 且走远程/hybrid 传输
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionPayload.kt:77`
- **对照情况**：功能相似：同为 CM provider 组装断言响应，特权调用方下发 clientDataHash 时自建 clientDataJSON 并回传（:77 注释「特权调用方自带 clientDataJSON：直接对其摘要签名；否则自建 JSON 取 SHA-256」，providedClientDataHash 链路自 PasskeyAssertionRequest.kt:119 取 option.clientDataHash）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：复核注：触发条件「走远程/hybrid 传输」在本仓不成立——docs/RESOLVED_LOG.md:437（§342 / ISSUE-P3-338 闭环）记载本仓 permission.BLUETOOTH 声明数为 0、transports 已降为单值 [internal]，无 hybrid 面，故该坑无触发路径

### 2. Validation.kt:38 → DomainMatcher.kt:123 ⚠ risk=unclear
- **坑的本质**：按平台契约不得自校验 rpId↔origin（系统 Credential Manager 已做并明确要求不做），自行校验属重复且违背平台预期
- **触发条件**：实现 onGetCredential 校验逻辑时想顺手校验 rpId
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/DomainMatcher.kt:123`
- **对照情况**：同类模式：主项目在创建（DomainMatcher.isRpIdTrustedForCreation，DomainMatcher.kt:123-133）与浏览器断言候选（CredentialResponseAssembler.resolvePasskeyTargetRpId，CredentialResponseAssembler.kt:211-226、不匹配即拒绝呈现于 :224）两条路径都自校验 rpId↔origin，比 fenris「平台已校验、提供方不校验」更严
- **是否有同样风险**：**unclear**
- **建议**：（待用户裁决后确定，见需补充信息）
- **需补充信息**：主项目自校验在 https web origin 下与 WebAuthn 规范等价（rp.id 须为 origin 主机的点号边界后缀），不会误拒平台放行的合法请求；唯一行为分歧是 localhost 来源（http://localhost 与 https://localhost 同为单标签均被拒）——fenris 显式放行 http（Validation.kt:33 与 :91-92 isHttpLocalhost），而主项目 isBrowserOrigin 只认 https（CallingOriginResolver.kt:176-177），localhost 走 isDomainMatch→PublicSuffixList.isRegistrableDomain 被单标签拒绝（判定计算在 DomainMatcher.kt:98 与 PublicSuffixList.kt:92-100，创建路径分支在 DomainMatcher.kt:128-132，断言路径在 CredentialResponseAssembler.kt:224），且该拒绝已被单测显式锁定（DomainMatcherTest.kt:39、PublicSuffixListTest.kt:67/:110）——即现状是测试在案的既定口径而非疏漏。仍需用户确认：①产品是否需要支持浏览器委派的 localhost（本地开发）来源通行密钥注册/断言（Chrome 等浏览器对 localhost 有放行先例）；若确认无需支持，现状为更严的既定口径、无需改动

### 3. Constants.kt:29 → KeystoreManager.kt:18
- **坑的本质**：AndroidKeyStore 无法对对称密钥做按单次操作认证（一次用户操作含多次 keymaster 调用），只能设 5 秒认证有效期窗口
- **触发条件**：想用 setUserAuthenticationRequired 做逐操作认证
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/KeystoreManager.kt:18`
- **对照情况**：同类模式：同用 AndroidKeyStore 对称密钥 + 用户认证绑定（快速解锁封印/自动填充放行绑定）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 4. ConfirmImportSheet.kt:48 → SecureDialog.kt:119
- **坑的本质**：Compose 底部 sheet 无法在用户触屏期间否决 dismissal：触摸列表即禁用滑动关闭，松手后必须延时数毫秒再恢复，否则误关丢选择
- **触发条件**：用户在列表上滚动时顺手下滑 sheet
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/SecureDialog.kt:119`
- **对照情况**：无直接对应：主项目确认类交互走 Dialog 而非底部 sheet
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 5. SetupViewModel.kt:19 → MainActivity.kt:90
- **坑的本质**：初始化阶段 config 尚未 ready，currentConfig()（await ready）会永久挂起；须用 config.first() 读未就绪值（restoreVault 同型注释 :46）
- **触发条件**：建库/恢复库时 config 还未完成初始化
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/MainActivity.kt:90`
- **对照情况**：同类模式：主项目也有对状态流的 first{} 等待（databaseSession.state.first { isUnlockedState }），但语义与触发条件不同
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 6. SetupViewModel.kt:39 → VaultImportController.kt:200
- **坑的本质**：刻意不判空、放任 NPE/ISE 抛出，确保 openInputStream 等失败统一走 catch 错误处理不旁路（同型注释重复于 SettingsViewModel.kt:74、ImportFromFileSheet.kt:46）
- **触发条件**：openInputStream 返回 null 或解码器索引缺省
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/importer/VaultImportController.kt:200`
- **对照情况**：功能相似：多处 contentResolver.openInputStream 消费点，但主项目选择显式判空并归一为类型化失败
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 7. tpm_attestation_test.go:253 → 无直接对应
- **坑的本质**：TPM 证明实现曾把 attToBeSigned 原文（authData||clientDataHash）直接放进 certInfo.extraData；正确实现必须放其 SHA-256 哈希，测试锁定防回归（core-ts 同型测试同注释）
- **触发条件**：extraData 未做哈希而存原文
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目不实现 TPM 证明验证/生成
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 8. tpm-android.test.ts:341 → 无直接对应
- **坑的本质**：同 Go 版：extraData 必须是 attToBeSigned 的哈希而非原文，原文形式曾被错误接受，此用例防回归
- **触发条件**：certInfo.extraData 存原文未哈希
- **主项目对应位置**：无直接对应
- **对照情况**：无：同上，主项目无 TPM 证明面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 9. sdk-js index.ts:326 → CredentialResponseAssembler.kt:222
- **坑的本质**：无 userId 的可发现登录服务端无法按用户查凭据，SDK 以 challenge 作查找键，且 userId=challenge 与显式 challenge 字段双发以兼容新旧服务端（tests/e2e/harness.ts:132 注明镜像此 workaround）
- **触发条件**：可发现（usernameless）认证且服务端版本不一
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt:222`
- **对照情况**：无此面：该坑在 RP/SDK 服务端按 userId 查凭据；主项目是认证器侧（provider），候选按 rpId/包名严格匹配，challenge 只参与签名不参与查找
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 10. CLAUDE.md:129 → PasskeyAssertionPayload.kt:129
- **坑的本质**：可发现认证（无 userId）下服务端无法在仪式前放入每凭据 PRF 盐，PRF 输出必为 undefined、Vault 不可用；userHandle 虽揭示 userId 但为时已晚，WebAuthn 规范层面无解
- **触发条件**：无 userId 的 discoverable 登录且无全局静态 PRF 盐
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionPayload.kt:129`
- **对照情况**：无此面：该坑是 RP 服务端在仪式前无法投放每凭据 PRF 盐；主项目为 provider 侧，仅在 RP 显式携带 eval 时计算 PRF
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 11. CLAUDE.md:157 → 无直接对应
- **坑的本质**：liboqs-python 自动安装损坏（clone 不存在的 tag），CI 只能从源码按固定 commit 构建；升级 pyproject.toml 中版本时须同步改 ci.yml 的 SHA 与缓存键
- **触发条件**：升级 liboqs-python 依赖版本
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 liboqs / 后量子签名依赖（本次 grep liboqs/mldsa/dilithium 于 *.kt/*.rs/*.toml/*.kts 零命中）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 12. passkey.go:147 → PasskeyCreateActivity.kt:38
- **坑的本质**：以邮箱作 userId 时，该值会 base64url 编码后经未加密 userHandle 字段在每次认证仪式中暴露（PII 泄漏）；应改为不透明 UUID 映射
- **触发条件**：backendless 模式直接用邮箱当 userId 注册
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt:38`
- **对照情况**：无此面：该坑在 RP 服务端以邮箱作 userId；主项目为认证器侧，userHandle 原样采用 RP 下发的 user.id（:38 注释「原样采用 RP 下发的 user.id 作为 userHandle（此前自造随机值，破坏无用户名登录）」、:336；断言侧 PasskeyAssertionPayload.kt:113 原样回传），不自行由邮箱/用户名派生
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 13. PinProtocol.kt:177 → 无直接对应
- **坑的本质**：带权限的 getPinUvAuthToken 命令被 8 种 CTAP 错误码（INVALID_COMMAND 等）拒绝即降级到无权限基本取 token，兼容不支持 permissions 的旧认证器；:202 处对意外异常同样降级
- **触发条件**：认证器返回 INVALID_COMMAND/UNSUPPORTED_OPTION 等错误
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目是平台认证器（Credential Provider），不实现 CTAP2 PIN/UV 协议（grep ctap/pinUv 仅命中 CBOR 键名注释与「声明无 hybrid 能力」的 ISSUE-P3-338 记录，WebAuthnJsonKeys.kt:106）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 14. MainActivity.kt:317 → 无直接对应
- **坑的本质**：Android NFC Tag 对象在手机移开后再靠近不可复用，重连前必须关闭旧 transport 重建会话，否则后续 CTAP 通信失败
- **触发条件**：安全钥匙移开后又重新贴上（NFC 重连）
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 NFC/外接安全钥匙传输代码（grep NfcAdapter/nfc 于五模块源码零命中）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 15. CLAUDE.md:83 → 无直接对应
- **坑的本质**：@noble/post-quantum 的 verify 参数序为签名在前，与常见「消息在前」直觉相反，跨语言移植时易写反
- **触发条件**：TypeScript 侧接入 ml_dsa65 验签
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 ML-DSA / @noble 依赖（grep mldsa/ml-dsa/dilithium 零命中）；断言签名算法为 ES256/Ed25519/RS256，经自有加密引擎（PasskeyAssertionPayload.kt:94、:173-177）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

## 三、复核记录（独立复核结论）

row2（fenris Validation.kt:38 → DomainMatcher.kt:123）复核修正：
1. 修正 ourSimilarity 中行号漂移——「CredentialResponseAssembler.resolvePasskeyTargetRpId，:222-232」实为 CredentialResponseAssembler.kt:211-226（不匹配即拒绝呈现的判定在 :224，`if (!DomainMatcher.isDomainMatch(targetRpId, …)) return null`）。
2. 修正 needInfo 中指向——「localhost 走 isRegistrableDomain 分支被单标签拒绝（DomainMatcher.kt:128-132）」中 :128-132 只是 isRpIdTrustedForCreation 的分支选择处，单标签拒绝的实际计算在 isDomainMatch（DomainMatcher.kt:98）→ PublicSuffixList.isRegistrableDomain（PublicSuffixList.kt:92-100，localhost 单标签按默认规则 `*` 为公共后缀且无上层标签 ⇒ false），断言路径同样在 CredentialResponseAssembler.kt:224 拒绝。
3. 补入新证据——该拒绝已被单测显式锁定（app/src/test/java/com/keepasskey/app/passkey/DomainMatcherTest.kt:39 `assertFalse(isDomainMatch("localhost","localhost"))`、PublicSuffixListTest.kt:67 `isRegistrableDomain("localhost")==false`、PublicSuffixListTest.kt:110 `registrableDomain("localhost")==null`），说明拒绝 localhost 是既有既定口径而非疏漏，needInfo 第②问（是否有真实实例被拒）权重据此下调，但「产品是否需要支持浏览器委派 localhost 来源」仍待用户裁决，维持 risk=unclear。
4. 补正分歧范围——不止 http://localhost，https://localhost（同为单标签）同样被拒；参考侧引证核实属实（fenris Validation.kt:38-39 注释「intentionally do NOT validate rpId against origin」、:33 与 :91-92 isHttpLocalhost 放行 http://localhost）。
