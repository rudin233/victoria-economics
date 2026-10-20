/**
 * Inventory & Storage（库存与存储）限界上下文。
 * <p>
 * domain 保护单地点整数商品库存、容量、排他预留及完整结算回执；
 * infrastructure 通过受控 SQLite 写事务与地点 revision CAS 保存权威状态。
 */
package com.bbmurloc.victoriaeconomics.server.inventory;
