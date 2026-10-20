# M1.2：SQLite 兼容策略与持久化快照版本门禁

日期：2026-10-20。状态：实现及本地验收完成，等待审查；未 commit / push。

## 基线、范围与兼容决策

开始时工作区干净，分支为 `refactor/m1-0-sqlite-transaction-cleanup`，实际 HEAD 为 `d587385f299ddeffbb7f2c28b208459887343bf3`，与已有远程跟踪引用一致。本轮保留该提交中的 M1.0 / M1.1 保护，没有切换到旧 main。

已阅读 M0 审查报告、M1.1 实现与验证报告及 Production / Labor / Payroll DDD 草案。本轮采用用户确定的策略：真正改变持久化语义时使用新世界，严格拒绝不兼容格式，不自动迁移、清空、覆盖或重建历史数据库。

本轮仍支持当前有效 v2，因为整数商品、200 tick 批次及新结算结构尚未实施。没有把当前 v2 伪装成未来格式，也没有新增 v3 数据库。

## 完整修改文件清单

生产代码路径以下均以 `src/main/java/com/bbmurloc/victoriaeconomics/server/` 为前缀：

| 文件 | 修改原因 |
|---|---|
| `storage/sqlite/EconomySchema.java` | 复用唯一 Schema 版本检查；把原 v2 DDL 提取为创建/校验共用定义，补充约束、索引、完整性和快照预检；既有文件的重检禁止创建 |
| `storage/EconomyDatabase.java` | 正式世界打开入口委托给可独立测试的文件兼容门禁 |
| `storage/sqlite/SqliteEconomyDatabase.java`（新增） | 已有文件先物理只读预检，再以不含 CREATE 权限的可写连接重新验证，通过后才交给业务层 |
| `storage/sqlite/EconomyStateCodec.java`（新增） | 固定解释 Schema v2 的 Batch / Inventory JSON；严格字段和类型校验，不做迁移或未来格式猜测 |
| `production/infrastructure/SqliteProductionExecutionRepository.java` | Batch 配置读写使用 Codec，保留原 CAS、批次生命周期及 UoW 提交确认方式 |
| `inventory/infrastructure/SqliteInventoryStore.java` | Inventory 读写使用 Codec，异常快照在业务变异和 UPDATE 前被拒绝；保留 Double 及原结算语义 |

另新增：

- `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java`：56 项真实文件 SQLite 兼容与保护测试。
- `src/test/resources/fixtures/sqlite/schema-v2.sql`：独立保留基线 v2 DDL，配合测试中的历史 JSON 字面量，避免只验证新 writer / reader 自己相互兼容。
- 本报告。

共 9 个文件。领域类、Production 应用流程、设备、NPC、工资、劳动力、所有权、M1.0 / M1.1 事务实现及其测试均未修改。

## 当前版本与 Codec 合同

唯一持久化版本来源继续是 `economy_schema_migrations.version`，必须恰好一行且为整数 2。没有添加重复的 `user_version`、额外版本表、每行格式标签或 JSON envelope。

| v2 数据 | 现有序列化与语义 |
|---|---|
| Batch configuration | 原 JSON 对象：productionMethodSelections、resolvedRecipe、installedEquipment、equipmentCapacity、workforcePlan、effectiveRevision |
| 配方 | inputs / outputs 为 Double；requiredWorkers 为整数需求 |
| Workforce | 职业到 NPC UUID 列表，以及整数 speedNumerator / speedDenominator；不转换成汇总人数 |
| Batch 进度 | SQL progress_units 为整数；满速基数 1200 tick，结合原速度分母解释；状态沿用现有枚举 |
| Inventory | location、Double capacity、Double quantities、reservations、settlements；保留现有小数和 Double 的 signed zero |
| Settlement | 原 batchId、consumed、produced，数量仍为 Double；保留比例结算含义，不增加完成/中止结果标签 |

`EconomyStateCodec` 只提供固定 v2 的 Batch / Inventory 读写，不是可插拔序列化框架。写出形状保持原样，并自检能否被当前固定合同解释。

读取时检查完整字段集合、重复键、原始 JSON 类型、整数精确性、有限 Double、UUID、位置/回执键归属，并调用现有领域构造器验证快照。缺失 int/long 字段不默认为 0；未知字段不被忽略；小数不被强转为整数；数字字符串不被当作 JSON number。

Gson 2.10.1 的 [JsonParser 源码](https://github.com/google/gson/blob/gson-parent-2.10.1/gson/src/main/java/com/google/gson/JsonParser.java)会为对象解析启用 lenient 并丢弃重复名字，因此本轮用非 lenient JsonReader 遍历结构并检查重复键，再显式构造固定类型；单独的已验证 number token 保留其词法值，以保住 `-0.0`。没有升级 Gson 或 SQLite JDBC。

Codec 同时声明 v2 的 1200 tick 语义前提；如果后续只改 FULL_SPEED_TICKS 或 Schema 版本而没有同步替换解释合同，构建后的旧 Codec 会明确拒绝继续使用。它不提供 200 tick 或 Long 数据的备用读取路径。

## 打开与拒绝顺序

正式入口保持 `EconomyDatabase.open(...)`，底层文件步骤如下：

1. 已存在文件先以 Xerial SQLiteConfig 的物理 READONLY 模式连接，继续使用 SqliteConnectionGuard。没有用 immutable 跳过 WAL，也没有先打开可写连接再设置 query_only。
2. 在一个已提交读取快照中检查原 v2 DDL、唯一版本记录、quick_check、foreign_key_check，并扫描全部 Batch（包括未被 Execution 引用的历史 Batch）和 Inventory JSON。
3. 不兼容时立即关闭只读连接并抛错；不开放业务连接，不执行 DDL、业务 UPDATE、迁移或文件重建。
4. 预检通过后，以不含 CREATE 权限的可写连接重新执行同一个校验器。既有文件的重检明确禁止进入创建分支；文件消失会打开失败，文件变空会拒绝，不静默重建。
5. 只有原本不存在的数据库才允许创建原 v2 布局。校验/创建完成后设置连接局部 foreign_keys / busy_timeout，返回受保护连接。

因此业务恢复、补偿和服务装配之前已经完成格式门禁。两个受影响适配器仍会在每次读取目标快照时校验 Codec，不能通过直接读取异常 JSON 后重新序列化来“修复”它。

Schema 仍由原版本检查负责选定合同；只读预检和可写重检调用同一实现，分别用于保护旧文件、避免两次打开之间放行变化，没有建立第二套版本权威。

DDL 的创建内容与 `d587385` 基线的 10 条 CREATE 语句逐条一致（9 张表、1 个显式唯一索引）。校验使用这些相同定义，按空白归一后严格比对，不再仅比较列名集合，因此漏约束、错类型、缺索引、附加对象或混合布局均被拒绝。它不是通用 SQL 语义等价判断器；手工修改成其他 DDL 写法也可能被拒绝，不会自动修复。

## 各版本及文件的加载行为

| 输入 | 当前程序行为 |
|---|---|
| 不存在的文件 | 创建原有 Schema v2 和原有 JSON 形状 |
| 当前有效、非空 v2 | 只读预检和可写重检通过；按 Double / 1200 tick / UUID / 设备语义加载 |
| 非空 v1 / 旧表布局 | 拒绝，保留旧表、行和文件字节，不升级 |
| 不受支持版本（测试含 -1、0、3、99） | 拒绝，不能因表名相同而猜测兼容 |
| 空版本表、多版本记录、缺表/索引、错约束/类型、额外布局、外键不一致 | 拒绝，不补建、不删行、不改版本 |
| v2 元数据配不合法或未来形状 Batch / Inventory JSON | 拒绝；未知属性和缺失字段不被默默忽略 |
| 已存在空文件或非 SQLite 文件 | 拒绝，不用新数据库覆盖 |
| 不兼容内容位于 WAL | 按 WAL 中实际已提交内容拒绝，不绕过 WAL 读取旧主文件 |

只读门禁的底层模式依据本地 sqlite-jdbc 3.46.1.0 的 SQLiteConfig 实现；[SQLite 官方文件打开说明](https://www.sqlite.org/uri.html)区分只读打开与 immutable 的锁/变化检测行为。本轮没有给正式数据库修改 journal 模式。

## 保护验证与验收结果

新增 56 项测试使用隔离的临时 SQLite 文件，覆盖：

- 历史 v2 字面量中的 Double 库存、预留、小数结算、设备和 NPC UUID 原样加载；普通读取不重写文件；小数及 signed zero 经 v2 写回仍保留。
- ACTIVE、PAUSED、SETTLING_COMPLETED、SETTLING_ABORTED 均按 1200 tick 基数恢复，进度不转换为 200 tick。
- 非空 v1、未知版本、不完整/混合 Schema、约束/类型/唯一索引变化及外键孤儿明确拒绝。
- Batch 的缺字段、未来版本/人数形状、非整数参数、错误数值类型、重复键、非法 UUID、速度/配方错误等拒绝。
- Inventory 的缺字段、null、数字字符串、重复数量、未来回执、错误 location/batchId、负数回执和非有限数量拒绝；直接业务 deposit 也不产生 UPDATE。
- 历史未引用 Batch 的异常 JSON 仍能在业务恢复前被发现；SQL REAL 进度不被 getLong 静默截断后放行启动。
- 每次文件拒绝前后比较整个文件字节，全部一致；WAL 用例另外比较 WAL 字节，也完全一致。
- 只读连接本身不能执行 UPDATE；既有文件的可写重检也不会在空布局上创建数据库；未解决的本地事务不能通过兼容检查获得 committed 读取。

最终执行：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：**BUILD SUCCESSFUL，退出码 0，2m 20s，9 个任务全部执行**。

| 测试类 | 数量 | 失败 / 错误 / 跳过 |
|---|---:|---|
| InventoryAndEquipmentTest | 6 | 0 / 0 / 0 |
| SqliteInventoryContractTest | 5 | 0 / 0 / 0 |
| BoundaryRecoveryTest | 10 | 0 / 0 / 0 |
| BuildingProductionIsolationTest | 5 | 0 / 0 / 0 |
| ProductionIntegrationTest | 19 | 0 / 0 / 0 |
| RecoveryAndControlsTest | 11 | 0 / 0 / 0 |
| StartCommitRecoveryTest | 11 | 0 / 0 / 0 |
| WorkforceContractTest | 3 | 0 / 0 / 0 |
| ProductionDomainTest | 13 | 0 / 0 / 0 |
| ProductionRepositoryTest | 11 | 0 / 0 / 0 |
| SqliteTransactionsTest（M1.0，未改） | 26 | 0 / 0 / 0 |
| EconomyUnitOfWorkTest（M1.1，未改） | 29 | 0 / 0 / 0 |
| SchemaCompatibilityTest（新增） | 56 | 0 / 0 / 0 |
| **合计** | **205** | **0 / 0 / 0** |

[JUnit 报告](../../build/reports/tests/test/index.html)。产物为 `build/libs/victoria_economics-0.1.1-1.21.1.jar`。M1.0 / M1.1 的事务实现和故障测试保持原样；全部原有 149 项测试通过。

本轮为纯 JVM 和临时文件 SQLite 验证，未启动 Minecraft，未对真实世界数据库执行打开、写入或迁移。只读计算 `run/saves/新的世界/victoria_economics/economy.sqlite` 的检查前后 SHA-256 均为 `3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`。

## M1.3 必须遵守的约束及边界

1. 本轮没有启用新 Schema 版本，因为尚无实际持久化格式变化。M1.3 真正落地 Long 商品/配方、200 tick 基数和新结算结构时，必须同时启用下一 Schema 版本及对应 Codec，切换所有相关读写入口；不能继续以 v2 保存新语义。
2. 新程序必须在任何业务写入前拒绝 v1 / v2，不迁移、不清库、不 ALTER 旧数据库，也不将历史 Double 取整或把旧 progress_units 套入 200 tick。
3. 新格式要明确约束数量、容量、预留、完成/中止回执和命令匹配所需字段。若引入库存 revision / CAS，其 DDL 和 Codec 合同必须与新 Schema 一起生效。
4. 设备、NPC 后续退出也不能隐式把历史快照转换为人数。M1.3 尚未退出的模型字段应按该阶段真实实现保留；未来不兼容更改继续版本化，不能提前宣称已有 BuildingStaffing 格式。
5. 继续复用本轮 v1 / v2 字面量夹具，并补充“真正的新程序/新 Codec 拒绝旧非空数据库”的测试。本轮证明当前 v2 的正确解释及未知版本拒绝，未实现未来 reader，不把这一结果当成新格式本身已经验收。
6. 新格式启用前必须同时通过 M1.0 / M1.1 故障回归及完整构建。未知提交不自动重试、隔离连接不恢复为权威等保护继续适用。

没有需要立即更改现有 Schema 才能完成 M1.2 的阻塞。剩余的新版本 DDL、整数 Codec 和新结算合同属于 M1.3，未提前实施。启动预检会扫描 Batch 历史和 Inventory 快照；没有进行大规模存档性能测试，也不承诺外部进程同时替换文件时的跨进程原子性或临时锁文件字节不变。现有 Gradle 弃用提示保留。本轮到此停止，等待审查。
