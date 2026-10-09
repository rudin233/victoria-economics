/**
 * 库存上下文中的设备应用服务和查询索引。
 * <p>
 * ProductionEquipmentService 取得建筑定义，协调共享写锁下的领域修改和持久化，
 * 保存成功后才发布新的设备状态；跨地点调拨保留原子保存。
 * ProductionEquipmentRegistry 仅提供设备聚合查询索引，不是额外的设备权威或聚合根。
 */
package com.bbmurloc.victoriaeconomics.server.inventory.application;

