package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import java.util.*;

/** 协调生产开工、推进及结算恢复。 */
public final class ProductionService {
    private static final System.Logger LOG=System.getLogger(ProductionService.class.getName());
    private final ProductionBoundaryCoordinator coordinator;
    private final Object lock;
    private final Map<UUID,String> blocked=new HashMap<>();
    public ProductionService(ProductionBoundaryCoordinator coordinator,Object lock){this.coordinator=coordinator;this.lock=lock;}
    public ProductionMethodConfiguration methods(UUID id){return coordinator.methods(id);}
    public ProductionExecution execution(UUID id){return coordinator.execution(id);}
    public String blockedReason(UUID id){synchronized(lock){return blocked.get(id);}}
    public void selectProductionMethod(UUID id,String group,String method){coordinator.changeMethods(id,(m,defer)->m.requestSelection(group,method,defer));}
    public void requestProductionMethods(UUID id,Map<String,String> target){coordinator.changeMethods(id,(m,defer)->m.requestTarget(target,defer));}
    public void cancelPendingProductionMethods(UUID id){coordinator.changeMethods(id,(m,defer)->m.cancelPending());}
    public ProductionBatch startBatch(UUID id){return startBatch(id,false);}
    private ProductionBatch startBatch(UUID id, boolean automatic) {
        try (var measurement = EconomyDiagnostics.stage("production.start")) {
            synchronized (lock) {
                if (!automatic) {
                    var execution = coordinator.execution(id);
                    var batch = execution.batch();
                    if ((batch != null && (batch.needsSettlement() || batch.isEnded()))
                            || (batch == null && coordinator.methods(id).pending().isPresent()))
                        finishBoundary(id, execution);
                }
                var started = coordinator.startBatch(id, automatic);
                blocked.remove(id);
                return started;
            }
        }
    }

    /** 关闭自动续产，当前批次继续按原状态推进和结算。 */
    public void stopAfterCurrentBatch(UUID id) {
        coordinator.automatic(id, false);
    }
    public void setAutomatic(UUID id,boolean enabled){coordinator.automatic(id,enabled);}
    public void setManualPause(UUID id,boolean pause){coordinator.manualPause(id,pause);}
    public void retry(UUID id){synchronized(lock){finishBoundary(id, coordinator.execution(id));blocked.remove(id);}}
    /** 建筑生命周期沿用库存结算协议，不应用卖方的待生效生产方法。 */
    public void settleForLifecycle(UUID id){synchronized(lock){
        if(!coordinator.lifecycleHeld(id))throw new IllegalStateException("Durable lifecycle intent required");
        settleDecidedBatch(id, coordinator.execution(id).batch());
    }}
    private void finishBoundary(UUID id, ProductionExecution execution) {
        try (var measurement = EconomyDiagnostics.stage("production.followup")) {
            boolean settled = settleDecidedBatch(id, execution.batch());
            if ((settled || !execution.hasUnfinishedBatch()) && !coordinator.lifecycleHeld(id))
                coordinator.reconfigure(id);
        }
    }
    private boolean settleDecidedBatch(UUID id, ProductionBatch batch) {
        if (batch == null || !batch.needsSettlement()) return false;
        coordinator.settle(id, ProductionSettlement.forBatch(batch));
        return true;
    }
    /** 先完成全部建筑的推进与结算，再在各区域内轮转开工。 */
    public void onServerTick() {
        try (var diagnosticScope = EconomyDiagnostics.scope("production.tick")) {
            synchronized (lock) {
                long tick = coordinator.acceptedTick();
                var regions = coordinator.buildingsByRegion();
                var followUp = new HashSet<UUID>();
                var failures = new ArrayList<RuntimeException>();

                for (var buildings : regions.values()) for (UUID id : buildings) {
                    try (var buildingMeasurement = EconomyDiagnostics.scope("production.building")) {
                        if (coordinator.processTick(id, tick) != ProductionBoundaryCoordinator.TickResult.NEEDS_FOLLOW_UP)
                            continue;
                        finishBoundary(id, coordinator.execution(id));
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
    public void recoverAcceptedTick() {
        try (var diagnosticScope = EconomyDiagnostics.scope("production.recovery")) {
            synchronized (lock) {
                if (coordinator.needsRecovery()) onServerTick();
            }
        }
    }
    private void block(UUID id,RuntimeException failure){String message=failure.getMessage();if(!Objects.equals(blocked.put(id,message),message))LOG.log(System.Logger.Level.WARNING,"Production blocked at "+id+": "+message,failure);}
}
