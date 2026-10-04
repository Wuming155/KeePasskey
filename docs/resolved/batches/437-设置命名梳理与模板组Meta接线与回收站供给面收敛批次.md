# §437 设置命名梳理与模板组 Meta 接线与回收站供给面收敛批次（2026-10）

**条目**：`ISSUE-P3-468` / `ISSUE-P3-469` / `ISSUE-P3-470` **三条整条闭环**（P3 4 → **1**）
**来源**：用户 2026-10-03 设置主页走查反馈（468）+ 官方格式/对拍调研（469 / 470）；三条均已由用户逐项拍板

## 437.0 任务口径

1. **468 为纯主页文案 / 归属层级调整**（用户裁决原文见 `ACTIVE_ISSUES.md` 条目；C 项**否决**理由留痕于 437.1）；
2. **469 分写侧（小）/ 读侧（中）两步**，写侧须产出官方实现端到端对拍证据（`AGENTS.md` 规则 8）；
3. **470 只动供给面判定口径**，删除 / 还原 / 清空主流程（官方 `MainForm_Functions` 口径）不动。

## 437.1 `ISSUE-P3-468`：设置主页命名与归属梳理

按用户逐项裁决实施（A / B / D / E 采纳，C 否决，F 采纳）：

| 项 | 改动 | 落点 |
|---|---|---|
| A | 条目「自动填充」→「**系统自动填充**」；组 id `settings_cat_preferences` → `settings_cat_autofill`（组名「自动填充」不变） | `strings.xml`(zh/en) + `SettingsGroups.kt` |
| B | 条目「密码库与加密」→「**数据库与加密**」 | `settings_database`(zh；en 原为 Database & Encryption，不变) |
| D | 组「安全与审计」→「**安全**」；条目「健康度检查与审计」→「**密码健康检查**」（泄露比对留在副标题） | `settings_cat_security` / `settings_health` |
| E | 条目「界面偏好」→「**字体与动效**」；副标题改「密码字段等宽字体、界面动效」 | `settings_cat_interface` / `settings_interface_sub` |
| F | release 下「开发者调试」隐藏时不渲染「系统」组标题，「关于」独立成卡 | `SettingsGroupSpec.headerRes` 放宽为 `Int?`；`systemGroup` 按 `BuildConfig.DEBUG` 取 `null` |
| C（**否决**） | 「更改主密码」**维持**挂在「安全」组不动 | 用户原话「无必要」——属取舍非缺陷，不回退既有归属 |

- **C 项否决理由（留痕）**：整改面本拟把「更改主密码」并入「数据库与加密」（官方 KeePass / KeePassXC 把改主密钥放在数据库设置内），
  但用户 2026-10-03 明确否决（原话「无必要」）。实施时**保持既有归属与顺序**，仅执行上表 A/B/D/E/F。
- **与 `ISSUE-P3-444`/§397 的一致性（重要）**：`settings_health` 与 `settings_cat_interface` 同时是二级页
  `HealthCheckScreen` / `InterfaceSettingsScreen` 的标题资源（§397 立规「页首标题与入口行**同一字符串资源**」）。
  本条改名使二级页标题**随之更新**，正是该不变量的应有结果——**未改任何二级页代码 / 路由 / 结构**（AC③ 满足）。
- **AC③ 复核**：除 F 项条件渲染外，`SettingsGroups.kt` 仅改 `headerRes` 取值与注释；分组顺序、行顺序、图标、其余文案逐项未动。

## 437.2 `ISSUE-P3-469`：模板组接入官方 `EntryTemplatesGroup`

### 写侧（`VaultExportCoordinator.installEntryTemplates`）

- 安装模板组后把组 UUID 写入 Meta `EntryTemplatesGroup`（+ `EntryTemplatesGroupChanged` 时间戳）——该字段是三家官方实现识别模板组的唯一依据；
- **幂等两形态**：模板组已在（Meta 命中或同名分组）且 Meta 已登记 ⇒ 失败不重复安装；
  模板组已在但 Meta 未登记（存量库 / 第三方库）⇒ **回填** Meta 后视为安装完成（`installEntryTemplates` 的存量库分支）；
- **本类不再依赖 Android `Context`**：唯一曾用之处的失败文案改走 `StringsProvider` 通道（`context.getString` → `strings.get`），
  使协调器可在宿主 JVM 用例中直接驱动安装落盘对拍——这是取得写侧对拍证据的必要前置。

### 读侧（`VaultGroup.isTemplate` + 列表投影）

- `VaultGroup` 新增 `isTemplate`，由 `VaultGroupCoordinator.groupsFlow` 按 `db.entryTemplatesGroup` 命中标注（与 `isRecycleBin` 同构）；
- `VaultListProjection.buildTemplateEntries` 改为**优先取官方模板组**（`isTemplate`），读不到才**回落**按固定组名「模板」扫描——
  第三方库的官方模板组通常不叫「模板」，只按名扫描会漏掉它；回落路径逐字保留，既有交互零回归。

## 437.3 `ISSUE-P3-470`：回收站供给面判定收敛

- `recycleBinGroupsOf` 拆出私有 `recycleBinRootsOf` 作为供给面判定**唯一收口**：
  - `recycleBinEnabled = false` ⇒ **空表**（不凭任何依据判「已删」——残留同名组 / 历史 UUID 命中组里的条目都是活条目）；
  - `= true` ⇒ 以 `RecycleBinUuid` 命中为准；UUID **缺失**时才允许组名（`回收站` / `Recycle Bin`）兜底。
- **边界（有意）**：`primaryRecycleBinRoot`（删除 / 还原 / 清空的**定位**）口径**不动**——删除主流程对 `recycleBinEnabled=false`
  已在各分支显式走物理删除，不依赖本判定；两者输入与用途不同（前者看条目归属，后者要落地移动），KDoc 已写明分工。
- **既有用例前提修正（如实留痕）**：`RecycleBinSubtreeExclusionTest`「Meta 未回填但组名叫回收站时同样排除」原用
  `recycleBinEnabled = false`——该前提被本条裁决**推翻**（关闭回收站时不得据任何依据判已删）。该用例改 `enabled = true`
  （AC① 明确允许「UUID 缺失时按名兜底」，正是「懒创建未写 UUID 的第三方库」形态），断言不变；另**新增**「Meta 关闭回收站 + 残留同名组」用例。

## 437.4 验证证据

- 全量 JVM 单测（`test --rerun-tasks --max-workers=1` 强制真实执行）：`xml=516 tests=3360 failures=0 errors=0 skipped=13`
  （§436 基线 3351；本批新增 9 例：`TemplateGroupInteropProbeTest` 3 + `VaultGroupTemplateFlagTest` 2 +
  `VaultListProjectionFiltersTest` 3（模板供给三项）+ `RecycleBinSubtreeExclusionTest` 1）。
- **写侧互操作对拍（规则 8）**：由 `TemplateGroupInteropProbeTest` 走真实安装链路落盘
  `app/build/interop-probe/keepasskey-template-group-probe.kdbx`，经
  `python tools/template-group-interop/verify_template_group.py` 双实现核验（原样读数）：

```
keepassxc-cli 解锁成功；<EntryTemplatesGroup> = 'v1asc5B7frDX+N0hqFaXqA=='
pykeepass 解锁成功；<EntryTemplatesGroup> = 'v1asc5B7frDX+N0hqFaXqA=='
命中分组：name='模板' 直接条目数=5
✓ 对拍通过：官方 CLI 与 pykeepass 双实现读数一致，模板组成立
```

  （工具链：keepassxc-cli 2.7.12 / pykeepass 4.2.0；产物 SHA-256 与复现命令见 `build/interop-probe/TEMPLATE_GROUP_PROBE.md`）
- 截图测试包装编译门禁：`:app:compileDebugScreenshotTestKotlin --rerun` BUILD SUCCESSFUL。
- `assembleDebug` BUILD SUCCESSFUL；装机走查前置读数（§434）见 437.5。
- 门禁读数单点采集（§308，原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 435 份；分册登记 437 条；全量索引 437 条；最大 §437）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 574 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=9  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

## 437.5 如实声明（未美化）

1. **真机视觉走查待用户**：三条均未做人工视觉走查。已按 §434 前置流程构建并 `adb install -r` 到
   `com.keepasskey.debug`（与正式版 `com.keepasskey` 独立沙箱，不影响用户库数据）：
   首次安装（rebase 前基线上）`check_installed_build.py --expect-symbol markTemplatesGroup` = OK（APK 22:30:49 / 设备 22:31:09）；
   本批 rebase 到 `main`（提交 `4e8e3f2b`）后**按最终代码重建并重装**，读数 = OK
   （APK 2026-10-04 08:38:59 / 设备 08:39:04，符号在包内）——设备上当前跑的即推送提交对应包。
   走查回执若提出新观感问题，按惯例另批整改。
2. **F 项的 release 分支无 JVM 用例覆盖**：`headerRes = null` 只在 `BuildConfig.DEBUG = false` 时生效，
   单测恒跑 debug 变体，故「release 下不渲染『系统』标题」仅由类型与代码判据成立，未由测试锁定——
   本批未新造 release 变体测试（成本不抵收益）。
3. **469 写侧的存量库回填只在「安装模板」动作触发**：普通保存路径（`persistSession`）**不**隐式回填 Meta，
   以免在保存热路径引入隐藏的元数据写入。存量库未点「安装模板」时其 Meta 仍为空——按名兜底使其在
   **读侧**仍可被识别，故不构成功能缺口。
4. **过程缺陷（如实留痕）**：`RealVaultRepository.kt` 因本批新增 2 行注释达 **502 行越入 tier1**
   （`count_line_tiers` 恒 0 闸门真实命中，非侥幸拦截）——将该说明移入 `VaultExportCoordinator` 类 KDoc，
   文件回 **500 行**（tier2；`tier2` 计数 35 = budget 35 顶格，未新增文件）。

## 437.6 闭环状态

`ISSUE-P3-468` / `ISSUE-P3-469` / `ISSUE-P3-470` 三条整条闭环，P3 由 4 项降为 1 项（余 `ISSUE-P3-471`）。
