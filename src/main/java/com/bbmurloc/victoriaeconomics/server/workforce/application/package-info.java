/**
 * 当前只提供独立 Staffing 的 Pending 命令；没有真实工资应计及持久时间时，生效命令明确拒绝。
 * 不调用旧 NPC 人事写入，不驱动生产 tick，不自动补招。
 */
package com.bbmurloc.victoriaeconomics.server.workforce.application;
