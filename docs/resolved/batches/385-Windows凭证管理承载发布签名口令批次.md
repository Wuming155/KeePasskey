# §385 Windows 凭证管理承载发布签名口令批次（`ISSUE-P3-418` 整条闭环）

> **触发**：2026-10-01 用户指示「稳定版本的签名应该放到 Windows 的凭证管理里面，避免明文存储」。
> **结案条目**：`ISSUE-P3-418`（整条闭环，同日登记同日结案）。

---

## 0. 原文登记

> **ISSUE-P3-418**：发布签名口令支持从 Windows 凭证管理器读取——消除本地明文落盘。
>
> - **状态（2026-10-01 用户指示，同时认领整改）**：进行中。
> - **核实时间点**：2026-10-01。
> - **核实方式**：用户指示「稳定版本的签名应该放到 Windows 的凭证管理里面，避免明文存储」；现状核对 `app/build.gradle.kts:12-27`——发布口令当前只能来自**明文** `keystore.properties`（本地盘）或环境变量（CI），Windows 本机无加密承载通道。
> - **根因**：口令解析链只有「环境变量 → keystore.properties」两级，本机开发常态是走明文属性文件；密钥库文件（`release.jks`）与口令同盘明文存放，机器被读盘即发布密钥失控（P2-55 防的是口令随仓库泄露，本条补的是口令随本地盘泄露）。
> - **整改方案**：新增第三条解析通道——Windows 凭证管理器「普通凭据」（CredRead 通用凭据，经 `tools/signing/read-cred.ps1` P/Invoke 读取），解析顺序改为**环境变量 → Windows 凭证管理器 → keystore.properties**（存量通道全兼容，CI/Linux 不受影响）；凭证目标名 `KeePasskey/Keystore/StorePassword` 与 `KeePasskey/Keystore/KeyPassword`；P2-55 构建期 fail-closed 断言原样生效于新通道。
> - **涉及文件**：`app/build.gradle.kts`、`tools/signing/read-cred.ps1`（新建）、`keystore.properties.example`（通道说明）。
> - **验收标准**：AC① cmdkey 写入测试凭据后脚本可读出、目标不存在时静默降级；AC② gradle 解析顺序实测：凭证管理器优先于 keystore.properties，且新通道口令同样触发 P2-55 fail-closed 断言；AC③ 全量 test 绿 + 门禁 8/8。

---

## 1. 整改

| 层 | 变更 |
|---|---|
| `tools/signing/read-cred.ps1` | **新建**：P/Invoke `advapi32!CredReadW`（`CRED_TYPE_GENERIC`，type 1）读「普通凭据」口令 blob（UTF-16LE 解码），UTF-8 写 stdout。**退出码契约**：0=读到 / 1=目标不存在（预期降级，静默）/ 2=脚本或 API 异常（stderr 诊断 + 调用方告警）。stdout 只承载口令本身，无任何日志 |
| `app/build.gradle.kts` | 新增 `readWindowsCredentialPassword(target)`（PowerShell 子进程调上述脚本；非 Windows / 脚本缺失即不启用，CI/Linux 零影响）；storePassword / keyPassword 解析链扩为**环境变量 → Windows 凭证管理器 → keystore.properties**（storeFile / keyAlias 非秘密，不加通道）；exit=2 或子进程异常时 `logger.warn` 后降级（不 fail 构建，保持「未配置签名＝出未签名包」的既有语义）；P2-55 fail-closed 断言块原样不动，标签更新为三通道全称；注释「豁免通道：不存在」同步补凭证管理器通道 |
| `keystore.properties.example` | 硬性要求第 3 条改写为三通道注入说明（CI Secret / Windows 凭证管理器 / 本地文件），给出凭据管理器 UI 与 `cmdkey /generic:` 两种写入方式（特殊字符口令推荐 UI 避 shell 转义）；注明走凭证管理器后本文件可只留 `storeFile` / `keyAlias` 非秘密字段；re-key 说明尾注推荐迁入凭证管理器 |

**红线遵守**：P2-55 三条 fail-closed 断言（长度 ≥ 16 / 禁命中公开示例值 / 禁模板占位符）对**所有通道**一视同仁，凭证管理器通道不设任何旁路；解析优先级固定 环境变量 → 凭证管理器 → properties（凭证管理器优先于明文文件，避免「两边都有、明文先中」使迁移失效）。

---

## 2. 验证

- **AC①（脚本回路）**：`cmdkey /generic:"KeePasskey/Keystore/StorePassword"` 写入测试凭据后，`read-cred.ps1` 读出**逐字原值**（exit 0、stdout 无尾随换行）；`cmdkey /delete:` 后重读 exit 1（静默降级，符合「未配置该通道」语义）。
- **AC②（gradle 优先级 + fail-closed）**：向凭证管理器植入历史公开泄露口令 `keepasskey123`（本地 `keystore.properties` 内是真实高熵口令），`.\gradlew.bat :app:tasks` **配置阶段即被 P2-55 断言拦截**——报错标签为新通道全称「storePassword（环境变量 / Windows 凭证管理器 / keystore.properties）」——同时证明①凭证管理器优先于明文属性文件被选中、②fail-closed 断言对新通道生效无旁路；删除测试凭据后重跑 `:app:tasks` BUILD SUCCESSFUL（4s），存量通道解析不受影响。
- **AC③（全量回归）**：`.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**（114 tasks executed）；聚合 `xml=498 tests=3222 failures=0 errors=0 skipped=13`（与 §384 基线一致，零用例增减——本批未动任何生产/测试代码路径）。

### 2.1 门禁读数（原样粘贴，归档后采集）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=36  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 383 份；分册登记 385 条；全量索引 385 条；最大 §385）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 556 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

---

## 3. 如实声明

1. **首跑 `--rerun-tasks` 偶发失败（与本批改动无关，依据如下）**：第一次全量强制跑在 `:app:testDebugUnitTest` 失败——`SyncCacheEvictorTest > 锁库后同步缓存目录为空` AssertionError（daemon 日志定位，2024 tests completed, 1 failed）；该用例的 NTFS `listFiles()`「鬼影条目」假阳性残余（约 3% 概率）已由 `docs/architecture/已知工程限界.md` §7 与用例自身 KDoc 登记，属**已知限界**而非新回归——本批改动不在其调用链上（纯 build 脚本 + 新增 ps1）。独立复跑 1 次通过（仅重执行失败 task）后，全量 `--rerun-tasks` 再跑 114/114 全绿，计数回基线。
2. **用户侧迁移动作未代办**：本批交付的是「通道 + 守卫 + 模板指引」；用户真实口令是否已由 `keystore.properties` 迁入凭证管理器属用户侧操作（写入即生效，无需改代码），模板 `keystore.properties.example` 有完整 UI / cmdkey 两种写法指引。迁移前现状（明文属性文件）仍可正常出包，行为不回退。
3. **CI 路径未实跑**：GitHub Actions 为 Linux，凭证管理器通道在非 Windows 上直接短路返回 null（代码路径与现状等价），未在真实 CI 上验证——风险为零但如实记录。
4. 未跑真机 / 未触任何 `*/src/**` 代码，装机验证不适用。

