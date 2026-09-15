<a id="s84"></a>
## §84 自动填充认证结果崩溃级修复与 requestCode 单调化批次（2026-09-16）：ISSUE-P2-73 / ISSUE-P3-122（IPC-01）

> **本批次缘起**：第四轮定版批注要求 `ISSUE-P2-73` 与 `IPC-01`（`ISSUE-P3-122`）「**须同批**」，
> 故两项合并实施。**本批不闭环任何一条**：`P2-73` 的 AC② 依定版批注**不得实施**，AC③ 需真实
> autofill 客户端；`P3-122` 的 `IPC-10` 未做。价值在于**修掉一条崩溃级缺陷**、**消除一族串扰面**，
> 并把一处**硬冲突**显式登记为决策点。

### 84.1 `P2-73` AC①：原条文把它写轻了——这是 **Android 12+ 的崩溃级缺陷**

原条文描述为「用裸 `setResult(RESULT_OK)`，**不带** `EXTRA_AUTHENTICATION_RESULT`（协议漂移）」。
按 `AGENTS.md` 规则 7 取官方 `FillResponse.Builder#setAuthentication` 原文，实情更重：

> **IMPORTANT**: Extras must be non-null on the intent being set for Android 12 otherwise it will cause
> a crash. **Do not use `Activity.setResult(int)`**, instead use `Activity.setResult(int, Intent)` with
> non-null extras. Consider setting `EXTRA_AUTHENTICATION_RESULT` to null **or use `Bundle.EMPTY`** with
> `Intent.putExtras(Bundle)` on the intent when finishing activity to avoid crash.

本项目 minSdk 36、targetSdk 36 ⇒ **恒处在崩溃区间**。据此：

| 位置 | 整改 |
|---|---|
| `AutofillUnlockActivity.completeAuthResult()` | `setResult(RESULT_OK)` → `setResult(RESULT_OK, Intent().putExtras(Bundle.EMPTY))` |
| `AutofillConfirmActivity`（锁定门控通过分支） | 同上 |

**为何取 `Bundle.EMPTY` 而非伪造一个空 `Dataset`**：官方把 `Bundle.EMPTY` 明列为等价做法；
两条路径均由框架侧缓存数据集，自身不产出载荷。`Bundle.EMPTY` 既满足「extras 非空」的崩溃前置，
又**逐字保持**「回传成功、不改动载荷」的既有语义——本批不借修崩溃之机改变填充行为。

### 84.2 AC① 的「回传数据集」与 AC② 存在**硬冲突**（登记为决策点，本批不自行择一）

要让框架真正把数据集写入表单，认证 Activity 必须构造并回传 `Dataset`。它拿到字段 `AutofillId`
的**正规**途径是框架注入的 `EXTRA_ASSIST_STRUCTURE`——而该注入**要求 PendingIntent 为
`FLAG_MUTABLE`**，正是第四轮批注明令**不得**改的那一项（理由：本仓这两条路径不消费 fillIn extras，
改 MUTABLE 是「为对齐文档而降低安全性」）。另一条路是服务把已填充的 `Dataset` 经自家 Intent
传给 Activity，代价是**新增一份明文 Parcel 副本**，与「敏感数据铁律」相抵。

两条路各有代价、且**互相排斥**：

| 方案 | 满足 | 代价 |
|---|---|---|
| 改 `FLAG_MUTABLE` + 活动读 `EXTRA_ASSIST_STRUCTURE` 自建 Dataset | 官方完整语义 | 与第四轮批注冲突（扩大注入面） |
| 服务把已填充 `Dataset` 经自家 Intent 下传 | 官方完整语义 | 新增明文 Parcel 副本（敏感面扩大） |
| **本批做法**：保持 `FLAG_IMMUTABLE` + `Bundle.EMPTY` | 消除崩溃；其余语义不变 | **数据集回传仍未达成** ⇒ AC① 只完成「崩溃修复」这一半 |

⇒ **须由定版方在「AC② 撤销」与「AC① 数据集回传」之间裁决**；实施者不得单方面择一
（与 `ISSUE-P2-47` AC②、`ISSUE-P2-79` AC② 同一处置体例）。

### 84.3 AC② 未实施，并由守卫**反向锁定**现行口径

依定版批注不实施；同时新增接线守卫，防止后人「顺手统一」为 MUTABLE：
选择器恒 `FLAG_MUTABLE`（**恰 1 处**）、解锁与确认恒 `FLAG_IMMUTABLE`（**恰 2 处**），
并锁定选择器路径的理由注释（`// 框架需注入 fillIn extras，必须 FLAG_MUTABLE`）。

### 84.4 `IPC-01`：认证 PendingIntent 的 requestCode 单调化

**缺陷形态**（第四轮裁定为成立-部分）：三条认证入口分别使用常量 requestCode
（解锁 `100` / 确认 `100+index` / 选择器 `200`），且一律带 `FLAG_UPDATE_CURRENT` ⇒ 两次不同的
`onFillRequest` 一旦落到同一 requestCode，后一次会**就地更新**前一次的 PendingIntent extras，
用户点中的是旧候选、实际拉起的却是新上下文的 Activity（TOTP 错配；`ISSUE-P3-42` 会话授权开启时
叠加 30 秒授权串扰）。「凭据值串扰」按裁定**不成立**。

**修复**：新增进程级单调分配器 `authRequestCodeAllocator`（`AtomicInteger`）+
`nextAuthRequestCode()`，三条入口一律改用之——与 CM 通道
`CredentialResponseAssembler.RequestCodeAllocator` **同构**。计数器**刻意不随响应重置**
（重置会重新引入复用）。

### 84.5 新增覆盖

`AutofillAuthResultWiringTest`（新，3 例，源码接线守卫）：
① 两个认证 Activity 必须使用**双参** `setResult(RESULT_OK, Intent)`，且不得残留单参形态；
② 三条认证入口必须经单调分配器，且不得残留把常量直接交给 `PendingIntent.getActivity` 的写法；
③ FLAG 口径反向锁定（见 §84.3）。

**既有守卫的一处加固**：`AutofillConfirmDeliveryLockTest`「每一处回传 RESULT_OK 之前必须经过锁定门控」
原以固定 **400 字符窗口**判定门控是否出现在回传点之前。本批为该回传点补写说明注释后，
门控被判**挤出窗口而误红**——说明该判据对注释长度敏感（缩窗口则漏判、扩窗口则误判）。
已改为**结构化判定**：回传点之前、且与回传点处于**同一函数体内**必须出现过门控调用。
这是「源码文本锚点必须锚在语义结构上，而非脆弱的字符距离上」的又一实例（对照 §81.6）。

### 84.6 验证证据（2026-09-16）

- `:app:testDebugUnitTest --tests "com.keepasskey.app.autofill.*"` → **BUILD SUCCESSFUL**
  （`AutofillAuthResultWiringTest` 3/3、`AutofillConfirmDeliveryLockTest` 4/4、其余 autofill 套件全绿）。
- 全量 `test --rerun-tasks --max-workers=1` 与 `assembleRelease`、真机
  `:app:connectedDebugAndroidTest` 结果见提交信息。

### 84.7 未完成与下一批

1. **`P2-73` AC① 的数据集回传**（§84.2 决策点）与 **AC③ 设备侧实测认证填充链路**——
   后者需要真实 autofill 客户端触发认证流，本机无 ADB 侧入口（与 `P2-83` AC③ 同类边界），已登记残余。
2. **`P3-122` IPC-10**：`onSaveRequest` 的超时预算（需包裹整段协程体并整理收尾语义）。
3. 表内其余：`P2-47`（AC② 决策点）、`P2-79`（需产品确认）；
   `P3-76` / `P3-83` / `P3-120~P3-125`（除 122 的 IPC-01 已完成）。
4. `P3-120`（真机 Frida 实测完整性检测命中率，第四轮标 **P1/UNVERIFIED**，为 4 条目的共同前提）
   条件已具备（本机已 root），宜插队。
