package com.bbmurloc.victoriaeconomics.server.production.application;

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
    private ProductionBatch startBatch(UUID id,boolean automatic){synchronized(lock){
        if(journal.pendingStarts().stream().anyMatch(i->i.buildingId().equals(id)))throw new IllegalStateException("Start compensation still pending");
        settleAndHandleBoundary(id);
        var plan=coordinator.prepare(id);var config=plan.configuration();UUID batch=UUID.randomUUID();var intent=new ProductionJournal.StartIntent(batch,id);
        journal.recordStart(intent);
        try{
            inventory.reserve(id,batch,config.resolvedRecipe().inputs());
            coordinator.commitStart(id,batch,plan,automatic);
        }catch(RuntimeException failure){
            // An unknown result may have created the Batch. Keep both intent and Reservation until fresh verification.
            if(failure instanceof CommitOutcomeUnknownException)throw failure;
            try{if(!journal.containsBatch(batch))compensateStart(intent);}catch(RuntimeException unknown){failure.addSuppressed(unknown);}
            throw failure;
        }
        finishCommittedStart(batch);blocked.remove(id);return coordinator.execution(id).batch();
    }}
    public void abortBatch(UUID id){synchronized(lock){coordinator.abort(id);settleAndHandleBoundary(id);}}
    public void setAutomatic(UUID id,boolean enabled){coordinator.automatic(id,enabled);}
    public void setManualPause(UUID id,boolean pause){coordinator.manualPause(id,pause);}
    public void retry(UUID id){synchronized(lock){recoverStarts();settleAndHandleBoundary(id);blocked.remove(id);}}
    private void settleAndHandleBoundary(UUID id){
        var e=coordinator.execution(id);var batch=e.batch();
        if(batch!=null&&batch.needsSettlement()){
            var recipe=batch.getConfiguration().resolvedRecipe();
            var outcome=batch.getStatus()==ProductionBatch.Status.SETTLING_COMPLETED?ProductionSettlement.Outcome.COMPLETED:ProductionSettlement.Outcome.ABORTED;
            var command=new ProductionSettlement(batch.getId(),outcome,recipe.inputs(),recipe.outputs());var receipt=inventory.settle(id,command);
            if(!receipt.command().equals(command))throw new IllegalStateException("Settlement receipt does not match frozen command");
            coordinator.confirmSettlement(id,batch.getId());
        }
        e=coordinator.execution(id);
        if(!e.hasUnfinishedBatch())coordinator.reconfigure(id);
    }
    /** One durable advance per building/accepted Tick. A failure leaves that Tick pending and stops global allocation. */
    public void onEconomicTick(){synchronized(lock){
        long tick=coordinator.acceptedTick();recoverStarts();var failures=new ArrayList<RuntimeException>();
        for(UUID id:coordinator.buildingIds())try{
            if(!coordinator.advance(id,tick))continue;
            settleAndHandleBoundary(id);var e=coordinator.execution(id);
            if(e.batch()==null&&e.automatic())try{startBatch(id,true);}catch(ProductionStartBlocked denied){block(id,denied);}
            coordinator.finishTick(id,tick);
        }catch(RuntimeException failure){block(id,failure);failures.add(failure);}
        if(!failures.isEmpty()){var failure=new IllegalStateException("Production Tick remains incomplete: "+tick);failures.forEach(failure::addSuppressed);throw failure;}
        coordinator.confirmWholeTick(tick);
    }}
    /** Restart uses the existing accepted Tick and checkpoints; it neither allocates nor guesses missed Ticks. */
    public void recoverAcceptedTick(){synchronized(lock){if(!coordinator.needsRecovery())return;recoverStarts();recoverBoundaries();onEconomicTick();}}
    public void recoverBoundaries(){synchronized(lock){
        for(UUID id:coordinator.buildingIds()){
            var e=coordinator.execution(id);
            if(!e.hasUnfinishedBatch()||(e.batch()!=null&&e.batch().needsSettlement()))settleAndHandleBoundary(id);
        }
    }}
    public void recoverStarts(){synchronized(lock){
        for(var intent:journal.pendingStarts()){
            if(journal.containsBatch(intent.batchId())){
                var e=coordinator.execution(intent.buildingId());
                if((e.batch()==null||!e.batch().getId().equals(intent.batchId()))&&(e.lastBatch()==null||!e.lastBatch().getId().equals(intent.batchId())))throw new IllegalStateException("Committed start reference cannot be confirmed");
                finishCommittedStart(intent.batchId());
            }else compensateStart(intent);
        }
    }}
    private void compensateStart(ProductionJournal.StartIntent intent){
        inventory.release(intent.buildingId(),intent.batchId());journal.finishStart(intent.batchId());
    }
    private void finishCommittedStart(UUID batch){try{journal.finishStart(batch);}catch(RuntimeException failure){LOG.log(System.Logger.Level.WARNING,"Committed start cleanup will retry: "+batch,failure);}}
    private void block(UUID id,RuntimeException failure){String message=failure.getMessage();if(!Objects.equals(blocked.put(id,message),message))LOG.log(System.Logger.Level.WARNING,"Production blocked at "+id+": "+message,failure);}
}
