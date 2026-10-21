# 隔离整服性能采样方法

工作包 P 按用户变更采用统一内存事务运行时与完整经济检查点，见 [P 设计](../design/VE-Task-P-一致性检查点架构.md)和 [P 验收报告](VE-Task-P-经济Tick性能诊断与优化验收.md)。已保留完整 `tickServer`、Context/子系统/事务阶段及旧架构六场景双轮数据。下文 Task E 的 Pre→Post 数据与逐 Tick 落盘说明均为历史口径；原生 `tickTimesNanos` 在 Post 前结束，不能单独拿来判断 50ms 总预算。

当前推荐在没有并行构建/其他压力任务时执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Measure-Economy.ps1 -Label local01 -Repetitions 2 -SaveInterval 200
node tools/Summarize-Economy.mjs build build/local01-results.json local01
```

每次选择新 Label，不重用或删除旧隔离世界。六场景均 150 Tick 预热、1300 对齐样本，正常 Payroll/Production 不跳过。内存业务事务与磁盘检查点须分别统计；磁盘仍 DELETE/FULL。`SaveInterval=200` 仅为压力测试原版保存入口的参数，正式默认跟随原版自动保存；传 0 不额外触发。GameTest 吞吐没有普通服务器 20 TPS 上限，不能冒充玩家服 TPS。

本次恢复增加 `active32`，仍实际运行所有工资、付款、生产和存档：

```powershell
& ./tools/Measure-Economy.ps1 -Label scale01 -Repetitions 2 -Scenarios @('active16','active32') -SaveInterval 200
node tools/Summarize-Economy.mjs build build/scale01-results.json scale01
```

优先比较交替运行的独立基线副本和当前版本，并观察 empty 对照是否一起漂移。基线副本只能来自 `10c3260` 加 [本次探针补丁](task-p/resume-measurement.patch)，放入新建的 build 子目录，不在正式工作区 reset。实际复测的 51 次运行、一次未完成启动、CPU/SQL/JSON 分阶段计数及不利结果均保留在 [resume-results.json](task-p/resume-results.json)。新阶段没有通过 50ms 严格峰值验收。

阶段 CPU、SQL、JSON 统计均为嵌套包含；save/payroll/settlement Tick 组可重叠。结算 Tick 只有七个，1300 Tick 的 p99 可能完全没有覆盖它们，因此必须单独报告结算分布。SQL 时间只计 execute，不覆盖 prepare/bind/getter/Java 映射；有界探针丢弃的时间样本应标明，不能从精确计数伪造完整耗时。32 栋 Journal 推导使用 32 栋数，不沿用旧四栋分支。

在 PowerShell 内可以只运行部分场景并关闭细分观察器：

```powershell
& ./tools/Measure-Economy.ps1 -Label minimal01 -Repetitions 2 -Scenarios @('active4','active16') -NoDetails
node tools/Summarize-Economy.mjs build build/minimal01-results.json minimal01
```

此时只保留测试源集的完整 Tick 计时；Context/SQL/事务细分为不可用，不能把缺失计数解释为没有数据库业务。所有业务检查照常执行。非 Tick 格式/历史成本可在服务器退出后执行 `./gradlew.bat profileEconomyPreflight -PvePreflightRun=p_local01_history4_1 --console=plain`，只接受已有 build 隔离目录，保留前后主文件/WAL 哈希。

实际原版自动保存及手动 flush 验证：新运行名执行 `./gradlew.bat runGameTestServer -PveGameTestRun=native01 -PveGameTestMode=checkpoint --console=plain`，采样 6200 Tick，不能把单维度 Save 事件当作全局提交完成。

崩溃验证使用新运行名先运行 `veGameTestMode=crash`（明确预期 JVM halt 73、Gradle 失败、`crash-reached.txt` 存在），再在同目录运行 `crashRestart`，核验最近完整快照的整个摘要。普通 `full` 后接同目录 `restart` 则验证正常停服；二者不得混称。不要把故意非零退出当作测试意外通过，也不要跳过重启断言。

若要复现优化前行为，在单独、干净的 `d408087` 源码副本应用 [measurement-only.patch](task-p/measurement-only.patch)，再运行 baseline 标签；不要在当前优化工作区反向套用或重置。该补丁保留最初的库存 read/mutate 粗粒度探针；最终版本额外细分库存操作，前后总耗时比较使用共同口径。

以下为 Task E 历史采样方法（逐 Tick 磁盘实现，现已被替代）。日期：2026-10-21。其 Pre→Post 统计、事务预算与旧版本只用于复核历史，不代表当前架构。原结果见[任务 E 报告](../design/VE-Task-E-M5.1-M5.2-M5.3-建筑生命周期与整体验收.md)。

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
