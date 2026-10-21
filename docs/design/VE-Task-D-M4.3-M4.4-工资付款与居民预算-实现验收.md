# 任务 D：M4.3 / M4.4 工资付款与居民预算——实现验收

日期：2026-10-21。范围为公司统一付款、居民真实到账和用途预算；未实施出售、拆除、市场、物流、换汇、贷款或股票。

## 1. 实际基线和数据保护

- 分支：`refactor/m1-0-sqlite-transaction-cleanup`。
- 实际 HEAD：`95f94a0d038f20138ee25e458e41e667aa596046`，与任务 C 参考提交一致；开始时工作区干净。
- 已读取 M0、DDD 草案和任务 A/B/C 的完整验收报告。本轮使用已确认规则；未替历史工资来源决定分配优先级，也未设定消费/储蓄比例。
- 没有 commit、push、merge、reset、自动暂存或切换分支。所有数据库行为验证使用 JUnit 临时文件；没有启动 Minecraft 或打开真实存档 SQLite 连接。
- 真实世界 `run/saves/新的世界/victoria_economics/economy.sqlite` 仅只读核对 SHA-256：`3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`。最终再核对主文件及 WAL 是否存在；结果见构建验收。

## 2. D1～D6 实施结果

| 阶段 | 结果 |
|---|---|
| D1 公司统一付款 | 一个 CompanyId + PeriodId 父 Run，包含全部币种分区；历史公司是付款主体；现金为零也有不可重复支付的零付款决定。 |
| D2 比例分配 | 每公司每币种先按建筑汇总；整数最大余数，规范 BuildingId 文本升序打破并列；组内不明确的部分分配被拒绝。 |
| D3 原子资金与债务 | 复用 Finance 的同连接 WriteSession；扣款、地区到账、双边账本、工资 paid、付款行及父 Run 同一物理事务。 |
| D4 周期调度 | 持久接受时间→应计/到期化→公司付款→周期确认→生产；区分 PENDING 技术恢复与 BLOCKED 已知业务拒绝，不因现金不足停止应计。 |
| D5 欠薪恢复 | 真实部分付款后保持暂停；后续真实清偿使 CURRENT，原 Batch 恢复；手动原因、原预留、快照和不可逆中止继续有效。 |
| D6 居民预算 | 显式 Region/Currency 居民部门；消费/储蓄只是用途额度。额度 CAS、跨期结转、资金预留和真实可用现金共同约束，无默认比例。 |

## 3. 付款身份、币种和完整命令

`PayrollPaymentRun.Command` 固定 `id / CompanyId / PeriodId`；截止 Tick 为 `PeriodId × 1200`，精确检查溢出。ID 由公司与周期稳定派生，修改同 ID 的公司或周期会被拒绝。数据库同时具有 Run ID 主键和 `UNIQUE(company_id,period_id)`，不能换一个 ID 再发一次，也不能把币种放进父键绕开每公司每周期一次。

命令不接受任意付款金额或单币种筛选。事务重新读取该公司的全部 `liability.period <= Run.period` 到期未付来源，在同一个父 Run 内产生多个 CurrencyId 分区。各分区独立计算同币种数量，不把多币种总额混成一个 long，也不换汇。全部分区先规划，任一缺配置或规划失败均不提交其他分区。

完成后重试返回原持久决定，即使随后现金增加也不重新规划。无到期未付债务的公司不生成 Run；“有债务但可支付现金为零”生成 COMPLETED 零支付决定，保留欠薪，不创建零金额 TransferReceipt。

PaymentLine 是 Run 的从属不可变值，没有独立自由更新服务。每行保留原 segment、Liability Period、BuildingId、受益 RegionId、CurrencyId、居民账户、付款前欠款和付款额；正金额行引用稳定的财务 Transfer 事件。

## 4. 最大余数与未决历史来源门禁

同公司同币种下，设建筑欠款为 dᵢ，总欠款为 D，可支付额 B=min(真实未预留现金,D)。先给出 `floor(B×dᵢ/D)`，再按余数降序补齐剩余最小单位。余数相同按规范小写 UUID 的 BuildingId 文本升序排序，避免 Java UUID 有符号比较造成不同顺序。

乘法/除法中间值采用 BigInteger，最终数量与汇总使用 exact long。负数、零债务输入、总额不可表示、账户或工资 Revision 溢出均整笔拒绝。各分区付款总额正好为 B，任何来源不会超付，也不损失舍入单位。

组内规则保持保守：

- 一个建筑组只有一个正数未付 Liability 来源时，可做部分支付。
- 多来源组分配为 0 或全额时，没有拆分选择：分别全部为 0 或逐来源全额支付。
- 多来源组获得介于 0 与组欠款之间的分配时，抛出 `PayrollPaymentUnavailable`，记录 `UNRESOLVED_BUILDING_SOURCE_ALLOCATION`。不替不同 segment、地区或多个欠款期间选择 FIFO、比例、随机或其他优先级。

该门禁也保守覆盖同一 segment 下多个未付期间，避免在没有正式决策时隐式决定冲销哪一期。它不妨碍建筑层分配、单来源部分付款及多历史来源全额清偿。不同债务公司始终分开，原受益地区不丢失。

## 5. Finance、Payroll 与 Run 的真实事务

CompanyPayrollService 使用现有 `SqliteTransactions.write` 和 `SqlitePayrollPaymentRepository` 的明确事务会话。付款存储能力专用于公司周期付款，不提供通用现金或工资 CRUD；Finance 与 Payroll 仍各有独立仓储。没有在外层事务调用会另开物理事务的 FinanceService.transfer。

顺序为：

1. 周期准备事务冻结本周期有到期未付债务的公司，并持久创建稳定 PENDING 父意图。
2. 付款物理 IMMEDIATE 事务取得写权限后，加载父意图、可信时间、真实公司账户、全部债务和显式居民部门。
3. 依据真实 balance 和有效 CashReservation 求和得到 available；规划全部币种、建筑和来源行。
4. 保存币种分区的余额/预留历史快照、付款前账本位置及有效 Reservation 身份，供之后核验。它们是冻结的审计证据，不作为未来现金余额来源。
5. 用 Finance 仓储同一 WriteSession 转账，生成 TransferReceipt 与双边 LedgerEntry；保存该付款行。
6. Payroll 会话核对行、Transfer、原债务公司、来源和受益账户，CAS 冲销 Liability，同时更新 Accrual.paid；不改应计额、余数或原历史归属。
7. 全部行完成后将父 Run 改为 COMPLETED；物理提交及连接清理确认后才返回结果。

每币种均满足：公司扣款 = 居民实际到账 = 到期债务减少 = PaymentLine 金额和。市场或其他用途的资金 Reservation 不可用于工资。任何写点失败，资金、债务、所有分区/行及父 Run 完成标记一起回滚；先前独立提交的意图保留。

提交未知时隔离旧连接及派生 JDBC 句柄，不在该连接重扣。新可信连接经过完整格式门禁，再用 `verify(command)` 联查持久图：完整 COMPLETED 返回原结果；仅有意图或意图不存在时，依据新连接确认的事实恢复同一稳定周期身份，没有随机新 ID。失败证据表只追加，成功或重启不删除它；`failures(command)` 可查询原因和经济 Tick。

原 M1 的 commit/rollback/savepoint/连接隔离算法未修改。所有权威修改仍先取得写权限，再读取；普通 committed 查询与显式 DEFERRED 快照仍不持有 IMMEDIATE 写锁，没有 ThreadLocal 或 unguarded unwrap。

## 6. 周期状态和重启恢复

新增世界付款周期游标以及周期记录，含义与工资应计游标不同：

| 状态 | 含义及恢复 |
|---|---|
| Run PENDING | 付款尚无确定结果，或技术步骤待恢复。不能认为已付；阻止本周期确认和后续 Tick 越过。未知提交先用新可信连接核验。 |
| Run BLOCKED | 在确认整笔回滚之后，持久记录的已知规划拒绝，例如缺账户或未决来源分配。没有资金和债务变化，不是付款成功。 |
| Run COMPLETED | 全币种决定与实际付款效果已同事务提交；可以为零支付，永不再次扣款。 |
| Cycle PLANNED | 公司成员已冻结，仍有待处理结果。 |
| Cycle ACKNOWLEDGED | 所有公司都具有 COMPLETED 或可核验 BLOCKED 结果，且预算跨期标记一同保存。只证明周期尝试已处理，不证明工资全付。 |

PayrollPeriodService 按持久 accepted Tick 与已完成应计的周期运行，先准备稳定公司集合，再逐公司付款，最后在独立事务确认周期并推进游标。一个公司已确认的结果不因其他公司失败或周期确认失败而重发；技术失败留下 PENDING，其他公司的已提交效果仍可逐一核验。

已知 BLOCKED 可以确认周期尝试，使未来经济 Tick 和工资继续累计；到期欠款仍真实存在，生产继续依据 ARREARS 暂停。它没有被改成成功或 CURRENT。

同一周期内，外部明确补齐配置后可通过同一命令重试 BLOCKED；成功也只完成该父 Run 一次。进入后续周期后，旧 BLOCKED 决定保持封存，重放只返回该拒绝事实，防止旧周期争夺新周期的全量债务决定。新周期规划包含所有尚未付清的历史到期债务。不会自动为已完成零支付 Run 补发。

正式 Clock 的顺序为：恢复已接受周期→恢复各建筑检查点→接受下一 Tick→应计/到期化→处理付款周期→生产。`acceptNext` 与生产全局确认都验证付款尝试游标，不能仅因方法返回就清除未完成事实。工资应计和付款检查点分别持久，不回退、不复用周期 ID、不按墙钟或停服时间补算。

技术或未知提交故障仍停止当前 Clock，需要可信恢复；已知现金为零或未决业务门禁不会使工资应计停滞。整个世界并非一个全局物理事务；每个公司 Run 是完整原子单位，世界周期按持久结果确认。

## 7. 真实清偿后的生产闭环

正式链为：

`EconomicClock → PayrollAccrualService → PayrollPeriodService → CompanyPayrollService → Finance/Payroll 同事务 → SqliteBuildingPayrollQuery → ProductionBoundaryCoordinator.advance`。

付款后，查询读取当前公司对应建筑的真实到期未付金额。没有复制现金或工资状态进 Execution，也没有注入模拟 CURRENT。真实未付为 0 时只解除 PAYROLL_* 原因；MANUAL 保留，automatic=false 仍只影响下一批，SETTLING_ABORTED/ABORTED 不能复活。

端到端测试执行 2400 个真实逻辑回调并跨一次重启：两栋 1/10 速度建筑在第 1200 Tick 获得真实部分工资后仍欠薪，保留相同 BatchId、1199 进度单位、配方/人数快照和库存 Reservation；第 2400 Tick 公司真实补足付款后，无遮挡的原 Batch 推进一次，手动暂停的原 Batch 保持同一进度，直到显式解除手动原因。居民到账、公司扣款与债务减少共同核对。

另一个测试用真实到期欠薪和 ABORTED 结算，再以明确的隔离 accepted-time 夹具建立下一付款边界；真实清偿后旧 Batch 不复活，原料全退、产出为零。该夹具不宣称重放离线生产。历史公司/地区的持久测试仅构造隔离历史事实，不提供建筑出售或迁移业务入口。

## 8. 居民部门和预算

RegionalHouseholdSector 只持有 RegionId、CurrencyId 和真实 FinanceAccount ID。显式 open 验证地区、币种和账户所有权；账户新建为零，部门与空预算和开户账本同一事务建立。没有默认身份、人口、现金、工资或预算比例。

HouseholdBudget 保存 consumption、savings、carriedPeriod、Revision；不保存现金余额。每次变更在 IMMEDIATE 事务重读真实账户及有效 Reservation，并验证：

`consumption + savings <= balance - activeReservations`。

资金预留、预算额度和未分配现金三者不会双重占用。同一账户的普通 Finance 转出及 reserve 也检查预算覆盖，不能由另一入口抢占已经分配的额度；需要显式释放/调整额度后再锁定或转出，没有自动挪用储蓄或消费的规则。

应用提供显式绝对额度分配、消费/储蓄之间的精确移动和跨期结转；使用 expected Revision/CAS。工资到账仅增加真实现金，未配置分配时新增现金保持未分配。跨周期保留未用额度，只推进标记，不加钱、不创建现金账本事件；重复结转幂等，未来周期和倒退请求拒绝。

本轮没有消费交易或市场订单。没有为了减少预算而销毁真实现金或凭空生成 LedgerEntry；未来消费方需要在共享事务显式调整额度并建立真实资金 Reservation。

## 9. Schema / Codec v8 与核验合同

v8 新增 9 张规范化表：

- payroll_payment_runtime、payroll_payment_cycles、payroll_payment_runs。
- payroll_payment_partitions、payroll_payment_cash_locks、payroll_payment_lines、payroll_payment_failures。
- regional_household_sectors、regional_household_budgets。

具有公司周期唯一、来源 Liability/Transfer/账户/地区/币种 FK、整数与非负 CHECK、父状态/Revision 约束。PaymentLine 无独立根或删除 API。没有可写 occupied/available、第二现金余额或预算发行。

Batch、整数库存及暂停/屏障业务保持任务 C 合同，但当前 JSON envelope 明确为 formatVersion=8；不让 v7 JSON 在新世界下隐式读取。新建世界使用 v8，不迁移 v1～v7。独立新增 schema-v8.sql，历史 v2～v7 字面量夹具保留。

`EconomyFinancialValidation` 的 `paid == 0` 已替换为 `EconomyPaymentValidation` 的完整联查，而非简单放开付款数值。先核验原账户账本、工资率/人数历史、余数和应计结果，再核验：

1. 每单位 Liability.paid 都能汇总到完整父 Run 下的付款行；Accrual.paid 与其 Liability 汇总一致。
2. 公司、建筑、历史地区、币种、居民部门、来源及收款账户全部匹配。
3. 按周期重建付款前欠款，来源完整；重新执行建筑最大余数计算，拒绝超付和未决部分拆分。
4. Transfer ID 与完整命令、双边账本、付款前余额位置和资金预留快照相符。
5. PENDING/BLOCKED 不允许有已经发布的分区或付款行；BLOCKED 有保留的失败证据。周期游标、已知结果与单调版本一致。
6. 居民预算与真实未预留现金、部门身份、周期和 CAS 状态一致。

复用原物理 READONLY 预检及通过后无 CREATE 权限的可写复检。不兼容版本、旧 NPC/整数快照、缺表、混合格式、伪造 paid、缺 Transfer、错误账本、伪造额度和游标均在业务写入前拒绝，不修复旧数据。旧 v7 六种 Batch 状态及 WAL 字节保护新增覆盖，历史 v1～v6 保护继续适用。

## 10. 测试矩阵与构建

| 验收点 | 测试证据 |
|---|---|
| 足额/比例/并列/零现金/资金锁定 | PaymentDomainTest、CompanyPayrollPaymentTest；真实临时 SQLite 与双边账本；规范 BuildingId 并列，大整数乘法和总额溢出。 |
| 多公司/多地区/多币种父 Run | 单公司两个币种共同提交；缺任一分区则全部不付；不同历史债务公司分开，旧公司欠薪不阻塞新公司事实。 |
| 多来源门禁 | 同 segment 多欠款期间、同建筑多历史受益地区，部分分配 BLOCKED，全额按来源清偿；旧拒绝保留，后续周期继续应计。 |
| 原子故障 | 第二笔转账、第二 Liability、第二 Accrual、第二账本、第二付款行、币种分区和父 Run 写点分别故障，整笔资金/债务/行回滚。 |
| 意图和周期确认 | 意图创建失败不残留孤立周期；周期游标失败只重试确认，不重扣；下一 Tick 拒绝越过 PENDING。 |
| 未知提交 | 意图、实际付款、周期确认的 commit 前/后回执丢失；成功提交后 reset 失败；新可信连接核验不重复付款。 |
| M1 异常 | rollback+close 失败无 finally commit/reset；派生句柄隔离；Savepoint 失败和 callback 捕获错误也阻止外层提交。 |
| 两连接 | 同公司周期、同周期调度竞争只提交一次；DELETE/WAL 中首次权威读取前第二写 SQLITE_BUSY(5)。 |
| 真实暂停恢复 | PayrollPaymentPauseIntegrationTest：2400 真实回调、部分/全额付款、相同 Batch/预留/快照、重启、MANUAL、automatic=false、ABORTED 不复活。 |
| 预算 | 显式分配、未消费结转、不新增现金账本、既有和新预留约束、CAS/溢出、部门初始化与更新失败整体回滚。 |
| 预算并发 | 两连接 CAS；预算与预留争用同一现金；工资到账/预留/预算三连接竞争；未知提交前后；DEFERRED 一致只读快照不取写锁。 |
| v8 防伪 | PaymentCompatibilityTest：真实 paid 图只读重启；缺行/Transfer/原锁、错误公司或受益账户、超付、余额快照、非法预算、周期/行序号溢出等拒绝，主文件及 WAL 不变。 |
| 全部回归 | 任务 C 200/334 Tick、整数商品和全额结算、唯一 Staffing、RECONFIGURING、工资精度/余数、每建筑 Tick、StartIntent/Journal、库存 CAS、Finance、身份与劳动力；M1.0 26 项及 UoW 29 项。 |

没有删除测试类或降低有效断言。旧当前格式夹具显式更新为 v8，另保留 v7 拒绝测试。资金夹具仅在 src/test，以有来源说明的 EXTERNAL_CREDIT 和完整账本建立隔离资金；它们不打包或接入正式服务器。

最终执行：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：**BUILD SUCCESSFUL in 3m 49s**，9 个任务全部实际执行。最终 XML：**37 类、539 项，0 failures、0 errors、0 skipped**。相对任务 C 的 444 项净增 95 项：新增 9 类共 87 项，旧 Schema 兼容测试新增 8 项。

| 测试类 | 数量 |
|---|---:|
| BoundaryRecoveryTest | 14 |
| BuildingIdentityPersistenceTest | 12 |
| BuildingProductionIsolationTest | 5 |
| BuildingStaffingTest | 9 |
| CompanyPayrollPaymentTest | 16 |
| CompanyPayrollRecoveryTest | 19 |
| EconomicTimePersistenceTest | 8 |
| EconomyUnitOfWorkTest | 29 |
| FinancePersistenceTest | 14 |
| FinancialCompatibilityTest | 14 |
| FinancialTransactionSafetyTest | 10 |
| GoodsInventoryTest | 4 |
| HouseholdBudgetConcurrencyTest | 8 |
| HouseholdBudgetPersistenceTest | 6 |
| HouseholdBudgetTest | 4 |
| LaborTransactionSafetyTest | 17 |
| PaymentCompatibilityTest | 19 |
| PaymentConcurrencyTest | 5 |
| PaymentDomainTest | 8 |
| PayrollAccrualDomainTest | 8 |
| PayrollPaymentPauseIntegrationTest | 2 |
| PayrollPersistenceTest | 26 |
| ProductionConcurrencyTest | 8 |
| ProductionDomainTest | 17 |
| ProductionIntegrationTest | 19 |
| ProductionRepositoryTest | 11 |
| ProductionSchemaContractTest | 13 |
| ReconfigurationTransactionSafetyTest | 4 |
| RecoveryAndControlsTest | 13 |
| SchemaCompatibilityTest | 125 |
| SqliteInventoryConcurrencyTest | 14 |
| SqliteInventoryContractTest | 5 |
| SqliteTransactionsTest | 26 |
| StaffingQueryTest | 4 |
| StaffingRepositoryTest | 8 |
| StartCommitRecoveryTest | 11 |
| WorkforceContractTest | 4 |

M1.0 的 26 项及 UoW 的 29 项均包含在完整运行中。产物 `build/libs/victoria_economics-0.1.1-1.21.1.jar` 包含 CompanyPayrollService、PayrollPeriodService、RegionalHouseholdService、HouseholdBudget 和 v8 核验器；测试资金夹具、JdbcFaults 及 SQL fixtures 条目为 0。

`git diff --check` 通过。真实世界主文件最终 SHA-256 与开始值完全一致：`3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`；开始和结束核对均只有 economy.sqlite，没有该存档的 WAL。历史 schema-v2～v7.sql 没有 Git 修改。HEAD 仍是 95f94a0，没有自动提交或推送。

完整输出：`build/task-d-validation.log`；JUnit XML：`build/test-results/test`；HTML：`build/reports/tests/test/index.html`。未升级 SQLite JDBC、Gradle 或 NeoForge，未降低持久性。验证范围是纯 JVM 与真实隔离 SQLite，没有把测试耗时当作 Minecraft 全服性能验收。

## 11. 尚未开放的能力与任务 E 前置

1. 正式资金发行/注入来源、公司初始现金及人口仍未授权，没有 debug addMoney 或默认现金。正式账户默认为 0，付款只能花真实可用现金。
2. Minecraft 玩家公司/地区身份来源、真实币种、工资率和总供给仍需显式配置；没有这些事实不创建可运行经济身份。居民部门注册与额度输入通过明确服务能力提供，没有代玩家猜测配置。
3. 正式消费和储蓄比例未定。新增收入不自动分配；额度移动和跨期保留不代表已经实现居民消费、订单或物流。
4. 建筑组内多历史来源的部分冲销优先级仍未决，持续 BLOCKED 并可查询。全额或零分配场景已支持，不以随机/FIFO 默认值绕过。
5. 旧 BLOCKED 周期封存不消灭债务；后续 Run 读取所有历史未付金额。任务 E 必须继续保留原债务公司、地区、币种、工资段及分数余数，不能通过当前 owner 重新归属。
6. 出售的 Pending PM、库存资产范围、拆除/出售后不足一个最小单位余数的最终清算和生命周期操作存储仍需决策。没有实现售楼或拆除服务；历史测试夹具不是正式交割流程。
7. 技术未知结果隔离后仍需新可信连接/Context 重建，未实现在线自动重连。周期与各公司都是可核验持久单位，不宣称一个覆盖全部公司的世界事务。
8. 付款、预算、历史账本和恢复证据没有自动裁剪；大规模建筑/长期历史的整服性能仍需隔离负载验证，不能为优化而丢弃幂等证据。

任务 E 可依赖真实付款/欠薪、居民现金、预算约束和任务 C 的受控终止/屏障，但必须先处理其未决生命周期输入，并验证跨所有权、历史债务和预留资产的一致性。

## 12. 完整文件清单

相对实际基线共 50 个文件：新增 29，修改 21，删除 0。以下均为仓库相对完整路径。

职责与原因：新 Payroll 文件负责父周期身份、分配、同事务 posting、失败证据和调度；Household 文件负责部门与用途额度；Finance 只扩展显式会话查询和预算覆盖检查；Schema/Codec/验证器落实 v8；Clock/Context 及生产确认增加付款恢复门禁。旧测试只调整当前格式和正式付款装配，并保留原故障断言；新增测试及独立 SQL 字面量验证新规则。本轮无文件删除。

### 新增

```text
docs/design/VE-Task-D-M4.3-M4.4-工资付款与居民预算-实现验收.md
src/main/java/com/bbmurloc/victoriaeconomics/server/household/application/RegionalHouseholdService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/household/domain/HouseholdBudget.java
src/main/java/com/bbmurloc/victoriaeconomics/server/household/domain/RegionalHouseholdSector.java
src/main/java/com/bbmurloc/victoriaeconomics/server/household/infrastructure/SqliteHouseholdBudgetRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/household/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/household/port/HouseholdBudgetRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/application/CompanyPayrollService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/application/PayrollPeriodService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollPaymentLine.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollPaymentPlanner.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollPaymentRun.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollPaymentUnavailable.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/ProportionalPaymentAllocator.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqlitePayrollPaymentRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/port/PayrollPaymentRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyPaymentSchema.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyPaymentValidation.java
src/test/java/com/bbmurloc/victoriaeconomics/server/household/HouseholdBudgetConcurrencyTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/household/HouseholdBudgetPersistenceTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/household/HouseholdBudgetTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/CompanyPayrollPaymentTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/CompanyPayrollRecoveryTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/PaymentCompatibilityTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/PaymentConcurrencyTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/PaymentDomainTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/PaymentTestWorld.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/PayrollPaymentPauseIntegrationTest.java
src/test/resources/fixtures/sqlite/schema-v8.sql
```

### 修改

```text
src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java
src/main/java/com/bbmurloc/victoriaeconomics/server/finance/infrastructure/SqliteFinanceAccountRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/finance/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollAccrual.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqlitePayrollRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/EconomicClock.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBoundaryCoordinator.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyFinancialSchema.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyFinancialValidation.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java
src/main/java/com/bbmurloc/victoriaeconomics/server/time/infrastructure/SqliteEconomicTimeRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/time/package-info.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/EconomicTimePersistenceTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialCompatibilityTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialTestWorld.java
src/test/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryContractTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionSchemaContractTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/TestEconomy.java
src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java
```

任务 D 结束后停止，等待统一源码审查；不自动进入任务 E 或执行 Git 提交、推送、合并。
