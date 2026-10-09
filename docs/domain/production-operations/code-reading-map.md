# 生产代码阅读地图

本图说明当前源码的入口与调用关系，不新增业务规则。领域依据见 [Production Operations 概览](overview.md)，迁移审计及保留耦合见 [包结构审计](package-refactoring.md)。

## 1. 从包结构开始

主源码前缀：`src/main/java/com/bbmurloc/victoriaeconomics/server/`。

```text
production/
  domain/          生产配置、执行聚合、批次实体、配方与选人计算
  application/     开工/推进/结算协调、时钟调度、人员统计查询
  port/            生产需要的库存、用工、工资事实与恢复日志契约
  infrastructure/  现有 SQLite 开工意图适配器
inventory/
  domain/          单地点商品库存、独立设备持有、设备请求与结果
  application/     设备变更与跨地点保存协调、设备查询索引
  infrastructure/  现有 SQLite 商品地点与设备持有适配器
building/          本轮保留的建筑身份、生产部门组合与建筑应用入口
workforce/employment/  本轮保留的正式任职与岗位预留权威
storage/sqlite/    本轮保留的 schema、建筑 checkpoint、生产 codec、任职保存
```

| 职责 | 优先阅读 | 关键区别 |
| --- | --- | --- |
| 生产配置聚合根 | [ProductionMethodConfiguration](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionMethodConfiguration.java) | 保护有效组合和唯一 Pending；仍由生产部门持有并随建筑保存。 |
| 生产执行聚合根 | [ProductionExecution](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionExecution.java) | 唯一未结束批次、受控进度/状态及 METHODS → EQUIPMENT → WORKFORCE 边界。 |
| 批次 Entity 与承诺值 | [ProductionBatch](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionBatch.java)、[ProductionBatchConfiguration](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionBatchConfiguration.java) | 身份与历史保留；不提供任意推进/状态 setter。 |
| 领域计算 | [ProductionRecipeResolver](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionRecipeResolver.java)、[WorkforcePlanningService](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/WorkforcePlanningService.java) | 配方依据固定规则；选人依据正式任职、资格、设备上限与整数瓶颈，结果是不可变 WorkforcePlan。 |
| 应用协调 | [ProductionService](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java) | 读取事实、取得承诺、持久化、补偿和恢复；不持有另一份库存或工资账本。 |
| 商品聚合根 | [GoodsInventory](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/domain/GoodsInventory.java) | 真实数量、单地点预留、可用量、容量及幂等结算回执。 |
| 设备聚合根 | [ProductionEquipmentHolding](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/domain/ProductionEquipmentHolding.java) | 独立设备容量、安装/未安装数量、批次保护、延期请求与锁定。 |
| 设备应用协调 | [ProductionEquipmentService](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/application/ProductionEquipmentService.java) | 共用写锁下复制 → 领域变更 → 保存 → 更新索引；两地点调拨一起保存。 |
| 人员统计查询 | [StaffingCalculator](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/StaffingCalculator.java) | 汇总 Registry/建筑/HR 的只读统计，返回 StaffingSnapshot；不创建批次、不选具体员工、不授予开工许可。 |

## 2. 开工：从命令或自动评估入口读起

外部入口为 [ProductionCommands](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/command/ProductionCommands.java) 的 `/ve production <building UUID> start`；自动入口是 `ProductionService.onEconomicTick()` 对启用自动生产的建筑评估后调用 `startBatch()`。

1. `ProductionService.startBatch()` 在共享经济写锁下检查未补偿开工意图、现有批次和边界，并通过 `WorkforcePort.reconcile()` 处理人事容量。
2. `prepare()` 重新读取建筑状态、工资限制、地点库存容量、设备请求和当前配方；`WorkforcePlanningService.plan()` 创建本批最少参产安排，`ProductionBatchConfiguration` 固定整个承诺。
3. `ProductionJournal.recordStart()` 先记录可恢复开工意图，再调用 `ProductionInventoryPort.reserve()` 和设备服务 `protectForBatch()`。库存地点使用本建筑 UUID。
4. 最后提交前再次调用 `prepare()` 并确认材料预留，随后由 `ProductionExecution.start()` 创建批次；`BuildingRepository.save()` 保存真实批次后清理开工意图。
5. 失败路径检查是否已有真实已提交 Batch。未提交则 `compensateStart()` 释放材料和设备保护；释放失败保留意图供重试，不能误释放已提交批次的资源。

PM 管理当前仍从 `BuildingService.selectProductionMethod()/requestProductionMethods()` 进入 `ProductionDepartment`，由 `ProductionMethodConfiguration` 校验完整目标，并根据执行状态立即生效或保存 Pending。本轮不改这个公共入口。

## 3. 推进：从服务端 tick 读起

`ServerLifecycleEvents.onServerTick(ServerTickEvent.Post)`
→ `ServerEconomyContext.onServerTick()`
→ [EconomicClock.onServerTick()](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/EconomicClock.java)
→ `ProductionService.onEconomicTick()`
→ `ProductionExecution.tick(1)`。

应用服务先恢复未完成开工意图，再遍历经济建筑 Registry。工资事实为 CURRENT 时恢复原批次，ARREARS 时按原进度终止，其余状态暂停；执行聚合只推进 ACTIVE 批次，并由原有整数进度单位判定何时进入结算。保存后继续调用 `settleAndHandleBoundary()`。

满速基础批次为 1200 个逻辑 tick。此调用链没有 Level、Chunk、BlockEntity 或 GUI 依赖，也没有墙上时间/离线补算入口。客户端不能设置生产进度。

## 4. 结算：从完成或终止读起

正常入口：`onEconomicTick()` 推进到 SETTLING_COMPLETED。

提前终止入口：`/ve production <UUID> stop` → `ProductionService.abortBatch()` → `ProductionExecution.terminate()` → SETTLING_ABORTED。工资补救失败也通过同一执行行为终止。历史进度不归零。

两者汇合到 `ProductionService.settleAndHandleBoundary()`：

1. `ProductionInventoryPort.settle()` 使用批次固定配方和实际进度；[SqliteInventoryStore](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryStore.java) 重新加载地点聚合，调用 `GoodsInventory.settle()`，原子保存扣料、产出、剩余释放和回执。
2. 相同批次重复结算返回同一回执；冲突结算被拒绝。已合法开始的生产允许产品首次造成超容，普通新入库和新开工仍受容量限制。
3. `ProductionExecution.settlementCompleted()` 标记批次真正结束，应用服务保存执行 checkpoint。
4. METHODS：`ProductionDepartment.applyPendingProductionMethods()` → `execution.methodsApplied()` → 保存；失败恢复配置和执行 checkpoint。
5. EQUIPMENT：设备服务 `flushPendingOperationsForBuilding()` → `execution.equipmentApplied()` → 保存。
6. WORKFORCE：`WorkforcePort.reconcile()` → `execution.workforceReconciled()` → 保存；未安置超员时停在可恢复边界。
7. 自动下一批在后续 tick 重新评估开工条件，使用新配置，而不是复用旧批次的开工许可。

## 5. 设备延期变更：从设备命令读起

`/ve production <UUID> install|uninstall|cancel_equipment`
→ `ProductionEquipmentService`
→ `ProductionEquipmentHolding`
→ [SqliteEquipmentRepository](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteEquipmentRepository.java)。

设备应用服务依据建筑类型取得固定设备类型/总容量，在共享锁下变更聚合副本；保存成功后才更新 `ProductionEquipmentRegistry` 查询索引。

- 有批次保护时安装/卸载请求等待真正结束。安装在受理时锁定全部所需可用未安装设备；数量不足拒绝，不预留未来安装空间。
- 无批次保护时按现有聚合行为处理请求。安装与卸载不改变设备总数量。
- 正常批次边界先结算并应用 Pending PM，再由 `flushPendingOperationsForBuilding()` 释放批次保护、调用 `processRequests()` 按统一受理顺序执行请求，保存设备结果。
- `EquipmentConfigurationRequest` 保留 requested/executed 数量与状态。部分执行释放未执行安装部分的锁；取消仅影响待执行请求，不撤销已经发生的数量变化。
- 显式请求 UUID 用于重试。已处理请求不重复执行。未安装设备调拨仍使用现有两地点原子保存事务。

## 6. 恢复与失败重试：从启动装配或 retry 读起

启动入口为 `ServerLifecycleEvents.onServerAboutToStart()`
→ `ServerEconomyRuntime.start()`
→ [ServerEconomyContext](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java)。

装配根按原有顺序恢复建筑/PM/执行、设备及任职，再构造生产应用服务并调用 `recoverStarts()`，最后接上 EconomicClock。生产服务每个经济 tick 也先调用 `recoverStarts()`。

管理员入口 `/ve production <UUID> retry` 调用 `ProductionService.retry()`：先恢复开工意图，再重试该建筑的结算/边界。

| 失败点 | 持久化证据与恢复入口 |
| --- | --- |
| 锁料或创建失败，释放也失败 | [SqliteProductionJournal](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionJournal.java) 保留 StartIntent；recoverStarts 重试材料释放、设备保护释放和意图清理。 |
| 创建已提交但确认丢失 | journal.containsBatch 检查真实批次；重新加载建筑并保留资源承诺，不按失败开工释放。 |
| 库存结算已保存，执行 checkpoint 保存失败 | GoodsInventory 的同批次 Settlement 回执使再次 settle 不重复扣料或产出。 |
| PM 保存失败 | 恢复有效/待定配置及执行 checkpoint；下次从 METHODS 继续。 |
| 设备已保存，执行边界保存失败 | 设备请求的持久化终态阻止再次执行；仍从 EQUIPMENT 重试。 |
| 人事容量尚未满足 | 保留 WORKFORCE 边界与旧批次历史，下一批不开工。 |

[SqliteBuildingRepository](../../../src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteBuildingRepository.java) 与包内 `ProductionStateCodec` 仍共同负责建筑、PM、执行及历史的现有 checkpoint。这是有意保留的持久化边界，不能从新包名推断已经拆成独立数据库事务。

## 7. 本轮验证及阅读限制

领域测试位于 `src/test/java/.../server/production/domain/`；库存设备测试位于 `.../server/inventory/domain/`；真实 SQLite 集成与恢复/并发控制测试及 TestEconomy 装配位于 `.../server/production/application/`。

测试使用真实临时 SQLite。工资及资格是明确的测试替身；正式默认适配器仍阻止未经权威事实许可的开工。实际工资付款、公司调任/NPC 同意及完整解雇流程没有因本次包迁移而补全。

本轮只重排已有类与引用，没有变更库存数量精度、JSON DTO/字段、SQL schema、命令权限或经济算法。真实 Minecraft 运行验收仍属于后续工作。

