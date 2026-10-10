/**
 * 独立生产 Repository、恢复日志及生命周期事实适配器。
 * <p>
 * Configuration 与 Execution 分别保存；Execution 和内部 Batch 在同一事务提交。
 * Batch 表是唯一可变批次记录，Journal 据此核实提交；未知事务状态拒绝提供权威查询结果。
 * RepositoryProductionFacts 向库存与用工提供必要的容量和批次保护事实，不缓存第二份生产权威。
 */
package com.bbmurloc.victoriaeconomics.server.production.infrastructure;
