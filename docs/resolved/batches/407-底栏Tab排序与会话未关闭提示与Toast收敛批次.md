# 批次 407：底栏 Tab 排序与会话未关闭提示与 Toast 收敛批次（ISSUE-P3-438 / P3-443 / P3-446 三条闭环）

> 日期：2026-10-02。P3 11→**8**。
> 起因：用户指派「整改 P3-443、P3-438、P3-446」（2026-10-02 竞品对比裁决 G2 / G13 / G9 落地批）。

## 1. 原文收录（`ACTIVE_ISSUES.md` 条目正文，原样剪贴）

### ISSUE-P3-438：进程被系统杀死后无「上次会话未正常关闭」提示，用户不解为何需重输主密码

- **核实时间点**：2026-10-02；**核实方式**：① 本仓走查——`strings.xml` 全文无「上次会话 / 异常退出 / 被系统杀死」类文案（仅有编辑面 `unlock_unsaved_edits_discarded`「上次未保存的改动已丢弃」，`strings.xml:399`，语义不同层）；解锁页无对应一次性提示节点；② 竞品对照——[UI与操作体验的竞品对比报告.md](../../references/UI与操作体验的竞品对比报告.md) §3.1/§5-G2：keepass2android `AppKilledInfo` 在下次启动提示「上次被系统杀死」（其架构分析 §8.4）。
- **背景**：自动锁定把进程杀掉（系统回收）与用户主动锁库在 UI 上不可区分——下次打开都落在解锁页，用户易误读为「应用丢了我的会话 / 出了故障」，反而削弱自动锁定本应传达的安全感。
- **验收标准**：
  ① 区分「上次为异常退出（进程被系统回收 / 崩溃）」与「用户主动锁库 / 超时锁定」，仅前者在解锁页展示一次性轻提示（文案如实，不渲染成错误告警）；
  ② 判定依据复用进程生命周期既有信号（如进程级 `AutoLockManager` 状态），不新增持久化敏感数据；
  ③ 提示文案走 `strings.xml` 双语；有 `@Preview` 覆盖；全量 test 绿 + 门禁全 PASS。
- **关联**：低优先；可与 `ISSUE-P3-437` 同批实施（同为解锁页过程说明面）。

### ISSUE-P3-443：底栏 Tab 补「顺序自定义」（在既有「隐藏 Tab」设置上追加排序）

- **核实时间点**：2026-10-02；**核实方式**：竞品对照报告 §3.6 / §5-G13（代码直读）——Monica 底栏 Tab 顺序与可见性均用户可配（`BottomNavModel.kt:25-34` + `SimpleMainScreen.kt:909-920` `bottomNavOrder`/`bottomNavVisibility`，单 Tab 自动隐藏底栏 `:922`）；本仓仅验证器 / 生成器可隐藏（`AppBottomBar.kt:42-87`），顺序固定。**裁决**：G13 「可以做」。
- **验收标准**：
  ① 设置内可拖拽 / 上下移调整底栏 Tab 顺序，持久化于界面偏好；隐藏语义与现开关合并为同一设置面（显隐 + 排序一体）；
  ② 宽屏 NavigationRail 与底栏同源顺序（`KeePasskeyApp.kt:346-353` 消费同一配置）；顶层 Tab 转场（PD-44 双向交叉淡化）与返回键熔断（`KeePasskeyApp.kt:226-238`）行为不变；
  ③ 至少保留一个可见 Tab；`@Preview` 覆盖；全量 test 绿 + 门禁全 PASS。

### ISSUE-P3-446：反馈通道单一化——清退 3 处残留 Toast 收敛进全局 Snackbar 通道

- **核实时间点**：2026-10-02；**核实方式**：竞品对照报告 §2.4 / §5-G9（本仓走查 grep 计数）——本仓 Toast 仅 3 处残留：`ClipboardSecurityManager.kt:282`、`EntryDetailUrlActions.kt:108`、`OpenVaultEntryActivity.kt:108`；全局唯一 Snackbar 宿主（`AppGlobalSnackbarHost.kt:19-50`）已覆盖其余全部反馈面。**裁决**：G9 「可以做」（可随手批处理）。
- **验收标准**：
  ① 3 处 Toast 改走 `AppSnackbarChannel`（在非 Compose 上下文中的调用点经既有通道桥接），语义与文案不变；特别注意 `ClipboardSecurityManager` 的提示可能发生在无 Activity 前台场景——若 Snackbar 不可达则**如实保留**该处 Toast 并在批次文档说明理由；
  ② 全仓 Toast 残留清零或留痕说明；全量 test 绿 + 门禁全 PASS。

## 2. 整改内容

### 2.1 ISSUE-P3-446：Toast 清退收敛全局 Snackbar 通道

1. **`AppSnackbarChannel`**：新增 `hostActive: StateFlow<Boolean>` 与 `markHostActive()`——全局宿主当前是否在组合中（即发入通道的消息能否被**即时**显示）。生产者据此在「宿主不可达」场景自行选择兜底通道，避免消息滞留缓冲、迟至下次打开主界面才弹出陈旧提示。
2. **`AppGlobalSnackbarHost`**：消费循环包进 `try/finally`——进入组合即 `markHostActive(true)`，离开组合（`LaunchedEffect` 取消，覆盖导航销毁 / Activity 销毁全部路径）在 `finally` 中 `markHostActive(false)`。
3. **三处调用点处置**：
   - **`EntryDetailUrlActions.notify`**：`onShowMessage == null` 的兜底分支由 Toast 改为 `AppSnackbarChannel.trySend`——本执行器只被主界面外壳内的组合调用，全局宿主必可达；`notify` 不再需要 `context` 形参（签名收窄，三处调用点同步）。文案与语义零变化。
   - **`OpenVaultEntryActivity`**：`toast()` 改为 `notifyUi()`＝`AppSnackbarChannel.trySend`——本页随即 `launchMainAndFinish()`，事件缓冲于通道（容量 64 + DROP_OLDEST），`MainActivity` 外壳组合后由全局宿主即时消费（主界面既存则当屏显示，冷启动则落解锁页后显示）。文案与语义零变化。
   - **`ClipboardSecurityManager.notifyClipboardCleared`**：**按 AC ① 双轨**——`hostActive` 为 true（主界面外壳正在组合）即发全局 Snackbar；不可达（清空定时器到点落在无前台主界面组合的场景）**如实保留 Toast 兜底**并在本节留痕：该场景发通道会把「剪贴板已自动清空」滞留缓冲、迟至用户下次打开主界面才弹出陈旧提示，比 Toast 更不可接受；Android 12+ 本就抑制后台 Toast，兜底分支为 best-effort。熄屏 / 后台 / 锁定 / 冷启动对账路径仍刻意不提示（用户不在场，ISSUE-P3-352 AC③ 既有口径不变）。
4. **留痕说明（AC ②「清零或留痕」）**：全仓 `Toast` 代码级残留仅上述 `ClipboardSecurityManager` 兜底分支一处（`grep` 核实），其余两处清零；既有 KDoc 中提及 Toast 的历史叙述（如 `EntryDetailViewModel`「此前该方法仅发 Toast」）为历史记录不改写。
5. **可达性判据的边界（如实声明）**：`hostActive` 反映「外壳组合是否存活」。若清空定时器恰在自动填充 / 凭据解锁页前台（无外壳组合）时到点，提示将走 Toast 兜底而非缓冲滞留——这是设计取向：宁可当场 Toast 也不迟到。

### 2.2 ISSUE-P3-438：进程异常关闭的一次性解锁页提示

1. **新文件 `SessionCloseMarker.kt`**（security 包，`@Singleton`）：会话开合跨进程标记。判定信号**复用既有** `DatabaseSession.state` 进程级状态机——`OPENED` / `DIRTY` 判「会话打开」，`LOCKED` / `CLOSED` 判「已正常收尾」（主动锁库 / 超时锁定 / 熄屏熔断全部经 `lock()` 推回 `LOCKED`，与「进程死亡时仍打开」在信号层即区分，AC ①②）；跨进程只持久化**一个布尔**（无任何敏感数据，AC ②；同型先例：`ClipboardSecurityManager` 跨进程待清标记）。选择 `DatabaseSession.state` 而非 `AutoLockManager` / `AutoLockSessionGuard` 的原因：自动填充（`AutofillUnlockActivity`）与凭据（`CredentialUnlockActivity`）两条不经 MainActivity 的解锁入口**不走** `guard.onUnlockSuccess()`，但都收敛于同一 `DatabaseSession`。
2. **时序契约（本类正确性的关键）**：`initialize()` 必须在进程唯一冷启动点（`MainApplication.onCreate`）**同步取走持久化值**再启动采集——`state` 是 `StateFlow`，采集器注册即收到首值（`CLOSED`）并覆写持久层；若先采集后取值，「上次异常关闭」事实会在任何消费之前被首值冲掉。取走后的事实驻留单例内存，由 `consumeAbnormalClose()` 一次性交付（幂等，二次调用恒 false）。
3. **解锁页接线**（复用 `unsavedEditsDiscardedNotice` 同型一次告知管线）：`UnlockUiState` 新增 `lastSessionAbnormalCloseNotice`；`UnlockViewModel.init` 消费 `sessionCloseMarker?.consumeAbnormalClose()` 后置位（nullable 形参供 JVM 单测，生产 DI 注入真实例）；`UnlockScreen.UnlockContent` 在数据库概要卡之下渲染一次性轻提示——`bodySmall` + `onSurfaceVariant` **中性色**，不渲染为错误告警（AC ①，与丢弃编辑提示的 error 色显式区分）。
4. **文案**（zh/en 成对 1 条）：`unlock_last_session_abnormal_close`＝「上次应用未正常关闭，密码库已自动锁定」/ "The app did not close properly last time; the vault was locked automatically"。
5. **`@Preview`**：`UnlockScreenPreviews` 新增 `UnlockContentAbnormalCloseNoticePreview`（浅 / 深两态，AC ③）。
6. **测试**：新增 `SessionCloseMarkerTest` 4 例——行为层 2 例（`isOpenState` 四态映射）+ 接线层源码守卫 2 例（「冷启动取走持久值先于采集器启动」的时序契约、「MainApplication 同步接线」）。
7. **边界（如实声明）**：force-stop 亦属「进程死亡时会话仍打开」，下次启动同样呈现提示——文案用「未正常关闭」如实中性表述，不渲染为故障；自动填充入口冷启动同样会消费并呈现该提示（同一解锁页组件，语义成立）。

### 2.3 ISSUE-P3-443：底栏 Tab「显隐 + 排序」一体化

1. **持久化模型**：`UserSettings.showAuthenticatorTab` / `showGeneratorTab` 两布尔**整体替换**为 `bottomNavOrder: List<String>`＝**有序可见 Tab 名单**（元素为 `BottomNavItem.name` 规范名；不在名单中即隐藏）。规范名常量收敛于数据层 `BottomNavTabNames`（存储层不引 UI 类型）；名单为空 / 含非法名 / 缺密码库 Tab 由 UI 解析层 fail-safe 兜底。旧布尔键迁移：`RealSettingsRepository.resolveBottomNavOrder` 在新键缺失时按旧布尔过滤出厂全序（用户既有隐藏偏好无缝延续），迁移结果不回写新键，用户首次调整时才落 `bottom_nav_order` 键。
2. **解析纯函数**（`BottomNavItem` companion，JVM 单测直测）：`resolveVisibleItems(orderNames)`（名单即顺序；空 / 全非法回落「全部可见、枚举序」；密码库 Tab 缺失回插队首——它是隐藏回落目标）；`displayOrderFor(orderNames)`（设置页展示用完整序列：可见在前、隐藏殿后）。原 `getVisibleItems(showAuthenticator, showGenerator)` 仅有外壳一个调用点，随本批退役。**可隐藏集合维持旧口径**（验证码 / 生成器）：密码库为隐藏回落目标、设置为返回设置页的常驻入口，二者固定显示——「至少保留一个可见 Tab」（AC ③）由此不变量天然成立。
3. **外壳消费点**（AC ②）：`KeePasskeyApp` 的 `visibleNavItems = remember(bottomNavOrder) { BottomNavItem.resolveVisibleItems(...) }`——底栏（`AppBottomBar`）与宽屏 `AppNavigationRail` 消费**同一列表**，同源顺序；`hiddenTabRedirectRoute` 改签名为 `(currentRoute, visibleItems)`（语义不变：停在已隐藏的顶层 Tab 即回落密码库）；顶层 Tab 转场（PD-44 双向交叉淡化，`navigateToTopLevel`）与返回键熔断路径**零改动**。
4. **设置面**（AC ①）：新文件 `BottomNavTabOrderCard.kt`（subscreens 包，独立成文件为本文件行数闸门余量）——`themeListSection` 内原两行显隐开关整替为本卡：每行＝图标 + 标签 +（不可隐藏项的「固定显示」说明）+ 上移 / 下移 `IconButton` + 显隐 `Switch`；行序即 `displayOrderFor`；换位后回写「完整序列中的可见子集」；开关仅 `canHide` 项可用。`SettingsRepository` 接口两旧 setter 整替为 `setBottomNavOrder(order: List<String>)`（DataStore 逗号分隔持久化）；`SettingsPreferencesController` / `SettingsViewModel` / `SettingsUiState` / `SettingsUiStateProjection` / `ThemeSettingsScreen` / `KeePasskeySettingsNavGraphRoutes` 全链同步。
5. **文案**（zh/en 成对 4 条）：`theme_nav_tabs_desc` / `theme_nav_tab_pinned_sub` / `cd_move_tab_up` / `cd_move_tab_down`；旧 `theme_show_auth_tab_*` / `theme_show_gen_tab_*` 4 键同批退役（无残余引用，`grep` 核实）。
6. **`@Preview`**：`BottomNavTabOrderCard` 新增浅 / 深两态 ×「全部显示 / 验证码已隐藏」反向态共 4 张（ISSUE-P3-340 立规：开关的关态必须有预览）。
7. **测试**：`KeePasskeyAppLogicTest` 既有 2 例回落用例改锚新签名（断言语义逐条保留）+ 新增解析行为层 3 例（名单即顺序 / 密码库回插队首与空名单回落 / 完整展示序列与不可隐藏集合）；`FakeSettingsRepository` 同步新接口。

## 3. 验证

### 3.1 测试与门禁

- `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` 通过。
- 全量 `.\gradlew.bat test --rerun-tasks --max-workers=1`：**BUILD SUCCESSFUL（114 executed）**；`count_test_results.py` = **`xml=502 tests=3250 failures=0 errors=0 skipped=13`**（§406 基线 3243 ⇒ 净 +7 例：`SessionCloseMarkerTest` 4 + `KeePasskeyAppLogicTest` 净 +3）。
- **过程缺陷如实留痕**：首轮全量 `:sync:testDebugUnitTest` 红一例（`SyncCacheTest.kt:287` AssertionError）——本批**零触碰** sync 模块，与 §333 留痕的「Windows 文件锁偶发」同型；该模块独立强跑全绿后，全量重跑第二轮全绿（本节读数即第二轮）。
- 截图包装：新增 `@Preview` 后跑 `generate_screenshot_test_wrappers.py`（`promoted=102 wrappers=102 packages=17`）+ `:app:compileDebugScreenshotTestKotlin --rerun` 通过。

### 3.2 门禁读数（`python tools/doc/gate_readings.py`，原样粘贴）

```text
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=36  budget=36
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 405 份；分册登记 407 条；全量索引 407 条；最大 §407）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 560 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：61  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

### 3.3 如实声明

- P3-446 的 `ClipboardSecurityManager` Toast 兜底为**有意保留**（AC ① 允许并要求说明理由，见 §2.1 第 3 条）；宿主可达性判据边界见 §2.1 第 5 条。
- P3-438 的设备侧场景（真机杀进程 → 冷启动提示）本轮无已连接设备未实跑，宿主侧由行为层 + 源码守卫 + 预览承托；`consumeAbnormalClose` 的一次性语义依赖 MainApplication 冷启动接线，已有源码守卫锁定。
- P3-443 未采用拖拽手势排序（Monica 同款 `bottomNavOrder` 数据模型、上下移按钮交互）：上下移在 Compose 中无需引入拖拽选择依赖即满足 AC ①「拖拽 / 上下移」的「上下移」分支，且对无障碍更友好；如后续要拖拽可单独立项。
- P3-443 的真机走查（宽屏 Rail 顺序联动 / 返回键熔断）待用户装机走查。
