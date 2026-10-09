/**
 * 生产应用层所需的出站契约，由消费者 Production Operations 拥有。
 * <p>
 * 库存数量、正式任职与工资状态的真实权威分别在对应上下文，端口不复制其账本。
 * ProductionJournal 保存跨步骤开工意图用于恢复；现有状态 DTO 类型耦合本轮暂时保留。
 */
package com.bbmurloc.victoriaeconomics.server.production.port;

