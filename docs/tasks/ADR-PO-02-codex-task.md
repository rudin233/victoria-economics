# 交给 Codex 的本地实施任务 — ADR-PO-01 + ADR-PO-02（方案 B'）

你正在 `victoria-economics` 的 IDEA 本地源码工作区内工作。请以**实际修改代码并验证通过**为任务，而不是仅提交设计报告。

## 1. 当前背景

- Minecraft 1.21.1、NeoForge、Java 21、SQLite；服务器经济状态权威。
- 最后一次已查 GitHub `main`：`01a8c0a8a6380006fe9e937491675b67b5e23c76`，标题 `refactor(production): decouple workforce and inventory contracts`。以本地实际文件为准，先检查 Git 状态。
- 领域方案已确定：`ProductionMethodConfiguration` 与 `ProductionExecution` 是**两个独立聚合根**，不是 `ProductionSite` 单聚合。
- 用户明确确认**没有需要保留的旧世界经济数据**。采用方案 B'：重新设计干净的 SQLite 初始 Schema，不写旧版本存档迁移转换。但启动时不得自动删除现有世界数据库或无提示清空数据；如果遇到不兼容旧库，明确报错并提示另用新世界。
- 先阅读仓库内：`docs/domain/production-operations/overview.md`、`code-reading-map.md`、`context-decoupling-review.md`、`package-refactoring.md`。
- 同时阅读随本任务提供的 `VE-ADR-PO-02-双聚合持久化设计.md`，其中包含结构与业务协议要求。当前 overview.md 对双聚合的职责描述有效，但其现存持有/保存方式不是最终实现。

## 2. 具体执行要求

A. **先核对基线**：`git status`、`git log -3 --oneline`、检查本地未提交改动和全部生产测试；执行 `gradlew.bat test --console=plain`、`gradlew.bat build --console=plain`。保留现有无关修改。

B. **独立聚合及版本**：
- `ProductionMethodConfiguration` 拥有 `buildingId`、Effective/Pending PM、配置总 revision 与 effective revision；配置校验仍由原 `ProductionMethodRules` 维护。
- `ProductionExecution` 拥有 `buildingId`、current/last Batch 关联、边界、自动运行状态和执行 revision；其内部 `ProductionBatch` 状态仍受聚合控制。
- 将两个聚合的真实可变状态从 `EconomicBuilding`/`ProductionDepartment` 移出；不可复制三份可变权威。不要只做移动类/包名就声称完成。

C. **从零设计初始 SQLite Schema**：
- 至少区分 Building Identity、生产方法配置、生产执行、Batch 唯一权威记录、StartIntent；继续使用同一 SQLite 数据库。
- 分别新增 `ProductionMethodConfigurationRepository`、`ProductionExecutionRepository` 与 SQLite 适配器，版本条件更新；保存 Execution 及当前 Batch 必须是一个 SQLite 事务。
- 消除当前 Execution JSON 和 `production_batches` 同时保存可变 Batch 状态的重复权威：可以让 Execution 存 Batch ID/Boundary/Automatic，而 Batch 表保存配置快照/进度/状态。
- 保留 `ProductionJournal.containsBatch(batchId)` 的提交核实能力；表设计和查询必须以真实提交为准。
- 添加新世界数据库 Schema 版本记录，但不编写不存在的旧世界记录转换。**绝不在正常启动时 DROP/TRUNCATE 数据**。
- Building 初始化需要保证 Building、Configuration、Execution 记录逻辑完整；若用同一个 SQLite 事务共同创建多个聚合初始记录是允许的。

D. **改造应用层入口**：
- 生产 PM 修改不能继续通过修改 `EconomicBuilding.getProductionDepartment()` 的状态实现；把它交给生产应用层协调。`BuildingService` 应回归建筑身份/运营职责。
- `ProductionService` 使用两个独立 Repository/缓存索引，保持共享经济写锁、原料 Reservation、设备保护、工资和资格 fail-closed、最少参产人数、批次快照与经济 tick 规则。
- PM 提交与开工遵守服务端成功提交顺序；Batch **成功持久化**才算开工。
- 正式 Batch 创建失败时执行原有资源补偿；提交结果未知不能误释放已提交 Batch 的原料和设备保护；必须先核实权威 Batch 记录。
- 确认未提交但资源释放未完成时，PM 仍允许独立修改，而新 Batch 不得开启；提交结果未知时 PM 变更必须拒绝。

E. **可恢复边界协议**：
- 继续使用 METHODS → EQUIPMENT → WORKFORCE；配置生效、执行边界可分别提交。
- 必须支持“Effective B 已保存，Execution 仍在 METHODS”这一可恢复中间状态。重试不能重复应用有经济效果的操作。
- 即使 Execution 尚在 EQUIPMENT/WORKFORCE，期间新的 PM Pending 已独立保存，也必须在继续后续阶段之前及时重入 METHODS；重启也不能跳过它。
- PM 生效后立即启动必要人事再配置，但任职调整可以稍后完成；必要再配置失败或权威信息未知则拒绝开工。不得擅自模拟公司调任或 NPC 同意；旧非必要待执行请求可以取消，仍必要的合法旧请求保留。
- `ProductionEquipmentService` 当前依赖 `Building → ProductionDepartment.hasActiveBatch()`，拆除旧持有关系时必须替换成可信的生命周期查询/边界授权，活动、暂停、待结算或状态未知一律不得提前应用设备变更。旧 Batch 的授权不能释放新 Batch 的保护。
- PO-ES-20：`SETTLING_ABORTED` 终止决定一旦持久化不可逆，库存比例结算可重试但不能恢复旧批次。

F. **API/命令/装配**：
- 更新 `ServerEconomyContext` 的初始化、装配、持久化恢复与运行时索引；开工提交确认丢失时仅恢复相应 Execution，不要覆盖独立更新的 Configuration。
- 更新 `/ve production` 和 `/ve debug` 对生产配置/状态的查询与修改入口，保持现有命令语法和权限语义。
- 保留库存、设备、Employment、工资 Port 的现有权威归属及核心算法；不顺手实施 NPC、工资、市场、物流、GUI 等新功能。
- 修改域文档、代码阅读地图和 ADR，准确记录新聚合边界、新 DB 和实际调用链。

## 3. 必须新增/保留测试

逐项验证：
1. 新世界中创建建筑及两个聚合，保存/重启恢复。
2. Configuration 独立提交 Pending 与取消，不写 Execution；Execution tick 保存不写 Configuration；各自 revision 冲突拒绝过期覆盖。
3. 双操作竞争：PM 先提交→Batch 采用新配置；Batch 先提交→新 PM Pending。
4. 原料锁和设备保护成功但 Batch 未提交时补偿；确认提交且确认回执丢失时保留资源承诺。
5. 旧 StartIntent 未清理完：确认未提交仍允许 PM 修改但阻止开工；提交结果未知暂拒 PM。
6. Execution 状态未知拒 PM；已确认 Batch 受保护但进度保存暂时失败，Pending PM 仍可保存（不能把“整库不可写”伪装成此场景）。
7. 库存结算成功但执行状态保存失败：下一次重试不重复扣料/产出；比例终止相同。
8. PM 已应用、Execution METHODS checkpoint 未保存；自动恢复到后续阶段。
9. 在 WORKFORCE 阶段 PM 再次修改且执行边界回退保存失败；重启仍检测新 Pending 并重入 METHODS。
10. 设备已应用而 Execution EQUIPMENT checkpoint 保存失败：重试不重复安装或卸载。
11. PM 空闲立即生效，即使缺料/欠薪/缺工；工资与 NPC 资格正式默认依然 fail-closed。
12. 人事必要协调未完成阻止开工，非阻塞人事事项不阻止开工；不伪造真实 NPC 调任完成。
13. 1200 服务端 tick 满速、不可变 Batch 快照、10% 门槛、最少参产人数、超容完工入库等旧测试均继续通过。

原有 46 项测试尽量保留并适配新的 Repository 和装配方式。旧数据迁移专属测试可替换为“新 Schema 初始化、版本、约束与非破坏性启动”测试，必须说明替换原因。

## 4. 执行过程与交付

分五组提交**本地可验证修改**：M1 Schema/Repository；M2 移除 Building 生产状态；M3 开工/进度/结算恢复；M4 边界与人事/设备安全协调；M5 命令适配/文档/回归。不要一次在整个代码库实施无法定位问题的大改。

每组修改后运行相关 Gradle 测试，最终执行：

```powershell
.\gradlew.bat test --console=plain
.\gradlew.bat build --console=plain
```

报告完成的文件、两个聚合的真实权威状态所有者、Schema 与 Repository 设计、故障恢复证据、测试计数和结果、尚未完成的上下文依赖。说明是否在正式游戏端到端运行过，不能将纯 JUnit 测试冒充游戏内验收。

**不要只给设计计划；请在本地实际修改代码并反复修复到测试通过。禁止主动 push、创建/合并 PR、删改无关用户文件。**
