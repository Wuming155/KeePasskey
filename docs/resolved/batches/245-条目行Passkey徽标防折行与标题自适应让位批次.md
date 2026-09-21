# §245 条目行 Passkey 徽标防折行与标题自适应让位批次

> 对应条目：`ISSUE-P2-242`（**整条结案**）
> 日期：2026-09-21（接 §244）
> 涉及模块：`app`
> 真机环境：Redmi 4X（santoni，`lineage_Mi8937_4_19`），**Android 17 / API 37**

---

## 1. 缺陷现象与真机物证

用户在真机上核对保存的通行密钥时，发现条目列表存在严重的排版挤压畸变（用户真机实拍照片确证）：

1. **中长标题条目**（`cm-probe-user@example.com`，25 字符）：
   - 右侧的 `PasskeyBadge` 文本被挤压成单字竖排：`P`、`a`、`s`、`s`、`k`、`e`、`y` 一行一个字母垂直垂落，横穿在钥匙图标右侧；
2. **超长标题条目**（`fake-passkey-probe@example.com@www.passkeys.io`，46 字符）：
   - 标题文本占满了整个屏幕行宽度，右侧的 `PasskeyBadge` 完全被挤出屏幕而**彻底消失**，导致用户在列表页误以为该条目未成功保存通行密钥。

---

## 2. 根因分析

1. **调用方布局缺陷**（`VaultEntryRowLayouts.kt:166-181`）：
   - 在 `StandardEntryLayout` 中，标题位于 `Column` 下的 `Row(verticalAlignment = Alignment.CenterVertically)` 内；
   - 标题 `Text(entry.title)` 仅设置了 `maxLines = 1, overflow = TextOverflow.Ellipsis`，**漏掉了 `modifier = Modifier.weight(1f, fill = false)`** 约束（对比第 199 行副标题处的用户名即正确配置了 weight）；
   - 在非加权 `Row` 测量规则下，无 weight 的 Text 优先吃满自身所需宽度甚至整行宽度，只给后面的 `PasskeyBadge` 留下微小甚至 0 宽的空间；
2. **组件自身防线缺失**（`SecurityBadge.kt:54`）：
   - `PasskeyBadge` 内部的 `Text(text = "Passkey")` 缺少 `maxLines = 1` 与 `softWrap = false`，当可用宽度极窄时，发生软折行（softWrap）导致 7 个字母垂直竖排；
3. **详情页同类隐患**（`EntryDetailComponents.kt:100`）：
   - 详情页 Hero 区域的标题所在 `Column` 未加 weight，且标题 Text 同样未加 `weight(1f, fill = false)`，长标题同样存在挤压风险。

---

## 3. 整改内容

| 文件 | 整改内容 | 效果 |
|---|---|---|
| `SecurityBadge.kt` | `PasskeyBadge` 内部 `Text` 显式声明 `maxLines = 1, softWrap = false`。 | 锁定胶囊徽标内部文字绝对单行水平排布，杜绝垂直挤压变形。 |
| `VaultEntryRowLayouts.kt` | `StandardEntryLayout` 中标题 `Text` 增加 `modifier = Modifier.weight(1f, fill = false)`。 | 标题自适应测量，过长时向右侧 `PasskeyBadge` 预留完整宽度并在尾部以省略号截断。 |
| `EntryDetailComponents.kt` | `EntryHeaderSection` 中标题 `Text` 增加 `modifier = Modifier.weight(1f, fill = false)`，父 `Column` 增加 `Modifier.weight(1f)`。 | 详情页同样具备让位保护。 |
| `PasskeyBadgeLayoutWiringTest.kt` | 新增自动化单元测试（3 项断言）。 | 静态源码比对锁定上述三处布局契约，防止后续重构丢失约束。 |

---

## 4. 真机验证（可复现）

1. 执行 `assembleRelease` 构建 release 包并 `adb install -r` 覆盖安装到 Redmi 4X；
2. 解锁测试密码库，在包含 `fake-passkey-probe@example.com@www.passkeys.io` 与 `cm-probe-user@example.com` 的列表页读取 Android CLI 无障碍树坐标（bounds）：
   - 第一条标题 bounds: `[137,204][431,234]`（截断让位，宽 294px）；
   - 第一条 Passkey 徽标文本 bounds: `[484,207][562,231]`（**宽 78px，高 24px，标准正常水平单行胶囊**）；
   - 第二条标题 bounds: `[137,338][431,368]`（截断让位，宽 294px）；
   - 第二条 Passkey 徽标文本 bounds: `[484,341][562,365]`（**宽 78px，高 24px，标准正常水平单行胶囊**）；
3. 照片物证中出现的一长串竖排字符与徽标完全消失问题彻底消除，两项条目均水平右侧对齐完整呈现 `[Passkey]`。

---

## 5. 工程验证

- 针对性单测 `PasskeyBadgeLayoutWiringTest` 3 例全部通过；
- 全仓单测 `.\gradlew.bat test --rerun-tasks --max-workers=1` 验证通过；
- `python tools/doc/check_md_links.py` 与 `python tools/doc/check_resolved_index_sync.py` 自检通过；
- 模块行数分档 `tier1=2 / tier2=29 / functions_ge_100=3` 持平。

---

## 6. 如实声明

1. 本批修改仅涉及 UI 排版与自适应权重约束，不改动任何数据模型、加密密钥或存储序列化逻辑；
2. 截图测试因真机开启 `FLAG_SECURE` 防截屏保护而全黑，本批真机验证完全基于 Android CLI 的无障碍控件树与真实 bounds 物理几何数据。
