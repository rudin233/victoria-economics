package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.ProductionEmploymentPort;
import java.util.*;

/** Fresh authoritative reads, shared lock, and no cached second production state. */
public final class RepositoryProductionFacts implements ProductionEmploymentPort {
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
}
