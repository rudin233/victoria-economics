package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.*;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Small cross-root coordinator using the existing physical WriteSession; no nested transaction manager. */
public final class ProductionBoundaryCoordinator {
    private final Connection connection;
    private final Object lock;
    private final SqliteBuildingRepository buildings;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteProductionExecutionRepository executions;
    private final SqliteBuildingStaffingRepository staffing;
    private final SqliteRegionalLaborSupplyRepository supply;
    private final SqlitePayrollRepository payroll;
    private final SqliteBuildingPayrollQuery wages;
    private final SqliteInventoryStore inventory;
    private final OccupationRegistry occupations;
    public ProductionBoundaryCoordinator(Connection c,ProductionRecipeResolver recipes,OccupationRegistry occupations,Object lock){
        connection=c;this.lock=lock;this.occupations=occupations;
        buildings=new SqliteBuildingRepository(c,lock);methods=new SqliteProductionMethodConfigurationRepository(c,recipes,lock);
        executions=new SqliteProductionExecutionRepository(c,lock);staffing=new SqliteBuildingStaffingRepository(c,recipes,occupations,lock);
        supply=new SqliteRegionalLaborSupplyRepository(c,occupations,lock);payroll=new SqlitePayrollRepository(c,lock);
        wages=new SqliteBuildingPayrollQuery(c,lock);inventory=new SqliteInventoryStore(c,lock);
    }
    public ProductionExecution execution(UUID id){return executions.load(id);}
    public ProductionMethodConfiguration methods(UUID id){return methods.load(id);}
    public long acceptedTick(){return new SqliteEconomicTimeRepository(connection,lock).load().acceptedTick();}
    public List<UUID> buildingIds(){synchronized(lock){return buildings.loadAll().stream().map(EconomicBuilding::getId).toList();}}
    private <T>T write(SqliteTransactions.WriteWork<T> work){synchronized(lock){
        try{return SqliteTransactions.write(connection,work);}
        catch(CommitOutcomeUnknownException unknown){SqliteConnectionGuard.quarantine(connection,unknown);throw unknown;}
    }}
    private EconomicBuilding live(SqliteTransactions.WriteSession tx,UUID id){
        var b=buildings.inTransaction(tx).load(id);if(b.lifecycle()!=BuildingLifecycle.EXISTING)throw new IllegalStateException("Historical building cannot operate");return b;
    }
    private void noUnresolvedStart(SqliteTransactions.WriteSession tx,UUID id)throws SQLException{
        tx.requireConnection(connection);try(var s=connection.prepareStatement("SELECT 1 FROM production_start_intents WHERE building_id=?")){
            s.setString(1,id.toString());try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("Verify and recover StartIntent before reconfiguration");}
        }
    }
    public void changeMethods(UUID id,BiConsumer<ProductionMethodConfiguration,Boolean> change){write(tx->{
        live(tx,id);noUnresolvedStart(tx,id);var e=executions.inTransaction(tx).load(id);var m=methods.inTransaction(tx).load(id);long revision=m.configurationRevision();
        change.accept(m,e.blocksConfiguration());
        if(m.configurationRevision()==revision)return null;
        if(e.blocksConfiguration())methods.inTransaction(tx).save(m,revision);
        else reconfigure(tx,id,e,m,revision,staffing.inTransaction(tx).load(id));
        return null;
    });}
    /** Returns true only when a durable arrears-dismissal abort decision now needs resource settlement. */
    public boolean requestStaffing(UUID id,String role,Integer target){return write(tx->{
        var b=live(tx,id);noUnresolvedStart(tx,id);if(occupations.get(role)==null)throw new IllegalArgumentException("Unknown occupation");
        var repo=staffing.inTransaction(tx);var old=repo.load(id);var m=methods.inTransaction(tx).load(id);var e=executions.inTransaction(tx).load(id);
        var requested=target==null?old.cancel(role):old.requestTarget(role,target,m.recipe().requiredWorkers());
        if(requested==old)return false;
        if(target!=null&&!e.blocksConfiguration()){
            reconfigure(tx,id,e,m,m.configurationRevision(),requested);return false;
        }
        repo.save(requested,old.revision());
        if(target!=null&&target<old.employed().getOrDefault(role,0)&&e.hasUnfinishedBatch()
                &&wages.inTransaction(tx,id,b.ownerCompanyId())==State.ARREARS){
            e.terminate();executions.inTransaction(tx).save(e,e.executionRevision());return true;
        }
        return false;
    });}
    public void reconfigure(UUID id){write(tx->{
        var e=executions.inTransaction(tx).load(id);if(e.hasUnfinishedBatch())throw new IllegalStateException("Running Batch defers staffing and PM changes");
        var m=methods.inTransaction(tx).load(id);reconfigure(tx,id,e,m,m.configurationRevision(),staffing.inTransaction(tx).load(id));return null;
    });}
    private void reconfigure(SqliteTransactions.WriteSession tx,UUID id,ProductionExecution e,ProductionMethodConfiguration m,long pmRevision,BuildingStaffing requested)throws SQLException{
        var b=live(tx,id);noUnresolvedStart(tx,id);if(e.hasUnfinishedBatch())throw new IllegalStateException("Previous Batch unfinished");
        long tick=SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick();
        var wagesRepo=payroll.inTransaction(tx);var segment=wagesRepo.currentForBuilding(id);
        if(!wagesRepo.currencyForRegion(b.regionId()).equals(segment.currency()))throw new IllegalStateException("Regional wage currency mismatch");
        wagesRepo.accrue(segment,tick);
        var staffRepo=staffing.inTransaction(tx);var before=staffRepo.load(id);
        m.applyPending();var capacity=m.recipe().requiredWorkers();
        var capped=StaffingTransition.forCapacity(requested,capacity).after();var available=new TreeMap<String,Long>();
        var regional=supply.inTransaction(tx);
        for(String role:capacity.keySet()){
            long total=regional.find(b.regionId(),role).map(RegionalLaborSupply::totalSupply).orElse(0L);
            long free=Math.subtractExact(total,regional.occupied(b.regionId(),role));
            free=Math.addExact(free,Math.subtractExact(before.employed().getOrDefault(role,0),capped.employed().getOrDefault(role,0)));
            available.put(role,free);
        }
        var planned=StaffingTransition.consumePending(requested,capacity,available).after();
        if(m.configurationRevision()!=pmRevision)methods.inTransaction(tx).save(m,pmRevision);
        if(!before.employed().equals(planned.employed())||!before.pending().equals(planned.pending())){
            var changed=new BuildingStaffing(id,planned.employed(),planned.pending(),Math.incrementExact(before.revision()));
            staffRepo.save(changed,before.revision());
            long count=0;for(int n:changed.employed().values())count=Math.addExact(count,n);
            if(count!=segment.employees())wagesRepo.changeEmployees(segment.id(),count,tick);
        }
        if(e.boundary()==ProductionExecution.Boundary.RECONFIGURING){e.reconfigurationCompleted();executions.inTransaction(tx).save(e,e.executionRevision());}
    }
    public record StartPlan(ProductionBatchConfiguration configuration,long executionRevision){}
    public StartPlan prepare(UUID id){return write(tx->new StartPlan(prepare(tx,id,null),executions.inTransaction(tx).load(id).executionRevision()));}
    private ProductionBatchConfiguration prepare(SqliteTransactions.WriteSession tx,UUID id,UUID reservedBatch)throws SQLException{
        var b=live(tx,id);if(b.getStatus()!=BuildingStatus.ACTIVE)throw new ProductionStartBlocked("Building stopped");
        var e=executions.inTransaction(tx).load(id);if(e.batch()!=null||e.boundary()!=ProductionExecution.Boundary.NONE)throw new ProductionStartBlocked("Previous Batch or reconfiguration unfinished");
        if(wages.inTransaction(tx,id,b.ownerCompanyId())!=State.CURRENT)throw new ProductionStartBlocked("Payroll is not CURRENT or is unavailable");
        if(!inventory.configured(tx,id))throw new ProductionStartBlocked("Storage location has not been configured");
        if(inventory.inTransaction(tx).storageStatus(id).overCapacity())throw new ProductionStartBlocked("Storage is over capacity");
        var m=methods.inTransaction(tx).load(id);if(m.pending().isPresent())throw new IllegalStateException("Pending PM not applied");
        var s=staffing.inTransaction(tx).load(id);WorkforcePlan plan;
        try{plan=new WorkforcePlanningService().plan(m.recipe().requiredWorkers(),s.employed());}catch(IllegalStateException denied){throw new ProductionStartBlocked(denied.getMessage());}
        if(reservedBatch==null&&!inventory.inputsAvailable(tx,id,m.recipe().inputs()))throw new ProductionStartBlocked("Missing batch input");
        return new ProductionBatchConfiguration(m.effective().methods(),m.recipe(),plan,m.effectiveRevision());
    }
    public void commitStart(UUID id,UUID batch,StartPlan expected,boolean automaticRequest){write(tx->{
        var current=prepare(tx,id,batch);if(!current.equals(expected.configuration()))throw new IllegalStateException("Production conditions changed before commit");
        var e=executions.inTransaction(tx).load(id);if(e.executionRevision()!=expected.executionRevision())throw new IllegalStateException("Execution changed before final start");
        if(automaticRequest&&!e.automatic())throw new IllegalStateException("Automatic production disabled before commit");
        try(var s=connection.prepareStatement("SELECT batch_id FROM production_start_intents WHERE building_id=?")){
            s.setString(1,id.toString());try(var r=s.executeQuery()){if(!r.next()||!batch.toString().equals(r.getString(1)))throw new IllegalStateException("StartIntent does not match Batch");}
        }
        if(!inventory.inTransaction(tx).isReserved(id,batch,current.resolvedRecipe().inputs()))throw new IllegalStateException("Complete input Reservation required");
        // A manual start can race the callback after this Tick was accepted. Persist that the
        // advance decision saw no old Batch, together with the new Batch's zero progress.
        long tick=SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick();
        if(e.processingTick()==null&&e.lastCompletedTick()<tick)e.beginTick(tick);
        e.start(batch,current);if(!automaticRequest)e.requestAutomatic(true);executions.inTransaction(tx).save(e,e.executionRevision());return null;
    });}
    /** Progress, reasons and the durable replay marker commit together. */
    public boolean advance(UUID id,long tick){return write(tx->{
        if(tick!=SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick())throw new IllegalArgumentException("Only accepted economic Tick may advance production");
        var b=live(tx,id);var e=executions.inTransaction(tx).load(id);
        if(e.processingTick()==null&&e.lastCompletedTick()>=tick)return false;
        if(e.beginTick(tick)){
            State state=wages.inTransaction(tx,id,b.ownerCompanyId());
            for(var reason:List.of(ProductionExecution.PauseReason.PAYROLL_ARREARS,ProductionExecution.PauseReason.PAYROLL_RECOVERY,ProductionExecution.PauseReason.PAYROLL_UNAVAILABLE))e.resume(reason);
            switch(state){
                case ARREARS->e.pause(ProductionExecution.PauseReason.PAYROLL_ARREARS);
                case RECOVERY->e.pause(ProductionExecution.PauseReason.PAYROLL_RECOVERY);
                case UNAVAILABLE->e.pause(ProductionExecution.PauseReason.PAYROLL_UNAVAILABLE);
                case CURRENT->{ }
            }
            e.tick(1);executions.inTransaction(tx).save(e,e.executionRevision());
        }
        return true;
    });}
    public void finishTick(UUID id,long tick){write(tx->{var e=executions.inTransaction(tx).load(id);if(e.lastCompletedTick()==tick&&e.processingTick()==null)return null;e.finishTick(tick);executions.inTransaction(tx).save(e,e.executionRevision());return null;});}
    public void confirmSettlement(UUID id,UUID batch){write(tx->{var e=executions.inTransaction(tx).load(id);if(e.batch()==null||!e.batch().getId().equals(batch))throw new IllegalStateException("Foreign settlement receipt");if(!e.batch().isEnded()){e.settlementCompleted(batch);executions.inTransaction(tx).save(e,e.executionRevision());}return null;});}
    public void abort(UUID id){write(tx->{live(tx,id);var e=executions.inTransaction(tx).load(id);e.terminate();executions.inTransaction(tx).save(e,e.executionRevision());return null;});}
    public void automatic(UUID id,boolean enabled){write(tx->{live(tx,id);var e=executions.inTransaction(tx).load(id);e.requestAutomatic(enabled);executions.inTransaction(tx).save(e,e.executionRevision());return null;});}
    public void manualPause(UUID id,boolean pause){write(tx->{var e=executions.inTransaction(tx).load(id);if(pause)e.pause();else e.resume();executions.inTransaction(tx).save(e,e.executionRevision());return null;});}
    public void confirmWholeTick(long tick){write(tx->{
        var time=SqliteEconomicTimeRepository.inTransaction(connection,tx);if(time.acceptedTick()!=tick||!time.payrollCaughtUp())throw new IllegalStateException("Time/payroll not ready");
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT 1 FROM production_executions e LEFT JOIN production_batches b ON b.batch_id=e.current_batch_id WHERE e.last_completed_tick<>"+tick+" OR e.processing_tick IS NOT NULL OR e.boundary<>'NONE' OR b.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED')")){if(r.next())throw new IllegalStateException("Unfinished per-building production Tick");}
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT 1 FROM production_start_intents")){if(r.next())throw new IllegalStateException("Unresolved StartIntent");}
        if(time.firstUncertainProductionTick()!=null||time.productionCallbackReturnedTick()!=tick)
            try(var s=connection.prepareStatement("UPDATE economic_time SET first_uncertain_production_tick=NULL,production_callback_returned_tick=?,revision=? WHERE id=1 AND accepted_tick=? AND revision=?")){
                s.setLong(1,tick);s.setLong(2,Math.incrementExact(time.revision()));s.setLong(3,tick);s.setLong(4,time.revision());if(s.executeUpdate()!=1)throw new IllegalStateException("Economic time changed");
            }
        return null;
    });}
    public boolean needsRecovery(){synchronized(lock){return SqliteTransactions.read(connection,tx->{
        var time=SqliteEconomicTimeRepository.inSnapshot(connection,tx);if(time.hasUnconfirmedProductionCallback())return true;
        try(var s=connection.prepareStatement("SELECT 1 FROM production_executions e LEFT JOIN production_batches b ON b.batch_id=e.current_batch_id WHERE e.processing_tick IS NOT NULL OR e.last_completed_tick<? OR e.boundary<>'NONE' OR b.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED') UNION ALL SELECT 1 FROM production_start_intents")){
            s.setLong(1,time.acceptedTick());try(var r=s.executeQuery()){return r.next();}
        }
    });}}
}
