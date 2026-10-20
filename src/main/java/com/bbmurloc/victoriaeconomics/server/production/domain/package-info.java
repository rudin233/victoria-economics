/**
 * 生产运营的领域对象和无状态领域计算。
 * <p>
 * {@link com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodConfiguration}
 * 与 {@link com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution}
 * 分别保护生产方案和批次执行；ProductionBatch 是执行聚合内部的只读 Entity 快照。
 * RecipeResolver 与 WorkforcePlanningService 解析配方及参产安排，不执行数据库或 Minecraft 操作。
 * 参产使用冻结的职业整数人数，速度是精确瓶颈比例；不持有实际 Staffing 或工资的第二份权威。
 * Execution 的 RECONFIGURING 屏障、多暂停原因和每建筑 Tick 检查点均显式持久化。
 * 两个聚合均以 buildingId 定位，拥有独立版本与 Repository；建筑不持有其可变状态。
 */
package com.bbmurloc.victoriaeconomics.server.production.domain;
