/**
 * 生产运营的领域对象和无状态领域计算。
 * <p>
 * {@link com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodConfiguration}
 * 与 {@link com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution}
 * 分别保护生产方案和批次执行；ProductionBatch 是执行聚合内部的只读 Entity 快照。
 * RecipeResolver 与 WorkforcePlanningService 解析配方及参产安排，不执行数据库或 Minecraft 操作。
 * 参产仍使用真实 NPC UUID 与资格事实；速度仅由各职业参产比例决定，批次快照不可变。
 * 两个聚合均以 buildingId 定位，拥有独立版本与 Repository；建筑不持有其可变状态。
 */
package com.bbmurloc.victoriaeconomics.server.production.domain;
