/**
 * 生产出站契约的现有持久化适配器。
 * <p>
 * SqliteProductionJournal 使用现有意图与批次表；数据库连接和 schema 由共享基础设施管理。
 * 建筑与生产状态的组合 checkpoint 仍由 storage.sqlite 中的建筑 Repository 和 codec 负责。
 */
package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

