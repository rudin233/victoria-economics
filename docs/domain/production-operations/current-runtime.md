# 当前经济运行链与代码阅读地图

Schema v12 使用业务 JSON format 10。当前存档合同见 [P 检查点设计](../../design/VE-Task-P-一致性检查点架构.md)及 [P 验收报告](../../testing/VE-Task-P-经济Tick性能诊断与优化验收.md)。[任务 E 报告](../../design/VE-Task-E-M5.1-M5.2-M5.3-建筑生命周期与整体验收.md)记录此前 v9 阶段；其中逐 Tick 落盘要求已被用户本轮决定替代。[生产概览](overview.md)与[代码阅读地图](code-reading-map.md)描述现行行为。搬包审查和 ADR-PO-02 的实施记录保留历史。

工作包 P 恢复阶段只局部减少同步结算重复工作：同一显式 WriteSession 的 Execution Session 复用已严格解码的不可变配置，每次保存仍重新读取行并比对原 JSON；不跨事务缓存经济状态。开工事务内校验当前库存、用工、工资及版本。自动重开不重复已经完成的重配置。库存结算阶段和未知提交保护继续沿用。具体安全论证、688 项回归及未通过的性能项目见 P 报告第 12 节；不能把查询数量下降等同于 20 TPS 验收通过。

## 权威与入口

| 职责 | 当前源码（均在 `src/main/java/com/bbmurloc/victoriaeconomics` 下） |
|---|---|
| NeoForge 启停 / Tick | `server/event/ServerLifecycleEvents` → `ServerEconomyRuntime` → `ServerEconomyContext` |
| 世界持久时间 | `server/production/application/ServerTickCoordinator`、`server/time/infrastructure/SqliteEconomicTimeRepository` |
| 建筑身份与创建 | `server/building/EconomicBuilding`、`BuildingService`、`server/production/application/ProductionBuildingInitializer` |
| 双生产根 | `server/production/domain/ProductionMethodConfiguration` 与 `ProductionExecution`，各自 Repository / CAS |
| 批次临时用工 / 区域供给 | `server/workforce/domain/BuildingStaffing`、`RegionalLaborSupply`。区域占用从各批次的人数派生 |
| 重配置 / 开工 / 恢复 | `server/production/application/ProductionBoundaryCoordinator`、`ProductionService`，开工共享一个写事务 |
| 整数商品 | `server/inventory/domain/GoodsInventory`、`server/inventory/infrastructure/SqliteInventoryStore` |
| 历史工资 / 付款 | `server/payroll/domain/PayrollAccrual`、`PayrollPaymentRun`；`PayrollAccrualService`、`PayrollPeriodService`、`CompanyPayrollService` |
| 现金 / 用途额度 | `server/finance` 是真实现金唯一权威；`server/household` 只管理消费、储蓄额度 |
| 生命周期操作 | `server/building/application/BuildingLifecycleService`、`domain/BuildingLifecycleOperation`、`infrastructure/SqliteBuildingLifecycleOperationRepository` |
| 数据门禁 | `server/storage/sqlite/EconomySchema`、`EconomyStateCodec`、Financial / Payment / LifecycleValidation |
| 唯一内存事务权威 | `server/storage/EconomyDatabase`；全部领域使用同一具名共享内存数据库 |
| 完整经济存档 | `EconomyCheckpoint`、`SqliteCheckpointStore`、`EconomicCheckpointWriter` |
| 原版保存协调 | `mixin/EconomySaveMixin`；服务器级 `saveAllChunks`，不是每维度 Save 事件 |

每分钟 1200 个实际服务器逻辑 Tick；200 Tick 满速、3/5 速度第 334 Tick 完成。不按墙钟追补。每建筑的运行时 Tick 标记保护推进，库存结算和重配置保留各自的原子事务及幂等恢复。**运行中提交不等于落盘**；重启恢复整个最近成功经济检查点，丢弃未保存后缀，不宣称逐 Tick 崩溃 exactly-once。

工作包 P 保留 `ProductionBoundaryCoordinator.processTick`：无外部待办的 ACTIVE/PAUSED、无自动开工的空闲建筑，以及完成资源交接的 BLOCKED 生命周期，在同一个 IMMEDIATE **内存业务事务**保存本 Tick 决定和完成标记。已有 processing、SETTLING、RECONFIGURING 和自动开工仍由后续处理阶段协调。工资率只在同次事务内复用，不缓存跨 Tick 权威。

## 保存与恢复

SQLite RAM 存储是运行时唯一权威，磁盘 `economy.sqlite` 只保存完整检查点。后台线程只写主线程已冻结的不可变表格，不触及世界或活跃聚合。一次物理写事务包含时间、全部聚合、账本、付款行、历史回执及检查点 ID/摘要；最多一份执行中、一份待执行新快照。旧文件 v1～v11 拒绝、不迁移，主文件及 WAL 受保护；不用 SavedData。

默认跟随 Minecraft 原生自动保存（普通 20 TPS 为约 6000 Tick），不另写死 200 Tick。普通 `/save-all` 请求后台存档，不代表 VE 已提交；`/save-all flush` 等待 VE 确认，否则报错。正常停服先停止经济推进、恢复可解决待办并等待最后存档，再释放 Context。异常或超时明确保留上一检查点。可用 `/ve checkpoint status` 查看最近提交与失败，用 `/ve checkpoint flush` 显式等待。

Minecraft 原生文件和 VE SQLite 没有共同物理事务。正式虚拟库存→背包/箱子交换入口保持关闭。绑定锚点前先确认含建筑身份的 VE 检查点；NBT 只作身份提示，不能创建经济资产、转移所有权或证明拆除。

每批开工按生效配方的满编需求从区域获取可用人员，每个必需职业至少一人。所有招到的人参与生产和计薪，速度取各职业人数比例的最小值。配方及人数在本批冻结，只为生产方法保留 Pending。无批次时计薪人数为零，已发生债务和余数保留。

一个开工物理事务同时写 Batch、原料预留、用工占用与工资基数。明确失败整体回滚，提交结果不明时隔离原连接并在同一运行时的新连接上核验。库存完成或中止回执可信后，另一物理事务保存终态和结束 Tick，释放对应 batchId 的占用，并将计薪人数归零。暂停和待结算期间仍保留本批人员。工资按区间 [开工 Tick，结束 Tick) 累计。

每个 accepted Tick 先推进并结算各建筑，再以 Tick 对区域内完整 UUID 列表的长度取余并轮转开工。本 Tick 刚终结的建筑最早在下一 Tick 招聘，其他符合条件的建筑可使用已释放的供给。

正常完工全额耗料、全额产出。普通欠薪状态推进时暂停当前批次，MANUAL 等暂停原因各自保留。仅在受控的出售或拆除准备的特殊业务路径中，未决定完工的批次才可能中止，按全额退料、零产出结算。公司周期父 Run 的多币种分区、真实扣转、账本和债务减少同事务；历史来源的未决部分冲销仍 BLOCKED。所有账户零余额开户，没有正式测试注资或默认人口入口。

## 生命周期的实际开放范围

详见 [ADR-LIFECYCLE-01 的 E0 矩阵](../../architecture/adr/ADR-LIFECYCLE-01.md)。本轮正式开放受控准备、持久阻塞、重试和可信核验；**最终出售及拆除未开放**。

`无操作 → QUIESCING → BLOCKED`。开始事务保存独立意图、关 automatic、中止决定；库存结算确认后原子接替 RECONFIGURING 屏障。进入 BLOCKED 前已经释放批次用工，原有所有权与公司工资债务继续保留；不应用卖方 Pending。已决定 COMPLETED 的批次保持原结算。

没有 approve、强制完成、清理操作记录或移除 Registry 的后门。方块消失只是锚点消失，经济身份不会随之删除。墓碑格式的独立验证不是正式拆除验收。

## 管理命令

命令根 `/ve` 需权限 2；所有修改需权限 4。参数均为明确身份，Company / Region 必须已登记且有真实币种、工资率和供给；没有自动分配。

```text
/ve debug create <type> <capacity> <company> <region>
/ve checkpoint status|flush
/ve production <building> status
/ve production <building> start|stop|retry
/ve production <building> automatic <true|false>
/ve production <building> pause <true|false>
/ve production <building> pm <group> <method>
/ve production <building> cancel_pm
/ve debug <building> bind_anchor <x> <y> <z>
/ve building <building> lifecycle status
/ve building <building> lifecycle sale <operation> <seller> <buyer> <identityRevision>
/ve building <building> lifecycle demolish <operation> <seller> <identityRevision>
/ve building <building> lifecycle retry <operation>
/ve building <building> lifecycle verify <operation>
```

`stop` 仅在现有经济锁和执行版本保护下提交 `automatic=false`。空闲建筑停止自动开工，当前批次保留进度、配方、人员、原料预留和暂停原因。达到满进度后完整结算并处理待生效配置，自动续产关闭时不创建下一批。`automatic true` 允许后续自动开工。`start` 要求上一批结束并通过现有开工检查，成功后开启自动续产。

sale / demolish 返回的是准备状态或 BLOCKED，不是交易成功。相同 OperationId 的完整命令必须一致。verify 始终开新可信连接，经完整格式预检后读取事实；不会自动重发或发布未经证实的 owner。

## 验证与运行隔离

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
.\gradlew.bat runGameTestServer --console=plain
.\gradlew.bat runGameTestServer -PveGameTestRun=bench_01 --console=plain
.\gradlew.bat runGameTestServer -PveGameTestRun=bench_01 -PveGameTestMode=restart --console=plain
```

GameTest 使用 `src/gameTest` 独立测试 Mod 和 `build/task-e-gametest` 目录，测试资金、身份和结构不进正式 jar，也不进入普通 client/server run。测试不打开仓库 `run/saves` 世界。没有正式发行、完整市场、物流、消费、出售定价或资产产权合同；未决项不得用调试夹具代替。

`veGameTestRun` 是可选的简单运行名；会使用 `build/task-e-gametest-<name>`。为重复性能采样选择一个不存在的新运行名，不清理或重用旧世界，也不要同时运行其他压力任务。`check` 编译测试源但不自动启动 Minecraft。

`veGameTestMode=restart` 专用于再次启动上一条已成功的隔离世界，以对比 Context 创建前后的真实持久业务图和逻辑回调数量，不创建新的资金或身份夹具。每次 run 都重置断言回执，只有对应 GameTest 实际完成才允许 Gradle 报告成功；没有发现测试时的进程零退出码不算验收。
