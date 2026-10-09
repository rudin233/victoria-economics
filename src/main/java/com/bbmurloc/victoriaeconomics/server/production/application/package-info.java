/**
 * 生产运营的应用协调、调度及统计查询。
 * <p>
 * ProductionService 使用既有端口与共享经济写锁协调资源承诺、执行、结算和恢复。
 * EconomicClock 接收逻辑服务器 tick，不根据墙上时间补算。
 * StaffingCalculator 汇总跨上下文事实，返回查询快照；其结果不代替开工前权威校验，
 * 也不代替领域层 WorkforcePlanningService 的具体选人安排。
 */
package com.bbmurloc.victoriaeconomics.server.production.application;

