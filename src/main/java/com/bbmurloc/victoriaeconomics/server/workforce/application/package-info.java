/**
 * 正式 Staffing 目标命令：空闲时工资截止后原子生效，运行时只保存 Pending。
 * 欠薪裁员先记录不可逆终止并结算，再在重配置事务中变更人数；部分成功消费本次请求，不自动补招。
 */
package com.bbmurloc.victoriaeconomics.server.workforce.application;
