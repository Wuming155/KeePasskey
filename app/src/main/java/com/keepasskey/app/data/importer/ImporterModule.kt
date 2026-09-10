package com.keepasskey.app.data.importer

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * ISSUE-P3-19 导入解析器的 Hilt 多绑定模块（框架子批贡献）。
 *
 * 多绑定的意义（并行子批零冲突）：`EntryImporter` 以 `@IntoSet` 汇聚为
 * `Set<EntryImporter>` 注入 [ImporterRegistry]，因此**新增数据源 = 新增一个 `@Binds @IntoSet`
 * 方法 + 一个实现类**，不改调用方（工程规则：开闭原则）。
 *
 * 另一子批（Bitwarden JSON / 1Password 1PUX）在 `data/importer/` 下新增自己的
 * `@Module @InstallIn(SingletonComponent::class)` 与两条 `@Binds @IntoSet` 即可自动注册，
 * 无需改动本文件。
 *
 * **ISSUE-P3-19 集成补记（编排者）**：并行子批的任务书禁止其自建 Hilt 模块（约定「由编排者在
 * 集成阶段统一编写多绑定」），故 Bitwarden JSON 与 1Password 1PUX 的两条绑定由编排者**就地
 * 追加到本模块**——否则 [ImporterRegistry.find] 对这两个数据源恒返回 null，控制器只能
 * fail-closed 报 `SOURCE_UNAVAILABLE`，即「解析器已就绪但功能不可用」。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ImporterModule {

    /** KeePass 2.x 明文 XML 解析器（框架子批）。 */
    @Binds
    @IntoSet
    abstract fun bindKeePassXmlImporter(importer: KeePassXmlImporter): EntryImporter

    /** 浏览器密码导出 CSV 解析器（框架子批）。 */
    @Binds
    @IntoSet
    abstract fun bindBrowserCsvImporter(importer: BrowserCsvImporter): EntryImporter

    /** Bitwarden 明文 JSON 解析器（解析器子批；集成阶段由编排者注册）。 */
    @Binds
    @IntoSet
    abstract fun bindBitwardenJsonImporter(importer: BitwardenJsonImporter): EntryImporter

    /** 1Password 1PUX（ZIP + export.data）解析器（解析器子批；集成阶段由编排者注册）。 */
    @Binds
    @IntoSet
    abstract fun bindOnePasswordPuxImporter(importer: OnePasswordPuxImporter): EntryImporter
}
