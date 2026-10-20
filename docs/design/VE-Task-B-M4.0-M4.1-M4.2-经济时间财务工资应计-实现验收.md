# 任务 B：M4.0 + M4.1 + M4.2 经济时间、财务与工资应计实现验收

日期：2026-10-20。状态：B1～B5 实施及本地验证完成，等待统一源码审查。没有执行 Git commit、push 或 merge；没有进入任务 C、M4.3/M4.4 或 M5。

## 1. 基线与工作区

开始时分支为 `refactor/m1-0-sqlite-transaction-cleanup`，实际 HEAD 为 `c279dfd30b52b6a0e412c7fe57fc67ec09507a02`，工作区干净，与用户参考提交一致。结束时 HEAD 和分支未变。已阅读 M0 分阶段计划、Production/Labor/Payroll DDD 草案、任务 A 验收报告，以已确认规则及本次授权为准。

本轮未执行 git add、reset、stash 或切换分支。环境中的部分新增文件显示为 A/AM，另有未暂存修改和未跟踪文件；没有调整这些暂存选择。后续审查和提交应检查完整工作区，不能只看暂存快照。

真实世界 `run/saves/新的世界/victoria_economics/economy.sqlite` 仅计算哈希，未通过 SQLite 打开。开始和结束 SHA-256 均为：

`3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8`

## 2. B1～B5 结果

| 内部阶段 | 实施结果与不变量 |
|---|---|
| B1 持久时间 | 一个 SQLite 世界 Tick 权威；接受 Tick、工资游标、生产回调返回证据分离；重启不追补离线时间，不重放历史生产回调。 |
| B2 财务基础 | 显式币种目录、公司/地区通用账户、零余额开户、可复用资金预留、双边原子转账、完整命令幂等和账本核验。没有正式注资入口。 |
| B3 工资应计 | 地区平均工资生效历史、历史公司段、人数生效历史、精确 /1200 余数、逐分钟到期债务及 CAS；没有发薪。 |
| B4 查询与恢复 | 真实 CURRENT/ARREARS/RECOVERY/UNAVAILABLE 查询适配器；未知提交用新可信连接核验；正式旧 NPC 生产链与新工资模型隔离。 |
| B5 格式与验证 | Schema/Codec v6、旧 v1～v5 非破坏拒绝、纯领域/临时 SQLite/两连接/故障测试，完整构建通过。 |

没有建立新的通用事务框架、ORM、事件总线或第二份现金/就业权威。核心 SqliteTransactions、SqliteConnectionGuard 和 PM/Execution UoW 均保持原实现。

## 3. 世界 Tick 与恢复协议

### 3.1 持久事实

`economic_time` 单例保存 accepted_tick、production_callback_returned_tick、first_uncertain_production_tick 和 revision。`payroll_runtime` 单例保存 processed_tick、authority 和 revision。

- accepted_tick 是实际逻辑服务器 Tick 已被持久接受的序号，不是业务完成回执。
- 周期 ID 为 `acceptedTick / 1200`，最近周期边界为 `periodId * 1200`；使用 exact 增加/乘法，不分配墙钟 ID。
- payroll processed_tick 与该次处理的全部工资段/到期行在同一物理事务更新。
- production_callback_returned_tick 只证明旧生产协调器本次调用正常返回。旧协调器会捕获单建筑错误，因此该字段**不证明所有 Batch、库存、启动或边界操作成功**。
- first_uncertain_production_tick 保留首个未能确认回调返回的缺口；没有在本轮提供清除此证据的业务入口。

正式 ServerEconomyContext 使用持久构造器。单参数内存 EconomicClock 仅保留给原有隔离模拟和旧生产回归夹具，不参与正式世界装配。

### 3.2 正常 Tick 顺序

1. 在 IMMEDIATE 事务内重读时间，以 expected accepted tick 和 revision CAS 持久接受下一 Tick。
2. 在另一 IMMEDIATE 事务内重新读取 accepted Tick、工资段、人数和工资率，将登记工资应计推进至此 Tick，并同时更新工资游标。
3. 调用旧 ProductionService 一次。
4. 正常返回后，用独立事务记录此次回调返回证据。

任何未捕获异常使当前 clock 实例停止分配新 Tick，要求重新打开可信连接并核验；不会在每次后续回调中重新分配一个周期。提交未知时没有自动再次提交或再次执行生产。

### 3.3 重启及崩溃窗口

重启读取 SQLite 序号，不使用系统时间差。不调用旧 Tick 回调追补停服时间。先记录未确认回调的缺口，再按 accepted time 恢复工资。工资根据每段 lastAccruedTick、余数、到期行主键恢复；相同目标再次处理不产生第二笔工资或周期债务。

如果生产已持久推进而回调确认未保存，重启不会再推进该历史 Tick；真实 SQLite Batch 测试验证进度保持 1，下一次新逻辑 Tick 才变成 2。如果 Tick 已接受但生产尚未调用，重启也不猜测应执行它；该 Tick 可能没有完整生产效果，缺口仍可查询。这是本轮刻意保留的边界：**工资/时间恢复可靠，生产不具备端到端 exactly-once**。StartIntent、SETTLING、Inventory 回执和三阶段边界仍各自恢复。任务 C 才能统一生产事务，不得把本报告解释为它们已完成。

### 3.4 写频率与性能

正常回调有三次物理写事务：接受 Tick、工资/游标、回调返回确认。20 TPS 时约 60 次事务提交/秒，另加既有生产写入；没有降低 SQLite durability 或改用异步缓存时间。

真实临时文件测试运行 1200 次持久回调，最终完整构建样本耗时 10949ms，平均约 9.12ms/Tick；此前样本为 14851ms，约 12.38ms/Tick。计时包含夹具建立/关闭，使用空工资登记集和轻量生产回调。这是本机 JVM/SQLite 样本，不代表 Minecraft 全服负载、慢磁盘或大规模建筑容量。有效段增多会增加每 Tick 的查询/更新，后续需要在隔离性能世界测量；不能为降低写频率而回退到可复用 Tick 或墙钟补算。

## 4. Finance 聚合与账本

CurrencyId 是规范 UUID，currencies 只保存显式身份及名称，没有默认世界币种或换汇。FinanceAccount 的主体由互斥 CompanyId/RegionId 外键确定，同主体同币种最多一个账户。正式开户余额为 0，记录 OPEN 事件及 0 金额账本行。

现金余额只在 finance_accounts 保存。CashReservation 是账户从属的持久承诺，不保存第二份余额；reserved 为有效预留行的精确求和，available=balance-reserved。预留要求正数和明确用途。释放幂等；已释放 ID 的原请求重放只返回已释放回执，不能再次锁钱。同 ID 不同金额、用途或账户拒绝。

转账命令/TransferReceipt 固定 id、source、destination、currency、amount。不同账户、正数、同币种、真实可用余额、加减溢出和 revision/CAS 都在写锁之后检查。两端余额、TRANSFER 事件和两条相反账本行在同一事务写入。第二端/事件/第二账本行失败均整体回滚。重复完整命令返回原结果；相同 ID 改任何命令字段拒绝。

`FinanceAccountRepository.verify(command)` 用持久完整事件核验结果。未知 commit 没有自动重发；测试分别在实际 commit 前、后抛异常，再从新可信连接核验。所有应用服务返回的对象均为 detached 值，外层提交/清理未成功不会返回可信结果。

账本支持逐账户按 sequence 重算余额，并核对每个事件的币种、目标、金额、行数和双边守恒。预留不产生现金变动账本。Schema 留有带显式 provenance 的 EXTERNAL_CREDIT 存储类型，仅供隔离资金夹具记录真实测试来源；**正式 FinanceService、服务器命令和 Context 没有任何 issuance/credit/addMoney 入口**。未来正式注资仍需单独授权和来源规则。测试夹具位于 src/test，不进入 mod jar。

## 5. 工资率、工资段与到期债务

RegionalWageRate 保存 RegionId、CurrencyId、effectiveTick、每人每 1200 Tick 的整数最小单位金额及 revision。按地区保存不可回溯覆盖的有序历史；显式配置 0 与缺失配置不同。当前地区不允许隐式切换币种，不使用 OccupationDefinition.wageMultiplier。

新工资率只能作用于 accepted Tick 或未来；不能倒改已接受历史。配置时先在同一事务累计该地区旧工资段到当前 accepted Tick，再插入历史值。计算使用半开区间 `[from,to)`，在工资率生效时刻和经济分钟边界拆分。

PayrollAccrual 独立保存固定历史 CompanyId、BuildingId、RegionId、CurrencyId、segmentId、起点/截止点、lastAccruedTick、当前人数基数、累计/未到期/到期/已付金额、余数和 revision。公司历史债务永远从原段读取，不从建筑现所有者倒推。

精确公式为 `raw=r+n*w*Δt`，商除以 1200，余数模 1200。中间分子使用 BigInteger，最终金额以 longValueExact、addExact 和 exact revision 检查；既不会 Double 计薪，也不会因中间乘积大于 long 而截断。最终不能表示的金额整笔拒绝。

每跨过一个 1200 Tick 边界，将整单位未到期金额转入 payroll_liabilities，键为 segment+periodId；余数原样留在段内。0 金额周期也有记录，便于验证连续游标。重复目标不会重复到期化。应计不操作 Finance，因此无现金时仍产生真实债务。本轮 paid 始终为 0，不能假装发薪；格式校验也拒绝没有本轮付款合同的伪造已付数据。

关闭历史段后不再新增其未来工资，已有未到期金额继续按可靠边界到期，分数余数永久保留在原公司段。新公司新段不继承余数。最终小额余数清算规则仍未决定，本轮不擅自四舍五入或转嫁。

## 6. 人数截止与工资历史

payroll_employment_history 保存每段的初始人数及后续基数生效 Tick/revision，避免覆盖人数后无法解释历史工资。启动校验结合工资率和人数历史精确复算应计、余数和逐周期金额。

未来共享 WriteSession 的基础顺序是：

1. 重新加载旧 Payroll 段并 accrue 到 accepted Tick。
2. 在相同物理事务保存经过岗位/地区约束的新 Staffing。
3. changeEmployees 验证 Payroll 已截止到该 Tick，且数据库真实 Staffing 总数等于新基数，再保存工资基数和历史行。

不能用 detached 假段绕过：accrue 会重新核对整个持久段及原人数/身份。正式应用没有开放这一生效命令；测试的同事务路径明确叫 Fixture，只验证未来基础能力。工资、Staffing 或人数历史任一点失败，旧工资游标及人数均回滚。

工资全局处理还检查：正数 Staffing 不得缺少登记工资段；已有活段的公司、地区和人数必须匹配。缺少 enrollment 或越过工资截止私改 Staffing 时拒绝推进游标，不能默默跳过工资。

## 7. 真实状态查询与正式门禁

SqliteBuildingPayrollQuery 复用 BuildingPayrollPort 状态定义，没有另一套相反工资状态。state(building) 从 SQLite 读取现公司，显式 state(building, expectedCompany) 还校验公司匹配；使用同一 DEFERRED 快照。

| 状态 | 真实依据 |
|---|---|
| CURRENT | 已切换为 BuildingStaffing 权威、当前公司活段/币种/工资率存在、时间与人数可核验、工资游标已追上且该公司该建筑到期未付为 0。与账户现金充足无关。 |
| ARREARS | 以上权威可确认，当前公司该建筑历史及活段存在真实到期未付金额。旧公司的欠薪不计入买方状态。 |
| RECOVERY | 工资/时间游标尚待追平、人数未完成工资截止，或连接/本地事务结果不可信。 |
| UNAVAILABLE | 正式就业权威仍未切换，或必要身份、登记、工资率等缺失；其他无法形成可信查询的错误也不会返回 CURRENT。 |

新世界 payroll_runtime.authority 明确初始化为 LEGACY_NPC，没有正式切换 API。低层 enrollment 要求 BUILDING_STAFFING，正式 Context 不暴露该写仓储。只有隔离夹具显式设置此持久状态，用真实 Staffing/工资/时间验证新模型，不复制 NPC、不双写就业、不把纯招聘计划视为任职。

Context 只单独装配新 Finance、PayrollAccrualService 和 staged 工资查询。ProductionService 继续用旧 EmploymentService 及默认 unavailable 的旧工资入口；没有注入新适配器。其 ARREARS->terminate 旧分支原样保留，不能在任务 C 的暂停/恢复改好以前接通。StaffingApplicationService.applyEffectiveChanges 继续明确拒绝，真实人数生效门禁未开放。

## 8. 事务、未知提交与既有保护

所有新写入复用 SqliteTransactions.write，使用已验证的 sqlite-jdbc 3.46.1.0 IMMEDIATE 配置；写权限先于权威读取，不手写 BEGIN，不 unguarded unwrap。新仓储只通过连接/线程/寿命绑定的 WriteSession 读写；时间也提供明确 inTransaction/inSnapshot 能力，普通 load 保留 committed-read 检查。

Finance/Payroll 仓储操作使用 WriteSession.perform；操作异常即使被 callback 捕获也阻止外层提交。Schema 预检使用显式 DEFERRED ReadSession，普通查询不占 IMMEDIATE 写锁。没有改写 SqliteTransactions、SqliteConnectionGuard 或 SqliteEconomyUnitOfWork。

未知 commit 不假定失败，不自动再提交；清理/rollback/savepoint 失败的隔离规则继续生效。测试真实观察：rollback+close 失败时 commit/reset 次数均为 0，另一连接只读到原余额/工资；派生 Statement 无法继续工作。成功 commit 后 reset 失败则旧连接拒绝权威读取，新连接可核验整笔真实结果。

任务 A 四根初始化、身份 CAS、供给竞争、旧 NPC 资格/任职保护以及 M1.0～M1.4 的 Batch/库存/Journal/UoW 回归均保留。没有修改 FULL_SPEED_TICKS、商品、工资倍率、pig_iron_tools 岗位或三阶段生产边界。

## 9. Schema v6 与 Codec

新增 economic_time、payroll_runtime、currencies、finance_accounts、finance_events、finance_ledger、cash_reservations、regional_wage_rates、payroll_accruals、payroll_employment_history、payroll_liabilities 共 11 张表及工资活段唯一索引。合计 25 张显式表（含版本表）、2 个显式索引。具有必要 FK、唯一、整数类型/范围、非负、CAS 和单活段约束；无 occupied/available 或第二现金缓存。

时间/财务/工资采用上述规范化 SQL 合同，不构建通用 JSON 框架。Batch/Inventory 结构和业务语义保持不变，但 envelope 明确为 formatVersion=6，200 Tick 和 NPC UUID 快照仍保留。v5 JSON 不能在 v6 行中被隐式读取。独立新增 schema-v6.sql；历史 v2～v5 fixture 保留。

复用现有物理 READONLY 预检、通过后无 CREATE 权限可写重检。旧 v1～v5、未知版本、混合/缺表、不一致账本、游标、余数、工资历史或假付款均拒绝，无修复或迁移。v5 六种 Batch 状态和主文件/WAL 拒绝前后逐字节相同；历史 v1～v4 同类保护继续通过。

未来更改工资付款合同、取消 NPC 快照或其他不兼容布局，必须继续管理 Schema/Codec，不能在 v6 下悄悄重新解释本轮状态。

## 10. 验证矩阵与完整构建

| 场景 | 测试证据 |
|---|---|
| 真实时间及重启 | 1200 次持久回调恰为一分钟，重启时间不跳增；CAS/long overflow；已接受未处理、SQL 故障和 commit 前后回执丢失；真实 Batch 已推进后的崩溃不重放。 |
| Finance | 零余额/目录 FK；精确大整数余额及重启；不足/预留/释放；完整命令幂等及冲突；币种隔离；目的账户溢出；第二账户、事件或第二账本行失败整笔回滚。 |
| 并发 | 双连接竞争同一 100 余额各转 80 只能成功一笔；双连接累计同周期只生成一组 Liability；DELETE/WAL 均在第一次权威 SELECT 前验证另一写入 SQLITE_BUSY(5)。 |
| 工资领域 | 逐 Tick 余数1～1200；随机分段与连续计算等价；工资率分段；增/减旧人数先计薪；历史关闭余数不转公司；缺率和显式零区分；精确分子及最终溢出。 |
| 工资持久事实 | CURRENT/ARREARS/RECOVERY/UNAVAILABLE；跨重启余数和周期；停止/暂停/终止/auto=false/超容仍应计；无资金仍欠薪；历史公司隔离；重复到期化；基数历史复算。 |
| 工资原子故障 | accrual、liability、global cursor、工资率、Staffing、人数历史每个写点失败；commit 前后回执丢失；rollback/reset/savepoint 失败；不跳时段、不假付款。 |
| 格式 | 旧 v1～v5 主文件/WAL 字节保护；完整 v6 图只读加载不改字节；不平账、无账资金、伪造已付、非法余数、缺工资期行及旧 JSON 标签均拒绝。 |
| 原有回归 | 任务 A 四根/身份/供给/并发；200/334 Tick、最低10%、全额完成/中止；库存 Reservation/CAS、StartIntent/Journal、UoW29项、M1.0故障26项全部通过。 |

执行用户指定命令：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

结果：**BUILD SUCCESSFUL in 1m 6s**，9 个任务全部实际执行。共 25 类 **402 项测试，0 failure、0 error、0 skipped**。相对任务 A 的 314 项新增 88 项：新六类 80 项、兼容新增 8 项。没有删除旧测试类或降低生产断言以获得全绿；当前格式夹具改为 v6，历史格式的拒绝断言单独保留。

JUnit 明细由最终 XML 汇总如下。

| 测试类 | 数量 |
|---|---:|
| BuildingIdentityPersistenceTest | 9 |
| EconomicTimePersistenceTest | 8 |
| FinancePersistenceTest | 14 |
| FinancialCompatibilityTest | 14 |
| FinancialTransactionSafetyTest | 10 |
| PayrollAccrualDomainTest | 8 |
| PayrollPersistenceTest | 26 |
| GoodsInventoryTest | 4 |
| SqliteInventoryConcurrencyTest | 14 |
| SqliteInventoryContractTest | 5 |
| BoundaryRecoveryTest | 10 |
| BuildingProductionIsolationTest | 5 |
| ProductionIntegrationTest | 19 |
| RecoveryAndControlsTest | 12 |
| StaffingQueryTest | 4 |
| StartCommitRecoveryTest | 11 |
| WorkforceContractTest | 3 |
| ProductionDomainTest | 17 |
| ProductionRepositoryTest | 11 |
| EconomyUnitOfWorkTest | 29 |
| SchemaCompatibilityTest | 109 |
| SqliteTransactionsTest | 26 |
| BuildingStaffingTest | 9 |
| LaborTransactionSafetyTest | 17 |
| StaffingRepositoryTest | 8 |
| **合计** | **402** |

`git diff HEAD --check` 通过。产物为 `build/libs/victoria_economics-0.1.1-1.21.1.jar`；未升级 SQLite JDBC、Gradle 或 NeoForge。测试是纯 JVM 及隔离 SQLite 文件；没有在 Minecraft 中对用户存档做运行写入验收。

## 11. 未开放入口、风险与任务 C 前置条件

1. 正式工资登记/Staffing 权威切换、实际招聘裁员仍未开放。C 必须在共享写事务中截止旧工资、改变 Staffing/PM、记录工资人数基数，并提交 Execution 单一重配置屏障；不得分开提交。
2. C 必须先实现 ARREARS 对原 Batch 暂停/恢复及多阻塞处理，才能将新工资查询接入 ProductionService。旧 ARREARS 终止路径不能承接真实适配器。
3. C 需处理生产回调缺口及每建筑事务边界。当前 first_uncertain_production_tick 不自动清除，回调返回不是全业务完成；可能少做崩溃窗口中的生产 Tick，但不会自动再推进已保存 Batch。此限制没有降低工资/库存/已提交数据保护。
4. 正式建筑创建仍缺玩家公司/地区来源。四根初始化完整保留；未来正式雇佣起点还需在 C 的原子事务内登记 Payroll，不能从旧 NPC 自动转换人数或回填过去工资。
5. 币种、地区工资率、总供给及合法资金来源仍需显式输入；没有默认值。真实账户默认0，不提供发行/调试加钱。正式资金注入来源、授权与流程并未实现。
6. 公司统一发薪、最大余数法、居民现金到账及预算均未实施，留给 D。当前 paid 永远0；Finance 的通用转账不能被当成已冲销某笔 Payroll 债务。
7. 历史段余数已安全保存，但生命周期结束后如何最终清算小额余数仍待正式决策；不得丢弃或转嫁。出售/拆除及资产归属仍为 M5。
8. 每 Tick 三次提交有磁盘成本；大世界/慢存储压力测试尚未进行。不能据当前空登记集样本宣称规模容量已验证。全服故障后 clock 实例停止，需要重新打开可信连接；未实现在线自动替换被隔离 Context。
9. pig_iron_tools 的正式岗位数据仍未配置，未放宽无职业产出规则。未删除 NPC、未实现 RECONFIGURING、未改变生产商品/进度/结算合同。

任务 B 到此停止，等待统一源码审查，不自动进入 C/D 或执行 Git 提交、推送、合并。

## 12. 完整文件变更

M=相对基线修改，A=新增；无删除。表为完整工作区文件集合，包含暂存和未暂存内容。

| 变更 | 文件 |
|---|---|
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/application/FinanceService.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/domain/CashReservation.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/domain/CurrencyId.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/domain/FinanceAccount.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/domain/LedgerEntry.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/domain/TransferReceipt.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/infrastructure/SqliteFinanceAccountRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/port/FinanceAccountRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/application/PayrollAccrualService.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollAccrual.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/PayrollLiability.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/domain/RegionalWageRate.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqliteBuildingPayrollQuery.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqlitePayrollRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/port/PayrollRepository.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/EconomicClock.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/package-info.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyFinancialSchema.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyFinancialValidation.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java` |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/time/domain/EconomicTime.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/time/infrastructure/SqliteEconomicTimeRepository.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/time/port/EconomicTimeRepository.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/EconomicTimePersistenceTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancePersistenceTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialTestWorld.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/JdbcFaults.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/PayrollAccrualDomainTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryContractTest.java` |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java` |
| A | `src/test/resources/fixtures/sqlite/schema-v6.sql` |
| A | `docs/design/VE-Task-B-M4.0-M4.1-M4.2-经济时间财务工资应计-实现验收.md` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/finance/package-info.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/package-info.java` |
| A | `src/main/java/com/bbmurloc/victoriaeconomics/server/time/package-info.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialCompatibilityTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/FinancialTransactionSafetyTest.java` |
| A | `src/test/java/com/bbmurloc/victoriaeconomics/server/financial/PayrollPersistenceTest.java` |

共 40 个文件。
