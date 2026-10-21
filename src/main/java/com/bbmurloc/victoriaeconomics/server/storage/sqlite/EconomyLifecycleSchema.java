package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.util.List;

/** v9 records safe quiescence, not an unapproved asset transfer or fractional-wage closeout. */
final class EconomyLifecycleSchema {
    private EconomyLifecycleSchema() {}
    static final List<String> DDL = List.of(
        "CREATE TABLE building_lifecycle_operations(id TEXT PRIMARY KEY NOT NULL, building_id TEXT NOT NULL UNIQUE REFERENCES economic_buildings(id), seller_company_id TEXT NOT NULL REFERENCES companies(id), buyer_company_id TEXT REFERENCES companies(id), kind TEXT NOT NULL CHECK(kind IN ('SALE','DEMOLITION')), expected_identity_revision INTEGER NOT NULL CHECK(typeof(expected_identity_revision)='integer' AND expected_identity_revision>=0), started_tick INTEGER NOT NULL CHECK(typeof(started_tick)='integer' AND started_tick>=0), payroll_segment_id TEXT NOT NULL REFERENCES payroll_accruals(id), batch_id TEXT, settlement_outcome TEXT CHECK(settlement_outcome IN ('COMPLETED','ABORTED')), status TEXT NOT NULL CHECK(status IN ('QUIESCING','BLOCKED')), blocked_tick INTEGER CHECK(blocked_tick IS NULL OR (typeof(blocked_tick)='integer' AND blocked_tick>=started_tick)), blockers TEXT NOT NULL, revision INTEGER NOT NULL CHECK(typeof(revision)='integer'), CHECK((kind='SALE' AND buyer_company_id IS NOT NULL AND buyer_company_id<>seller_company_id) OR (kind='DEMOLITION' AND buyer_company_id IS NULL)), CHECK((batch_id IS NULL AND settlement_outcome IS NULL) OR (batch_id IS NOT NULL AND settlement_outcome IS NOT NULL)), CHECK((status='QUIESCING' AND revision=0 AND blocked_tick IS NULL AND blockers='') OR (status='BLOCKED' AND revision=1 AND blocked_tick IS NOT NULL AND length(blockers)>0)), FOREIGN KEY(batch_id,building_id) REFERENCES production_batches(batch_id,building_id))"
    );
}
