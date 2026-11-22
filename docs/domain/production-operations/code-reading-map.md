# 生产代码阅读地图

本文描述当前实现。旧生产边界和设备设计见[包迁移审计](package-refactoring.md)、[解耦审计](context-decoupling-review.md)及 [ADR-PO-02](../../architecture/adr/ADR-PO-02.md) 的历史实施记录。领域职责见[概览](overview.md)，保存与管理命令见[当前运行合同](current-runtime.md)。

## 1. 主要入口

| 职责 | 优先阅读 |
| --- | --- |
| 玩家命令 | [ProductionCommands](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/command/ProductionCommands.java) 绑定开工、停产、暂停、自动续产和配置。 |
| 生产与事务协调 | [ProductionService](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java) 协调开工、结算和恢复。[ProductionBoundaryCoordinator](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBoundaryCoordinator.java) 在共享经济锁下执行 SQLite 事务。 |
| 双聚合 | [ProductionMethodConfiguration](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionMethodConfiguration.java) 管理 Effective、Pending 和各自版本。[ProductionExecution](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionExecution.java) 管理当前及最近 Batch、暂停原因、自动续产、边界和每建筑 Tick 标记。 |
| 批次与计划 | [ProductionBatch](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionBatch.java)、[ProductionRecipeResolver](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionRecipeResolver.java) 和 [WorkforcePlanningService](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/WorkforcePlanningService.java) 固定配方、方法有效版本和参产计划。 |
| 商品库存 | [GoodsInventory](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/domain/GoodsInventory.java) 管理地点容量、整批预留和幂等结算。[SqliteInventoryStore](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryStore.java) 原子提交库存变更和回执。 |

设备系统已移除。用工以各职业已雇整数人数为权威，每个所需职业至少达到 10%，生产速度取人数比例瓶颈。工具和机械仍可作为普通商品。

## 2. 开工与配置

手动和自动开工都执行 `ProductionService.startBatch()`。已有未完成批次时禁止新开工。重配置边界或开工意图未清理前也不能提交新批次。手动开工成功后开启自动续产。

1. `prepare()` 核验建筑运营、工资 CURRENT、有效配方、各职业用工、本地库存容量及原料。准备结果包含配置快照和执行版本。
2. 开工 Journal 提交 `StartIntent`，库存按 Batch ID 整批预留原料。
3. `commitStart()` 重新核验开工条件、完整预留和执行版本。自动开工还须确认 `automatic=true`。Execution 引用和 Batch 行同一事务提交。
4. 正式开工后清理意图。失败时依据已提交 Batch 核实结果，确认未提交才释放预留。未知结果保留意图和资源，由可信连接恢复。

运行中变更生产方法或人员时保存最新待生效请求，当前批次仍用开工时的配置。结算确认后进入唯一的 `RECONFIGURING` 边界，待生效方法、用工调整、相关工资变更和边界清除可在同一事务提交。成功后批次移入历史，才可评估自动续产。

## 3. 推进、普通停产与特殊中止

`ServerTickEvent.Post → ServerLifecycleEvents → ServerEconomyRuntime → ServerEconomyContext → ServerTickCoordinator → ProductionService.onServerTick() → ProductionBoundaryCoordinator → ProductionExecution.tick(1)`。

协调器受理一次原生服务器 Tick，在生产前处理工资。生产用每建筑 Tick 标记防止重复推进，所有待办处理完毕才确认本 Tick。满速批次需 200 Tick，经济一分钟为 1200 Tick。推进与客户端界面、建筑区块加载和墙钟无关。

- 普通 `stop` 经 `stopAfterCurrentBatch()` 只提交 `automatic=false`。当前批次及其进度、配置、暂停原因和原料预留保持原样，达到满进度后完整结算。同一经济锁和执行版本保护停产与自动开工的提交顺序。
- 欠薪的常规推进会暂停批次。工资恢复时，工资暂停原因解除，手动暂停等其他原因继续有效。
- 欠薪期间缩减已雇人数、出售或拆除的受控准备，仍可通过特殊路径中止未决定完工的批次。`SETTLING_ABORTED` 一旦提交，不恢复为运行状态。

正常完工进入 `SETTLING_COMPLETED`，结算消耗全部预留原料并产出完整配方数量。特殊中止进入 `SETTLING_ABORTED`，结算全额退料、零产出。库存按 Batch ID 保存回执，重复确认不会重复扣料或产出。普通停产也可在待结算或重配置期间提交，不介入这些恢复步骤。

## 4. 存档与恢复

[EconomySchema](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java) 当前为 v10，业务 JSON 为 format 9。生产方法配置、执行引用和 Batch 各有权威表。执行表保存 `automatic`、边界、暂停原因和每建筑 Tick 标记，批次表保存配置快照、进度和状态。`production_start_intents` 确保每建筑最多一个未清理开工意图。库存、用工和工资状态各有自己的存储。

[ServerEconomyContext](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java) 从完整经济检查点恢复内存 SQLite 权威，并装配 Tick、工资、生命周期和生产恢复。继续处理已接受 Tick 时保留执行标记和库存回执，不根据停服时间分配新 Tick。确认保存后，普通停产的 `automatic=false` 会随同批次状态一并恢复。

可从 `ProductionIntegrationTest`、`RecoveryAndControlsTest`、`BoundaryRecoveryTest` 和 `StartCommitRecoveryTest` 查看停产、结算和事务故障用例。建筑生命周期的独立恢复合同见 `BuildingLifecycleTest` 及其恢复测试。
