# §318 附件回收保存 fail-closed 批次（`ISSUE-P2-310` 闭环）

> **批次性质**：数据完整性整改。附件落盘缓存被系统回收后，`BinaryStore` 的 fail-open 读契约
> （返空流）会被保存路径消费成「字段头声明 N 字节、实际写出 0 字节」的自相矛盾内层头，
> 使整库下次打开判损坏。本批在**写侧**加保存前校验（fail-closed），**读侧 fail-open 契约不动**。

---

## 1. 整改对象

| 条目 | 判据来源 | 本批处置 |
|---|---|---|
| `ISSUE-P2-310` 附件被系统回收后 fail-open 返空字节 → 保存产出矛盾内层头 → 整库打不开（○） | 2026-09-24 同步/加密/passkey 安全审计 | **闭环**（AC① / AC② / AC③ 全部达成，见 §2 / §3） |

## 2. 整改方案

### 2.1 根因（条目正文已逐行锁定）

`FileBinaryStore.load` / `openStream` 在缓存条目缺失（`cacheDir` 被系统回收 / 设置页清空缓存 /
提前清理）时按 fail-open 契约返回空流；`InnerHeader.serialize` 写 BINARY 字段头时用解析期固定的
`spilledSize` 声明长度（`bin.size + FLAGS_FIELD_BYTES`），`writeTo` 对 spill 分支直接
`openStream().copyTo` 无长度校验 ⇒ 写出 0 字节但头部声明 `spilledSize + 1` ⇒ 内层头长度自相矛盾 ⇒
下次打开按声明长度消费后续字节整段错位 ⇒ `KdbxCorruptFileException`，整库打不开（默认 `.bak` 仅一代、
关备份即不可恢复）。`sizeOf` / `cacheSize` 原语生产侧零调用——校验所需原语早已存在，只是没人调。

### 2.2 方案（AC①：保存 fail-closed，不动读侧）

1. 新增类型化异常 `KdbxAttachmentSpillMissingException : IOException`
   （`database/exception/`，KDoc 写明与读侧 `KdbxCorruptFileException` 的语义相对性：
   本异常代表**本地环境**丢了附件字节、库内容完好，可重试保存或重加附件）。
2. `InnerHeader.BinaryItem` 新增 `verifySpillIntact()`：落盘条目校验
   `store.sizeOf(spillKey) == spilledSize`，不等即抛上述类型化异常；内存条目为 no-op。
3. `InnerHeader.serialize` 在写每个 BINARY 字段头**之前**调用该守卫——异常在任何
   附件字段写出之前抛出，矛盾头不存在被写出的可能。读侧 `load` / `openStream` 的
   fail-open 契约一字未动（`SessionOpener` / `SessionExternalParser` 等读路径零变化）。

### 2.3 测试

- **JVM 守卫用例 4 例**（`database/.../InnerHeaderSpillIntactGuardTest.kt` 新增）：
  回收（sizeOf=0）⇒ 抛类型化异常且异常信息含声明量/实际量、附件字段不得写出；
  部分截断（字节数不符）⇒ 同样拦截；字节完好 ⇒ serialize 正常且往返逐字节等价（既有语义不变）；
  内存条目不受守卫影响。
- **AC② 设备取证**（`app/src/androidTest/.../AttachmentSpillEvictionSaveGuardDeviceTest.kt` 新增，
  按 AC 明示在 **AVD Pixel_10** 上执行、实体机禁用——§263 卸载风险）：真实 `FileBinaryStore`
  （`cacheDir/attachments` 落盘基线）+ `filesDir` 正式库文件跑完整生产链路：
  含 5 MiB 附件库打开（解析期真落盘，`sizeOf` 实证 5 MiB）→ 阳性对照保存成功 →
  `store.clear()` 模拟「设置→清空缓存（不 force-stop）」→ 再次保存 ⇒
  断言 `KdbxResult.Failure` 且 `error is KdbxAttachmentSpillMissingException`（类型化拦截而非矛盾头）→
  正式库文件字节未变、重新打开成功、附件字节逐字节完好（重新落盘后可读）。
  读数：`tests=1 failures=0 errors=0 skipped=0`
  （`app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10(AVD) - 16-_app-.xml`）。

## 3. 验证读数（原样粘贴）

```
xml=406 tests=2757 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 316 份；分册登记 318 条；全量索引 318 条；最大 §318）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 470 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

> 用例计数：`tests=2753`（§317）→ **2757**（+4 = JVM 守卫 4 例；AC② 设备用例 1 例计入 connected 层，
> 不入 JVM 计数）。

## 4. 残余与声明（如实登记）

- **判据边界**：守卫校验 `sizeOf(key) == spilledSize`，能拦截「条目缺失 / 部分截断 / 变长」；
  若存储实现返回**恰好等长但内容损坏**的字节（本仓实现不存在该形态：条目级原子写 + fsync），
  守卫按设计不覆盖——AC① 即为此口径。
- **读侧 fail-open 契约保持**：被回收后 `attachment.data` 返回空字节的行为不变（读侧不动是 AC① 明示）；
  本批保证的是**保存面**不再把该状态固化为库级损坏，且失败后正式库文件完好、附件缓存恢复（重新打开
  即重建落盘）后重试保存即成功——AC② 用例第 ④ 段实证。

## 5. 涉及文件

生产：`database/file/InnerHeader.kt`（`verifySpillIntact` + serialize 前校验）、
`database/exception/KdbxAttachmentSpillMissingException.kt`（新增）。
测试：`database/src/test/.../InnerHeaderSpillIntactGuardTest.kt`（新增 4 例）、
`app/src/androidTest/.../AttachmentSpillEvictionSaveGuardDeviceTest.kt`（新增 1 例，AC②）。
