# CODEBUDDY.md
This file provides guidance to CodeBuddy when working with code in this repository.

## 项目概述

KeePasskey 是一款使用原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 .kdbx（v4）格式，内置 WebDAV 与 S3 兼容协议同步；以 Android 14+（API 34）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证（低版本平滑退化至传统密码填充）。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

## 硬约束（任何操作都适用）

1. **模块依赖严格单向**（详见架构指南），禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
3. **`参考项目/` 目录只读**：仅作实现思路借鉴，严禁修改或复制其代码入库（许可证约束）。
4. **工程规则**（单一职责、禁止魔法数字、依赖倒置、错误处理等）详见下方规则文件，写代码前必须遵守。

## 详细文档索引（按需阅读）

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| `.codebuddy/rules/engineering-rules.md` | 工程规则：单一职责与巨型类阈值、魔法数字、依赖倒置与 Hilt 注入、Result 错误处理、敏感数据、Compose 规范、**原子写盘、协程调度约束、Credential Provider 隔离、防御性安全（FLAG_SECURE / 剪贴板 / 混淆）** | **写 / 改任何代码前** |
| `.codebuddy/skills/architecture.md` | 5 模块职责与依赖规则、7 条关键架构决策（加密分离、kdbx 兼容、DatabaseSession、同步模型、passkey 路线、UI 优先） | 跨模块改动、新增功能落位前 |
| `.codebuddy/skills/reference-projects.md` | 参考项目地图：各功能应参照哪个项目的哪些文件 | 实现 database / crypto / sync / passkey 功能时 |
| `.codebuddy/memory/project-status.md` | 版本配套表、项目现状、决策日志、待办 | 升级依赖、了解进度与历史决策时 |

构建使用 Gradle Wrapper（Gradle 8.10.2）：Windows 下 `.\gradlew.bat <task>`。
