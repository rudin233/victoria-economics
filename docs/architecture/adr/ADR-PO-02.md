# Victoria Economics — ADR-PO-02：双聚合与全新 SQLite 初始 Schema

**状态：已确认采用方案 B'；本文为实施设计，具体 SQL 字段允许在代码审查中做等价调整。**  
**基础版本：** GitHub `rudin233/victoria-economics`，`main` HEAD `01a8c0a8a6380006fe9e937491675b67b5e23c76`（2026-10-09）。  
**关联决策：** ADR-PO-01（两个独立聚合根）；生产事件风暴 PO-ES-01～PO-ES-23。  
**数据条件：** 用户确认无需要保留的旧世界经济数据，因此不开发历史数据迁移；但任何运行时启动流程都不能自动 `DROP` 或 `DELETE` 已有世界数据。

## 1. 背景与问题

当前 `EconomicBuilding` 内嵌 `ProductionDepartment`，后者同时持有 `ProductionMethodConfiguration` 和 `ProductionExecution`。`SqliteBuildingRepository.save(building)` 同时保存建筑、当前与 Pending PM、执行 JSON 和 Batch 历史。即使两个 Java 类具有各自不变量，目前仍无法独立持久化；尤其 PO-ES-23 要求在执行进度保存暂时失败、保护状态可信的情况下仍能提交 Pending PM。

## 2. 决策

1. `ProductionMethodConfiguration` 与 `ProductionExecution` 分别成为以 `buildingId` 定位的独立聚合根，拥有独立的修改边界、Repository 与持久化版本。
2. `EconomicBuilding` 仅权威管理建筑身份、类型和自身运营状态，不再直接持有可修改的生产配置或执行状态。需要兼容门面时，只允许委托，不允许存储第二份生产权威状态。
3. 两个聚合使用同一世界级 SQLite 数据库，不创建独立数据库或微服务。现阶段沿用 `ServerEconomyContext.economyLock` 保证本服务端内事务操作顺序，Repository 同时采用条件更新防止过时版本覆盖。
4. 初始 Schema 直接按双聚合重新设计。重用原本合理的表和字段是允许的；不需要刻意保留旧表名或旧 JSON 结构，也不添加旧版数据升级逻辑。
5. Batch 正式开工的提交点：`ProductionExecution` 的当前 Batch 与其唯一权威 Batch 记录在同一个 SQLite 事务成功提交；原料 Reservation 与设备保护属于先行的可补偿承诺。提交结果未知时冻结影响判定的操作并核查事实。
6. 生产方法与执行状态通常分别提交。需要检查另一聚合的操作由应用协调器在共享写锁中完成；不得把跨聚合逻辑塞进某一个聚合根。
7. Inventory 的数量、锁定、设备持有与结算，Employment 的任职与岗位 Reservation，Finance/Payroll 的真实付款继续属于其各自限界上下文。本轮不扩展这些上下文。

## 3. 聚合职责与版本

### A. ProductionMethodConfiguration

标识：`buildingId`。  
核心状态：`effectiveSelections`、唯一可选 `pendingSelections`、`configurationRevision`、`effectiveRevision`。  
`configurationRevision` 在实际配置变更（含 Pending 新增、替换、取消）成功时递增；`effectiveRevision` 仅在 Effective PM 真正改变时递增。两者均为单调递增逻辑版本，不是生产方法的科技 `tier`。  
核心约束：PM 组/方法归属、基础 PM 等级和完整组合校验；Pending 最多一份。  
注：是否立即生效、是否可以提交新的 Pending，需要消费应用协调层取得可信的 Execution 状态。不能仅依赖调用方传入不可信的 `boolean defer` 就宣称完整的不变量得到保证。

### B. ProductionExecution

标识：`buildingId`。  
核心状态：唯一 `currentBatchId`（若存在）、`lastBatchId`（若存在）、`boundaryStage`、`automatic`、`executionRevision`。Batch 的配置快照、进度与状态归 ProductionExecution 管理。  
核心约束：同一建筑最多一个未真正结束的 Batch；Batch 快照固定；`SETTLING_ABORTED` 代表终止决定已经不可逆；结算前不可覆盖当前 Batch；批次边界依次处理 METHODS、EQUIPMENT、WORKFORCE。  
如果需要记录用于恢复的协调标记，应放在明确的协调状态中或 Execution 自身的边界状态中，不得形成第二份 Effective PM 的权威副本。

### C. 不再构成聚合的对象

`ProductionBatch` 是 Execution 内部有身份的 Entity。`ProductionBatchConfiguration`、`ProductionMethodSelections`、`ResolvedProductionRecipe`、`WorkforcePlan` 是领域值/快照；`WorkforcePlanningService` 与配方解析职责仍是领域服务；`ProductionService` 仍为 Application Service / Process Coordinator。

## 4. SQLite 初始 Schema 的建议形状

以下 DDL 是**设计轮廓**，需由 Codex 根据 SQLite/JDBC 实际模型完善，并为全新数据库编写可重复初始化过程。可采用明细表或 JSON 序列化，但不得双重权威保存同一 Batch 状态。

```sql
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS economy_schema_migrations (
    version INTEGER PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS economic_buildings (
    id TEXT PRIMARY KEY,
    building_type_id TEXT NOT NULL,
    status TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS production_method_configurations (
    building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id),
    effective_json TEXT NOT NULL,
    pending_json TEXT,
    configuration_revision INTEGER NOT NULL DEFAULT 0 CHECK (configuration_revision >= 0),
    effective_revision INTEGER NOT NULL DEFAULT 0 CHECK (effective_revision >= 0)
);

CREATE TABLE IF NOT EXISTS production_executions (
    building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id),
    current_batch_id TEXT,
    last_batch_id TEXT,
    boundary TEXT NOT NULL DEFAULT 'NONE',
    automatic INTEGER NOT NULL DEFAULT 0 CHECK (automatic IN (0, 1)),
    execution_revision INTEGER NOT NULL DEFAULT 0 CHECK (execution_revision >= 0),
    methods_effective_revision INTEGER NOT NULL DEFAULT 0 CHECK (methods_effective_revision >= 0)
);

CREATE TABLE IF NOT EXISTS production_batches (
    batch_id TEXT PRIMARY KEY,
    building_id TEXT NOT NULL REFERENCES economic_buildings(id),
    configuration_json TEXT NOT NULL,
    progress_units INTEGER NOT NULL,
    status TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS production_start_intents (
    batch_id TEXT PRIMARY KEY,
    building_id TEXT NOT NULL UNIQUE REFERENCES economic_buildings(id)
);
```

**重要约束：** `production_executions.current_batch_id/last_batch_id` 只保存引用 ID，`production_batches` 是 Batch 可变状态的唯一权威记录；不可再在 Execution JSON 中复制完整当前 Batch，并同时在 Batch 表维护另一份可变状态。保存 Execution 与其内部 Batch 必须在**同一个数据库事务**提交。`ProductionJournal.containsBatch(batchId)` 必须仍能判定 Batch 是否已真实提交。由领域构造/加载时校验引用、状态、归属，必要时配置外键和索引。

库存和设备表可保留有效设计（如 `inventory_locations`、`equipment_holdings`）；本次不重写它们的业务结构。`employment_state` 现有全局格式暂不动，待 Employment & Compensation 重构。

若选择不同的物理列设计，应解释如何保护上述语义，而不是机械照搬示例 DDL。

## 5. Repository / 单位工作职责

建议新增两个独立接口及对应 SQLite 适配器：

```java
interface ProductionMethodConfigurationRepository {
    ProductionMethodConfiguration load(UUID buildingId);
    void create(ProductionMethodConfiguration initial);
    void save(ProductionMethodConfiguration changed, long expectedRevision);
}

interface ProductionExecutionRepository {
    ProductionExecution load(UUID buildingId);
    void create(ProductionExecution initial);
    void save(ProductionExecution changed, long expectedRevision);
    boolean batchExists(UUID batchId); // 或由 Journal 适配器查询同一权威表
}
```

这些是语义草图，不应盲目按此直接复制 Java。具体接口可选择 `Optional`、不可变 Snapshot、显式 `CommitResult` 等方式，但必须清楚区分不存在、版本冲突、保存失败和提交结果未知。

条件提交示例：`UPDATE ... SET ..., revision = revision + 1 WHERE building_id=? AND revision=?`。检查受影响行数；不匹配是并发冲突，不可默默覆盖。`save()` **成功后**才更新运行时权威索引；失败时恢复/重载，不能保留已变异但未保存的聚合对象。避免新旧注册表同时持有可变权威对象。

建筑初始化可由应用层使用**一个事务**创建 Building、Configuration、Execution 三份初始记录。独立聚合不意味着禁止应用层联合创建的必要事务。

## 6. 跨聚合业务协议

### 6.1 PM 变更与开工竞争（PO-ES-01、09、11、22）

1. 同一服务端经济写锁内读取可信 Execution 与配置状态；已提交的先执行操作获胜。
2. 没有已提交 Batch、没有未完结边界且没有提交结果不明的开工时，允许合法 PM 立即生效；否则可靠确认批次保护时把目标存为 Pending。
3. 仅有**已确认未提交 Batch** 的开工补偿尚未结束时，仍允许 PM 变更；禁止新开工（PO-ES-10）。
4. 如 Batch 提交结果未知或 Execution 权威保护状态不可确认，拒绝新的 PM 修改，不自动记作 Pending。
5. Batch 创建时在不可变快照中保存当次 Effective PM 的版本/选择；正式提交前重新验证最新权威 Effective PM、Execution、原料、设备、任职/资格、工资与必要的人事协调状态。不得根据旧的只读就绪快照授权开工。
6. 保留原料 Reservation、设备保护、StartIntent 及失败补偿的可恢复性；遇到丢失提交确认，不得释放实际已提交批次的承诺。

### 6.2 Pending 独立保存（PO-ES-23）

可靠确认 Batch 受保护时，即使 Execution 最近一次进度保存失败，Configuration 仍可独立保存 Pending。实施与测试时，故障只影响 Execution 的该次持久化；若 SQLite 整库不可写，不能假装 Pending 能保存。

### 6.3 边界、重入和幂等（PO-ES-02、03、05、06、07、08）

批次结算后进入 METHODS → EQUIPMENT → WORKFORCE。PM 的 `applyPending()` 和 Execution 的 `methodsApplied()` 可能独立提交，必须允许以下合法中间状态：**新的 Effective 已提交，但 Execution 仍处于 METHODS**。重复执行 PM 生效操作不能改变已成功的结果，随后继续 EQUIPMENT。

若在 EQUIPMENT 或 WORKFORCE 期间又收到新 PM，持久化 Pending 与 Execution 重进 METHODS 可能发生一先一后的提交。启动恢复、每次进入 EQUIPMENT/WORKFORCE、以及新 Batch 启动前都需要**重新检查 PM 当前有效状态与待处理版本**，不得因 Execution 的旧 boundary 状态而跳过最新待生效方案。

PM 生效后立刻触发必要的用工再配置，不需要 PM/Employment 在同一事务完成；恢复时要根据最新 Effective 配置、岗位 Reservation 和真实 Employment 事实主动查漏。必要再配置未完成则禁止新 Batch；与当前调整无关的人事 Pending 不必阻止开工。新 PM 生效可以使旧的非必要待执行人事事项失效，仍必要事项保留、重新验证；已经发生的调动/解雇不得逆转。

### 6.4 批次终止及跨就业流程（PO-ES-13～21）

本轮不实现完整 NPC 调动；必须保留将来协议插入位置。`SETTLING_ABORTED` 的成功持久化是不可逆终止提交点；库存比例结算可以稍后继续。Production 不自行修改 NPC 任职。若将来由 NPC 主动调动引发终止，请求需要可去重的流程身份，且在终止提交前验证调动仍有效；已确认撤销不得触发过时终止。公司发起的调任延迟至自然批次边界执行。

### 6.5 设备反向依赖的保护

现有 `ProductionEquipmentService.flushPendingOperationsForBuilding()` 穿透 `Building → ProductionDepartment.hasActiveBatch()`。拆除 Building 内生产持有者时，必须同步替换该检查，不能直接删掉。建议库存通过窄的、可信的 `ProductionExecution` 生命周期事实适配器核验 **endedBatch 与已持久化允许设备变更的边界**；或由唯一协调器提供经核验的授权。检查状态未知、错 Batch、尚在 ACTIVE/PAUSED/SETTLING 时一律拒绝。设备聚合内部 `protectedByBatch` 仍保护所有权；无保护记录不等于可任意执行旧请求。

## 7. 分阶段实施次序

**M0：基线、测试和 ADR。** `git status` / 当前 SHA；完整 `test/build`；核对源码，不覆盖本地修改。将 ADR-PO-01/02 和 PO-ES-01～23 作为设计约束写入文档；区分尚未实现的 NPC、工资功能。

**M1：全新初始 Schema 与独立 Repository。** 实现 Configuration/Execution 独立加载、创建、保存、乐观版本控制。Batch 单一权威记录，Execution 与 Batch 原子保存。添加 SQLite 测试。

**M2：移出建筑权威状态。** `EconomicBuilding` 不再创建/持有 ProductionDepartment 的可变状态。替换或删除旧部门入口。建筑创建与生产聚合初始化原子协调；`ServerEconomyContext` 启动从独立 Repository 恢复。`BuildingService` 仅保留建筑职责，PM 写命令改由生产应用服务协调；维护 `/ve` 命令外部行为。

**M3：改造开工、计时、终止、补偿。** 生产服务从两个 Repository 取得最新状态，运行相同业务规则；失去数据库提交回执时重读**执行聚合与其 Batch 记录**，不再重载整份 EconomicBuilding。完成 PO-ES-09～11、20 的测试。

**M4：恢复批次边界。** 独立配置生效与执行边界，支持已提交中间状态；在 EQUIPMENT/WORKFORCE 和开工前主动发现并处理最新 Pending；处理 PM 生效后用工再配置的启动与重启查漏；确保设备边界可信检查从建筑内部对象图解耦。

**M5：删除过时的代码与旧测试假设。** 不保留第二份生产权威，不保留旧数据库迁移兼容代码；现有其他限界上下文尽量不改。完善源码阅读地图、调整测试装配。

每完成一个阶段独立运行相关测试；全部完成后运行 `gradlew.bat test --console=plain` 与 `gradlew.bat build --console=plain`。不得因重构而降低、删除原有生产域及 SQLite 恢复测试覆盖；明确替换仅用于旧格式迁移的测试。

## 8. 不可省略的验收场景

- 新世界：建筑、配置、执行三者原子初始化；重启完整恢复。
- Configuration 单独保存不写 Execution；Execution 进度更新不写 Configuration；各自版本冲突保护。
- PO-ES-01：PM 与开工竞争按成功提交顺序；旧配置 Batch 快照不变。
- PO-ES-04：缺料、欠薪、缺员但空闲时合法 PM 仍可生效；真实开工仍 fail-closed。
- PO-ES-09/11：原料与设备准备完成但 Batch 未提交时可以补偿；提交结果不明时 PM 拒绝且不释放可能已提交的资源。
- PO-ES-10：确认未提交 Batch 但释放失败，允许修改 PM，阻止新 Batch，恢复后清锁。
- PO-ES-22/23：无法判定 Execution 保护状态则拒绝 PM；可靠确认 Batch 受保护但进度保存失败仍能独立持久化 Pending。
- 完工：库存恰好一次消耗与产出；比例终止也恰好一次；重启后不重复结算。
- 边界故障：PM 已生效、Execution 尚在 METHODS；设备已执行、Execution 尚在 EQUIPMENT；两者均可恢复且不重复执行。
- 边界重入：WORKFORCE 被阻塞时再提交新 PM，服务器崩溃重启后不能跳过新方案。
- 不可逆终止：已提交 `SETTLING_ABORTED` 后不能恢复 ACTIVE；后续继续结算。
- 设备与人员保护：活动/暂停/待结算 Batch 不得提前修改设备或退出受保护参产；人员必要再配置未完成禁止新 Batch。
- 不依赖区块加载；经济计时保持 1200 tick 满速批次、TPS 下降不离线补算。
- 正式服工资与资格暂时仍默认 UNAVAILABLE / 不可证明，不得用测试假事实让正式服开工。

## 9. 不在本次实现范围内

真实 BuildingPayroll、NPC 资格来源、跨公司/公司内调任意愿、工资发放、采购销售物流、GUI、商品精度新规则、生产规模效应。不可因为这些领域暂缺就擅自伪造成功状态。不得未经用户允许 push、合并 PR、删除现有世界文件。

## 10. 实施记录（2026-10-09）

本地基线为 `01a8c0a8a6380006fe9e937491675b67b5e23c76`，`main` 跟踪 `origin/main`，远程指向 VE 本体。实施前没有未提交源码；用户提供的 `README.txt`、ADR 目录和任务目录未跟踪。没有执行 reset、clean、提交、push 或 PR；README 和任务文件保持原样。本次直接采用已确认 B′，没有创建 ProductionSite。

### 实际权威与事务

- `EconomicBuilding`：仅身份、类型、运营状态。`BuildingRepository` 仅存取建筑身份，不再保存生产或 HR 对象图。
- `ProductionMethodConfiguration`：独立 buildingId、Effective/Pending、两个版本；`ProductionMethodConfigurationRepository` CAS 保存自身行。
- `ProductionExecution`：独立 buildingId、current/last 内部 Batch、自动状态、边界、执行版本与协调版本；`ProductionExecutionRepository` 在一个事务中 CAS 保存执行引用及内部 Batch。
- `production_batches`：唯一可变 Batch 记录。配置 JSON 只写入一次，后续仅更新进度/状态。拒绝快照改变、进度倒退、终止反转和终止/结算后的进度改写。
- 加载返回已验证的独立对象，不另建持有可变根的 Production Registry。失败后重新加载 Repository，不保留未提交变更，不替换独立更新的 PM。
- 建筑初始化仍可用一个共享事务共同创建三份初始记录，成功才加入建筑 Registry。库存、设备、Employment、工资各自保持原权威。

初始 Schema 版本为 **2**，包含 ADR 第 4 节的五个业务表、版本表及原有三个库存/用工表。增加复合延迟外键，保证 current/last Batch 属于该建筑；增加未结束 Batch 的部分唯一索引。执行表不含 `state_json` 或可变 Batch JSON。

`methods_effective_revision` 是本次实现采用的边界协调检查点：记录执行已处理的 Effective 版本，不记录 PM 选择。不依赖旧边界判断新方案是否已经处理；EQUIPMENT/WORKFORCE 和开工前均核对 Pending、当前有效版本与检查点。没有创建第二份 PM 权威。

启动只初始化空数据库。已有库的版本、表或列不兼容时原样拒绝并明确提示另用新世界，不创建旧格式迁移，不清空旧表或世界文件。`EconomyDatabase.open` 的顶层错误保留具体拒绝理由。

### 独立提交与恢复证据

| 故障或竞争 | 实现与测试证据 |
| --- | --- |
| 两根独立提交、过期覆盖 | Repository 条件更新及各自版本；SQLite trigger 验证 PM 不写执行、执行不写 PM；ProductionRepositoryTest。 |
| PM / start 顺序 | 共用经济写锁，最后核验两个根、所有资源与人事；Batch 快照保存 Effective 版本；StartCommitRecoveryTest。 |
| 执行进度保存失败但保护可信 | Pending 独立配置事务；故障只注入执行 Repository，配置写入仍真实成功；StartCommitRecoveryTest。 |
| 已确认未提交而释放失败 | 持久化 StartIntent 保留材料/设备承诺，允许 PM、禁止新开工；重启补偿；StartCommitRecoveryTest。 |
| Batch 提交结果未知 | Journal 核实唯一 Batch 表；不能核实时拒绝 PM 并保留承诺，能核实时只重新加载 Execution；StartCommitRecoveryTest。 |
| JDBC 提交实际完成但回执丢失 | 连接代理在真实 commit 后抛 SQLException，验证 Batch 和执行原子保存且可核实；ProductionRepositoryTest。 |
| JDBC 仍在不明事务 | Repository / Journal 拒绝把未提交连接视图当作已提交事实，等待可重新核实或重启；ProductionRepositoryTest。 |
| 库存先完成、执行后失败 | 原 Inventory 幂等回执使完工及比例终止不重复扣料/产出；ProductionIntegrationTest、StartCommitRecoveryTest。 |
| Effective 已保存、执行仍 METHODS | 配置不回滚；重启自动继续边界；RecoveryAndControlsTest、BoundaryRecoveryTest。 |
| EQUIPMENT/WORKFORCE 新 Pending 保存后回退失败 | 重启及重试先核对配置并重入 METHODS，不跳过迟到目标；BoundaryRecoveryTest。 |
| 设备完成、执行检查点失败 | 设备请求终态持久化，重放不重复执行；RecoveryAndControlsTest、BoundaryRecoveryTest。 |
| 必要人事未完成或未知 | PM 先真实提交，立即尝试 Reservation 清理与实际用工协调；阻止下一批，不自动伪造调任或解雇；BoundaryRecoveryTest。 |
| 设备授权未知或陈旧 | 库存拥有的生命周期 Port 检查正确已结束 Batch、持久化 EQUIPMENT、Pending 和有效版本；ACTIVE、PAUSED、SETTLING 及旧 Batch 均拒绝；BoundaryRecoveryTest。 |
| 恢复后最后一 tick 完工 | 保留 resume + tick 的既有组合行为，Repository 接受 PAUSED → SETTLING_COMPLETED；ProductionIntegrationTest。 |

`recoverStarts()` 后执行 `recoverBoundaries()`；关闭自动生产的建筑也主动查漏，必要人事事实每次从 Employment 取得。公司调任、NPC 同意及完整解雇尚未实现：协调返回未完成时保持 WORKFORCE，不能新开工。其他建筑的人事事项不构成全局阻塞。

旧 `schemaMigrationIsRepeatableAndPreservesLegacyRowsAndEquipmentColumn` 仅验证旧格式迁移，已替换为 `incompatibleLegacySchemaIsRejectedWithoutChangingRowsOrEquipment`；仍核验原建筑、PM 行和设备数 73 不变。其余原测试保持覆盖并适配新 Repository，新增初始 Schema、原子创建、独立提交、恢复和 JDBC 故障测试。

### 修改文件清单

下列路径均相对于 `src/main/java/com/bbmurloc/victoriaeconomics/server/`：

| 分组 | 文件 | 原因 |
| --- | --- | --- |
| 生产领域 | production/domain/ProductionMethodConfiguration.java、ProductionExecution.java、ProductionBatchConfiguration.java、ProductionRecipeResolver.java | 独立身份/版本、边界版本、批次承诺版本；移除建筑对象图桥接。 |
| 独立契约 | production/port/ProductionMethodConfigurationRepository.java、ProductionExecutionRepository.java、ProductionRevisionConflict.java | 独立创建/加载/CAS 保存及明确版本冲突。 |
| 生产存储/适配 | production/infrastructure/SqliteProductionMethodConfigurationRepository.java、SqliteProductionExecutionRepository.java、SqliteProductionJournal.java、RepositoryProductionFacts.java | 单一 Batch 权威、独立事务、提交核实、可信设备/人员保护事实。 |
| 应用协调 | production/application/ProductionService.java、ProductionBuildingInitializer.java、StaffingCalculator.java | 跨根提交和失败恢复、联合初始化、统计改读配置。 |
| 建筑 | building/EconomicBuilding.java、BuildingService.java；storage/sqlite/SqliteBuildingRepository.java | 移出可变生产状态，身份存储不再写 PM/执行。 |
| 库存/用工 | inventory/port/EquipmentBatchLifecyclePort.java、inventory/application/ProductionEquipmentService.java、workforce/employment/ProductionEmploymentPort.java、EmploymentService.java | 以窄查询替换建筑/部门穿透，保留各上下文权威和算法。 |
| 数据库 | storage/sqlite/EconomySchema.java、SqliteTransactions.java、CommitOutcomeUnknownException.java；storage/EconomyDatabase.java | 全新初始 Schema、保存/回滚/未知提交区分、非破坏性拒绝旧库。 |
| 服务端入口 | ServerEconomyContext.java、command/ProductionCommands.java | 独立装配/恢复和 PM 命令；语法权限不变，状态增加版本诊断。 |
| 包说明 | production/package-info.java、domain/package-info.java、infrastructure/package-info.java、port/package-info.java | 更新所有权和持久化边界描述。 |
| 删除 | building/department/production/ProductionDepartment.java；storage/sqlite/ProductionStateCodec.java | 清除旧持有者与重复 Batch JSON 编码，避免另一套生产权威。 |

测试位于 `src/test/java/.../server/production/`：新增 `infrastructure/ProductionRepositoryTest.java` 及 `application/BuildingProductionIsolationTest.java`、`StartCommitRecoveryTest.java`、`BoundaryRecoveryTest.java`；适配 `TestEconomy.java`、`ProductionIntegrationTest.java`、`RecoveryAndControlsTest.java`、`WorkforceContractTest.java` 和 `domain/ProductionDomainTest.java`。

文档更新：本 ADR、`docs/domain/production-operations/overview.md`、`code-reading-map.md`；第一、第二轮审计仅增加历史适用范围和删除文件引用说明，保留当时结论。

### 分阶段实际验证

| 阶段 | 测试范围 | 结果 |
| --- | --- | --- |
| M0 基线 | 完整 test / build | BUILD SUCCESSFUL；基线 56 项已有结果，任务 UP-TO-DATE，未宣称重新执行。 |
| M1 | ProductionRepositoryTest、ProductionDomainTest、InventoryAndEquipmentTest、SqliteInventoryContractTest | 32 项实际执行通过。 |
| M2 | M1 范围加 BuildingProductionIsolationTest | 37 项实际执行通过。 |
| M3 | StartCommitRecoveryTest、ProductionIntegrationTest、WorkforceContractTest | 32 项实际执行通过；修复最终人事检查对原超员错误语义的影响。 |
| M4 | BoundaryRecoveryTest、RecoveryAndControlsTest、StartCommitRecoveryTest、ProductionIntegrationTest、WorkforceContractTest、ProductionRepositoryTest | 61 项实际执行通过。 |
| M5 | ProductionRepositoryTest、ProductionIntegrationTest、BuildingProductionIsolationTest、ProductionDomainTest | 48 项实际执行通过；新增 JDBC 与暂停后立即完工回归。 |

最终执行 `gradlew.bat test --console=plain`：**BUILD SUCCESSFUL，94 项实际执行，0 failures、0 errors、0 skipped**，用时 2 分 5 秒。当前基线原有 56 项覆盖（其中 1 项旧迁移测试等量替换），新增 38 项。

最终执行 `gradlew.bat build --console=plain`：**BUILD SUCCESSFUL**，用时 1 秒，test UP-TO-DATE 复用刚通过的完整测试。产物为 `build/libs/victoria_economics-0.1.1-1.21.1.jar`；检查确认包含两个新 Repository、初始化及事实适配器，旧 ProductionDepartment / ProductionStateCodec 类残留为 0。

`git diff --check` 与暂存区检查通过，Java package/路径不匹配为 0，更新文档的 43 个本地链接全部有效。用户 README 与任务文件 SHA-256 和开始时一致，未提交或 push。

验证使用纯 Java 和真实临时 SQLite，没有启动 Minecraft 服务端，不把测试工资/资格事实当成正式游戏成功事实。工资、Finance、真实 NPC 资格、公司调任同意及完整解雇仍是对应上下文的待接入项；正式默认装配继续拒绝未经权威事实许可的开工。双聚合、初始 Schema、生产资源与边界恢复本轮没有遗留测试失败。
