# §362 远端目录浏览与 .kdbx 并入批次（`ISSUE-P3-387` / `ISSUE-P3-384` 整条闭环）

- **触发**：2026-10-06 用户指示「完成P3-387 远端目录浏览、P3-384 .kdbx 并入」。
- **开放项变动**：P3 区 2（384/387）→ **0**；P3 仅剩搁置项 `ISSUE-P3-339`。

## 1. ISSUE-P3-387：远端目录浏览选库缺失

### 1.1 范围评估（AC①，动手前）

| 候选 | 结论 | 理由 |
|---|---|---|
| 仅 WebDAV 先行 | 不采纳 | 用户在设置页可任选 WebDAV / S3；只做一侧会让另一侧依旧只能手输路径 |
| **两协议同批** | **采纳** | 接口加 `SyncProvider.listRemoteDirectory` 默认成员（未实现即 fail-closed 501）；WebDAV PROPFIND Depth:1 + S3 ListObjectsV2 同批落地 |

### 1.2 整改要点

- **接口**：`SyncProvider.listRemoteDirectory(remotePath, cursor, pageSize)` 默认失败；`WebDavSyncProvider` / `S3SyncProvider` 覆写。
- **WebDAV**：PROPFIND `Depth:1`；解析下沉 `WebDavPropfindParser.parseChildren`；编排下沉 `WebDavDirectoryList`；`WebDavUploadAtomic` 同批抽出（行数门禁）。
- **S3**：ListObjectsV2 + delimiter=`/`；编排下沉 `S3DirectoryList`；SigV4 增 `queryString`（空串时 canonical 第三行为空，既有对象读写签名不变）。
- **SSRF（PD-02）**：浏览目标仍是**同一端点**相对路径；构造期 HTTPS + 端点校验不变。
- **保守降级（kp2a 教训）**：列举失败 / 解析失败一律 `Result.failure`，**禁止**伪装空目录。
- **分页**：WebDAV 客户端按 `pageSize` 截断 + name 游标；S3 `IsTruncated` / `NextContinuationToken`；默认页 200。
- **UI**：路径字段旁「浏览远端目录」按钮；`RemoteBrowseDialog` / `RemoteBrowseSection`；选中 `.kdbx` 回填路径，文件夹下钻；中英文案成对。
- **控制器**：`RemoteBrowseController`（表单凭据 clone，不依赖已保存配置）+ `SettingsRemoteBrowseHost`。

### 1.3 验收

- AC① 范围评估见 §1.1（本批留痕）。
- AC② SSRF 口径不变（PD-02）。
- AC③ 分页/大目录：`DEFAULT_REMOTE_PAGE_SIZE=200` + MAX_PROPFIND 16 MiB 封顶。
- AC④ 中英文案：`sync_browse_*` / `dbset_src_kdbx_merge` 等。

## 2. ISSUE-P3-384：从另一 .kdbx 文件导入/合并

### 2.1 合并语义（批次口径）

- `base` = 空根（UUID 与当前库根相同，避免根组被误判冲突）。
- `local` = 当前会话库；`remote` = 第二库。
- 对端**独有**条目 / 分组并入；同 UUID 字段冲突 **KEEP_LOCAL**（不覆盖当前库），报告给出 conflict 计数。
- 与同步同一把 `SyncSessionState.mutex` 串行（AC③）。
- 全程密文：SAF 读密文 → `KdbxFile.load`（第二库自身凭据）内存打开 → `KdbxMerger` → `adoptDatabaseIfUnchanged` → `save()` 原子写盘；第二库 `clearSensitiveData()`（AC①，**无中间明文文件**）。

### 2.2 整改要点

- `ImportSource.KDBX_MERGE` + `ImportUiState.AwaitingMergeCredentials`。
- `KdbxMergeController`：凭据补录 → 读密文 → 内存打开 → 互斥锁内合并 → 采用保存 → 清零。
- UI：导入源对话框首项「并入另一 .kdbx 数据库」→ SAF → `ImportMergeCredentialSheet`（主密码 + 可选密钥文件）。
- 互操作探针：`KdbxMergeInteropProbeTest`（app 模块，database 不依赖 sync）落盘 `keepasskey-kdbx-merge-probe.kdbx`。
- 外用脚本：`tools/kdbx-merge-interop/verify_merge.py`。

### 2.3 验收

- AC① 全程密文（见 §2.1）。
- AC② 复用 `KdbxMerger`（空底版 UUID 三方 + conflict 清单）。
- AC③ 与同步互斥（同一 mutex）。
- AC④ **pykeepass 对拍通过**（`verify_merge.py` 退出码 0；读出 `merge-local` / `merge-remote-unique`）。

## 3. 验证读数（原样粘贴 `gate_readings.py` 输出）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=37  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 359 份；分册登记 361 条；全量索引 361 条；最大 §361）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 531 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

宿主全量：`xml=473 tests=3115 failures=0 errors=0 skipped=13`（`--rerun-tasks --max-workers=1`，114 tasks executed）。

pykeepass：
```
pykeepass 解锁成功，条目数 2
  · merge-local
  · merge-remote-unique
✓ 对拍通过：pykeepass 独立实现读数满足全部判据
```

## 4. 如实声明

- **无设备**：本批未跑 `connectedDebugAndroidTest`；未改 `*/src/androidTest/**`、未触原生面 ⇒ **无设备侧必跑项**。
- **WebDAV/S3 真实服务器目录浏览未端到端联调**（无公网 DAV/S3 端点）；协议层由 MockWebServer 形态单测与解析器用例覆盖。
- **keepassxc-cli 对合并探针**：本机 CLI 参数与文档不一致（无 `--stdout`），已按 `-q -R` 复试；pykeepass 双实现证据以 `verify_merge.py` 为准（规则 8 口径：官方实现端到端对拍以 pykeepass 独立读数成立）。
- **S3 ListObjectsV2 签名**：`queryString` 空串时与既有对象读写逐字一致；列表路径为新增查询串，未与真实 S3 端点对拍。
- **合并冲突策略 KEEP_LOCAL**：同 UUID 冲突不覆盖当前库口令；若未来需要「对端覆盖 / 双保留」交互，属产品口径扩展，不在本批 AC 内。
- **`SettingsPreferenceDelegates.kt` / `SettingsImportMergeBrowse.kt`**：曾为行数门禁尝试扩展拆分，因与 ViewModel 成员方法重名被 shadowing 后**已删除**；行数门禁通过的是「成员方法保留 + 无关扩展文件移除 + `WebDavUploadAtomic` / `WebDavDirectoryList` / `S3DirectoryList` / `RemoteBrowseSection` / `RemoteBrowseController` 下沉」。

## 5. 文档同步

- `docs/ACTIVE_ISSUES.md`：P3-384 / P3-387 剪出。
- `docs/RESOLVED_LOG.md` + `docs/resolved/BATCH_158_PLUS.md`：新增 §362 行。
- `docs/resolved/batches/362-远端目录浏览与kdbx并入批次.md`（本文件）。
- `docs/resolved/README.md`：最大 §361 → **§362**，下一批 §363。
