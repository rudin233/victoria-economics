/**
 * 正式独立整数 Staffing 与区域总供给。
 * Pending 不是实际雇佣；占用/可用供给从持久化 employed 派生，不在领域根中复制。
 * 实际人数生效前先累计旧工资，并在同一物理事务更新工资基数；Batch 快照不受 Pending 影响。
 */
package com.bbmurloc.victoriaeconomics.server.workforce.domain;
