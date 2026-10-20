/**
 * 整数商品库存的领域权威。
 * <p>
 * GoodsInventory 是单地点商品聚合根，保护可用量、排他预留、容量及幂等结算回执。
 * 完成结算全量扣料产出，中止结算全额释放预留且零产出；同 BatchId 的命令必须匹配。
 * 领域对象不负责 SQL、建筑身份创建或生产应用步骤调度。
 */
package com.bbmurloc.victoriaeconomics.server.inventory.domain;
