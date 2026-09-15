<a id="s77"></a>
## §77 Popup 窗口敏感内容面盘点批次（2026-09-15）：ISSUE-P3-79

> **本批次缘起**：`ISSUE-P3-79` 指出 Compose 的 Popup 系窗口（`DropdownMenu` /
> `ExposedDropdownMenuBox`）由 `PopupLayout` 承载、**不实现** `DialogWindowProvider`，
> 故既有的 [SecureDialog](../../../app/src/main/java/com/keepasskey/app/security/SecureDialog.kt)
> 对其恒为 fail-safe 空操作；条目要求**盘点**全部 Popup 调用点的敏感内容面并按需接线。
> 复核报告对此项的定版结论为 **NOT REPRODUCIBLE**（7 处菜单项全为静态文案），本批**独立复核该结论**后收口。

### 77.1 盘点结果（2026-09-15 逐点直读源码核实，**无需接线**）

**全仓 Popup 调用点恰好 4 处、菜单项共 8 个**（另经全仓检索确认**无**其它
`Popup(` / `TooltipBox(` / `ModalBottomSheet(` 调用点）：

| # | 文件 | 菜单项（全部为静态动作文案 / provider 名称） |
|:--:|---|---|
| 1 | `ui/screens/vault/VaultListTopBars.kt:224` | 彻底退出应用（1 项） |
| 2 | `ui/screens/vault/VaultGroupRow.kt:120` | 重命名 / 更改图标 / 删除分组（3 项） |
| 3 | `ui/screens/detail/EntryDetailTopBar.kt:113` | 移动到分组 / 删除条目 / 删除共享图标（3 项） |
| 4 | `ui/screens/settings/subscreens/CloudSyncComponents.kt:125+150` | provider 名称与描述（1 项 × provider 数） |

**结论**：8 个菜单项**全部为静态文案**，**无任何口令 / TOTP / 密钥 / 用户数据插值**
⇒ 按条目 AC③「无敏感内容的调用点留痕说明『无需接线』，避免『全量加 flag』的过度改动」**不接线**。
该结论与复核报告一致，且由本批**独立复核**（非引用报告结论）。

### 77.2 交付物：把「今天无需接线」变成可执行守卫

「今天无需」不等于「永远无需」——一旦某菜单开始渲染凭据，或新增第 5 个 Popup 调用点，
本次结论即失效。故新增
`app/src/test/java/com/keepasskey/app/security/PopupSecureFlagInventoryTest.kt`（**3 例**）：

| 用例 | 守护内容 |
|---|---|
| `Popup 调用点清单与已核实清单完全一致` | 全仓扫描 `DropdownMenu(` / `ExposedDropdownMenuBox(`，其**文件集合必须等于**上表 4 个文件——新增（未复核）或删除（清单失效）都报红，强制重新盘点 |
| `任何菜单块内都不得出现凭据类记号` | 逐个抽取 `DropdownMenu(...)` 的**完整调用文本**（参数表括号配平 **＋** 其后的尾随 lambda 花括号配平），断言块内无 `readString(` / `password` / `totp` / `otpauth` / `secret` / `userName` 等记号；命中即提示必须补 `PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)` |
| `菜单块抽取必须真的覆盖到菜单项（防空扫…）` | **守住本用例自身的有效性**：断言抽取到 ≥4 个菜单块、且其中确有 `DropdownMenuItem(`、且**总数恰为 8**（与清单一致）——防止抽取逻辑退化后「扫描通过但其实什么都没扫到」的**假绿** |

同时把盘点结论写入 `SecureDialog` 的 KDoc「未能覆盖」一节，并写明**接线条件**（菜单一旦承载
凭据类内容即必须接线）。

### 77.3 过程缺陷（如实留痕）

1. **首版抽取逻辑必然空扫（已在本批内捕获并修正）**：初版 `menuBlocks` 用**单一深度计数器**
   同时统计 `(` 与 `{`，而 `DropdownMenu(expanded = …, onDismissRequest = { … }) { 菜单项 }`
   的参数表内含一个 `{…}` lambda——它先把深度归零，使抽取**在参数表结束处截断**，
   菜单项一个都扫不到。**若无第 3 条用例，本批会以「扫描通过」收尾，实际什么都没扫**。
   ⇒ 纪律：**任何静态扫描型守卫都必须自带「非空扫」断言**（本次为「抽取到的菜单项数 == 8」）；
   该形态与 §74 的「偶发红只报 `cleared=false`」同源——**证据为零时不得判 PASS**。
2. **复核报告的结论必须独立复核后再采信**：报告判 P3-79 为 NOT REPRODUCIBLE，本批仍**逐点直读**
   4 个调用点的全部菜单项文本后才登记「无需接线」，并在 KDoc 中写明依据——
   避免「因为报告说没事所以没事」的传递式结论。

### 77.4 验证证据（2026-09-15）

- **新用例**：`.\gradlew.bat :app:testDebugUnitTest --tests "*PopupSecureFlagInventoryTest*"` →
  `BUILD SUCCESSFUL`，`tests=3 / failures=0 / errors=0`（含防空扫断言，故「无敏感记号」为**有效**结论）。
- **全量单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL`，
  计数见 §77.5。
- **发布产物**：`.\gradlew.bat assembleRelease --rerun-tasks` → `BUILD SUCCESSFUL`，产物
  `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`（已配置 release 签名）。

### 77.5 基线变动

| 项 | 变动 |
|---|---|
| app 模块单测 | 1005 → **1008**（+3） |
| 全量单测 | 1802 → **1805** |
