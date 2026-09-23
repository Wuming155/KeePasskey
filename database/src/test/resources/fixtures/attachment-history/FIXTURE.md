# 外部管理器口径「中等尺寸附件 + 历史快照」fixture（读取半边）

> **落盘由 KeePassXC 官方 CLI（2.7.12）完成，请勿手工编辑**。出处与用途见 `ISSUE-P1-276`
> 与 `AGENTS.md` 规则 8。

- 库文件：`keepassxc-medium-attachment-history.kdbx`
- SHA-256：`1ae6bb38735de3d2f9784647ed029effcbad8bc9ff644d574f5b072bcd4e23f5`
- 口令：`attachment-budget-probe-2026`
- 库头版本：**KDBX 4.0**（`00 00 04 00`，由官方 CLI 写出；本仓写侧现为 4.1）
- 库内形态（官方 CLI `export` 读数）：`<History>` 1 块 / `<Entry>` 6 个 /
  `<Binary>` 6 个且**全部**为 `<Value Ref="0"/>` ⇒ 同一池条目被引用 **6 次**
- 附件：`photo.jpg`，**1 048 576 B（1 MiB）**，内容 SHA-256
  `631b84027d6b9e52b539c4e8373622d23032dfadc64d60af87339c9037e4f769`
  （可压缩字节，故库文件本身仅 ~7 KB）

## 为什么必须有它

`ISSUE-P1-276` 的受害区间是 `(512 KiB, 1 MiB]`（含 1 MiB 端点）：旧计费口径按
`Σᵢ nᵢ·sᵢ > 2·Σⱼ sⱼ + 1 MiB` 计费，本库为 `6 × 1 MiB = 6 MiB > 2 × 1 MiB + 1 MiB = 3 MiB`
⇒ **旧口径下整库打不开**。价值在于受害形态由**官方实现落盘**（不是本仓自产）：
`ThirdPartyAttachmentHistoryFixtureTest` 因此构成「官方产 → 本仓读」半边；
对偶半边（本仓产 → 官方读）由 `AttachmentHistoryReferenceBudgetRoundtripTest` 的探针产物 +
`ATTACHMENT_BUDGET_PROBE.md` 的 `keepassxc-cli` 读数承担。

## 生成过程（Windows / Git Bash；口令经 stdin、不落 argv）

```bash
export MSYS_NO_PATHCONV=1
KC='/c/Program Files/KeePassXC/keepassxc-cli.exe'

# 1) 取本仓写侧基线：先跑探针用例产出 v4.1 语料（1 MiB 附件 + 3 条历史）
./gradlew.bat :database:testDebugUnitTest --tests "*AttachmentHistoryReferenceBudgetRoundtripTest*"
cp database/build/attachment-budget-probe/keepasskey-medium-attachment-history.kdbx \
   database/src/test/resources/fixtures/attachment-history/keepassxc-medium-attachment-history.kdbx

# 2) 由官方 CLI 改写两轮（每轮 `edit` 追加一个历史快照，落盘方 = KeePassXC）
cd database/src/test/resources/fixtures/attachment-history
PW='attachment-budget-probe-2026'
printf "$PW" | "$KC" edit -q keepassxc-medium-attachment-history.kdbx "/Medium Photo v4" -t "Medium Photo k2"
printf "$PW" | "$KC" edit -q keepassxc-medium-attachment-history.kdbx "/Medium Photo k2" -t "Medium Photo k3"

# 3) 核验形态
printf "$PW" | "$KC" ls -R -q keepassxc-medium-attachment-history.kdbx     # Medium Photo k3
printf "$PW" | "$KC" export -q keepassxc-medium-attachment-history.kdbx   # <Entry> 6 / <Value Ref="0"> 6
```

> 注 1：**`keepassxc-cli db-create` 产的是 KDBX 3.1**（本仓读取侧只支持 v4），故本 fixture 走
> 「v4 基线 + 官方 CLI 改写」路径而非 `db-create`；官方 CLI 改写 v4 库时保持 v4。
> 注 2：官方 CLI 按**标题路径**定位条目，`edit -t` 会改标题，故下一轮须用新标题路径。
> 注 3：重新生成会得到不同的 UUID / 盐值 / 加密 IV，**须同步更新本文件的 SHA-256 与版本行**。
