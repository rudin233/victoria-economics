# M1.4：专用生产设备退出运行链与 Schema v4

日期：2026-10-20。状态：实现和本地验收完成，等待源码审查；没有 commit、push 或合并。

## 基线、授权与范围

开始时工作区干净，分支为 `refactor/m1-0-sqlite-transaction-cleanup`，实际 HEAD 为 `7df8fbcd7ba4dba667bb955933bd4eb231b42837`，与用户给出的 `7df8fbc` 基线一致。结束时 HEAD 与分支未变。没有 reset、checkout 到旧版本或覆盖既有用户改动。

已阅读 M0 源码审查及分阶段计划、Production / Labor / Payroll DDD 草案、M1.3 整数批次与 Schema v3 报告。本轮只执行 M1.4，使用新世界、严格拒绝旧格式、不自动迁移的已确认策略。

专用设备的类型、持有、安装、卸载、容量、保护、调拨、延期请求已从生产链删除。普通商品和库存容量继续存在。NPC Employment、资格、JobReservation、HR 比例目标与三阶段生产边界继续保留；没有实现 BuildingStaffing、RegionalLaborSupply、真实 Payroll、公司/地区身份、出售、拆除或 RECONFIGURING。

## 依赖分析与实现结果

| 层次 | 原依赖 | 本轮处理 |
|---|---|---|
| Definition | 建筑设备类型和数量上限；设备定义及注册；定义验证 | 删除设备字段、定义、注册表和验证。保留行业、普通 Good、PM、职业及 PMG 验证。 |
| Batch / Domain | installedEquipment、equipmentCapacity；按安装比例限制参产人数 | 删除设备快照及参数。人数上限只来自正式 PM 需求，保留整数比例、10% 门槛和唯一员工 UUID 校验。 |
| Inventory Domain | 独立设备资产、请求序列和 Batch 保护 | 删除专用资产、请求及保护端口。GoodsInventory 的业务实现不改，只更新其类说明。 |
| Application | 设备服务/索引；开工保护；边界延期操作；启动失败释放设备 | 全部移除。开工及失败补偿继续依靠商品 Reservation 和 StartIntent。 |
| Production facts | 同时给设备和 Employment 提供授权 | 仅删除设备边界授权接口；保留 PM 岗位容量、参产保护和具体员工保护查询。 |
| Staffing 查询 | 设备能力限制 effectiveWorkers；设备字段暴露给调用者 | 删除字段及算法；保留旧 HR targetRatio、目标人数、短缺/超额查询。 |
| Infrastructure | equipment_holdings 及设备 SQLite 仓储 | 删除仓储；新建世界启用不含该表的 Schema v4；旧库绝不执行 DROP TABLE。 |
| Context / 命令 | 设备定义、索引、服务、恢复装配；生产和 debug 设备命令 | 删除全部设备装配、getter 和命令入口；保留库存和普通商品命令。 |
| 测试与说明 | 混合库存/设备测试、设备夹具、边界故障测试 | 删除纯设备用例；库存、Batch、补偿、未知提交和人事保护继续验证。同步更新相关 package-info。 |

共删除 9 个设备专用业务类/接口及一个已无内容职责的 application/package-info。没有为兼容旧调用保留空设备服务、恒定设备能力或安装 API。

## 人数、资格与不可变 Batch

`BuildingTypeDefinition` 现在只含 id、industryId、productionMethodGroupIds。`ProductionBatchConfiguration` 现在只含：

```java
Map<String, String> productionMethodSelections;
ResolvedProductionRecipe resolvedRecipe;
WorkforcePlan workforcePlan;
long effectiveRevision;
```

WorkforcePlan 继续保存职业对应的真实 NPC UUID 列表及整数速度分子/分母；没有伪造员工，也没有提前切换汇总人数。

`WorkforcePlanningService.plan(building, demand, employment, qualified)` 不再接受任何设备参数。候选人仍必须属于当前建筑的正式职业，通过实际 qualification 判定，并按 UUID 去重和稳定排序。每职业可用参产人数最多为该职业 PM 满编需求；取各职业人数比例最小值，选取足以支持该瓶颈比例的实际员工。不同职业不能重复使用同一 UUID。

对每个正需求职业，用 `10L * n_i >= N_i` 精确校验至少 10%。BatchConfiguration 也重新校验参与集合、每职业人数上下限及速度分数与快照的一致性，防止错误持久快照绕过门槛。人数交叉乘法在 int×int 提升为 long 后进行，没有浮点权威进度。

满速仍为 200 economic ticks。3/5 速度仍在第 334 tick 完成。新的集成测试使用 6/10 劳工与 3/4 机械师，速度为 6/10；第 333 tick 为 1998/2000，第 334 tick 完成。开工后再招聘或提出解雇不会改变当前 Batch 的人员、配方和速度快照。受保护 NPC 的解雇继续排队，直到既有人员边界处理。

`StaffingCalculator` 删除设备索引、设备比例及人员上限计算。查询 effectiveWorkers 为 `min(正式雇员人数, PM 需求)`，速度取职业比例最小值；10% 判断改用整数比较。保留旧 HR targetRatio / hiringTargetWorkers / hiringShortfall / workersAboveHiringTarget，低招聘目标不会凭空减少或增加实际参产能力。

Staffing 查询继续是正式任职诊断，不是开工资格许可；它沿用原有的任职人数来源，不代替 ProductionService 的资格过滤、最终重检、工资门禁和人事 reconciliation。没有把查询结果接成绕过资格检查的启动通道。StaffingExpectation 和 HumanResourcesDepartment 未删除或替换，其职责留待 M2.2。

## 开工、商品与可靠边界

ProductionService 不再访问设备持有、不建立或释放设备保护、不等待设备延期请求。开工协议仍为：预检真实生产条件 → 记录 StartIntent → 全额预留本地商品 → 再检查 PM/Employment/资格/工资/Execution revision/Reservation → 保存 Batch → 清理 StartIntent。

失败且确认未提交时，补偿释放全部商品预留再完成 StartIntent；释放失败保留 intent，并阻止下一次开工。提交状态无法核实时，保留 intent 和原料承诺，不假定未提交而提前释放。恢复仍通过 Journal 对持久 Batch 的存在性及 Execution 归属确认。

M1.3 结算合同原样保留：

- COMPLETED：消耗完整冻结 inputs，生成完整 outputs，释放预留，持久化完整命令回执。
- ABORTED：不消耗原料、不产出商品，释放全部预留，持久化 ABORTED 命令回执。
- 同 BatchId 必须匹配 outcome 与完整 inputs/outputs；冲突重放不能覆盖结果。
- 库存回执或 Execution 检查点丢失时，保留 SETTLING 决定，重启按持久结果匹配，不重复结算。

`ProductionExecution.Boundary` 仍为 `NONE / METHODS / EQUIPMENT / WORKFORCE`。EQUIPMENT 仅为持久恢复协议中的空检查点，保留名称直到 M3.1；没有设备业务或设备资产授权。

推进流程仍是：

1. 结算回执确认后持久化 METHODS。
2. 应用/重新读取 PM，处理必要的旧人事协调，持久化进入 EQUIPMENT。
3. 再读取可信 Execution 与 PM，确认没有 pending 或生效 revision 不匹配，通过 `advanceLegacyBoundary()` 从 EQUIPMENT 进入 WORKFORCE，并执行 Execution CAS 保存。
4. 完成必要人事 reconciliation、再次核实 PM 后，持久清除 WORKFORCE；随后才允许新 Batch。

`advanceLegacyBoundary()` 必须处于 EQUIPMENT 且持有已结束 Batch；不能用于 ACTIVE、PAUSED 或 SETTLING。保存失败只丢弃 detached 修改，数据库继续保留原阶段。新 PM 到达晚期边界仍会重新进入 METHODS，生效 revision 不匹配仍先重走 PM 协调。没有简单跳过阶段，也没有提前实现单屏障。

## Schema v4 与 Codec

`EconomySchema.VERSION` 正式改为 4。新库 SQL 不再包含 equipment_holdings；其余表、库存地点 revision/CAS、Batch 状态和三阶段边界约束继续保留。

新的 Batch 合同：

```json
{"formatVersion":4,"fullSpeedTicks":200,"snapshot":{
  "productionMethodSelections":{"base":"method"},
  "resolvedRecipe":{"inputs":{"ore":10},"outputs":{"metal":5},"requiredWorkers":{"worker":1}},
  "workforcePlan":{"employees":{"worker":["00000000-0000-0000-0000-000000000003"]},"speedNumerator":1,"speedDenominator":1},
  "effectiveRevision":0
}}
```

installedEquipment、equipmentCapacity 不在 v4 字段集合中。即使把旧 Batch 的 formatVersion 改为 4，带旧设备字段的 JSON 也会被拒绝，不能被 Gson 静默忽略。

Inventory 保留 M1.3 的整数余额、普通库存容量、预留及 COMPLETED/ABORTED 完整命令合同，但统一标记 `formatVersion:4`；不会在同一 v4 世界内混用 v3 payload。没有商品数值变更或设备兑换。编码器自检、严格字段/类型/重复键/整数词法/UUID/命令归属验证继续保留。

继续使用 M1.2 原文件入口和同一兼容验证器：已有文件先用物理 READONLY 读取已提交快照，包含现有 WAL；通过后才使用无 CREATE 权限的可写连接重检；已有文件不能进入新建分支。业务恢复和写入在门禁完成后才开始。

| 输入 | 行为 |
|---|---|
| 不存在的新库文件 | 创建无专用设备表的 v4 布局，只写 v4 Codec。 |
| 完整合法 v4 | 正常加载，保留 NPC UUID、整数物料、200 tick 和现有可靠边界。 |
| v1 / v2 / v3 | 拒绝，不迁移、不清空、不重建、不兑换历史设备。 |
| 仅改版本号的旧布局 | 精确 DDL 不匹配，拒绝；不替用户删旧设备表。 |
| v3 JSON / 改标签后仍带设备字段的 Batch | Codec 拒绝，不能隐式读成 v4。 |
| 未知版本、不完整布局、坏 JSON、已有空文件 | 继续拒绝，原数据保持。 |

旧 v2/v3 独立 SQL fixture 均未修改；新增独立 schema-v4.sql。不存在 ALTER/DROP 旧库或历史设备折算路径。

## 保留的安全保护

SqliteTransactions、SqliteConnectionGuard、SqliteEconomyUnitOfWork、两个生产仓储、SqliteInventoryStore 及其事务业务实现本轮均未改动。

- M1.0：rollback 失败后的连接隔离；未知 commit 不自动再次提交；Savepoint 失败毒化外层提交；连接与派生句柄的可信状态保护。
- M1.1：写权限先于权威读取；显式 WriteSession；PM/Execution 双聚合 UoW 与独立 CAS；Execution revision 仅在外层提交确认后发布，失败丢弃事务对象。
- M1.2：物理只读预检、严格 DDL/版本/完整性/快照门禁及已有文件禁止重建。
- M1.3：库存 IMMEDIATE 事务内重新读取、地点 revision/CAS、完整命令幂等、长整数溢出拒绝、容量及排他预留。
- NPC：实际资格判定、正式任职岗位容量、JobReservation、具体参产员工保护、pending dismissal 和必要人事协调继续有效。RepositoryProductionFacts 仅去掉设备接口。

GoodsInventory 只有说明文字变化，商品的普通容量、已承诺产出超容、入出库、排他预留和整数校验没有因设备取消而改动。tools、iron、wood 等默认 Good 仍注册，PM 商品配方未删。普通机械/工具商品与已删除的专用设备资产是不同的数据。

## 测试与构建证据

所有 SQLite 集成测试使用隔离临时文件。本轮新增/改写重点：

- 不装配任何设备即根据合法员工开工；原设备 10% 安装量造成无法开工的断言，替换为合法员工满速开工。
- 参产规划只受岗位和真实合格员工限制，满编没有设备因子；逐职业 10% 整数边界继续通过。
- 新 StaffingQueryTest 4 项：HR 比例目标不制造能力、职业瓶颈、每职业最低人数，以及实际 NPC 招聘/待解雇之后 3/5 Batch 快照与第 334 tick 完成不变。
- 新建 v4 无设备表，编码后的 Batch snapshot 恰含四个规定字段；NPC UUID 保留。
- SchemaCompatibilityTest 从 77 增到 89 项：增加旧 v3 的六种 Batch 状态拒绝、旧 v3 主文件/WAL 字节保留、伪改元数据版本拒绝，以及旧 Batch/Inventory 标签和残留设备字段拒绝。
- 用真实 SQLite trigger 拒绝 `EQUIPMENT → WORKFORCE` UPDATE；确认状态仍为 EQUIPMENT、重试和开工受阻、库存只结算一次；移除故障后重启恢复旧 Batch，不重复结算。
- METHODS checkpoint、晚期 PM 重入、PM 生效 revision 不匹配、WORKFORCE 未完成、未知 Execution、暂停及结算中拒绝错误推进，原恢复测试继续验证。
- StartIntent 预留失败、部分预留、保存失败、释放失败、未知提交、回执丢失和重启恢复测试均保留，去掉设备断言后仍检查完整商品预留及 Batch 身份。
- 库存双连接读前写锁、并发修改及 CAS、commit 前/后故障、清理/rollback/close 失败和派生句柄隔离原测试继续通过。

删除 4 项纯设备业务用例：混合 InventoryAndEquipmentTest 中 3 项设备资产/请求测试、RecoveryAndControlsTest 中 1 项设备调拨测试。库存的 4 项测试移入 GoodsInventoryTest，未删除。其他混合场景保留并按新规则改写。新增 16 项（兼容 12、Staffing 查询/集成 4），总数由 247 变为 259。

| 测试类 | 数量 | 结果 |
|---|---:|---|
| GoodsInventoryTest | 4 | 通过 |
| SqliteInventoryContractTest | 5 | 通过 |
| SqliteInventoryConcurrencyTest | 14 | 通过 |
| ProductionDomainTest | 17 | 通过 |
| ProductionIntegrationTest | 19 | 通过 |
| BoundaryRecoveryTest | 10 | 通过 |
| RecoveryAndControlsTest | 12 | 通过 |
| StartCommitRecoveryTest | 11 | 通过 |
| WorkforceContractTest | 3 | 通过 |
| StaffingQueryTest | 4 | 通过 |
| BuildingProductionIsolationTest | 5 | 通过 |
| ProductionRepositoryTest | 11 | 通过 |
| EconomyUnitOfWorkTest | 29 | 通过 |
| SqliteTransactionsTest | 26 | 通过 |
| SchemaCompatibilityTest | 89 | 通过 |
| **总计（15 类）** | **259** | **0 失败、0 错误、0 跳过** |

已执行：

```powershell
.\gradlew.bat test build --rerun-tasks --console=plain
```

输出 `BUILD SUCCESSFUL in 36s`，9 个任务全部执行。`git diff --check` 通过。保留既有构建工具版本，没有为本轮升级依赖。

源码扫描没有专用设备类型、安装数量/容量字段、保护/释放/延期 API 或服务 getter 的运行引用。剩余 Equipment 文本仅为获准保留的 EQUIPMENT 枚举/SQL/过渡逻辑及历史说明。进一步检查本轮产物 `build/libs/victoria_economics-0.1.1-1.21.1.jar`，应用命名空间中的设备 class 条目为 **0**，避免已删除源码仍混入构建产物。

真实世界文件 `run/saves/新的世界/victoria_economics/economy.sqlite` 只计算文件哈希，没有用 SQLite 打开或写入。开始与结束 SHA-256 相同：

```text
3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8
```

## 实际文件变更清单

以下生产路径以 `src/main/java/com/bbmurloc/victoriaeconomics/` 为根。

| 文件 | 变更及原因 |
|---|---|
| common/definition/building/BuildingTypeDefinition.java | 删除专用设备类型与容量字段。 |
| common/definition/DefaultDefinitions.java | 删除设备定义注册、建筑设备参数；保留全部普通 Good 和 PM 岗位数据。 |
| common/definition/DefinitionValidator.java | 删除设备依赖、所有权映射和验证参数；其他引用验证保留。 |
| server/ServerEconomyContext.java | 删除设备定义、索引、仓储、服务、getter 和装配；更新人员查询及生产构造参数。 |
| server/command/ProductionCommands.java | 删除 install/uninstall/cancel_equipment/add_equipment 和设备状态输出；保留商品/库存命令。 |
| server/production/domain/ProductionBatchConfiguration.java | 删除设备快照；以职业正式需求检查人数上限。 |
| server/production/domain/WorkforcePlanningService.java | 删除设备参数及上限因子；保留资格和精确比例。 |
| server/production/domain/ProductionExecution.java | 保留 EQUIPMENT，改名为空过渡方法 advanceLegacyBoundary 并说明其边界前提。 |
| server/production/application/ProductionService.java | 删除开工、恢复和补偿中的设备依赖；可靠持久化空过渡。 |
| server/production/application/StaffingCalculator.java | 删除设备能力计算，保留旧 HR 比例目标和只读查询职责。 |
| server/production/application/StaffingSnapshot.java | 删除设备能力字段。 |
| server/production/application/OccupationStaffingSnapshot.java | 删除 equipmentLimitedMaximum 和过时说明，保留人员/HR 诊断。 |
| server/production/infrastructure/RepositoryProductionFacts.java | 删除设备边界授权接口；保留 Employment 必需事实。 |
| server/storage/sqlite/EconomySchema.java | 启用 v4，新布局不创建设备表。 |
| server/storage/sqlite/EconomyStateCodec.java | v4 字段集合与标签，不接受设备字段及旧格式。 |
| server/inventory/domain/GoodsInventory.java | 仅更新类说明，业务实现不改。 |
| server/workforce/employment/EmploymentService.java | 仅更新过时设备说明，旧人事业务实现不改。 |
| server/inventory/package-info.java | 更新为普通商品库存上下文说明。 |
| server/inventory/domain/package-info.java | 删除设备根及设备请求说明。 |
| server/inventory/infrastructure/package-info.java | 删除设备仓储说明，描述现有事务与门禁。 |
| server/production/domain/package-info.java | 说明真实 UUID/资格、人数速度和冻结快照。 |
| server/production/infrastructure/package-info.java | 明确事实适配器只向旧人事提供必要事实。 |

删除的生产文件：

1. common/definition/productionequipment/ProductionEquipmentDefinition.java
2. common/definition/productionequipment/ProductionEquipmentDefinitionRegistry.java
3. server/inventory/application/ProductionEquipmentRegistry.java
4. server/inventory/application/ProductionEquipmentService.java
5. server/inventory/domain/EquipmentConfigurationRequest.java
6. server/inventory/domain/ProductionEquipmentHolding.java
7. server/inventory/infrastructure/SqliteEquipmentRepository.java
8. server/inventory/port/EquipmentBatchLifecyclePort.java
9. server/production/domain/EquipmentCapacityCalculator.java
10. server/inventory/application/package-info.java

以下测试路径以 `src/test/java/com/bbmurloc/victoriaeconomics/server/` 为根。

| 文件 | 变更及原因 |
|---|---|
| inventory/domain/InventoryAndEquipmentTest.java → GoodsInventoryTest.java | 改名并保留全部 4 项库存测试；只删 3 项纯设备用例。 |
| inventory/infrastructure/SqliteInventoryContractTest.java | 更新 v4 JSON 标签/用例名称，保留真实保存失败和回执测试。 |
| production/application/TestEconomy.java | 删除设备夹具装配，保留真实 SQLite、员工和工资测试事实、全部必要故障开关。 |
| production/application/ProductionIntegrationTest.java | 删除设备断言；保留冻结配方、恢复、补偿、库存及岗位用例；合法员工直接开工。 |
| production/application/BoundaryRecoveryTest.java | 替换设备授权断言为可靠空过渡、PM 重入和各非结束状态拒绝推进。 |
| production/application/RecoveryAndControlsTest.java | 保留恢复/并发控制；用 SQLite trigger 验证空过渡持久化失败；只删纯设备调拨用例。 |
| production/application/StartCommitRecoveryTest.java | 去掉设备承诺断言，保留启动未知结果、材料保护及失败补偿。 |
| production/application/WorkforceContractTest.java | 去掉设备参数/断言，保留全部资格及正式任职容量测试。 |
| production/application/StaffingQueryTest.java（新增） | 人员查询、HR 比例、10% 门槛和真实 3/5 冻结 Batch 集成。 |
| production/domain/ProductionDomainTest.java | 新规划接口，无设备因素，原 200/334 tick、资格、整数及非法 PM 测试保留。 |
| production/infrastructure/ProductionRepositoryTest.java | 去掉设备构造参数，原 CAS/故障回归保留。 |
| storage/sqlite/EconomyUnitOfWorkTest.java | 去掉设备构造参数，29 项事务保护保留。 |
| storage/sqlite/SchemaCompatibilityTest.java | v4 正向合同，旧 v3 拒绝及数据库/WAL 保留，原有格式安全测试继续适用。 |

另新增：

- `src/test/resources/fixtures/sqlite/schema-v4.sql`：独立无设备布局 fixture。
- `docs/design/VE-M1.4-专用设备退出与Schema-v4-实现与验收-2026-10-20.md`：本报告。

生产源码 22 修改、10 删除；测试 11 修改、1 改名并删设备用例、1 新增；加 fixture 和报告，按改名计一次为 47 个文件（Git 未暂存时会把旧/新测试路径分别列出）。

## 未解决项、限制及 M2.1 前置条件

没有本轮验收失败或已知新增的一致性阻塞。仍需明确：

1. pig_iron_tools 没有正式职业需求，继续被最终配方规则拒绝；没有编造岗位，启用前需用户提供正式数据。
2. 正式 Payroll 和 NPC 资格权威的默认 unavailable/false 门禁保留；测试使用显式事实，不能视为已经实现正式工资或真实 NPC 资格数据源。
3. 旧 HR 模型仍在，Staffing 查询不是开工许可；旧 NPC 人事到 M3.1 才正式替换，不建立汇总人数的第二权威。
4. EQUIPMENT 仍是持久空检查点，必须成功保存才能进入 WORKFORCE；M3.1 才能在完整事务设计下替换三阶段边界。
5. M2.1 可在此基线上新增 Building 身份与 Staffing/Supply 持久化基础，但正式公司/地区标识来源必须明确，不能擅自分配默认公司或地区。初始化 Building/PM/Execution/Staffing 应复用已存在 UoW 原子提交，保留独立 Repository/CAS；区域 occupied/available 只派生。
6. M2.1 不应提前把汇总人数写入旧 Employment 并双写，也不应开放绕过真实工资应计的人事生效入口；对应依赖和切换仍按 M0 / DDD 的 M2.2、M3.1、M4 工作包执行。
7. M2.1 或后续若改变数据库布局、NPC 快照或其他持久合同，必须同步演进 Schema/Codec，不得在 v4 名义下混写不兼容结构。新世界/拒绝旧格式策略继续有效。
8. 本轮执行源码、JUnit 和真实临时 SQLite 验证，没有启动 Minecraft 写入用户世界。

M1.4 到此停止，等待下一轮审查；未开始 M2、M3 或 M4。
