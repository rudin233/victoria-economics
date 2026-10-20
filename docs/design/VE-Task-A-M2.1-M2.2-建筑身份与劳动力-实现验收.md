# 合并任务 A：M2.1 + M2.2 建筑身份与劳动力实现验收

日期：2026-10-20。状态：A1～A5 连续实施及本地验收完成，等待统一源码审查。没有 commit、push、merge 或 reset。

## 1. 基线与实施边界

开始时工作区干净，分支为 `refactor/m1-0-sqlite-transaction-cleanup`，实际 HEAD 为 `4d16cfd3e0c0c2ed7da89fd924ddf813959eb8e0`，与用户参考提交一致；结束时 HEAD 与分支未变。没有覆盖未知用户修改。

已阅读 M0 分阶段源码审查计划、Production / Labor / Payroll DDD 草案及 M1.4 验收报告。以用户本次明确授权和草案已确认规则为准。本轮合并原 M2.1/M2.2，未实施 M3、M4、M5。

交付的是显式身份、独立 Staffing/Supply 的完整领域和持久化基础、Pending 应用命令、只读查询及可靠事务验证。**正式招聘、裁员、调配和新就业权威切换尚未开放**。没有假 Payroll、假时钟、默认公司/地区、初始现金或人口；也没有从旧 NPC 数据填充新 Staffing。

## 2. A1～A5 实施结果

| 内部阶段 | 实现及不变量 | 验证 |
|---|---|---|
| A1 身份与领域 | CompanyId/RegionId 为规范 UUID VO；公司/地区目录只持有身份。Building 为不可变身份检查点，运行控制和生命周期分离。 | 身份唯一性、FK、无身份来源拒绝、不可覆盖 ID、CAS、墓碑不物理删除。 |
| A2 持久化与事务 | Building/PM/Execution/Staffing 四根同一 IMMEDIATE 事务创建；Staffing/Supply 规范化表及 CAS；occupied 只派生。 | 四个插入点逐一故障、未知提交、清理/Savepoint/回滚失败、重启、双连接写锁和 CAS。 |
| A3 领域与 Pending | 每职业一个 Pending，区分缺失/0/正整数；纯规划支持 PM 收缩、裁员、部分招聘及一次性消费。应用只持久 Pending。 | 纯领域 9 项；实际人数生效命令明确拒绝；库存及旧 NPC 不双写。 |
| A4 查询整理 | 查询来自独立 Staffing、有效 PM、同区域持久就业和供给；删除旧 HR 默认比例目标。 | Pending/真实人数区分、供给未配置显式表示、DEFERRED 一致快照、并发写不被 IMMEDIATE 读锁阻止。 |
| A5 综合验收 | v5 门禁、全部适用生产回归、临时文件 SQLite、并发与真实 JDBC 故障注入。 | 19 类 314 项全部通过；指定完整 build 成功。 |

实施过程中已分别运行生产领域/建筑隔离/UoW 回归、新领域与存储测试、新并发与故障测试、查询和兼容测试，再统一执行完整构建；没有在 A1/A2 后提前停止。

## 3. 聚合边界与权威

| 聚合 / 模型 | 权威与职责 | 不持有的内容 |
|---|---|---|
| Company / Region | 不同类型的 UUID 身份及持久目录；显式创建、唯一性和 FK 引用。 | 玩家自动归属、资金、工资、默认人口。 |
| EconomicBuilding | id、类型、ownerCompanyId、regionId、lifecycle、status、identityRevision。 | PM、Batch、Staffing、Inventory、Payroll 的权威副本。 |
| ProductionMethodConfiguration | 继续独立管理有效/待生效 PM 及版本，提供岗位容量。 | Staffing 人数的副本。 |
| ProductionExecution | 继续管理冻结 NPC Batch、进度、结算与三阶段边界。 | 新 Staffing 或区域供给余额。 |
| BuildingStaffing | buildingId、employed 职业整数映射、pending 职业整数映射、revision。当前为独立 staged 模型。 | NPC UUID、地区可用量、现金/债务、PM 容量、Execution 状态。 |
| RegionalLaborSupply | RegionId + OccupationId 对应 totalSupply 及 revision。 | 可写 occupied/available 缓存。 |
| 旧 Employment | 当前正式生产仍依赖的真实 NPC 任职、资格、岗位预留和参产保护。 | 不与新 Staffing 双写，不从新查询补造 NPC。 |

CompanyId/RegionId 构造要求非空 UUID，字符串解析要求规范形式；数据库目录唯一性和 FK 再检查身份存在。Occupation 引用当前定义和持久 occupation_catalog，未知职业不能作为供给或 Staffing 写入；目录注册只登记定义身份，不建立任何供给。若持久目录包含当前定义无法识别的职业，拒绝隐式转换。

BuildingStatus 仍为 ACTIVE/STOPPED/DISABLED。新增 BuildingLifecycle 为 EXISTING/DEMOLISHED，用于身份存续，含义与是否停产独立。没有新增出售/拆除业务命令；身份更新接口拒绝偷偷改变所有者、地区、类型或生命周期。历史身份不能物理删除，也不能作为运行身份更新或开工。

## 4. Building 仓储、初始化与发布

BuildingRepository 原 upsert 已替换为显式 create、load/loadAll 和 `save(changed, expectedRevision)`。create 是 INSERT，重复 ID 报错；更新使用 identity_revision CAS，只修改当前允许的运行状态，不触及其他根。immutable `withStatus` 生成一个新的工作 revision，`Math.incrementExact` 拒绝溢出。保存失败不改变 Registry 中的对象。

`BuildingService.createBuilding(type)` 明确拒绝：缺少真实公司和地区来源，不能猜测身份。接受显式 CompanyId/RegionId 的创建 API 仍需通过数据库真实目录 FK。既有 Minecraft debug create 因此保持门禁；本轮未提供玩家身份分配或默认目录记录。

审查结果：原 EconomyUnitOfWork 的 PM/Execution Session 主要用于已存在根的 staged 保存，不适合机械套用四根初始化。采用最小复用方案：保留原 UoW 的行为，ProductionBuildingInitializer 直接拥有一次 `SqliteTransactions.write` 的物理事务，并使用明确的 Building/Staffing 写会话。

初始化顺序为：

1. 解析当前建筑类型和默认合法 PM。
2. 取得 IMMEDIATE 写权限，然后 INSERT Building；真实目录 FK 在事务内检查。
3. 创建 ProductionMethodConfiguration 和 ProductionExecution。
4. 创建空 BuildingStaffing（revision=0，实际人数/Pending 为空）。
5. 仅在物理 commit **和 JDBC 清理**均确认成功、initializer 正常返回之后，BuildingService 才加入 BuildingRegistry。

PM/Execution 的旧 create 可在该事务内使用受保护 Savepoint；Savepoint 成功没有被当成物理提交，没有在内层发布 Building 或递增一个“已提交”修订。任何一步失败均由外层整笔回滚。各根创建仍使用自己的仓储，没有万能 Repository。

结果未知时，BuildingService 抛出 BuildingCreationUnconfirmed，携带稳定的 immutable candidate 身份用于核验；不发布 Registry、不自动重试、不换新 ID 再插入。`verifyCreation(candidate)` 通过专门工厂打开新可信连接，经过格式门禁，再在同一读取中核对 Building 和三个伴随根及身份归属；部分图或身份不符拒绝。确认持久存在后才发布；确认不存在则返回 empty，仍不自动创建。

正式数据库工厂通过 EconomyDatabase 保存的文件路径建立新连接；不尝试从已隔离连接恢复权威。正常启动的 Registry 也只从已通过门禁的持久身份加载。

## 5. StaffingTransition 与持久状态

每职业人数是非负 int；revision 与区域总量/汇总是 long。输入映射复制为不可变快照，employed 中的 0 规范为缺失，Pending 的 0 必须保留。SQL pending_target 的 NULL 与 0 分别对应无请求和显式归零。

| 行为 | 规则 |
|---|---|
| requestTarget | 目标在 0～有效 PM 岗位上限内，职业必须存在于该 PM；覆盖同职业旧请求，不改实际人数。 |
| cancel | 删除该职业 Pending；不表示目标 0，也不改实际人数。 |
| forCapacity | 将超额实际人数压至新上限，计算应裁减量；Pending 收缩至新上限，移除职业收缩为 0；不招聘、不修改旧根。 |
| consumePending | 在纯计划中先约束岗位，再按目标、缺口、可用供给决定结果；成功处理一次后清空本次 Pending，包括供给为 0 的部分成功/无增员结果。 |
| apply | 只接受同 buildingId、同 before 状态/版本的明确 transition，返回新 immutable 工作状态。此领域操作不证明工资已应计。 |

对于当前人数 e、收缩后的目标 t 和可用供给 a：

- t≤e：计划结果 t，应裁员 e−t。
- t>e：增加 `min(t−e, a)`，结果不会超过岗位和目标；Pending 被消费。
- 没有 Pending 的职业不招聘；供给随后增加不会自动生成新的目标或补招任务。

计数差额、增加、revision 和 long 汇总使用 exact 运算/范围验证；没有把 SQL REAL、旧 Double 或溢出数据截成合法人数。Staffing 保存会在写会话里重新加载身份、PM、原人数及区域占用，再做 revision CAS 与完整角色行保存。中途 SQL 异常会阻止整个外层提交。

PM 降级的 `forCapacity` 只是规划，本轮没有把新 PM 生效、实际 Staffing 裁减拆成独立正式提交；它们仍需未来 M3.1 与真实工资应计共同事务。旧 Batch 仍持有原快照。

## 6. 区域约束与查询一致性

`occupied(region, role) = SUM(building_staffing_roles.employed)`，按 Building 的真实持久 RegionId 连接查询；不同地区/职业隔离。Pending 不参加 SUM。仓储逐行使用 Math.addExact 做 long 汇总；启动验证的 SQLite 整数 SUM 溢出同样是失败，不退化为浮点近似。

写入 Staffing 时，在 IMMEDIATE 事务内重新计算其他建筑占用，加本建筑新人数，要求不超过该区域职业的持久总供给。降低 totalSupply 时同样先在事务内读取实际占用，低于已就业人数则拒绝。所有修改都有独立 revision/CAS。没有 occupied/available 表、可写缓存或跨连接读完后再开写事务的窗口。

未配置供给不隐式生成人口：查询返回 OptionalLong.empty；有效新增人数不能超过已配置总量。正常空 Staffing/Pending 记录不要求先造一个虚假的默认供给。

StaffingCalculator/StaffingSnapshot/OccupationStaffingSnapshot 已改为整数人数、有效 PM 岗位、Pending、total/occupied/available，不再带默认 HR 比例、招聘目标比例或生产开工许可。HumanResourcesDepartment、StaffingExpectation 已删除。SqliteStaffingQuery 在一次 DEFERRED 快照中读取各根，避免混读 revision 和人员行，同时不取得 IMMEDIATE 写权限。

查询明确定义为独立 staged Staffing 事实；当前 Batch 的实际选人和资格过滤继续由旧 WorkforcePort/Employment/WorkforcePlanningService 完成。测试证明：14 名正式 NPC 不会被复制成新 Staffing 的 14 人，新的 Pending=10 也不能替代缺少的 NPC。

## 7. 工资门禁与旧生产保护

StaffingApplicationService 只实现 requestTarget / cancelPending 的持久命令。`applyEffectiveChanges` 无条件明确拒绝，并说明需要真实工资应计、持久经济 Tick 及 M3.1 权威切换。没有可注入的“总是成功” Payroll 适配器，也没有虚假生效 tick 参数。

低层仓储 `inTransaction(WriteSession)` 是未来 payroll-aware 外层事务可使用的存储能力，不是正式招聘命令；当前 ServerEconomyContext 不暴露低层就业写仓储。隔离测试的 `applyPendingForPersistenceTest` 只构造可核验的持久测试数据，用于供给并发/CAS/故障约束，不能视为正式人员生效闭环。

证据：

- ProductionService 没有依赖 BuildingStaffing、RegionalLaborSupply 或新 StaffingApplicationService；仍从原 WorkforcePort 读取真实 NPC 任职/资格。
- ServerEconomyContext 继续装配旧 EmploymentService 给 ProductionService；新服务和查询单独装配，没有 tick 订阅或后台补招。
- 新 Staffing 请求和低层测试数据均不写 employment_state；旧 NPC 的招聘/解雇也不填充新 Staffing。
- 正式 Payroll unavailable/资格 false 的默认 fail-closed 保留；已有生产集成测试的显式 CURRENT 事实没有被拿来解除新生效门禁。
- 满速 200 tick、3/5 第 334 tick、每职业 10%、冻结配方/NPC 快照、完整完成/中止、命令幂等、StartIntent/Journal 和 METHODS/EQUIPMENT/WORKFORCE 均保留。未实现 RECONFIGURING。

ProductionService 和旧 EmploymentService 只额外检查历史身份不能开工/入职，未改变原工资、终止或结算规则。pig_iron_tools 继续因无正式职业需求被拒绝，没有补造岗位。

## 8. 事务基础的最小扩展

未新增平行事务框架、ORM 或事件总线。SqliteTransactions.run 的原 commit/rollback/Savepoint/隔离清理逻辑未改；SqliteConnectionGuard、原 PM/Execution UoW、库存写事务与 CAS 实现未改。

本轮对原 helper 增加两项能力：

1. ReadSession/read：连接绑定、线程绑定、作用域过期的显式读取能力，使用 DEFERRED 事务取得一致快照。普通 committed load 仍拒绝 unresolved 本地事务，未删除 requireCommittedReads，也没有 ThreadLocal。
2. WriteSession.perform：新仓储操作失败后标记当前写会话失败；write 在 callback 返回时再次检查，避免 callback 捕获第二写错误后提交第一写。标记只属于当前明确会话，没有全局事务状态。

IMMEDIATE 写权限仍通过现有 sqlite-jdbc 3.46.1.0 已登记的 driver 配置机制取得；没有原生 BEGIN 混用，没有 unguarded unwrap。commit 未知不重试；rollback 或清理失败仍隔离连接和派生句柄；Session 失效后不能继续读取。

## 9. Schema v5 与兼容合同

新建世界正式使用 Schema v5。新增 6 张表：companies、regions、occupation_catalog、building_staffing、building_staffing_roles、regional_labor_supply。economic_buildings 扩为显式 owner/region/lifecycle/revision，原 status 保留。总计 14 张应用表及 1 个显式唯一索引。

新表具有必要的 NOT NULL、FK、复合主键/唯一性、人数和 revision 的 INTEGER/非负/范围 CHECK。职业数量行限制在 int 范围；区域总量和版本为非负 long。没有现金、工资、人口默认值、occupied/available 缓存、设备或新批次屏障表。

Staffing/Supply 使用规范化 SQL 合同，不引入另一套通用 JSON Codec。Batch 和 Inventory 的结构语义沿用 M1.4/M1.3，但 envelope 统一为 formatVersion=5，Batch fullSpeedTicks 仍为 200，NPC UUID 保留。旧整数、严格 JSON/重复字段/UUID/回执归属/溢出保护不变。

文件入口继续先物理 READONLY 预检，包含 WAL；已有文件通过后才以不含 CREATE 权限的可写连接重检。v5 门禁额外验证身份规范形式、四根完整性、角色 FK/整数约束及占用不超过供给。旧 v1～v4 一律拒绝；不迁移、不清空、不重建、不改旧文件，不从 NPC 生成 Staffing。

独立 schema-v2/v3/v4.sql 保持不变；新增 schema-v5.sql。兼容测试对旧 v4 的六种 Batch 状态及 WAL 验证拒绝前后逐字节相同，历史 v1/v2/v3 保护继续适用。

## 10. 验收矩阵与结果

| 验收点 | 证据 |
|---|---|
| 公司/地区有效性、唯一性、建筑 CAS | BuildingIdentityPersistenceTest：类型化 ID、目录重复/FK拒绝、status/lifecycle 区分、旧 revision、溢出、历史身份保护。 |
| 四根原子创建 | 对四张根表分别注入真实 INSERT trigger 拒绝；四根均为 0，Registry 无发布。 |
| 未知提交与发布 | commit 前/后 SQLException、成功 commit 后 reset 失败、内层 Savepoint 回执失败；均不提前发布或重试；新可信连接核验完整图/缺失/部分图。 |
| 领域规则 | BuildingStaffingTest：缺失/0/正数、覆盖/取消、容量、负数、PM 收缩、不招聘、部分招聘消费、外部快照不变、stale transition 和溢出。 |
| 重启与 SQL 持久合同 | StaffingRepositoryTest：空根/Pending=0 重启、FK/REAL/超 int 拒绝、供给长整数、revision overflow。 |
| 多建筑与区域事实 | 两地区两职业真实持久汇总；Pending 不占用；供给不能低于就业；提高供给不自动补招。 |
| 两连接竞争 | WAL 下两个建筑目标均为 8、总供给 12，最终 8+4；供给降低与招聘竞争不会超额；另一连接的 stale Building/Staffing/Supply 均被 CAS 拒绝。 |
| 写锁时机 | DELETE/WAL 两种模式在首个权威 SELECT 前由第二连接真实 UPDATE，得到 SQLITE_BUSY code 5。 |
| 查询一致性 | 首次身份读取后第二连接成功修改供给，当前 DEFERRED 查询仍看到原快照；证明没有 IMMEDIATE 读锁且没有混读。 |
| 故障 | SQL 角色写失败且 callback 捕获时，先前 supply 更新仍回滚；实际人数 commit 前/后故障保持整个持久结果；rollback+close 失败不 reset、不 commit，原人数与派生占用保持，缓存句柄隔离。 |
| 门禁及权威隔离 | 新实际人数生效命令明确失败；新旧状态不双写；旧资格、岗位、冻结 Batch 与三阶段恢复回归通过。 |
| 格式保护 | v5 正常，v1～v4 拒绝；旧 DB/WAL 字节不变；v5 缺根、非法身份、非法人数及无供给占用均拒绝。 |

完整测试汇总：

| 测试类 | 数量 |
|---|---:|
| BuildingIdentityPersistenceTest | 9 |
| BuildingStaffingTest | 9 |
| StaffingRepositoryTest | 8 |
| LaborTransactionSafetyTest | 17 |
| SchemaCompatibilityTest | 101 |
| StaffingQueryTest | 4 |
| BuildingProductionIsolationTest | 5 |
| ProductionDomainTest | 17 |
| ProductionIntegrationTest | 19 |
| BoundaryRecoveryTest | 10 |
| RecoveryAndControlsTest | 12 |
| StartCommitRecoveryTest | 11 |
| WorkforceContractTest | 3 |
| ProductionRepositoryTest | 11 |
| EconomyUnitOfWorkTest | 29 |
| SqliteTransactionsTest | 26 |
| GoodsInventoryTest | 4 |
| SqliteInventoryContractTest | 5 |
| SqliteInventoryConcurrencyTest | 14 |
| **合计（19 类）** | **314** |

**0 failures、0 errors、0 skipped**。基线 259 项中适用的生产/事务保护保留；旧 HR 比例查询断言按新查询需求替换，没有为了通过测试删除生产故障类。新增 55 项：新领域/身份/仓储/故障 43 项，兼容新增 12 项。

已执行指定命令：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：`BUILD SUCCESSFUL in 45s`，9 个任务全部执行。JUnit XML 汇总如上；`git diff --check` 通过。构建产物 `build/libs/victoria_economics-0.1.1-1.21.1.jar` 中专用设备及已删除 HR 类条目为 0。未升级驱动或构建依赖。

真实世界 `run/saves/新的世界/victoria_economics/economy.sqlite` 仅计算文件哈希，未通过 SQLite 打开或写入。开始和结束 SHA-256 相同：

```text
3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8
```

## 11. 尚不可用入口与任务 B 前置条件

本轮没有验收失败。以下是刻意保留的依赖边界，并非已完成的正式运行能力：

1. Minecraft 建筑创建仍缺真实公司/地区来源；无身份参数调用明确拒绝，不创建默认实体或代玩家分配公司。
2. 正式招聘、裁员、调配与 PM+Staffing 生效不开放。必须先有真实持久 tick 和 PayrollAccrual，再进入 M3.1 的共同事务和唯一就业权威切换。
3. 新总劳动力供给没有默认人口或正式初始化命令。实际 Region/Occupation 供给值须来自后续授权的真实数据，未配置供给由查询明确表示。
4. Company/Region 目录只有身份，没有财务账户、工资值或居民现金。任务 B 必须明确货币和真实资金初始化来源，不能用余额 mock 或默认现金冒充财务基础。
5. 任务 B 的持久时钟需要明确已接受 tick、崩溃/提交回执未知的恢复和工资游标规则；继续离线不补算。不得用本轮内存 EconomicClock 冒充持久时间。
6. 工资应计需基于变更前的真实就业义务，累计到生效 tick 后才能提交人数改变；真实账户/预留、账本和应计状态要复用可信 UoW，不能借 Pending 或 pure transition 跳过工资。
7. M3.1 才能替换旧 NPC 权威及三阶段边界。本轮四根创建、staged Staffing、旧 Employment 三者职责明确；后续不得长期双写或混合汇总。
8. pig_iron_tools 仍需正式岗位数据才能启用。任何后续不兼容持久布局/快照变化继续同步演进 Schema/Codec；不能在 v5 名义下隐式改格式。
9. 验证范围为源码、JUnit 和真实临时 SQLite；未启动 Minecraft 对用户世界做端到端写入。

合并任务 A 到此停止，等待统一源码审查，不自动进入任务 B 或 M3～M5。

## 12. 完整文件变更清单

下表由本轮最终 Git 工作区清单生成：M=修改，A=新增，D=删除。均相对于仓库根目录；包含本报告。

| 状态 | 文件 |
|---|---|
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/BuildingRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/BuildingService.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/EconomicBuilding.java` |
| D | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/department/hr/HumanResourcesDepartment.java` |
| D | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/department/hr/StaffingExpectation.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/OccupationStaffingSnapshot.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBuildingInitializer.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingCalculator.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingSnapshot.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/package-info.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionMethodConfigurationRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/EconomyDatabase.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteBuildingRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteTransactions.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/employment/EmploymentService.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryContractTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BoundaryRecoveryTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/BuildingProductionIsolationTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionIntegrationTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingQueryTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/TestEconomy.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/WorkforceContractTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/ProductionRepositoryTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyUnitOfWorkTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java` |
| A | `docs/design/VE-Task-A-M2.1-M2.2-建筑身份与劳动力-实现验收.md` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/BuildingCreation.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/BuildingCreationUnconfirmed.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/building/BuildingLifecycle.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/company/domain/Company.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/company/domain/CompanyId.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/company/infrastructure/SqliteCompanyRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/company/port/CompanyRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/region/domain/Region.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/region/domain/RegionId.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/region/infrastructure/SqliteRegionRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/region/port/RegionRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/application/StaffingApplicationService.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/application/package-info.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/BuildingStaffing.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/RegionalLaborSupply.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/StaffingTransition.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/package-info.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/SqliteBuildingStaffingRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/SqliteOccupationCatalog.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/SqliteRegionalLaborSupplyRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/SqliteStaffingQuery.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/port/BuildingStaffingRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/port/RegionalLaborSupplyRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/workforce/port/StaffingQueryPort.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/TestIdentities.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/building/BuildingIdentityPersistenceTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/LaborTestWorld.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/domain/BuildingStaffingTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/LaborTransactionSafetyTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/workforce/infrastructure/StaffingRepositoryTest.java` |
| A | `src/test/resources/fixtures/sqlite/schema-v5.sql` |

共 61 个文件；无 Git 提交、推送或合并。
