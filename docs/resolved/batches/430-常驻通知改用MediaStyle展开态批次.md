# §430 常驻通知改用 `MediaStyle` 展开态批次（`ISSUE-P3-440` 四轮回执：`BigTextStyle` → `MediaStyle`）

> 批次日期：2026-10-03。
> 触发＝第四轮装机走查回执（用户原话：「忽略了一点：诊断C-媒体样式，这个可以，已经有箭头了」）
> ——即 §429 附带的**设备侧 A/B 对照实验**给出了决定性读数。
> 性质＝**缺陷修复**（承接 §428 / §429）：§429 用了 `BigTextStyle` 仍未得到展开箭头，本批换成 `MediaStyle`。
> 装机形态＝debug 版（`com.keepasskey.debug`）；设备＝Xiaomi M332BF / HyperOS OS4.0 / Android 17。

## §1 决定性证据（设备侧 A/B 对照）

用系统自身 `cmd notification post` 在同一台设备上分别投递三种样式的诊断通知并截图比对：

| 诊断通知 | 样式 | 折叠态是否出现展开箭头 |
|---|---|---|
| 诊断 A | `-S bigtext`（短文本） | **无** |
| 诊断 B | `-S bigtext`（长多行文本） | **无**（与 A 相同结论）
| 诊断 C | `-S media` | **有**（用户回执确认「这个可以，已经有箭头了」） |

⇒ **HyperOS 上「展开箭头」由 `MediaStyle` 决定，与文案长短无关**；§429 的 `BigTextStyle` 在本机
（含 `bigText` 拉长到多行）**都不产生箭头**，故本批替换。

旁证（Android 官方文档「创建可展开的通知 / 添加操作按钮」原文）：

> 与其他通知样式不同，**`MediaStyle` 还允许您通过指定三个操作按钮（这些操作按钮也应在收起的视图中显示）
> 修改收起尺寸的内容视图**——为此，请向 `setShowActionsInCompactView()` 提供操作按钮索引。

即 `MediaStyle` 是**唯一**能把操作按钮带进「收起视图」的样式，与上面实测互证。

## §2 整改

- **新增依赖** `androidx.media:media:1.7.0`（`gradle/libs.versions.toml` 的 `androidxMedia` + 
  `app/build.gradle.kts`）：`androidx.core` **不含** `MediaStyle`（已解包 `core-1.19.0.aar` 核对），
  该类在 `androidx.media.app.NotificationCompat` 下；KeePassDX 用的也是同一个库。
- `UnlockedNotificationController.post()`：`setStyle(NotificationCompat.BigTextStyle().bigText(contentText))`
  → **`setStyle(MediaStyle())`**；其余（`setOngoing` / `VISIBILITY_SECRET` / `setOnlyAlertOnce` /
  Chronometer / 动作集 / `PD-69` 安全面）**零变化**。
- **刻意不用 `setShowActionsInCompactView`**：① KeePassDX 源码自注「Won't work with Xiaomi」，
  本机正是 Xiaomi；② 它要求动作另配图标（本仓动作一直是 `null` 图标、由系统渲染为文字按钮）。
  故本批只取「展开箭头」这一已被实测确认的效果，**不引入图标资源与紧凑按钮位**。
- `UnlockedNotificationWiringTest`：`常驻通知必须给出展开态以便一键展开动作` →
  `常驻通知必须以MediaStyle提供展开态`（断言 `MediaStyle()` 存在 + `BigTextStyle` 不得回退）。

## §3 门禁读数（原样粘贴 `python tools/doc/gate_readings.py` 输出）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=36  budget=36
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 428 份；分册登记 430 条；全量索引 430 条；最大 §430）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 571 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：64 个  检查过的调用点：BoxScope 组件=62  框架 supportingText 槽=9  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

## §4 测试

`.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿；
`python tools/doc/count_test_results.py` → `xml=513 tests=3345 failures=0 errors=0 skipped=13`
（**与 §429 持平**：本轮是**替换**同一条守卫的断言口径，未增删用例）。

## §5 过程留痕（对照实验首轮被「自动成组」污染）

首轮把三条诊断通知（A/B/C）**一次性**投递，被系统 `AUTOGROUP_SUMMARY` 自动成组，
三条被折叠成一张卡片里的三行单行文本 ⇒ 都不显示箭头，**实验无效**（图片见当时取证，
分组键 `0|com.android.shell|g:Aggregate_AlertingSection`）。
处置（用户侧）＝重新单独投递 `-S media` 一条，箭头出现，结论成立。
**教训**：以系统通知做对照实验时，**同包多条的自动成组会掩盖样式差异**，须逐条投递。

## §6 如实声明（残余）

- **修复本身未取得装机回执**：重装 debug 包会结束应用进程 ⇒ 库回锁定态 ⇒
  「已解锁常驻通知」本机无法自行复现（设备侧只能验证「同机制的 diagnostic media 通知有箭头」）。
- **预期行为**：解锁后该条通知**折叠态右上角出现展开箭头**，点一下即展开并显示
  「立即锁定 / 复制用户名 / 复制验证码」。
- **本次新增依赖**：`androidx.media:media:1.7.0`（官方 AndroidX 工件、无额外传递依赖），
  供应链 CVSS 硬断言由既有 `check_dependency_cvss.py` 覆盖。
- **若仍无箭头**：按用户已预先裁决「**移除通知上的复制动作**」执行，不再挂着不可达入口。
- 设备上遗留的数条 `诊断A/B/C/M/X` 通知为本次对照实验产物（已 snooze），用户可随手划掉。
