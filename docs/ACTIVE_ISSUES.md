# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

**核实口径**：各条以条目内注明的核实时间与方式为准；静态定位、宿主行为复现和设备/真实服务端验收分别标明，不相互替代。运行探针使用虚构数据；临时探针已清理，不作为永久复现资产。同类项目只提供比较背景，不作为本仓缺陷成立或必须删除防护的依据。编号保持原有身份，不因移除误报或调整优先级重新编号。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能、测试有效性缺口及需验证影响的安全问题 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

分级按当前已确认的影响与触发条件确定；尚未完成的攻击链或性能测量不能当作既成结果。

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

暂无。

## P2 中危缺陷与协议/测试缺口

暂无。

## P3 低危问题与体验改进

### ENG-20261111-01 Android DOM 加固特性无效调用与告警噪音

- **核实时间与方式**：2026-11-11，真机 M332BF / Android API 37 / arm64-v8a / release 0.1.0 的可导出诊断日志与 `adb logcat` 交叉核对（16:05 失败周期与 17:45 成功周期均出现同一组 5 条告警）；源码静态复核 `sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt:193-275`、`settings.gradle.kts`、`sync/src/test` 与 `sync/src/androidTest/java/com/keepasskey/sync/webdav/WebDavPropfindParserDeviceTest.kt:45,70`。未改动代码，未构造删除后的复现。
- **位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt:211-217`（`applyXxeGuardFeature`）、`:226-245`（`hardenedFactories` 初始化）、`:255-261`（`applyXxeGuardSetting`）、`:263-275`（`newHardenedBuilder`）；`app/src/main/java/com/keepasskey/app/data/logger/DiagnosticLogRedactor.kt:35,41`（脱敏误伤）。
- **问题**：`hardenedFactories` 按线程初始化时逐项设置 4 个 Xerces/SAX 特性与 `setXIncludeAware(false)`，Android 平台实现 `org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl` 分别抛 `ParserConfigurationException` / `UnsupportedOperationException`，容错后只落 WARN；第 6 项 `isExpandEntityReferences = false` 未抛异常、被平台接受。本仓只交付 Android APK（`settings.gradle.kts` 五个模块均为 Android 模块，模块外无 Kotlin/JVM 运行时源码，`sync/src/test` 无 XXE/DOCTYPE 用例），桌面 JVM 仅为单测执行环境，故这 5 项尝试在运行时零效果，却在每个工作线程首次建工厂时抛异常、采集栈帧并写入可导出诊断缓冲；导出的 `<redacted-url>` / `<redacted-file>` 还把 feature URL 与异常类名误判脱敏。Android 上真正有效的外部实体防线是 `EntityResolver`（空输入兑现），由设备测试哨兵验证，不依赖上述特性。
- **影响边界**：诊断日志 5 条重复告警与无谓的异常/栈帧开销；不影响同步结果（同一组告警同时出现在 `Error` 与 `MergedAndUploaded` 两份日志中），无已知安全缺口。删除时须注意 `isExpandEntityReferences = false` 是被接受的设置，直接删除会把工厂状态从 `false` 改回默认 `true`，属于行为变化而非清理无效代码。
- **尚未完成**：删掉 5 个注定抛异常的调用与两个容错包装函数，保留 `isExpandEntityReferences = false` 裸赋值以维持工厂状态；同步更新 `WebDavPropfindParser.kt` 与 `WebDavPropfindParserDeviceTest.kt` 的过时注释；真机跑 `WebDavPropfindParserDeviceTest`（XXE 哨兵 + 合法报文）与 `:sync:testDebugUnitTest` 全量通过，确认 `EntityResolver` 仍兜住外部实体后移除本条。可选：修正 `DiagnosticLogRedactor` 对异常类型行的豁免。
