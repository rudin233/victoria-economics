/**
 * 商品地点与设备持有的现有 SQLite 适配器。
 * <p>
 * SqliteInventoryStore 实现生产消费者的库存端口，并保留地点加载、领域变更、原子保存流程。
 * SqliteEquipmentRepository 保存设备持有与请求结果，保留两地点保存的事务边界。
 * 包结构调整不改变表名、JSON 字段、数量精度或数据库迁移。
 */
package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;

