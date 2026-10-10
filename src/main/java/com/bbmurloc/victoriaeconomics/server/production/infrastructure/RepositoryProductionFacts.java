package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.server.inventory.port.EquipmentBatchLifecyclePort;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.ProductionEmploymentPort;
import java.util.*;

/** Fresh authoritative reads, shared lock, and no cached second production state. */
public final class RepositoryProductionFacts implements EquipmentBatchLifecyclePort, ProductionEmploymentPort {
    private final ProductionMethodConfigurationRepository methods;
    private final ProductionExecutionRepository executions;
    private final Object lock;
    public RepositoryProductionFacts(ProductionMethodConfigurationRepository methods, ProductionExecutionRepository executions, Object lock) {
        this.methods = methods; this.executions = executions; this.lock = lock;
    }
    @Override public Map<String, Integer> positionCapacity(UUID id) {
        synchronized (lock) { return methods.load(id).recipe().requiredWorkers(); }
    }
    @Override public boolean participationProtected(UUID id) {
        synchronized (lock) { return executions.load(id).hasUnfinishedBatch(); }
    }
    @Override public boolean employeeProtected(UUID id, UUID employee) {
        synchronized (lock) {
            var execution = executions.load(id);
            return execution.hasUnfinishedBatch() && execution.batch().getConfiguration().activeEmployeesByOccupation()
                    .values().stream().anyMatch(ids -> ids.contains(employee));
        }
    }
    @Override public void requireEquipmentBoundary(UUID id, UUID endedBatch) {
        synchronized (lock) {
            var execution = executions.load(id);
            var configuration = methods.load(id);
            if (execution.boundary() != ProductionExecution.Boundary.EQUIPMENT || execution.batch() == null
                    || !execution.batch().isEnded() || !execution.batch().getId().equals(endedBatch)
                    || configuration.pending().isPresent() || execution.methodsEffectiveRevision() != configuration.effectiveRevision())
                throw new IllegalStateException("Equipment boundary is not durably authorized: " + id + " / " + endedBatch);
        }
    }
}
