# 工作包 P：经济 Tick 性能诊断、一致性检查点与验收

2026-10-21。已完成本轮安全架构调整和验证，等待源码审查。**严格 50ms 尖峰性能验收仍未通过；没有进入 E-Final，也不具备直接宣布整体重构完成或合并 main 的条件。**

**历史暂停交接（现已获授权恢复，最新结果见第 12 节）：** 当时工作区作为可提交审查的阶段版本保留，代码通过下述 657 项 JUnit、构建及隔离服务器验证，没有执行 Git commit/push。第 1～11 节保留该阶段的原始数据与结论；本次恢复只继续同步结算优化，不进入 E-Final。

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

## 12. 暂停后恢复执行：同步结算优化

### 12.1 恢复基线和测量

实际 HEAD `10c32601f5479a16c80895debeeafa27aad21ff0`，分支 `refactor/m1-0-sqlite-transaction-cleanup`，恢复时工作区干净。保留跨平台 Java 子进程选择和四种真实崩溃测试。没有改变 Schema v10、payload 9、世界内存权威、检查点恢复合同或原版保存协调。

先只添加默认关闭的 EconomyDiagnostics 阶段探针，再运行 `resumeBefore` 六场景双轮及 active32 双轮，共 14 个新世界/独立 JVM，每轮 150 Tick 预热、1300 完整 Tick，测试保存间隔仍为 200，正常 Payroll/付款/Production 均运行。未与自有构建或压力测试并行。优化前探针补丁保存在 [resume-measurement.patch](task-p/resume-measurement.patch)，仅供在独立干净 `10c3260` 副本重现，不要反向套用当前工作区。第 8 节历史数据没有被覆盖。

新增阶段观测包含 start、prepare、commitStart、settlement、followup、reconfigure、Execution load/save、PM load、recipe resolve 和 Codec。阶段 SQL/DML、物理事务、Savepoint、JSON 次数为嵌套包含关系，不能相加当作独立总量。CPU 使用 ThreadMXBean 当前线程计时，Windows 的 CPU 样本较粗，聚合数据与 nanoTime 墙钟分别保留；不把等待/GC 停顿冒充 CPU。分类计数区分普通、结算、工资边界和保存 Tick；后三类可重叠。没有每 Tick 同步打印日志。

优化前 active16 第一轮 7 个同步结算 Tick 平均 118.887ms、最大 225.295ms；这一轮每次完工重开每建筑 Execution 读取 15 次、Batch 解码 30 次、库存解码 8 次、PM 读取 4 次、reconfigure 2 次。库存实际 settlement scope 累计 33.992ms / 112 次；start 累计 419.590ms，Execution load/save 和 Batch Codec 是更值得优先减少的重复成本。上述 scope 互相包含，不将其总和解释为总 Tick。SQL 计时只涵盖 execute 调用，不包括 prepare、bind、ResultSet getter、代理和 Java 对象构造。

### 12.2 局部优化及安全论证

1. Execution 读取在同一查询中按主键关联 current/last Batch，保留未引用未完成 Batch 的独立检查。保存时直接读取 prior Batch，不先 SELECT existence 再读同一行。真实 EXPLAIN 显示主键查找，无新索引、无新 Schema 或持久字段。
2. 同一显式 WriteSession 的 Repository Session 只复用已严格解码的**不可变配置**及其原始 JSON。每次保存仍重新读取数据库行，只有原 JSON 完全相同时才复用；不同即重新严格解码。owner、progress、status、生命周期、Tick 标记及 revision CAS 仍核验。Session 结束后能力失效，不跨事务/连接/Tick 复用，没有全局或 ThreadLocal 缓存。
3. 开工准备及最终开工分别独立进入 IMMEDIATE 写事务，各自在该事务内复用自己已经加载的 Execution。库存开工检查一次读取给出存在、容量、可用投入和完整 Reservation 事实；最终事务重新检查，不使用前一事务的库存事实发许可证。PreparedStart 私有且不发布可写聚合。
4. 自动重开之前，该调用链已完成 settlement 和 reconfigure，不再在 startBatch 内重复运行一次。最终 prepare/commitStart 仍从权威状态重验全部条件。人工 start 仍完整处理边界，失败和恢复链保留。最终提交后只返回不可变 Batch，不为返回值额外加载整棵 Execution；未知提交抛出异常且不发布对象。
5. Codec 保留严格 JsonReader 和原始数字拼写，用局部字符检查及 Long.parseLong 精确检查 long 范围，避免每个数字重新创建 JsonParser、编译正则和构造 BigDecimal。小数、指数、数值字符串、越界及重复字段仍拒绝，编码后的原有完整解码校验仍在，格式合同没有放宽。

StartIntent 记录、库存 Reservation、完整 Settlement Receipt、确认结算、RECONFIGURING、最终开工和 finishTick 的业务提交顺序没有合并。没有把整个 ProductionService 包在可重入事务内。失败仍保留已完成阶段的可信事实，由原恢复协议继续；一栋建筑失败不让其他建筑重复推进。工资截止、供给检查、历史债务及暂停原因、现金/商品守恒均不变。没有调整 200/334/1200 Tick、错开批次、延迟结算或漏处理建筑。

### 12.3 针对性回归

受影响的原有 Production、Inventory、Schema、UoW、Diagnostics 测试通过（首次优化验证 6m08s）。新增 Codec 字面值边界、同步多建筑完工重开、Pending PM/Staffing 与工资截止、缺料和已承诺超容、结算确认失败与幂等恢复、旧/新编排的经济事实等价及完整检查点恢复。新增显式 Session 原 JSON 改变、非法数字拼写和不可逆 ABORTED 状态的测试，确保复用不能隐藏数据变化，捕获内层错误仍使整笔事务回滚。查询计划、读取计数与状态结果共同验证查询优化，不以硬件时间阈值作为 JUnit 通过条件。

新增测试最初两项夹具错误假定 crude_tools 消耗 10 木材且净增库存；实际定义投入/产出各 30。已修正为读取冻结配方，并在超容场景显式选择现有 efficient_tools，没有改动生产规则或放宽断言。最终完整回归和隔离服务器结果在本节后续记录。

### 12.4 完整性能复测与环境漂移

共 51 次完整独立 1300-Tick 样本（66,300 Tick），加一轮实际原版保存 6200 Tick；28 次正式前后、16 次交替配对、6 次关闭细分探针校准和 1 次中间候选试测。中断的启动样本保留但不纳入。全部可核验原始汇总见 [resume-results.json](task-p/resume-results.json)，没有覆盖原 results.json。以下单位 ms，每一分位数都属于该独立运行，不将两个 p99 平均冒充合并分位数。

先后双轮的均值比较如下。期间空世界也由约 1.3ms 漂移到约 0.58ms，故下表“名义降幅”不能单独解释为代码收益；不筛除慢轮、不使用最好轮作结论。Windows/IDE 后台和 JIT/GC/调度没有完全受控。电源方案为 HP 推荐，未修改；WMI 2100MHz 是其报告的时钟字段，不证明采样期间固定频率。

| 场景 | 前均值 | 后均值 | 名义降幅（非因果） | 前 p99 两轮 | 后 p99 两轮 |
|---|---|---|---|---|---|
| empty | 1.318 | 0.896 | 32.037% | 5.243 / 4.387 | 3.489 / 2.045 |
| idle4 | 5.094 | 4.498 | 11.699% | 17.514 / 12.111 | 14.694 / 5.937 |
| active4 | 5.341 | 3.554 | 33.452% | 32.746 / 29.528 | 23.761 / 13.382 |
| idle16 | 17.621 | 14.027 | 20.396% | 56.702 / 48.134 | 50.641 / 15.465 |
| active16 | 14.437 | 8.661 | 40.013% | 88.675 / 118.046 | 43.417 / 24.073 |
| history4 | 8.945 | 3.972 | 55.597% | 42.036 / 41.812 | 14.383 / 13.662 |
| active32 | 28.932 | 11.450 | 60.423% | 144.634 / 138.824 | 25.900 / 24.074 |

完整先后运行分布：

| 标签 | 场景 | 均值 | p50 | p95 | p99 | 最大 | 结算均值 | 结算最大 |
|---|---|---|---|---|---|---|---|---|
| resumeAfter/1 | active16 | 10.653 | 11.374 | 15.124 | 43.417 | 119.379 | 77.535 | 119.379 |
| resumeAfter/2 | active16 | 6.669 | 6.043 | 8.706 | 24.073 | 60.587 | 50.215 | 60.587 |
| resumeAfter/1 | active32 | 11.606 | 10.649 | 14.766 | 25.900 | 97.141 | 83.380 | 97.141 |
| resumeAfter/2 | active32 | 11.295 | 10.395 | 14.155 | 24.074 | 106.732 | 85.852 | 106.732 |
| resumeAfter/1 | active4 | 4.711 | 4.045 | 5.916 | 23.761 | 128.109 | 41.273 | 128.109 |
| resumeAfter/2 | active4 | 2.398 | 2.061 | 3.182 | 13.382 | 29.321 | 15.371 | 18.154 |
| resumeAfter/1 | empty | 1.208 | 0.840 | 1.770 | 3.489 | 70.066 | 0.000 | 0.000 |
| resumeAfter/2 | empty | 0.584 | 0.399 | 0.880 | 2.045 | 34.908 | 0.000 | 0.000 |
| resumeAfter/1 | history4 | 4.058 | 3.684 | 5.053 | 14.383 | 44.133 | 16.379 | 21.339 |
| resumeAfter/2 | history4 | 3.886 | 3.505 | 4.787 | 13.662 | 55.530 | 15.382 | 19.675 |
| resumeAfter/1 | idle16 | 19.120 | 17.260 | 23.556 | 50.641 | 175.333 | 0.000 | 0.000 |
| resumeAfter/2 | idle16 | 8.934 | 8.390 | 11.918 | 15.465 | 58.142 | 0.000 | 0.000 |
| resumeAfter/1 | idle4 | 6.132 | 5.473 | 7.959 | 14.694 | 152.947 | 0.000 | 0.000 |
| resumeAfter/2 | idle4 | 2.865 | 2.604 | 3.700 | 5.937 | 27.656 | 0.000 | 0.000 |
| resumeBefore/1 | active16 | 13.165 | 12.802 | 17.364 | 88.675 | 225.295 | 118.887 | 225.295 |
| resumeBefore/2 | active16 | 15.709 | 13.175 | 19.083 | 118.046 | 181.056 | 126.974 | 167.281 |
| resumeBefore/1 | active32 | 28.924 | 24.594 | 32.250 | 144.634 | 342.785 | 286.561 | 342.785 |
| resumeBefore/2 | active32 | 28.940 | 24.694 | 33.718 | 138.824 | 410.411 | 268.797 | 410.411 |
| resumeBefore/1 | active4 | 5.367 | 4.358 | 6.548 | 32.746 | 114.417 | 35.601 | 44.855 |
| resumeBefore/2 | active4 | 5.315 | 4.354 | 6.753 | 29.528 | 127.632 | 35.585 | 42.041 |
| resumeBefore/1 | empty | 1.305 | 0.841 | 1.929 | 5.243 | 87.532 | 0.000 | 0.000 |
| resumeBefore/2 | empty | 1.332 | 0.923 | 2.117 | 4.387 | 121.091 | 0.000 | 0.000 |
| resumeBefore/1 | history4 | 8.977 | 7.397 | 12.428 | 42.036 | 145.471 | 38.245 | 48.179 |
| resumeBefore/2 | history4 | 8.913 | 7.482 | 12.746 | 41.812 | 128.909 | 37.922 | 44.421 |
| resumeBefore/1 | idle16 | 17.599 | 15.897 | 20.986 | 56.702 | 152.899 | 0.000 | 0.000 |
| resumeBefore/2 | idle16 | 17.643 | 15.950 | 22.327 | 48.134 | 187.597 | 0.000 | 0.000 |
| resumeBefore/1 | idle4 | 5.829 | 5.111 | 8.162 | 17.514 | 86.608 | 0.000 | 0.000 |
| resumeBefore/2 | idle4 | 4.360 | 3.711 | 7.265 | 12.111 | 127.837 | 0.000 | 0.000 |

为检验漂移，用 git archive 建立 build 内独立 10c3260 副本，只加探针补丁；第一轮参考→优化，第二轮优化→参考。用户重置使用状态期间有一次进程在启动阶段中断，已完成十次不重跑，剩余六次采用新目录补齐。以下空世界对照较接近，但第二轮仍漂移，因此只能给出逐轮结果，不能承诺稳定百分比收益。

| 标签 | 场景 | 均值 | p50 | p95 | p99 | 最大 | 结算均值 | 结算最大 |
|---|---|---|---|---|---|---|---|---|
| resumeRef1/1 | empty | 0.575 | 0.396 | 0.869 | 1.539 | 28.136 | 0.000 | 0.000 |
| resumePair1/1 | empty | 0.568 | 0.423 | 0.878 | 1.835 | 29.077 | 0.000 | 0.000 |
| resumeRef1/1 | active4 | 2.425 | 2.063 | 3.218 | 14.342 | 29.998 | 18.632 | 26.748 |
| resumePair1/1 | active4 | 2.244 | 1.939 | 2.981 | 11.714 | 30.237 | 14.170 | 17.084 |
| resumeRef1/1 | active16 | 6.755 | 5.924 | 9.364 | 22.347 | 70.067 | 60.209 | 70.067 |
| resumePair1/1 | active16 | 6.059 | 5.476 | 7.209 | 24.730 | 80.174 | 51.095 | 80.174 |
| resumeRef1/1 | active32 | 12.355 | 11.089 | 16.214 | 31.488 | 129.364 | 109.159 | 129.364 |
| resumePair1/1 | active32 | 11.026 | 10.181 | 13.580 | 30.146 | 104.795 | 83.713 | 104.795 |
| resumeRef2/1 | empty | 0.590 | 0.412 | 0.890 | 1.902 | 29.338 | 0.000 | 0.000 |
| resumePair2/1 | empty | 0.582 | 0.413 | 0.900 | 1.417 | 30.336 | 0.000 | 0.000 |
| resumeRef2r/1 | active4 | 5.969 | 4.735 | 7.986 | 35.718 | 183.131 | 55.635 | 154.220 |
| resumePair2r/1 | active4 | 4.714 | 4.122 | 5.823 | 23.935 | 133.867 | 27.592 | 35.415 |
| resumeRef2r/1 | active16 | 15.745 | 13.237 | 19.347 | 121.499 | 167.685 | 132.378 | 167.685 |
| resumePair2r/1 | active16 | 6.445 | 5.859 | 8.110 | 25.588 | 58.843 | 50.408 | 58.843 |
| resumeRef2r/1 | active32 | 14.871 | 12.750 | 24.249 | 36.550 | 418.096 | 155.392 | 418.096 |
| resumePair2r/1 | active32 | 18.753 | 13.228 | 27.795 | 63.565 | 347.034 | 161.353 | 286.134 |

关闭细分探针后仍运行完整生产/工资/付款/保存业务，只保留完整 Tick 计时。它不是跳过数据库业务；SQL、JSON、阶段 CPU 和结算分类在这些运行中不可用，不能写成零。

| 标签 | 场景 | 均值 | p50 | p95 | p99 | 最大 |
|---|---|---|---|---|---|---|
| resumeCal/1 | active16 | 13.187 | 11.256 | 15.228 | 81.772 | 237.947 |
| resumeCal/2 | active16 | 5.428 | 4.971 | 6.139 | 17.123 | 53.614 |
| resumeCal/1 | active32 | 10.651 | 9.853 | 12.445 | 29.874 | 109.474 |
| resumeCal/2 | active32 | 10.264 | 9.548 | 11.330 | 31.017 | 99.503 |
| resumeCal/1 | active4 | 4.501 | 3.968 | 5.231 | 23.282 | 104.023 |
| resumeCal/2 | active4 | 2.107 | 1.832 | 2.621 | 10.980 | 29.521 |

GameTest 未限速吞吐与 startInterval 保存在原始 JSON，仅表示隔离无玩家测试容量，不是实际玩家服 TPS。normalTicks 是没有原版保存请求的 Tick，仍可能含工资/结算尖峰。MinecraftBeforePost 和扣除 VE 后的剩余时间也含测试框架，不能称为完全隔离的原版 CPU。

### 12.5 SQL、JSON、事务及 CPU 证据

同场景相同七个结算 Tick 的每 Tick 平均计数（含全局检查及一条测试查询；不是每建筑独立计数）：

| 指标 | 优化前 | 优化后 | 减少比例 |
|---|---|---|---|
| sql.read | 2726.429 | 1654.429 | 39.319% |
| sql.dml | 211.000 | 211.000 | 0.000% |
| tx.physical | 295.000 | 215.000 | 27.119% |
| tx.savepoint | 0.000 | 0.000 | 不变 |
| repository.execution.read | 240.000 | 144.000 | 40.000% |
| repository.methods.read | 64.000 | 48.000 | 25.000% |
| json.batch.decode | 480.000 | 244.571 | 49.048% |
| json.inventory.decode | 128.000 | 96.000 | 25.000% |
| json.inventory.encode | 32.000 | 32.000 | 0.000% |
| inventory.receipts.decoded | 496.000 | 368.000 | 25.806% |

DML 数量完全相同；保留真实结算、工资、CAS、Journal 和出生 Tick 标记。减少的是重复只读事务、无权威变化的重复重配置及反复解码。缓存命中是同 Session 原 JSON 完全相同的已验证配置复用，不是库存/现金/人数第二权威。Journal 独立 DML 没有计入 managed tx.physical。

按 Tick 类型的“样本数 / SELECT / DML / managed事务”如下。后三类允许重叠，不能机械加总；正式默认仍原版保存间隔，200 只用于压力测试。

| 类别 | 前 | 后 |
|---|---|---|
| ordinary | 1285 / 393.207 / 51.000 / 23.000 | 1285 / 330.402 / 51.000 / 23.000 |
| settlement | 7 / 2726.429 / 211.000 / 295.000 | 7 / 1654.429 / 211.000 / 215.000 |
| payroll | 1 / 622.000 / 203.000 / 27.000 | 1 / 558.000 / 203.000 / 27.000 |
| save | 7 / 465.000 / 51.000 / 24.000 | 7 / 401.000 / 51.000 / 24.000 |

配对首轮（空世界对照接近）active16 的各阶段数据如下。CPU 为该阶段累计 ms，不是均值；CPU 缺失表示未单独计量，0.000 也可能只是粗粒度计时没有分配到这个短 scope，不能解释为没有 CPU 成本。Scope 包含关系不可相加。细小判定、工资查询与确认 Tick 的成本分别包含在 processTick / prepare / reconfigure / Execution load/save 中，没有把所有 if 或 SQL getter 冒称单独 CPU 火焰图。

| 阶段 | 前次数 | 前均值 | 后次数 | 后均值 | 前累计 CPU | 后累计 CPU |
|---|---|---|---|---|---|---|
| production.processTick | 20800 | 0.303 | 20800 | 0.266 | 6140.625 | 5359.375 |
| production.execution.load | 22368 | 0.068 | 21696 | 0.078 | 1578.125 | 1843.750 |
| production.execution.save | 21248 | 0.128 | 21248 | 0.085 | 2343.750 | 1500.000 |
| production.followup | 224 | 0.839 | 112 | 1.303 | 250.000 | 93.750 |
| production.settlement | 112 | 0.187 | 112 | 0.228 | 46.875 | 15.625 |
| production.reconfigure | 224 | 0.444 | 112 | 0.567 | 125.000 | 46.875 |
| production.methods.load | 448 | 0.037 | 336 | 0.053 | 0.000 | 0.000 |
| production.recipe.resolve | 1232 | 0.004 | 1120 | 0.004 | — | — |
| production.prepare | 112 | 0.390 | 112 | 0.337 | 62.500 | 46.875 |
| production.commitStart | 112 | 0.645 | 112 | 0.562 | 46.875 | 31.250 |
| production.start | 112 | 1.858 | 112 | 1.126 | 187.500 | 125.000 |
| codec.batch.decode | 84576 | 0.013 | 42320 | 0.007 | — | — |
| codec.inventory.decode | 896 | 0.033 | 672 | 0.025 | — | — |
| inventory.reserve | 112 | 0.165 | 112 | 0.152 | — | — |

主线程 VE 占用及 SQL（每 Tick）如下，含 Context、独立捕获及等待；SQL 为计数而非磁盘 fsync。

| 场景 | 轮 | Context ms | VE ms | 总 Tick 占比 | managed事务 | SELECT | DML |
|---|---|---|---|---|---|---|---|
| active16 | 1 | 10.228 | 10.251 | 96.228% | 24.042 | 338.087 | 51.978 |
| active16 | 2 | 6.353 | 6.366 | 95.458% | 24.042 | 338.087 | 51.978 |
| active32 | 1 | 11.322 | 11.340 | 97.705% | 41.076 | 648.770 | 100.951 |
| active32 | 2 | 11.005 | 11.025 | 97.616% | 41.076 | 648.770 | 100.951 |
| active4 | 1 | 4.282 | 4.301 | 91.309% | 11.267 | 105.075 | 15.249 |
| active4 | 2 | 2.135 | 2.144 | 89.406% | 11.267 | 105.075 | 15.249 |
| empty | 1 | 0.766 | 0.781 | 64.648% | 7.008 | 25.386 | 3.002 |
| empty | 2 | 0.351 | 0.358 | 61.280% | 7.008 | 25.386 | 3.002 |
| history4 | 1 | 3.758 | 3.797 | 93.558% | 11.267 | 145.075 | 55.280 |
| history4 | 2 | 3.599 | 3.635 | 93.541% | 11.267 | 145.075 | 55.280 |
| idle16 | 1 | 18.533 | 18.618 | 97.372% | 23.008 | 635.552 | 35.117 |
| idle16 | 2 | 8.610 | 8.622 | 96.511% | 23.008 | 635.552 | 35.117 |
| idle4 | 1 | 5.706 | 5.723 | 93.328% | 11.008 | 179.441 | 11.034 |
| idle4 | 2 | 2.607 | 2.615 | 91.277% | 11.008 | 179.441 | 11.034 |

JSON 次数及完整历史回执遍历仍随历史增长；没有拆除历史回执或另造缓存。SQL execute 时间不包含 prepare/bind/getters/Java 映射。部分 SQL 时间样本达到有界探针上限，完整计数仍保存；被丢弃时间样本为 task-e-gametest-p_resumeBefore_active32_1: dropped.sql.read=20841；task-e-gametest-p_resumeBefore_active32_2: dropped.sql.read=20841；task-e-gametest-p_resumeRef1_active32_1: dropped.sql.read=20841；task-e-gametest-p_resumeRef2r_active32_1: dropped.sql.read=20841。不得按已记录子样本冒充未记录的全部 SQL 时间。

### 12.6 检查点与恢复成本

| 场景 | 捕获 N | 捕获均值 | 捕获最大 | 后台 N | 验证均值 | 打开均值 | 写事务均值 | 写事务最大 | 后台总均值 |
|---|---|---|---|---|---|---|---|---|---|
| empty | 14 | 2.047 | 5.997 | 16 | 11.531 | 10.289 | 9.287 | 20.969 | 31.421 |
| idle4 | 14 | 2.286 | 4.663 | 16 | 16.814 | 21.829 | 11.722 | 40.458 | 50.737 |
| active4 | 14 | 2.675 | 6.305 | 16 | 18.334 | 14.616 | 13.260 | 47.526 | 46.523 |
| idle16 | 14 | 8.972 | 80.568 | 16 | 24.333 | 18.368 | 12.886 | 33.309 | 55.945 |
| active16 | 14 | 3.350 | 6.879 | 16 | 23.356 | 17.324 | 11.578 | 19.964 | 52.628 |
| history4 | 14 | 6.898 | 11.537 | 16 | 40.801 | 31.116 | 19.622 | 25.882 | 91.797 |
| active32 | 14 | 3.564 | 6.181 | 16 | 23.351 | 19.113 | 11.399 | 21.737 | 54.141 |

后台表排除四行空世界引导作业，包含压力保存及采样后最终验收 flush，不能当作每 Tick 磁盘事务数。写事务包括 BEGIN、整图写入、commit 与清理，不是纯 fsync。磁盘 DELETE/FULL 未降低。正常停止和未知提交恢复基础设施没有修改。

实际默认自动保存的 6200-Tick 运行：均值 4.280、p50 3.770、p95 5.091、p99 12.182、最大 131.827。全局原版请求 3、自动 1、flush 1，维度事件 9。检查点提交和 Minecraft 各文件仍不是共同物理事务。

真实崩溃恢复：HALTING 73 at runtime tick 292; saved tick 252；VERIFIED entire checkpoint 285d70d0-44b2-43a7-a1c7-1483754c3d33 at tick 252。首次完整 runtime.open 331.908ms（N=1 冷恢复），不与第 8.4 节旧阶段热恢复分位数混算。VE process restart verified at accepted Tick 2403 with identical production, liabilities and cash。没有补算离线时间。

### 12.7 最终回归、故障和交付状态

完整命令 .\gradlew.bat test build --rerun-tasks --console=plain：**50 类、688 项、0 失败/错误/跳过，BUILD SUCCESSFUL in 4m 39s**。保留原 657 项，新增 31 项有效回归。SqliteTransactionsTest 28 项；DELETE/WAL × before/after 四个真实子 JVM halt 用例全部执行，没有 skip 或改弱断言。Java 可执行文件的跨平台选择未改回 java.exe 硬编码。

| 范围 | 实际结果 |
|---|---|
| 真实工资边界同 Tick 完工 | 新增第六批在 Tick 1200 完工并原 Tick 开下一批；真实公司扣款 14、居民到账 14、paid 14，重试不重复付款/产出 |
| 同 Tick 多建筑与 Pending | 新批进度零；PM/Staffing 生效前旧工资截止，冻结旧配方及人数不改 |
| 异常与恢复 | 确认结算 UPDATE 失败不启动新批；回执重放无额外产出，冲突完成/中止拒绝；旧 JDBC/rollback/savepoint/未知提交回归均通过 |
| Session 复用 | 原 JSON 改变、非法数字、已决定 ABORTED 均不能被复用隐藏，捕获内层异常仍整笔回滚 |
| 检查点 | 新路径运行后整图捕获、私有 RAM 安装及摘要一致；旧版本/热 journal/WAL 字节保护原测试保持 |
| NeoForge full | 实际四建筑、三公司/地区/币种、BLOCKED 继续计薪、真实欠薪付款、MANUAL 暂停保留、命令权限/NBT/远锚点卸载、连续生产通过 |
| 原版保存与失败 | 实际自动/save-all/flush、多维去重、save-off、两次捕获失败不冒领旧回执，原版文件继续保存，通过 |
| 独立正常/崩溃重启 | 完整业务事实及经济时间核验通过；崩溃 halt 73 是预期非零退出，后续 crashRestart 必须真正成功 |
| 正式 jar | 302 项，正式保存 Mixin 在，测试/注资/崩溃入口泄漏 0；SHA-256 E355AEB1ED2F323604ABC982DE1672AC204336748E77ACE49ABCA8DB72FF56E7 |
| 真实世界保护 | 只做文件哈希，前后 3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8；WAL 不存在，未用 SQLite 打开该真实世界 |
| 云端与 Linux | 本地 Windows JDK21 成功；未本地运行 Linux，当前优化尚未推送，Actions 必须由用户手动推送后核实 |

六次服务器启动包含一次故意 halt；checkpoint 命名空间虽然注册四个方法，每 mode 只有一个实际情境，其余早退，不把注册数当作四倍验收。所有日志、世界及失败尝试保留在 build；主要可发布证据见 [resume-evidence.json](task-p/resume-evidence.json)。

### 12.8 未通过项与后续候选

**本轮正确性回归通过，严格性能验收仍未通过，工作包 P 不能宣告最终验收结束。** 明确减少了查询/解码/事务重复，部分配对结算明显缩短，但收益受环境漂移影响，不存在足够依据承诺稳定总体降幅。active16 集中结算仍有 >50ms；active32 第二配对轮优化版均值还劣于参考版。即使普通 Tick 的 p99 小于 50ms，七个结算 Tick 少于总样本 1%，该分位数也不能代替结算独立分布。关闭探针的轮次仍超过预算，不能把全部问题归因于探针。50ms 是 Minecraft 总预算，不是 VE 独占额度；尚无真实玩家/其他 Mod 长期容量验收。

后续应先固定系统背景与供电条件、记录 JIT/GC/调度证据，再做交替多轮测量；本轮没有擅自改电源设置或用最好轮替代。参考配对的 joined-load 平均成本并非总是下降，虽然 SQL 数更少；可独立比较“两 BatchId 一次条件查询”与当前关联查询的 prepare/getter/映射成本，再决定是否保留查询形态。未据此修改现有结算时序。更大幅减少物理业务阶段需要单独证明 StartIntent、完整回执、RECONFIGURING、工资截止及未知提交等价性，本轮没有合并这些资产边界。完整库存历史 JSON/整图快照仍随历史增长，没有通过删历史或降低 durability 换速度。CPU 仅阶段计量，未做逐栈 JFR/真实断电/长期玩家服或慢盘验收。

用户恢复后补充允许把未通过过程保留在报告、交 GPT 聊天模式讨论后再修正，因此本轮停在已验证的局部优化与完整证据，不继续改架构、不进入 E-Final。Pending PM 出售、库存产权、交易对价和工资最终余数仍未决，最终生命周期交割继续 BLOCKED。没有改经济规则、真实存档、默认资金/人口、市场或跨原版物品交换；没有 commit、push、merge、reset。

### 12.9 本轮实际文件清单

| 状态 | 文件 | 原因 |
|---|---|---|
| M | `docs/design/VE-Task-P-一致性检查点架构.md` | 阶段报告、方法和可核验证据 |
| M | `docs/domain/production-operations/current-runtime.md` | 阶段报告、方法和可核验证据 |
| M | `docs/testing/VE-Task-P-经济Tick性能诊断与优化验收.md` | 阶段报告、方法和可核验证据 |
| M | `docs/testing/economy-performance.md` | 阶段报告、方法和可核验证据 |
| M | `docs/testing/task-p/README.md` | 阶段报告、方法和可核验证据 |
| M | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/EconomyPerformanceGameTests.java` | 实际规模和分类计数观测，不进入正式 jar |
| M | `src/gameTest/java/com/bbmurloc/victoriaeconomics/gametest/PerformanceMeasurements.java` | 实际规模和分类计数观测，不进入正式 jar |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/diagnostics/EconomyDiagnostics.java` | 复用诊断，阶段 CPU/SQL/JSON 观察 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/inventory/infrastructure/SqliteInventoryStore.java` | 一次事务内开工库存事实读取 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionBoundaryCoordinator.java` | 显式 Session 和事务内状态复用 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionService.java` | 去重复自动边界、提交后不可变返回值 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/domain/ProductionMethodRules.java` | 配方/PM 观测，规则不变 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionExecutionRepository.java` | 关联读取、一次 prior 查询、同 Session 验证配置复用 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/production/infrastructure/SqliteProductionMethodConfigurationRepository.java` | 配方/PM 观测，规则不变 |
| M | `src/main/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/EconomyStateCodec.java` | 保持格式的严格数字解析优化 |
| M | `src/test/java/com/bbmurloc/victoriaeconomics/server/diagnostics/EconomyDiagnosticsTest.java` | 阶段计数与嵌套观测回归 |
| M | `tools/Measure-Economy.ps1` | 32 栋场景与正确汇总 |
| M | `tools/Summarize-Economy.mjs` | 32 栋场景与正确汇总 |
| ?? | `docs/testing/task-p/resume-evidence.json` | 阶段报告、方法和可核验证据 |
| ?? | `docs/testing/task-p/resume-measurement.patch` | 优化前独立副本的探针复现 |
| ?? | `docs/testing/task-p/resume-results.json` | 阶段报告、方法和可核验证据 |
| ?? | `src/test/java/com/bbmurloc/victoriaeconomics/server/production/application/ProductionSettlementOptimizationTest.java` | 12 项真实事务/生产/工资/等价性回归 |
| ?? | `src/test/java/com/bbmurloc/victoriaeconomics/server/storage/sqlite/CodecIntegerParsingTest.java` | 18 项严格字面值回归 |

此清单针对恢复基线 10c3260；第 11 节是暂停前的历史清单。build 内参考副本、脚本、日志和临时数据库未提交。

## 13. 借鉴 Create 的 DDD 兼容性能实验

### 13.1 基线、范围与最终决定

基线 `4ccf5112ba698b918189636361eb0b5178f52af8`，分支 `refactor/m1-0-sqlite-transaction-cleanup`，开始时工作区干净。保留第 12 节全部已验证成果及旧数据。本轮只实现一个候选：无 Pending、无资源边界的空闲建筑通过事务内事实证明重配置无效果时，跳过完整重配置。**6 次对照已到上限，稳定收益不确定，候选正式代码及新增测试已撤回；最终只交付报告、原始汇总和可审查补丁。** 没有第二套优化、Schema 改动或 E-Final。

候选见 [create-candidate.patch](task-p/create-candidate.patch)，其内容不参与当前编译；在独立原基线副本中应用检查通过。正式 src/main、src/test、src/gameTest 的 git diff 为空；用反向补丁撤回自己的唯一改动，没有 reset 或覆盖任务开始前的工作。

### 13.2 Create 官方依据

2026-10-21 通过官方分支实际查询到 `7ff2760e4924d35d4bc2e22c4b19923f0178cd16`；下载并阅读固定提交的五个文件，摘要在 [实验依据](task-p/create-experiment-evidence.json)。不以 Create 的机器更新代替 VE 事务。

| 官方源码（固定提交） | 参考及边界 |
|---|---|
| [DeferralBehaviour](https://github.com/Creators-of-Create/Create/blob/7ff2760e4924d35d4bc2e22c4b19923f0178cd16/src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/simple/DeferralBehaviour.java) | 请求合并；回调成功才清除待处理标记。该实现把标记写 NBT，VE 不照搬为第二权威。 |
| [KineticNetwork](https://github.com/Creators-of-Create/Create/blob/7ff2760e4924d35d4bc2e22c4b19923f0178cd16/src/main/java/com/simibubi/create/content/kinetics/KineticNetwork.java) | 聚合结果改变才同步；update 方法仍计算聚合，不声称其所有计算都只在变化时发生。成员/区块规则不适合 VE。 |
| [RecipeFinder](https://github.com/Creators-of-Create/Create/blob/7ff2760e4924d35d4bc2e22c4b19923f0178cd16/src/main/java/com/simibubi/create/foundation/recipe/RecipeFinder.java) | 静态条件缓存与后续动态筛选分离，资源重载清空静态结果。 |
| [RecipeTrieFinder](https://github.com/Creators-of-Create/Create/blob/7ff2760e4924d35d4bc2e22c4b19923f0178cd16/src/main/java/com/simibubi/create/foundation/recipe/trie/RecipeTrieFinder.java) | 复用静态配方索引，重载失效；不代表库存预留或工资状态可以缓存。 |
| [BasinOperatingBlockEntity](https://github.com/Creators-of-Create/Create/blob/7ff2760e4924d35d4bc2e22c4b19923f0178cd16/src/main/java/com/simibubi/create/content/processing/basin/BasinOperatingBlockEntity.java) | 连续加工复用配方前再次匹配动态输入，apply 成功后通知内容变化；不能把配方匹配当成财务事务。 |

借鉴的是“昂贵工作前证明是否需要工作”和“静态候选与动态资格分离”；没有复制可变 needsUpdate、静态领域缓存、BlockEntity 调度、浮点动力或 NBT/SavedData 权威。KineticNetwork 的成员卸载机制不能让 VE 在区块卸载时停摆。Basin 的再次匹配也不能替代 Reservation、Payroll、CAS 或结算回执。

### 13.3 一次诊断与唯一候选

原 advance 的 completeSimple 分支在无 Batch、automatic=false、boundary=NONE、无 StartIntent 时，每 Tick 仍加载 PM/Staffing 并进入 reconfigure。一次真实 SQLite 诊断记录每栋 35 SELECT、1 DML、1 物理事务，包含地区供给与就业读取。已有 idle16 的 635.552 SELECT/Tick 在本轮参考版重现；不是另起一套探针。

候选只修改 ProductionBoundaryCoordinator：复用已经读取的 EconomicTime；仍在现有 IMMEDIATE 事务内加载 PM 与 Staffing；仅在工资游标追平、状态 CURRENT/ARREARS、PM 和 Staffing 都无 Pending、领域 forCapacity 不改变人数、活跃 Payroll 已应计至本 Tick、币种和历史公司/地区/人数匹配时跳过 reconfigure。RECOVERY/UNAVAILABLE 走原路径。没有全局布尔、缓存、后台队列或新 Repository。

PM 完整解析和 Staffing 构造刻意保留：v10 没有可以普遍代替岗位合法性验证的“已应用配置版本”凭据；仅看 pending_json IS NULL 不足以证明岗位无需收缩。每 Tick 的工资状态查询、PauseReason、生命周期检查、StartIntent 检查、Execution CAS/finishTick 都保留。单栋诊断读数 35→22，DML/事务仍 1/1。

DDD 归属不变：领域 StaffingTransition 判断容量变化，PM 仍负责合法组合；应用协调器在同一 WriteSession 证明本次业务无效果；基础设施继续提供当前事务事实和提交保护。没有合并领域根，也没有让提示成为权威。

### 13.4 不变量与正确性验证

独立经济时钟先调用 PayrollAccrualService.recoverAcceptedTime，accrueAll 包含历史段、精确余数及到期化。候选额外校验当前段已到本 Tick，不用全局游标代替登记或工资身份。未来实际人数变化、Pending PM/Staffing、容量降低及 RECONFIGURING 仍进入原工资截止和跨根事务；Pending=0 不被视为不存在。地区供给增加且无 Pending 不自动补招；新的正式请求即时处理。供给降低仍受原真实 occupied 约束。

没有 Pending 且容量不变时，区域 available 不能产生任何实际人数修改，故该汇总可略过；不能在有 Pending 时复用它。正常完成、ABORTED、200/334/1200 Tick、同 Tick 开新批、工资付款、库存和 StartIntent 都不在候选范围。设备系统已退出，设备变化是“不适用”，没有虚构设备测试或再引入设备因素；普通商品不变。BLOCKED 生命周期不进入候选，原继续计薪路径保留。

| 候选针对性测试类 | 项数 | 失败/错误/跳过 |
|---|---|---|
| `IdleReconfigurationTest` | 11 | 0/0/0 |
| `PayrollPaymentPauseIntegrationTest` | 2 | 0/0/0 |
| `ProductionIntegrationTest` | 19 | 0/0/0 |
| `ProductionSettlementOptimizationTest` | 12 | 0/0/0 |
| `ProductionTickOptimizationTest` | 19 | 0/0/0 |

本轮新增 11 项只存在于归档补丁：空闲无 Pending；Pending PM；Pending Staffing=0/7；共享供给与部分招聘一次消费/新请求立即生效；RECOVERY；旧段余数与新工资率；每建筑 Tick 写入失败、重试及完整检查点安装；提交前/后回执未知的新连接核验；1201 Tick 原完整重配置与候选经济最终事实等价。原测试同时覆盖运行中冻结与延迟生效、同 Tick 完工、Tick1200 真付款、欠薪及部分付款/MANUAL 恢复、BLOCKED 继续计薪。

候选针对性命令 test --tests *IdleReconfigurationTest --tests *ProductionTickOptimizationTest --tests *ProductionSettlementOptimizationTest --tests *PayrollPaymentPauseIntegrationTest --tests *ProductionIntegrationTest：**63 项、0 失败/错误/跳过，3m30s**。撤回后又执行原 ProductionTickOptimizationTest：**19 项、0 失败/错误/跳过，6s**。没有删改原 688 项的任何源码/断言。

IDE logpoint 尝试两次，后续取事件时会话均已消失，没有有效运行值；已清理自身 logpoints，保留用户断点原状态。诊断结论来自真实 SQLite 测试和已有 EconomyDiagnostics，不把调试器没有输出当成未执行证据。

### 13.5 有限 A/B 方法和完整数据

只有下列六个独立 JVM/隔离世界：参考 idle16→候选 idle16→候选 active16→参考 active16→参考 active32→候选 active32。每次 150 Tick 预热、1300 Tick 样本；总 7800 Tick；没有复跑、校准、额外空世界或筛选最好轮。参考由 git archive 提取原 4ccf5112，未加优化/新探针；双方复用原工具及默认开启诊断，压力存档间隔同为 200（测试参数，非正式存档规则），完整生产、工资和付款全部运行。六个实际 NeoForge perf 验收标记均 PASSED。

硬件/环境：i7-12700（12核/20线程）、约16GB RAM、Windows11 10.0.26200、JDK21.0.12；服务端最大堆约4GB；HP 推荐电源方案未更改；测试开始可用内存约3.13GiB。工作区 C: 位于 NVMe WDC PC SN530；另有 SATA HDD，不据设备列表推定 DB 被写到 HDD。SQLite 运行时共享内存，检查点 DELETE/FULL，无降级。没有同时执行自有构建/另一服务器压力负载；IDE及外部后台仍在，未锁频、未记录逐栈 JFR/GC/JIT/调度轨迹。

以下单位 ms；每行属于唯一一次运行，不能用本组建立跨 JVM 可重复性。原始完整汇总见 [create-experiment-results.json](task-p/create-experiment-results.json)，顺序、环境、哈希及测试证据见 [create-experiment-evidence.json](task-p/create-experiment-evidence.json)。

| 版本/场景 | 均值 | p50 | p95 | p99 | 最大 | VE 均值 | VE 占比 |
|---|---|---|---|---|---|---|---|
| 参考/idle16 | 19.618 | 17.649 | 22.727 | 52.639 | 327.264 | 19.110 | 97.410% |
| 候选/idle16 | 14.445 | 13.120 | 16.866 | 41.988 | 141.639 | 13.979 | 96.778% |
| 参考/active16 | 13.766 | 11.896 | 16.419 | 84.602 | 153.475 | 13.216 | 96.003% |
| 候选/active16 | 13.554 | 11.857 | 15.595 | 55.098 | 293.096 | 13.131 | 96.882% |
| 参考/active32 | 25.135 | 22.417 | 29.187 | 127.833 | 252.871 | 24.617 | 97.939% |
| 候选/active32 | 11.987 | 10.950 | 15.248 | 31.082 | 120.656 | 11.672 | 97.368% |

非原版保存 Tick（normalTicks）如下；该旧指标仍可能包含结算或工资边界，不能冒充完全无事件的普通 Tick 独立分布。完全 ordinary 的 SQL/事务分类另列。

| 版本/场景 | N | 均值 | p95 | p99 | 最大 |
|---|---|---|---|---|---|
| 参考/idle16 | 1293 | 19.395 | 22.347 | 40.135 | 327.264 |
| 候选/idle16 | 1293 | 14.213 | 16.687 | 24.245 | 141.639 |
| 参考/active16 | 1293 | 13.504 | 16.217 | 84.602 | 153.475 |
| 候选/active16 | 1293 | 13.369 | 15.531 | 55.098 | 293.096 |
| 参考/active32 | 1293 | 24.926 | 29.047 | 129.426 | 252.871 |
| 候选/active32 | 1293 | 11.863 | 15.021 | 19.172 | 120.656 |

同步结算单独分布如下，active 每版仅 7 个结算 Tick，不能凭全局 p99 掩盖这少于 1% 的尖峰。

| 版本/场景 | 结算 N | 均值 | p95 / p99 | 最大 |
|---|---|---|---|---|
| 参考/idle16 | 0 | 不适用 | 不适用 | 不适用 |
| 候选/idle16 | 0 | 不适用 | 不适用 | 不适用 |
| 参考/active16 | 7 | 94.620 | 118.543 / 118.543 | 118.543 |
| 候选/active16 | 7 | 124.167 | 293.096 / 293.096 | 293.096 |
| 参考/active32 | 7 | 178.003 | 252.871 / 252.871 | 252.871 |
| 候选/active32 | 7 | 89.218 | 120.656 / 120.656 | 120.656 |

### 13.6 工作量、CPU 和检查点

| 版本/场景 | SQL 读取/Tick | DML/Tick | 管理事务/Tick | Batch 解码总数 | 库存解码总数 | 快速路径命中 |
|---|---|---|---|---|---|---|
| 参考/idle16 | 635.552 | 35.117 | 23.008 | 0 | 0 | 0 |
| 候选/idle16 | 427.552 | 35.117 | 23.008 | 0 | 0 | 20800 |
| 参考/active16 | 338.087 | 51.978 | 24.042 | 42320 | 672 | 0 |
| 候选/active16 | 338.087 | 51.978 | 24.042 | 42320 | 672 | 0 |
| 参考/active32 | 648.770 | 100.951 | 41.076 | 84640 | 1344 | 0 |
| 候选/active32 | 648.770 | 100.951 | 41.076 | 84640 | 1344 | 0 |

参考/候选的 active16、active32：SELECT、DML、物理事务、Batch/库存编解码总数逐项完全相同，快速路径命中均为 0。idle16 的 20800 次命中节省 270400 SELECT（208/Tick，整体 -32.727%），DML/事务/JSON 不变；依然完整解析 PM、加载 Staffing，没有缓存这两份权威。

| 版本/场景 | 类别 | N | SELECT/Tick | DML/Tick | 事务/Tick |
|---|---|---|---|---|---|
| 参考/idle16 | ordinary | 1292 | 635.000 | 35.000 | 23.000 |
| 参考/idle16 | settlement | 0 | 不适用 | 不适用 | 不适用 |
| 参考/idle16 | payroll | 1 | 862.000 | 187.000 | 27.000 |
| 参考/idle16 | save | 7 | 705.000 | 35.000 | 24.000 |
| 候选/idle16 | ordinary | 1292 | 427.000 | 35.000 | 23.000 |
| 候选/idle16 | settlement | 0 | 不适用 | 不适用 | 不适用 |
| 候选/idle16 | payroll | 1 | 654.000 | 187.000 | 27.000 |
| 候选/idle16 | save | 7 | 497.000 | 35.000 | 24.000 |
| 参考/active16 | ordinary | 1285 | 330.402 | 51.000 | 23.000 |
| 参考/active16 | settlement | 7 | 1654.429 | 211.000 | 215.000 |
| 参考/active16 | payroll | 1 | 558.000 | 203.000 | 27.000 |
| 参考/active16 | save | 7 | 401.000 | 51.000 | 24.000 |
| 候选/active16 | ordinary | 1285 | 330.402 | 51.000 | 23.000 |
| 候选/active16 | settlement | 7 | 1654.429 | 211.000 | 215.000 |
| 候选/active16 | payroll | 1 | 558.000 | 203.000 | 27.000 |
| 候选/active16 | save | 7 | 401.000 | 51.000 | 24.000 |
| 参考/active32 | ordinary | 1285 | 633.805 | 99.000 | 39.000 |
| 参考/active32 | settlement | 7 | 3281.857 | 419.000 | 423.000 |
| 参考/active32 | payroll | 1 | 1054.000 | 395.000 | 43.000 |
| 参考/active32 | save | 7 | 705.000 | 99.000 | 40.000 |
| 候选/active32 | ordinary | 1285 | 633.805 | 99.000 | 39.000 |
| 候选/active32 | settlement | 7 | 3281.857 | 419.000 | 423.000 |
| 候选/active32 | payroll | 1 | 1054.000 | 395.000 | 43.000 |
| 候选/active32 | save | 7 | 705.000 | 99.000 | 40.000 |

以下现有 stage 为包含式墙钟及当前线程 CPU；CPU 在 Windows 下计时较粗，累计值不等于整个 JVM CPU，阶段之间不能相加。没有尝试用探针关闭校准来寻找好看的结果。

| 版本/场景 | processTick 均值 | 阶段累计 CPU | PM load 均值 |
|---|---|---|---|
| 参考/idle16 | 1.009 | 19234.375 | 0.050 |
| 候选/idle16 | 0.688 | 13093.750 | 0.049 |
| 参考/active16 | 0.608 | 11484.375 | 0.074 |
| 候选/active16 | 0.598 | 11609.375 | 0.082 |
| 参考/active32 | 0.589 | 22093.750 | 0.064 |
| 候选/active32 | 0.279 | 11328.125 | 0.034 |

检查点指标继续保留，后台作业包括引导、预热和最终验收 flush，不是每 Tick 作业数；整图写事务包含清理，不等于纯 fsync。

| 版本/场景 | 捕获 N/均值/最大 | 全程磁盘作业数 | 最大整图写事务 | 最大作业总耗时 |
|---|---|---|---|---|
| 参考/idle16 | 7 / 4.605 / 6.717 | 8 | 45.182 | 133.437 |
| 候选/idle16 | 7 / 5.503 / 11.514 | 8 | 25.776 | 95.674 |
| 参考/active16 | 7 / 5.778 / 8.089 | 8 | 34.331 | 103.425 |
| 候选/active16 | 7 / 5.632 / 8.223 | 8 | 22.637 | 112.198 |
| 参考/active32 | 7 / 8.083 / 10.627 | 8 | 38.628 | 161.988 |
| 候选/active32 | 7 / 3.496 / 5.063 | 8 | 22.517 | 105.728 |

### 13.7 事实、推断、未证实原因与停止

**事实：** idle16 观察均值 19.618→14.445（名义 -26.37%），SELECT 635.552→427.552；active16 结算均值 94.620→124.167、结算最大 118.543→293.096，出现不利尾部。active32 未命中候选、工作量相同，却观察均值 25.135→11.987（名义 -52.31%）。所有版本仍有 >50ms 峰值。没有 active32 回退，不声称触发停止条件5；触发的是稳定收益证据不足（4）和六次上限（8）。

**合理推断：** 运行环境/JVM 成本存在重要变动，不能把 active32 的下降归功于“跳过空闲重配置”，也不能把 idle16 的全部名义收益当成候选收益。方法体变化本身也可能影响 JIT；工作量相同不证明机器执行成本绝对相同。**未证实：** 本次没有证据定位到某次 GC、具体 JIT 去优化、CPU 热/频率或 Windows 调度，不把这些可能性写成根因。

因此停止编码和性能运行，撤回自己的候选及新测试到原已验基线，仅归档补丁供审查；不否定查询减少和无变化假设的诊断价值，也不宣称性能因果通过或失败。没有为了 50ms 改业务时序。工作包 P 性能验收仍未通过。

### 13.8 最终交付及后续审查边界

本轮最终变更只有六个文档/证据文件：本报告、第13节对应的 [决策报告](VE-Task-P-Create借鉴与后续决策.md)、task-p/README.md、task-p/create-experiment-results.json、task-p/create-experiment-evidence.json、task-p/create-candidate.patch。正式源码/测试源码/Schema/检查点均没有差异。

因为最终不保留候选，本轮没有重跑完整 test build，也没有重新宣称正常停服、独立重启和强制崩溃全套验收通过；完整688项及保存/崩溃恢复仍引用第12节已验基线。本轮实际是候选63项、恢复原版19项、6次NeoForge perf。遵守“不保留正式代码时无需重跑大型验收”的工作量门禁；未推送，本地证据不能代替新的 GitHub Actions。

真实世界仅做文件哈希，仍为 3250D82E8EB2C156211D924A2F8A40A2B018146AB983313F1A09C8D5EC681AE8，WAL 不存在；未用 SQLite 打开真实世界。没有 commit、push、merge、reset 或 E-Final。

建议 ChatGPT 下一步审查：①补丁的“无变化”证明是否完整，特别是未来工资率/身份修改路径；②是否愿意仅凭减少查询保留小优化，或先批准带负对照及 GC/JIT/调度证据的另一轮严格限额实验；③将空闲开销和同步结算尖峰作为不同问题讨论。持续生产尖峰没有被本候选解决。静态配方复用、版本化变化通知或事务阶段调整仅是后续候选，本轮均未实施。
