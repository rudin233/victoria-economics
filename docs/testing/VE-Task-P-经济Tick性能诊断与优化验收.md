# 工作包 P：经济 Tick 性能诊断、一致性检查点与验收

2026-10-21。已完成本轮安全架构调整和验证，等待源码审查。**严格 50ms 尖峰性能验收仍未通过；没有进入 E-Final，也不具备直接宣布整体重构完成或合并 main 的条件。**

**暂停交接：用户已要求暂停，当前工作区作为可提交审查的阶段版本保留。** 代码已通过下述 657 项 JUnit、构建及隔离服务器验证；没有执行 Git commit/push。后续同步结算尖峰优化及 E-Final 均未启动，等待用户与 GPT 讨论后明确恢复。本次暂停只补充文档，不修改已经验证的代码。

## 1. 基线、已有工作与本轮变更

实际分支 refactor/m1-0-sqlite-transaction-cleanup，HEAD d4080879b76e563afec26d82ca86732b826e748f。原 P 开始时工作区干净；本次补充接手时已有未提交的监控、优化、29 项新测试、24 次正式采样及报告草稿，全部保留并整合。没有 reset、stash、commit、push 或 merge。中断的校准目录及未对齐的早期探索样本保留在 build，不作为正式结果。

真实存档只计算哈希，没有使用 SQLite 打开：run/saves/新的世界/victoria_economics/economy.sqlite。前后 SHA-256 均为 3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8，WAL 始终不存在。所有数据库写入和崩溃测试使用 JUnit 临时目录或 build/task-e-gametest-p_* 隔离世界。

用户正式替代了逐 Tick 物理落盘要求。当前正常业务在唯一的内存事务状态中运行；SQLite 文件仅保存一致性恢复点。领域原子性、商品/现金守恒、工资余数、CAS、资格/身份门禁和生命周期未决事项没有放宽。

## 2. P-A 保留成果与证据

保留 EconomyDiagnostics：默认关闭、线程限定、System.nanoTime 观察；没有经济时钟用途、ThreadLocal 事务能力或每 Tick 同步诊断日志。覆盖原生完整 Tick、Context、接受时间/恢复、应计/付款、每建筑推进、边界、库存与 SQLite begin/work/commit/cleanup，另增加纯生产/工资/库存计算、快照、等待及后台存储分阶段数据。

保留三项旧协议优化：（1）无外部待办的单建筑推进和完成标记在同一 IMMEDIATE 事务保存，SETTLING、RECONFIGURING、StartIntent 仍走原协议；（2）只在同一工资事务内复用 accepted Tick 和地区/币种工资率，每段仍重新核验 Staffing、历史和 CAS；（3）已确认回执和已追平工资游标不做无意义 UPDATE。没有跨 Tick 的工资/余额缓存，也没有合并不同建筑的业务失败范围。

旧基线首轮空世界平均 13.865ms，其中 commit 累计 7.745ms/Tick；idle4 平均 60.802ms，commit 30.958ms；idle16 平均 182.301ms，commit 92.214ms。重复磁盘提交和重复协调是实测主要成本，不能把全部耗时都归给 SQLite。第二轮波动原样保留。

之前两次 IDE 调试会话未形成可用断点命中，已清理本轮断点；没有把调试器或失败的 JDK attach 当作运行证明。结论来自实际计时、真实 SQLite、JDBC 注入及独立服务器进程。

## 3. P-B / P-C：唯一运行时权威与事务

详细设计见 [一致性检查点架构](../design/VE-Task-P-一致性检查点架构.md)。

| 方面 | 原实现 | 当前实现 |
|---|---|---|
| 当前业务事实 | 世界磁盘 SQLite | 一个具名共享内存 SQLite |
| 日常业务原子性 | 受保护 SQLite/UoW/Session | 同一套 UoW/Session，在 RAM 中提交/回滚 |
| 日常保存成本 | 多个磁盘事务/Tick | 不产生磁盘提交 |
| 持久恢复点 | 各步骤的持久标记 | 一份完整经济检查点 |
| 未知业务提交核验 | 新磁盘连接 | 同一 RAM 数据库的新可信连接 |
| 崩溃 | 按逐步骤事实恢复 | 撤销整个未保存后缀 |

内存 SQLite 是事务型运行状态的实现，不是磁盘旁的第二套缓存；日常仍有 SQL/JDBC 成本。这样保留现有 DDD 聚合及已经验证的多行约束，无需造一个巨型 EconomyManager 或逐领域双写缓存。Context 内全部 Repository/ApplicationService 共用 RAM 连接和 economyLock；keeper 维持其生命周期。BuildingRegistry 只是提交后发布的身份视图。

业务事务不跨 Tick 保持打开；仍先取 IMMEDIATE 再读取权威状态。committed load 仍拒绝未解决本地事务，事务内读取必须用显式能力。Snapshot install/receipt 也核对 Session 属于同一连接。未知业务结果隔离旧连接，用新 RAM 连接核验，不能用旧磁盘检查点冒充当前状态。

经济 Tick 仍只由真实服务器回调推进；200 满速、3/5 第 334 Tick、1200 Tick 工资周期、最低 10%、冻结快照、完整完成/中止、真实工资与暂停原因均保留。不补算离线时间。

已经保存的 Tick/周期不倒退；未保存的 Tick、Run 和命令在崩溃后可能整体撤销并重新执行。这是新恢复边界的直接结果，替代旧的逐 Tick 永久接受承诺。未保存的中止/付款回执也不能对外承诺跨崩溃永久存在；已保存历史内仍保持不可逆决定和幂等。时钟、库存、工资、现金及回执一起回退，不能单独保留超前时钟或一方到账。

## 4. 完整经济检查点

主线程持 economyLock，在业务事务结束后，以一次只读快照冻结全部 版本化业务 表：建筑、PM/Batch/Execution、Staffing/供给、工资历史/余数/债务、账户/账本/预留、付款行与失败事实、预算、生命周期、Journal 和时间。不可变载荷只含 String/long/null，严格排序并作 SHA-256；不导出可变聚合、JDBC 句柄或 Minecraft 对象。

保存点要求 accepted Tick、工资和付款游标追平，现存建筑处理标记确认、无未完成 StartIntent/结算/屏障。未解决状态使本次保存失败并保留上一恢复点，不把半个 Tick 宣布成可信存档。

唯一后台写者最多一份执行中和一份待执行完整快照。待执行同世界旧快照可由更新完整快照覆盖，旧等待者只在覆盖它的新快照实际提交后获回执；没有无限快照队列或合并回调链。序号单调，磁盘前代回执 CAS 防止旧代覆盖新代。文件锁由工作线程自身强引用持有，即使停服超时丢弃 Context，也不能因 GC 提前放行另一写者。

后台先在私有 RAM 镜像完整验证历史图，再用现有 SqliteTransactions.write 一次 IMMEDIATE 物理事务替换全部行和元数据。FK 延迟检查而不关闭，任一行失败整体回滚。没有降低 DELETE/FULL、改默认 journal_mode、异步逐仓储 save 或长期开放磁盘写事务。

commit 回执未知时隔离原连接，从新只读连接验证完整图、摘要和 CheckpointId；相同结果才确认，不自动重写原请求。无法核验时阻塞写者。入队、SQL 执行完成、物理提交和提交回执是不同状态。

DELETE 模式 hot journal 可能使只读打开失败。先复制主文件/WAL/journal 到新临时目录，核对原文件复制前后摘要不变，只在副本执行恢复；副本必须通过当前格式和完整摘要门禁。只读恢复点加载不改原文件。下一次实际保存才允许在已验证、未变化的当前格式原文件上恢复。旧格式、损坏副本和外部 super-journal 都不自动恢复原文件，不删除用户 journal。依据 [SQLite 文件锁与 hot journal](https://www.sqlite.org/lockingv3.html)及 [原子提交](https://www.sqlite.org/atomiccommit.html)，并以四种真实子进程退出验证。

## 5. Minecraft 1.21.1 / NeoForge 21.1.248 保存依据

读取本地实际 neoforge-21.1.248-sources.jar，归档及各源文件哈希见 [evidence.json](task-p/evidence.json)。没有拿其他 Minecraft 版本推定时序。

| 源码入口 | 实际含义 |
|---|---|
| MinecraftServer.tickServer / computeNextAutosaveInterval | 默认 20 tickrate 为 6000 逻辑 Tick；自动调用 saveEverything(true,false,false)，TPS 降低时墙钟间隔变长 |
| saveEverything | 先 PlayerList.saveAll，再 saveAllChunks；isSaving 不等于所有存储均提交 |
| saveAllChunks | 逐维度 ServerLevel.save，最后 level.dat；VE 在该服务器级入口只采集一次 |
| PlayerDataStorage.save | 玩家 NBT 压缩写临时文件后替换，不与区块或 SQLite 组成共同事务 |
| ServerLevel.save | 先维度数据、区块保存调用、实体 autoSave/saveAll，再 LevelEvent.Save；flush 的 NeoForge I/O 等待在事件之后 |
| ChunkSerializer / ChunkMap / IOWorker | BlockEntity NBT 属于区块序列化；普通保存可排队，flush 走区块/实体存储排空路径 |
| DimensionDataStorage / IOUtilities | 原版自己的 SavedData 另有异步保存及等待；VE 没有使用它存经济数据 |
| SaveAllCommand | save-all 和 flush 均进入 saveEverything；VE flush 等待在方法返回前完成，异常不会执行 Saved 成功回执 |
| ServerStopping / stopServer / ServerStopped | 先阻止经济推进并尽力最终保存，原版保存玩家/世界并关闭资源，最后释放 Context |

不监听每维度 Save 来冒充全局完成。最终实测三次服务器级保存对应九次维度事件，只有三次 VE 原版保存请求。普通保存异步；/save-all flush 等待本次 VE 提交，采集拒绝时不能复用旧成功 future。VE 失败不会阻止原版文件保存；运行中 flush 明确失败，停服记录 VE 失败后继续关闭原版资源。所有维度 save-off 时不跟随自动保存，显式强制保存仍有效。

每次等待和队列排空上限 30 秒，超时不报告成功；停服有多个屏障，极端 I/O 卡死总耗时可能超过 30 秒。默认跟随原版保存，不新增正式 200 Tick 周期；200 只用于压力测试。另提供 /ve checkpoint status 与权限 4 的 flush，没有从墙钟推算经济时间。

原版开始保存、维度事件、VE 冻结、SQLite 提交及各原版 I/O 完成不同步，不构成跨存储物理原子性。

玩家和 level.dat 写入存在记录异常而不一律向上抛出的路径；NeoForge SavedData 还会复制 NBT 并异步写入。VE 的 flush 回执只能证明自身检查点，不能代替原版 I/O 错误检查或证明每份玩家/实体文件已经共同落盘。没有把维度 Save 事件或一次命令返回当作这种证明。

## 6. SavedData 与跨系统资产

没有新增经济 SavedData、Tick/CheckpointId SavedData 或另一份世界经济权威。世界 ID 和恢复元数据在 VE SQLite；目前没有必须新增低频世界 NBT 标记的证据。即使未来加标记，也不能使两个存储原子提交。

主源码没有 VE 虚拟库存与 ItemStack/背包/箱子的正式资产交换入口，继续 fail-closed。虚拟 deposit/withdraw 不生成原版物品。仅先扣 VE 或仅跟随同次 save 都不能防止复制/吞物；未来必须有独立交接 ID、双方耐久回执、幂等核验/托管与崩溃恢复合同，普通可合并物品不能假装天然具备这些信息。

锚点 NBT 只是身份提示，不授予资产/所有权。绑定前先同步确认包含 Building 的 VE 检查点，成功后才改 BlockEntity；读取仍核对当前经济身份，方块消失不会自动拆除。崩溃可以留下无锚点建筑，不会从未知 NBT 自动建造经济资产。

## 7. NSUK 参考结论

实际参考 commit 00276ed14f2bbf2493b0f5ae14f5b69a620b2775，逐文件 blob 标识见证据。阅读了以下七项：

- [新版重写当前进度与后续计划.md](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/docs/%E6%96%B0%E7%89%88%E9%87%8D%E5%86%99%E5%BD%93%E5%89%8D%E8%BF%9B%E5%BA%A6%E4%B8%8E%E5%90%8E%E7%BB%AD%E8%AE%A1%E5%88%92.md)
- [CommercialWorkService.java](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/src/main/java/common/cn/nsimu/simukraft/commercial/CommercialWorkService.java)
- [CitizenNavigationService.java](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/src/main/java/common/cn/nsimu/simukraft/path/CitizenNavigationService.java)
- [StorageWriteQueue.java](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/src/main/java/common/cn/nsimu/simukraft/storage/StorageWriteQueue.java)
- [SqliteConnectionPool.java](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/src/main/java/common/cn/nsimu/simukraft/storage/core/SqliteConnectionPool.java)
- [StorageMetrics.java](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/src/main/java/common/cn/nsimu/simukraft/storage/core/StorageMetrics.java)
- [TransactionRunner.java](https://github.com/MeteorPointer/New-Simukraft-1.21.1/blob/00276ed14f2bbf2493b0f5ae14f5b69a620b2775/src/main/java/common/cn/nsimu/simukraft/storage/core/TransactionRunner.java)

借鉴不可变载荷、单写者、同 key 待执行合并、提交后通知、有界排空和指标。VE 合并的是整个世界恢复点，不能把转出、到账和债务冲销分成独立 key。没有照搬池化的八连接或常驻磁盘写连接：日常 RAM 连接常驻，低频检查点重新打开并预检，成本独立计量。

NSUK 的商业服务按建筑 nextTick 节流，但 tick 仍遍历 manager.all；不能据此声称它完全没有全量扫描。导航层有每 Tick 请求预算、不可变后台搜索、版本戳/缓存失效，适合参考纯计算调度；VE 没有用节流跳过经济 Tick/工资，也没有后台访问世界。

没有复制 NORMAL、默认 WAL、失败批次逐条重试、quiet rollback 或 finally reset。其文档 200 Tick 周期是该项目选择，不是 VE 正式规则；部分模块的 SavedData 兜底也未采用。

## 8. 性能方法、环境与数据

完整 tickServer HEAD/RETURN 包含 Post 中 VE；Minecraft 原生 tickTimesNanos 在 Post 前结束。GameTestServer 不按正常上限等待，本报告 ticks/s 是实测无上限吞吐，正常 20 TPS 等价上限为 min(20,吞吐)，**不是玩家服实际 TPS 验收**。未运行长期真实玩家/其他 Mod 容量测试。

每场景 150 Tick 预热、1300 对齐样本、两次独立新世界/JVM。baseline 与 optimized 各 12 次保留旧协议结果；checkpoint 12 次采用 200 Tick 测试保存压力；calmem 4 次关闭细分观察，保留全部业务和保存。没有并行运行构建/JUnit/其他自有压力任务。阶段内正常业务路径相同；双轮后仅完善保存拒绝报告和连接/锁保护，并用最终源码执行 calmem、原版保存和全部故障/恢复回归。

Windows 11 10.0.26200，i7-12700 12 核/20 线程，可见内存 16,457,100KiB，NVMe WDC PC SN530 SDBPNPZ-256G-1006，JDK 21.0.12 HotSpot，服务器最大堆 4,213,178,368 bytes，sqlite-jdbc 3.46.1.0。磁盘 DELETE/FULL、busy_timeout=5000；没有宣称 IDE/系统索引完全无干扰。

场景：empty；idle4/idle16 每栋显式 10 laborer + 4 machinist，仍正常计薪；active4/active16 连续满速批次；history4 额外 40 个合法关闭工资段和 500 次双边转账产生的 1000 账本行。历史案例是数量压力，不是多年运行证明。所有资金/供给均为隔离来源、有完整记录，不进入正式服。

单位均 ms。下表均值是两个等样本运行的平均；**没有平均分位数来冒充合并 p95**。检查点方案同时改变了授权的崩溃恢复合同，性能提升不是相同逐 Tick 耐久合同下的魔法加速。

| 场景 | 基线均值 ms | 旧协议优化 ms | 检查点均值 ms | 相对基线降幅 |
|---|---|---|---|---|
| empty | 15.488 | 13.817 | 1.212 | 92.2% |
| idle4 | 58.822 | 34.670 | 5.525 | 90.6% |
| active4 | 55.850 | 38.039 | 5.167 | 90.7% |
| idle16 | 168.015 | 93.223 | 16.826 | 90.0% |
| active16 | 184.080 | 96.916 | 14.630 | 92.1% |
| history4 | 67.272 | 37.825 | 8.574 | 87.3% |

### 8.1 每次原始汇总

| 阶段 / 场景 / 轮 | N | 均值 | p50 | p95 | p99 | 最大 | VE 主线程均值 | VE 占比 | 无上限 ticks/s |
|---|---|---|---|---|---|---|---|---|---|
| baseline / active16 / 1 | 1300 | 173.259 | 164.505 | 287.905 | 405.551 | 1150.751 | 172.807 | 99.7% | 5.769 |
| baseline / active16 / 2 | 1300 | 194.901 | 181.563 | 297.582 | 375.840 | 910.523 | 194.373 | 99.7% | 5.127 |
| baseline / active4 / 1 | 1300 | 42.808 | 32.499 | 82.657 | 113.056 | 440.806 | 42.412 | 99.1% | 23.339 |
| baseline / active4 / 2 | 1300 | 68.892 | 63.028 | 115.098 | 178.052 | 304.602 | 68.331 | 99.2% | 14.499 |
| baseline / empty / 1 | 1300 | 13.865 | 12.095 | 24.791 | 36.603 | 70.603 | 13.354 | 96.3% | 71.910 |
| baseline / empty / 2 | 1300 | 17.110 | 15.346 | 30.477 | 38.436 | 51.554 | 16.510 | 96.5% | 58.228 |
| baseline / history4 / 1 | 1300 | 83.002 | 77.849 | 132.621 | 169.311 | 385.825 | 82.408 | 99.3% | 12.034 |
| baseline / history4 / 2 | 1300 | 51.541 | 39.995 | 101.917 | 133.828 | 441.910 | 51.122 | 99.2% | 19.389 |
| baseline / idle16 / 1 | 1300 | 182.301 | 172.231 | 299.068 | 337.530 | 523.002 | 181.788 | 99.7% | 5.483 |
| baseline / idle16 / 2 | 1300 | 153.729 | 141.483 | 270.975 | 338.791 | 553.124 | 153.309 | 99.7% | 6.503 |
| baseline / idle4 / 1 | 1300 | 60.802 | 54.735 | 100.758 | 128.431 | 204.410 | 60.295 | 99.2% | 16.432 |
| baseline / idle4 / 2 | 1300 | 56.842 | 52.285 | 93.029 | 123.355 | 327.680 | 56.366 | 99.2% | 17.579 |
| calmem / active16 / 1 | 1300 | 13.986 | 11.628 | 15.140 | 104.740 | 253.400 | — | — | 71.279 |
| calmem / active16 / 2 | 1300 | 14.088 | 11.660 | 15.416 | 111.602 | 202.545 | — | — | 70.761 |
| calmem / active4 / 1 | 1300 | 4.851 | 4.066 | 6.128 | 29.456 | 97.915 | — | — | 202.617 |
| calmem / active4 / 2 | 1300 | 4.880 | 3.937 | 6.033 | 28.132 | 184.424 | — | — | 203.159 |
| checkpoint / active16 / 1 | 1300 | 14.520 | 12.307 | 17.592 | 107.717 | 229.455 | 14.088 | 97.0% | 68.646 |
| checkpoint / active16 / 2 | 1300 | 14.739 | 12.479 | 16.368 | 108.540 | 266.523 | 14.335 | 97.3% | 67.616 |
| checkpoint / active4 / 1 | 1300 | 5.090 | 4.073 | 6.374 | 32.439 | 132.775 | 4.613 | 90.6% | 195.116 |
| checkpoint / active4 / 2 | 1300 | 5.243 | 4.129 | 7.159 | 30.503 | 142.638 | 4.855 | 92.6% | 189.311 |
| checkpoint / empty / 1 | 1300 | 1.139 | 0.811 | 1.704 | 4.583 | 43.664 | 0.762 | 66.9% | 854.026 |
| checkpoint / empty / 2 | 1300 | 1.286 | 0.815 | 1.665 | 5.232 | 155.247 | 0.794 | 61.7% | 760.911 |
| checkpoint / history4 / 1 | 1300 | 8.530 | 7.238 | 11.056 | 40.101 | 217.933 | 7.992 | 93.7% | 116.679 |
| checkpoint / history4 / 2 | 1300 | 8.619 | 7.050 | 11.383 | 39.914 | 181.591 | 8.188 | 95.0% | 115.460 |
| checkpoint / idle16 / 1 | 1300 | 16.667 | 15.130 | 19.534 | 45.696 | 141.826 | 16.168 | 97.0% | 59.855 |
| checkpoint / idle16 / 2 | 1300 | 16.986 | 15.400 | 19.972 | 44.536 | 188.393 | 16.475 | 97.0% | 58.722 |
| checkpoint / idle4 / 1 | 1300 | 5.502 | 4.870 | 7.115 | 15.978 | 79.952 | 5.099 | 92.7% | 179.944 |
| checkpoint / idle4 / 2 | 1300 | 5.547 | 4.827 | 7.067 | 24.774 | 110.275 | 5.166 | 93.1% | 179.170 |
| optimized / active16 / 1 | 1300 | 96.559 | 85.764 | 146.167 | 228.801 | 1052.338 | 96.097 | 99.5% | 10.351 |
| optimized / active16 / 2 | 1300 | 97.272 | 88.892 | 146.841 | 209.067 | 879.539 | 96.824 | 99.5% | 10.274 |
| optimized / active4 / 1 | 1300 | 41.222 | 35.291 | 71.275 | 128.461 | 322.790 | 40.611 | 98.5% | 24.238 |
| optimized / active4 / 2 | 1300 | 34.857 | 31.023 | 58.169 | 107.750 | 291.852 | 34.383 | 98.6% | 28.648 |
| optimized / empty / 1 | 1300 | 14.407 | 12.580 | 27.962 | 33.569 | 305.988 | 13.777 | 95.6% | 69.217 |
| optimized / empty / 2 | 1300 | 13.227 | 11.759 | 23.717 | 35.043 | 49.074 | 12.747 | 96.4% | 75.382 |
| optimized / history4 / 1 | 1300 | 37.176 | 33.577 | 58.101 | 124.199 | 270.232 | 36.672 | 98.6% | 26.860 |
| optimized / history4 / 2 | 1300 | 38.474 | 35.817 | 58.865 | 127.552 | 233.128 | 37.972 | 98.7% | 25.962 |
| optimized / idle16 / 1 | 1300 | 96.464 | 90.824 | 150.508 | 188.038 | 263.505 | 95.992 | 99.5% | 10.361 |
| optimized / idle16 / 2 | 1300 | 89.982 | 86.252 | 129.310 | 189.270 | 361.515 | 89.545 | 99.5% | 11.115 |
| optimized / idle4 / 1 | 1300 | 40.403 | 35.133 | 69.792 | 79.406 | 145.761 | 39.722 | 98.3% | 24.710 |
| optimized / idle4 / 2 | 1300 | 28.936 | 24.760 | 50.915 | 94.442 | 132.962 | 28.514 | 98.5% | 34.510 |

40 次共 52,000 Tick，原始 JSON 汇总及全部计数见 [results.json](task-p/results.json)。calmem 的缺失指标是未观测，不是零业务。观察器每项上限 1,000,000 样本；旧 16 建筑部分 SQL-read 计时达到上限，计数仍完整，未拿截断 SQL 总耗时作结论。

### 8.2 子系统与事务

以下是每 Tick 的包含式耗时，父子项不能相加。VE 主线程总占比取不重叠的 Context + 保存捕获 + 显式等待；后台作业单列，不将后台墙钟时间加到 Tick。SQL execute 不含完整 ResultSet 遍历，业务 work 包含读取/解码。纯领域计时是代表性生产/工资/库存计算，不冒称覆盖所有 CPU 指令。

| 范围：ms / 经济 Tick | 基线 active4 | 旧优化 active4 | 检查点 active4 | 检查点 active16 |
|---|---|---|---|---|
| clock.accept | 3.930 | 4.317 | 0.163 | 0.166 |
| clock.payroll | 5.264 | 5.233 | 0.808 | 1.978 |
| clock.payments | 0.622 | 0.665 | 0.133 | 0.127 |
| clock.productionRecovery | 0.370 | 0.418 | 0.125 | 0.132 |
| production.tick | 44.970 | 26.660 | 3.459 | 11.753 |
| production.coordinator.write | 40.013 | 25.720 | 3.317 | 11.430 |
| inventory.read | 0.020 | 0.022 | 0.015 | 0.049 |
| inventory.domain | — | 0.001 | 0.001 | 0.003 |
| payroll.domain.accrue | — | — | 0.030 | 0.053 |
| production.domain.advance | — | — | 0.003 | 0.004 |
| sqlite.begin | 1.353 | 0.871 | 0.055 | 0.069 |
| sqlite.work | 24.019 | 14.760 | 4.316 | 13.567 |
| sqlite.commit | 28.647 | 20.394 | 0.067 | 0.158 |
| sqlite.cleanup | 0.114 | 0.059 | 0.053 | 0.068 |
| checkpoint.capture | — | — | 0.017 | 0.027 |

| 阶段 / 场景 | managed 事务/Tick | write scope/Tick | 无 DML write/Tick | 读 SQL/Tick | DML/Tick | 每建筑处理均值 ms |
|---|---|---|---|---|---|---|
| baseline / empty | 8.002 | 4.002 | 1.000 | 24.009 | 3.002 | — |
| baseline / idle4 | 36.003 | 16.002 | 5.000 | 241.064 | 15.034 | 10.605 |
| baseline / active4 | 28.283 | 12.153 | 1.043 | 218.399 | 19.228 | 10.218 |
| baseline / active16 | 89.123 | 36.605 | 1.172 | 798.516 | 67.892 | 10.396 |
| optimized / empty | 7.002 | 3.002 | 0.000 | 25.009 | 3.002 | — |
| optimized / idle4 | 11.003 | 7.002 | 0.000 | 179.064 | 11.034 | 4.881 |
| optimized / active4 | 11.369 | 7.175 | 0.043 | 121.759 | 15.249 | 5.513 |
| optimized / active16 | 24.468 | 19.692 | 0.172 | 405.956 | 51.978 | 5.048 |
| checkpoint / empty | 7.008 | 3.002 | 0.000 | 25.386 | 3.002 | — |
| checkpoint / idle4 | 11.008 | 7.002 | 0.000 | 179.441 | 11.034 | 0.920 |
| checkpoint / active4 | 11.375 | 7.175 | 0.043 | 122.136 | 15.249 | 0.808 |
| checkpoint / active16 | 24.473 | 19.692 | 0.172 | 406.333 | 51.978 | 0.716 |

managed 计数是 helper 拥有的事务 scope，非驱动所有 BEGIN 或 fsync 数；Journal 两次独立 DML/批次另计。checkpoint 阶段这些 scope 全在 RAM，不是磁盘提交。每个采样窗口实际七次原版保存请求；最终验收另有一次 flush，后台作业表包含它但不把它算成采样 Tick。正式默认频率为原版约 6000 Tick，手动保存可额外触发。

### 8.3 捕获、后台存储与尖峰

| 场景 | 捕获 N / 均值 / 最大 ms | 后台 N | 验证均值 ms | 打开/预检均值 ms | 磁盘写事务均值/最大 ms | 完整后台作业均值 ms | 检查点文件 bytes |
|---|---|---|---|---|---|---|---|
| empty | 14 / 2.564 / 3.822 | 16 | 24.965 | 13.840 | 10.551 / 18.426 | 49.757 | 339968 / 339968 |
| idle4 | 14 / 3.010 / 5.212 | 16 | 19.672 | 16.894 | 15.796 / 49.474 | 52.756 | 339968 / 339968 |
| active4 | 14 / 3.176 / 4.644 | 16 | 21.959 | 18.425 | 23.391 / 125.140 | 64.153 | 364544 / 364544 |
| idle16 | 14 / 5.024 / 11.646 | 16 | 34.820 | 31.531 | 27.479 / 134.090 | 94.209 | 348160 / 348160 |
| active16 | 14 / 5.060 / 6.979 | 16 | 38.212 | 30.910 | 18.219 / 23.911 | 87.787 | 466944 / 466944 |
| history4 | 14 / 12.977 / 26.417 | 16 | 106.992 | 70.440 | 49.922 / 86.298 | 227.774 | 667648 / 667648 |

后台 N 排除了空世界首次引导，包含七次压力保存和最后一次验收保存。磁盘写事务时间包含 BEGIN、整图 INSERT/DELETE、metadata、commit 和 cleanup；不是单独 fsync。后台完整作业还含私有镜像验证和前代打开/历史预检，不冒充纯 SQL commit。

| 场景 / 轮 | 无保存请求 Tick：N/均值/p99 | 保存 Tick：N/均值/最大 | 结算 Tick：N/均值/最大 | 工资周期 Tick：N/耗时 |
|---|---|---|---|---|
| active16 / 1 | 1293 / 14.366 / 111.850 | 7 / 42.996 / 71.660 | 7 / 130.647 / 229.455 | 1 / 46.394 |
| active16 / 2 | 1293 / 14.581 / 108.592 | 7 / 43.930 / 65.805 | 7 / 136.290 / 266.523 | 1 / 57.478 |
| active4 / 1 | 1293 / 4.926 / 29.784 | 7 / 35.485 / 49.955 | 7 / 35.936 / 49.062 | 1 / 23.317 |
| active4 / 2 | 1293 / 5.099 / 29.020 | 7 / 31.838 / 46.399 | 7 / 50.432 / 142.638 | 1 / 30.503 |
| empty / 1 | 1293 / 0.992 / 3.559 | 7 / 28.280 / 43.664 | 0 / 0.000 / 0.000 | 1 / 5.493 |
| empty / 2 | 1293 / 1.005 / 3.694 | 7 / 53.249 / 155.247 | 0 / 0.000 / 0.000 | 1 / 5.232 |
| history4 / 1 | 1293 / 8.217 / 33.555 | 7 / 66.318 / 217.933 | 7 / 36.591 / 46.097 | 1 / 33.466 |
| history4 / 2 | 1293 / 8.403 / 33.236 | 7 / 48.514 / 91.210 | 7 / 35.728 / 44.447 | 1 / 48.320 |
| idle16 / 1 | 1293 / 16.456 / 36.318 | 7 / 55.514 / 119.168 | 0 / 0.000 / 0.000 | 1 / 46.032 |
| idle16 / 2 | 1293 / 16.721 / 33.040 | 7 / 65.961 / 188.393 | 0 / 0.000 / 0.000 | 1 / 49.164 |
| idle4 / 1 | 1293 / 5.337 / 12.323 | 7 / 36.115 / 59.437 | 0 / 0.000 / 0.000 | 1 / 32.249 |
| idle4 / 2 | 1293 / 5.379 / 15.733 | 7 / 36.607 / 54.074 | 0 / 0.000 / 0.000 | 1 / 32.036 |

无保存请求 Tick 仍包含工资/批次尖峰，不等于剔除业务后的常态。active16 第一轮七个结算 Tick 平均 130.647ms、最大 229.455ms，说明剩余瓶颈包含同步多建筑完整结算/重开、重复仓储读取和解码，不能只归因于保存或磁盘。history4 运行成本也随历史增加。没有改 200/1200 Tick 或错开经济时间来美化峰值。

原版默认自动保存的独立长探针：6200 Tick，均值 4.675、p50 3.893、p95 5.371、p99 21.637、最大 177.493，无上限吞吐 212.275。实际自动保存 1 次、手动保存/flush 各 1 次，九次维度事件；证据含保存 Tick 和工资/结算分类。后台线程没有访问世界。

### 8.4 加载与崩溃恢复成本

每场景先预热一次，再五次完整 runtime.open，包括只读检查点门禁和 RAM 重建；每次前后主文件/WAL 哈希一致，没有离线补算或关闭时产生新数据写入。

| 场景 | 干净恢复 N | 完整 open 均值 | p50 | p95 = max（N=5） |
|---|---|---|---|---|
| empty | 5 | 21.068 | 21.631 | 25.586 |
| idle4 | 5 | 34.628 | 34.484 | 46.640 |
| active4 | 5 | 46.401 | 40.739 | 68.088 |
| idle16 | 5 | 54.446 | 46.352 | 80.205 |
| active16 | 5 | 71.308 | 68.086 | 99.582 |
| history4 | 5 | 127.122 | 128.753 | 169.528 |

真实 NeoForge 强制退出后的首次恢复：runtime.open 356.025ms（N=1，非热启动统计），其他启动子阶段保存在证据。完整崩溃事实：HALTING 73 at runtime tick 297; saved tick 257；VERIFIED entire checkpoint 0082b22a-2245-44df-9949-82b4a83c1f11 at tick 257。这测量检查点恢复路径，不用整段 Minecraft 启动墙钟冒充 SQLite 加载。hot-journal 的正确性用子 JVM 验证，其耗时没有伪装成上述干净恢复分位数。

## 9. Schema v10 与安全回归

新增 economic_checkpoint 单行元数据：world_id、checkpoint_id、sequence、economic_tick、content_sha256。v9 业务布局及 JSON 内容语义未变化，业务 payload 明确仍为 format 9；这是显式分离的版本合同。旧 v1～v9 Schema 严格拒绝，不迁移、不重建、不兑换资产。复制预检也不得改旧主文件/WAL。没有把旧 UUID、Double 或旧进度重新解释为新结构。

最终执行 .\gradlew.bat test build --rerun-tasks --console=plain：**48 类、657 项、0 失败/错误/跳过，BUILD SUCCESSFUL in 7m 15s**。原 598 保留；旧 P 新增 29；本轮 28 个检查点/恢复测试，加 Schema 参数扩展 2。SqliteTransactionsTest 实际 28 项，含原 M1.0 保护；没有删类或放松有价值断言。

| 验证范围 | 实际证据 |
|---|---|
| 领域与旧安全链 | 200/334、10%、冻结 Batch、全额完成/中止、Payroll 余数、父 Run、真实现金/预算、Staffing/供给、四根初始化、RECONFIGURING、Journal/Reservation/CAS 全回归 |
| 内存事务 | 唯一 RAM authority、两连接排他写权限、跨域回滚、未知 RAM commit 新连接核验、旧磁盘不能冒充当前事实 |
| 完整存档 | 快照不可变、一次全部表/metadata 提交、晚期表写失败整体回滚、摘要篡改拒绝、前代 CAS、Session 不能混用连接 |
| 异步排队 | 一份 in-flight + 一份 pending、合并只在新快照提交后确认、未知结果阻塞、不自动重复写 |
| 真实 SQLite 崩溃 | DELETE/WAL × 提交前/提交后，四个独立 JVM halt 71/72；旧或新整图，hot journal 先副本恢复，前后原文件不变 |
| JDBC 故障 | before/after commit、SQL 失败、rollback/close/savepoint/派生句柄保护仍适用；未知 checkpoint commit 只有一次 commit 后新连接验证 |
| Finance/Payroll 整图 | 真实付款行、双边账本、paid/未付、1201 Tick 的余数一起恢复，原命令重放不再付款 |
| NeoForge full | 四建筑、三公司/地区/币种、真实欠薪与付款、手动暂停保留、BLOCKED 计薪、命令权限/NBT/锚点区块卸载、连续 Batch |
| 独立正常进程重启 | VE process restart verified at accepted Tick 2403 with identical production, liabilities and cash |
| 原版保存故障 | 真正未结束的 RAM writer 阻塞采集；原版维度继续保存；flush 失败且无旧回执冒领；解除后显式保存成功；save-off 门禁 |
| NeoForge 真崩溃 | halt 73 没有 finally/停服保存，恢复后第一 Tick 前核对整个经济图摘要与 Tick |
| 旧格式 | 字面 v1～v9/未知/不完整 Schema、旧 JSON、WAL 字节拒绝保护 |

checkpoint/checkpointFailure/crashRestart namespace 各有 4 个注册方法，只有匹配 mode 的一个实际情境执行，其余显式早退；不将它们冒称四倍业务覆盖。crash 模式的 Gradle 失败是经过退出码 73 和阶段文件核验的故意崩溃，不是忽略失败。最后的正式恢复断言必须成功。

正式 jar victoria_economics-0.1.1-1.21.1.jar：298 项，保存 Mixin 存在，测试类/性能 Mixin/测试结构/注资/崩溃入口泄漏 0。SHA-256 40404424DBBFDF799F0613F71DC5322E1CD75EC3B7DCEC0D101F7710FF99C3FD。完整证据在 [evidence.json](task-p/evidence.json)，日志与临时世界在 build。

## 10. 剩余工作、性能结论与 E-Final

统一 RAM 业务事务和完整定时存档已经落地并通过本轮正确性回归。四建筑平均约 5.167ms，16 建筑约 14.630ms；原版保存协调没有每 Tick 磁盘提交。常见 Tick 有明显余量，但 **active16 p99 仍为 107.717 / 108.540ms，50ms 总预算的严格尖峰验收不通过**。不能因为平均值改善就承诺玩家服稳定 20 TPS；真实玩家/其他 Mod 的余量仍未完整测试。

后续候选设计：在每栋建筑的既有显式 Session 内减少结算/重开重复读取和解码；审查库存 JSON 历史回执与查询成本；如合并 RAM 业务步骤，先证明 StartIntent、结算回执、工资截止、故障毒化及恢复边界，不能直接把整个 ProductionService 包进可重入事务。多建筑同步结算本身不能靠改周期或漏 Tick 平摊。本轮没有擅自重写这套协议。

完整快照捕获与加载随历史增长；当前仅测到 16 建筑、40 关闭段/1000 账本，未证明多年规模。后续增量方案须有不可变版本、完整历史覆盖和恢复证明，不能逐 key 异步丢行。保留最新一份原子检查点，不是多代介质损坏备份；首个检查点前崩溃没有可信恢复点时拒绝自动重建。真实断电/慢盘、长期 GC、大世界和多人网络未测。停服极端 I/O 超时明确失败，不能承诺一个 30 秒全局停服期限。

跨 Minecraft 实物交接仍关闭，库存产权、出售对价、Pending PM 和最终工资分数余数规则仍未决定。没有执行最终出售/拆除，没有擅自补 pig_iron_tools 岗位或发行资金/人口。**E-Final 技术和业务门禁尚未全部满足；本轮停止等待源码审查，不自动进入后续阶段或合并 main。**

## 11. 完整实际文件清单

没有删除基线源码/测试文件。状态 ?? 表示新增未跟踪文件；M 表示修改。下列是交付工作区的实际清单；build 日志/世界/草稿备份不作为源码交付。仅将确认由初次 JavaExec 产生的两个空日志移入 build 保留，未删除未知用户文件。

| 状态 | 实际文件 | 原因 |
|---|---|---|
| M | build.gradle | 隔离运行模式、崩溃测试 classpath、预检任务 |
| M | docs/domain/production-operations/current-runtime.md | 当前合同、完整结果和可复现证据 |
| M | docs/testing/economy-performance.md | 当前合同、完整结果和可复现证据 |
| M | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/AcceptanceTestMod.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| M | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/GameEconomyFixture.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| M | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/RestartAcceptanceGameTests.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| M | src/gameTest/resources/META-INF/neoforge.mods.toml | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/ServerEconomyContext.java | 装配、启停、保存入口和锚点安全 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/command/ProductionCommands.java | 装配、启停、保存入口和锚点安全 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/event/ServerLifecycleEvents.java | 装配、启停、保存入口和锚点安全 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryStore.java | 库存观察点；规则不变 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/application/PayrollAccrualService.java | 事务内复用与精确计算观察 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/application/PayrollPeriodService.java | 事务内复用与精确计算观察 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/payroll/infrastructure/SqlitePayrollRepository.java | 事务内复用与精确计算观察 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/EconomicClock.java | 保留安全推进优化与观测 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBoundaryCoordinator.java | 保留安全推进优化与观测 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java | 保留安全推进优化与观测 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/EconomyDatabase.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/EconomyUnitOfWork.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomySchema.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteConnectionGuard.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteEconomyDatabase.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteTransactions.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| M | src/main/java/com/bbmurloc/victoriaeconomics/server/time/infrastructure/SqliteEconomicTimeRepository.java | 本轮直接关联修改 |
| M | src/main/resources/victoria_economics.mixins.json | 原版服务器级保存协调 |
| M | src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionConcurrencyTest.java | 领域、事务、版本、完整检查点回归 |
| M | src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SchemaCompatibilityTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | docs/design/VE-Task-P-一致性检查点架构.md | 当前合同、完整结果和可复现证据 |
| ?? | docs/testing/VE-Task-P-经济Tick性能诊断与优化验收.md | 当前合同、完整结果和可复现证据 |
| ?? | docs/testing/task-p/README.md | 当前合同、完整结果和可复现证据 |
| ?? | docs/testing/task-p/evidence.json | 当前合同、完整结果和可复现证据 |
| ?? | docs/testing/task-p/measurement-only.patch | 当前合同、完整结果和可复现证据 |
| ?? | docs/testing/task-p/results.json | 当前合同、完整结果和可复现证据 |
| ?? | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/EconomyCheckpointGameTests.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/EconomyPerformanceGameTests.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/PerformanceMeasurements.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/SchemaPreflightMeasurements.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/mixin/PerformanceServerMixin.java | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/resources/data/victoria_economics_checkpointtests/structure/empty.nbt | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/resources/data/victoria_economics_perftests/structure/empty.nbt | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/gameTest/resources/ve-performance.mixins.json | 真实 NeoForge 保存、恢复、性能验证；不进入正式 jar |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/mixin/EconomySaveMixin.java | 原版服务器级保存协调 |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/server/diagnostics/EconomyDiagnostics.java | 默认关闭的耗时/SQL/事件观察 |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/EconomicCheckpointWriter.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/package-info.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/CheckpointFileRecovery.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyCheckpoint.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| ?? | src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/SqliteCheckpointStore.java | 统一 RAM 权威、完整检查点、版本及恢复保护 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/diagnostics/EconomyDiagnosticsTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/financial/PayrollAccrualBatchOptimizationTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/payroll/PayrollCheckpointTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionTickOptimizationTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/storage/MemoryCheckpointRuntimeTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/CheckpointCrashProcess.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/CheckpointPersistenceTest.java | 领域、事务、版本、完整检查点回归 |
| ?? | src/test/resources/fixtures/sqlite/schema-v10.sql | 领域、事务、版本、完整检查点回归 |
| ?? | tools/Measure-Economy.ps1 | 隔离多轮采样和 JSON 汇总 |
| ?? | tools/Summarize-Economy.mjs | 隔离多轮采样和 JSON 汇总 |

复现入口见 [性能方法](economy-performance.md)、[证据索引](task-p/README.md)与 [当前运行地图](../domain/production-operations/current-runtime.md)。历史 A～E 报告保留当时合同，新持久化要求及其替代范围在本报告/设计中明确记录。
