# 任务 E：M5.1 / M5.2 / M5.3 建筑生命周期与整体验收

日期：2026-10-21。**安全基础、受控停产及验证已实施；最终出售和拆除交割因未决规则保持 BLOCKED。本报告不宣布整体重构完全验收，也不建议直接合并 main。**

## 1. 真实基线与实施边界

- 分支：`refactor/m1-0-sqlite-transaction-cleanup`。
- 实际起点 HEAD：`72f0a46eb6e1046edc30ff2ca7ccea820edb1e73`，与任务 D 参考提交一致；开始工作区干净。
- 已完整阅读 M0 计划、Production / Labor / Payroll DDD 草案、任务 A/B/C/D 报告。采用已确认规则，先建立 [E0 决策矩阵 / ADR-LIFECYCLE-01](../architecture/adr/ADR-LIFECYCLE-01.md)，并询问缺失决策；没有得到授权的规则保持未决。
- 没有 commit、push、merge、reset、stash、切换分支或自动暂存。任务 A～D 报告、DDD 草案及历史 schema-v2～v8.sql 保持原样。
- 所有数据库行为验证使用 JUnit 临时数据库或 `build/task-e-gametest*` 隔离 Minecraft 世界，没有通过 SQLite 打开用户真实存档。
- `run/saves/新的世界/victoria_economics/economy.sqlite` 仅核对 SHA-256，开始及结束均为 `3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`。开始及结束未发现该文件的 WAL。

| 阶段 | 实际完成内容 | 明确未完成 |
|---|---|---|
| E0 | 源码权威审查、决策矩阵、只读 Assessment、未决政策门禁。 | 未替用户决定 PM、余数、资产范围或价格。 |
| E1 | 独立持久操作、完整命令身份、同建筑排他、单向阶段、开工门禁、资源恢复与可信核验。 | 没有绕过决策的 force/approve/cancel/清记录入口。 |
| E2 | 出售准备：关自动、持久中止、全额退料、保持旧公司工资及人数、持久 BLOCKED。 | 所有权交割、卖方工资关闭、新公司段及对价支付未执行。 |
| E3 | 拆除准备与同样的恢复协议；墓碑格式不变量、不可复用身份保护。 | 没有正式释放员工、关闭工资或生成 DEMOLISHED 的应用入口。 |
| E4 | Schema / Codec v9、生命周期和墓碑图预检、旧格式及 WAL 保护。 | 不迁移任何旧世界，不把 BLOCKED 解释成完成。 |
| E5 | 删除无调用 Registry.remove；清理过时源码说明，标注历史文档 Superseded，增加当前阅读地图和 ADR。 | 不删除有职责的 Journal、Reservation、账本、债务、兼容性测试或测试用 UoW。 |
| E6 | JUnit、真实 SQLite、两连接、故障注入、隔离 NeoForge 全链及进程级重启验证。 | 最终交割、真实玩家多客户端、大规模长期整服容量未验收。 |

## 2. E0 决策矩阵

| 项目 | 已确认 / 严格推导 | 尚未决定或真实源码事实 | 本轮行为 |
|---|---|---|---|
| 出售 Pending PM | Batch 冻结不变；最终清卖方 Pending Staffing。 | PM 是否继承、取消或买方复核未定。 | 保留有效及 Pending PM；存在时记录 PENDING_PM_POLICY_UNDECIDED。 |
| 最终工资余数 | 必须归原公司保留，不丢失、不转嫁。 | 最终如何汇总、到期和支付未定。 | WAGE_REMAINDER_POLICY_UNDECIDED；不关闭段，持续按原人数计薪。余数恰为 0 也不视为政策已获批准。 |
| 库存随售 / 拆除处置 | Batch 中止必须全退零产，库存是独立权威。 | 是否随售、退料产权及拆除后的商品处置未定。 | 不兑换、不转移、不销毁；记录处置门禁及非空事实。 |
| 出售资金对价 | 真实现金只有 Finance；不能造钱。 | 是否有价、金额、币种、付款与交割条件未定。 | SALE_CONSIDERATION_UNDECIDED；Command 没有价格字段不代表价格为 0。 |
| 地点产权 | GoodsInventory 保存数量、容量、Reservation、结算及 CAS。 | inventory_locations 没有 owner 或产权凭据。 | 地点 UUID 等于 BuildingId 不能证明归属，记录 INVENTORY_OWNERSHIP_UNDEFINED。 |
| 操作存储 | 用户本轮授权稳定操作及可靠恢复。 | 具体实现可以选择最小单表。 | building_lifecycle_operations，复用 WriteSession，不引入 Saga。 |
| 已决定完成的 Batch | M1.3 的完整结算命令不能改变，SETTLING_COMPLETED 不可改为 ABORTED。 | 不是可重新分配结算结果的业务选择。 | 按原 COMPLETED 结算后阻塞交割；ACTIVE/PAUSED 则持久决定 ABORTED。 |

出售和拆除的最终生效均未开放。没有通过“空库存”“没有 Pending”或“余数为 0”猜测其他缺失政策；缺失规则不妨碍安全准备和其他领域继续运行。

## 3. 生命周期状态、OperationId 与只读规划

`BuildingLifecycleOperation` 是独立不可变操作记录，状态为 `QUIESCING → BLOCKED`，revision 为 0→1。当前没有代表最终交割的 COMPLETED 状态；增加最终合同和状态时需要相应版本演进。

完整 Command 包含 OperationId、BuildingId、原 CompanyId、SALE 的真实买方或 DEMOLITION 的无买方、操作类型及 expectedIdentityRevision。买方必须已登记、不同于卖方；类型/参数不匹配、旧 owner 或旧 identity revision 均拒绝。相同 ID 完整命令相同则返回原事实，不再次终止、退料、转让或释放人数；不同内容拒绝。表的 UNIQUE(building_id) 保证每栋建筑最多一个未完成操作，当前全部状态都是未完成状态。

记录 startedTick、原 Payroll segment、当前 BatchId 及冻结结算结果匹配依据。blockedTick 是安全准备被确认的经济时刻，**不是交割或工资截止时刻**。操作不保存库存、人数、现金或有效 PM 的可写副本。阻塞原因是该次准备时观察到的门禁快照，不作为未来商品余额。

`BuildingLifecycleService.assess(command)` 使用一个 DEFERRED 已提交快照读取身份、PM、人数总计、工资来源及余数、Batch、库存和门禁，`finalizationAvailable()` 为 false。它不停止生产、不预留资源、不写操作、不表示批准价格或资产范围。普通 committed 查询仍拒绝未解决的本地事务。两连接测试证明 Assessment 不占用 IMMEDIATE 写锁。

BuildingStatus 的 ACTIVE/STOPPED/DISABLED 仍是运行控制；BuildingLifecycle 的 EXISTING/DEMOLISHED 表示身份存续；操作阶段另外存储，三者没有混用。

## 4. 真实事务、资源结算与恢复

### 4.1 开始事务

取得现有 sqlite-jdbc 3.46.1.0 的 IMMEDIATE 写权限后，重新读取操作、Building、Payroll 和 Execution，验证身份 CAS、买方目录及操作排他，然后在同一物理事务：

1. 保存 QUIESCING 操作及原工资/Batch 来源。
2. 关闭 automatic。
3. 对 ACTIVE/PAUSED 保存不可逆 SETTLING_ABORTED；已有 SETTLING/结束决定保持不变。
4. 保存 Execution 与 Batch；任一步失败整体回滚，既不残留孤立操作，也不产生未获意图保护的中止。

操作开始后，最终开工事务、PM、Staffing、自动启用及普通身份修改均重新检查持久意图。低层 PM/Staffing 仓储同样拒绝旁路；Execution 仓储还拒绝伪造另一个已经处于 SETTLING 的新 Batch，不能先在内存推进新 Batch 再绕过开工门禁。

### 4.2 原资源协议

生命周期使用 `ProductionService.settleForLifecycle`，先按原 Journal 事实恢复 StartIntent，再使用原冻结配方、BatchId 和 COMPLETED/ABORTED 完整命令执行库存结算。Batch 未提交才补偿 Reservation；已提交或结果未知不能误退资源。资源释放失败保留意图。

库存与 Execution 确认仍沿用原多事务幂等协议，没有削弱为一个内存回执。对 captured Batch 检查持久 Inventory settlement 与完整命令相等，才能继续；真实回执丢失后重启读取同一持久结果，不再次耗料、产出或退款。并发确认已经移入 lastBatch 的相同已结束批次是幂等的，其他批次回执仍拒绝。

### 4.3 屏障交接事务

资源结算确认、StartIntent 清理后，在同一 IMMEDIATE 事务重新读取所有相关事实：确认旧 owner/identity revision/Payroll segment 未变、automatic=false、没有未结算 Batch，并验证真实库存回执。将结束 Batch 移入历史、清除 RECONFIGURING，同时将操作推进为 BLOCKED。两个标记原子交接，生命周期意图继续阻止开工；不应用卖方 Pending、不裁员、不招聘、不关闭工资。

交接任一 SQL 写入失败，操作保持 QUIESCING，RECONFIGURING 仍存在。技术故障保留恢复事实并阻止越过；已确认 BLOCKED 的建筑能继续完成每 Tick 检查点，经济时间、工资应计和公司付款不会因未决政策冻结。

### 4.4 未知结果

意图、库存、交接或 JDBC 清理结果未知时，隔离旧连接及派生句柄，不在同一连接再次提交，不假定成功或失败。稳定 OperationId 随异常保留。`verify(command)` 或 `verify(operationId,buildingId)` 必须新建可信连接，经完整 Schema/业务图预检后读取事实；不自动发布 owner 或重做创建。重启只恢复 QUIESCING 的已确认阶段；BLOCKED 不被当作可交割。

事务内对象均为 detached 工作值，Savepoint 成功不代表外层成功。没有新增 ThreadLocal、全局事务开关、unguarded unwrap、ORM、事件总线或通用事务框架。

## 5. 出售 / 拆除尚未执行的最终步骤

出售最终需要在一个共享写事务中：工资计至实际交割 Tick、关闭卖方历史段、原公司余数处置、owner CAS、清卖方 Pending Staffing、保留实际人数与地区占用、建立买方起点、完成操作，并按已批准资产及资金合同交割。本轮**没有**把这些步骤拆开提交，也没有提供部分 owner 更新来冒充整体交割。SqliteBuildingRepository 仍拒绝普通 save 改 owner/region/lifecycle。

拆除最终需要库存清理、真实工资截止与余数政策、原子释放人数、关闭历史段及 DEMOLISHED 墓碑。本轮**没有**释放员工或制造正式墓碑；被 BLOCKED 的原公司继续承担全部工资和地区就业占用。

v9 格式预检支持独立历史墓碑：没有活跃 Payroll、员工、Pending、current Batch、自动开工、StartIntent、商品余额或 Reservation，保留历史身份、生产根、空 Staffing 根、工资历史和债务。它不强迫墓碑具有不存在的活跃工资段。墓碑不参与新的生产 Tick 检查点；旧 ID 不能再创建或招人。相关 SQL 夹具明确标注为历史格式验证，**不是实际拆除交割测试**。

## 6. 各领域唯一权威

| 领域 | 权威 | 生命周期阶段的处理 |
|---|---|---|
| Building | ID、类型、owner、region、存续/运行状态、Identity Revision。 | 准备期间身份不变；不物理 DELETE。 |
| Lifecycle Operation | 稳定命令、阶段、来源、门禁及 revision。 | 只协调安全准备，不成为资产账本。 |
| PM / Execution | 独立 PM 与 Batch/进度/屏障/暂停原因/检查点。 | 不应用 Pending；保存原结算决定及历史冻结快照。 |
| Inventory | 整数商品、容量、Reservation、完整结算、revision。 | 全额中止退款或原完成结果；不推定资产 owner。 |
| Staffing / Supply | 职业整数人数/Pending；地区总供给。 | BLOCKED 不释放或复制人数；occupied/available 仍从真实人数派生。 |
| Payroll | 原公司、建筑、地区、币种历史段，精度余数、应计/已到期/已付债务。 | 原活段保留、继续应计；旧债不丢、不转买方。 |
| Finance | 唯一现金、Reservation、TransferReceipt、Ledger。 | 不因操作记录产生资金；所有旧工资付款合同保留。 |
| Household | 居民部门身份和用途额度。 | 只在真实转账时增加现金，不因生命周期重复加预算。 |
| EconomicTime | accepted Tick、工资/付款游标及生产恢复依据。 | 不补离线；BLOCKED 不伪装全世界业务已完成。 |

## 7. M1 安全基础与元数据修复

SqliteTransactions 和 SqliteEconomyUnitOfWork 的事务算法未改。写权限先于权威读取、普通 committed load、显式会话、CAS、rollback 失败隔离、未知 commit、Savepoint 失败阻止外层提交等保护继续保留。PM 显式 Session.save 纳入既有 `WriteSession.perform`，使新的生命周期拒绝即使被 callback 捕获也不能提交先前修改。

实际 NeoForge 进程重启探针发现 Guard 的接口选择问题：Xerial 对 ResultSetMetaData 返回 ResultSet 自身，对 ParameterMetaData 返回 PreparedStatement 自身。旧包装按运行时类型优先选择 ResultSet/PreparedStatement，导致正常 `getMetaData()` 返回值发生 ClassCastException。

最小修复先按方法声明的两个 metadata 接口包装，保留 null 及其余句柄分支，继续共享原隔离状态、owner 和 unwrap 限制。没有暴露原连接或放宽提交判定。新增两项真实 JDBC 测试：metadata 接口和查询正常；旧 metadata、parameter metadata、ResultSet 在隔离后全部拒绝使用。原 M1.0 的 26 项故障测试保留，连同新增两项构成 28 项；UoW 的 29 项保留。

## 8. Schema / Codec v9

新增一张 building_lifecycle_operations，合计 34 张应用表（含版本表）及 2 个显式唯一索引；操作的 UNIQUE 还生成 SQLite 内部索引。具备原公司/买方/建筑/Payroll/Batch 复合归属 FK、类型/非负整数/状态-revision/可空字段配对 CHECK。没有可写的 occupied、available、库存 owner 默认值或第二现金余额。

Batch 和 Inventory 的当前 envelope 明确为 formatVersion=9；仍是整数商品、职业人数快照、200 Tick、完整结算合同，未修改业务比例。操作字段为轻量规范化 SQL，blockers 用排序且严格校验的枚举文本，没有通用序列化框架。

在原物理 READONLY 预检、无 CREATE 权限可写复检基础上增加 LifecycleValidation：来源公司/建筑/工资段/时间匹配、没有自动开工、Batch 决定一致、BLOCKED 有匹配持久结算及无 StartIntent、核心政策门禁、墓碑不变量。Financial / PaymentValidation 的已付来源、付款行、账本和债务核验均保留。

旧 v1～v8、未知版本、混合 DDL、旧 JSON 标签或伪造生命周期图均在业务写入前拒绝；不迁移、不清空、不修复。新增独立 schema-v9.sql；旧 v2～v8 字面量没有改写。拒绝路径逐字节核验主文件及 WAL，没有 checkpoint 旧 WAL。

## 9. 代码、命令与文档清理

主源码/测试扫描证明上游已删除的专用设备、NPC Employment、Qualification、JobReservation、pendingDismissals、旧三阶段及 HR 默认比例对象没有重返运行链。保留 Occupation 定义、普通机械商品、GoodsInventory、Journal、StartIntent、Batch Settlement、Finance Ledger、Payroll Liability、历史格式夹具与有恢复职责的适配器。删除的运行入口只有无调用的 BuildingRegistry.remove 方法；本轮没有整类删除有效保护。

更新旧 ADR-PO-02、overview、解耦审查、旧代码地图、搬包文档、历史任务单及 production-audit 的 Superseded 标注，保留历史原文。新增 [当前代码阅读地图](../domain/production-operations/current-runtime.md)、生命周期 ADR、[可复现性能方法](../testing/economy-performance.md)，同步 package-info 和中英文锚点资源。

`/ve` 查询权限为 2，修改为 4。新增 lifecycle status / sale / demolish / retry / verify；OperationId、seller、buyer、Identity Revision 均显式输入，返回的是准备阶段/BLOCKED。只读 Assessment 另有 Java 服务接口。create 增加显式 CompanyId / RegionId 参数，必须已有目录及可信工资配置；无参数旧入口仍拒绝，未代玩家分配公司。

建筑五根及工资起点的原子初始化保留。调试创建命令的库存地点配置仍为原有独立步骤，失败不撤销已经合法创建的五根，不能把整个管理命令称为库存联合原子创建；返回成功信息已经放到库存配置成功之后。

锚点只持有 BuildingId，NBT 正常保存/恢复，已有绑定不能经 setter 重绑成其他 ID；方块删除不触发经济拆除。真实玩家公司权限体系、默认资金/人口/工资率/币种仍未实现，没有从测试夹具接入正式注资。

## 10. 测试与故障矩阵

| 验收点 | 实际证据 |
|---|---|
| 纯领域 | BuildingLifecycleDomainTest：类型及买方、负 revision、单向阶段、时间/批次配对、不可变门禁。 |
| 受控准备 | BuildingLifecycleTest：运行、暂停、已决定完成/中止、空闲；全额退料与原完整决定；Pending、工资、人数及 owner 不被提前修改。 |
| 欠薪 | 真实 1200+ Tick、Finance 零付款产生实际欠薪，生命周期中止不丢余数/债务；BLOCKED 后工资继续。 |
| 正式准入 | 开工、自动启用、PM、Staffing、身份及低层仓储旁路拒绝；普通商品操作保留。 |
| 开工交错 | Reservation 已取得时进入生命周期，StartIntent 补偿并拒绝最后 Batch 提交，没有孤立预留。 |
| 原子故障 | intent INSERT、Batch 中止 UPDATE、结束批次屏障交接、BLOCKED UPDATE 分别注入 SQL 失败；各物理阶段整体回滚。 |
| 未知提交 | 开始及交接 commit 前/后丢回执，成功 commit 后 reset 失败；新可信连接核验完整旧图或新图，不重退。 |
| 库存回执 | 真实退款保存后回执丢失，旧连接隔离；重启读取原 settlement，仅一次退款。 |
| M1 清理 | rollback+close 失败时无 finally commit/reset；本地未解决事务 committed read 拒绝；捕获仓储错误仍阻止外层提交。 |
| 两连接 | 同完整命令仅一个意图/退款；两个 OperationId 竞争同建筑排他；另一连接不能偷改人数；DELETE/WAL 在第一次权威 SELECT 前实际观察 SQLITE_BUSY(5)。 |
| 只读规划 | DEFERRED 一致快照；另一连接可以取得写权限；不停止当前 Batch 或创建操作。 |
| 格式防伪 | 生命周期自动开工、错公司、未来 Tick、缺政策、未知原因、错结算、假 ACTIVE、交接不完整等均拒绝，主文件/WAL 不变。 |
| 墓碑 | 独立历史夹具：无活跃 Payroll 正常，时间继续、ID 不复用；员工/库存/自动/StartIntent/活 Payroll 不一致拒绝。非正式拆除。 |
| 全部回归 | Staffing 唯一权威、RECONFIGURING、200/334 Tick、整数与全额完成/中止、预留/CAS、Journal/StartIntent、工资余数、付款守恒、预算、时间检查点、身份 CAS。 |

### 最终 JUnit 与构建

执行 `./gradlew.bat test build --rerun-tasks --console=plain`：**BUILD SUCCESSFUL in 8m12s**，11 个任务全部实际执行。最终 XML 为 **42 类、598 项，0 failures、0 errors、0 skipped**。相对任务 D 的 539 项净增 59：五个新生命周期类 49 项、旧 Schema 兼容 8 项、Guard metadata 2 项。没有删除旧测试类或降低仍有效的恢复断言。

| 测试类 | 数量 | 失败 / 错误 / 跳过 |
|---|---:|---|
| BuildingIdentityPersistenceTest | 12 | 0 / 0 / 0 |
| BuildingLifecycleDomainTest | 3 | 0 / 0 / 0 |
| EconomicTimePersistenceTest | 8 | 0 / 0 / 0 |
| FinancePersistenceTest | 14 | 0 / 0 / 0 |
| FinancialCompatibilityTest | 14 | 0 / 0 / 0 |
| FinancialTransactionSafetyTest | 10 | 0 / 0 / 0 |
| PayrollAccrualDomainTest | 8 | 0 / 0 / 0 |
| PayrollPersistenceTest | 26 | 0 / 0 / 0 |
| HouseholdBudgetConcurrencyTest | 8 | 0 / 0 / 0 |
| HouseholdBudgetPersistenceTest | 6 | 0 / 0 / 0 |
| HouseholdBudgetTest | 4 | 0 / 0 / 0 |
| GoodsInventoryTest | 4 | 0 / 0 / 0 |
| SqliteInventoryConcurrencyTest | 14 | 0 / 0 / 0 |
| SqliteInventoryContractTest | 5 | 0 / 0 / 0 |
| CompanyPayrollPaymentTest | 16 | 0 / 0 / 0 |
| CompanyPayrollRecoveryTest | 19 | 0 / 0 / 0 |
| PaymentCompatibilityTest | 19 | 0 / 0 / 0 |
| PaymentConcurrencyTest | 5 | 0 / 0 / 0 |
| PaymentDomainTest | 8 | 0 / 0 / 0 |
| BoundaryRecoveryTest | 14 | 0 / 0 / 0 |
| BuildingLifecycleCompatibilityTest | 15 | 0 / 0 / 0 |
| BuildingLifecycleConcurrencyTest | 6 | 0 / 0 / 0 |
| BuildingLifecycleRecoveryTest | 12 | 0 / 0 / 0 |
| BuildingLifecycleTest | 13 | 0 / 0 / 0 |
| BuildingProductionIsolationTest | 5 | 0 / 0 / 0 |
| PayrollPaymentPauseIntegrationTest | 2 | 0 / 0 / 0 |
| ProductionConcurrencyTest | 8 | 0 / 0 / 0 |
| ProductionIntegrationTest | 19 | 0 / 0 / 0 |
| ProductionSchemaContractTest | 13 | 0 / 0 / 0 |
| ReconfigurationTransactionSafetyTest | 4 | 0 / 0 / 0 |
| RecoveryAndControlsTest | 13 | 0 / 0 / 0 |
| StaffingQueryTest | 4 | 0 / 0 / 0 |
| StartCommitRecoveryTest | 11 | 0 / 0 / 0 |
| WorkforceContractTest | 4 | 0 / 0 / 0 |
| ProductionDomainTest | 17 | 0 / 0 / 0 |
| ProductionRepositoryTest | 11 | 0 / 0 / 0 |
| EconomyUnitOfWorkTest | 29 | 0 / 0 / 0 |
| SchemaCompatibilityTest | 133 | 0 / 0 / 0 |
| SqliteTransactionsTest | 28 | 0 / 0 / 0 |
| BuildingStaffingTest | 9 | 0 / 0 / 0 |
| LaborTransactionSafetyTest | 17 | 0 / 0 / 0 |
| StaffingRepositoryTest | 8 | 0 / 0 / 0 |
| **合计（42 类）** | **598** | **0 / 0 / 0** |

完整输出：`build/task-e-validation.log`；JUnit XML：`build/test-results/test`；HTML：`build/reports/tests/test/index.html`。最终测试和构建结果按上述实际输出填入，没有用删除保护测试或降低断言获得通过。历史旧版本拒绝另有新增 8 项。

### 隔离 Minecraft / NeoForge

Minecraft 1.21.1、NeoForge 21.1.248、ModDevGradle 2.0.146、Java 21.0.12。独立 src/gameTest 测试 Mod，普通 client/server run 只加载 main；check 只编译测试源，不自动启动游戏。测试身份、资金和两个结构模板不进入 mod jar。

1. 完整 GameTest 方法在默认、review、final 三个独立隔离世界分别通过，final 使用最终源码：真实管理员创建/生产/生命周期命令、低权限拒绝、锚点 NBT 重载/禁止重绑、方块销毁不删经济身份、远处锚点区块真实卸载后生产继续。
2. 真实库存 SQL 故障令出售准备保留 QUIESCING；清除隔离故障后重建 Context，按原意图全退零产并 BLOCKED；拆除准备、相同命令和冲突重放同样经过真实 Brigadier 入口。两个最终交割均未被冒称完成。
3. 满速连续批次逐 Tick 检查没有空档或双推进；真实工资周期由 ServerTickEvent 推进。无资金慢批真实欠薪暂停；显式可核验测试资金在下一周期真实扣款、地区现金到账、债务减少，MANUAL 保持，显式解除后恢复原 Batch/进度/Reservation。
4. 在同一 review 隔离世界退出整服后，以新的 JVM 运行 restart GameTest：正式 Context 初始化前后 accepted Tick **2403**、全部 Batch 进度/状态、现金、应计/已付/余数/债务、库存和操作状态一致；之后持久 Tick 增量等于实际逻辑回调数。没有停服墙钟补算。该方法 1 项通过，整服正常保存并退出。

最终源码完整场景日志：`build/task-e-gametest-final.log`（All 1 required tests passed，Gradle 2m39s）；独立 review 复测为 3m16s；进程重启日志：`build/task-e-process-restart.log`（All 1 required tests passed，Gradle 31s）。两种不同 GameTest 方法均有真实运行证据，不能与 JUnit 数量相加。

开发中曾遇到测试 namespace 未发现函数，以及上述 Guard metadata 类型问题；均已修复并重新实际验证。增加每次启动重置的 assertion receipt：无测试或未完成断言时，即使 Minecraft 返回进程 0，Gradle 也必须失败，不能把零退出码当成通过。

最终完整 run 的断言回执为 `build/task-e-gametest-final/acceptance-result.txt`，内容 PASSED full；进程重启对应回执为 PASSED restart。新隔离目录的 Minecraft 启动日志有首次 server.properties 缺失/default 创建及一次 Mojang 公钥 TLS 请求失败；没有导致 GameTest 失败，但不能据此声称在线玩家认证已验收。

构建后检查 `build/libs/victoria_economics-0.1.1-1.21.1.jar`：生命周期服务/操作/验证器和 Guard 已打包；GameTest Mod、两个结构、JUnit/JDBC 故障及资金夹具、SQL fixtures 条目为 0。中英文 JSON 正常解析，Git diff whitespace 检查通过；没有更改驱动版本或 SQLite 耐久性。

## 11. 实际整服性能与未测范围

最终独立新世界采样（没有与 JUnit 并行）：四栋建筑、三套显式公司/地区/币种、两栋生命周期阻塞但继续计薪、一栋满速连续生产、一栋欠薪后付款恢复；2352 个实际 Tick 样本，到 accepted Tick 2402。

| 项目 | 实际值 |
|---|---:|
| 平均 ServerTickEvent.Pre → Post | 55.429 ms |
| p50 | 50.474 ms |
| p95 | 91.176 ms |
| 最大 | 261.433 ms |
| SQLite | journal_mode=DELETE，synchronous=2（FULL） |

JSON 为 `build/task-e-gametest-final/acceptance-performance.json`。此前同样独立的 review 样本有 2345 Tick，平均 65.473ms、p95 102.949ms、最大 481.330ms；波动说明不能据一次测量承诺稳定容量。第一轮世界平均 64.276ms、p95 101.979ms，但初段与回归构建有重叠，不作为独立容量结论。

环境：Windows 11 10.0.26200、i7-12700（12 核/20 线程）、约 15.7GiB 可见内存、JDK 21.0.12；磁盘介质/后台负载没有完整控制。样本包含重启、工资边界和 Minecraft 区块处理，不是纯 SQL 微基准。

**该四栋建筑场景已超过 20TPS 的 50ms 总 Tick 预算，日志出现 Can't keep up，不能宣称整服性能达标。** 没有降低耐久性、改异步提交、缓存权威游标或省略账本来提高数字。

正常 Tick 至少四个全球写事务（接受、应计、整轮生产确认、幂等返回确认）及每栋现存建筑两个写事务（推进决定、完成检查点）；本场景即至少 12 个取得写权限的事务/Tick。空闲重配置、结算/新开工、工资周期及操作阶段额外增加事务，另有多次 DEFERRED 一致读成本。取得写权限不代表每个空事务都写盘。当前方案保留持久证据，真实成本需后续专项优化及回归，不能据小场景推广大世界容量。

复现步骤和选择新隔离运行名见[性能方法](../testing/economy-performance.md)。未测：大量建筑/长期历史、真实客户端及玩家网络、多 Mod 组合、慢盘/断电、长期 GC 和大规模同时恢复；没有据 JUnit 时长替代这些项目。

## 12. 剩余问题与 main 合并判断

**不具备“整体重构已完全验收、可直接合并 main”的条件。** 本轮安全基础可以单独审查；不能因为测试通过把未实现的交割及性能限制隐去。

必须先完成：

1. 明确出售 Pending PM、原公司最终工资余数清算、库存产权/随售及拆除处置、出售资金对价合同。
2. 在这些规则下实现最终 owner CAS、工资截止/新段、Staffing 处理及操作完成的共同事务，补全实际出售/拆除、跨地区释放/重新招聘及买方不受旧债影响的正式验收。本轮相关历史测试只证明数据隔离，不能充当交割闭环。
3. 明确最终生命周期状态与持久版本；不在当前只有 QUIESCING/BLOCKED 的 v9 中静默增加另一种不兼容完成合同。任何旧文件仍须原样保留，迁移需要另外授权。
4. 解决每 Tick 事务/查询成本并做隔离整服容量验证；优化必须保持现有持久检查点、完整账本与事务保护。
5. 正式身份配置、资金初始化来源、玩家公司授权、居民消费比例及市场消费仍不由本轮编造；pig_iron_tools 仍缺正式岗位数据而受拒绝。任务 D 的多历史工资来源部分分配门禁继续有效。

任务 E 的所有安全可实施内容完成后停止，等待统一源码审查；不自动提交、推送、合并或开始其他工作包。

## 13. 完整变更文件清单

原因分组：Lifecycle 新文件承接操作及门禁；生产/身份/Staffing 仓储阻止生命周期旁路并支持屏障交接；Inventory 仅新增同会话读取用于真实回执核验；时间查询区分墓碑；Guard 修复真实驱动 metadata 接口并保留隔离；Schema/Codec 为 v9；Commands/Context/锚点装配正式准备与核验；测试源集、兼容夹具、JUnit 及文档覆盖上述不变量。

<!-- BEGIN_FILE_MANIFEST -->
相对实际基线共 **62** 个文件：新增 **25**、修改 **37**、删除 **0**。包括本报告；M=修改，A=新增。

| 状态 | 文件 |
|---|---|
| M | `build.gradle` |
| A | `docs/architecture/adr/ADR-LIFECYCLE-01.md` |
| M | `docs/architecture/adr/ADR-PO-02.md` |
| A | `docs/design/VE-Task-E-M5.1-M5.2-M5.3-建筑生命周期与整体验收.md` |
| M | `docs/domain/production-operations/code-reading-map.md` |
| M | `docs/domain/production-operations/context-decoupling-review.md` |
| A | `docs/domain/production-operations/current-runtime.md` |
| M | `docs/domain/production-operations/overview.md` |
| M | `docs/domain/production-operations/package-refactoring.md` |
| M | `docs/production-audit.txt` |
| M | `docs/tasks/ADR-PO-02-codex-task.md` |
| A | `docs/testing/economy-performance.md` |
| A | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/AcceptanceMetrics.java` |
| A | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/AcceptanceTestMod.java` |
| A | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/EconomyAcceptanceGameTests.java` |
| A | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/GameEconomyFixture.java` |
| A | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/RestartAcceptanceGameTests.java` |
| A | `src/gameTest/resources/data/victoria_economics_gametests/structure/empty.nbt` |
| A | `src/gameTest/resources/data/victoria_economics_restarttests/structure/empty.nbt` |
| A | `src/gameTest/resources/META-INF/neoforge.mods.toml` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/common/block/BuildingAnchorBlock.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/common/blockentity/BuildingAnchorBlockEntity.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/application/BuildingLifecycleService.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/BuildingRegistry.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/domain/BuildingLifecycleOperation.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/infrastructure/SqliteBuildingLifecycleOperationRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/package-info.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/port/BuildingLifecycleOperationRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/command/ProductionCommands.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryStore.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/package-info.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/package-info.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBoundaryCoordinator.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/package-info.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionExecutionRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionMethodConfigurationRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/port/package-info.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyLifecycleSchema.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyLifecycleValidation.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteBuildingRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteConnectionGuard.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/time/infrastructure/SqliteEconomicTimeRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/application/package-info.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/SqliteBuildingStaffingRepository.java` |
| M | `src/main/resources/assets/victoria_economics/lang/en_us.json` |
| M | `src/main/resources/assets/victoria_economics/lang/zh_cn.json` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/building/BuildingLifecycleDomainTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialCompatibilityTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryContractTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BuildingLifecycleCompatibilityTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BuildingLifecycleConcurrencyTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BuildingLifecycleRecoveryTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BuildingLifecycleTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionSchemaContractTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/TestEconomy.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteTransactionsTest.java` |
| A | `src/test/resources/fixtures/sqlite/schema-v9.sql` |
<!-- END_FILE_MANIFEST -->
