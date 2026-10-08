# 05 · KeePassXC（合并与 Passkey schema 参考 ⚖️）踩坑对照详情

> 来源：参考项目 `参考项目/KeePassXC/`（仓库内路径前缀 `src/`）的踩坑标注与主项目 KeePasskey 的逐条对照。
> 扫描日期 2026-09-28。踩坑标注 29 条，对照行 29 条（risk=yes 1 / unclear 0 / no 28）。
> 总表见 [00-对照总表.md](00-对照总表.md)。

## 一、踩坑标注（29 条）

### 1. src/core/Database.cpp:144
- **代码上下文**：打开 .kdbx 文件时探测读写模式
- **原文**：
  > // Don't autodetect read-only mode, as it triggers an upstream bug.
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/803`（可达性：yes）
- **坑的本质**：网络共享下自动降级只读会误判（上游缺陷），库被静默以只读打开无法保存；故禁用自动降级，只按指定模式打开
- **置信度**：confirmed

### 2. src/core/Database.cpp:249
- **代码上下文**：saveAs 保存数据库（原子/非原子两条路径）
- **原文**：
  > If atomic is false, this function uses QTemporaryFile instead of QSaveFile due to a bug in Qt (QTBUG-57299)
- **链接**：无（可达性：none）
- **坑的本质**：QSaveFile 在 Dropbox/Google Drive/OneDrive 等云同步目录下 rename 会失败，被迫改用非原子的 QTemporaryFile；崩溃/断电瞬间可能丢数据
- **置信度**：confirmed

### 3. src/core/Database.cpp:410
- **代码上下文**：原子保存后用临时文件替换原库
- **原文**：
  > Note: call into the QFile rename instead of QTemporaryFile due to an undocumented difference in how the function handles errors
- **链接**：无（可达性：none）
- **坑的本质**：QTemporaryFile::rename 与 QFile::rename 对错误处理有未公开差异，跨文件系统保存会报错，须显式调 QFile::rename
- **置信度**：confirmed

### 4. src/core/Bootstrap.cpp:46
- **代码上下文**：应用启动早期设置 QT_BEARER_POLL_TIMEOUT
- **原文**：
  > This creates a latency spike every 10 seconds on Mac OS 10.12+ and Windows 7 >= when on a wifi connection.
- **链接**：无（可达性：none）
- **坑的本质**：QNetworkAccessManager 周期轮询网卡造成每10秒延迟尖峰，用 QT_BEARER_POLL_TIMEOUT=-1 规避，副作用是打印定时器负间隔警告
- **置信度**：confirmed

### 5. src/core/Bootstrap.cpp:92
- **代码上下文**：disableCoreDumps 禁止 core dump 泄密
- **原文**：
  > // NOTE: Dumps cannot be disabled for snap builds as it prevents desktop portals from working
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/7607`（可达性：yes）
- **坑的本质**：snap 沙箱下 PR_SET_DUMPABLE=0 会使 desktop portals 失效（无法打开/另存文件），只能放弃禁 core dump，内存落盘风险残留
- **置信度**：confirmed

### 6. src/main.cpp:77
- **代码上下文**：main() 中抬高全局线程池下限
- **原文**：
  > // HACK: Prevent long-running threads from deadlocking the program with only 1 CPU
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/10391`（可达性：yes）
- **坑的本质**：单核虚拟机下线程池仅1线程，长任务（密钥派生）令解锁永久挂起；强制 QThreadPool maxThreadCount≥3 规避
- **置信度**：confirmed

### 7. src/cli/TextStream.cpp:63
- **代码上下文**：CLI 文本输出流封装
- **原文**：
  > Workaround for an issue with QTextStream. Its operator<<(const char *string) will encode the string with a non-UTF-8 encoding.
- **链接**：无（可达性：none）
- **坑的本质**：QTextStream 对 const char* 重载按非 UTF-8 编码输出，非 ASCII 文本乱码；包成 QString 强制走 UTF-8
- **置信度**：confirmed

### 8. src/core/Translator.cpp:37
- **代码上下文**：按平台装载翻译文件
- **原文**：
  > // NOTE: this is a workaround for the terrible way Qt loads languages
- **链接**：无（可达性：none）
- **坑的本质**：Qt 装载语言的机制怪异，需按特定顺序/方式自行安装翻译，否则翻译不生效（注释未展开细节）
- **置信度**：suspected

### 9. src/browser/BrowserService.cpp:1278
- **代码上下文**：浏览器集成按 URL 匹配条目并排序
- **原文**：
  > // NOTE: QUrl::matches is utterly broken in Qt < 5.11, so we work around that
- **链接**：无（可达性：none）
- **坑的本质**：QUrl::matches 在 Qt<5.11 彻底错误，URL 匹配须先 adjusted 去掉 fragment/userinfo 再手工比对，否则匹配结果不可信
- **置信度**：confirmed

### 10. src/gui/DatabaseWidget.cpp:254
- **代码上下文**：DatabaseWidget 析构时释放 m_db
- **原文**：
  > QSharedPointer may behave differently depending on whether it is cleared by the `clear` method or by its destructor.
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/6393`（可达性：yes）
- **坑的本质**：Database 析构触发的槽里再拷贝共享指针会使引用计数失准（曾致 Secret Service 重入死锁），必须在析构前显式 m_db.clear()
- **置信度**：confirmed

### 11. src/gui/DatabaseWidget.cpp:2035
- **代码上下文**：闲置自动锁定流程
- **原文**：
  > // Don't try to lock the database while saving, this will cause a deadlock
- **链接**：无（可达性：none）
- **坑的本质**：保存进行中立即锁定会死锁，改为 200ms 后单次重试锁定
- **置信度**：confirmed

### 12. src/gui/entry/EditEntryWidget.cpp:1177
- **代码上下文**：条目编辑页 Apply 提交
- **原文**：
  > // HACK: Check that entry pointer is still valid, see https://github.com/keepassxreboot/keepassxc/issues/5722
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/5722`（可达性：yes）
- **坑的本质**：外部合并（KeeShare 共享组）会使 m_entry 悬空，直接解引用崩溃且改动丢失；提交前必须判空并提示用户改动已丢
- **置信度**：confirmed

### 13. src/gui/Icons.cpp:168
- **代码上下文**：全局取图标入口 Icons::icon
- **原文**：
  > Resetting the application theme name before calling QIcon::fromTheme() is required for hacky QPA platform themes
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/4963`（可达性：yes）
- **坑的本质**：qt5ct 等 QPA 主题会随机清空图标主题名导致图标全部丢失，取图标前必须重置 theme name
- **置信度**：confirmed

### 14. src/autotype/mac/AutoTypeMac.cpp:67
- **代码上下文**：枚举窗口标题供 AutoType 匹配
- **原文**：
  > Audio recording injects a "StatusIndicator" window owned by the "Window Server" process into to list in macOS 12.2
- **链接**：`https://github.com/keepassxreboot/keepassxc/issues/7418`（可达性：yes）
- **坑的本质**：macOS 12.2 起录音时系统注入 StatusIndicator 假窗口且被当作活动窗口，AutoType 全部匹配失败；须按标题+属主过滤
- **置信度**：confirmed

### 15. src/autotype/xcb/AutoTypeXCB.cpp:347
- **代码上下文**：键盘布局变化后刷新 X11 键表
- **原文**：
  > /* workaround X11 bug https://gitlab.freedesktop.org/xorg/xserver/-/issues/1155 */
- **链接**：无（可达性：none）
- **坑的本质**：XkbGetMap 返回的映射不完整（X server 缺陷），须再 XkbSetMap 回写并 XSync 才能拿到正确键表
- **置信度**：confirmed

### 16. src/core/Merger.cpp:612
- **代码上下文**：mergeDeletions 按合并模式分流
- **原文**：
  > // no deletions are applied for any other strategy!
- **链接**：无（可达性：none）
- **坑的本质**：删除记录只在 Synchronize 合并模式下生效，其余模式直接忽略——实现合并/同步时极易误以为删除会传播到目标库
- **置信度**：confirmed

### 17. src/core/Merger.cpp:702
- **代码上下文**：mergeMetadata 合并库级元数据
- **原文**：
  > TODO HNH: missing handling of recycle bin, names, templates for groups and entries, public data … => possible lost update
- **链接**：无（可达性：none）
- **坑的本质**：元数据合并未处理回收站/模板/公共字典数据，新旧字典按键覆盖且不看各自时间，登记为可能丢失更新的实现缺口
- **置信度**：confirmed

### 18. src/fdosecrets/objects/Service.cpp:123
- **代码上下文**：Secret Service 集合随数据库关闭而移除
- **原文**：
  > race conditions when deleteLater was called on the m_backend, but not delivered yet,
- **链接**：无（可达性：none）
- **坑的本质**：依赖 m_backend 的 destroyed 信号移除 D-Bus 对象会与关闭后新到的 D-Bus 调用竞态；须在 databaseClosed 时立即主动 removeFromDBus
- **置信度**：confirmed

### 19. src/fdosecrets/objects/Collection.cpp:192
- **代码上下文**：D-Bus 返回集合修改时间
- **原文**：
  > // FIXME: there seems not to have a global modified time.
- **链接**：无（可达性：none）
- **坑的本质**：KDBX 无库级全局修改时间，只能退化为根组 lastModificationTime，客户端比较集合修改时间可能不准
- **置信度**：suspected

### 20. src/gui/Clipboard.h:62
- **代码上下文**：macOS 自定义剪贴板生命周期
- **原文**：
  > This object lives for the whole program lifetime and we cannot delete it on exit, so ignore leak warnings.
- **链接**：无（可达性：none）
- **坑的本质**：MacPasteboard 与进程同寿、退出时无法析构必然报泄漏，是已知并刻意接受的（QTBUG-54832）
- **置信度**：confirmed

### 21. src/gui/entry/EntryView.cpp:339
- **代码上下文**：恢复条目表表头视图状态
- **原文**：
  > // Reset to unsorted first (https://bugreports.qt.io/browse/QTBUG-86694)
- **链接**：无（可达性：none）
- **坑的本质**：restoreState 前必须先清排序指示器，否则恢复出的视图状态错乱（Qt 缺陷）
- **置信度**：confirmed

### 22. src/gui/DatabaseOpenDialog.cpp:59
- **代码上下文**：多数据库标签切换快捷键
- **原文**：
  > // Ctrl+Tab is broken on Mac, so use Alt (i.e. the Option key) - https://bugreports.qt.io/browse/QTBUG-8596
- **链接**：无（可达性：none）
- **坑的本质**：macOS 上 Ctrl+Tab 快捷键失效（Qt 缺陷），切库修饰键改用 Alt
- **置信度**：confirmed

### 23. src/gui/DatabaseWidget.cpp:1263
- **代码上下文**：关闭编辑/新建页后恢复焦点
- **原文**：
  > // Workaround: ensure entries are focused so search doesn't reset
- **链接**：无（可达性：none）
- **坑的本质**：不把焦点还给条目视图会导致搜索状态被重置，须手动 m_entryView->setFocus()
- **置信度**：suspected

### 24. src/format/KdbxXmlReader.cpp:913
- **代码上下文**：KDBX XML 解析条目附件 binary
- **原文**：
  > NOTE: This only impacts KDBX 3.x databases … Prepend a random string to the key to make it unique and prevent data loss
- **链接**：无（可达性：none）
- **坑的本质**：KDBX 3.x 下同一附件键名可重复出现且值不同，直接覆盖会丢数据；须加随机前缀改成唯一键把两份都保留
- **置信度**：confirmed

### 25. src/gui/entry/EntryView.cpp:412
- **代码上下文**：条目列表列宽自适应 fitColumnsToWindow
- **原文**：
  > Without this, fitting to window will be broken and/or work unreliably (stumbled upon during testing)
- **链接**：无（可达性：none）
- **坑的本质**：写给后维护者的约束：若重写 resizeEvent 而不以 QTreeView::resizeEvent(event) 方式调父类，列宽自适应会失效
- **置信度**：suspected

### 26. CHANGELOG.md:10
- **代码上下文**：2.7.12 发布说明：通行密钥标志
- **原文**：
  > Passkeys: Set BE and BS flags to true (NOTE: MAY BREAK EXISTING PASSKEYS) [#13042]
- **链接**：`https://github.com/keepassxreboot/keepassxc/pull/13042`（可达性：yes）
- **坑的本质**：为互操作把 KPEX 通行密钥 BE/BS 标志改为 true；标志被视为不可变，旧条目可能认证失效，需手动补值为 0 的属性恢复
- **置信度**：confirmed

### 27. CHANGELOG.md:58
- **代码上下文**：2.7.11 发布说明：直接写盘保存
- **原文**：
  > Fix potential database truncation when using direct write save method with YubiKeys [#11841]
- **链接**：`https://github.com/keepassxreboot/keepassxc/pull/11841`（可达性：yes）
- **坑的本质**：直接写盘保存 + 需按键的硬件密钥时，等待按键期间库文件可能被截断为 0 字节；修复为先写内存缓冲、key transform 完成后再落盘
- **置信度**：confirmed

### 28. CHANGELOG.md:17
- **代码上下文**：2.7.12 发布说明：Auto-Type 回退
- **原文**：
  > Auto-Type: Revert change that caused race condition on Linux [#12738]
- **链接**：`https://github.com/keepassxreboot/keepassxc/pull/12738`（可达性：yes）
- **坑的本质**：把初始延迟移入动作队列引发竞态，Auto-Type 间歇性失效；回退为在捕获活动窗口引用之前同步执行初始延迟
- **置信度**：confirmed

### 29. CHANGELOG.md:25
- **代码上下文**：2.7.12 发布说明：附件名净化（安全修复）
- **原文**：
  > Sanitize attachment file names before saving [#13114]
- **链接**：`https://github.com/keepassxreboot/keepassxc/pull/13114`（可达性：yes）
- **坑的本质**：其他工具生成的库中附件名可含斜杠/反斜杠，导出保存附件时发生目录穿越逃出基目录；保存前须剔除路径分隔符
- **置信度**：confirmed

## 二、对照行（29 条）

### 1. Database.cpp:144 → SessionOpener.kt:126
- **坑的本质**：打开 .kdbx 时自动探测降级只读模式在网络共享下误判（上游缺陷），库被静默只读打开无法保存；故禁用自动降级，只按显式指定模式打开
- **触发条件**：数据库文件位于 samba/网络共享
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/SessionOpener.kt:126`
- **对照情况**：功能相似：同为「打开库文件决定读写模式」的入口
- **是否有同样风险**：no
- **建议**：主项目无任何自动探测：只读是调用方显式传参（SessionOpener.kt:130/163 `readOnly: Boolean = false`，DatabaseSession.kt:201/215 同），全仓无 canWrite/可写探测（已 grep `canWrite|可写|写保护`，命中的均为 KDoc 文字或无关字段），不会出现「静默降级只读」的误判面
- **需补充信息**：（无）

### 2. Database.cpp:249 → AtomicFileWriter.kt:78
- **坑的本质**：QSaveFile 原子保存（rename）在 Dropbox/Google Drive/OneDrive 等云同步目录下会失败，被迫改用非原子 QTemporaryFile，崩溃/断电瞬间可能丢数据
- **触发条件**：原子保存被关闭且库文件在云同步目录内
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt:78`
- **对照情况**：同类模式：同为「库文件落盘的原子性保障」路径
- **是否有同样风险**：no
- **建议**：本地文件路径：临时文件与目标同目录（AtomicFileWriter.kt:37-38/78）+ ATOMIC_MOVE + 失败降级链（96-103、fallbackReplace 195-230），不依赖云目录行为。SAF 自选位置（可能落在第三方云 provider 目录）写回确为非原子，但该面已作为**有意取舍**登记在 docs/architecture/已知工程限界.md:637（§24，ISSUE-P2-229），且用户可见文案必须如实披露——属已登记的已知限界而非未设防的坑
- **需补充信息**：（无）

### 3. Database.cpp:410 → AtomicFileWriter.kt:37
- **坑的本质**：QTemporaryFile::rename 与 QFile::rename 对错误处理有未公开差异，跨文件系统保存会报错，须显式调 QFile::rename
- **触发条件**：保存目标与临时目录跨文件系统
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt:37`
- **对照情况**：同类模式：同为「临时文件→rename 替换原库」的原子写盘路径
- **是否有同样风险**：no
- **建议**：已规避：临时文件路径恒为 `targetFile.name + ".tmp"` 与目标同目录（AtomicFileWriter.kt:37-38 注释明写「保证 rename 处于同一文件系统内」，78 行构造），不存在跨文件系统 rename 的触发面
- **需补充信息**：（无）

### 4. Bootstrap.cpp:46 → 无直接对应
- **坑的本质**：QNetworkAccessManager 周期轮询网卡造成每 10 秒延迟尖峰，需 QT_BEARER_POLL_TIMEOUT=-1 规避
- **触发条件**：实例化 QNAM 且设备处于 WiFi
- **主项目对应位置**：无直接对应
- **对照情况**：无：Qt QNAM 承载层轮询为 Qt 桌面特有机制
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 5. Bootstrap.cpp:92 → 无直接对应
- **坑的本质**：snap 沙箱下 PR_SET_DUMPABLE=0 会使 desktop portals 失效，只能放弃禁 core dump，内存落盘风险残留
- **触发条件**：KEEPASSXC_DIST_SNAP 构建且依赖 portals
- **主项目对应位置**：无直接对应
- **对照情况**：无：无 snap 沙箱 / desktop portals / core dump 配置面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 6. main.cpp:77 → RealVaultRepository.kt:394
- **坑的本质**：单核虚拟机下线程池仅 1 线程，长任务（密钥派生）令解锁永久挂起；强制 QThreadPool maxThreadCount≥3 规避
- **触发条件**：仅 1 个可用 CPU 核
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/data/repository/RealVaultRepository.kt:394`
- **对照情况**：同类模式：同为「长任务（KDF/整库序列化）与并发调度的交互」
- **是否有同样风险**：no
- **建议**：主项目不使用共享定容线程池：KDF 与整库序列化走协程 Dispatchers（SessionPersistence.kt KDoc「具名序列化缓冲 → Dispatchers.Default 加密 → 落盘」），且已实现「长任务挂锁」机制——persistSession 以 beginLongTask/endLongTask try/finally 包裹（RealVaultRepository.kt:394-408），锁定请求在长任务期间暂存、结束后补执行（AutoLockSessionGuard.kt:116-131）；协程 Mutex 是挂起而非阻塞自旋，不存在「单线程池内互等导致永久挂起」的死锁结构
- **需补充信息**：（无）

### 7. TextStream.cpp:63 → 无直接对应
- **坑的本质**：QTextStream 对 const char* 重载按非 UTF-8 编码输出导致非 ASCII 乱码，须包成 QString 强制 UTF-8
- **触发条件**：直接 operator<< 输出 const char* 字面量
- **主项目对应位置**：无直接对应
- **对照情况**：无：Kotlin String 恒为 UTF-16 内存形态，无 C 层 const char* 编码歧义
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 8. Translator.cpp:37 → 无直接对应
- **坑的本质**：Qt 装载语言的机制怪异，须按特定顺序自行安装翻译，否则翻译不生效
- **触发条件**：多语言翻译装载
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目用 Android 标准资源系统（values-zh 等），无自装翻译机制
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 9. BrowserService.cpp:1278 → DomainMatcher.kt:19
- **坑的本质**：QUrl::matches 在 Qt<5.11 彻底错误，URL 匹配须先 adjusted 去掉 fragment/userinfo 再手工比对，否则匹配结果不可信
- **触发条件**：依赖 QUrl::matches 做 URL 等值判断
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/DomainMatcher.kt:19`
- **对照情况**：同类模式：同为「URL 等值/域匹配的自研实现」
- **是否有同样风险**：no
- **建议**：主项目未依赖任何平台 URL 等值 API：DomainMatcher.extractDomain 手工剥离 scheme（:24-26）、path/query/fragment（:29）、认证信息 user:pass@（:43），配 IDN.toASCII（:104-107）与 Mozilla PSL（PublicSuffixList），另有严格标签边界与注册域判定（AutofillCandidateRanker.applySameSiteTiers :260-273）——正是 KeePassXC「先 adjusted 再手工比对」的等价防御，且不依赖存在缺陷的单一 API
- **需补充信息**：（无）

### 10. DatabaseWidget.cpp:254 → DatabaseSession.kt:110
- **坑的本质**：Database 析构触发的槽里再拷贝共享指针会使引用计数失准（曾致 Secret Service 重入死锁），必须在析构前显式 m_db.clear()
- **触发条件**：Database 析构激活的槽中拷贝 QSharedPointer
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt:110`
- **对照情况**：同类模式：同为「库生命周期终结时的资源/引用清理」，但无析构回调重入结构
- **是否有同样风险**：no
- **建议**：主项目库树是不可变 data class（KdbxDatabase），经 StateFlow 共享，无「析构激活槽回调内拷贝共享指针」的重入面；lock/close 只重置 core.state/readOnlyMode/saveWriter（DatabaseSession.kt:110-140、330-349），敏感树的擦除由调用方按「存活侧身份集合判定」执行（SyncConflictController.kt 注释段，契约见 docs/architecture/敏感缓冲所有权契约.md），不走析构信号链
- **需补充信息**：（无）

### 11. DatabaseWidget.cpp:2035 → AutoLockSessionGuard.kt:116
- **坑的本质**：保存进行中立即锁定会死锁，改为 200ms 后单次重试锁定
- **触发条件**：自动锁定触发时 m_db->isSaving() 为真
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/AutoLockSessionGuard.kt:116`
- **对照情况**：功能相似：同为「锁定与保存并发的交互」
- **是否有同样风险**：no
- **建议**：已系统性规避且强于 KeePassXC 的 200ms 重试：①保存期间挂锁——persistSession 以 beginLongTask/endLongTask try/finally 包裹整库保存（RealVaultRepository.kt:394-408）；②挂锁期锁定请求暂存、depth 归零后在 NonCancellable 内补执行（AutoLockSessionGuard.kt:116-131 endLongTask）；③锁库前对 DIRTY 态先 best-effort 补存再 lock（AutoLockSessionGuard.kt:125-131）；④databaseSession.lock() 走协程 Mutex（DatabaseSession.kt:329），即使争用也只是挂起等待而非死锁
- **需补充信息**：（无）

### 12. EditEntryWidget.cpp:1177 → SyncConflictAutoMerge.kt:110
- **坑的本质**：外部合并（KeeShare）会使 m_entry 悬空，Apply 直接解引用崩溃且改动丢失；提交前必须判空并提示用户改动已丢
- **触发条件**：编辑期间他人合并/同步了同一条目
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/sync/SyncConflictAutoMerge.kt:110`
- **对照情况**：功能相似：同为「编辑会话进行中条目树被外部替换」的窗口，但主项目无悬空指针结构且采纳前有单点守卫
- **是否有同样风险**：no
- **建议**：主项目对本条触发面（编辑进行中条目树被合并/同步替换）已有结构性防护，KeePassXC 的两面（悬空指针崩溃、用户编辑丢失）均不存在：①树为不可变 data class，编辑保存按 UUID 在当前树即时查找，条目不存在即如实报错不崩溃（VaultEntryWriteCoordinator.kt:244-248 返回 Failure repo_entry_not_found）；②合并产物采纳前有单点守卫：会话树在合并/上传窗口内被 UI 编辑替换即中止本周期而不覆盖编辑（SyncConflictAutoMerge.kt:109-112 `databaseFlow.value !== expectedSessionSnapshot → abortDiverged()`，:139/:199 两处接线，另 adoptMergedIfSessionUnchanged :89-95）；③锁库弃编辑有一次性告知（unsavedEditRegistry.markDirtyEditsDiscarded，AutoLockSessionGuard.kt:124）。反向面（外部程序改动已打开的库文件→保存静默覆盖）触发条件不同（文件被外部改动，而非会话内树替换），已作为开放条目 ISSUE-P2-378 登记（docs/ACTIVE_ISSUES.md:48/:60），按其既定 AC（基线 mtime+size 快照 + 「保存时」「回前台」两时点校验）落地即可，本条无需新增整改。原行引用的 :137/:196 经核实实为 :139/:199（行号漂移已修正）
- **需补充信息**：（无）

### 13. Icons.cpp:168 → 无直接对应
- **坑的本质**：qt5ct 等 QPA 主题会随机清空图标主题名导致图标全部丢失，取图标前必须重置 theme name
- **触发条件**：Linux 使用 qt5ct 等平台主题
- **主项目对应位置**：无直接对应
- **对照情况**：无：Android 无 QPA 平台主题层，图标走 Compose/资源系统
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 14. AutoTypeMac.cpp:67 → 无直接对应
- **坑的本质**：macOS 12.2 起录音时系统注入 StatusIndicator 假窗口且被当作活动窗口，AutoType 全部匹配失败；须按标题+属主过滤
- **触发条件**：任一应用占用麦克风（录制指示点亮）
- **主项目对应位置**：无直接对应
- **对照情况**：无：无 macOS 窗口枚举/AutoType 面（legacy 无障碍通道按窗口包名复核，另见 AutoType 竞态条）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 15. AutoTypeXCB.cpp:347 → 无直接对应
- **坑的本质**：XkbGetMap 返回的映射不完整（X server 缺陷），须再 XkbSetMap 回写并 XSync 才能拿到正确键表
- **触发条件**：键盘布局变更后重建键映射
- **主项目对应位置**：无直接对应
- **对照情况**：无：无 X11 键表处理面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 16. Merger.cpp:612 → KdbxMerger.kt:167
- **坑的本质**：删除记录只在 Synchronize 合并模式下生效，其余模式直接忽略——实现合并/同步时极易误以为删除会传播到目标库
- **触发条件**：mergeMode != Synchronize 时同步删除对象
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt:167`
- **对照情况**：功能相似：同为「合并时删除对象（墓碑）的传播语义」
- **是否有同样风险**：no
- **建议**：主项目不存在「某合并模式静默忽略删除」的暗面：三方合并对墓碑无条件合并（KdbxMerger.kt:107 localDeleted/remoteDeleted 索引、:167-170 KdbxTombstoneMerger.merge 按 UUID 去重取最晚删除时间并清洗存活集合，KdbxTombstoneMerger.kt:9-26）；两条单方强制策略 TAKE_REMOTE（整树采纳远端，删除随之生效）与 TAKE_LOCAL（本地覆盖远端）是设置页明示的覆盖语义且跳过合并（ConflictStrategyPolicy.skipsMerge，ConflictStrategy.kt:56-58；SyncConflictController.kt:139-155），非静默忽略
- **需补充信息**：（无）

### 17. Merger.cpp:702 → SyncConflictAutoMerge.kt:77
- **坑的本质**：元数据合并未处理回收站/模板/公共字典数据，新旧字典按键覆盖且不看各自时间，登记为可能丢失更新的实现缺口
- **触发条件**：合并双方 metadata 公共数据各含较新键
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/sync/SyncConflictAutoMerge.kt:77`
- **对照情况**：功能相似：同为「三方合并时库级元数据（Meta）的处置」，但主项目已就该面作出逐字段产品裁决（PD-35）
- **是否有同样风险**：no
- **建议**：无需整改——该面是已登记的产品裁决而非缺口：PD-35「三方合并时库级 Meta 字段的逐字段口径」（docs/architecture/产品裁决登记.md:1100-1119，2026-09-23 经 ISSUE-P2-280 AC③ 登记）逐字段裁定：customIcons 按 UUID 并集 + LastModificationTime LWW 合并（KdbxMerger.kt:193 起 mergeCustomIcons，对齐官方 PwDatabase.MergeInCustomIcons）；其余库级 Meta（recycleBinUuid/recycleBinEnabled/recycleBinChanged/historyMaxItems/historyMaxSize/maintenanceHistoryDays/memoryProtection/customData/defaultUserName/库名描述等）明确裁「以本地为准」，逐字段理由与重开条件均已留痕；开放条目 ISSUE-P3-385（ACTIVE_ISSUES.md:266-267）亦引用该裁决。按 AGENTS.md §4，属取舍的条目不进 ACTIVE_ISSUES，故不按原建议改 LWW 或补登待办；若未来出现跨端共享库级配置的真实需求（含收藏等 customData 跨端传播——收藏现以条目 customData 承载，在「以本地为准」口径下远端收藏变更不随合并传播），按 PD-35 重开条件①逐字段另立裁决
- **需补充信息**：（无）

### 18. Service.cpp:123 → KeePasskeyCredentialProviderService.kt:148
- **坑的本质**：依赖 m_backend 的 destroyed 信号移除 D-Bus 对象会与关闭后新到的 D-Bus 调用竞态；须在 databaseClosed 时立即主动 removeFromDBus
- **触发条件**：deleteLater 已入队未投递期间有 D-Bus 调用
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt:148`
- **对照情况**：同类模式：同为「库关闭/锁定后，外部调用通道对新请求的处置」
- **是否有同样风险**：no
- **建议**：无延迟析构信号竞态结构：Android 侧无 D-Bus 对象注册表，凭据提供者对每个请求即时复核会话态——库锁定时返回「解锁 KeePasskey」链式 Action（KeePasskeyCredentialProviderService.kt:148-162），自动填充各路径同样「确认后复核锁定态，锁定即丢弃未决响应」（AutofillConfirmActivity.kt:283、AutofillSessionGrantStore.kt:139），请求处置不依赖对象生命周期信号
- **需补充信息**：（无）

### 19. Collection.cpp:192 → 无直接对应
- **坑的本质**：KDBX 无库级全局修改时间，只能退化为根组 lastModificationTime，客户端比较集合修改时间可能不准
- **触发条件**：Secret Service 客户端查询集合 modified 时间
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 Secret Service/D-Bus 集合时间暴露面；同步新鲜度判据用远端文件 ETag（SyncEngine.kt:123-132），不消费 kdbx 内部全局时间
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 20. Clipboard.h:62 → 无直接对应
- **坑的本质**：MacPasteboard 与进程同寿、退出时无法析构必然报泄漏，是已知并刻意接受的（QTBUG-54832）
- **触发条件**：macOS 进程退出做泄漏检查
- **主项目对应位置**：无直接对应
- **对照情况**：无：Android ClipboardManager 为系统服务，无进程自持 pasteboard
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 21. EntryView.cpp:339 → VaultListProjection.kt:277
- **坑的本质**：restoreState 前必须先清排序指示器，否则恢复出的视图状态错乱（Qt 缺陷 QTBUG-86694）
- **触发条件**：setViewState 恢复带排序的表头状态
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListProjection.kt:277`（`sortEntries` 纯函数投影；**原对应点** `VaultListFilterChips.kt:29` 的筛选芯片行已于 §481 按用户裁决整体移除）
- **对照情况**：同类模式：同为「条目列表排序状态的保存/恢复」
- **是否有同样风险**：no
- **建议**：无恢复路径即无该坑：排序为会话态且明写「不持久化」（`VaultListUiState` 的 `sortOption` 随 ViewModel 存活，无视图状态序列化/restore 机制），排序在 `VaultListProjection` 纯函数投影（`selectSortedEntries` :249 → `sortEntries` :277）；若未来引入排序持久化，须以此条为前置提醒
- **需补充信息**：（无）

### 22. DatabaseOpenDialog.cpp:59 → 无直接对应
- **坑的本质**：macOS 上 Ctrl+Tab 快捷键失效（Qt 缺陷），切库修饰键改用 Alt
- **触发条件**：macOS 注册 Ctrl+Tab 切换快捷键
- **主项目对应位置**：无直接对应
- **对照情况**：无：无多数据库标签/桌面快捷键面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 23. DatabaseWidget.cpp:1263 → VaultListViewModel.kt:96
- **坑的本质**：不把焦点还给条目视图会导致搜索状态被重置，须手动 m_entryView->setFocus()
- **触发条件**：编辑/新建页关闭后未聚焦条目视图
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListViewModel.kt:96`
- **对照情况**：同类模式：同为「列表搜索态跨页面存续」（KeePassXC 侧本身 suspected）
- **是否有同样风险**：no
- **建议**：搜索态以 StateFlow 持有在 VaultListViewModel（:96 `private val searchQueryFlow = MutableStateFlow("")`），ViewModel 存活期覆盖导航往返，不依赖焦点归属；Compose 声明式状态下无「失焦即重置搜索」的隐式通道
- **需补充信息**：（无）

### 24. KdbxXmlReader.cpp:913 → VaultEntryWriteCoordinator.kt:219 ⚠ risk=yes
- **坑的本质**：KDBX 3.x 下同一附件键名可重复出现且值不同，直接覆盖会丢数据；须加随机前缀改成唯一键把两份都保留
- **触发条件**：KDBX 3.x 条目内重复附件键名（主项目对应面：第三方工具写入的 KDBX4 同名 <Key> 附件经编辑保存被折叠）
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:219`
- **对照情况**：功能相似：同为「同名附件的保真与覆盖语义」
- **是否有同样风险**：**yes**
- **建议**：证据补强后维持 risk=yes：解析侧不丢——附件收集进 List 逐份 add（KdbxXmlGroupReader.kt:163 附近 BinaryNode 回调 attachments.add，本批复核实读证实），不按键名覆盖；导出侧已按下标寻址（EntryDetailAttachmentExporter.kt:39-40，ISSUE-P3-295 注记修过「同名附件导出错内容」）。**残留缺口在编辑保存路径且无登记无测试**：mergeAttachments（VaultEntryWriteCoordinator.kt:214-224，原行写 :216-224 系行号漂移）对 data 为空的既有附件按名称 firstOrNull 匹配（:219）——若打开的库中某条目含两份同名附件（v4 文件由第三方工具写入时合法存在；主项目仅支持 KDBX4，KdbxUnsupportedVersionException.kt:6-9，触发面比 KeePassXC 的「3.x 专属」窄），UI 编辑保存会把两份折叠为第一份的两次拷贝，第二份内容静默丢失。docs/ 全文 grep mergeAttachments 零命中；AttachmentSameNameAddressingTest 仅锁定 ISSUE-P3-295 的读/导出寻址面，未覆盖编辑保存折叠。UI 新增路径的「同名附件视为替换」是已留痕口径（EntryEditViewModel.kt:418），但对文件既有同名附件被折叠无任何留痕。建议：①mergeAttachments 改按下标/refIndex 身份匹配（与导出侧 ISSUE-P3-295 同口径），或至少对「同名既有附件多于 UI 份数」时保留无法匹配的余份；②若暂不实现，须按规则 6.1 补登 ACTIVE_ISSUES（附核实时间点），避免成为无登记的静默缺口
- **需补充信息**：（无）

### 25. EntryView.cpp:412 → 无直接对应
- **坑的本质**：写给后维护者的约束：若重写 resizeEvent 而不以 QTreeView::resizeEvent 方式调父类，列宽自适应会失效
- **触发条件**：未来重写 EntryView::resizeEvent
- **主项目对应位置**：无直接对应
- **对照情况**：无：无自绘树视图/resizeEvent（Compose 声明式布局；KeePassXC 侧本身 suspected）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 26. CHANGELOG.md:10 → PasskeyData.kt:112
- **坑的本质**：为互操作把 KPEX 通行密钥 BE/BS 标志改为 true；标志被视为不可变，旧条目可能认证失效，需手动补值为 0 的属性恢复
- **触发条件**：旧版本创建的 BE/BS=false 通行密钥条目
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/model/PasskeyData.kt:112`
- **对照情况**：功能相似：同为「通行密钥 BE/BS 标志的持久化、读取与断言回传」，但主项目无任何改写既有标志的路径
- **是否有同样风险**：no
- **建议**：无同型坑面，risk 定为 no：全仓无修改既有标志的编辑/迁移路径——①断言路径的计数器回写 withSignCount 只替换 FIELD_SIGN_COUNT 一个字段，其余自定义字段（含 KPEX_PASSKEY_FLAG_BE/BS）按引用原样保留（PasskeyEntryCoordinator.kt:330、:381-388）；②v1→KPEX 就地迁移经 PasskeyData.fromCustomFields 解析后按解析值回写，显式 "0" 得以保留（PasskeyData.kt:471-477，LEGACY 键回落同理，仅缺省才回落 true）；③读取对显式 "0" 如实采纳（parseFlag PasskeyData.kt:387-394，缺省才回落 true）、断言按持久化值回传（PasskeyAuthFlags.kt:55-56，app 模块），KDoc 明示「如实回传，否则向 RP 谎报备份状态」是有意安全口径。KeePassXC 2.7.12 的坑本质是其自身为互操作翻转存量标志致旧条目失效，本仓不存在该自伤动作；他方管理器写入 BE=0/BS=0 的条目在本仓按真实值回传属如实行为而非缺陷。若产品侧需要「标志编辑/迁移入口」，属特性需求应另立条目（可引用 PD 表口径留痕），不构成本坑同型风险
- **需补充信息**：（无）

### 27. CHANGELOG.md:58 → SessionPersistence.kt:16
- **坑的本质**：直接写盘保存 + 需按键的硬件密钥时，等待按键期间库文件可能被截断为 0 字节；修复为先写内存缓冲、key transform 完成后再落盘
- **触发条件**：direct write 保存且密钥变换需硬件按键
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:16`
- **对照情况**：同类模式：同为「保存链路中加密/密钥变换与目标文件打开的时序」
- **是否有同样风险**：no
- **建议**：时序天然安全：save 链是「内存缓冲全量序列化加密（serializeToBytes，KDF 纯软件 Argon2/AES-KDF，无硬件按键等待面）→ 才调 saveWriter 落盘」，且本地通道落盘走同目录 .tmp + fsync + 原子 rename（AtomicFileWriter），目标文件在凭据就绪且加密完成前**不会被打开截断**；不存在 direct-write 先截断再等 KDF 的窗口
- **需补充信息**：（无）

### 28. CHANGELOG.md:17 → LegacyAutofillAccessibilityService.kt:178
- **坑的本质**：把初始延迟移入动作队列引发竞态，Auto-Type 间歇性失效；回退为在捕获活动窗口引用之前同步执行初始延迟
- **触发条件**：2.7.11 升级后 Linux X11 触发 Auto-Type
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillAccessibilityService.kt:178`
- **对照情况**：同类模式：同为「延迟后向目标窗口注入文本」的时序敏感路径
- **是否有同样风险**：no
- **建议**：主项目 legacy 通道不使用「初始延迟 + 动作队列」结构：回填在触发时同步执行，且前置三道复核——消费即清载荷、库锁定复核、**当前窗口包名复核**（「窗口包名 = 通知时的目标包名」才写入，LegacyAutofillAccessibilityService.kt:42/:178），单次 ACTION_SET_TEXT（:211-216）；陈旧窗口会被包名复核拒绝而非错投，无延迟队列竞态面
- **需补充信息**：（无）

### 29. CHANGELOG.md:25 → EntryDetailAttachmentExporter.kt:36
- **坑的本质**：其他工具生成的库中附件名可含斜杠/反斜杠，导出保存附件时发生目录穿越逃出基目录；保存前须剔除路径分隔符
- **触发条件**：附件名含 / 或 \\ 且导出保存到磁盘
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailAttachmentExporter.kt:36`
- **对照情况**：功能相似：同为「把库内附件（不可信名称）写到用户指定位置」
- **是否有同样风险**：no
- **建议**：无路径拼接面：附件导出走 SAF CreateDocument，目标是由用户选择器产生的文档 URI（targetUri），附件名仅作 UI 展示（EntryDetailAttachmentExporter.kt:36-56，openOutputStream 直接写 URI），从不以附件名拼文件系统路径；内部落盘缓存亦用不透明随机键 + SHA-256 规范命名（FileBinaryStore.kt:33-49，本批复核实读 store/storeFromStream 均以 newKey() 生成键），附件名不参与任何文件路径构造，目录穿越无从发生
- **需补充信息**：（无）

## 三、复核记录（独立复核结论）

1. **【EditEntryWidget.cpp:1177 悬空指针条】risk 由 yes 改为 no**：原行所述触发面「编辑期间他人合并/同步了同一条目」在主项目有结构性防护——①树为不可变 data class，编辑保存按 UUID 在当前树即时查找，条目不存在即如实报错不崩溃（VaultEntryWriteCoordinator.kt:244-248 `?: return KdbxResult.Failure(...repo_entry_not_found)`）；②合并产物采纳前有单点守卫：会话树在合并/上传窗口内被 UI 编辑替换即中止本周期而不覆盖编辑（SyncConflictAutoMerge.kt:109-112 `databaseFlow.value !== expectedSessionSnapshot → abortDiverged()`，:139/:199 两处接线；另 adoptMergedIfSessionUnchanged :89-95）——KeePassXC 式「悬空指针崩溃 + 用户编辑丢失」两面均不存在；③锁库弃编辑有一次性告知（unsavedEditRegistry.markDirtyEditsDiscarded，AutoLockSessionGuard.kt:124）。原行行号漂移已修正（守卫接线实为 :139/:199，非 :137/:196；markDirtyEditsDiscarded 实为 :124，非 :122）。反向面（外部程序改动已打开的库文件→保存静默覆盖）触发条件不同（文件被外部改动，而非会话内树替换），已作为开放条目 ISSUE-P2-378 登记（docs/ACTIVE_ISSUES.md:48/:60），无需本条新增整改。证据均为本次实读。
2. **【Merger.cpp:702 元数据合并条】risk 由 yes 改为 no**：主项目确有同型行为（MergeResult 仅承载树/墓碑/冲突/图标池四项——KdbxMerger.kt:57-63，原行写 :174-179 且漏计 conflicts，系行号漂移；合并产物由 localDb.copy 仅换 rootGroup/deletedObjects/customIcons 组装——SyncConflictAutoMerge.kt:77-83，原行写 :77-81），但该面是已登记的产品裁决而非无登记的静默缺口：PD-35「三方合并时库级 Meta 字段的逐字段口径」（docs/architecture/产品裁决登记.md:1100-1119，2026-09-23 经 ISSUE-P2-280 AC③ 登记）逐字段裁定 customIcons 按 UUID 并集+LWW 合并（KdbxMerger.kt:193 起 mergeCustomIcons），其余库级 Meta（recycleBinUuid/recycleBinEnabled/historyMaxItems/customData/defaultUserName/库名描述等）明确裁「以本地为准」并附逐字段理由与重开条件；开放条目 ISSUE-P3-385（ACTIVE_ISSUES.md:266-267）亦明示引用该裁决。原行「该面未登记进 ACTIVE_ISSUES 或已知工程限界表」的查证口径错位：按 AGENTS.md §4，判「是否为已定取舍」只认产品裁决登记表，属取舍的条目一律不进 ACTIVE_ISSUES——原行建议「实现 LWW 合并或补登 ACTIVE_ISSUES」恰违反该纪律，故 advice 整体替换。另留一处观察（不构成本条改动）：收藏状态以 customData 承载（VaultEntryWriteCoordinator.setEntryFavorite），在 PD-35「customData 以本地为准」口径下远端收藏变更不随合并传播，若该跨端传播需求成立应按 PD-35 重开条件①另立裁决。
3. **【CHANGELOG.md:10 BE/BS 标志条】risk 由 unclear 改为 no**：本次新增核实闭环了原行悬置的问题——全仓确无修改既有标志的编辑/迁移路径：①断言路径的计数器回写 withSignCount 只替换 FIELD_SIGN_COUNT 一个字段、其余自定义字段（含 KPEX_PASSKEY_FLAG_BE/BS）按引用原样保留（PasskeyEntryCoordinator.kt:330 与 :381-388）；②v1→KPEX 就地迁移经 PasskeyData.fromCustomFields 解析后按解析值回写，显式 "0" 得以保留（PasskeyData.kt:471-477，LEGACY 键回落同理，仅缺省才回落 true）；③读取对显式 "0" 如实采纳（parseFlag PasskeyData.kt:387-394）、断言按持久化值回传（PasskeyAuthFlags.kt:55-56，位于 app 模块，原行路径未写全），且该处 KDoc 明示「如实回传，否则向 RP 谎报备份状态」是有意安全口径。KeePassXC 2.7.12 的坑本质是其自身为互操作翻转存量标志（标志被视为不可变致旧条目失效），本仓不存在该自伤动作；他方管理器写入 BE=0/BS=0 的条目在本仓按真实值回传属如实行为而非缺陷。原 needInfo 悬置的「是否提供标志编辑/迁移入口」属特性需求，若产品侧想要应另立条目，不构成本坑同型风险，故 risk 定为 no、needInfo 清空。
4. **【KdbxXmlReader.cpp:913 同名附件条】risk=yes 维持，仅修正与补强证据**：①行号微调——mergeAttachments 实为 VaultEntryWriteCoordinator.kt:214-224（firstOrNull 恰在 :219，原行写 :216-224）；②补实登记与测试缺口证据：docs/ 全文 grep mergeAttachments 零命中（该折叠面无任何登记），AttachmentSameNameAddressingTest 仅锁定 ISSUE-P3-295 的读/导出下标寻址面（用例名「同名附件按下标分别取到各自的字节」等），未覆盖编辑保存折叠；③解析侧逐 <Binary> 节点 emit 入 mutableListOf（KdbxXmlGroupReader.kt:163 附近 BinaryNode 回调 attachments.add）、KDBX4-only 支持（KdbxUnsupportedVersionException.kt:6-9 KDoc「如非 v4 版本」）均实读证实，行内关于「触发面比 3.x 窄但第三方工具写入的 v4 同名 <Key> 仍可出现」的判断成立。advice 相应补入证据并加规则 6.1 补登兜底。
