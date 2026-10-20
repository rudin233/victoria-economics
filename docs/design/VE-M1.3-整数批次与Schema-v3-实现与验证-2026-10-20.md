# M1.3：整数商品、200 economic tick 批次与 Schema v3

日期：2026-10-20。状态：实现及本地验收完成，等待源码审查；未 commit、push 或合并。

## 基线与范围

开始时工作区干净。实际分支为 `refactor/m1-0-sqlite-transaction-cleanup`，实际 HEAD 为 `dc8a036985d00379059ba2047407576ea2019d42`，包含已审查的 M1.0、M1.1、M1.2。结束时 HEAD 未变。

已阅读 M0 源码审查及分阶段计划、Production / Labor DDD 草案、M1.2 实现与验证报告。本轮只实现 M1.3；没有删除设备或 NPC，没有改动工资、地区劳动力、建筑所有权，没有实现单一 RECONFIGURING 屏障。

实施按以下不变量推进：先确认调用范围与数值含义；同步切换领域、端口、服务、持久格式；再补充双连接及故障测试，最后执行完整构建。各层不能单独把 v2 Double JSON 当成新整数数据使用。

## 修改前的源码影响分析

| 路径 / 职责 | 判定与处理 |
|---|---|
| ProductionMethodDefinition、ProductionMethodRules、ResolvedProductionRecipe | 商品输入/输出及 PM 增量改为 Long；职业需求仍是 Integer。PM 合并允许有符号增量，最终验证合法配方。 |
| ProductionBatch、ProductionExecution | 权威进度原本已为 long 单位及整数速度分数；保留该实现，把满速基数由 1200 改为 200。ProductionExecution 的饱和推进逻辑无需改写。 |
| ProductionBatchConfiguration、WorkforcePlan、WorkforcePlanningService | 保留不可变员工 UUID、设备及配方快照。复用整数交叉乘法、逐职业 10% 门槛和瓶颈比例；补上空职业需求拒绝。 |
| GoodsInventory、ProductionInventoryPort、SqliteInventoryStore | 实际库存、容量、Reservation、结算数量全部是商品量，改为 long；移除结算 progress 参数，增加明确的结果命令和 SQL revision。 |
| ProductionService、ProductionJournal / StartIntent | 服务从持久化 SETTLING 状态生成冻结命令。Journal / StartIntent 只记录身份及恢复事实，不持有 Double 商品量，无需改动其合同。 |
| EconomySchema、EconomyStateCodec、Execution Repository | 真正切换 Schema 和 Batch/Inventory 合同。Execution Repository 已通过 Codec 读写，无需改动其 CAS 或提交后 revision 确认。 |
| ProductionCommands | 仅商品数量、库存容量参数改为 LongArgumentType；设备数量仍为 int。 |
| StaffingCalculator、StaffingSnapshot、EquipmentCapacityCalculator、StaffingExpectation | 这里的 double 是比例、设备能力或诊断数据，不是商品量；保持原样。OccupationDefinition 的工资倍率也不改。 |
| EconomicClock | 一分钟仍为 1200 economic ticks；Batch 时长变化不改变时钟单位，不引入离线补算。 |
| 测试、夹具与 JSON 字面量 | 逐项替换旧商品 Double、1200 tick 和比例中止断言；保留幂等、失败、恢复、设备及员工保护用例。历史 v2 夹具保持独立。 |

没有对所有 Double 进行全局替换。

## 整数商品与生产进度

`ProductionMethodDefinition.inputChanges/outputChanges`、最终配方和全部库存物料接口采用 `Map<String, Long>`。默认商品定义改用 long 字面量，没有把历史数据库数量转换为整数。

PM 按现有分组次序组合，各项使用 `Math.addExact`；允许负的增量，组合完成后只移除恰为 0 的项。最终 inputs 可以为空，但存在的输入、输出必须严格大于 0；输出不能为空；最终职业需求必须非空且各项严格大于 0。负的最终商品量、零数量最终配方及整数溢出均拒绝。

库存容量及实际余额非负，余额可以为 0；入库、出库、预留及冻结命令中的商品条目必须为正数。对空 inputs 的合法配方仍建立一个有 BatchId 的空 Reservation，结算仍要求该预留存在。数量、容量总和、预留总和、余额增减使用精确 long 运算。计算在临时副本上完成；溢出或容量校验失败不会部分修改对象或释放预留。

普通入库受容量限制；出库不能动用其他批次预留。已承诺批次的完整产出允许使总库存超过容量，但不能超出 long 可表示范围；超容状态继续阻止普通入库和新批次预留，允许出库恢复容量。ArithmeticException 不会被转换成截断值或成功回执。

速度仍是 `s = min(n_i / N_i)`，其中每个正需求职业都必须满足 `10 * n_i >= N_i`。设备只沿用现有参与人数上限，不再额外乘一次速度；本轮未提前删除设备逻辑。瓶颈比例由整数分子、分母表达，冻结在 BatchConfiguration 内。

进度上限为 `200 * speedDenominator`，每个 economic tick 增加 `speedNumerator`，达到上限时饱和并进入 SETTLING_COMPLETED。满速第 200 tick 完成；3/5 速度第 333 tick 为 999/1000，第 334 tick 饱和完成。`getProgress()`、`productionSpeed()` 的 double 仅用于展示，不参与商品结算。当前 Batch 的配方、速度、设备及员工快照在启动后保持不可变。

## 完成与中止结算合同

新增不可变命令：

```java
record ProductionSettlement(UUID batchId, Outcome outcome,
                            Map<String, Long> inputs, Map<String, Long> outputs) {
    enum Outcome { COMPLETED, ABORTED }
}
```

端口为 `SettlementReceipt settle(UUID location, ProductionSettlement command)`；回执持有同一完整命令，服务必须核对相等后才能推进 Execution。

| 结果 | 扣除投入 | 生成商品 | 释放预留 | 持久身份 |
|---|---|---|---|---|
| COMPLETED | 全部冻结 inputs | 全部冻结 outputs | 全部 | BatchId + COMPLETED + 完整 inputs/outputs |
| ABORTED | 0 | 0 | 全部 | BatchId + ABORTED + 完整 inputs/outputs |

ABORTED 也保留完整原配方用于重放匹配，并不持有按比例计算的商品。释放预留后原料恢复可用，实际原料余额没有被先扣再退。

同地点同 BatchId 的相同命令返回原结果；不同 outcome、inputs 或 outputs 一律冲突，不能覆盖旧回执。匹配重放不会重写库存 JSON 或增加 revision。服务只从已持久化的 SETTLING_COMPLETED / SETTLING_ABORTED 及冻结 Batch 配方构造命令，完全移除了结算的 Double progress 参数。

保留 SETTLING 状态、StartIntent、Journal、设备保护及启动失败补偿。库存结算成功与 Execution 后续检查点仍是现有可恢复协议：库存回执丢失或 Execution 保存失败时，持久 SETTLING 和 BatchId 允许重启读取并匹配已有结算；不能重复扣料或产出。本轮没有把 METHODS / EQUIPMENT / WORKFORCE 三个边界改成 M3.1 的单屏障。

## 库存写事务、CAS 与发布

所有库存创建和修改复用 `SqliteTransactions.write`，没有扩展 EconomyUnitOfWork 或新增平行事务框架。其已有 WriteSession 足够覆盖单个库存地点的受控读改写；PM/Execution 的显式跨聚合 UoW 继续原样保留。

修改顺序为：

1. 验证连接可信且无未解决的本地事务。
2. 通过 M1.1 的 JDBC 配置机制取得 IMMEDIATE 写事务。
3. 在会话内重新 SELECT 权威 state_json 及地点 revision，解码成 detached 库存对象。
4. 执行领域修改、校验整数及容量不变量。
5. 用 `UPDATE ... SET state_json=?, revision=? WHERE location_id=? AND revision=?` 做 CAS，revision 用 `Math.incrementExact` 增加；受影响行数必须为 1。
6. 外层物理 commit 和连接清理得到确认后才返回结果 / 回执。失败时不发布 detached 对象。

SQLite 同时只允许一个写者，因此其他连接不能在第 3 至第 5 步间插入库存写入。CAS 额外拒绝不符合预期的 revision，冲突导致整个写事务回滚。普通 inspect/storageStatus/isReserved 保留 requireCommittedReads，不获取 IMMEDIATE 写锁。

仍使用 sqlite-jdbc 3.46.1.0。复用的 M1.1 实现临时设置已登记的 driver transaction mode 为 IMMEDIATE，让 JDBC `setAutoCommit(false)` 自行执行 BEGIN IMMEDIATE；事务开始后把后续 driver 模式改回 DEFERRED，以免 JDBC commit/rollback 自动开始的空事务重新抢写锁。不手写原生 BEGIN，不混用两套 BEGIN，不新增 unwrap 绕过 Guard。

`SqliteTransactions`、`SqliteConnectionGuard`、PM/Execution 仓储和 UoW 的实现本轮均未修改。rollback 失败隔离、commit 结果未知时不猜测、Savepoint 失败阻止外层提交、派生 JDBC 句柄隔离、提交后才确认 Execution revision 的保护继续有效。库存未知 commit 不自动重试，也不向调用者返回成功回执；恢复必须重新读取持久状态并按完整命令核对。

## Schema v3 与固定 Codec

正式启用 `EconomySchema.VERSION = 3`。这是实际的持久语义变化，不是把 v2 重命名后继续使用。

SQL 布局只为 inventory_locations 增加 `revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision >= 0)`；新地点 revision 为 0。其他生产表、设备表和员工快照表保留。版本仍由原 `economy_schema_migrations` 单行权威管理，没有另建版本表或迁移器。

Batch JSON 的固定外层为：

```json
{"formatVersion":3,"fullSpeedTicks":200,"snapshot":{
  "productionMethodSelections":{"base":"method"},
  "resolvedRecipe":{"inputs":{"ore":10},"outputs":{"metal":5},"requiredWorkers":{"worker":1}},
  "installedEquipment":100,"equipmentCapacity":100,
  "workforcePlan":{"employees":{"worker":["00000000-0000-0000-0000-000000000003"]},"speedNumerator":1,"speedDenominator":1},
  "effectiveRevision":0
}}
```

SQL progress_units 仍为整数，Codec 按上述 200 tick 基数验证状态与进度。Inventory JSON 的固定外层为 `{"formatVersion":3,"snapshot":...}`；snapshot 包含 location、整数 capacity、quantities、reservations、settlements。每个 settlements[BatchId] 为 `{"command":{"batchId":...,"outcome":"COMPLETED"或"ABORTED","inputs":...,"outputs":...}}`。SQL revision 不复制进 JSON 或 ProductionExecution。

Codec 同时检查精确字段集合、JSON 原生类型、重复字段、规范 UUID、位置/BatchId 归属及领域不变量。全部整数字段要求整数 JSON 字面量并落在 long / int 范围；`12.5`、`12.0`、`12e0`、数字字符串、溢出以及旧的 `-0.0` 都拒绝。没有 round、cast 或截断兼容。编码后用相同固定解码合同自检，不构建通用序列化框架。

新 envelope 能阻止仅调整表结构或伪造元数据版本后，把原始 v2 JSON 当作 v3 读取。旧 1200 tick Batch、Double 库存、无 outcome 的旧比例结算回执没有备用解码入口；NPC UUID 和设备快照仍按原结构保留。

| 数据库 / 文件 | 当前加载行为 |
|---|---|
| 不存在的文件 | 新建完整 v3 SQL 布局；后续 writer 只写 v3 JSON。 |
| 合法 v3 | 通过 M1.2 原有完整布局、版本、quick_check、FK、全部 Batch 和 Inventory 快照预检；正常加载。 |
| v1 / v2 | 物理只读预检拒绝；不迁移、清空、重建或取整。 |
| 未知版本、混合/不完整布局、异常 JSON | 业务连接交付前拒绝，不修复原文件。 |
| 已存在的空文件 / 非 SQLite 文件 | 拒绝，不自动创建新库覆盖。 |

继续复用 M1.2 的单一验证器：先物理 READONLY 读取已提交快照（包含 WAL），通过后才用不含 CREATE 权限的可写连接重检，重检禁止已有文件进入新建路径。所有业务恢复、补偿及写入在门禁通过后才开始。拒绝路径未执行 DDL / DML 或 WAL checkpoint。

## pig_iron_tools 与必要输入

默认 `pig_iron_tools` 的 inputs 为 wood=30、iron=20，outputs 为 tools=60，但 workerChanges 为空；`hand_assembly` 也不增加职业需求。最终组合有商品产出而没有任何正职业需求，违反本轮新规则，现已在最终配方解析时拒绝。未擅自添加正式岗位。

默认 `crude_tools + hand_assembly` 的 laborer=10、machinist=4 合法，不影响构建或默认定义装配。原先借 pig_iron_tools 模拟岗位收缩的恢复测试改用独立测试 PM `reduced_tools`，其需求在测试夹具中明确声明；没有把这些测试人数填入正式数据。

启用 pig_iron_tools 仍需要用户提供正式的职业及正整数岗位需求（或明确的合法最终 PM 组合）。这不阻止本轮交付，但该 PM 在补齐数据前不可选用。

## 测试与故障证据

全部文件 SQLite 测试使用 JUnit 临时目录，未打开真实游戏世界数据库。

| 测试类 | 数量 | 主要覆盖 |
|---|---:|---|
| InventoryAndEquipmentTest | 7 | 整数排他预留、两种全量结算、容量、数量及总和溢出、失败不部分修改；保留原设备测试。 |
| SqliteInventoryConcurrencyTest（新增） | 14 | 真实双连接写锁时序、并发预留和入库、完成/中止竞争、CAS、revision 溢出、commit/rollback/cleanup 及句柄隔离故障。 |
| SqliteInventoryContractTest | 5 | 实际持久化及触发器拒绝、回执重启、完整命令冲突、幂等重放不重写、v3 字面量合同。 |
| BoundaryRecoveryTest | 10 | METHODS / EQUIPMENT / WORKFORCE 边界的恢复及独立保存失败，按新 tick 调整。 |
| BuildingProductionIsolationTest | 5 | 建筑与两个生产聚合的独立性，保留。 |
| ProductionIntegrationTest | 19 | 全速 200 tick、冻结快照、暂停/恢复、中止全退零产、资源保护及自动批次行为。 |
| RecoveryAndControlsTest | 13 | 保留既有恢复/控制测试；新增完成/中止结算回执丢失后的持久 SETTLING 和重启恢复两例。 |
| StartCommitRecoveryTest | 11 | 启动各阶段失败、回执丢失、StartIntent 持久化失败及资源补偿，保留。 |
| WorkforceContractTest | 3 | 原有员工/资格端口保护，保留。 |
| ProductionDomainTest | 17 | 有符号 PM、零项消除、非法最终配方、精确溢出、200 tick、3/5 第 334 tick、10% 边界及 pig_iron_tools 拒绝。 |
| ProductionRepositoryTest | 11 | 原有 CAS、真实提交回执失败和权威恢复；夹具改为整数及 200 tick。 |
| EconomyUnitOfWorkTest | 29 | M1.1 全部回归；仅商品夹具换成 Long。 |
| SchemaCompatibilityTest | 77 | 原兼容安全场景继续适用，新 v3 读写/200 tick、旧 v2 四种未完成状态拒绝、严格数值合同、v1/v2/未知版本和主文件/WAL 字节保留。 |
| SqliteTransactionsTest | 26 | M1.0 原故障测试全部保留，源文件未改。 |
| **合计** | **247** | **0 failures、0 errors、0 skipped；14 个测试类。** |

具体双连接和故障注入结果：

- DELETE 和 WAL 两种 journal 模式均在库存首次 SELECT 前用第二连接尝试真实 UPDATE，均得到 SQLITE_BUSY（code 5），证明读前已经获得写权限。
- WAL 下两个独立库存实例并发预留 10 单位库存中的 7 单位，只有一个成功；并发各入库 2、3 单位，最终由 10 变为 15，revision 正确增加，无丢失更新。
- 完成和中止同时竞争同一 BatchId，只持久一个结果。之后改变 outcome 或冻结物料的重放均拒绝，旧结果不被覆盖。
- 在 SELECT 与 CAS UPDATE 之间注入同事务内真实 revision 变化，CAS 失败后整笔回滚；另一连接仍看到原数量和 revision。
- 分别在真正 commit 前、真正 commit 后注入 SQLException：只调用一次 commit、不返回回执；重开连接读取并重放原命令，最终只出现一次完整结算。
- 成功提交后的清理失败不返回回执，连接隔离；rollback 与 close 同时失败时，缓存 Statement 也不能继续使用，未确认工作不会被 reset 提交。
- 服务在第 199 tick 后丢失完成/中止回执，Execution 保持 SETTLING；重启恢复原 BatchId 后只保留一个结果。中止木材仍为 100、tools 为 0；完成木材为 70、tools 为 30。
- 独立库存 committed read / write 在本地事务未解决时拒绝，不将事务内对象当作已提交事实。
- 独立 v2 DDL + 原始小数库存/1200 tick Batch 夹具、以及含新 WAL 写入的 v2 文件均被拒绝；断言拒绝前后主文件及 WAL 字节相同。伪装成 v3 布局的原始 v2 JSON 也被拒绝。
- `9007199254740993`（超过 Double 精确整数范围）的库存经过实际 SQLite 保存及重启仍逐值精确相等。

历史旧比例结算及 1200 tick 断言已改写，没有整类删除幂等、故障或恢复测试。由基线 205 项增加到 247 项（+42）。

执行用户指定命令：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：`BUILD SUCCESSFUL in 34s`；9 个任务全部执行。JUnit XML 汇总为 247/0/0/0。`git diff --check` 通过。此前依赖工具已有的 Gradle 10 弃用提示不影响本次构建，未为本轮升级构建依赖。

真实世界 `run/saves/新的世界/victoria_economics/economy.sqlite` 仅计算文件哈希，没有建立 SQLite 连接或执行写入。开始与结束 SHA-256 均为：

```text
3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8
```

## 完整修改文件清单

生产源码共 15 个文件（14 修改、1 新增），以下以 `src/main/java/com/bbmurloc/victoriaeconomics/` 为根：

| 文件 | 原因 |
|---|---|
| common/definition/production/ProductionMethodDefinition.java | 商品变化量改为 Long。 |
| common/definition/DefaultDefinitions.java | 正式商品字面量改为 long，不补造 pig_iron_tools 岗位。 |
| common/definition/DefinitionValidator.java | 商品引用验证接收 Long 映射。 |
| server/production/domain/ProductionMethodRules.java | 有符号整数合并、精确加法、最终零项消除。 |
| server/production/domain/ResolvedProductionRecipe.java | 正整数物料及正职业需求不变量。 |
| server/production/domain/ProductionBatch.java | 满速基础时长 200 tick。 |
| server/production/domain/WorkforcePlanningService.java | 明确拒绝无正职业需求的生产。 |
| server/production/domain/ProductionSettlement.java（新增） | 冻结且可核对的 COMPLETED / ABORTED 命令。 |
| server/production/port/ProductionInventoryPort.java | Long 物料接口和完整命令/回执，移除比例结算。 |
| server/production/application/ProductionService.java | 从持久 SETTLING 状态构造并核对结算命令。 |
| server/inventory/domain/GoodsInventory.java | 整数库存、容量、排他预留、精确运算、全量幂等结算。 |
| server/inventory/infrastructure/SqliteInventoryStore.java | IMMEDIATE 读改写、地点 revision/CAS、提交后回执。 |
| server/storage/sqlite/EconomySchema.java | 新建 v3 布局和库存 revision；复用原严格门禁。 |
| server/storage/sqlite/EconomyStateCodec.java | v3 固定 envelope、整数物料和 typed 回执，拒绝 v2 及数值强转。 |
| server/command/ProductionCommands.java | 商品和库存容量参数改为 long。 |

测试共 12 个文件（11 修改、1 新增），以下以 `src/test/java/com/bbmurloc/victoriaeconomics/server/` 为根：

| 文件 | 原因 |
|---|---|
| inventory/domain/InventoryAndEquipmentTest.java | 改写库存结算规则，增加整数溢出，保留设备测试。 |
| inventory/infrastructure/SqliteInventoryContractTest.java | 新端口及 v3 字面量、真实保存失败和重启回执。 |
| inventory/infrastructure/SqliteInventoryConcurrencyTest.java（新增） | 双连接写锁/CAS/并发与 JDBC 故障注入。 |
| production/domain/ProductionDomainTest.java | 整数配方、速度边界、无岗位拒绝等领域测试。 |
| production/application/TestEconomy.java | 整数库存/新端口/故障夹具，独立 test-only 收缩 PM。 |
| production/application/ProductionIntegrationTest.java | 200 tick 和完整中止语义，保留业务保护。 |
| production/application/BoundaryRecoveryTest.java | 新时长及测试 PM，保留边界故障覆盖。 |
| production/application/RecoveryAndControlsTest.java | 新时长/全额中止，新增两种结算回执丢失重启。 |
| production/application/StartCommitRecoveryTest.java | 新时长/全额中止，保留 StartIntent 故障场景。 |
| production/infrastructure/ProductionRepositoryTest.java | 整数及 200 tick 夹具，保留实际 JDBC 故障测试。 |
| storage/sqlite/EconomyUnitOfWorkTest.java | 仅改商品 Long 夹具，29 项保护保留。 |
| storage/sqlite/SchemaCompatibilityTest.java | v3 合同、原 v2 拒绝及 DB/WAL 字节保留。 |

另新增：

- `src/test/resources/fixtures/sqlite/schema-v3.sql`：独立的新格式 DDL 夹具；原 schema-v2.sql 未改。
- `docs/design/VE-M1.3-整数批次与Schema-v3-实现与验证-2026-10-20.md`：本报告。

合计 29 个文件。未修改 ProductionBatchConfiguration、ProductionExecution、Journal / StartIntent、事务 helper、连接 Guard、PM/Execution UoW 与仓储实现、真实世界数据库。

## 未解决项与 M1.4 前置条件

没有本轮验收失败或已知破坏事务一致性的未解决问题。以下限制保留并需后续阶段遵守：

1. pig_iron_tools 仍缺正式岗位数据，启用前必须补齐；当前按规则拒绝。
2. 正式 Payroll 依赖继续 fail-closed；本轮业务测试的 CURRENT 是显式测试事实，未伪造正式工资系统。
3. M1.4 若移除 Batch 的设备字段、设备表或改变启动/结算快照语义，必须同步管理新的 Schema / Codec 合同（例如后续版本）；不能在 v3 名义下悄悄删除字段或重新解释历史批次。M3.1 用汇总人数替代 NPC UUID 时同样适用。继续遵循新世界、拒绝旧格式、不自动迁移的已授权策略。
4. 长整数及总量存在可表示上限。达到上限时显式失败且保留 SETTLING / Reservation 恢复事实，不能借“已承诺产出”静默溢出。容量超限和数值溢出是不同的规则。
5. 本轮覆盖领域、应用及真实 SQLite 集成测试，没有启动游戏对真实世界进行端到端写入验证。

M1.3 到此停止，等待下一轮源码审查；未进入 M1.4。
