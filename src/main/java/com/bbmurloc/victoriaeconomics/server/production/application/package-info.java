/**
 * 生产运营的应用协调、调度及统计查询。
 * <p>
 * ProductionService 使用既有端口与共享经济写锁协调资源承诺、执行、结算和恢复。
 * EconomicClock 接收逻辑服务器 tick，不根据墙上时间补算。
 * StaffingCalculator 查询独立整数 Staffing、有效 PM 和区域供给；本阶段不成为生产用工权威。
 * 生产开工继续由 WorkforcePort 的旧 NPC 任职/资格事实与 WorkforcePlanningService 校验。
 */
package com.bbmurloc.victoriaeconomics.server.production.application;
