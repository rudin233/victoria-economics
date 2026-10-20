/**
 * 独立整数 Staffing 与区域总供给；当前阶段只用于独立持久化和领域规划。
 * Pending 不是实际雇佣；占用/可用供给从持久化 employed 派生，不在领域根中复制。
 * 实际人数生效必须先完成真实工资应计；生产仍使用旧 Employment，直到 M3.1 切换。
 */
package com.bbmurloc.victoriaeconomics.server.workforce.domain;
