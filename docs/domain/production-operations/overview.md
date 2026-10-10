# Production Operations — 生产运营限界上下文

## 1. 领域定位

Production Operations 负责 Victoria Economics 中生产建筑的生产方案管理、配方解析、生产批次组织、实际参产人员安排、生产进度推进和生产结果结算的业务协调。

本上下文以经济建筑作为生产活动的业务载体，但建筑身份、库存、任职、工资与资金分别由其他上下文管理。

当前采用 [ADR-PO-02](../../architecture/adr/ADR-PO-02.md) 确认的双独立聚合方案。`EconomicBuilding` 仅持有身份、类型和运营状态；两个生产聚合以 `buildingId` 关联，各自通过独立 Repository 保存，不再嵌入建筑部门。

## 2. 权威事实

Production Operations 是以下事实的权威来源：

- 每栋生产建筑当前有效的 Production Method 组合。
- 当前唯一待生效的生产方案目标。
- 当前配方所需的原料、产品及职业满配需求。
- 当前生产批次的身份、配置快照、参产人员、进度和生命周期。
- 生产是否已经完成，以及生产资源应当按什么进度比例结算。
- 某员工是否仍受当前生产批次的人员安排保护。
- 实际参产状态及其发生变化的时间事实。

## 3. 聚合与不变量

### ProductionMethodConfiguration

保护有效生产方法组合始终合法，并管理最多一个 Pending 目标。

运行中的生产批次不受后续方法选择变更影响。

`configurationRevision` 随实际配置变化递增，包含 Pending 新增、替换和取消；`effectiveRevision` 仅在有效组合真正改变时递增。是否即时生效由生产应用协调器在共享经济写锁中查询可信执行保护事实后决定，不能仅相信外部传入的延期标志。

### ProductionExecution

保证每栋生产建筑最多存在一个未真正结束的生产批次。

批次开始时固定配方、人员和设备能力。

暂停、恢复、完成、提前终止与结算属于受控的生命周期行为。未完成结算的批次不得被覆盖。

执行拥有独立 `executionRevision`。Batch 是内部 Entity：存档只在 `production_batches` 中保存一份可变进度/状态，执行表仅保存 current/last ID 与边界。Execution 和内部 Batch 在同一 SQLite 事务提交，成功提交才算正式开工。成功保存的 `SETTLING_ABORTED` 不得恢复运行或改变终止进度。

`methodsEffectiveRevision` 记录执行边界已处理的有效配置版本，是可恢复协调检查点，不是第二份 Effective PM 权威。Configuration 生效与 Execution 边界可以分别提交，必须容许“新 Effective 已保存而 Execution 仍在 METHODS”。

## 4. 核心领域服务

ProductionRecipeResolver 根据有效方法组合解析不可变配方。

WorkforcePlanningService 根据有效任职人员、职业需求和设备能力，选择足以支持最大可行生产速度的最少参产人员。

## 5. 与其他上下文的协作

- **Building Identity**：取得建筑身份、类型和运营条件。
- **Employment & Compensation**：取得有效任职、资格与工资限制；提供职业容量需求、批次人员保护和参产状态事实。
- **Inventory & Storage**：请求原料排他锁定、生产消耗、产品入库及设备能力状态。
- **Company Finance**：不直接持有或修改公司现金；实际工资付款通过 Employment & Compensation 协调。

## 6. 生产业务流程

生产应用服务取得相关权威事实，判断开工条件，锁定完整原材料，并由 ProductionExecution 正式创建批次。

生产进度由服务端经济时钟驱动，不依赖客户端界面或建筑区块持续加载。

正常完工或提前终止后，按照实际进度结算原料与产品，并依次处理生产方案、设备配置与人事事项，最后评估下一批。

任何跨上下文失败都不能导致重复扣料、重复产出、无主资源锁定或提前释放受保护人员。

生产应用服务在 EQUIPMENT、WORKFORCE、启动恢复和新开工之前检查最新 Pending 与 Effective 版本，发现迟到配置则先重入 METHODS。PM 生效后立即尝试岗位 Reservation 与必要人事再配置；未完成或无法核实必要调整时禁止新批次，不能伪造 NPC 调任同意或真实解雇结果。

Configuration 独立提交不需要执行进度写入。执行保护状态可信时，进度保存失败不阻止 Pending；Batch 提交结果或执行保护未知时拒绝 PM。明确未提交但资源补偿尚未结束时允许 PM，仍禁止新开工。

## 7. 非职责范围

Production Operations 不决定公司的采购价格、销售价格、物流路径、资产所有权、工资债务或实际付款。

这些业务由对应上下文承担。生产仅表达对原材料、设备能力和劳动力的需求以及真实生产执行结果。

## 8. 存储与运行限制

双聚合使用同一世界 SQLite 和原共享经济写锁；Repository 使用独立 revision 条件更新，加载结果为已验证的独立对象，不另建可变生产 Registry。建筑和两个生产根的首次创建在一个事务完成，失败不发布半初始化建筑。

本次初始 Schema 为版本 2，不开发旧世界迁移。不兼容数据库明确拒绝并提示新世界，正常启动不清空或删除原数据；库存、设备和任职原业务 JSON 保持。

正式工资及资格来源仍未实现，默认分别 UNAVAILABLE 和 false，因此正式开工继续受限。具体调用链、存档和失败恢复见 [代码阅读地图](code-reading-map.md)，实际验证见 ADR 实施记录。
