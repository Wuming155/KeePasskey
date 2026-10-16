# §529 XLSX 文本单元格 Python 往返验收批次

<a id="s529"></a>

## 1. 闭环范围

本批按用户提出的 Python 读写方案关闭 ISSUE-P3-623。导出端区分原值交换 CSV 与表格文本 XLSX；本批将 Python `openpyxl` 读写往返作为约定的 XLSX 导入验收边界。

## 2. 实施与验收

- Kotlin 导出器的表格安全路径把字段写为 XLSX `inlineStr` 文本单元格；原值 CSV 能力及明文导出授权门控保持。
- 单测覆盖公式前缀、Unicode、空值、前导零与原值往返。
- 合成工作簿由 `openpyxl 3.1.5` 读取、保存并重新加载；`+1+1`、`-1+1`、`=1+1`、`@SUM(1,1)` 均以字符串单元格读回，原值逐项一致。
- Python 读取的设备/桌面测试资产：[`KdbxCsvExporter-inline-text-synthetic.xlsx`](../../../.ci-artifacts/remediation-20261015/p3-623/KdbxCsvExporter-inline-text-synthetic.xlsx)。

## 3. 验收范围

- 本批证明当前 XLSX 文档结构被 `openpyxl` 按字符串单元格导入并在往返写入后保留文本类型。
- 本批不声称 Excel、Google Sheets 或 LibreOffice 的 UI 导入/公式引擎已经实测；当前机器和 Redmi 4X 均未安装表格应用。该应用级兼容性不属于按用户指定 Python 读写边界的本批验收范围。

## 4. 关联入口

- 整改方案：[CSV 导出用途分离与公式防护方案](../../architecture/问题整改方案/P3/ISSUE-P3-623/CSV导出用途分离与公式防护方案.md)
- 当前未闭环事项：[ACTIVE_ISSUES.md](../../ACTIVE_ISSUES.md)
- XLSX 合成资产：[`KdbxCsvExporter-inline-text-synthetic.xlsx`](../../../.ci-artifacts/remediation-20261015/p3-623/KdbxCsvExporter-inline-text-synthetic.xlsx)
