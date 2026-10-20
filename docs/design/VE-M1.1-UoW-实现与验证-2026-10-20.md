# M1.1：显式共享 Unit of Work 与事务内仓储读取

日期：2026-10-20。状态：实现及本地验收完成，等待代码审查；未 commit / push。

## 基线与范围

- 开始时工作区干净，分支为 `refactor/m1-0-sqlite-transaction-cleanup`，本地 HEAD 与远程跟踪引用均为 `ec1977f7f1a05051154303af6d00bda478c7fa38`。本轮没有切换到 main，也没有修改 HEAD。
- 已阅读 M0 审查报告及 Production / Labor / Payroll DDD 草案，以 M0 的 M1.1 工作包为边界。
- 本轮提供事务基础接口，按 M0 要求先不迁移 ProductionService 的业务流程。保留生产双聚合、双仓储、各自 CAS 和现有 Batch 规则。
- Schema 仍为 v2；未修改 Inventory、设备、NPC、工资、地区、所有权或三阶段屏障，也未实施 M1.2。

## 文件及职责

以下路径以 `src/main/java/com/bbmurloc/victoriaeconomics/server/` 为前缀：

| 文件 | 改动原因 |
|---|---|
| `storage/EconomyUnitOfWork.java` | 新增应用层写事务接口，明确 detached 对象及提交发布契约 |
| `storage/sqlite/SqliteEconomyUnitOfWork.java` | 同一连接、同一经济锁内管理两种仓储会话、加载对象身份、保存队列和提交后确认 |
| `production/port/ProductionMethodConfigurationRepository.java` | 新增显式事务内 Session 契约，保留普通 committed load |
| `production/port/ProductionExecutionRepository.java` | 同上，明确 Session.save 不表示提交完成 |
| `production/infrastructure/SqliteProductionMethodConfigurationRepository.java` | 复用同一 SQL 实现，分别提供 committed 与受能力凭证保护的事务内读取/保存 |
| `production/infrastructure/SqliteProductionExecutionRepository.java` | 同上；事务内保存不提前 acknowledgeCommit，普通 save 拒绝隐式加入外层事务 |
| `storage/sqlite/SqliteTransactions.java` | 新增 IMMEDIATE 写事务入口和有作用域、绑定连接/线程的 WriteSession；原 run 的故障清理保持原样 |
| `storage/sqlite/SqliteConnectionGuard.java` | 内部保存已登记 Xerial 连接的事务配置，以受控方式选择事务模式；不暴露原始 JDBC 连接 |

另新增 `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyUnitOfWorkTest.java`（29 项真实文件 SQLite 测试）及本报告。现有 M1.0 的 26 项测试未修改。

## 最终接口与会话规则

```java
public interface EconomyUnitOfWork {
    void write(Work work);
    interface Work { void run(Session session); }
    interface Session {
        ProductionMethodConfigurationRepository.Session methods();
        ProductionExecutionRepository.Session executions();
    }
}
```

两个仓储的 `Session` 均保留各自类型的 `load(UUID)`、`save(root, expectedRevision)`。SQLite 实现通过 `SqliteEconomyUnitOfWork(connection, recipes, economyLock)` 构造，内部自行绑定两个仓储，不能拼入另一个连接的仓储。

规则：

1. `write` 取得 SQLite 写权限后，才调用应用回调及加载业务权威。
2. 会话内同一根重复加载返回同一 detached 对象，只接受本会话加载的对象保存；每根每次 UoW 最多登记一次保存。
3. `save` 登记回调结束时的最终对象状态。回调正常返回后，在原事务内先 flush PM，再 flush Execution / Batch，最后只执行一次外层物理 commit。
4. flush 在应用回调之外执行，第二次写入失败不能被回调捕获后继续提交第一次写入。会话加载/保存操作失败后，即使异常被回调捕获，整个会话仍拒绝提交。
5. 会话和仓储会话只能在当前线程、当前回调作用域内使用。WriteSession 的私有构造器、连接身份校验及关闭检查防止伪造、跨连接和过期访问；没有 ThreadLocal 或全局事务开关。
6. 不在回调内发布缓存或把工作 revision 当作提交回执；只有整个 `write` 正常返回才能发布已保存根。未登记保存的本地编辑会恢复检查点。
7. 任意失败后丢弃全部会话对象，重新读取 committed facts；不自动重跑回调或重试 commit。

普通仓储 `load` 继续执行 `requireCommittedReads`；普通 `save` 也要求独立、已解决的连接状态。需要共享事务时必须使用会话，不能借普通 save 的内层 Savepoint 提前确认 revision。原初始化所需的嵌套 create 保持原样。

## SQLite 写权限与驱动依据

实际依赖和测试驱动均为 `sqlite-jdbc 3.46.1.0`，未升级依赖。核对了本地 sources.jar，以及 [Xerial 对应版本的 SQLiteConnection 源码](https://github.com/xerial/sqlite-jdbc/blob/3.46.1.0/src/main/java/org/sqlite/SQLiteConnection.java)与 [SQLite 官方事务说明](https://www.sqlite.org/lang_transaction.html)。

该驱动的 `setAutoCommit(false)` 根据连接的 TransactionMode 发出 BEGIN；默认 DEFERRED。`commit()` / `rollback()` 在执行对应语句后会按连接配置再次 BEGIN。因此采用以下最小控制：

1. 写入口先检查连接可信且 autoCommit=true，拒绝嵌套或未解决事务。
2. 仅将本次 JDBC begin 的配置设为 IMMEDIATE，然后复用原 `SqliteTransactions.run` 调用 `setAutoCommit(false)`。没有手写 SQL BEGIN。
3. JDBC begin 成功后、业务回调开始前，将驱动的后续 BEGIN 配置改为 DEFERRED。已经开启的实际事务仍持有 IMMEDIATE 写权限；该配置只防止 commit / rollback 自动开启的空事务再次争抢写锁。
4. 原 helper 确认 commit / rollback 并完成 JDBC cleanup 后，恢复调用前的事务模式。异常隔离后的配置恢复只是内存属性赋值，不做 JDBC reset 或 SQL，不可能提交失败工作。

Guard 只在直接登记 Xerial 连接时保存其配置；正式 EconomyDatabase 已在物理连接创建时调用 protect。没有新增 unwrap 路径，`unwrap(SQLiteConnection.class)` 仍被拒绝，连接及所有派生句柄的隔离规则保持原样。无法取得已登记驱动配置时明确拒绝写入口，不降级到 DEFERRED。

普通 committed load 继续使用原读法；没有把整个连接永久切为 IMMEDIATE。双连接测试证明，另一个连接已经持有 RESERVED 写权限时，普通 PM / Execution committed load 仍能读取旧的已提交状态。

## revision 与发布策略

- Execution 的事务内 SQL 仍使用 CAS 写入 `expectedRevision + 1`，但不调用 `acknowledgeCommit`。只有外层物理 commit 及 JDBC cleanup 均成功后，UoW 才确认对象 revision。
- PM 保留领域命令递增 configuration/effective revision 的规则；该数值在回调期间只是工作版本，不是提交回执。两个根的加载检查点均覆盖本地状态和 revision。
- 回调异常、第二根写失败、CAS 冲突或未知提交时，UoW 恢复本地检查点，随后抛出原失败并要求丢弃对象。恢复本地对象不表示数据库已经回滚。
- 特别是“实际 commit 成功后丢失回执”：数据库可已经保存两个新版本，本地对象仍不获确认；独立 committed load 才能确认实际结果。测试同时覆盖实际 commit 前失败与实际 commit 后失败，均只调用一次 commit，不自动再次提交。
- 本轮不新增 Registry，也不把事务内根写入现有缓存。跨根业务检查必须使用同一会话；两个相互独立的 committed load 不承诺跨调用的共同快照。

## M1.0 保护及故障结果

原 `SqliteTransactions.run` 的清理路径没有重写，写入口复用相同的状态处理：

- rollback 失败：隔离并尝试关闭连接，禁止 setAutoCommit(true) 造成意外提交。
- close 同时失败：隔离仍是粘性的，旧连接及缓存 Statement 等派生句柄拒绝继续使用。
- commit 回执未知：保守抛出 CommitOutcomeUnknownException；不猜成功或失败，不重试回调/commit。
- 内层 Savepoint 的 release 及 rollback 均失败：即使回调捕获异常，外层 UoW 也不能提交。
- JDBC begin 失败：保留 M1.0 的保守隔离，不让应用回调进入或继续读取权威；需要新可信连接核验及显式重试。

双连接、真实文件 SQLite 验证包括：

| 场景 | 结果 |
|---|---|
| 默认 DEFERRED 仅 setAutoCommit(false)，尚未读写 | 第二连接能写，证明其不等于 IMMEDIATE |
| DELETE / WAL 两种 journal 模式，进入 UoW 后尚未加载任何根 | 第二连接 UPDATE 返回 SQLITE_BUSY，证明写权限先于业务读取 |
| 竞争者已经持有写权限 | UoW 无法 begin 时不进入回调、不进行业务读取，并隔离未确认连接 |
| WAL 中等待另一个写者提交 | 获得权限后的会话读到该写者最新提交的两个根版本 |
| 第一个 PM UPDATE 后、第二根 UPDATE 前 | 独立观察连接仍看到完整旧状态；外层成功后看到完整新状态 |
| 第二根触发器失败 / 第二根 CAS 失败 / Batch INSERT 失败 | PM、Execution、Batch 的数据库改动整体回滚，本地 revision 不提前确认 |
| 实际 commit 返回前、JDBC cleanup 之前 | 另一连接已经能写，证明驱动没有重新取得 IMMEDIATE 写锁；本地 Execution 尚未确认 |
| 实际 commit 前失败 / commit 后回执丢失 | 两种结果均报告未知，只 commit 一次，通过 committed load 核验实际持久状态 |
| 已提交后的 cleanup 失败 | 不发布 revision，隔离原连接，由独立连接核验两个根已提交 |
| 第二根 SQL 失败且 rollback / close 同时失败 | 失败工作没有被 cleanup 提交；派生句柄及后续 UoW 均拒绝访问 |

等待最新提交的测试使用 WAL；默认 DELETE 模式单独验证抢锁、冲突拒绝、普通读取和原子可见性。没有修改正式数据库的 journal 配置；SQLite 竞争仍可能产生 SQLITE_BUSY，接口不承诺透明重试。

## 完整验收

执行用户指定命令：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：BUILD SUCCESSFUL，退出码 0，耗时 2m 8s，8 个任务全部执行。

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
| EconomyUnitOfWorkTest（新增） | 29 | 0 / 0 / 0 |
| **总计** | **149** | **0 / 0 / 0** |

[JUnit 报告](../../build/reports/tests/test/index.html)。产物为 `build/libs/victoria_economics-0.1.1-1.21.1.jar`。全部测试为纯 JVM / 临时文件 SQLite 测试，未启动 Minecraft 或真实世界。

`run/saves/新的世界/victoria_economics/economy.sqlite` 的本轮检查前后 SHA-256 相同，均为 `3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`。未调用真实世界数据库初始化、迁移或写入。

没有发现阻止 M1.1 验收的未解决一致性问题。接口要求调用者使用共享 economyLock、遵守 detached 对象发布规则，并在未知结果后重读；驱动升级时应重新核验 BEGIN / commit / rollback 行为。现有 Gradle 弃用提示保留。M1.2 及后续工作未开始，等待本轮审查。
