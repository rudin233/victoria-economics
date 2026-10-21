package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import java.sql.*;
import java.util.*;

/** Read-only v9 graph validation. A blocked operation is never proof of an ownership transfer. */
final class EconomyLifecycleValidation {
    private EconomyLifecycleValidation() {}
    static void validate(Connection c,SqliteTransactions.ReadSession snapshot) throws SQLException {
        long accepted=com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository.inSnapshot(c,snapshot).acceptedTick();
        var executions=new SqliteProductionExecutionRepository(c,new Object());
        try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM building_lifecycle_operations")) {
            while(rows.next()) {
                var op=SqliteBuildingLifecycleOperationRepository.decode(rows);var cmd=op.command();
                require(op.startedTick()<=accepted&&(op.blockedTick()==null||op.blockedTick()<=accepted));
                try(var q=c.prepareStatement("SELECT b.*,p.company_id AS payroll_company,p.building_id AS payroll_building,p.region_id AS payroll_region,p.start_tick AS payroll_start,p.closed_tick FROM economic_buildings b JOIN payroll_accruals p ON p.id=? WHERE b.id=?")) {
                    q.setString(1,op.payrollSegment().toString());q.setString(2,cmd.buildingId().toString());
                    try(var r=q.executeQuery()) {
                        require(r.next()&&r.getString("lifecycle").equals("EXISTING")&&r.getString("owner_company_id").equals(cmd.seller().toString())&&r.getLong("identity_revision")==cmd.expectedIdentityRevision()
                                &&r.getString("payroll_company").equals(cmd.seller().toString())&&r.getString("payroll_building").equals(cmd.buildingId().toString())&&r.getString("payroll_region").equals(r.getString("region_id"))&&r.getLong("payroll_start")<=op.startedTick()&&r.getObject("closed_tick")==null);
                    }
                }
                var e=executions.inSnapshot(snapshot,cmd.buildingId());require(!e.automatic());
                if(op.batchId()==null)require(e.batch()==null&&e.boundary()==ProductionExecution.Boundary.NONE);
                else {
                    var batch=e.batch()!=null?e.batch():e.lastBatch();
                    require(batch!=null&&batch.getId().equals(op.batchId()));
                    boolean completed=op.settlement()==ProductionSettlement.Outcome.COMPLETED;
                    require(completed?(batch.getStatus()==ProductionBatch.Status.SETTLING_COMPLETED||batch.getStatus()==ProductionBatch.Status.COMPLETED):(batch.getStatus()==ProductionBatch.Status.SETTLING_ABORTED||batch.getStatus()==ProductionBatch.Status.ABORTED));
                    if(op.status()==Status.BLOCKED) {
                        require(batch.isEnded());
                        try(var q=c.prepareStatement("SELECT state_json FROM inventory_locations WHERE location_id=?")) {
                            q.setString(1,cmd.buildingId().toString());try(var r=q.executeQuery()) {
                                require(r.next());var state=EconomyStateCodec.decodeInventory(cmd.buildingId(),r.getString(1)).state();
                                var receipt=state.settlements().get(batch.getId());
                                require(receipt!=null&&receipt.command().equals(new ProductionSettlement(batch.getId(),op.settlement(),batch.getConfiguration().resolvedRecipe().inputs(),batch.getConfiguration().resolvedRecipe().outputs())));
                            }
                        }
                    }
                }
                if(op.status()==Status.BLOCKED) {
                    require(e.batch()==null&&e.boundary()==ProductionExecution.Boundary.NONE&&op.blockers().contains(Blocker.WAGE_REMAINDER_POLICY_UNDECIDED));
                    if(cmd.kind()==Kind.SALE)require(op.blockers().containsAll(Set.of(Blocker.SALE_CONSIDERATION_UNDECIDED,Blocker.INVENTORY_DISPOSITION_UNDECIDED)));
                    try(var q=c.prepareStatement("SELECT 1 FROM production_start_intents WHERE building_id=?")) {
                        q.setString(1,cmd.buildingId().toString());try(var r=q.executeQuery()){require(!r.next());}
                    }
                }
            }
        }
        // Historical identity has no active enrollment or runtime employees. Historical roots and debts remain.
        try(var s=c.createStatement();var r=s.executeQuery("SELECT b.id FROM economic_buildings b JOIN production_executions e ON e.building_id=b.id WHERE b.lifecycle='DEMOLISHED' AND (e.current_batch_id IS NOT NULL OR e.automatic<>0 OR e.boundary<>'NONE' OR EXISTS(SELECT 1 FROM building_staffing_roles x WHERE x.building_id=b.id AND (x.employed<>0 OR x.pending_target IS NOT NULL)) OR EXISTS(SELECT 1 FROM payroll_accruals p WHERE p.building_id=b.id AND p.closed_tick IS NULL) OR NOT EXISTS(SELECT 1 FROM payroll_accruals p WHERE p.building_id=b.id) OR EXISTS(SELECT 1 FROM production_start_intents x WHERE x.building_id=b.id))")) {require(!r.next());}
        try(var s=c.createStatement();var r=s.executeQuery("SELECT b.id,i.state_json FROM economic_buildings b JOIN inventory_locations i ON i.location_id=b.id WHERE b.lifecycle='DEMOLISHED'")) {
            while(r.next()) {
                var state=EconomyStateCodec.decodeInventory(UUID.fromString(r.getString(1)),r.getString(2)).state();
                require(state.reservations().isEmpty()&&state.quantities().values().stream().noneMatch(n->n>0));
            }
        }
    }
    private static void require(boolean condition) {if(!condition)throw new IllegalStateException("Inconsistent v9 lifecycle intent, settlement or historical building; no repair attempted");}
}
