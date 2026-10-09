/**
 * 生产运营的领域对象和无状态领域计算。
 * <p>
 * {@link com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodConfiguration}
 * 与 {@link com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution}
 * 分别保护生产方案和批次执行；ProductionBatch 是执行聚合内部的只读 Entity 快照。
 * RecipeResolver 与 WorkforcePlanningService 解析配方及参产安排，不执行数据库或 Minecraft 操作。
 * 当前两个聚合仍由建筑内 ProductionDepartment 持有；包迁移不改变这一组合关系。
 */
package com.bbmurloc.victoriaeconomics.server.production.domain;

