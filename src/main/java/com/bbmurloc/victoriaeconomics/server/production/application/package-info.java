/**
 * 生产运营的应用协调、调度及统计查询。
 * <p>
 * ProductionService 使用既有端口与共享经济写锁协调资源承诺、执行、结算和恢复。
 * 正式 EconomicClock 每个逻辑服务器 tick 持久接受一次时间，独立推进工资游标；不根据墙上时间补算。
 * 生产回调确认与时间接受分开；崩溃留下的不确定生产 Tick 不自动重放，等待后续原子整合。
 * StaffingCalculator 查询独立整数 Staffing、有效 PM 和区域供给；本阶段不成为生产用工权威。
 * 生产开工继续由 WorkforcePort 的旧 NPC 任职/资格事实与 WorkforcePlanningService 校验。
 */
package com.bbmurloc.victoriaeconomics.server.production.application;
