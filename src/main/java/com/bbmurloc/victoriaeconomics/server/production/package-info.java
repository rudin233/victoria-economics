/**
 * Production Operations（生产运营）限界上下文。
 * <p>
 * domain 保护生产方案、批次承诺和生命周期；application 协调开工、结算及重试；
 * port 声明所需的库存、用工、工资与恢复契约；infrastructure 放置现有持久化适配器。
 * 建筑身份及其现有组合持久化边界本轮仍留在 building 与 storage 包。
 */
package com.bbmurloc.victoriaeconomics.server.production;

