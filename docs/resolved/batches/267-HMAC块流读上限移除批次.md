# §267 HMAC 块流读侧上限移除批次（`ISSUE-P3-268` 整条闭环）

> 本批次由 `docs/ACTIVE_ISSUES.md` 条目 `ISSUE-P3-268` 整改归档而来。
> AC⓪ 裁决经两轮收敛：评估阶段曾提「设置页自定义上限 / 固定放宽 16 MiB」两候选，
> 用户指示「参考一下项目里面的参考项目是怎么做的」——核实后用户最终裁决（2026-09-23）：
> **「既然别家都没有上限，那么咱们也不要设置上限，只检查负数。」**
> 裁决登记 [`architecture/产品裁决登记.md`](../../architecture/产品裁决登记.md) **`PD-31`**。

## §1 背景与前提核实（条目正文要点 + 本批补齐的实证）

条目核实（2026-09-22）：`HmacBlockStream.kt` 读侧两处（`readAll` / `loadNextBlock`）对块长
`> MAX_READ_BLOCK_SIZE`（= `DEFAULT_BLOCK_SIZE` = 1 MiB）一律抛 `KdbxCorruptFileException`；
规格（keepass.info KDBX 4）对块长 `s` 仅定 Int32，「KeePass 当前用 1 MiB」只是官方**写侧**惯例。
即旧上限会把第三方实现写出的「块长 > 1 MiB」合法文件误判为损坏。

条目原文标注「报告中『KeePassXC 也恒 1 MiB』系第三方行为论断，未实证」——本批**逐一实证补齐**
（对 `参考项目/` 的**有目标**定点核对，规则 3 允许范围）：

| 参考实现 | 读侧块长校验 | 证据位置 |
|---|---|---|
| **KeePass 2.61.1 官方**（格式裁决者） | 仅 `nBlockSize < 0`，**无上限**，按声明直接 `MemUtil.Read` 分配 | `KeePassLib/Serialization/HmacBlockStream.cs:231-237` |
| **keepass2android**（Android） | 同官方：仅负数检查，**无上限** | `KeePassLib2Android/Serialization/HmacBlockStream.cs:222-228` |
| **KeePassXC** | 仅 `blockSize < 0`，**无上限**；其 `m_blockSize(1024*1024)` 仅是**写侧**默认分块 | `src/streams/HmacBlockStream.cpp:142-149`（构造器 :27） |

三家参考实现（含裁决者）读侧**全部无上限**、也**均无任何设置项**——「设置自定义」在参考实现里
无先例；且该上限属格式解析层护栏，暴露给用户无从判断、调大即自行放弃防护，属伪需求。
旧仓内 1 MiB 上限是**比三家都严格**的自加护栏（防恶意文件声明超大块诱发大分配）。

## §2 整改内容（用户裁决：读上限移除，仅检查负数）

- [`HmacBlockStream.kt`](../../../database/src/main/java/com/keepasskey/database/file/HmacBlockStream.kt)
  **删除**常量 `MAX_READ_BLOCK_SIZE` 与读侧两处越上限拒绝分支（`readAll` / `loadNextBlock`），
  读侧仅保留 `blockSize < 0` → `KdbxCorruptFileException("非法的负数块大小")`——
  与三家参考实现读侧语义对齐（块长仅受 Int32 域约束）。
- **fail-closed 面收口（同型补齐）**：上限移除后，「声明超大块长而底层流数据不足」会走到块数据读取，
  原实现该处 `EOFException` **裸漏出**（readAll 的 EOF 包裹只盖住了 HMAC 读取段），
  与仓内「损坏文件一律类型化异常」纪律不符——`readAll` / `loadNextBlock` 的块数据读取
  统一补 `catch (EOFException) → KdbxCorruptFileException("HMAC 块读取意外中断")`
  （与既有 HMAC 读取处同型）。
- **残余防线如实登记**：恶意分配由 [`LittleEndianUtil.readBytes`](../../../database/src/main/java/com/keepasskey/database/io/LittleEndianUtil.kt)
  既有通用护栏兜底（`DEFAULT_MAX_READ_BYTES = 16 MiB`，P0-5 立规：不可信长度驱动分配一律先过界），
  超界即类型化拒绝——HMAC 层无上限 ≠ 分配无界。该工具层护栏**一行未动**。
- KDoc 同步：类 KDoc 记录裁决（ISSUE-P3-37「块尺寸上限完全未变」表述随之更新，
  防止陈旧断言与代码矛盾）。

## §3 测试

旧回归用例 `readAll_rejectsOversizedBlockSize`（断言 2 MiB 块被拒）按测试资产纪律**改写**（非删除），
随新裁决共 4 例，落 [`KdbxCompatibilityAndSecurityTest`](../../../database/src/test/java/com/keepasskey/database/KdbxCompatibilityAndSecurityTest.kt)：

| 用例 | 锁定面 |
|---|---|
| `readAll_acceptsBlockSizeBeyondLegacy1MiB` | 旧上限 + 1（1 MiB + 1 字节）单块经真实 `writeAll`/`readAll` 往返**必须被接受**（旧代码必拒的最小越界块） |
| `readAll_rejectsNegativeBlockSize` | 负数块长仍类型化拒绝（官方 `nBlockSize < 0` 同语义） |
| `readAll_failsClosedOnTruncatedOversizedBlock` | 声明 2 MiB 而流无数据 → EOF 路径兜底为 `KdbxCorruptFileException`（沿承原「J 项」用例形态，fail-closed 不回退） |
| `streamingPath_acceptsBlockSizeBeyondLegacy1MiB` | 流式路径（`loadNextBlock`）同样接受 > 1 MiB 合法单块（`HmacBlockOutputStream` 定制分块写出 → `HmacBlockInputStream` 读回 + `verifyEndOfStream`） |

## §4 验证读数

- 定向：`KdbxCompatibilityAndSecurityTest` / `KdbxFileTest` / `KdbxStreamingPipelineTest`
  `--rerun-tasks` 强制真实执行，**BUILD SUCCESSFUL**（首跑曾全程 UP-TO-DATE 不可信，按仓规强制重跑后 36 任务全 executed）。
- 全量 `.\gradlew.bat test --rerun-tasks --max-workers=1`：**BUILD SUCCESSFUL**（退出码 0），
  `count_test_results.py` = **`xml=366 tests=2547 failures=0 errors=0 skipped=13`**
  （§266 基线 `xml=366 tests=2544` ⇒ **净 +3 例**逐条可对＝删 1 例（旧上限拒绝用例改写）+ 增 4 例＝§3 表，无漂移；
  xml 类数持平＝用例落既有测试类、未新建类）。
- `BROKEN_MD_LINKS=0`（`python tools/doc/check_md_links.py`）、
  `RESOLVED_INDEX_SYNC=OK`（`python tools/doc/check_resolved_index_sync.py`）。

## §5 如实声明

1. 读侧无上限后，恶意 / 损坏文件可声明至多 16 MiB 的块长并触发等量临时分配
   （`readBytes` 护栏内）才被 EOF / 校验拒绝——这是用户裁决「对齐参考实现」**有意接受**的
   取舍（与官方 / keepass2android / KeePassXC 同一暴露面），已登记 `PD-31` 与限界口径，不得静默放宽该护栏。
2. 未触 `*/src/androidTest/**`、原生面（`crypto/src/main/rust/**`）、`参考项目/`、构建脚本与依赖清单
   ⇒ 无四层 `connectedDebugAndroidTest` 义务；未跑 `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍
   （未改 `PasskeyData` schema / `PasskeyPkcs8Codec` / KPEX 字段）。
3. 互操作影响评估：写侧契约不变（恒 1 MiB 分块），读侧放宽只会**增加**可接受文件集
   （原来误拒的 > 1 MiB 块文件），既有官方语料与 `OwnProductInteropProbeTest` 覆盖面不受影响。
