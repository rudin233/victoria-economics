/**
 * 独立生产 Repository。建筑生命周期意图由 building 独立保存。
 * <p>
 * Configuration 与 Execution 分别保存；Execution 和内部 Batch 在同一事务提交。
 * Batch 表是唯一批次记录，未知开工结果须在可信快照核验批次及关联业务状态。
 * Batch 保存整数参产快照；Execution 的 Tick 检查点和暂停原因与进度原子保存，不缓存用工或工资副本。
 */
package com.bbmurloc.victoriaeconomics.server.production.infrastructure;
