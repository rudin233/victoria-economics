/**
 * Production Operations（生产运营）限界上下文。
 * <p>
 * domain 保护生产方案、批次承诺和生命周期；application 协调开工、结算及重试；
 * port 声明所需的库存、用工、工资与恢复契约；infrastructure 放置现有持久化适配器。
 * 两个生产聚合独立持久化；建筑身份由 building 管理，共享 SQLite 生命周期位于 storage。
 */
package com.bbmurloc.victoriaeconomics.server.production;
