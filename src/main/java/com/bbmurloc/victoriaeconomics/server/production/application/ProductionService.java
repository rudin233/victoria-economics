package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.CommitOutcomeUnknownException;
import java.util.*;

/** Journaled resources remain independent; each building's Tick advance and reconfiguration have explicit transactions. */
public final class ProductionService {
    private static final System.Logger LOG=System.getLogger(ProductionService.class.getName());
    private final ProductionBoundaryCoordinator coordinator;
    private final ProductionInventoryPort inventory;
    private final ProductionJournal journal;
    private final Object lock;
    private final Map<UUID,String> blocked=new HashMap<>();
    public ProductionService(ProductionBoundaryCoordinator coordinator,ProductionInventoryPort inventory,ProductionJournal journal,Object lock){this.coordinator=coordinator;this.inventory=inventory;this.journal=journal;this.lock=lock;}
    public ProductionMethodConfiguration methods(UUID id){return coordinator.methods(id);}
    public ProductionExecution execution(UUID id){return coordinator.execution(id);}
    public String blockedReason(UUID id){synchronized(lock){return blocked.get(id);}}
    public void selectProductionMethod(UUID id,String group,String method){coordinator.changeMethods(id,(m,defer)->m.requestSelection(group,method,defer));}
    public void requestProductionMethods(UUID id,Map<String,String> target){coordinator.changeMethods(id,(m,defer)->m.requestTarget(target,defer));}
    public void cancelPendingProductionMethods(UUID id){coordinator.changeMethods(id,(m,defer)->m.cancelPending());}
    public ProductionBatch startBatch(UUID id){return startBatch(id,false);}
    private ProductionBatch startBatch(UUID id,boolean automatic){try(var measurement=EconomyDiagnostics.stage("production.start")){synchronized(lock){
        if(journal.pendingStarts().stream().anyMatch(i->i.buildingId().equals(id)))throw new IllegalStateException("Start compensation still pending");
        // The only automatic caller has just confirmed settlement/reconfiguration below.
        // prepare and commitStart still independently re-read every start condition in IMMEDIATE transactions.
        if(!automatic)settleAndHandleBoundary(id);
        var plan=coordinator.prepare(id);var config=plan.configuration();UUID batch=UUID.randomUUID();var intent=new ProductionJournal.StartIntent(batch,id);
        journal.recordStart(intent);
        ProductionBatch started;
        try{
            inventory.reserve(id,batch,config.resolvedRecipe().inputs());
            started=coordinator.commitStart(id,batch,plan,automatic);
        }catch(RuntimeException failure){
            // An unknown result may have created the Batch. Keep both intent and Reservation until fresh verification.
            if(failure instanceof CommitOutcomeUnknownException)throw failure;
            try {
                if (!journal.containsBatch(batch)) compensateStart(intent);
            } catch (RuntimeException compensationFailure) {
                compensationFailure.addSuppressed(failure);
                throw compensationFailure;
            }
            throw failure;
        }
        // Only the immutable Batch escapes, and only after commit AND JDBC cleanup returned successfully.
        finishCommittedStart(batch);blocked.remove(id);return started;
    }}}
    /** 关闭自动续产，当前批次继续按原状态推进和结算。 */
    public void stopAfterCurrentBatch(UUID id) {
        coordinator.automatic(id, false);
    }
    public void setAutomatic(UUID id,boolean enabled){coordinator.automatic(id,enabled);}
    public void setManualPause(UUID id,boolean pause){coordinator.manualPause(id,pause);}
    public void retry(UUID id){synchronized(lock){recoverStarts();settleAndHandleBoundary(id);blocked.remove(id);}}
    /** Lifecycle reuses the exact Journal/settlement protocol without applying seller Pending changes. */
    public void settleForLifecycle(UUID id){synchronized(lock){
        if(!coordinator.lifecycleHeld(id))throw new IllegalStateException("Durable lifecycle intent required");
        recoverStarts();settleAndHandleBoundary(id);
    }}
    private void settleAndHandleBoundary(UUID id){
        try(var measurement=EconomyDiagnostics.stage("production.followup")){
        var e=coordinator.execution(id);var batch=e.batch();
        if(batch!=null&&batch.needsSettlement()){
            var command = ProductionSettlement.forBatch(batch);
            ProductionInventoryPort.SettlementReceipt receipt;
            try(var settlementMeasurement=EconomyDiagnostics.stage("production.settlement")){receipt=inventory.settle(id,command);}
            if(!receipt.command().equals(command))throw new IllegalStateException("Settlement receipt does not match frozen command");
            coordinator.confirmSettlement(id,receipt.command());
        }
        e=coordinator.execution(id);
        if(!e.hasUnfinishedBatch()&&!coordinator.lifecycleHeld(id))coordinator.reconfigure(id);
        }
    }
    /** 先完成全部建筑的推进与结算，再在各区域内轮转开工。 */
    public void onServerTick() {
        try (var diagnosticScope = EconomyDiagnostics.scope("production.tick")) {
            synchronized (lock) {
                long tick = coordinator.acceptedTick();
                recoverStarts();
                var regions = coordinator.buildingsByRegion();
                var followUp = new HashSet<UUID>();
                var failures = new ArrayList<RuntimeException>();

                for (var buildings : regions.values()) for (UUID id : buildings) {
                    try (var buildingMeasurement = EconomyDiagnostics.scope("production.building")) {
                        if (coordinator.processTick(id, tick) != ProductionBoundaryCoordinator.TickResult.NEEDS_FOLLOW_UP)
                            continue;
                        settleAndHandleBoundary(id);
                        var execution = coordinator.execution(id);
                        if (execution.boundary() != ProductionExecution.Boundary.NONE && coordinator.lifecycleHeld(id))
                            throw new IllegalStateException("Lifecycle resource handoff remains incomplete; recover its OperationId");
                        followUp.add(id);
                    } catch (RuntimeException failure) {
                        block(id, failure);
                        failures.add(failure);
                    }
                }
                requireTickWorkSucceeded(tick, failures);

                for (var buildings : regions.values()) {
                    int offset = Math.floorMod(tick, buildings.size());
                    for (int i = 0; i < buildings.size(); i++) {
                        UUID id = buildings.get((int) (((long) offset + i) % buildings.size()));
                        if (!followUp.contains(id)) continue;
                        try {
                            var execution = coordinator.execution(id);
                            if (execution.batch() == null && execution.automatic() && tick >= execution.nextStartNotBeforeTick()
                                    && !coordinator.lifecycleHeld(id)) {
                                try { startBatch(id, true); }
                                catch (ProductionStartBlocked denied) { block(id, denied); }
                            }
                            coordinator.finishTick(id, tick);
                        } catch (RuntimeException failure) {
                            block(id, failure);
                            failures.add(failure);
                            requireTickWorkSucceeded(tick, failures);
                        }
                    }
                }
                coordinator.confirmWholeTick(tick);
            }
        }
    }

    private static void requireTickWorkSucceeded(long tick, List<RuntimeException> failures) {
        if (failures.isEmpty()) return;
        var failure = new IllegalStateException("Production Tick remains incomplete: " + tick);
        failures.forEach(failure::addSuppressed);
        throw failure;
    }

    /** Restart uses the existing accepted Tick and checkpoints; it neither allocates nor guesses missed Ticks. */
    public void recoverAcceptedTick(){
        try (var diagnosticScope = EconomyDiagnostics.scope("production.recovery")) {synchronized(lock){if(!coordinator.needsRecovery())return;recoverStarts();recoverBoundaries();onServerTick();}
        }
    }
    public void recoverBoundaries(){synchronized(lock){
        for(UUID id:coordinator.buildingIds()){
            var e=coordinator.execution(id);
            if(!e.hasUnfinishedBatch()||(e.batch()!=null&&e.batch().needsSettlement()))settleAndHandleBoundary(id);
        }
    }}
    public void recoverStarts(){synchronized(lock){
        for(var intent:journal.pendingStarts()){
            if(journal.containsBatch(intent.batchId())){
                coordinator.verifyCommittedStart(intent);
                journal.finishStart(intent.batchId());
            }else compensateStart(intent);
        }
    }}
    private void compensateStart(ProductionJournal.StartIntent intent){
        inventory.release(intent.buildingId(),intent.batchId());journal.finishStart(intent.batchId());
    }
    private void finishCommittedStart(UUID batch){try{journal.finishStart(batch);}catch(CommitOutcomeUnknownException unknown){throw unknown;}catch(RuntimeException failure){LOG.log(System.Logger.Level.WARNING,"Committed start cleanup will retry: "+batch,failure);}}
    private void block(UUID id,RuntimeException failure){String message=failure.getMessage();if(!Objects.equals(blocked.put(id,message),message))LOG.log(System.Logger.Level.WARNING,"Production blocked at "+id+": "+message,failure);}
}
