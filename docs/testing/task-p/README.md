# 工作包 P 证据

- [results.json](results.json)：40 次独立服务器运行的原始 JSON 汇总及派生指标。baseline / optimized 是旧逐操作落盘架构的前后各 12 次；checkpoint 是一致性检查点架构的 12 次；calmem 是关闭细分探针的 4 次校准。每次 1300 个完整 Tick，不是原始逐 Tick trace。
- [evidence.json](evidence.json)：最终 JUnit、正式 jar 隔离检查、原版保存、故障、重启、崩溃恢复、加载计时和外部源码依据。
- [measurement-only.patch](measurement-only.patch)：给干净 d408087 源码副本添加最初测量入口，复核旧架构基线；不要反向应用到当前工作区。

完整方法、统计限制、源文件清单和未通过项目见 [P 验收报告](../VE-Task-P-经济Tick性能诊断与优化验收.md)。SQLite 文件及服务器日志留在 build 隔离目录，不属于文档发布内容。所有资金和身份均是明确隔离的测试事实。

暂停后恢复（基线 `10c3260`）：

- [resume-results.json](resume-results.json)：51 次新运行原始汇总，含六场景和 active32 前后双轮、交替配对、关闭探针校准及一次中间候选。每次 1300 Tick；一项启动中断未计入。
- [resume-evidence.json](resume-evidence.json)：688 项 JUnit、完整构建、查询计划、正式 jar、NeoForge 保存/失败/重启/崩溃恢复及文件保护。
- [resume-measurement.patch](resume-measurement.patch)：在独立干净 `10c3260` 副本添加本次优化前探针与 active32 入口。不要反向应用到当前工作区。配对参考使用 `build` 内 git archive 副本；未 reset/切换当前分支。

**严格性能验收未通过。** 有确定的 SQL/JSON/事务重复减少，但环境漂移明显，部分配对结果回退，不能用名义均值降幅或最好轮宣称稳定 20 TPS。原 40 次阶段数据保持不变；新结果和失败/未测项见报告第 12 节。
