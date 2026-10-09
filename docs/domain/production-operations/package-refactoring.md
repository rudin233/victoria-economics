# 第一轮 DDD 包迁移审计

## 基线与约束

- 本地 HEAD：`465b2ec`（`feat(production): add recoverable production execution and settlement`）。
- 开始时唯一未提交项为已暂存的 [Production Operations 概览](overview.md)，本轮保留其字节内容和暂存状态。
- 领域归属以该概览和本地实际代码为依据；此前的 [生产审计记录](../../production-audit.txt) 只用于理解现有实现。
- 本轮只移动类、更新 package/import/代码中的全限定类型引用，并补充包职责与阅读文档。类名、签名、身份、不变量、算法、锁、事务、SQL、JSON 字段及命令协议均不调整。
- `server.production` 对应 Production Operations，`server.inventory` 对应 Inventory & Storage；上下文内采用浅层 `domain/application/port`。已有 SQL 适配器使用非空 `infrastructure` 包，避免把 JDBC 放进领域层。
- 当前 IDEA MCP 未提供 Move Class；通过 IDEA 文件补丁接口执行明确的 Move to 与引用更新，用 IDE 检查、Java 代码主体比较和每组 Gradle 测试验证。不声称调用了不可用的 PSI Move Class。

## 当前类 → 领域归属 → 目标包 → 迁移风险

下表路径相对 `src/main/java/com/bbmurloc/victoriaeconomics/`。风险列描述纯迁移时需要保持的现有约束，不引入新业务规则。

| 当前类路径 | 领域归属与角色 | 第一轮目标包 | 迁移风险 |
| --- | --- | --- | --- |
| `server/production/method/ProductionMethodConfiguration.java` | Production Operations / 配置聚合根（现由部门持有） | `server.production.domain` | 中：建筑组合持有与回滚 checkpoint；只修正引用，不拆持久化。 |
| `server/production/method/ProductionMethodSelections.java` | Production Operations / 不可变值对象 | `server.production.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/production/method/ProductionMethodRules.java` | Production Operations / 固定定义与组合规则 | `server.production.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/production/ProductionExecution.java` | Production Operations / 执行聚合根（现由部门持有） | `server.production.domain` | 中：嵌套 State/Boundary 被序列化适配器引用；枚举和字段不变。 |
| `server/production/batch/ProductionBatch.java` | Production Operations / 聚合内部 Entity 快照 | `server.production.domain` | 中：批次 DTO 与历史加载依赖；构造器、ID、JSON 字段不变。 |
| `server/production/batch/ProductionBatchConfiguration.java` | Production Operations / 批次承诺值对象 | `server.production.domain` | 中：Gson 持久化 record；字段名及内容不变。 |
| `server/production/calculation/ProductionRecipeResolver.java` | Production Operations / 无状态领域服务 | `server.production.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/production/calculation/ResolvedProductionRecipe.java` | Production Operations / 配方值对象 | `server.production.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/production/calculation/EquipmentCapacityCalculator.java` | Production Operations / 设备事实到能力比例的纯计算 | `server.production.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/workforce/staffing/WorkforcePlanningService.java` | Production Operations / 无状态选人领域服务 | `server.production.domain` | 低：跨到生产上下文；仍接受现有 EmploymentRecord 与资格事实。 |
| `server/workforce/staffing/WorkforcePlan.java` | Production Operations / 不可变参产安排 | `server.production.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/inventory/GoodsInventory.java` | Inventory & Storage / 单地点商品库存聚合根 | `server.inventory.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/inventory/equipment/ProductionEquipmentHolding.java` | Inventory & Storage / 独立设备持有聚合根 | `server.inventory.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/inventory/equipment/EquipmentConfigurationRequest.java` | Inventory & Storage / 聚合内请求及执行结果 | `server.inventory.domain` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/inventory/equipment/ProductionEquipmentRegistry.java` | Inventory & Storage / 应用层设备查询索引 | `server.inventory.application` | 低：它是应用索引，不是另一份聚合根；不改索引与锁语义。 |
| `server/productionequipment/ProductionEquipmentService.java` | Inventory & Storage / 设备应用协调服务 | `server.inventory.application` | 中：建筑事实、共享锁和跨地点原子保存；仅移动包。 |
| `server/storage/sqlite/SqliteInventoryStore.java` | Inventory & Storage / SQL 与生产库存端口适配器 | `server.inventory.infrastructure` | 中：保留现有 SQL/JSON 与 load-modify-save 模式，不增接口。 |
| `server/storage/sqlite/SqliteEquipmentRepository.java` | Inventory & Storage / 设备持有持久化适配器 | `server.inventory.infrastructure` | 中：保留两地点 saveAll 的事务边界和 Gson 状态格式。 |
| `server/production/ProductionService.java` | Production Operations / 可恢复应用协调服务 | `server.production.application` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/production/EconomicClock.java` | Production Operations / 应用调度器 | `server.production.application` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/workforce/staffing/StaffingCalculator.java` | Production Operations / 跨上下文统计查询服务 | `server.production.application` | 中：统计模型仍读现有 Registry/HR；计算、容差和返回字段不变。 |
| `server/workforce/staffing/StaffingSnapshot.java` | Production Operations / 应用查询结果 | `server.production.application` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/workforce/staffing/OccupationStaffingSnapshot.java` | Production Operations / 职业统计查询结果 | `server.production.application` | 低：仅包和引用变化；现有行为与数量/状态表示保持。 |
| `server/inventory/ProductionInventoryPort.java` | Production Operations / 消费者拥有的库存出站契约 | `server.production.port` | 中：迁至消费者端口；暂保留 GoodsInventory.State/Settlement 类型耦合。 |
| `server/storage/sqlite/SqliteProductionJournal.java` | Production Operations / 开工意图持久化适配器 | `server.production.infrastructure` | 中：仍查现有批次与意图表；不拆共享数据库。 |
| `server/production/port/WorkforcePort.java` | Production Operations / 用工出站契约 | `server.production.port` | 保留；EmploymentRecord 的跨上下文事实耦合暂不改 DTO。 |
| `server/production/port/BuildingPayrollPort.java` | Production Operations / 工资限制出站契约 | `server.production.port` | 保留；不复制工资账本，不修改 CURRENT/RECOVERY/ARREARS/UNAVAILABLE。 |
| `server/production/port/ProductionJournal.java` | Production Operations / 恢复日志出站契约 | `server.production.port` | 保留；意图身份和恢复方法语义不变。 |
| `server/building/department/production/ProductionDepartment.java` | Production Operations / 建筑内生产组合门面 | `server.building.department.production（保留）` | 高：现由 EconomicBuilding 创建并持有，PM 与执行统一 checkpoint；独立化留待后续。 |
| `server/building/EconomicBuilding.java` | Building Identity / 当前建筑组合根 | `server.building（保留）` | 高：包含 HR 与生产部门；拆分将影响创建、恢复和保存边界。 |
| `server/building/BuildingService.java` | Building Identity / 现有建筑与 PM 应用入口 | `server.building（保留）` | 中：PM 命令与保存回滚一起执行；本轮只更新生产类 import。 |
| `server/building/BuildingRepository.java` | Building Identity / 当前组合 checkpoint 契约 | `server.building（保留）` | 高：保存整个建筑与生产状态；不能以移动为由拆事务或签名。 |
| `server/building/BuildingRegistry.java` | Building Identity / 建筑查询索引 | `server.building（保留）` | 低：现有恢复替换入口和查询职责保留。 |
| `server/building/BuildingStatus.java` | Building Identity / 运营状态值 | `server.building（保留）` | 低：存储枚举名称不变。 |
| `server/building/department/hr/HumanResourcesDepartment.java` | Employment & Compensation / 现嵌入建筑的 HR 设置 | `server.building.department.hr（保留）` | 中：是设置持有，不是任职账本；与建筑的组合关系不动。 |
| `server/building/department/hr/StaffingExpectation.java` | Employment & Compensation / 人事目标值对象 | `server.building.department.hr（保留）` | 低：不改变目标比例默认值或统计计算。 |
| `server/workforce/employment/EmploymentRecord.java` | Employment & Compensation / 正式任职事实 | `server.workforce.employment（保留）` | 中：生产选人及持久化直接依赖；不变更员工/建筑/职业身份。 |
| `server/workforce/employment/JobReservation.java` | Employment & Compensation / 岗位预留事实 | `server.workforce.employment（保留）` | 中：接纳顺序、员工唯一性和保存格式不变。 |
| `server/workforce/employment/EmploymentRegistry.java` | Employment & Compensation / 现有任职权威与索引 | `server.workforce.employment（保留）` | 高：兼有状态保护、预留、延期解雇和索引；不机械改称 Repository 或拆聚合。 |
| `server/workforce/employment/EmploymentService.java` | Employment & Compensation / 任职应用服务及 WorkforcePort 适配器 | `server.workforce.employment（保留）` | 高：岗位容量与边界人事变更涉及外部事实；保留接口、写锁与原子保存。 |
| `server/storage/sqlite/SqliteBuildingRepository.java` | Building Identity + Production / 现有跨组合 SQL checkpoint | `server.storage.sqlite（保留）` | 高：同事务保存建筑、有效/待定 PM、执行和批次历史，不分拆。 |
| `server/storage/sqlite/ProductionStateCodec.java` | 生产状态编码 / 建筑 checkpoint 的包内辅助类 | `server.storage.sqlite（保留）` | 高：package-private，保持与 SqliteBuildingRepository 同包；DTO 字段和 Gson 格式不动。 |
| `server/storage/sqlite/SqliteEmploymentRepository.java` | Employment & Compensation / 现有 SQL 适配器 | `server.storage.sqlite（保留）` | 高：全量 Registry.State 持久化边界；后续需与任职模型一起评估。 |
| `server/storage/sqlite/EconomySchema.java` | 共享数据库基础设施 | `server.storage.sqlite（保留）` | 高：禁止改变 schema、迁移与现有世界数据。 |
| `server/storage/EconomyDatabase.java` | 共享数据库生命周期适配器 | `server.storage（保留）` | 中：服务器世界路径和连接生命周期不变。 |
| `server/ServerEconomyContext.java` | 应用装配根 | `server（保留）` | 中：只更新 import；初始化、旧设备迁移、恢复顺序和共享锁不动。 |
| `server/ServerEconomyRuntime.java` | Minecraft 服务端上下文生命周期 | `server（保留）` | 低：不改变每个服务器的上下文管理。 |
| `server/event/ServerLifecycleEvents.java` | NeoForge 生命周期及 tick 适配器 | `server.event（保留）` | 中：不改事件订阅或 tick 接入。 |
| `server/command/ProductionCommands.java` | Minecraft 命令适配器 | `server.command（保留）` | 中：不改命令语法、权限或网络/客户端行为。 |
| `common/block/BuildingAnchorBlock.java` | Minecraft 建筑锚点查询适配器 | `common.block（保留）` | 低：维持普通右键只查询的行为。 |
| `common/blockentity/BuildingAnchorBlockEntity.java` | Minecraft 建筑锚点身份绑定 | `common.blockentity（保留）` | 中：UUID/NBT 保存格式不动。 |

现有 `common.definition` 中的建筑、商品、职业、生产方法与设备定义及其 Registry 保持原包；它们提供已确认的运行期固定定义，本轮不调整所有权或加载策略。

## 分组验证计划

1. 生产领域：迁移 11 个主源码类和 ProductionDomainTest；运行领域测试，并编译全部主/测试源码。
2. 库存与设备：迁移 7 个主源码类和 InventoryAndEquipmentTest；运行库存设备、生产集成和恢复控制测试。
3. 生产应用与端口：迁移 7 个主源码类和三个集成测试/装配类；运行全部 46 项既有测试。
4. 最终明确运行 `gradlew.bat test --console=plain` 和 `gradlew.bat build --console=plain`；核对 package 与路径、Java 主体、序列化字符串和原暂存文档。

## 后续建议（本轮不实施）

- 将 EconomicBuilding 从生产与 HR 状态所有权中拆出，需要先设计建筑创建/删除、生产聚合重新加载、PM 与执行 checkpoint 的持久化边界及失败恢复；不能先改包再顺便拆事务。
- ProductionDepartment 当前是嵌入建筑的生产组合门面。可在后续解耦后归入 `production.domain`，本轮不把它作为独立聚合复制出来。
- BuildingService 的 PM 应用入口可在后续迁至生产应用层，但要同时设计原入口兼容与保存回滚契约；本轮不改公共方法语义。
- EmploymentRegistry 应先明确任职唯一性、岗位预留、延期解雇与查询索引的所有权，再决定分离领域状态与查询索引；不能把完整 Employment & Compensation 搬进生产。
- WorkforcePort 和 ProductionInventoryPort 当前复用其他上下文的事实/状态类型。未来如需独立演进，可设计最小出站 DTO；本轮不新增空端口、复制账本或改签名。
- StaffingCalculator 保留为应用层跨上下文统计查询，其结果不作为开工授权，也不替代 WorkforcePlanningService。后续如解耦 Registry 查询，应另立保持现有数值结果的任务。
- 共用 EconomyDatabase/EconomySchema 与建筑 SQL checkpoint 仍是已存在的基础设施耦合。独立 repository、连接或事务边界属于另一次实质变更。

## 实际验证结果

| 阶段 | 实际命令 / 检查 | 结果 |
| --- | --- | --- |
| 基线 | `gradlew.bat test --console=plain` | BUILD SUCCESSFUL；46 项通过。 |
| 第一组 | `gradlew.bat test --tests '*ProductionDomainTest' --console=plain` | BUILD SUCCESSFUL；11 项通过；全部主/测试源码编译成功。 |
| 第二组 | `gradlew.bat test --tests '*InventoryAndEquipmentTest' --tests '*ProductionIntegrationTest' --tests '*RecoveryAndControlsTest' --console=plain` | BUILD SUCCESSFUL；35 项通过。 |
| 第三组 / 最终 test | `gradlew.bat test --console=plain` | BUILD SUCCESSFUL；46 项，0 failures、0 errors、0 skipped。 |
| 最终 build | `gradlew.bat build --console=plain` | BUILD SUCCESSFUL；打包成功，复用上一行已执行并通过的测试结果（test UP-TO-DATE）。 |
| IntelliJ | 核心类错误检查与 `build_project` | 无错误；IDE 构建 isSuccess=true。 |
| 源码核对 | 所有 80 份原 Java 文件按迁移关系比较：忽略 package/import、注释和格式，规范化迁移产生的全限定引用 | 代码主体一致；包括方法、身份字段、数量计算、SQL 与字符串字面量。 |
| 路径核对 | 89 份最终 Java 文件（80 原文件 + 9 份 package-info） | package 声明均与文件路径匹配，旧包引用无残留。 |
| 当前构建产物 | `build/libs/victoria_economics-0.1.1-1.21.1.jar`（依据当前 gradle.properties） | 已包含新的生产/库存包；旧 batch/calculation/method、productionequipment、inventory.equipment、workforce.staffing 类残留为 0。 |
| 文档与 Git | 22 个本地文档链接、`git diff --check` 和 `git diff --cached --check` | 全部通过；暂存区仅保留原 overview.md。 |

完成 25 个主源码类和 5 个测试/装配类的迁移。增加 9 份中文包职责说明；未创建空接口或额外领域实现。

IDEA 文件移动曾自动暂存部分迁移文件；仅将本轮新增的源码暂存恢复为未暂存。原已暂存 `overview.md` 的 Git blob 仍为 `0f023504cf02f5f9e7c59a4f6b739b3f06159376`，文件 SHA-256 仍为 `FB9A3F3BA4D2018BE5090FEA493688DF568224F5B21E78F251019D91B0AA8857`。

现有 Gradle 10 弃用提示仍存在，本轮没有修改构建脚本。测试为 Java/真实临时 SQLite 验证，未启动 Minecraft 服务端或执行游戏内验收。工作区改动未提交、未 push、未创建或合并 PR。
