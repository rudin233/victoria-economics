/**
 * Inventory & Storage（库存与存储）限界上下文。
 * <p>
 * domain 分别保护单地点商品库存和设备持有；application 协调设备请求与索引发布；
 * infrastructure 提供现有 SQLite 保存与加载。商品与设备使用独立聚合及容量体系。
 */
package com.bbmurloc.victoriaeconomics.server.inventory;

