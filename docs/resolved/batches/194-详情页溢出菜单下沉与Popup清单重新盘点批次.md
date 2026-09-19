# §194 详情页溢出菜单下沉批次（`ISSUE-P3-188` 第 1 目：`EntryDetailTopBar` 151 → **84 行出表**、≥100 项 20 → 19、待拆 17）

> **起因**：§193 后表内最大非豁免项 `EntryDetailTopBar`（151 行）几乎全是 `TopAppBar.actions`，
> 其中**溢出菜单一段独占 75 行**（`Box + IconButton + DropdownMenu` 三项）。
> 这一项与 §191~§193 不同：它**跨过一个安全清单锁**——`PopupSecureFlagInventoryTest`（ISSUE-P3-79）。

---

## 1. 改动

| 对象 | 行数 | 说明 |
|---|---|---|
| `detail/EntryDetailTopBar.kt` | 267 → **191** | 函数 **151 → 84 出表**；`showOverflowMenu` 状态随段落迁入新文件（其唯一使用者就是该菜单）；剪除 9 条失效 import（其中 `ui.unit.dp` 是**本批之前就已未被使用**的遗留 import，一并清掉） |
| `detail/EntryDetailTopBarSections.kt` | **新建 117 行** | `EntryDetailOverflowMenu(showsCustomIconDelete, onRequestMoveEntry, onRequestDeleteEntry, onRequestDeleteCustomIcon)` |

≥100 行函数 **20 → 19**（`files_scanned` 508 → 509）⇒ 扣除 PD-11 豁免的两个 NavGraph ⇒ **待拆 17**。
分档不变（第一档 2、第二档 28）。

## 2. 本批的关键不同：搬的是**被清单锁点名的调用点**

`PopupSecureFlagInventoryTest` 的三条判据：
1. **清单锁**：全仓含 `DropdownMenu(` / `ExposedDropdownMenuBox(` 的**文件集合**必须与
   `VERIFIED_POPUP_CALL_SITES` **完全相等**（`assertEquals`）；
2. **敏感记号扫描**：逐一抽取每个菜单块，断言块内无 `password` / `totp` / `secret` / `readString(` 等记号；
3. **防空扫**：菜单块数 ≥4 且 `DropdownMenuItem` 计数**必须等于 8**。

把菜单原样搬到新文件后，实际集合变成「旧文件消失 + 新文件出现」⇒ **判据 1 当场报红**。
这正是它该有的反应，也顺带证明该清单锁**不是摆设**（此前它只在被绕过时才响）。

处置是**重新盘点 + 换定位**，而不是消除报红：
- 盘点结论：3 个菜单项全是静态动作文案（移动到分组 / 删除条目 / 删除共享图标），
  与原文件里的内容**逐字相同**（见 §3 的 3 条缺失行），无任何凭据类插值 ⇒ 判定仍是「无需接线」；
- `VERIFIED_POPUP_CALL_SITES` 里 `EntryDetailTopBar.kt` → `EntryDetailTopBarSections.kt`，
  并就地写明「§194 前该菜单在 `EntryDetailTopBar` 内」与复验方式；
- **同步 `SecureDialog` KDoc 的「未能覆盖」一节**（该处清单文字与本测试约定要保持同步）；
- **未动的东西**：判据 2 的记号表、判据 3 的 `8` 与 `≥4`、以及扫描逻辑本身
  （新文件同样被 `walkTopDown()` 覆盖，敏感记号扫描对它照常执行）。
  **不得**用「把清单条目删掉」凑绿——那样判据 1 仍会因实际集合多出新文件而报红，
  而删掉新文件条目更是把该调用点移出盘点范围，等于自废这条守卫。

## 3. 逐字性

`python tools/doc/check_verbatim_move.py /tmp/old_tb.kt <本体> <段落文件>` 报
**`orig_content_kinds=167 / MISSING_KINDS=3 / NEW_ONLY_KINDS=18`**。三条缺失逐条：

| 缺失行 | 新形态 | 是否等价 |
|---|---|---|
| `Box {` | `Box(modifier = modifier) {`（段落组件补了 `modifier` 形参，默认 `Modifier`） | 等价（调用方未传 ⇒ 同义） |
| `if (uiState.entry?.customIconId != null) {` | `if (showsCustomIconDelete) {`，由调用点传 `uiState.entry?.customIconId != null` | 等价（判定式原样上移到调用点） |
| `import androidx.compose.ui.unit.dp` | 无 | 该 import **在本批之前就没有任何使用处**，属顺手清理 |

其余菜单代码（三项的文案资源、`leadingIcon`、`onClick` 里的「先关菜单再回调」顺序、
`showOverflowMenu` 的开合）逐字未改。原 `// ISSUE-P3-48 / P3-51 / P3-02` 三条说明注释一并随行搬移。

## 4. 验证

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | `BUILD SUCCESSFUL`，零 error 零 warning |
| `python tools/doc/logic_lines.py … EntryDetailTopBar` | **84**（原 151）⇒ 出表；逻辑行 4（`if (!uiState.isReadOnly)` 门控，非装配表 ⇒ PD-11 不适用） |
| `python tools/doc/long_functions.py` | `functions_ge_100=19`、`files_scanned=509` |
| `python tools/doc/count_line_tiers.py` | 第一档 2、第二档 **28** |
| `python tools/doc/check_verbatim_move.py …` | `MISSING_KINDS=3`，逐条见 §3 |
| 失效 import 自查 | 本体 9 条已剪（含 1 条历史遗留）；段落文件按实际使用取用，无冗余 |
| `test --rerun-tasks --max-workers=1` | 见 §4.1（**含 `PopupSecureFlagInventoryTest` 三条判据复跑**） |
| `:app:lintDebug` / `:app:compileDebugScreenshotTestKotlin --rerun-tasks` | 见 §4.1 |
| `python tools/doc/check_md_links.py` | `BROKEN_MD_LINKS=0` |
| **未执行** | `cargo test`、`test -DliveSyncTest`、四层 `connectedDebugAndroidTest`（无设备） |

### 4.1 实测数字（跑完后回填）

| 命令 | 结果 |
|---|---|
| `test --rerun-tasks --max-workers=1` | `BUILD SUCCESSFUL in 2m 9s`、`114 actionable tasks: 114 executed`；JVM 单测 **`xml=315 tests=2209 failures=0 errors=0 skipped=13`**（与 §191~§193 同值 ⇒ 本批未增删用例），`Uncaught exception` 命中 **0**。**`PopupSecureFlagInventoryTest` 三条判据全在此轮内通过**（清单锁 + 敏感记号扫描 + 「菜单项 = 8」防空扫） |
| 独立复核清单锁（不跑测试的旁证） | 以同样的记号扫 `app/src/main/java`：**实际调用点 4 份文件与 `VERIFIED_POPUP_CALL_SITES` 集合作为集合相等 = True**，且 `EntryDetailTopBar.kt` 已如实从两侧同时消失 |
| `:app:lintDebug` | `exit=0`、`issue` 元素 = **215**，与 §188~§193 持平 |
| `:app:compileDebugScreenshotTestKotlin --rerun-tasks` | `BUILD SUCCESSFUL in 52s`、`81 actionable tasks: 81 executed` |

**证据边界**：清单锁与记号扫描是**静态源码比对**，能证「菜单里没有凭据类内容」与「调用点都在册」，
**不能**证「Popup 窗口在真机上确实未带 FLAG_SECURE」——后者属设备侧断言面，
且 `ISSUE-P2-192` 已把「Popup 系的 FLAG_SECURE」列为需硬件的余量项。本批不改动接线，只搬位置，
故该残余风险的口径与 §193 前完全一致。

## 5. 过程缺陷：索引行又被截断，而自检脚本看不见它（第三次现形）

本批用脚本往两份索引各加一行时，行尾链接的**目标被截成 `resolved/batches/`**（反引号点名的
`194-….md` 被甩到下一行）——**被砍掉的恰好是 `.md` 文件名**。这形态 §178 犯过一次（§179 修复），
而 §180 为防复发写的 `check_md_links.py` **照样报了 `BROKEN_MD_LINKS=0`**：
其正则要求目标以 `.md` 结尾，而截断后的目标 `resolved/batches/` 不以 `.md` 结尾 ⇒ 整条链接
不被识别，守卫与缺陷共享了同一个盲区（守卫只覆盖它想到的形态）。

处置：
- 两处行尾链接已复原（`grep` 复核两份索引各含一条完整目标路径）；
- 脚本判据扩成两遍扫描——存在性跑「剔围栏 + 剔行内代码」，**截断形态**另跑一遍只剔围栏的
  `TRUNC`（反引号点名 `.md` 的链接文字 + 目标以 `/` 结尾 ⇒ 报红）。
  **没有**采用「目标是目录即判红」：文档地图里 `references/`、`batches/`、`../security/`
  是**故意**的分区目录链接，那样会误报 3 处（实测）；
- 反校（工具改动必须用已知值校准）：坏形态被抓 = True、目录链接不误抓 = True、
  真实文件通过 / 假路径报红 = True，五条判据全过后再复跑全仓 ⇒ `BROKEN_MD_LINKS=0`。

## 6. 下一批

表内余 **17 个待拆**（头部：`PackageBlocklistManageDialog` 131、`VaultListDialogHost` 130、
`GeneratorContent` 121、`EntryDetailScreen` 121、`SyncStatusCard` 119、`EntryDetailDialogHost` 119）；
另有 `ISSUE-P3-195` 待用户择一修法。
