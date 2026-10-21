# 当前经济运行链与代码阅读地图

日期：2026-10-21；Schema v10、业务 JSON format 9。当前存档合同见 [P 检查点设计](../../design/VE-Task-P-一致性检查点架构.md)及 [P 验收报告](../../testing/VE-Task-P-经济Tick性能诊断与优化验收.md)。[任务 E 报告](../../design/VE-Task-E-M5.1-M5.2-M5.3-建筑生命周期与整体验收.md)记录此前 v9 阶段；其中逐 Tick 落盘要求已被用户本轮决定替代。旧 overview、搬包审查及 ADR-PO-02 保留历史。

工作包 P 恢复阶段只局部减少同步结算重复工作：同一显式 WriteSession 的 Execution Session 复用已严格解码的不可变配置，每次保存仍重新读取行并比对原 JSON；不跨事务缓存经济状态。prepare/commitStart 各自重新校验库存、Staffing、工资及 CAS。自动重开不再重复已经完成的重配置，资产阶段、Journal 和未知提交恢复边界保持。具体安全论证、688 项回归及未通过的性能项目见 P 报告第 12 节；不能把查询数量下降等同于 20 TPS 验收通过。

## 权威与入口

| 职责 | 当前源码（均在 `src/main/java/com/bbmurloc/victoriaeconomics` 下） |
|---|---|
| NeoForge 启停 / Tick | `server/event/ServerLifecycleEvents` → `ServerEconomyRuntime` → `ServerEconomyContext` |
| 世界持久时间 | `server/production/application/EconomicClock`、`server/time/infrastructure/SqliteEconomicTimeRepository` |
| 建筑身份与创建 | `server/building/EconomicBuilding`、`BuildingService`、`server/production/application/ProductionBuildingInitializer` |
| 双生产根 | `server/production/domain/ProductionMethodConfiguration` 与 `ProductionExecution`，各自 Repository / CAS |
| 唯一实际雇佣 / 区域供给 | `server/workforce/domain/BuildingStaffing`、`RegionalLaborSupply`；occupied 由 SQL 真实人数派生 |
| 重配置 / 开工 / 恢复 | `server/production/application/ProductionBoundaryCoordinator`、`ProductionService`；StartIntent / Journal 保留 |
| 整数商品 | `server/inventory/domain/GoodsInventory`、`server/inventory/infrastructure/SqliteInventoryStore` |
| 历史工资 / 付款 | `server/payroll/domain/PayrollAccrual`、`PayrollPaymentRun`；`PayrollAccrualService`、`PayrollPeriodService`、`CompanyPayrollService` |
| 现金 / 用途额度 | `server/finance` 是真实现金唯一权威；`server/household` 只管理消费、储蓄额度 |
| 生命周期操作 | `server/building/application/BuildingLifecycleService`、`domain/BuildingLifecycleOperation`、`infrastructure/SqliteBuildingLifecycleOperationRepository` |
| 数据门禁 | `server/storage/sqlite/EconomySchema`、`EconomyStateCodec`、Financial / Payment / LifecycleValidation |
| 唯一内存事务权威 | `server/storage/EconomyDatabase`；全部领域使用同一具名共享内存数据库 |
| 完整经济存档 | `EconomyCheckpoint`、`SqliteCheckpointStore`、`EconomicCheckpointWriter` |
| 原版保存协调 | `mixin/EconomySaveMixin`；服务器级 `saveAllChunks`，不是每维度 Save 事件 |

每分钟 1200 个实际服务器逻辑 Tick；200 Tick 满速、3/5 速度第 334 Tick 完成。不按墙钟追补。每建筑的运行时 Tick 标记保护推进，库存结算、开工日志、重配置保留原子事务及幂等恢复。**运行中提交不等于落盘**；重启恢复整个最近成功经济检查点，丢弃未保存后缀，不宣称逐 Tick 崩溃 exactly-once。

工作包 P 保留 `ProductionBoundaryCoordinator.processTick`：无外部待办的 ACTIVE/PAUSED、无自动开工的空闲建筑，以及完成资源交接的 BLOCKED 生命周期，在同一个 IMMEDIATE **内存业务事务**保存本 Tick 决定和完成标记。已有 processing、SETTLING、RECONFIGURING、StartIntent 和自动开工仍走原协议。工资率只在同次事务内复用，不缓存跨 Tick 权威。

## 保存与恢复

SQLite RAM 存储是运行时唯一权威，磁盘 `economy.sqlite` 只保存完整检查点。后台线程只写主线程已冻结的不可变表格，不触及世界或活跃聚合。一次物理写事务包含时间、全部聚合、账本、付款行、历史回执及检查点 ID/摘要；最多一份执行中、一份待执行新快照。旧文件 v1～v9 拒绝、不迁移，主文件及 WAL 受保护；不用 SavedData。

默认跟随 Minecraft 原生自动保存（普通 20 TPS 为约 6000 Tick），不另写死 200 Tick。普通 `/save-all` 请求后台存档，不代表 VE 已提交；`/save-all flush` 等待 VE 确认，否则报错。正常停服先停止经济推进、恢复可解决待办并等待最后存档，再释放 Context。异常或超时明确保留上一检查点。可用 `/ve checkpoint status` 查看最近提交与失败，用 `/ve checkpoint flush` 显式等待。

Minecraft 原生文件和 VE SQLite 没有共同物理事务。正式虚拟库存→背包/箱子交换入口保持关闭。绑定锚点前先确认含建筑身份的 VE 检查点；NBT 只作身份提示，不能创建经济资产、转移所有权或证明拆除。

每职业至少 10%，速度是人数比例瓶颈。Staffing Pending 不改变当前 Batch；部分招聘消费一次请求。PM、人数和旧工资截止在同一 IMMEDIATE 写事务生效。没有设备、NPC Employment 或旧三阶段边界。普通工具、机械商品仍保留。

完成全额耗料产出，中止全退零产；欠薪只暂停，MANUAL 等原因独立保留。公司周期父 Run 的多币种分区、真实扣转、账本和债务减少同事务；历史来源的未决部分冲销仍 BLOCKED。所有账户零余额开户，没有正式测试注资或默认人口入口。

## 生命周期的实际开放范围

详见 [ADR-LIFECYCLE-01 的 E0 矩阵](../../architecture/adr/ADR-LIFECYCLE-01.md)。本轮正式开放受控准备、持久阻塞、重试和可信核验；**最终出售及拆除未开放**。

`无操作 → QUIESCING → BLOCKED`。开始事务保存独立意图、关 automatic、中止决定；库存结算确认后原子接替 RECONFIGURING 屏障。BLOCKED 不改变 owner、不释放员工、不关闭工资，持续保留原公司义务；不应用卖方 Pending。已决定 COMPLETED 的批次保持原结算。

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
/ve production <building> staffing <occupation> <target>
/ve production <building> cancel_staffing <occupation>
/ve production <building> pm <group> <method>
/ve production <building> cancel_pm
/ve debug <building> bind_anchor <x> <y> <z>
/ve building <building> lifecycle status
/ve building <building> lifecycle sale <operation> <seller> <buyer> <identityRevision>
/ve building <building> lifecycle demolish <operation> <seller> <identityRevision>
/ve building <building> lifecycle retry <operation>
/ve building <building> lifecycle verify <operation>
```

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
