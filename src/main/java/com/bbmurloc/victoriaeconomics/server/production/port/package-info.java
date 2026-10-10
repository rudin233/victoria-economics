/**
 * 生产应用层所需的出站契约，由消费者 Production Operations 拥有。
 * <p>
 * 库存数量、正式任职与工资状态的真实权威分别在对应上下文，端口不复制其账本。
 * 用工使用生产可理解的只读任职事实；库存只返回容量状态和持久化结算回执。
 * ProductionJournal 保存跨步骤开工意图用于恢复，不作为库存或任职的权威。
 * 两个独立 Repository 使用各自的 revision，拒绝过期覆盖；执行保存包含内部 Batch 的原子提交。
 */
package com.bbmurloc.victoriaeconomics.server.production.port;
