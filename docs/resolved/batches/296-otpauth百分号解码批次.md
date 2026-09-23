<a id="s296"></a>

# §296 otpauth 百分号解码批次

> `ISSUE-P2-289` **整条闭环**（P2 3 → **2 项**）。
> 触发＝用户命题「解决 Active Issues 内的存量问题」，按优先级认领。
> 覆盖面：`ISSUE-P2-289`（本体，core 解析层 + app 诊断透传）。**未触**原生内核 / `*/src/androidTest/**` / `参考项目/` / 构建脚本 ⇒ 无设备侧必跑项。

---

## 1. 条目正文（原样收录）

### `ISSUE-P2-289`：`otpauth://` 不做百分号解码，编码过的种子被静默解成错误密钥（含 label 不解码）

- **核实时间点**：2026-09-23 经解析侧与 Base32 兜底行为逐环核对（覆盖面比首轮更宽：label 亦不解码）。
- **核实方式**：唯一生产入口 `app/.../data/repository/VaultEntryTotpMapping.kt:28`；全仓 `Uri.parse` 无一处用于 otpauth ⇒ 上游未解码；`core/.../otp/TotpKeyUriParser.kt:159-191` 以裸字节切 `&` / `=`，`:242-257` 的 `normalizeBase32` 仅大写去空白去 `=`；`core/.../otp/OtpEngine.kt:146-170` 的 `Base32Decoder.decode` **静默丢弃字母表外字符**（其 KDoc 的「宽容」只声明为兼容存量库展示，未声明百分号后果）⇒ `secret=JBSWY3DPEHPK3PXP%3D%3D` 实得 `JBSWY3DPEHPK3PXP3D3D`，出码永远不对且**无报错**；`issuer` / `account` 里的 `%20` / `%40` 原样入库显示（`:149/199-200`）；`digits=7` 在 `:204` 的 `6..8` 判定后静默回落为 6、未知 `algorithm`（含 RFC 4226 允许的 MD5）在 `:235-239` 回落 SHA1，均无诊断。编辑页 / 扫码 / 1Password PUX（`OnePasswordPuxImporter.kt:193-225`）**共用同一条码路**；测试无 `%3D` 命中，无 `ImportWarnings` 承接。
- **对照**：KeePassDX `OtpEntryFields.kt:152-236` 用 `Uri.getQueryParameter`（框架自带百分号解码）。
- **涉及文件**：`core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt`、`core/src/main/java/com/keepasskey/core/otp/OtpEngine.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt`。
- **验收标准**：AC① 参数值一律先百分号解码再归一（label / account 同修），禁自写切分产生语义分歧；AC② Base32 丢弃非法字符时须**可辨识地失败或告警**（禁静默出错码），且不得放宽既有「兼容存量库展示」的宽容口径——两者作用域须写清；AC③ `digits` / `algorithm` 回落须带诊断信息（禁静默改写），并保留既有钳制语义；AC④ 用例含 `%3D%3D` / `%20` / `digits=7` / `algorithm=md5` 四态，并锁定「非 otpauth 的既有字段路径不回归」；AC⑤ 与 `ISSUE-P3-273`（TOTP 字段映射不落盘）划清边界，两条各自验收。

**开工前前提复核（2026-09-23）**：成立，另有一处条目示例**如实更正**：「`digits=7` 回落为 6」不成立——`:204` 的钳制为 `6..8`，7 在范围内被**保留**（`OtpEngine` 对任意位数出码）。本批 AC④ 的 digits 用例按真实语义锁定（`digits=9` 越界回落 + 告警；`digits=7` 保留 + 无告警）。

---

## 2. 整改

### 2.1 AC①：参数值一律先百分号解码再归一

`TotpKeyUriParser` 新增字节级 `percentDecode`（`%XX` → 字节，非法序列按字面量保留；只走 RFC 3986，`+` 不当空格——URI 规范而非表单编码）：label、secret、period / digits / algorithm / issuer / counter 全部先解码再归一；种子走字节路径（解码副本用毕即擦），非敏感元数据走 `queryValueString`。

### 2.2 AC②：新输入严格、存量展示宽容（作用域分列）

otpauth 路径在 `normalizeBase32` 后加 `isBase32Alphabet` 校验——含字母表外字符即**解析失败**（调用方如实报 URI 非法，禁下游宽容解码静默出错误密钥）；`OtpEngine.Base32Decoder` 的宽容口径 KDoc 明写「仅服务存量库 TOTP 展示，新输入解析不得经此」，两作用域互不外推。

### 2.3 AC③：回落诊断（禁静默改写）

`ParsedTotpConfig` 新增 `warnings`（仅元数据，不参与 equals/hashCode）；`digits` 越界与 `algorithm` 回落各记一条诊断。透传链：`Projection.warnings` → `UiVaultEntry.totpWarnings` → 详情页 `TotpCard` 警告行（`detail_totp_fallback_warning`，中英成对）——用户在查看验证码的同一现场可见。

### 2.4 AC⑤：与 `ISSUE-P3-273` 的边界

`ISSUE-P3-273` 是「TOTP 字段映射 / 默认参数假开关不落盘」（设置层），本条是 otpauth **解析层**——两者无代码交集（本条未动 `ExtendedSettingsStore` / `TotpSettingsScreen` / 字段映射），各自验收。

---

## 3. 验证

- **AC④ 用例**（`TotpKeyUriParserDecodingTest`，6 例）：`%3D%3D`（种子正确、错误形态不再出现）；`%20` / `%40`（label / issuer 正确入库）；`digits=9`（回落 6 + 告警）与 `digits=7`（保留 + 无告警）；`algorithm=md5`（回落 SHA1 + 告警）；非法字符种子可辨识失败；既有路径（纯 Base32 / 标准 URI）不回归。
- **全量回归与门禁**：`.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**，`xml=390 tests=2647 failures=0 errors=0 skipped=13`（`count_test_results.py` 现跑；基线 §295 `tests=2641` ⇒ **+6**）；`:app:compileDebugScreenshotTestKotlin --rerun` 绿。

## 4. 如实声明

- 未跑 `lint` / 真机（无 `androidTest`、无原生面改动）。
- 严格化的行为变化：存量库中**本就含非法字符**的 otpauth 字段（如混入 `-` 的手工录入）此前宽容出码（可能错误），现在解析失败、验证码消失（如实失败，用户须修正字段）——这是 AC② 明文的取舍。
- `OnePasswordPuxImporter` 共用同一条码路自动获得修正；其 `ImportWarnings` 与本条 `warnings` 是两条独立诊断通道（本条走详情页呈现），未做合并。
