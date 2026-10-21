/**
 * 正式 Staffing 目标命令：空闲时工资截止后原子生效，运行时只保存 Pending。
 * 欠薪裁员先记录不可逆终止并结算，再在重配置事务中变更人数；部分成功消费本次请求，不自动补招。
 * 生命周期操作开始后拒绝新的 Staffing 修改；未决交割不提前释放真实就业占用或停止工资。
 */
package com.bbmurloc.victoriaeconomics.server.workforce.application;
