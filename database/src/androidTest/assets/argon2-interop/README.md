# 设备侧 Argon2 互操作语料落位目录（`database/src/androidTest/assets/argon2-interop/`）

> 归属条目：**ISSUE-P3-23**（ISSUE-P3-11 验收标准 2 的残余面：真实 `.kdbx` 语料端到端解锁）
> ⚠️ **本 README 不是语料**。截至本文件写入时，**本目录下没有任何 `.kdbx` 语料**，
> 因此 `RealKdbxCorpusUnlockTest` 的两个用例会**整体跳过**（JUnit `Assume`），
> **跳过 ≠ 通过**，验收标准 2 **尚未达成**。

---

## 1. 这个目录是干什么的

`src/test/resources` **不会**被打进 androidTest（instrumented）APK —— 设备侧只能读 `assets`。
所以设备上做「真实 KeePass / KeePassXC `.kdbx` 端到端解锁」的语料**必须**放在这里：

```
database/src/androidTest/assets/argon2-interop/
├── README.md                                  # 本文件（非语料，被测试过滤掉）
├── argon2d-v19-t2-m64-p2-keepass2611.kdbx     # ← 真实语料（人工生成后放入）
├── argon2d-v19-t2-m64-p2-keepass2611.json     # ← 同名伴生元数据（必需）
├── argon2id-v19-t2-m64-p4-keepassxc.kdbx
└── argon2id-v19-t2-m64-p4-keepassxc.json
```

AGP 默认的 androidTest assets 源目录就是 `src/androidTest/assets`，**无需**在
`database/build.gradle.kts` 里额外声明。

---

## 2. 语料生成方法（逐步可执行清单）

**完整步骤、命名约定、伴生 `.json` schema、安全硬前提**统一维护在：

📄 **`crypto/src/test/resources/argon2-interop/README.md` §3 / §4**

要点复述（**细则以那份文档为准**）：

1. 用 **KeePass 2.61.1**（Windows GUI）与/或 **KeePassXC** 手工建库，KDF 选 Argon2d / Argon2id，
   **显式固定** `t` / `m` / `p`（不依赖工具默认值：KeePass 默认 `m=64MiB, t=2, p=2`，
   KeePassXC 默认 `m=64MiB, t=10, p=2`）；
2. 主密码**逐字使用**测试专用一次性口令 `Test-Vector-Only-2026!`；
   **严禁使用任何真实主密码**，库内**只放占位条目**、零真实数据；
3. 按命名约定 `<kdf>-v<版本十进制>-t<迭代>-m<内存MiB>-p<并行度>-<来源>.kdbx` 命名；
4. **同一份语料同时放两处**（供不同消费方，二者不可互替）：
   - 宿主 JVM 单测：`crypto/src/test/resources/argon2-interop/`
   - **设备侧 instrumented：本目录**
5. 每个 `.kdbx` **必须**配一个**同名** `.json` 伴生文件，`entryCount` 必须等于
   `rootGroup.allEntries().size`（含所有子分组）。

---

## 3. 放好语料之后

```powershell
# 设备侧（需先连真机 / 起模拟器）
.\gradlew.bat :database:connectedDebugAndroidTest --console=plain
```

本用例会：
- 逐个 `.kdbx` 在**设备上**用 `KdbxFile.load` 解锁；
- 断言解锁成功、条目数与伴生 `entryCount` 一致、KDF 参数（类型 / 版本 / t / m / p）与伴生声明一致；
- 断言错误口令被 `KdbxInvalidCredentialsException` 拦截。

若语料在、但伴生 `.json` 缺失或字段不全，用例会**硬失败**（不是跳过）——这是刻意设计的
fail-closed 语义：不允许「语料在、元数据糊」的假绿。

---

## 4. 变更纪律

- 新增任何 `.kdbx` 前，必须逐条满足
  `crypto/src/test/resources/argon2-interop/README.md` **§3.0** 的「一次性口令 + 零真实条目」硬前提，
  并在伴生 `.json` 中登记 `containsRealData: false`、`passphraseIsThrowaway: true`；
- **严禁**把本工程自建夹具（如 `database/src/test/resources/fixtures/test_vault.kdbx`）
  或参考项目资产（`参考项目/**/*.kdbx`）复制到本目录充当互操作语料。
