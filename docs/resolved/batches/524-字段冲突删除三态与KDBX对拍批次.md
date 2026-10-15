# §524 字段冲突删除三态与 KDBX 对拍批次

<a id="s524"></a>

## 1. 闭环范围

本批关闭 ISSUE-P3-629。原问题是云端 KDBX 删除了字段而本地仍有该字段时，字段级“采纳云端”没有删除操作，缺键分支会保留本地值并再次上传。

产品决定 PD-85 已明确字段缺失可表示删除，界面提供保留本地、采纳云端、删除云端三态；删除只作用于本次已知差异键。

## 2. 实施与验收

- 冲突 UI 区分缺失与空值；只有远端缺少该字段时显示删除选择，敏感字段仍保持掩码。
- FieldResolutionChoice 增加 DELETE_REMOTE。合并器只对 modifiedFields 中已知字段执行删除，并再次确认远端确实缺键；未知键、远端仍有值的键和标量字段均不能借此删除。
- app JVM 套件全量通过：2522 项，0 失败、0 错误。SyncCoordinatorTest 覆盖用户选择远端删除、上传和重新解析。
- 文件级探针 KdbxMergeInteropProbeTest 生成实际 KDBX 序列化产物；其中 merge-delete-target 的自定义字段 ProbeDeletedField 经 DELETE_REMOTE 后不存在。
- 独立实现 pykeepass 4.2.0 成功解锁同一 KDBX，读出 merge-local、merge-remote-unique、merge-delete-target，并确认目标条目不含 ProbeDeletedField。tools/kdbx-merge-interop/verify_merge.py 退出码 0。
- 合成产物 SHA-256：44AD7E1DAE58482DC2C185E9C54B2A45F844B6CBBE25B7B927FDB386F42EA269；口令和条目值均为虚构测试值。

复现命令：

    .\gradlew.bat :app:testDebugUnitTest --tests=com.keepasskey.app.data.importer.KdbxMergeInteropProbeTest
    python tools/kdbx-merge-interop/verify_merge.py app/build/interop-probe/keepasskey-kdbx-merge-probe.kdbx merge-probe-password --expect-entry merge-local --expect-entry merge-remote-unique --expect-entry merge-delete-target --expect-no-custom-property merge-delete-target ProbeDeletedField

## 3. 过程与限制

- 验收覆盖用户裁决、字段级决策、上传/重解析用例及独立 KDBX 读取；未使用真实云端账户或真实用户库。
- 未做桌面 KeePass UI 手工冲突演练；KDBX 文件级对拍不替代服务端网络与系统界面验收。

## 4. 关联入口

- 原方案：字段冲突删除语义与三态裁决方案（../../architecture/问题整改方案/P3/ISSUE-P3-629/字段冲突删除语义与三态裁决方案.md）
- 产品决定：产品裁决登记（../../architecture/产品裁决登记.md，PD-85）
- 互操作脚本：verify_merge.py（../../../tools/kdbx-merge-interop/verify_merge.py）
- 当前未闭环事项：ACTIVE_ISSUES.md（../../ACTIVE_ISSUES.md）
