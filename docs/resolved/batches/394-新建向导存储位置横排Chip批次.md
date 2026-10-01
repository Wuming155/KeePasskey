# §394 新建向导存储位置横排 Chip 批次（§393 后续走查反馈，会话内整改随批归档）

> **触发**：2026-10-01 用户在 §393 实现态装机走查后反馈：「新建密码库向导这个弹窗太大了，
> 而且存储位置竖着放不太合适，应该横着放，让用户自己选择。做成类似导入已有库的这种格式，
> 本地设备、WebDAV、兼容 S3 这种并列的那个样子」。
> **结案条目**：会话内整改（未占 ISSUE 编号），随本批次文档归档。

---

## 0. 根因与核实

- **核实（2026-10-01）**：直读 `CreateVaultWizardDialogSections.kt`——`VaultStorageLocationSection`
  为**三行竖排单选**（`StorageLocationOption`：单选钮 + 标题 + 两行副文案 × 3），
  向导整体高度被这六行文案撑起；而同包 `OpenExistingVaultDialog` 的三来源选择
  （`OpenVaultSourceChips`）是**横排 FilterChip**（本地设备 / WebDAV / 兼容 S3 并列一行），
  两对话框形态不同族。
- **根因**：ISSUE-P2-229（§233）落地「存储位置可选」时按行单选实现，ISSUE-P3-425（§391）
  增「云端」第三行进一步加高；与 §391 后统一的三来源 Chip 形态不一致。

## 1. 整改

| 层 | 变更 |
|---|---|
| `CreateVaultWizardDialogSections.kt` | `VaultStorageLocationSection` 重写：三行竖排单选改为**横排三枚 FilterChip**（应用私有目录 / 自选位置 / 云端，CapsuleShape、12sp，逐字对齐 `OpenVaultSourceChips` 形态）；「云端」Chip 仍受 `CloudSyncSnapshot.ready` 门控（未就绪置灰）；长说明改为**只随当前选中项单条呈现**（新增私有 `LocationCaption`）——外部位置的两条降级告知（写回非原子 + 不参与同步，error 色）与云端目标 / 未配置文案**原样保留不缩水**；`StorageLocationOption`（单选行组件）随之无消费方，整件删除 |
| `strings.xml`（zh / en 成对） | 三枚标题字符串改作 Chip 短标签（括号说明并入副文案）：`db_create_location_internal`「应用私有目录」、`db_create_location_external`「自选位置」、`db_create_location_cloud`「云端」；`db_create_location_internal_sub` 增「推荐：」前缀（原标题括注不丢失）；`db_create_location_external_sub` 随单选行删除而**整条退役**（zh / en）；`db_create_location_external_hint` 去除「点此行」措辞（Chip 不再是行）；`vaultName` 文案未动 |

**行为不变式**：选「自选位置」仍当场拉起 `ACTION_CREATE_DOCUMENT`、取消回落应用私有目录
（`onVaultDocumentCancelled` 链路一字未动）；「云端」未配置仍不可选；busy 禁用 / 弱口令确认 /
`SecureDialogWindowEffect` / CharArray 离场擦除全部原样。

---

## 2. 验证

- `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` 绿；`DatabasePickerViewModelTest`
  / `DatabasePickerCloudCreateTest` 等 `screens.database.*` 定向单测绿。
- 截图包装编译门禁（本批未动 `@Preview`，重生成 + 编译做防御性确认）：
  `wrappers=98` + `:app:compileDebugScreenshotTestKotlin --rerun` BUILD SUCCESSFUL。
- 真机：`:app:installDebug` 装机，视觉效果待用户走查（新 Chip 行 + 收敛后的向导高度）。

### 2.1 门禁读数（原样粘贴，归档后采集）

全量单测（`--rerun-tasks` 强制真实执行）：

```
BUILD SUCCESSFUL in 4m 6s
114 actionable tasks: 114 executed
xml=499 tests=3226 failures=0 errors=0 skipped=13
```

> 与 §393 基线（`xml=499 tests=3226`）完全一致——本批为纯呈现层改动，无用例增删。

`python tools/doc/gate_readings.py`（归档后采集，最大 §394）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 392 份；分册登记 394 条；全量索引 394 条；最大 §394）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 557 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

---

## 3. 如实声明

1. 本批为纯呈现层改动：**语义、回调与字符串 key 均未变**（仅值变更），JVM 面无新用例——
   「三选一 + 降级告知」的判定逻辑全在既有调用链，行为不变式见 §1 末段。
2. 真机视觉效果（Chip 换行边界 / 向导高度收敛幅度）未截图取证（FLAG_SECURE），待用户走查。
3. 未触 `androidTest` / 原生面；`lint` 未单独跑（无 CI 强制点）。
