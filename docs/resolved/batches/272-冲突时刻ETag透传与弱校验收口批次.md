<a id="s272"></a>

# §272 冲突时刻 ETag 透传与弱校验收口批次

> `ISSUE-P1-275` **整条闭环**（P1 2 → **1 项**）。
> 触发＝2026-09-23 两轮全仓审查（8 铺开 + 7 对抗复核）P1 区首项整改；
> 本体（自动合并上传丢弃冲突时刻 ETag）与叠加缺陷（`cleanEtag` 剥弱标记 + `formatHeaderEtag` 只补引号）同批收口。
> RFC 依据经 rfc-editor.org 全文核对：RFC 7232 §2.3（强/弱比较定义）、§3.1（If-Match 恒强比较）、
> RFC 4918 §8.6（"weak ETags … cannot be used in If-Match headers"）、§10.4.2（`Condition = ["Not"] (State-token | "[" entity-tag "]")`）、
> §10.4.4（If 头「服务器 MUST 使用弱**或**强比较」）、§10.4.9（`[W/"…"]` 弱形态示例）。

---

## 1. 条目正文（原样收录）

### `ISSUE-P1-275`：自动合并上传丢弃冲突时刻 ETag，退化为「重探当前值」——他端写入被静默覆盖；与弱 ETag 缺陷同批收口

- **核实时间点**：2026-09-23 经全仓定向核对 + 两轮独立对抗复核（铺开代理 A 组、复核代理 A 组、用户自查三方一致）。
- **核实方式**：① `remoteEtag` 其实**已随形参到位**——`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt:276` 的 `handleConflictMerge(..., remoteEtag: String)` 收到该值，却在 `:368` 调 `markResolvedAndUpload(remotePath, mergedBytes)` 时整个丢弃（同文件 `:339` 的 `beginPendingConflict` 反而存了它）⇒ 属**漏传参数**而非设计约束。② 丢弃后落进 `sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:446-448` 的回退分支：重新 `provider.getMetadata` 探**当前** ETag 再作 `If-Match` 预条件。③ 该形态被同函数 KDoc 明文禁止（`SyncEngine.kt:431-434`：「必须使用该值做 If-Match 乐观锁，而非重新探测的当前 ETag——用户决策期间远端可能再次被修改，用当前值会通过校验并静默覆盖他端的更新」），且 `sync/src/test/java/com/keepasskey/sync/SyncEngineTest.kt:462-464` 自陈「`expectedEtag = null` 为无条件 PUT 语义，仅限无基线可校验的场景」——自动合并路径**有**基线。④ 对照：用户裁决路径 `SyncConflictController.kt:136-139` 传了 `pendingRemoteEtag` ⇒ 「E2 整改」只落在两条合并上传路径之一。⑤ 可达性三条：`SyncCycleRunner.kt:344`（`commitLocal` 已收 412＝远端确认已变）、`SyncCycleRemoteOutcomes.kt:92`（`ConflictDetected`）、`:56`。⑥ `overwriteRemoteWithoutPrecondition` 在该路径**不生效**（`SyncEngine.kt:440-455` 不读该标志），故无「语义本就如此」的退路。⑦ 现有用例不构成锁定：`SyncEngineTest.kt:268` 只是不传 etag 的基线前移冒烟测。
- **背景与根因**：乐观锁在「探测远端 → 下载合并 → 上传」窗口的**最后一步**被撤除，而该窗口包含一次 KDF 级全库序列化与一次分钟级网络往返（`SyncNetworkOptions` callTimeout 量级）。后果是他端在窗口内的写入被无条件覆盖，且覆盖后 `advanceBaseAndPersist` 把基线前移——被覆盖的内容在其它设备上**再也看不到差异**，属静默数据丢失。项目对并发写保护已有明确立场（`SyncEngine.kt:431-434` 的自述、`docs/security/同步层记录级完整性威胁建模.md`）。
- **叠加缺陷（必须同批改）**：`sync/src/main/java/com/keepasskey/sync/model/SyncModels.kt:91-99` 的 `cleanEtag` 剥掉 `W/` 弱标记，而 `sync/src/main/java/com/keepasskey/sync/webdav/WebDavUrlCodec.kt:27-31` 的 `formatHeaderEtag` 只补引号 ⇒ 发往 `If-Match`（`WebDavSyncProvider.kt:211`）与 MOVE 的 `If`（`:295`）恒为强 ETag，在严格遵循 RFC 7232 的服务器上永不匹配。**只修前一条不修后一条，会把「静默覆盖」变成「合并后上传恒 412、界面永久提示『决策期间远端已更新』」的死局**，故两条一次收口。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt`（`:368` 漏传、`:276` 形参）、`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt`（`markResolvedAndUpload` 回退分支与其 KDoc）、`sync/src/main/java/com/keepasskey/sync/model/SyncModels.kt`（`cleanEtag`）、`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUrlCodec.kt`（`formatHeaderEtag`）、`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt`（`If-Match` / `If` 头）、`app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt`（`remoteEtag` 来源与三条可达路径）。
- **验收标准**：AC① `autoMergeAndUpload` 透传本轮探测到的冲突时刻 ETag，与用户裁决路径共用**同一判据**（禁止两处各写一份），并删除或改写「null 即回退重探」的默认分支，使「无基线可校验」成为唯一可走回退的显式情形；AC② 弱 ETag 语义收口：保留 `W/` 或按 RFC 7232 的强/弱匹配规则一致化读写两侧，禁止「剥标记后做强比较」；AC③ 412 处理路径可回归：合并上传遭 412 时**重新进入冲突流程**而非归一为一次性错误；AC④ 新增接线守卫用例，锁定「合并上传必须携带冲突时刻 ETag」与「弱 ETag 服务器不产生恒 412」两事实（沿用 `SyncEngineTest.kt:470-484` 的 fake-provider 断言形态，禁「测试内自算自证」）；AC⑤ **外部证据前置**：弱 ETag 覆盖面须以真实 DAV 服务器矩阵实测（Apache/mod_dav、nginx-dav、Nextcloud、IIS）取证，现有 `WebDavSyncScenarioTest.kt:383-402` 用脚本化 mock 只断言头形态、`tools/local-sync/run_webdav.py` 不产 ETag，**不构成**证据；矩阵未跑通的服务器须按规则 8 与项目「未执行」口径**如实标注**，不得以宿主绿推定闭环。

> **开工前前提复核（2026-09-23）**：条目七点核实方式与行号逐一反校属实——漏传参数、回退重探分支、
> KDoc 自述矛盾、三条可达路径（`SyncCycleRunner.kt:351` 传 `commitResult.remoteEtag` /
> `SyncCycleRemoteOutcomes.kt:63` 传 `openResult.etag` / `:99` 传 `openResult.remoteEtag`）、
> `cleanEtag` 剥 `W/`（且弱标记判定在剥引号之后，连「不透明值以 `W/` 开头」的合法标签也被误剥）、
> `formatHeaderEtag` 只补引号、现有用例无锁定——全部成立，无漂移勘误。

---

## 2. 整改

### 2.1 AC①：冲突时刻 ETag 透传 + 回退分支显式化

- **`SyncConflictController.expectedEtagForConflictUpload`（新增，internal）**：冲突上传 If 预条件
  期望值的**唯一判据**——`cleanEtag(conflictMomentEtag).ifEmpty { null }`（`cleanEtag` 幂等）。
  用户裁决路径（`resolveConflicts`）与自动合并路径（`autoMergeAndUpload`）共用，两处各写一份被禁止。
- **`autoMergeAndUpload`** 新增 `conflictEtag: String` 形参（`handleConflictMerge` 的 `remoteEtag` 下传），
  `markResolvedAndUpload` 调用补 `expectedEtag = expectedEtagForConflictUpload(conflictEtag)`——
  与「E2 整改」的用户裁决路径对齐；
- **`SyncEngine.markResolvedAndUpload`**：删除「`expectedEtag` 空白 ⇒ 重探 `provider.getMetadata` 当前值」
  的回退分支（原 `:446-447`），签名 `expectedEtag: String?` 去掉缺省值强制调用方显式传递；
  空白语义改写为「**无基线可校验**的唯一显式回退情形 ⇒ 无预条件上传（与 `commitLocalForce` 同语义，
  无 ETag 服务器既定口径）」，KDoc 明文禁止在本方法内回退重探充当预条件。

### 2.2 AC②：弱 ETag 语义收口（读写两侧一致化）

设计口径（RFC 依据见卷首语）：**规范形态保留弱标记**（`W/"abc"` → `W/abc`），写侧按比较规则选用形态——

- **`SyncModels.cleanEtag`**：剥引号但**保留 `W/` 前缀**；弱标记判定改在**剥引号之前**依语法形态
  （`W/` 后紧跟引号）进行——不透明标签内容本身可以 `W/` 开头（`"W/abc"`），剥引号后与弱标签不可区分，
  先判弱才能不误伤（旧实现把此类标签的 `W/` 也剥掉）。新增 `isWeakEtag`（规范形态 `W/` 前缀判定；
  病态不透明情形按弱处理、写侧退化为安全的 412 → 冲突重检，KDoc 已记）。
- **`WebDavUrlCodec.formatHeaderEtag`**：弱形态输出 `W/"abc"`，强形态输出 `"abc"`。
- **`WebDavSyncProvider.uploadAtomic` 的 MOVE `If` 头**：改经 `formatHeaderEtag`，
  输出 `([W/"abc"])`——RFC 4918 §10.4.4 允许服务器对 If 头用弱或强比较，回传服务器签发的原形态
  （弱存储标签 × 弱比较）才可匹配；§10.4.9 明示 `[W/"…"]` 为合法形态。
- **`WebDavSyncProvider.upload` 的 `If-Match`**：弱期望 ETag **不发送** `If-Match`（RFC 7232 §3.1
  强比较下弱标签永不匹配，发送即恒 412 死锁；RFC 4918 §8.6 明示弱 ETag 不能用于 If-Match）。
  跳过不是静默降级：本应用 WebDAV 的目标资源乐观锁由 `uploadAtomic` 的 MOVE `If` 头承担
  （弱比较可用），生产写路径不经过该分支。强期望 ETag 行为不变。
- **`S3SyncProvider.upload`**：同口径 fail-closed——弱期望 ETag 构造不出 If-Match 条件写
  （S3 无 MOVE-If 等价物，跳过即无条件 PUT＝P2-244 已否决的静默覆盖），与「ETag 空白」同抛
  `ProtocolError` 拒绝放行；探测路径同守卫。S3 ETag 实际恒为强形态，此守卫为防御性。
- 未触面：`RemoteConsistencyProbe` 的 `baseEtag == remoteEtag` 比较两侧均为规范形态，自洽；
  S3 的 HEAD 预检比较两侧同用 `cleanEtag`，自洽。

### 2.3 AC③：合并上传 412 重新进入冲突流程（深度界限 1 次）

两条合并上传路径均不再把 `ConflictError` 归一为一次性错误：

- **自动路径（`autoMergeAndUpload`）**：上传 412 ⇒ 以合并产物（`mergedBytes`，密文序列化）为本地侧
  经 `engine.commitLocal` 重取最新远端（其 412 分支自带新鲜远端下载；远端若已回退到基线内容则本次
  上传即成功，落入与既有成功分支同语义的采纳路径）⇒ 返回 `AutoMergeUploadResult.Superseded`，
  由 `handleConflictMerge` 以 `localBytes = mergedBytes`、`localDbOverride = null`、
  `base = syncCache.readBaseContent`（上传失败 ⇒ 基线未动，仍为本轮合并所用底版）、
  `remoteEtag = 最新值` **重新进入冲突流程**（重入轮再次有条目级分叉则照常走待决用户决策通道）。
- **用户路径（`resolveConflicts`）**：上传 412 ⇒ 同机制重入；新增 `pendingRemoteCache` 持有待决会话的
  `SyncCache`（重入时取 basecache），`clearPendingConflictSession` 同步清空。
- **深度界限**：重入轮（`isReentry = true`）再次 412 ⇒ 如实上浮 `SyncOutcome.Error`
  （复用 `sync_error_remote_changed_during_resolve` 文案），不得无限循环。
- **所有权与擦除（P3-235 / P3-258 红线不破）**：重入侧**全新解析** `mergedBytes`（一次额外 KDF +
  整树构建，罕见路径可接受），与首轮树**零实例共享** ⇒ 首轮四棵树（local / remote / trustedBase /
  mergedDb）在取到最新远端之后、重入之前按身份集合判定擦除（`eraseDiscardedParseResults`），
  不错擦存活别名（`localDbOverride` 即会话树快照 ⇒ 身份判定自动跳过）；用户路径新增
  `eraseSupersededPendingTrees` 同口径处理待决四树。旧 412 分支的「clearPendingConflictSession 后
  泛化 Error」行为废止。

### 2.4 未触面

`SyncCycleRunner` / `SyncCycleRemoteOutcomes` 三条可达路径一行未改（本就把 `remoteEtag` 传到位）；
`applyForcedConflictStrategy`（用户显式强制策略，条目已声明不计入）、`commitLocal` / `commitLocalForce`、
缓存与基线写序均未动。

---

## 3. 验证

### 3.1 接线守卫用例（AC④）

- **`sync` 模块 `SyncModelsTest`**：新增 4 例（弱标记保留 / 弱标记判定先于剥引号不误伤 `"W/abc"` /
  规范化幂等 / `isWeakEtag` 判定），改 1 例（原「去除弱校验前缀与引号」的剥标记断言与 AC② 相悖，
  改为保留断言——测试资产按纪律**只改不删**，理由即本条整改本体）。
- **`sync` 模块 `SyncEngineTest`**：新增 1 例「`markResolvedAndUpload` 无基线时显式无条件上传而不重探
  当前值」——远端存在 ETag（`etag-live-remote`）时传 `expectedEtag = null`，断言 Provider 收到的期望值
  **恰为 null**（旧实现在此重探并下传 `etag-live-remote`，断言即失败）；既有 412 用例补 1 行断言
  「冲突时刻 etag 原样透传到 Provider 预条件」；既有基线前移用例的调用补显式 `expectedEtag = null`。
- **`sync` 模块 `WebDavSyncScenarioTest`（MockWebServer 头形态断言）**：改 1 例（弱标记保留 +
  弱期望不产生 `If-Match` 头 + 强期望仍 `"strong"` 形态），新增 1 例「弱校验 ETag 服务器全链路上传
  不产生恒 412」——`StatefulDavDispatcher` 扩展 `weakEtagPaths` 弱存储标签模拟（PROPFIND/GET/PUT/MOVE
  下发 `W/"…"`、If 头弱比较裁决）：弱形态首传 → 弱形态预条件覆盖上传成功（`moveLog` 断言 If 头含
  `[W/"`）→ 过期弱基线仍被弱比较识破 412 ⇒ 乐观锁语义不因弱形态失效。
- **`sync` 模块 `WebDavSyncProviderTest`**：1 例断言随规范形态更新（`W/weak-etag-999`，注明旧断言
  与 AC② 相悖）。
- **`app` 模块 `SyncCoordinatorTest`**：`MemorySyncProvider` 增加 `lastExpectedEtag` 记录与
  `forceConflictUploads` 一次性强制 412 开关；新增 2 例——①「自动合并上传必须携带冲突时刻 ETag」
  （本地新增条目 A × 远端新增条目 B ⇒ 自动合并 ⇒ Provider 收到恰为冲突时刻的 `etag_remote_added_B`）；
  ②「合并上传 412 后重新进入冲突流程完成二次合并上传」（`forceConflictUploads = 2`：合并上传 412 ⇒
  重入 ⇒ 二次合并 ⇒ 上传成功，双侧新增均存活、冲突清单清空）。所有断言均为 fake-provider 实收值
  对拍，无「测试内自算自证」。

### 3.2 全量单测（JVM）

`.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**；
`python tools/doc/count_test_results.py`：**xml=369 tests=2572 failures=0 errors=0 skipped=13**
（较上批 2565 例净增 7，与新增守卫用例数一致）。

### 3.3 AC⑤ 如实声明：弱 ETag 真实 DAV 服务器矩阵**未执行**

按条目 AC⑤ 与项目「未执行」口径逐项声明：

| 矩阵对象 | 状态 | 说明 |
|---|---|---|
| Apache/mod_dav（弱 ETag 高发面） | **未执行** | 本会话无该服务器可用；`tools/local-sync` 联调链路代理侧不可运行（既有口径），且 `run_webdav.py` 不产 ETag，本就不构成弱面取证 |
| nginx-dav | **未执行** | 同上 |
| Nextcloud | **未执行** | 同上 |
| IIS | **未执行** | 同上 |

本批证据止于：RFC 全文核对（读写两侧形态与比较规则的**协议层**依据）+ 有状态 mock 的头形态与
弱比较裁决断言（**形态层**守卫）。真实服务器矩阵（弱 ETag 服务器对 `If` 头的比较函数实际取向、
MOVE 事务写兼容性）属「只有真实环境才能证伪」的残余面，**不得以宿主绿推定闭环**——后续在真实
DAV 环境可用时按规则 8 补测并回填本批次。本仓产物互操作对拍（`keepassxc-cli` / `pykeepass`）
不触及 WebDAV 弱 ETag 面（同步传输层与本仓 `.kdbx` 格式互操作无关），本批无对拍产物义务。

### 3.4 验证面如实声明

- 本批**未触及** `crypto/src/main/rust/**`、JNI 绑定或任一 `Native*` 引擎，亦未新增
  `*/src/androidTest/**` 用例 ⇒ 无设备侧强制验证义务，未跑 `connectedDebugAndroidTest`；
- 被改面（ETag 规范化、If 头形态、引擎预条件参数、412 重入）全部可在宿主 JVM 以 fake-provider /
  MockWebServer 实收值对拍覆盖；412 重入的所有权擦除路径由既有 `SyncPendingTreeErasureTest`
  全绿回归背书（擦除原语未改，新增路径复用同一原语与判据）。

---

## 4. 过程留痕

- 首轮全量测试暴露 1 例既有用例与新规范形态相悖（`WebDavSyncProviderTest` 弱标记断言），按新口径
  修正后复跑全绿——属预期中的行为契约变更，非实现缺陷；
- 弱标记判定从「剥引号后」移到「剥引号前」顺带修复了一个条目未载的既有缺陷：不透明值以 `W/` 开头的
  合法标签（`"W/abc"`）旧实现被误剥成 `abc`，读写两侧比对失配；本批一并修正并以用例锁定；
- AC⑤ 的未执行不是「豁免」而是「显式残余」：批内所有宿主可证面已证毕，弱 ETag 真实矩阵留待
  真实 DAV 环境补测。
