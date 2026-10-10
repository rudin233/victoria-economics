package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.inventory.application.ProductionEquipmentService;
import java.util.*;
import java.util.function.Consumer;

/** Recoverable application coordinator. Repositories own durable truth; queries return detached aggregates. */
public final class ProductionService {
    private static final System.Logger LOG = System.getLogger(ProductionService.class.getName());
    private final BuildingRegistry buildings;
    private final ProductionMethodConfigurationRepository configurations;
    private final ProductionExecutionRepository executions;
    private final ProductionEquipmentService equipment;
    private final ProductionInventoryPort inventory;
    private final BuildingPayrollPort payroll;
    private final WorkforcePort workforce;
    private final ProductionJournal journal;
    private final WorkforcePlanningService planning = new WorkforcePlanningService();
    private final Object lock;
    private final Map<UUID, String> blocked = new HashMap<>();
    private final Map<UUID, String> releaseFailures = new HashMap<>();
    private String lastRecoveryFailure;

    public ProductionService(BuildingRegistry buildings, ProductionMethodConfigurationRepository configurations,
                             ProductionExecutionRepository executions, ProductionEquipmentService equipment,
                             ProductionInventoryPort inventory, BuildingPayrollPort payroll, WorkforcePort workforce,
                             ProductionJournal journal, Object lock) {
        this.buildings = buildings; this.configurations = configurations; this.executions = executions;
        this.equipment = equipment; this.inventory = inventory; this.payroll = payroll;
        this.workforce = workforce; this.journal = journal; this.lock = lock;
    }

    public ProductionMethodConfiguration methods(UUID id) {
        synchronized (lock) { require(id); return configurations.load(id); }
    }
    public ProductionExecution execution(UUID id) {
        synchronized (lock) { require(id); return executions.load(id); }
    }
    public String blockedReason(UUID id) { synchronized (lock) { return blocked.get(id); } }

    public void selectProductionMethod(UUID id, String group, String method) {
        synchronized (lock) {
            var execution = trustworthyExecution(id);
            changeMethods(id, execution, c -> c.requestSelection(group, method, execution.blocksConfiguration()));
        }
    }
    public void requestProductionMethods(UUID id, Map<String, String> target) {
        synchronized (lock) {
            var execution = trustworthyExecution(id);
            changeMethods(id, execution, c -> c.requestTarget(target, execution.blocksConfiguration()));
        }
    }
    public void cancelPendingProductionMethods(UUID id) {
        synchronized (lock) { changeMethods(id, trustworthyExecution(id), ProductionMethodConfiguration::cancelPending); }
    }
    private ProductionExecution trustworthyExecution(UUID id) {
        require(id);
        for (var intent : journal.pendingStarts()) {
            if (intent.buildingId().equals(id)) journal.containsBatch(intent.batchId());
        }
        return executions.load(id);
    }
    private void changeMethods(UUID id, ProductionExecution execution, Consumer<ProductionMethodConfiguration> change) {
        var configuration = configurations.load(id);
        long revision = configuration.configurationRevision();
        change.accept(configuration);
        if (revision == configuration.configurationRevision()) return;
        configurations.save(configuration, revision);
        if (configuration.pending().isPresent() && execution.boundary() != ProductionExecution.Boundary.NONE) {
            execution.revisitMethods();
            try { executions.save(execution, execution.executionRevision()); }
            catch (RuntimeException failure) {
                block(id, failure); // Pending is already durable; recovery will rediscover it at the old boundary.
                throw new IllegalStateException("PM target saved; execution boundary update will retry", failure);
            }
        }
        if (!execution.blocksConfiguration()) {
            try { settleAndHandleBoundary(id); }
            catch (RuntimeException failure) { block(id, failure); } // The independent PM commit remains accepted.
        }
    }

    public ProductionBatch startBatch(UUID id) {
        synchronized (lock) {
            var building = require(id);
            var execution = executions.load(id);
            if (journal.pendingStarts().stream().anyMatch(i -> i.buildingId().equals(id)))
                throw new IllegalStateException("Start compensation still pending");
            if (execution.batch() != null || execution.boundary() != ProductionExecution.Boundary.NONE)
                throw new IllegalStateException("Previous batch or boundary unfinished");
            settleAndHandleBoundary(id); // Detect an independently committed PM and necessary personnel work before starting.
            execution = executions.load(id);
            var configuration = prepare(building);
            UUID batchId = UUID.randomUUID();
            var intent = new ProductionJournal.StartIntent(batchId, id);
            journal.recordStart(intent);
            boolean committed = false;
            try {
                inventory.reserve(id, batchId, configuration.resolvedRecipe().inputs());
                equipment.protectForBatch(id, batchId);
                // Keep the formal-capacity check before reconciliation; then re-read after its possible mutations.
                var finalConditions = prepare(building);
                if (!workforce.reconcile(id, finalConditions.resolvedRecipe().requiredWorkers()))
                    throw new IllegalStateException("Personnel reconciliation required at final commit");
                if (!configuration.equals(prepare(building)) || executions.load(id).executionRevision() != execution.executionRevision()
                        || !inventory.isReserved(id, batchId, configuration.resolvedRecipe().inputs()))
                    throw new IllegalStateException("Production conditions changed before commit");
                execution.start(batchId, configuration);
                execution.requestAutomatic(true);
                executions.save(execution, execution.executionRevision());
                committed = true;
                finishCommittedStart(batchId);
                blocked.remove(id);
                return execution.batch();
            } catch (RuntimeException failure) {
                if (!committed) {
                    try {
                        if (journal.containsBatch(batchId)) {
                            var restored = executions.load(id);
                            if (restored.batch() == null || !restored.batch().getId().equals(batchId))
                                throw new IllegalStateException("Committed start reference cannot be confirmed: " + batchId);
                            finishCommittedStart(batchId);
                            return restored.batch();
                        }
                    } catch (RuntimeException verification) {
                        failure.addSuppressed(verification);
                        // No mutable authority is cached. Keep the durable intent and both resource commitments.
                        throw failure;
                    }
                    compensateStart(intent);
                }
                throw failure;
            }
        }
    }
    private void finishCommittedStart(UUID batch) {
        try { journal.finishStart(batch); }
        catch (RuntimeException cleanup) { LOG.log(System.Logger.Level.WARNING, "Committed start cleanup will retry: " + batch, cleanup); }
    }
    private ProductionBatchConfiguration prepare(EconomicBuilding building) {
        UUID id = building.getId();
        if (building.getStatus() != BuildingStatus.ACTIVE) throw new IllegalStateException("Building is stopped");
        var execution = executions.load(id);
        if (execution.batch() != null || execution.boundary() != ProductionExecution.Boundary.NONE)
            throw new IllegalStateException("Previous batch or boundary unfinished");
        if (payroll.state(id) != BuildingPayrollPort.State.CURRENT)
            throw new IllegalStateException("Payroll is not CURRENT (or unavailable)");
        if (inventory.storageStatus(id).overCapacity()) throw new IllegalStateException("Storage is over capacity");
        var holding = equipment.getHolding(id);
        if (holding.hasPendingRequests()) throw new IllegalStateException("Equipment requests still pending");
        var configuration = configurations.load(id);
        if (configuration.pending().isPresent()) throw new IllegalStateException("Pending production methods not applied");
        if (execution.methodsEffectiveRevision() != configuration.effectiveRevision())
            throw new IllegalStateException("Effective production methods have not been reconciled");
        var recipe = configuration.recipe();
        var plan = planning.plan(id, recipe.requiredWorkers(), holding.getInstalledQuantity(), holding.getCapacity(),
                workforce.effectiveEmployment(id), workforce::qualified);
        Map<String, Integer> counts = new HashMap<>();
        workforce.effectiveEmployment(id).forEach(e -> counts.merge(e.occupationId(), 1, Integer::sum));
        if (!counts.entrySet().stream().allMatch(e -> e.getValue() <= recipe.requiredWorkers().getOrDefault(e.getKey(), 0)))
            throw new IllegalStateException("Employment exceeds current PM capacity; personnel reconciliation required");
        return new ProductionBatchConfiguration(configuration.effective().methods(), recipe, holding.getInstalledQuantity(),
                holding.getCapacity(), plan, configuration.effectiveRevision());
    }

    /** Logical ticks only; no loaded chunk, GUI, wall-clock or offline catch-up dependency. */
    public void onEconomicTick() {
        synchronized (lock) {
            recoverStarts();
            for (var building : buildings.getAll()) {
                try {
                    var execution = executions.load(building.getId());
                    boolean hadBatch = execution.batch() != null;
                    if (execution.hasUnfinishedBatch()) {
                        var before = execution.state();
                        var state = payroll.state(building.getId());
                        if (state == BuildingPayrollPort.State.ARREARS) execution.terminate();
                        else if (state == BuildingPayrollPort.State.CURRENT) execution.resume();
                        else execution.pause();
                        execution.tick(1);
                        persistExecution(execution, before);
                        settleAndHandleBoundary(building.getId());
                    } else settleAndHandleBoundary(building.getId());
                    var latest = executions.load(building.getId());
                    if (!hadBatch && latest.batch() == null && latest.automatic() && building.getStatus() == BuildingStatus.ACTIVE)
                        startBatch(building.getId());
                } catch (RuntimeException failure) { block(building.getId(), failure); }
            }
        }
    }
    public void abortBatch(UUID id) {
        synchronized (lock) {
            require(id);
            var execution = executions.load(id);
            var before = execution.state();
            execution.terminate();
            persistExecution(execution, before);
            settleAndHandleBoundary(id);
        }
    }
    public void setAutomatic(UUID id, boolean enabled) {
        synchronized (lock) {
            require(id);
            var execution = executions.load(id);
            var before = execution.state();
            execution.requestAutomatic(enabled);
            persistExecution(execution, before);
        }
    }
    public void retry(UUID id) {
        synchronized (lock) { require(id); recoverStarts(); settleAndHandleBoundary(id); blocked.remove(id); }
    }
    private void settleAndHandleBoundary(UUID id) {
        var execution = trustworthyExecution(id);
        var batch = execution.batch();
        if (batch != null && batch.needsSettlement()) {
            var recipe = batch.getConfiguration().resolvedRecipe();
            var receipt = inventory.settle(id, batch.getId(), recipe.inputs(), recipe.outputs(), batch.getProgress());
            execution.settlementCompleted(receipt.batchId());
            executions.save(execution, execution.executionRevision());
        }
        // Each checkpoint is independently durable. Re-read both owners before advancing a later stage.
        for (int attempt = 0; attempt < 32; attempt++) {
            execution = trustworthyExecution(id);
            if (execution.hasUnfinishedBatch()) return;
            var configuration = configurations.load(id);
            if (execution.boundary() == ProductionExecution.Boundary.NONE) {
                long revision = configuration.configurationRevision();
                if (configuration.applyPending()) {
                    configurations.save(configuration, revision);
                    continue;
                }
                requireReconciliation(id, configuration);
                var latest = configurations.load(id);
                if (latest.pending().isPresent() || latest.effectiveRevision() != configuration.effectiveRevision()) continue;
                execution = trustworthyExecution(id);
                var before = execution.state();
                execution.idleMethodsReconciled(latest.effectiveRevision());
                persistExecution(execution, before);
                return;
            }
            if (execution.boundary() != ProductionExecution.Boundary.METHODS && needsMethods(execution, configuration)) {
                execution.revisitMethods();
                executions.save(execution, execution.executionRevision());
                continue;
            }
            switch (execution.boundary()) {
                case METHODS -> {
                    long revision = configuration.configurationRevision();
                    if (configuration.applyPending()) configurations.save(configuration, revision);
                    configuration = configurations.load(id);
                    if (configuration.pending().isPresent()) continue;
                    // Trigger reservation cleanup/personnel reconciliation immediately after PM becomes effective.
                    // Incomplete real transfers still block WORKFORCE; Production never invents their completion.
                    workforce.reconcile(id, configuration.recipe().requiredWorkers());
                    var latest = configurations.load(id);
                    if (latest.pending().isPresent() || latest.effectiveRevision() != configuration.effectiveRevision()) continue;
                    execution = trustworthyExecution(id);
                    execution.methodsApplied(latest.effectiveRevision());
                    executions.save(execution, execution.executionRevision());
                }
                case EQUIPMENT -> {
                    equipment.flushPendingOperationsForBuilding(id, execution.batch().getId());
                    execution = trustworthyExecution(id);
                    if (execution.boundary() != ProductionExecution.Boundary.EQUIPMENT || needsMethods(execution, configurations.load(id))) continue;
                    execution.equipmentApplied();
                    executions.save(execution, execution.executionRevision());
                }
                case WORKFORCE -> {
                    requireReconciliation(id, configuration);
                    execution = trustworthyExecution(id);
                    if (execution.boundary() != ProductionExecution.Boundary.WORKFORCE || needsMethods(execution, configurations.load(id))) continue;
                    execution.workforceReconciled();
                    executions.save(execution, execution.executionRevision());
                }
                case NONE -> throw new IllegalStateException("Unexpected idle boundary");
            }
        }
        throw new IllegalStateException("Production methods kept changing during boundary reconciliation; retry required");
    }
    private static boolean needsMethods(ProductionExecution execution, ProductionMethodConfiguration configuration) {
        return configuration.pending().isPresent() || execution.methodsEffectiveRevision() != configuration.effectiveRevision();
    }
    private void requireReconciliation(UUID id, ProductionMethodConfiguration configuration) {
        if (!workforce.reconcile(id, configuration.recipe().requiredWorkers()))
            throw new IllegalStateException("Personnel reconciliation blocked: company transfer/consent/dismissal context required");
    }

    /** Startup recovery also checks idle sites, including automatic=false, against current PM and Employment facts. */
    public void recoverBoundaries() {
        synchronized (lock) {
            for (var building : buildings.getAll()) {
                try { settleAndHandleBoundary(building.getId()); }
                catch (RuntimeException failure) { block(building.getId(), failure); }
            }
        }
    }

    public void recoverStarts() {
        synchronized (lock) {
            List<ProductionJournal.StartIntent> intents;
            try { intents = journal.pendingStarts(); }
            catch (RuntimeException failure) { recoveryFailure(null, failure); return; }
            for (var intent : intents) {
                try {
                    if (journal.containsBatch(intent.batchId())) {
                        executions.load(intent.buildingId()); // Verify the independent execution; do not replace PM or Building state.
                        journal.finishStart(intent.batchId());
                    } else compensateStart(intent);
                } catch (RuntimeException failure) { recoveryFailure(intent.buildingId(), failure); }
            }
        }
    }
    private void compensateStart(ProductionJournal.StartIntent intent) {
        try {
            inventory.release(intent.buildingId(), intent.batchId());
            equipment.releaseBatchProtection(intent.buildingId(), intent.batchId());
            journal.finishStart(intent.batchId());
            releaseFailures.remove(intent.batchId());
        } catch (RuntimeException failure) {
            String message = "Material release will retry for batch " + intent.batchId() + ": " + failure.getMessage();
            if (!message.equals(releaseFailures.put(intent.batchId(), message))) LOG.log(System.Logger.Level.WARNING, message, failure);
        }
    }
    private void recoveryFailure(UUID id, RuntimeException failure) {
        String message = "Start recovery blocked at " + id + ": " + failure.getMessage();
        if (!message.equals(lastRecoveryFailure)) LOG.log(System.Logger.Level.WARNING, message, failure);
        lastRecoveryFailure = message;
    }
    private void persistExecution(ProductionExecution execution, ProductionExecution.State before) {
        if (before.batch() == execution.batch() && before.lastBatch() == execution.lastBatch()
                && before.boundary() == execution.boundary() && before.automatic() == execution.automatic()
                && before.methodsEffectiveRevision() == execution.methodsEffectiveRevision()) return;
        executions.save(execution, execution.executionRevision());
    }
    private void block(UUID id, RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (!message.equals(blocked.put(id, message))) LOG.log(System.Logger.Level.WARNING, "Production blocked at " + id + ": " + message, failure);
    }
    private EconomicBuilding require(UUID id) {
        var building = buildings.get(id);
        if (building == null) throw new IllegalArgumentException("Unknown building: " + id);
        return building;
    }
}
