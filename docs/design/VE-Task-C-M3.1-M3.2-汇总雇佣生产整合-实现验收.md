# 任务 C：M3.1 / M3.2 汇总雇佣与生产整合——实现验收

日期：2026-10-21。范围仅为任务 C；工资付款、居民预算、出售与拆除不在本次实现中。

## 1. 基线与工作区保护

- 实际起点 HEAD：`3f35cd0d01044508a30755605eeec8af1bcb5757`，与用户提供的任务 B 基线一致。
- 分支：`refactor/m1-0-sqlite-transaction-cleanup`；开始任务时工作区干净。
- 用户重置会话后重新核对：HEAD 和分支未变，本轮修改仍在。没有执行 Git commit、push、merge、reset，也没有覆盖其他用户修改。
- 所有数据库行为测试使用 JUnit 临时文件。没有运行 Minecraft 服务端，没有打开真实存档的 SQLite 连接。
- 现有真实世界 `run/saves/新的世界/victoria_economics/economy.sqlite` 仅做只读 SHA-256 核对；前后均为 `3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`。核对时没有该存档的 WAL 文件。

依据为 M0 工作包、DDD 草案中已确认规则、任务 A 与 B 的完整验收报告。未补造身份、工资率、币种、现金、人口或 `pig_iron_tools` 的职业需求。

## 2. C1～C5 实施结果

| 内部阶段 | 实际结果 |
|---|---|
| C1 汇总就业权威 | 正式生产读取 BuildingStaffing，冻结职业整数人数及精确速度；删除旧 NPC Employment、资格与保护运行链。 |
| C2 单一屏障 | `NONE / RECONFIGURING` 取代三个旧阶段；有效 PM、人数、工资基数与屏障在同一物理写事务提交。 |
| C3 工资阻塞 | 真实工资查询正式接入；欠薪只暂停，多个暂停原因独立保存；欠薪裁员先决定中止和退料，再变更人数。 |
| C4 连续批次与 Tick | 完工当 Tick 可以启动下一批且进度为零；每建筑持久检查点防重复推进；未完成事实阻止分配后续 Tick。 |
| C5 创建、格式与验证 | 四根创建扩展为包含真实工资起点；Schema/Codec v7；旧 v1～v6 拒绝；真实 SQLite、并发和故障测试及统一构建。 |

## 3. 聚合与唯一权威

| 领域 | 保存的权威 | 边界 |
|---|---|---|
| EconomicBuilding | 身份、公司、地区、生命周期、运行控制、Identity Revision | 不持有 Staffing、PM、库存或债务副本。 |
| ProductionMethodConfiguration | 有效 PM、Pending PM、配置及生效版本 | 岗位容量仍来自解析后的有效 PM。 |
| BuildingStaffing | 每职业实际人数、可选 Pending 目标、Revision | 唯一有效就业人数权威，没有 NPC 或工资金额。 |
| RegionalLaborSupply | 地区职业总供给及 Revision | occupied 从持久 Staffing 求和，available 由相减得到，无可写缓存。 |
| ProductionExecution | 当前/最近 Batch、自动生产开关、屏障、暂停原因、Tick 检查点 | Batch 的人数是历史冻结快照，不是第二份可写就业状态。 |
| Payroll | 原公司/建筑/地区/币种工资段、基数历史、游标、余数、到期债务 | 工资人数基数是计薪历史；变更必须与 Staffing 同事务，不能反向作为实际雇佣权威。 |
| GoodsInventory | 数量、Reservation、完整结算回执和 Revision | 不复制进 Execution；仍为整数商品唯一权威。 |
| EconomicTime | accepted Tick、工资游标及生产恢复证据 | 不使用墙钟，不补离线时间。 |
| Finance | 真实余额、资金预留、账本 | 沿用任务 B，本轮没有付款、注资或第二余额。 |

WorkforcePlan 保存不可变 `Map<String,Integer> participatingWorkers` 与整数 `speedNumerator / speedDenominator`。逐职业验证 `0 < n <= N` 和 `10 × n >= N`，速度为最小 `n/N` 并约分；交叉乘法使用精确整数运算。Double 仅用于显示。满速 200 Tick、3/5 第 334 Tick 完成的合同保持。

运行中申请、替换或取消 Pending 不改变有效人数、工资基数或旧 Batch。工资率变化通过真实历史段影响未来应计，不会重写 Batch。默认 `pig_iron_tools` 缺职业需求的问题仍由合法配方校验拒绝，没有编造岗位。

## 4. 正式调用链与共享事务

正式装配为：

`ServerEconomyContext → ProductionService / StaffingApplicationService → ProductionBoundaryCoordinator → 现有 Repository 的 WriteSession → SqliteTransactions.write`。

ProductionService 负责 Journal、库存资源协议和每建筑 Tick 流程；协调器负责跨聚合原子修改；领域对象负责状态约束。Repository 仍然各自独立。未创建通用事务管理器或万能 Repository。

### 4.1 为何使用现有 WriteSession

旧 EconomyUnitOfWork 面向 PM/Execution，并将 PM 的保存推迟到回调结束；人数仓储的容量验证需要在本事务中读取新有效 PM。直接机械扩展其旧保存顺序会在验证时读到旧容量。因此本轮复用它下层既有的显式 `SqliteTransactions.WriteSession`，在一个物理事务中按顺序调用各仓储，不引入第二种事务框架，也未改动原 UoW 提交算法。

新增最小能力：Execution 的显式事务/快照读取；Payroll 的当前建筑工资段和地区币种查询；Inventory 的只读事务能力，用于最终开工复核。Inventory 会话不能进行预留、结算或保存，避免形成另一条库存写入路径。

### 4.2 RECONFIGURING

库存完整结算回执确认后，Execution 保存结束 Batch 和 `RECONFIGURING`。如果库存已经提交而确认回执丢失，仍按原 BatchId、完整结算命令核验并重试，不再次产出商品。

重配置在取得 IMMEDIATE 写权限后执行：

1. 从数据库重新读取现存 Building、PM、Execution、Staffing、地区供给、当前工资段和 accepted Tick；未决 StartIntent 阻止重配置。
2. 校验真实地区工资币种，将**变更前**人数的工资精确累计到 accepted Tick。
3. 应用 Pending PM；按新岗位容量计算超额裁员和 Pending 收缩。
4. 用 `totalSupply - SUM(employed)` 加上本建筑将释放的人数，规划每职业 Pending；招聘最多达到目标、岗位容量与真实可用供给。
5. 部分招聘成功后也消费整个本次 Pending，不保留持续补招任务。目标 0 与取消仍是不同操作。
6. 保存 PM，再保存人数；人数总量变化时更新工资基数和不可追溯修改的基数历史。地区平均工资不按职业增加差价。
7. 保存清除屏障后的 Execution，最后物理提交。

上述任一点失败，PM、Staffing、工资变化和屏障清除一起回滚。事务内对象只作 detached 工作图，提交前不发布；协调器成功后由正式 committed load 取得结果。Repository Savepoint 成功不会提前 acknowledge 外层提交。

空闲 PM 或 Staffing 生效使用相同协调事务；没有“先提交 PM 再改人数”的窗口。运行中的 Batch 只保存 Pending。屏障存在时禁止新 Batch。

### 4.3 开工协议

`权威检查 → 持久 StartIntent → 整批库存预留 → 写事务内最终复核 → 保存 Batch → 清理 StartIntent`。

最终复核包含当前 Building、PM、Staffing、真实工资状态、库存状态、完整 Reservation、Execution Revision 和自动生产开关。预留期间权威条件发生变化则拒绝并按 Journal 补偿；解除预留失败保留意图等待恢复。已提交 Batch 的清理失败不会误作开工失败退料。

## 5. M1 事务保护与版本发布

- 使用既有 sqlite-jdbc `3.46.1.0` 与 `SqliteTransactions.write` 的受保护 IMMEDIATE 机制，在权威读取前取得写权限；不发原生 `BEGIN IMMEDIATE`，不 unwrap 驱动，不把普通 DEFERRED 查询升级为写锁。
- `requireCommittedReads` 保留，事务内读取必须显式提供当前连接的会话；没有 ThreadLocal 或全局重入开关。
- Revision/CAS、批次唯一约束、完整命令幂等、库存 CAS 均保留。
- Coordinator 的物理提交回执未知时，调用既有 Guard 隔离连接，禁止后续权威读写及派生 JDBC 句柄使用。仅将 `SqliteConnectionGuard.quarantine` 的可见性扩展为 public 供应用流程复用，隔离算法未改。
- 不依据异常猜测提交成功或失败，也不在同一连接自动再次提交；从新可信连接校验整个持久图，再按屏障、StartIntent、回执和检查点恢复。
- rollback / savepoint 失败仍阻止不可信外层提交；失败路径不通过 finally/reset 意外提交。隔离标记先于 close，close 失败也不能重新使用句柄。
- 本轮没有修改 `SqliteTransactions` 或原 `SqliteEconomyUnitOfWork` 的事务算法。原 26 项事务故障测试与 29 项 UoW 回归继续保留。

## 6. 欠薪暂停、恢复与受控裁员

正式生产从 `SqliteBuildingPayrollQuery` 读取当前 CompanyId + BuildingId 的持久事实：

| 状态 | 正式行为 |
|---|---|
| CURRENT | 其他原因解除时可以推进/恢复原 Batch；不要求假定现金充足。 |
| ARREARS | 加入 PAYROLL_ARREARS，保存 PAUSED；不终止、不退预留、不丢进度。 |
| RECOVERY | 加入 PAYROLL_RECOVERY，等待可信时间和工资游标；不当作 CURRENT。 |
| UNAVAILABLE | 加入 PAYROLL_UNAVAILABLE；缺身份、登记或工资配置时拒绝开工。 |

暂停原因以独立集合持久保存，包括 MANUAL。重新核验工资时只替换工资原因，不清除手动原因。`automatic=false` 控制未来开工，不阻止当前原 Batch 在其他暂停原因全部解除后恢复。旧公司债务仍留在旧工资段，查询不以建筑当前所有者重写历史责任。

欠薪时请求降低实际人数：先同事务保存 Pending、不可逆 SETTLING_ABORTED 决定及关闭 automatic；随后完成 ABORTED 全额退料、零产出；最后在重配置事务中截止旧人数工资、改变 Staffing 和工资基数。结算失败时人数不变。已发生欠薪继续存在。已决定 COMPLETED 的结算不能被 ABORTED 覆盖。

**实际付款留待任务 D。** 本轮 paid 仍为 0，没有现金扣款、工资付款分配、伪造清偿或付款命令。测试分别验证真实到期欠薪保留、手动原因与欠薪并存、真实工资游标恢复，以及领域原因独立清除；没有把纯领域清除原因冒充正式发薪闭环。实际付款后欠薪归零再自动恢复的端到端验收，需任务 D 的真实资金与债务合同才能完成。

## 7. 连续 Batch 与持久 Tick 恢复

### 7.1 每建筑检查点

Execution 新增：

- `last_completed_tick`：该建筑已经完成当前 Tick 的资源/边界流程。
- `processing_tick`：已经提交该 Tick 的推进决定，后续步骤仍可能未完成；只允许为 `last_completed_tick + 1`。

一次 `advance` 在同一写事务内核验 accepted Tick，更新工资暂停原因，最多推进旧 Batch 一次，同时保存 processing 标记。重复调用看见该标记时不会再次推进，只继续库存结算、重配置和开工协议。完成后另一个 CAS 事务推进 completed 并清除 processing。

新 Batch 在旧 Batch 完成的同 Tick 可以开工，保存时进度为零；该 Tick 的 processing 标记已经存在，恢复也不能推进它。手动开工若发生在“Tick 已接受、回调尚未处理”之间，会在开工事务一起保存该 Tick 的 processing 标记，证明本次推进决定此前没有旧 Batch；之后回调也不能推进出生于本 Tick 的新 Batch。

仓储拒绝检查点倒退、跳 Tick 或丢失未完成标记；数据库约束和启动预检同时校验这些事实。

### 7.2 全局时间与失败

Clock 在接受新 Tick 前先恢复已接受 Tick；`acceptNext` 还在 IMMEDIATE 事务内验证：旧工资游标已完成、所有建筑不落后、没有 processing、屏障、SETTLING 或 StartIntent。另一个连接不能趁恢复尚未完成继续分配 Tick。

ProductionService 不再吞掉某建筑失败并宣布整轮成功。其他已提交建筑可以完成自己的检查点，但本轮失败会向 Clock 抛出，Clock 停止分配后续 Tick。重试只补完未完成事实，成功建筑不重复推进。

全局确认再次查询所有建筑、工资游标、未决结算和 StartIntent，确认后才清除 `first_uncertain_production_tick` 并推进回调回执。方法返回本身不是业务完成依据。手动中止即使发生在 completed 检查点之后，未完成的 SETTLING 仍阻止宣布恢复完成。

重启按持久游标恢复，不分配新的周期 ID，不补离线 Tick，不依据墙钟猜测结果。进度和推进证据的物理原子性实现每建筑 accepted Tick 的至多一次推进及可靠重试；库存、Journal、屏障和下一批开工仍是多个幂等事务，**不是一个覆盖全世界所有业务的单一 exactly-once 事务**。无法确定的提交保留恢复事实，必须先用可信连接核验。

## 8. 新建筑与工资起点

ProductionBuildingInitializer 在既有一个物理写事务中建立 Building、PM、Execution、空 BuildingStaffing，再建立当前 accepted Tick 起始的 PayrollAccrual 和人数历史。初始 Execution 的 completed Tick 等于创建 Tick，避免重放建筑出生以前的生产。

工资币种来自该地区已明确配置且已生效的唯一工资币种，身份由目录/FK 校验；没有当前工资率或币种则整笔创建失败。空人数基数为 0 不代表默认工资为 0，也没有分配现金或人口。工资段 ID 与建筑 ID 稳定关联，用于未知提交后核验，不在异常重试时自动重建。

物理提交确认前不发布到 BuildingRegistry；回执未知保持 BuildingCreationUnconfirmed，使用新可信连接经过格式和工资图验证后才允许发布。第五根及其历史写入失败均与原四根一起回滚。

正式 `createBuilding(type)` 仍因缺玩家公司/地区身份来源明确拒绝。管理员 Staffing/Pause 命令只作用于已有且具备真实配置的建筑，不创建默认身份、资金、工资或供给。

## 9. Schema / Codec v7 与旧世界保护

v7 是真实不兼容版本：

1. 删除 `employment_state`，工资运行权威只允许 BUILDING_STAFFING。
2. Batch envelope 为 `formatVersion=7`，保留 `fullSpeedTicks=200`；参与快照为职业→整数人数，旧 employees/UUID 数组字段拒绝。
3. Execution 使用 NONE/RECONFIGURING，保存暂停原因和每建筑 completed/processing Tick；不再有三阶段边界及旧阶段版本字段。
4. 整数库存、完整完成/中止回执、财务及工资原有业务合同保持，当前 envelope 统一标记 v7；不接受 v6 JSON 冒用新结构。
5. 新增独立字面量 `schema-v7.sql` 测试夹具，保留历史 v2～v6 夹具原样用于拒绝测试。

复用原 READONLY 预检和通过后的受控可写复检，没有迁移或修复入口。预检核验精确 DDL、FK、四根及当前工资登记、工资历史/人数基数、地区供给求和、Batch 与实际 Staffing、暂停原因/状态、屏障、Tick 范围等；不一致时在业务写入前拒绝。

旧 v1～v6、未知版本、缺表/混合结构、旧 NPC 快照及篡改的新图均拒绝。测试核对拒绝前后主文件和历史 WAL 字节，不清空、不覆盖、不转换旧 NPC 或设备数据。未来付款合同若改变布局或格式，仍须管理兼容版本，不能在 v7 静默保存不兼容状态。

## 10. 测试矩阵与完整构建

| 验收范围 | 证据 |
|---|---|
| 正式人数、10%、精确速度、200/334 Tick | WorkforceContractTest、ProductionDomainTest、StaffingQueryTest；没有旧 Employment 表或生产适配器。 |
| 冻结与 Pending | PM/Staffing 运行中替换、取消，真实工资率变更后原 Batch 不变；初始人数及 0 Pending 重启恢复。 |
| 招聘和收缩 | 部分招聘消费一次、增加供给不自动补招；PM 缩岗、Pending 缩减、旧工资 cutoff 与新基数同事务。 |
| 屏障回滚 | BoundaryRecoveryTest 在 PM、Staffing、Payroll、人数历史、Execution 五个写点故障；空闲 PM 四个写点故障。 |
| 并发 | 两个真实 JDBC 连接竞争同 accepted Tick、开工和地区供给；12 人供给下两笔目标 8 得到 8+4，工资总基数同步。 |
| 写锁时机 | DELETE / WAL 两种模式，在首次 Building 权威 SELECT 前由第二连接尝试更新供给，真实返回 SQLITE_BUSY(5)。 |
| 工资暂停 | 真实 1200 Tick 形成 14 单位未付债务，原 Batch/预留不丢；RECOVERY/UNAVAILABLE 不放行；独立原因及 automatic 规则。 |
| 欠薪裁员 | 库存结算失败时人数不变；重试 ABORTED 后才调整人数，原 14 单位债务保留，无部分商品。 |
| 连续及重启 | 第 200 Tick 新批进度 0、第 201 Tick 为 1；334 Tick 慢批；推进回执丢失、开工后 finish 失败与双连接重复回调不多推进。 |
| 接受与出生 Tick | 未完成 accepted Tick 阻止下一分配；手动开工与回调交错，新 Batch 出生 Tick 不推进；手动中止未结算不假报恢复成功。 |
| 未知提交和隔离 | 重配置 commit 前/后回执丢失，fresh connection 只见旧图或完整新图；旧连接及派生 Statement 禁用。 |
| rollback / close 失败 | 部分 PM 已写后人数历史失败，同时 rollback/close 失败：无 commit/reset，观察连接仅见原图，可信重启可恢复。 |
| 开工与库存恢复 | StartIntent、部分预留、失败释放、最终复核、清理失败、结算回执丢失、完整命令冲突、普通容量与已承诺超容。 |
| 创建 | 四根及工资登记/历史任一点失败整体回滚；未配置工资拒绝；未知提交不发布未经核验的 Building。 |
| 格式 | v7 正常只读加载；v1～v6/未知/旧 UUID/工资人数不一致/非法暂停和屏障拒绝；主文件及 WAL 字节保护。 |
| M1/A/B 回归 | M1.0 26 项、UoW 29 项、身份/供给/人数 CAS、Finance 守恒与预留、工资精度/余数/周期/历史全部适用回归。 |

没有删除测试类来获取全绿结果。原 NPC/三阶段专属断言按新合同重写；库存、Batch、StartIntent、Journal、未知结果、事务和工资保护保留或由更完整的跨根测试替代。明确的隔离持久化夹具只在 src/test，不成为正式人数、现金或工资率来源。

执行用户指定命令：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：**BUILD SUCCESSFUL in 3m 11s**，9 个任务全部实际执行。最终 JUnit XML 为 **28 类、444 项，0 failure、0 error、0 skipped**。M1.0 的 26 项与 UoW 的 29 项均包含在本次完整运行中。

| 测试类 | 数量 |
|---|---:|
| BoundaryRecoveryTest | 14 |
| BuildingIdentityPersistenceTest | 12 |
| BuildingProductionIsolationTest | 5 |
| BuildingStaffingTest | 9 |
| EconomicTimePersistenceTest | 8 |
| EconomyUnitOfWorkTest | 29 |
| FinancePersistenceTest | 14 |
| FinancialCompatibilityTest | 14 |
| FinancialTransactionSafetyTest | 10 |
| GoodsInventoryTest | 4 |
| LaborTransactionSafetyTest | 17 |
| PayrollAccrualDomainTest | 8 |
| PayrollPersistenceTest | 26 |
| ProductionConcurrencyTest | 8 |
| ProductionDomainTest | 17 |
| ProductionIntegrationTest | 19 |
| ProductionRepositoryTest | 11 |
| ProductionSchemaContractTest | 13 |
| ReconfigurationTransactionSafetyTest | 4 |
| RecoveryAndControlsTest | 13 |
| SchemaCompatibilityTest | 117 |
| SqliteInventoryConcurrencyTest | 14 |
| SqliteInventoryContractTest | 5 |
| SqliteTransactionsTest | 26 |
| StaffingQueryTest | 4 |
| StaffingRepositoryTest | 8 |
| StartCommitRecoveryTest | 11 |
| WorkforceContractTest | 4 |

构建后的 `victoria_economics-0.1.1-1.21.1.jar` 检查：已删除的 9 类为 0 个条目，测试夹具/JdbcFaults 为 0 个条目，新的协调器、WorkforcePlan 和 StaffingApplicationService 均已打包。主源码旧 Employment/Qualification/JobReservation/三阶段运行入口扫描为 0。`git diff --check` 通过。

完整输出位于 `build/task-c-validation.log`，JUnit XML 位于 `build/test-results/test`，HTML 位于 `build/reports/tests/test/index.html`。本轮未降低 SQLite durability；没有进行 Minecraft 整服或大规模建筑负载测试，JUnit 时长不能作为真实服务器容量承诺。

## 11. 未完成入口、风险及任务 D 前置条件

- 真实工资付款、最大余数分配、居民预算均未实现；只有真实已到期未付债务。任务 D 需把资金扣转、账本、付款回执和债务减少放在一个可核验事务中，再完成“付款后恢复原 Batch”的端到端验证。
- paid 当前仍为 0，不能通过测试 SQL 或默认 CURRENT 开放正式清偿。历史不足最小单位的余数继续保留；出售/拆除的最终处理规则不在本轮推定。
- 公司、地区、币种、地区工资率和总供给仍需明确配置与身份授权；没有这些事实的创建/开工保持 fail-closed。没有新增任意注资或人口生成命令。
- 未知提交隔离后需要重建可信 Context/连接并核验；未实现运行中自动换连接。持久检查点支持重启恢复，不宣称无中断的全世界原子执行。
- STOPPED/DISABLED 沿用阻止新开工的既有控制语义；已有 Batch 不因该控制被隐式中止。暂停由明确原因管理，没有借本轮改变出售、拆除或其他控制规则。
- 每 Tick 有全局时间/工资及每建筑多笔安全写事务，成本随建筑数和历史数据增加。未以缓存游标、异步降持久性或批量猜测推进换取性能；仍需在隔离性能世界评估整服规模。
- `pig_iron_tools` 仍缺正式职业数据，不放宽无职业产出配方规则。

任务 D 可以依赖：唯一 Staffing、可信 accepted Tick、不可回退工资游标、精确历史工资债务、真实 Finance 可用余额和预留、独立暂停原因、可重试 RECONFIGURING 与每建筑检查点。接入付款后仍必须验证跨公司历史债务隔离、多原因恢复和未知回执，不能直接把现金充足等同于 CURRENT。

## 12. 完整文件清单

修改原因按职责分组：生产应用/领域/仓储用于汇总快照、屏障和 Tick；Payroll 用于显式事务查询与正式装配；Inventory 仅增加最终复核的只读能力；Schema/Codec 用于 v7 门禁；Context/Commands 切换正式入口；package-info 与注释同步职责；测试与夹具更新版本、保留旧格式拒绝并加入跨根故障/并发验证。

相对基线共 71 个文件：新增 7，修改 55，删除 9。下列路径均相对仓库根。  ### 新增  ```text
docs/design/VE-Task-C-M3.1-M3.2-汇总雇佣生产整合-实现验收.md
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBoundaryCoordinator.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionStartBlocked.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionConcurrencyTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionSchemaContractTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ReconfigurationTransactionSafetyTest.java
src/test/resources/fixtures/sqlite/schema-v7.sql
```

### 修改

```text
src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java
src/main/java/com/bbmurloc/victoriaeconomics/server/command/ProductionCommands.java
src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryStore.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/application/PayrollAccrualService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqliteBuildingPayrollQuery.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqlitePayrollRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/EconomicClock.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBuildingInitializer.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingCalculator.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingSnapshot.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionBatchConfiguration.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionExecution.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/WorkforcePlan.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/WorkforcePlanningService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionExecutionRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/port/ProductionInventoryPort.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyFinancialSchema.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyFinancialValidation.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteConnectionGuard.java
src/main/java/com/bbmurloc/victoriaeconomics/server/time/infrastructure/SqliteEconomicTimeRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/time/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/application/StaffingApplicationService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/application/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/BuildingStaffing.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/StaffingTransition.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/package-info.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/SqliteStaffingQuery.java
src/test/java/com/bbmurloc/victoriaeconomics/server/TestIdentities.java
src/test/java/com/bbmurloc/victoriaeconomics/server/building/BuildingIdentityPersistenceTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/EconomicTimePersistenceTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialCompatibilityTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialTestWorld.java
src/test/java/com/bbmurloc/victoriaeconomics/server/financial/PayrollPersistenceTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryContractTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BoundaryRecoveryTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionIntegrationTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/RecoveryAndControlsTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingQueryTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/StartCommitRecoveryTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/TestEconomy.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/WorkforceContractTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionDomainTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/ProductionRepositoryTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyUnitOfWorkTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/LaborTestWorld.java
src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/LaborTransactionSafetyTest.java
src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/StaffingRepositoryTest.java
```

### 删除

```text
src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/EmploymentFact.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/RepositoryProductionFacts.java
src/main/java/com/bbmurloc/victoriaeconomics/server/production/port/WorkforcePort.java
src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteEmploymentRepository.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/employment/EmploymentRecord.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/employment/EmploymentRegistry.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/employment/EmploymentService.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/employment/JobReservation.java
src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/employment/ProductionEmploymentPort.java
```

本任务结束后停留在工作区等待源码审查，不自动开始任务 D/E，不自动提交或推送。
