/**
 * 整数商品地点的 SQLite 适配器。
 * <p>
 * SqliteInventoryStore 实现生产消费者的库存端口，并保留地点加载、领域变更、原子保存流程。
 * 修改前获得写权限并重新加载权威库存，使用地点 revision CAS 保存。
 * Schema / Codec 门禁拒绝不兼容快照，不自动迁移历史数据库。
 */
package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;
