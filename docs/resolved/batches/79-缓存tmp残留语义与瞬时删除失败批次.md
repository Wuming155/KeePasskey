<a id="s79"></a>
## §79 缓存 `.tmp` 残留语义与瞬时删除失败批次（2026-09-15）：ISSUE-P3-108

> **本批次缘起**：§78 施工期间的全量单测中，`SyncCacheTest > clear 与 clearAll 均不删除防回滚状态文件`
> 失败（`sync` 模块 `206 tests completed, 1 failed`），残留物为 `cacheDir` 下的 `*.tmp`。
> 该失败正是 `ISSUE-P3-108` 所登记的「`.tmp` 残留窗口」形态——其 AC 明确要求：
> **「须先判定『残留 .tmp 是否属可观测缺陷』再定口径，不得静默放宽断言」**。本批据此判定、修根因、补覆盖。

### 79.1 语义判定（先判定，再定口径）

`cacheDir/sync` 下的 `.tmp` 是**原子写的中间产物**，其内容是 KDBX 密文快照（或防回滚状态）的**片段**。
锁定 / 退出清理的语义是「**销毁密文快照**」（ISSUE-P1-07 / F-23），
故 **残留 `.tmp` 构成可观测缺陷**（清理不彻底 + `deleteOrphanTmpFiles` 之外的磁盘无界增长），
**不予排除**、也**不放宽**断言。据此选择「修根因」而非「改断言」。

### 79.2 根因与修复

**根因**：`File.delete()` 在**句柄尚未释放**的瞬间返回 `false`（Windows 桌面调试环境与
「写入方刚结束」的时序；Android 上同理但概率更低）。原实现把该**瞬时**失败当作**清理失败**：
`clearAll()` 返回 false → `SyncCacheEvictor` 记录「同步缓存残留 N 项，锁定后密文可能仍可恢复」的告警，
并把 false 一路传回退出清理入口。

**修复（`sync/src/main/java/.../engine/SyncCache.kt`）**：

| 改动 | 说明 |
|---|---|
| `deleteCacheChild` 增**有界重试** | 删除失败且目标仍存在时，最多重试 `3` 次、间隔 `15 ms`，再按「删除后目标是否仍存在」判定成功（§74 的幂等契约保持） |
| `clear(remotePath)` 改走同一原语 | 原先此处直接 `file.delete()` 且**丢弃返回值**，与 `clearAll` 行为不一致；现共用同一「有界重试 + 幂等」删除原语 |
| 常量 `DELETE_RETRIES` / `DELETE_RETRY_GAP_MS` | 就近声明并注释来源（ISSUE-P3-108） |

**代价（如实声明）**：仅在**真的删除失败**时才会等待，**每个失败项最坏多等 45 ms**；
正常路径（删除一次即成功）**零额外开销**。

### 79.3 新增覆盖（按 AC 要求）

| 用例 | 覆盖点 |
|---|---|
| `updateBase 原子写的临时文件在 clearAll 后不得残留` | AC 指定的 **`SyncCache.updateBase` 路径**（base/meta 两个 tmp）——清理后**断言目录内无任何 `.tmp`**（不放宽） |
| `异常路径残留的 tmp（写中断）必须被 clearAll 清理` | **异常路径无残留**：模拟写中断留下的 `<key>.cache.<uuid>.tmp` 与**未交付**的 `<key>.rollback.<uuid>.tmp`（`clear()` KDoc 明示该边界）都必须被清；**已交付**的 `<key>.rollback` 必须保留（F-23 不变量） |

### 79.4 验证证据（2026-09-15）

- **首现失败原文（保留）**：
  `除防回滚状态外不得残留其他缓存文件 expected:<[...990.rollback]> but was:<[...990.CACHE.tmp, ...990.rollback]>`
  （`SyncCacheTest.kt:147`）——即清理后残留 `<hash>.CACHE.tmp`，且该次 `clearAll()` **返回 true**
  （断言在 144 行通过），说明残留发生在**枚举与删除之间 / 删除瞬时失败**的窗口内。
- **本批修复后**：`.\gradlew.bat :sync:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`；
  全量 `.\gradlew.bat test --rerun-tasks --max-workers=1` → **1807 例 / 0 失败 / 0 错误 / 13 跳过**
  （sync **206 → 208**，即本批新增 2 例）。
- **发布产物**：`.\gradlew.bat assembleRelease --rerun-tasks` → `BUILD SUCCESSFUL 2m54s`，
  日志零 `w:`，产物 `app-release.apk`（15 484 411 B）。

### 79.5 过程缺陷与残余（如实留痕）

1. **残留条目的命名与源码不一致，未能定位其来源（如实登记）**：该次残留名为
   `<大写十六进制键>.CACHE.tmp`，而本仓 `SUFFIX_CACHE = ".cache"`（**小写**）、
   键由 `SyncCache.sha256Hex` 产出（**小写**），**无法从源码复现该命名**。
   本批**未**臆造解释：修复针对的是**可复现的根因**（删除瞬时失败），
   且断言保持严格——无论该条目由何种路径产生，「清理后不得残留」的语义都要求它被删除。
   若后续再次观测到**大写**命名残留，应作为独立线索排查（可能来自本仓之外的写入者）。
2. **该窗口此前长期被当作「Windows 时序问题」**（`ISSUE-P3-108` 原记「单独复跑即通过」）——
   本次因 §74 的幂等修复 + 本批的有界重试，把「偶发」转成了「要么成功、要么如实失败」的确定性行为。
   ⇒ 纪律延续（§74）：**偶发失败必须追到可复现的机制，而不是停在「时序问题」**。
