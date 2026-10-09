/**
 * 库存与设备持有的领域权威。
 * <p>
 * GoodsInventory 是单地点商品聚合根，保护可用量、排他预留、容量及幂等结算回执。
 * ProductionEquipmentHolding 是独立设备聚合根，保护已安装、未安装、总容量和请求锁定。
 * EquipmentConfigurationRequest 是设备聚合内不可变请求快照，保留受理顺序与执行结果。
 * 这些对象不负责 SQL、建筑身份创建或生产应用步骤调度。
 */
package com.bbmurloc.victoriaeconomics.server.inventory.domain;

