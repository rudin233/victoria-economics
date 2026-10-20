/**
 * 生产运营的应用协调、调度及统计查询。
 * <p>
 * ProductionService 使用既有端口与共享经济写锁协调资源承诺、执行、结算和恢复。
 * 正式 EconomicClock 每个逻辑服务器 tick 持久接受一次时间，独立推进工资游标；不根据墙上时间补算。
 * 生产进度与每建筑 Tick 标记同事务；崩溃按持久检查点继续资源结算和重配置，不重复推进。
 * StaffingCalculator 查询唯一整数 Staffing、有效 PM 和区域供给；运行 Batch 使用历史冻结快照。
 * ProductionBoundaryCoordinator 在一个既有 WriteSession 中累计旧工资、提交 PM/人数/工资基数并清除屏障。
 */
package com.bbmurloc.victoriaeconomics.server.production.application;
