/**
 * 独立生产 Repository 与恢复日志。建筑生命周期意图由 building 独立保存。
 * <p>
 * Configuration 与 Execution 分别保存；Execution 和内部 Batch 在同一事务提交。
 * Batch 表是唯一可变批次记录，Journal 据此核实提交；未知事务状态拒绝提供权威查询结果。
 * Batch 保存整数参产快照；Execution 的 Tick 检查点和暂停原因与进度原子保存，不缓存用工或工资副本。
 */
package com.bbmurloc.victoriaeconomics.server.production.infrastructure;
