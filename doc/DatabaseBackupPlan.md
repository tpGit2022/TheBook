# 数据库完整备份实现计划

## 1. 背景与目标

当前 App 使用 Room 管理私有数据库。Release 包无法从外部直接访问 App 私有目录，因此需要由 App 主动将私有 `databases` 目录打包为 ZIP，并通过 Android 系统文件选择器导出到用户指定的位置。

本功能的主要目标是：

- 完整导出 App 私有 `databases` 目录。
- 保证备份期间数据库文件处于一致状态。
- 导出的 SQLite 数据库可以在外部解压、查看。
- 为后续实现完整数据库还原预留稳定、可校验的备份格式。
- 不因外部存储或网盘写入较慢而长时间阻塞 App 的数据库访问。

## 2. 第一版范围

第一版实现：

- 导出整个 App 私有 `databases` 目录。
- 生成 ZIP，并由用户通过系统文件选择器指定保存位置。
- ZIP 内包含备份清单、数据库主文件及可能存在的 WAL、SHM、journal 文件。
- 备份前等待正在执行的数据库操作结束。
- 创建数据库快照期间阻止新的数据库操作。
- 执行 WAL checkpoint、关闭 Room 后再复制数据库目录。
- 校验临时快照后再生成 ZIP。
- 提供明确的准备中、导出中、成功和失败状态。

第一版暂不实现：

- ZIP 导入还原。
- ZIP 加密。
- 自动定时备份。
- 云端同步。
- `shared_prefs`、缓存、媒体文件等非数据库数据的备份。

现有 XLS 数据导入、导出功能继续保留。两种导出方式的定位如下：

- 记录导出（XLS）：方便查看、编辑以及逻辑数据导入。
- 完整数据库备份（ZIP）：保留数据库原貌，用于完整还原和外部分析。

## 3. 当前代码风险

当前数据库由 `AppDatabase.getInstance()` 公开提供，各业务模块取得 Room 实例后直接调用 DAO。数据库操作分布在以下位置：

- `ui/AddViewModel.kt`
- `ui/AddFragment.kt`
- `ui/MineViewModel.kt`
- `ui/StatisticsFragment.kt`
- `tool/DataMigrateTools.kt`

如果备份代码直接关闭 Room 或复制数据库文件，其他线程可能已经持有旧的 Room 或 DAO 实例。仅在导出前调用一次 checkpoint 不能消除以下竞争条件：

- checkpoint 完成后、复制开始前又发生新写入。
- 主数据库文件与 `-wal` 文件复制自不同时间点。
- Room 被关闭后，后台任务继续使用旧实例。
- 一组关联写入只完成一半时被备份，例如已新增记录但尚未更新月统计。

因此，可靠备份的前提是所有数据库访问都必须经过统一的生命周期门闩。

## 4. 数据库访问门闩

### 4.1 DatabaseProvider

新增 `DatabaseProvider`，由它唯一管理：

- Room 实例的创建。
- Room 实例的关闭和延迟重建。
- 普通数据库操作。
- 数据库维护及备份操作。
- 公平模式的 `ReentrantReadWriteLock`。

建议提供以下受控入口：

```kotlin
DatabaseProvider.withDatabase(context) { database ->
    // DAO 查询或事务
}

DatabaseProvider.createSnapshot(context, targetDirectory)
```

原来的公开 `AppDatabase.getInstance()` 应删除或限制为业务代码不可访问，避免后续代码绕过门闩。

### 4.2 锁的语义

普通查询和增删改使用共享锁，锁覆盖完整 DAO 操作：

```text
普通数据库操作
    -> 获取共享锁
    -> 获取 Room 实例
    -> 完成 DAO 查询或事务
    -> 释放共享锁
```

数据库快照使用独占锁：

```text
数据库备份
    -> 获取独占锁
    -> 等待正在执行的数据库操作结束
    -> 阻止新的数据库操作进入
    -> WAL checkpoint
    -> 关闭 Room
    -> 复制 databases 目录到缓存
    -> 释放独占锁
```

共享锁只用于保护 Room 生命周期，不代替 SQLite 自己的事务管理。普通数据库操作之间仍可并发，备份则具有独占性。公平锁可以防止持续到来的普通读取导致备份长期得不到独占锁。

### 4.3 使用约束

- DAO 只能在 `withDatabase` 回调内部使用，不能返回或保存到外部。
- 多步关联写入必须使用同一个 `withDatabase`，并尽量放入同一个 Room 事务。
- 全项目改造完成后，搜索确认不存在绕过 Provider 的 `AppDatabase.getInstance()` 调用。

## 5. 原子性改造

### 5.1 新增记录

当前新增 `daily` 记录和更新月统计是分步完成的。计划将两部分放入同一个 `runInTransaction`：

```text
withDatabase
    -> runInTransaction
        -> 新增 daily
        -> 查询并更新 stat_month
```

这样备份不会得到“记录已经加入，但统计尚未更新”的中间状态；其中任一步失败时，整次写入都回滚。

### 5.2 首次数据迁移

当前首次数据迁移分别写入记录和统计。计划调整为：

1. 在数据库锁外解析 XLS。
2. 在数据库锁外计算统计数据。
3. 通过一次 `withDatabase + runInTransaction` 写入记录和统计。
4. 事务成功后才设置 `hasMigration=true`。

这样用户即使在首次迁移期间进入备份页面，也不会导出只完成了一部分的迁移结果。

### 5.3 其他数据库调用

以下操作全部改为通过 `DatabaseProvider.withDatabase()` 执行：

- 记录新增和删除。
- 首页及统计页面查询。
- XLS 导出查询。
- XLS 导入预检查。
- XLS 覆盖或合并导入事务。
- 首次数据迁移。

## 6. 数据库快照流程

`DatabaseProvider.createSnapshot()` 在独占锁内执行：

1. 获取或创建 Room 实例，确保数据库已正常打开。
2. 读取 `PRAGMA user_version`，作为清单中的实际数据库版本。
3. 执行：

   ```sql
   PRAGMA wal_checkpoint(TRUNCATE)
   ```

4. 完整读取 checkpoint 返回结果。
5. 如果 `busy != 0`，终止备份，不继续导出状态不确定的文件。
6. 调用 `RoomDatabase.close()`。
7. 将 Provider 保存的 Room 实例置空。
8. 使用 `context.getDatabasePath(Constants.APP_DATABASE_NAME).parentFile` 定位数据库目录，不硬编码 `/data/data/...` 路径。
9. 将 `databases` 目录下所有普通文件复制到本次专用缓存目录。
10. 释放独占锁。

下一次业务数据库操作会通过 Provider 自动创建新的 Room 实例，无须重启 App。

无论快照成功还是失败，必须保证：

- 独占锁在 `finally` 中释放。
- 已关闭的 Room 实例不会继续保留在 Provider 中。
- 原始数据库文件不会被移动、重命名或删除。

## 7. 使用缓存快照缩短阻塞时间

不直接在数据库关闭期间向用户选择的外部 `Uri` 压缩和写入。第三方文件管理器或网盘可能写入缓慢，如果数据库一直保持关闭，会导致 App 长时间不可用。

采用以下两阶段流程：

```text
独占阶段：
checkpoint
    -> 关闭 Room
    -> 复制私有 databases 到本地 cache
    -> 释放独占锁

普通阶段：
校验缓存快照
    -> 计算 SHA-256
    -> 生成备份清单
    -> 写 ZIP 到用户选择的 Uri
    -> 清理本次缓存
```

临时目录建议为：

```text
cache/database_backups/<随机ID>/
└── databases/
    └── daily.db
```

清理时只删除本次创建且路径已经确认的临时目录，不递归删除整个缓存根目录。

## 8. ZIP 格式

建议文件名：

```text
AppBackup_yyyy_MM_dd_HH_mm_ss.zip
```

ZIP 内部结构：

```text
backup_manifest.json
databases/
├── daily.db
├── daily.db-wal       # 存在时收录
├── daily.db-shm       # 存在时收录
└── daily.db-journal   # 存在时收录
```

`backup_manifest.json` 示例：

```json
{
  "backupFormatVersion": 1,
  "packageName": "com.seeksky.thebook",
  "appVersionName": "1.0",
  "appVersionCode": 1,
  "databaseVersion": 4,
  "createdAt": 1787998245000,
  "files": [
    {
      "path": "databases/daily.db",
      "size": 32768,
      "sha256": "..."
    }
  ]
}
```

字段规则：

- `backupFormatVersion` 表示备份包结构版本，不等同于 Room 数据库版本。
- `databaseVersion` 从 SQLite 的 `PRAGMA user_version` 读取。
- `createdAt` 使用 Unix 毫秒时间戳。
- 文件路径统一使用 `/`，不包含设备绝对路径。
- 每个文件记录大小和 SHA-256，为后续导入校验提供依据。
- 不写入设备型号、用户名等与还原无关的信息。

实现使用系统自带的 `ZipOutputStream`、`MessageDigest` 和 `org.json`，不新增第三方依赖。

## 9. 快照校验

写入外部存储前执行以下检查：

1. `databases/daily.db` 存在。
2. 主数据库文件大小大于 0。
3. 使用只读方式打开缓存中的 `daily.db`。
4. 执行：

   ```sql
   PRAGMA quick_check
   ```

5. 只有检查结果为 `ok` 才继续导出。
6. 计算所有备份文件的 SHA-256。
7. 生成 `backup_manifest.json`。
8. 生成 ZIP。

checkpoint、文件复制或 `quick_check` 任一步失败时，都不能返回成功结果。

## 10. 导出组件设计

建议新增：

```text
app/src/main/java/com/seeksky/thebook/backup/
├── BackupArchiveWriter.kt
├── DatabaseBackupExporter.kt
└── DatabaseBackupModels.kt
```

职责划分：

- `DatabaseProvider`：数据库实例、共享访问和独占维护。
- `DatabaseBackupExporter`：快照校验、清单生成、ZIP 和 `ContentResolver` 输出。
- `BackupArchiveWriter`：不依赖 Android API 的 ZIP 与 SHA-256 实现，便于 JVM 单元测试。
- `MineViewModel`：异步调度、互斥检查和页面状态。
- `MineFragment`：确认弹窗、文件选择器及状态展示。

导出结果使用明确类型，不使用简单 Boolean：

```kotlin
sealed class DatabaseBackupResult {
    data class Success(
        val fileCount: Int,
        val totalBytes: Long
    ) : DatabaseBackupResult()

    data class Failure(
        val reason: String,
        val cause: Throwable? = null
    ) : DatabaseBackupResult()
}
```

## 11. 页面与交互

在“我的”页面增加独立入口，并明确区分现有功能：

- 记录导出（XLS）
- 记录导入（XLS）
- 完整数据库备份（ZIP）

完整数据库备份的交互流程：

1. 用户点击“完整数据库备份（ZIP）”。
2. 弹窗说明备份内容。
3. 提醒 ZIP 未加密，可能包含私人数据。
4. 用户确认后打开系统文件选择器。
5. 使用 `ActivityResultContracts.CreateDocument("application/zip")` 创建目标文档。
6. 用户选择保存位置后，ViewModel 开始创建备份。

建议使用以下状态：

```kotlin
sealed class DatabaseBackupState {
    object Idle : DatabaseBackupState()
    object PreparingSnapshot : DatabaseBackupState()
    object WritingArchive : DatabaseBackupState()
    data class Success(
        val fileCount: Int,
        val totalBytes: Long
    ) : DatabaseBackupState()
    data class Error(val message: String) : DatabaseBackupState()
}
```

状态对应 UI：

- `PreparingSnapshot`：显示“正在准备数据库快照…”。
- `WritingArchive`：显示“正在写入备份文件…”。
- 运行期间禁用完整备份、XLS 导入和 XLS 导出入口。
- 成功后显示文件数量和总大小。
- 失败时显示可理解的原因。
- 用户取消系统文件选择器时静默返回，不视为失败。
- 页面销毁后不能通过已经失效的 Binding 更新 UI。

第一版不使用 WorkManager。当前数据库较小，备份是用户在前台主动触发的短任务。

## 12. 外部存储权限

完整数据库备份通过 SAF 写入：

```kotlin
ActivityResultContracts.CreateDocument("application/zip")
```

因此本功能本身不依赖：

- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`
- `MANAGE_EXTERNAL_STORAGE`

项目现有的全文件权限可能仍被媒体加解密功能使用，本次不顺带删除，避免扩大变更范围。但数据库备份入口和导出代码不得调用存储权限检查。

## 13. 失败处理与恢复

所有失败路径必须满足：

- Room 实例若已关闭，Provider 中必须置空，允许下次自动重建。
- 独占锁必须在 `finally` 中释放。
- 输入输出流通过 `use` 自动关闭。
- 本次临时快照在 `finally` 中清理。
- 外部 ZIP 输出流已经打开并发生写入失败时，尝试通过 `ContentResolver.delete()` 删除残缺文档；如果在打开输出流前失败，不删除目标 URI，避免误删用户选择的既有文件。
- 如果目标 Provider 不支持删除，至少向用户明确提示导出失败。
- 失败不能修改或删除原始数据库。
- 不允许通过移动原数据库来创建备份。

需要覆盖以下明确错误：

- 数据库目录或主库不存在。
- checkpoint 返回 busy。
- 临时缓存空间不足。
- 文件复制失败。
- `PRAGMA quick_check` 失败。
- 无法打开目标 `Uri`。
- ZIP 写入中断。
- 用户撤销目标文档访问权限。

## 14. 文件改造范围

计划新增：

- `database/DatabaseProvider.kt`
- `backup/BackupArchiveWriter.kt`
- `backup/DatabaseBackupExporter.kt`
- `backup/DatabaseBackupModels.kt`
- 与 ZIP、清单生成相关的单元测试。
- 与数据库快照和并发门闩相关的 Android 仪器测试。

计划修改：

- `database/AppDatabase.kt`
- `ui/AddViewModel.kt`
- `ui/AddFragment.kt`
- `ui/MineViewModel.kt`
- `ui/MineFragment.kt`
- `ui/StatisticsFragment.kt`
- `tool/DataMigrateTools.kt`
- `res/layout/fragment_mine.xml`
- `res/values/strings.xml`

实际实现时，应再次使用全项目搜索核对数据库调用点，避免遗漏后续新增或当前未列出的调用。

## 15. 测试计划

### 15.1 JVM 单元测试

- ZIP 目录结构正确。
- ZIP 子目录路径使用 `/` 且不存在绝对路径。
- 清单字段正确。
- SHA-256 计算正确。
- 空目录时失败。
- 缺少 `daily.db` 时失败。
- 输出异常时流和临时文件正确关闭、清理。

### 15.2 Android 仪器测试

1. 写入多条数据后导出。
2. 解压备份并执行 `PRAGMA quick_check`。
3. 验证导出数据库记录数与快照时一致。
4. 人为保持一个数据库操作未结束，验证备份会等待。
5. 备份获得独占锁后，验证新的数据库操作会等待。
6. 备份结束后数据库可以自动重新打开并继续写入。
7. checkpoint 失败时不会产生成功结果。
8. 连续多次备份不会复用已经关闭的 Room 实例。

### 15.3 手工测试

- 新增记录后立即导出。
- 连续点击备份入口。
- 导出过程中切换页面。
- 在系统文件选择器中取消。
- 保存到手机 Downloads。
- 保存到第三方文件管理器或网盘。
- 模拟缓存或目标存储空间不足。
- 在电脑上解压并使用 SQLite 工具打开 `daily.db`。
- 在 Release 包中确认无需访问 App 私有目录即可完成导出。

## 16. 实施顺序

1. 新增 `DatabaseProvider` 和共享/独占访问机制。
2. 改造所有直接 `AppDatabase.getInstance()` 调用。
3. 将新增记录和首次迁移改为原子事务。
4. 实现数据库缓存快照。
5. 实现 `quick_check`、SHA-256 和备份清单。
6. 实现 ZIP 到 SAF `Uri` 的输出。
7. 增加 ViewModel 状态和互斥控制。
8. 增加“完整数据库备份”页面入口。
9. 添加 JVM 单元测试和 Android 仪器测试。
10. 全项目搜索确认没有绕过 Provider 的数据库访问。
11. 在 Windows 环境编译并进行实机验证。

## 17. 构建约束与验证命令

本项目永久禁止在 WSL 中执行任何 Gradle 构建命令，包括 `gradle`、`gradlew` 和 `./gradlew`。

实现完成后，由用户在 Windows PowerShell 中执行：

```powershell
cd E:\MyCode\AndroidCode\TheBook
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

连接测试设备后执行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 18. 完成标准

满足以下条件后，完整数据库备份功能视为完成：

- 所有数据库业务调用均经过 `DatabaseProvider`。
- 备份能够等待已有数据库操作并独占快照阶段。
- 快照期间 Room 被正确 checkpoint、关闭并在后续按需重建。
- 外部 ZIP 写入不会持续阻塞数据库访问。
- ZIP 包含清单和完整 `databases` 文件。
- 缓存数据库通过 `PRAGMA quick_check`。
- 清单包含数据库版本、文件大小和 SHA-256。
- 用户取消、空间不足、输出失败等场景不会影响原数据库。
- 备份结束后 App 可以继续正常查询和写入。
- Windows PowerShell 构建、测试及 Release 实机验证通过。
