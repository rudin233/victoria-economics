package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatchConfiguration;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionInventoryPort;
import com.bbmurloc.victoriaeconomics.server.inventory.application.ProductionEquipmentService;
import com.bbmurloc.victoriaeconomics.server.production.domain.WorkforcePlanningService;
import java.util.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;

/**
 * Recoverable application coordinator. No Minecraft, wage ledger, or second stock authority.
 */
public final class ProductionService {
    private static final System.Logger LOG = System.getLogger(ProductionService.class.getName());
    private final BuildingRegistry buildings;
    private final BuildingRepository repository;
    private final ProductionEquipmentService equipment;
    private final ProductionInventoryPort inventory;
    private final BuildingPayrollPort payroll;
    private final WorkforcePort workforce;
    private final ProductionJournal journal;
    private final WorkforcePlanningService planning = new WorkforcePlanningService();
    private final Object lock;
    private final Map<UUID, String> blocked = new HashMap<>();

    public ProductionService(BuildingRegistry buildings, BuildingRepository repository, ProductionEquipmentService equipment,
                             ProductionInventoryPort inventory, BuildingPayrollPort payroll, WorkforcePort workforce,
                             ProductionJournal journal, Object lock) {
        this.buildings = buildings;
        this.repository = repository;
        this.equipment = equipment;
        this.inventory = inventory;
        this.payroll = payroll;
        this.workforce = workforce;
        this.journal = journal;
        this.lock = lock;
    }

    public String blockedReason(UUID building) {
        synchronized (lock) {
            return blocked.get(building);
        }
    }

    public ProductionBatch startBatch(UUID buildingId) {
        synchronized (lock) {
            EconomicBuilding building = require(buildingId);
            var execution = building.getProductionDepartment().getExecution();
            if (journal.pendingStarts().stream().anyMatch(i -> i.buildingId().equals(buildingId)))
                throw new IllegalStateException("Start compensation still pending");
            if (execution.batch() != null || execution.boundary() != ProductionExecution.Boundary.NONE)
                throw new IllegalStateException("Previous batch or boundary unfinished");
            if (!workforce.reconcile(buildingId, building.getProductionDepartment().getRecipe().requiredWorkers())) {
                throw new IllegalStateException("Personnel reconciliation required before production");
            }
            ProductionBatchConfiguration configuration = prepare(building);
            UUID batchId = UUID.randomUUID();
            var before = execution.state();
            journal.recordStart(new ProductionJournal.StartIntent(batchId, buildingId));
            boolean committed = false;
            try {
                inventory.reserve(buildingId, batchId, configuration.resolvedRecipe().inputs());
                equipment.protectForBatch(buildingId, batchId);
                // The final commit re-reads every authoritative condition; readiness is not a permit.
                if (!configuration.equals(prepare(building)) || !inventory.isReserved(buildingId, batchId, configuration.resolvedRecipe().inputs())) {
                    throw new IllegalStateException("Production conditions changed before commit");
                }
                execution.start(batchId, configuration);
                execution.requestAutomatic(true);
                repository.save(building);
                committed = true;
                try {
                    journal.finishStart(batchId);
                } catch (RuntimeException cleanup) {
                    LOG.log(System.Logger.Level.WARNING, "Committed start journal cleanup will retry: " + batchId, cleanup);
                }
                blocked.remove(buildingId);
                return execution.batch();
            } catch (RuntimeException failure) {
                if (!committed) {
                    try {
                        if (journal.containsBatch(batchId)) {
                            // A successful DB commit with a lost acknowledgement must never release committed inputs.
                            restoreCommittedBuilding(buildingId);
                            try {
                                journal.finishStart(batchId);
                            } catch (RuntimeException cleanup) {
                                LOG.log(System.Logger.Level.WARNING, "Committed start cleanup will retry: " + batchId, cleanup);
                            }
                            return require(buildingId).getProductionDepartment().getActiveBatch();
                        }
                    } catch (RuntimeException verificationFailed) {
                        failure.addSuppressed(verificationFailed);
                        execution.restoreState(before);
                        // Commit outcome unknown: keep the durable intent and reservations for recovery.
                        throw failure;
                    }
                    execution.restoreState(before);
                    compensateStart(new ProductionJournal.StartIntent(batchId, buildingId));
                }
                throw failure;
            }
        }
    }

    private ProductionBatchConfiguration prepare(EconomicBuilding building) {
        if (building.getStatus() != BuildingStatus.ACTIVE) throw new IllegalStateException("Building is stopped");
        var department = building.getProductionDepartment();
        var execution = department.getExecution();
        if (execution.batch() != null || execution.boundary() != ProductionExecution.Boundary.NONE)
            throw new IllegalStateException("Previous batch or boundary unfinished");
        if (payroll.state(building.getId()) != BuildingPayrollPort.State.CURRENT)
            throw new IllegalStateException("Payroll is not CURRENT (or unavailable)");
        var stock = inventory.storageStatus(building.getId());
        if (stock.overCapacity()) throw new IllegalStateException("Storage is over capacity");
        var holding = equipment.getHolding(building.getId());
        if (holding.hasPendingRequests()) throw new IllegalStateException("Equipment requests still pending");
        var recipe = department.getRecipe();
        var plan = planning.plan(building.getId(), recipe.requiredWorkers(), holding.getInstalledQuantity(), holding.getCapacity(),
                workforce.effectiveEmployment(building.getId()), workforce::qualified);
        if (!workforceCapacityFits(building))
            throw new IllegalStateException("Employment exceeds current PM capacity; personnel reconciliation required");
        return new ProductionBatchConfiguration(department.getSelectedProductionMethods(), recipe, holding.getInstalledQuantity(), holding.getCapacity(), plan);
    }

    private boolean workforceCapacityFits(EconomicBuilding building) {
        Map<String, Integer> counts = new HashMap<>();
        workforce.effectiveEmployment(building.getId()).forEach(e -> counts.merge(e.occupationId(), 1, Integer::sum));
        var demand = building.getProductionDepartment().getRecipe().requiredWorkers();
        return counts.entrySet().stream().allMatch(e -> e.getValue() <= demand.getOrDefault(e.getKey(), 0));
    }

    /**
     * Called once per logical server tick, independent of loaded chunks, GUI, wall time and TPS.
     */
    public void onEconomicTick() {
        synchronized (lock) {
            recoverStarts();
            for (EconomicBuilding building : buildings.getAll()) {
                try {
                    var execution = building.getProductionDepartment().getExecution();
                    boolean hadBatch = execution.batch() != null;
                    if (execution.hasUnfinishedBatch()) {
                        var before = execution.state();
                        var wageState = payroll.state(building.getId());
                        if (wageState == BuildingPayrollPort.State.ARREARS) execution.terminate();
                        else if (wageState == BuildingPayrollPort.State.CURRENT) execution.resume();
                        else execution.pause();
                        execution.tick(1);
                        persistExecution(building, before);
                        settleAndHandleBoundary(building);
                    } else if (execution.boundary() != ProductionExecution.Boundary.NONE)
                        settleAndHandleBoundary(building);
                    // A new batch starts on the following tick and never receives the old batch's tick.
                    if (!hadBatch && execution.batch() == null && execution.automatic() && building.getStatus() == BuildingStatus.ACTIVE)
                        startBatch(building.getId());
                } catch (RuntimeException failure) {
                    String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    if (!message.equals(blocked.put(building.getId(), message))) {
                        LOG.log(System.Logger.Level.WARNING, "Production blocked at building " + building.getId() + ": " + message, failure);
                    }
                }
            }
        }
    }

    public void abortBatch(UUID buildingId) {
        synchronized (lock) {
            var building = require(buildingId);
            var execution = building.getProductionDepartment().getExecution();
            var before = execution.state();
            execution.terminate();
            persistExecution(building, before);
            settleAndHandleBoundary(building);
        }
    }

    public void setAutomatic(UUID buildingId, boolean enabled) {
        synchronized (lock) {
            var building = require(buildingId);
            var execution = building.getProductionDepartment().getExecution();
            var before = execution.state();
            execution.requestAutomatic(enabled);
            persistExecution(building, before);
        }
    }

    /**
     * Safe to repeat after a failed save or a crash at any settlement/boundary step.
     */
    public void retry(UUID buildingId) {
        synchronized (lock) {
            recoverStarts();
            settleAndHandleBoundary(require(buildingId));
            blocked.remove(buildingId);
        }
    }

    private void settleAndHandleBoundary(EconomicBuilding building) {
        var department = building.getProductionDepartment();
        var execution = department.getExecution();
        ProductionBatch batch = execution.batch();
        if (batch != null && batch.needsSettlement()) {
            var recipe = batch.getConfiguration().resolvedRecipe();
            var receipt = inventory.settle(building.getId(), batch.getId(), recipe.inputs(), recipe.outputs(), batch.getProgress());
            var before = execution.state();
            execution.settlementCompleted(receipt.batchId());
            persistExecution(building, before); // If this fails, inventory's durable receipt makes retry idempotent.
        }
        if (execution.boundary() == ProductionExecution.Boundary.METHODS) {
            var methodsBefore = department.methodState();
            var before = execution.state();
            department.applyPendingProductionMethods();
            execution.methodsApplied();
            try {
                repository.save(building);
            } catch (RuntimeException e) {
                department.restoreMethodState(methodsBefore);
                execution.restoreState(before);
                throw e;
            }
        }
        if (execution.boundary() == ProductionExecution.Boundary.EQUIPMENT) {
            equipment.flushPendingOperationsForBuilding(building.getId(), execution.batch().getId());
            var before = execution.state();
            execution.equipmentApplied();
            persistExecution(building, before);
        }
        if (execution.boundary() == ProductionExecution.Boundary.WORKFORCE) {
            if (!workforce.reconcile(building.getId(), department.getRecipe().requiredWorkers())) {
                throw new IllegalStateException("Personnel reconciliation blocked: company transfer/consent/dismissal context required");
            }
            var before = execution.state();
            execution.workforceReconciled();
            persistExecution(building, before);
        }
    }

    public void recoverStarts() {
        synchronized (lock) {
            List<ProductionJournal.StartIntent> intents;
            try {
                intents = journal.pendingStarts();
            } catch (RuntimeException failure) {
                logRecoveryFailure(null, failure);
                return;
            }
            for (var intent : intents) {
                try {
                    if (journal.containsBatch(intent.batchId())) {
                        restoreCommittedBuilding(intent.buildingId());
                        journal.finishStart(intent.batchId());
                    } else compensateStart(intent);
                } catch (RuntimeException failure) {
                    logRecoveryFailure(intent.buildingId(), failure);
                }
            }
        }
    }

    private String lastRecoveryFailure;

    private void logRecoveryFailure(UUID building, RuntimeException failure) {
        String message = "Start recovery blocked at " + building + ": " + failure.getMessage();
        if (!message.equals(lastRecoveryFailure)) LOG.log(System.Logger.Level.WARNING, message, failure);
        lastRecoveryFailure = message;
    }

    private void restoreCommittedBuilding(UUID id) {
        EconomicBuilding restored = repository.loadAll().stream().filter(b -> b.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Committed building checkpoint missing: " + id));
        buildings.restore(restored);
    }

    private void compensateStart(ProductionJournal.StartIntent intent) {
        try {
            inventory.release(intent.buildingId(), intent.batchId());
            equipment.releaseBatchProtection(intent.buildingId(), intent.batchId());
            journal.finishStart(intent.batchId());
            releaseFailures.remove(intent.batchId());
        } catch (RuntimeException failure) {
            // Intent stays durable; even a partly-acquired reservation remains discoverable and retryable.
            String message = "Material release will retry for batch " + intent.batchId() + " at " + intent.buildingId() + ": " + failure.getMessage();
            if (!message.equals(releaseFailures.put(intent.batchId(), message)))
                LOG.log(System.Logger.Level.WARNING, message, failure);
        }
    }

    private final Map<UUID, String> releaseFailures = new HashMap<>();

    private void persistExecution(EconomicBuilding building, ProductionExecution.State before) {
        var current = building.getProductionDepartment().getExecution();
        if (before.batch() == current.batch() && before.lastBatch() == current.lastBatch()
                && before.boundary() == current.boundary() && before.automatic() == current.automatic()) return;
        try {
            repository.save(building);
        } catch (RuntimeException failure) {
            building.getProductionDepartment().getExecution().restoreState(before);
            throw failure;
        }
    }

    private EconomicBuilding require(UUID id) {
        var building = buildings.get(id);
        if (building == null) throw new IllegalArgumentException("Unknown building: " + id);
        return building;
    }
}
