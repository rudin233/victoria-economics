# Victoria Economics — Production / Labor / Payroll DDD 重构设计草案

**版本**：Draft 0.1 · 2026-10-20  
**状态**：设计稿，未实施；文中【已确认】为已通过的业务规则，【建议】为待代码审查的具体实现，【未决】不得被 Codex 擅自视为已确认。  
**源码参考**：`rudin233/victoria-economics`，2026-10-20 核对 GitHub `main` HEAD 为 `11ce45aa44707d3960ee0681dcea43b3cca9765f`（提交时间 2026-10-10）。  
**参考文档**：`victoria-economics-design-v0.1.md`、`生产运营限界上下文.md`、`VE-ADR-PO-02-双聚合持久化设计.md`。三份文档部分历史内容已被新决策取代。

## 1. 适用范围与已确认决策

### 1.1 仍然有效的规则

- **生产聚合**：`ProductionMethodConfiguration` 与 `ProductionExecution` 为两个独立聚合根；`EconomicBuilding` 不再持有生产权威状态；一栋建筑同时最多一个未结算 Batch。
- **经济时钟**：每 1 经济分钟 = 1200 逻辑 tick，满速批次基础时长 = **200 tick**；TPS 下降经济时间放慢、离线不补算。不同建筑没有人为设置批次间停顿。
- **整数商品**：Batch 配方的全部投入/产出是整数。开始时全额排他预留原料；正常完成全量消耗和产出；正式提前终止全额释放预留、**零产出**。工资不能随 Batch 终止回滚。
- **Batch 运行**：`ACTIVE`、`PAUSED`、`SETTLING_COMPLETED`、`SETTLING_ABORTED`、`COMPLETED`、`ABORTED` 保留语义。`PAUSED` 保留进度及原料锁定；`SETTLING_ABORTED` 是不可逆终止决定。结算成功后可在同一服务端 tick 启动新批，但新批在该 tick 不增加进度。
- **取消个体 NPC**：建筑汇总雇佣 `Map<OccupationId, Integer>`；每职业一个可替换的 Pending 目标，申请在生产中待批次边界执行；地区有限总供给，招聘允许部分成功、无自动补招任务。
- **取消生产设备**：不安装、不持有、不交易专用设备，不受设备容量限制；工具、发动机及机械等仍可作为普通经济商品交易及用于 PM 配方。
- **生产速度**：仅由本 Batch 开工时的各职业参产人数与所需满编数决定，`s=min_i(n_i/N_i)`；每个必需职业至少达到其需求 10%；所有产商品配方至少有一项正数职业需求。
- **PM 与用工**：旧 Batch 使用开工快照。新 PM 只在旧 Batch 完成后生效，伴随超额自动裁员；Pending Staffing 上限自动收缩，新人数不自动补招。PM 生效和 Staffing 修改同一 SQLite 事务提交；失败整体回滚并阻止新 Batch。
- **工资**：所有保留的雇员在正常生产、停产、暂停期间持续按时间应计；每经济分钟公司统一尝试支付；可支付现金不足时部分支付并留下欠薪；从生效 tick 起切换工资率，不能追溯；不允许动用市场锁定资金。
- **工资付款**：公司是债务人，按建筑/地区保留历史来源；多建筑分配按到期工资比例和最大余数法，余数并列按稳定 BuildingId；公司每个工资周期最多完成一次支付。
- **欠薪阻塞**：只暂停当前公司对应该建筑的到期欠薪 Batch；偿清后且无其他暂停原因时自动恢复原 Batch；因欠薪裁员可受控终止 Batch，自动生产设置关闭。
- **居民部门**：地区居民拥有一个真实现金账户，储蓄/消费为现金用途额度，消费预算跨周期结转；工资实际支付才增居民现金。
- **建筑变化**：拆除需先受控终止并结算、解除雇佣，保留墓碑、公司工资历史负债；出售需先终止结算、截止旧公司工资应计，保留实际雇佣人数、清除原公司 Pending Staffing、所有权交给买家，买家未来工资自行承担，且必须主动重新启用生产。旧公司历史债务不随出售转移。
- **地区供给**：仅地区供给和建筑实际雇佣为权威。就业总数、空闲人数即时派生。地区供给下降不得低于当前就业人数；多建筑招聘按服务端成功提交顺序分配。

### 1.2 取代历史设计

- `生产运营限界上下文.md` 中的“生产批次按进度比例结算”、“个体参产员工保护”和设备能力规则均已失效。
- 旧 ADR-PO-02 的边界 `METHODS → EQUIPMENT → WORKFORCE`、设备保护、个体 NPC reconciliation、1200 tick **满速批次**、比例终止结算均不再适用；独立生产双聚合、版本、批次排他、可靠结算与启动恢复仍保留。
- 取消设备不等于删除一切普通机械类贸易商品；取消个体 NPC 就业不等于去掉有限劳动力供给和工资义务。

## 2. 聚合/模块与不变量

### 2.1 `EconomicBuilding`（Building Identity）

- **权威**：`BuildingId`、`BuildingTypeId`、`OwnerCompanyId`、`RegionId`、`LifecycleStatus`。
- **建议状态**：`ACTIVE`、`DECOMMISSIONING`、`TRANSFER_PENDING`（仅需可靠转让流程时）、`DEMOLISHED`。现有 `BuildingStatus` 的实际枚举需要适配审查，不能照搬状态名。
- **不变量**：已拆除建筑不得招聘或启动 Batch；建筑 ID 不复用；墓碑持久保存最低限度的历史归属信息。
- **不负责**：工资负债、Batch 配方、雇员人数、地区供给。

### 2.2 `ProductionMethodConfiguration`（保留独立聚合）

- **权威**：`effectiveSelections`、最多一个 `pendingSelections`、配置和生效版本。
- **方法（语义草图）**：`requestSelection(...)`、`requestTarget(...)`、`cancelPending()`、`applyPending()`、`resolveRecipe()`。
- **不变量**：PM 组合和等级有效；当前 Batch 快照不变；无进行中 Batch 且无未完结重配置的 PM 立即生效时，也必须与 Staffing 上限调整保持同一事务。

### 2.3 `ProductionExecution`（保留独立聚合）

- **权威**：当前 Batch、最近结束 Batch、`automatic`、Execution 修订、必要的恢复屏障。
- **建议边界简化**：旧 `METHODS/EQUIPMENT/WORKFORCE` 改为 `NONE/RECONFIGURING`（或等价的持久化不可开工标记）。`settlementCompleted()` 仅表示库存结果已确认，仍需进入 `RECONFIGURING`；直到 PM、Staffing 及屏障清除同一个 SQLite 事务提交后才允许新开工。
- **核心方法（建议）**：`start(batchId, immutableConfig)`、`advance(ticks)`、`pause(reason)`、`resumeIfUnblocked()`、`requestAbort()`、`confirmSettlement(batchId)`、`finishReconfiguration()`、`setAutomatic(enabled)`。
- **不变量**：一个未终结 Batch；不覆盖未结算 Batch；恢复保持原 Batch 快照；不可逆终止；重新配置失败不得清除屏障；同 tick 新 Batch 不推进进度。
- **注意**：不为此引入第二份 Effective PM 权威数据；是否继续存储 `methods_effective_revision` 要以真实必要性检验。

### 2.4 `ProductionBatch` 与 `ProductionBatchConfiguration`

- **Batch Entity**：拥有 BatchId、BuildingId、状态、整数 `progressUnits`、完整不可变配置。
- **不可变配置建议字段**：PM 选择/生效版本、整数 `ResolvedProductionRecipe`、`Map<OccupationId,Integer> participatingWorkers`、速度分子/分母（按原整数设计可沿用）。移除所有设备容量、设备安装数量和员工 UUID。
- **算法**：对每个 `N_i>0` 的职业要求 `10*n_i>=N_i`；`s=min(n_i/N_i)`，计算精确有理比，不使用 Double；`FULL_SPEED_TICKS=200`。例如 s=3/5 时最大进度单位 1000，每 tick 增量 3，达到完成状态需要 `ceil(1000/3)=334 tick`。

### 2.5 `BuildingStaffing`（新独立聚合）

```java
// 结构草图：签名不约束后续实现
final class BuildingStaffing {
    BuildingId buildingId;
    long revision;
    Map<OccupationId, Integer> employedWorkers;
    Map<OccupationId, Integer> pendingTargets; // 缺失与目标 0 有不同语义

    void requestTarget(OccupationId role, int target, boolean defer);
    void cancelPending(OccupationId role);
    StaffingTransition planTransition(Map<OccupationId, Integer> newRequirements,
                                      Map<OccupationId, Integer> availableSupply);
    void apply(StaffingTransition transition);
}
```

- 聚合自身不持有地区剩余供给。`planTransition` 的区域事实由应用层读取；如需纯净聚合接口，也可将计算拆到无状态 `StaffingTransitionPlanner`，再调用聚合的 `apply(...)`。
- **内部约束**：人数非负；每职业最多一个 Pending 目标；PM 上限生效后雇佣数不超过上限；人员调整不追溯修改已锁定 Batch 快照。
- **跨聚合约束**：招聘涉及 `RegionalLaborSupply` 与所有建筑雇佣总和，在数据库写事务中二次验证；不能仅依赖先前读出的可用人数。
- 发生裁员或招聘之前先把相关建筑工资累计到变更生效经济时刻，再修改人数。

### 2.6 `RegionalLaborSupply`（地区供给权威）

- 保存 `(regionId, occupationId) -> totalSupply`，不保存 occupied/available。
- 可用人数：`totalSupply - SUM(employed)`；招聘及供给变化都必须在同一串行化写事务的权威快照中验证。
- 供给减少到 `<就业人数` 必须拒绝或让调用方延期，v1 不自动裁员。

### 2.7 `Payroll`（财务限界上下文）

- **义务归属**：公司（CompanyId）是债务人；建筑和地区是债务来源及收款地区。
- 建议 `AccrualBucket` 键：`(CompanyId, BuildingId, RegionId, CurrencyId, accrualEpoch/segment)`，包含 `lastAccruedTick` 和不可丢失的分数余数；出售时终止卖家段并开始买家段，余数不跨公司转移。
- **工资公式**：工资率 `w` 表示每人每 1200 经济 tick 的整数最小货币单位。

  `raw = oldRemainder + employed * w * elapsedTicks; dueAdded=raw/1200; newRemainder=raw%1200`。

- 若采用多个职业工资率，按职业相加后同样使用整数分母；现有 v1 已确认的是地区平均工资，不能擅自引入职业工资差价。
- 应计余额与已经到期但未付债务必须区分。公司支付每周期 idempotency key（如 `companyId+economicPeriodId`）。
- **付款**：读取不可被市场锁定的公司可支配现金；按到期工资负债的建筑组比例与最大余数算法确定支付额；扣公司现金、增收款地区居民账户、减少欠薪、记录付款同一事务；任何提交结果未知必须核查事实后再重试。
- **重要**：多笔相同 BuildingId、不同历史公司/地区或不同币种的债务不可因聚合键不当而混入同一比例分配；先明确按公司/币种支付范围。

### 2.8 `RegionalHouseholdSector`

- 一个地区/货币的**真实账户**保存现金，预算只表达其内部用途：可用于消费额度、保留储蓄额度、可能存在的市场订单锁定额度。
- `consumeBudget <= unreservedAccountCash`；工资实际支付才影响居民真实现金；未用消费额度跨周期结转。
- 如储蓄是预算划分而非独立账户，额度移动不得生成第二份真实现金余额。

## 3. 事务边界与崩溃恢复

### 3.1 同一经济 tick 的计薪顺序

1. 收到员工人数、工资率、建筑所有权变化请求时，先按照*变更前*状态计算 `lastAccruedTick -> currentTick` 的应计工资。
2. 计薪写入及状态变更尽量放入一个数据库事务，避免既改变人数又丢失计薪时间段。
3. 在公司统一工资周期触发时，将旗下建筑计薪推进到周期边界，再用截至该时点的到期未付金额决定支付计划。
4. 同一周期多次调用支付只能认可一次已提交付款；重启不重复扣款。

### 3.2 正常 Batch 完成与重配置

1. Batch 达到满进度，持久化 `SETTLING_COMPLETED`。
2. Inventory 使用 BatchId 完成**一次且仅一次**完整消耗和产出，并回执确认。
3. Execution 持久化 `COMPLETED + RECONFIGURING`。从此禁止新开工。
4. 在**一个 SQLite 写事务**内：加载最新 Building / PM / Staffing / 区域供给 / Payroll 应计游标 / Execution；确认旧 Batch 已结算；推进工资；应用 Pending PM；自动裁员；修正并执行一次性 Pending Staffing；同步写回 PM、Staffing、Payroll 和 Execution 清除 `RECONFIGURING`（及其各自版本）。
5. 提交成功，才重新判断开工并可在**同一服务器 tick**提交新 Batch；该新 Batch 不在当 tick 再推进。
6. 任何一步失败均保持或恢复 `RECONFIGURING`；交易中途失败整体回滚。对未知提交结果要重读已提交事实，不可直接相信内存。

> 设备取消后，不再需要逐步持久化 `METHODS/EQUIPMENT/WORKFORCE`。保留一个可靠的“结算后尚未重配置完毕”屏障是为了确保不会越过 Pending；其具体表示可结合现有 `ProductionExecution` 重构。

### 3.3 人事变更（无 Batch 时 / 有 Batch 时）

- 空闲建筑：用同一写事务推进工资、验证 PM 岗位上限和地区供给、调整 Staffing；成功后生效。
- 运行中的 Batch：只持久化 Pending Target，不马上改变雇佣人数和计薪基数；当前 Batch 原 PM 快照不受影响。
- 欠薪造成 `PAUSED` 时玩家要求裁员：先持久化不可逆终止意图并关闭 `automatic`，完成库存全额退料与零产出，再进入上述正常重配置事务。已发生债务保留。

### 3.4 欠薪暂停和恢复

- Payroll 对当前 `CompanyId+BuildingId` 的到期欠薪提供可信阻塞事实（仍保留 `UNAVAILABLE` 的 fail-closed 意义）。
- 欠薪时 ProductionExecution `pause()`，**不得直接 `terminate()`**；继续计薪。
- 当前公司对应建筑欠薪清零，且不存在其他暂停原因时，对同一 Batch `resume()`；不能恢复 `SETTLING_ABORTED`/`ABORTED`。
- 自动生产开关仅控制启动新 Batch；恢复已有 `PAUSED` Batch 是另一独立行为。

### 3.5 受控出售建筑

1. 防止新 Batch 提交，并关闭自动生产；若有正在运行/暂停 Batch，先持久化不可逆终止，再做 Inventory 幂等结算。
2. 按卖家旧雇佣人数将工资计入卖家债务，直至所有权交割 tick。
3. 同一数据库事务中更新 Building 所有者、清除卖方 Pending Staffing、建立买家未来工资应计起点。已有雇员继续占用地区供给；买卖双方历史工资记录分别保留。
4. 新公司必须自行启动生产，旧公司历史欠薪不得暂停新公司对应建筑。
5. 如果需要跨进程/重启完成这个多步流程，【建议】使用稳定的转让意图标识及最小持久化流程记录，不通过直接删除状态伪装完成。

### 3.6 受控拆除建筑

1. 停止新 Batch 并关闭自动生产；先终止并幂等结算未完 Batch。
2. 将当前公司工资应计推进至裁员/拆除生效 tick；按一个写事务释放 Staffing 所有雇员、删除运行时 Staffing、标记建筑墓碑（保留 ID、公司、地区），保留其历史工资负债/精度余数。
3. 因库存失败等不能安全拆除时，保持受控待拆除状态；不擅自释放预留或丢掉恢复记录。

### 3.7 SQLite 和缓存边界

- 已有 `SqliteTransactions.run(...)` 使用 JDBC `setAutoCommit(false)`/savepoint，在事务返回提交结果不明确时抛 `CommitOutcomeUnknownException`。**不能未经核验就认为它已经提供 `BEGIN IMMEDIATE` 语义**；需要实际确认 JDBC SQLite 驱动的起始事务和锁获取时机。
- 在世界单写者模型下继续采用 `ServerEconomyContext.economyLock`，但跨连接安全仍需数据库事务提供保证；事务开启后重新读权威数据，使用 revision CAS。失败重读，不能让缓存保留未提交变异。
- 生产库存的 Reservation / `ProductionJournal` / `StartIntent` 在验证已有原子事务和故障测试之前**不得直接删掉**。
- SQLite v2 当前包含设备表和全局 employment JSON；下一 Schema 版本允许重新设计。维持“不自动 DROP、DELETE 真实世界数据”的保护。若不做兼容迁移，应明确阻止旧世界加载及新世界要求。

## 4. 建议的应用服务与端口（语义契约）

```java
interface BuildingStaffingRepository {
    BuildingStaffing load(BuildingId buildingId);
    void create(BuildingStaffing initial);
    void save(BuildingStaffing changed, long expectedRevision);
}
interface RegionalLaborSupplyRepository {
    long totalSupply(RegionId region, OccupationId occupation);
    long employedTotal(RegionId region, OccupationId occupation); // 联合 SQL 聚合查询，非第二份状态
    void setSupply(RegionId region, OccupationId occupation, long supply);
}
interface PayrollAccrualPort {
    void accrueThrough(BuildingId building, CompanyId employer, long economicTick);
    PayrollBlockStatus blockStatus(BuildingId building, CompanyId currentOwner);
}
interface PayrollSettlementPort {
    PaymentResult payCompanyPeriod(CompanyId company, long periodId);
}
interface ProductionInventoryPort {
    void reserveFullInputs(BuildingId building, BatchId batch, Map<GoodId, Long> inputs);
    SettlementReceipt settleCompleted(BuildingId building, BatchId batch);
    SettlementReceipt settleAborted(BuildingId building, BatchId batch);
}
```

接口签名只是说明**唯一权威与命令意图**，并非可直接编译的最终代码。实体标识 `BuildingId`、`OccupationId`、`CompanyId` 是概念类型，不强制为现阶段立即创建全新的 Java 包装类（可暂用既有 `UUID/String`）。

协调服务建议：`ProductionService` 或较小 `ProductionBoundaryCoordinator`、`StaffingApplicationService`、`CompanyPayrollService`、`BuildingLifecycleService`。一个调用跨多个聚合时，由应用服务在共享 Unit of Work 内协调；各聚合只保护自身不变量。

## 5. SQLite 逻辑 Schema 草案

| 数据组 | 建议表 | 单一权威与约束 |
|---|---|---|
| 建筑 | `economic_buildings` | ID/类型/所有者/地区/运行状态/墓碑；外键所需历史 ID 不复用 |
| PM | `production_method_configurations` | effective/pending/version |
| Execution | `production_executions`, `production_batches` | Batch 唯一状态和结算/重配置屏障，避免重复状态存储 |
| 启动保护 | `production_start_intents`（待验证再简化） | 可靠判定预留与已提交 Batch |
| 人数 | `building_staffing`, `building_staffing_roles` | root revision、每职业 employed 与 nullable pending；**不保存地区已就业总量** |
| 区域供给 | `regional_labor_supply` | 地区职业整数总供给 |
| 工资应计 | `payroll_accruals` | 归属公司/建筑/地区/币种、tick 游标、整数分母余数 |
| 到期债务 | `payroll_liabilities` | 欠薪按原始债务人和受益地区留痕 |
| 付款幂等 | `payroll_payment_runs`, `payroll_payment_lines` | 公司+周期唯一；金额守恒、最大余数结果可重放/核验 |
| 居民预算 | `regional_household_budgets` | 仅预算划分；真实现金由账本单一权威维护 |

建议采用 `CHECK(employed >= 0)`、`CHECK(total_supply >= 0)`、工资非负约束、FK、CAS revision、`UNIQUE(company_id, period_id)`。`SUM(employed) <= total_supply` 属于跨多行约束，不能仅靠 `CHECK` 保护，必须置于可靠的写事务和应用层验证中。

## 6. 现有源码对照（2026-10-20 已核对）

| 当前代码路径或类 | 观察 | 重构策略 |
|---|---|---|
| `server/production/application/ProductionService.java` | 依赖设备服务、`WorkforcePort`、`BuildingPayrollPort`；欠薪 ARREARS 调用 `terminate()`；settle 传 progress；三个边界阶段 | 改为聚合 Staffing 快照与可靠 Payroll 阻塞，欠薪 `pause`，完整/终止分支结算，单重配置屏障 |
| `server/production/domain/ProductionExecution.java` | `Boundary.NONE/METHODS/EQUIPMENT/WORKFORCE`；终止时关 auto；保存不变快照 | 保留 Batch 状态与版本，缩减边界；终止语义保留 |
| `server/production/domain/ProductionBatch.java` | `FULL_SPEED_TICKS=1200` | 改 200，整数进度算法保留或优化 |
| `server/production/domain/ProductionBatchConfiguration.java` | 保存 installed/capacity、UUID 员工计划 | 删设备与 UUID，记录整数参产人数 |
| `server/production/domain/WorkforcePlan.java`、`WorkforcePlanningService.java` | UUID 列表、资格校验和设备参产上限 | 用只读取整数雇佣快照的无状态速度计算器代替 |
| `server/production/port/ProductionInventoryPort.java` | `Map<String,Double>` 和按 `double progress` 结算 | 改整数 Goods 和两种明确结算命令 |
| `server/storage/sqlite/EconomySchema.java` | Schema v2、`equipment_holdings`、`employment_state` | 新版本移除旧工作流表并加入 Staffing/Payroll 等真实权威表 |
| `server/ServerEconomyContext.java` | 连接 `EmploymentRegistry`、`ProductionEquipmentRegistry`；真实工资缺失时 fail-closed | 改注入真实 Staffing/Payroll；财务未实现前绝不伪造工资 CURRENT |
| `server/storage/sqlite/SqliteTransactions.java` | 共享连接 savepoint + 结果未知异常 | 复用并验证隔离语义；跨领域事务不能因数据库提交未知而误释放资源 |

当前 GitHub `main` 还未实现完整的 Company Finance 和真实工资账本。这里的 Payroll/Household 接口是**待实现设计**，不是现成可直接接入的功能。

## 7. 测试矩阵（必须覆盖）

1. 建筑/PM/Execution/Staffing 创建时原子初始化；重启恢复且无第二份权威状态。
2. 两建筑同地区同职业并发/顺序招聘，只按真实剩余供给成功；部分成功清除 Pending，无自动补招。
3. 进行中 Batch 接受待生效裁员或招聘，但速度快照与当前计薪基数不变；批次结束后事务生效。
4. PM 从高需求变低需求，自动裁员并缩小 Pending 上限；PM 生效及人数变化同一事务；失败整体回滚。
5. PM/Staffing 重配置失败，`RECONFIGURING` 未清除，不能偷跑旧 PM 的下一批；重启后重试一次成功。
6. 每职业至少 10% 才允许开工；零职业需求且产商品配方拒绝；设备状态不影响速度。
7. 满速 Batch 恰为 200 tick；速度 3/5 的 Batch 334 tick；没有 tick 加速或同 tick 新批二次计进度。
8. 整数原料足额预留；100% 完工全量消耗/产出；99% 终止全额退料/零产出。
9. Inventory 结算回执丢失、服务器崩溃、幂等重试，不得双重产出、返还或重复预留。
10. 欠薪 ARREARS 仅暂停对应建筑，保留进度/原料；工资清零自动恢复原 Batch。
11. 欠薪暂停时裁员需先正式终止、关闭 auto，结算完成后再释放员工；旧工资不消失。
12. 不生产/库存满/手动关闭自动开工期间仍计薪；中途裁员与工资率变更按 tick 分段累计。
13. 公司每个周期只付款一次；真实余额不足按建筑比例支付；最大余数并列 BuildingId；所有支付行之和等于真实扣款。
14. 订单资金被锁定时工资不可动用；工资账户扣款与各地区居民实际到账金额总和相等。
15. 居民储蓄和消费预算合计不超过可用真实现金；未消费额度结转不凭空增货币。
16. 地区供给降到已雇佣之下必须拒绝；拆除释放员工，墓碑及历史欠薪保留。
17. 出售运行中建筑：关 auto、终止旧 Batch、出售前工资归卖方；人数不变、清 Pending Staffing；出售后新工资归买方；旧欠薪不能阻塞买方建筑。
18. 退款、工资结算或出售事务提交结果未知时，恢复查询已提交事实，不重复执行。
19. 停止服务器后不追补停机经济 tick；TPS 降低只是经济进度放慢。

## 8. 分阶段 Codex 实施计划

**M0 — 基线审查**：先比对本地改动/当前 SHA、读取真实源码；运行现有 `test/build`；保留现有能力与世界数据安全保护；将旧文档标为 Superseded 而非擅自删除。

**M1 — Batch 与设备解耦**：删设备依赖、保留普通机械商品；改 Batch Configuration/速度快照/整数商品与退料结算；保留 Journal、StartIntent 的原恢复保证，新增故障测试。

**M2 — 汇总雇佣与区域供给**：独立 `BuildingStaffing` 及 Repository，有限供给事务查询与原子人数提交；删 UUID 任职、转岗、Reservation；重写 `WorkforcePlan`、Staffing 查询。

**M3 — 简化批次边界**：把 METHODS/EQUIPMENT/WORKFORCE 替换为单重配置屏障；原子处理 PM+Staffing，自动与人工开工统一检查屏障；处理同 tick 连续批次。

**M4 — 工资、公司账本和地区居民部门**：新增真实公司余额/预留能力、工资应计及负债、幂等分配付款、居民真实现金/预算；Payroll 未真实可用时保持 fail-closed。**不要为了让生产测试过而在正式服务端注入虚假工资状态**。

**M5 — 建筑出售、拆除与全面清理**：持久化可靠的生命周期意图、原子所有权切换、墓碑、欠薪历史；移除已无调用的旧设备、人事、协调类；完整重启/故障测试。

每一里程碑均执行相关 JUnit、SQLite 集成测试和 Gradle 构建；不得未经用户授权修改远程 GitHub 或破坏存档。

## 9. 尚未确认，不能直接作为实现决定

1. **出售建筑时卖方未生效的 Pending PM**：已明确要清除卖方 Pending Staffing，但还未明确 Pending PM 是否继承、取消或由买方复核。实施出售前需要单独决定。
2. **拆除或出售后不足一个最小货币单位的累计工资余数**：已确认不能转嫁至新公司且不能悄悄丢弃；如何在原公司账簿内汇总、实现未来支付，需要确定最后的精确清算规则。
3. **库存归属与出售资产范围**：出售建筑是否附带库存，以及原材料预留释放到谁的库存，由库存/资产所有权领域进一步定义，不可假定“建筑出售=库存自动转让”。
4. **多币种与居民账户接口**：这里仅定义按同一结算币种的分配，不擅自引入跨币种支付或汇率规则。
5. **原有经济世界数据的 Schema v2→v3 兼容策略**：此前已批准不迁移更老世界，但未自动授权清空当前有效存档；需要依据实际数据条件决定新世界初始化或非破坏迁移。
6. **可靠转让/拆除意图的具体存档位置**：可采用 `building_lifecycle_operations` 或等价单一权威方案，经源码审查再定，避免无谓 Saga 框架。

---

**结论**：聚合边界先不合并；生产核心只保留 PM 和 Batch，劳动力改为建筑整数人数，设备彻底退出。重配置以一个可靠的持久化屏障和一个 SQLite 原子事务替代三个旧阶段。Payroll/Household 独立完成真实资金记账，且现有源码暂无正式实现，需分阶段落地。
