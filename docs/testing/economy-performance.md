# 隔离整服性能采样方法

日期：2026-10-21。测量实际 NeoForge ServerTickEvent.Pre → Post 耗时，不使用墙钟推进经济状态。真实结果见[任务 E 报告](../design/VE-Task-E-M5.1-M5.2-M5.3-建筑生命周期与整体验收.md)。

1. 完成 `test build`，结束其他构建、游戏或压力任务。记录 JDK、操作系统、CPU、磁盘、是否有后台负载。
2. 为每次采样选一个尚不存在的简单运行名，例如 `bench_01`。确认 `build/task-e-gametest-bench_01` 不存在；存在时换名，不删除已有世界。
3. 执行 `./gradlew.bat runGameTestServer -PveGameTestRun=bench_01 --console=plain`。不修改同步级别、journal_mode 或 busy timeout 来美化数据。
4. 保留 Gradle 输出、该隔离目录下 logs、world/victoria_economics/economy.sqlite 和 `acceptance-performance.json`。GameTest 验证失败时，该次不能算通过验收的性能样本。
5. JSON 给出真实 Tick 样本数、acceptedTick、平均、p50、p95、最大耗时及 SQLite 实际设置。观察 `Can't keep up`；50ms 是 20TPS 的总 Tick 时间预算，不是本系统可以独占的预算。

需要进程级重启验证时，在上述运行退出后执行同一运行名并加 `-PveGameTestMode=restart`。它读取已有世界，在正式 Context 初始化前后核对时间、所有 Batch、现金、应计、余数、债务、操作状态和库存，再确认时间增量只来自新进程实际逻辑回调。它不重新运行注资夹具；该短重启测试不作为性能采样。

当前场景：四栋建筑，两个生命周期 BLOCKED 但持续计薪，一个满速连续批次，一个慢速欠薪后真实付款恢复；三个显式公司/地区/币种；两次经济分钟付款边界；上下文重启及远处锚点区块卸载。只有 src/gameTest 的可核验资金夹具注资，正式代码没有发行入口。

每个正常接受 Tick 至少包括四次全球写事务（接受、应计/到期化、整轮生产核验、幂等回调确认），以及每栋现存建筑两次写事务（推进决定/processing、完成检查点）。无 Batch 的普通重配置、Batch 完工/启动、生命周期阶段、工资周期意图/付款/确认还会增加事务。普通一致快照读取也有 DEFERRED 事务的开始/结束成本；不能把物理 commit 总次数全部解释为写事务。

当前实现保留上述持久恢复边界，没有为基准测试合并事务、缓存工资游标或降低 SQLite durability。该小型场景不是大世界容量承诺。后续容量测试应在同样隔离目录下扩大建筑数、工资段和付款历史长度，分别报告稳定运行和恢复/付款尖峰；必须先有安全的显式测试数据输入，并继续核对账本、供给、Batch 与 Tick 不变量。

不包含真实玩家网络、多客户端、模组组合、慢盘/断电、长时间 GC 或大规模分区负载。进程内 Context 重启与完整服务端进程重启应分别报告；不得把前者称为后者。

测试源集及运行配置依据 [NeoForge 1.21.1 GameTest 文档](https://docs.neoforged.net/docs/1.21.1/misc/gametest/) 和 [ModDevGradle 官方说明](https://github.com/neoforged/ModDevGradle)。实际结果以本地 NeoForge 21.1.248 / ModDevGradle 2.0.146 的编译与运行日志为准。
