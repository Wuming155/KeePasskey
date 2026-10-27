# AGENTS.md

项目事实以代码、配置和实际运行结果为准。普通任务先读本文件、[当前问题](docs/ACTIVE_ISSUES.md)及[工程规则](.codebuddy/rules/engineering-rules.md)，其它资料按需读取，入口见 [docs/README.md](docs/README.md)。

## 工作规则

- 新发现的未解决问题登记到当前问题清单，注明核实时间与方式；关闭后移除，不追加历史流水。
- 文档按 [固定职责](docs/README.md#固定职责) 归位，新增、更新与退役遵守 [文档维护流程](.codebuddy/rules/engineering-rules.md#文档维护流程)。默认更新已有文件，不创建任务报告、整改方案或批次归档；只有已提交的历史由 Git 承接。
- 文档和注释用简体中文；文档交付前运行 `python tools/doc/check_md_links.py`，同步清理受影响的旧内容与引用。
- 模块依赖保持单向，见 [架构](docs/architecture/ARCHITECTURE.md)。敏感数组交付与擦除遵守 [所有权契约](docs/architecture/敏感缓冲所有权契约.md)，秘密不得进日志。
- 测试新增、复用、合并与退役遵守 [测试规则](.codebuddy/rules/engineering-rules.md#测试)；先查已有覆盖，不按任务机械新增用例或检查脚本。
- KDBX 互操作结论须经独立实现端到端验证，本仓往返或文件头检查不足以证明互操作。
- 修改 Rust/JNI/Native 加密分派或探活，须在 crypto、database、sync、app 四层实跑 `connectedDebugAndroidTest`；新增或修改 androidTest 也须实际执行，确认结果非空。
- 设备测试优先复用已有的专用测试 AVD，不按任务或批次反复新建。需要干净状态时先清理相关应用数据与测试残留，仍不足时再清空该 AVD 的用户数据；仅在无可用 AVD、系统镜像不满足测试要求或现有 AVD 无法恢复时新建。也可使用明确可丢弃数据的设备；不得对存有需保留数据的应用运行可能卸载它的测试包。
- Android、Kotlin、Gradle、加密和第三方 API 的不确定行为查官方资料。浏览器验收以实际调用路径为准，不预设 Chrome 或 GMS，不外推单个样本结论。
- 参考项目只按明确问题查阅，不无目标扫描、修改或复制 `参考项目/` 中的代码。
- 提交或推送按用户授权执行；只暂存本次明确路径，不使用 `git add -A` 或 `git add .`。提交正文说明实际改动、验收结果与未覆盖边界。
