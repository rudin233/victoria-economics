package com.bbmurloc.victoriaeconomics.server.building.department.production;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.domain.ResolvedProductionRecipe;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodSelections;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodRules;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionMethodConfiguration;
import java.util.*;

public final class ProductionDepartment {
    private ProductionMethodConfiguration methods;
    private final ProductionExecution execution;

    public ProductionDepartment(UUID buildingId) {
        execution = new ProductionExecution(buildingId);
    }

    public void initializeMethods(ProductionMethodRules rules, Map<String, String> effective, Map<String, String> pending) {
        if (methods != null) throw new IllegalStateException("Production methods already initialized");
        methods = new ProductionMethodConfiguration(rules, effective, pending);
    }

    private ProductionMethodConfiguration methods() {
        if (methods == null) throw new IllegalStateException("Production methods have not been initialized");
        return methods;
    }

    public Map<String, String> getSelectedProductionMethods() {
        return methods().effective().methods();
    }

    public String getSelectedProductionMethodId(String group) {
        return getSelectedProductionMethods().get(group);
    }

    public Optional<ProductionMethodSelections> getPendingProductionMethods() {
        return methods().pending();
    }

    public ResolvedProductionRecipe getRecipe() {
        return methods().recipe();
    }

    public void selectProductionMethod(String group, String method) {
        methods().requestSelection(group, method, execution.blocksConfiguration());
        revisitBoundary();
    }

    public void requestProductionMethods(Map<String, String> target) {
        methods().requestTarget(target, execution.blocksConfiguration());
        revisitBoundary();
    }

    private void revisitBoundary() {
        if (methods().pending().isPresent() && execution.boundary() != ProductionExecution.Boundary.NONE)
            execution.revisitMethods();
    }

    public void cancelPendingProductionMethods() {
        methods().cancelPending();
    }

    public ProductionMethodConfiguration.State methodState() {
        return methods().state();
    }

    public void restoreMethodState(ProductionMethodConfiguration.State state) {
        methods().restore(state);
    }

    public boolean applyPendingProductionMethods() {
        if (execution.hasUnfinishedBatch()) throw new IllegalStateException("Cannot change methods before settlement");
        return methods().applyPending();
    }

    public ProductionExecution getExecution() {
        return execution;
    }

    public ProductionBatch getActiveBatch() {
        return execution.batch();
    }

    /**
     * Includes paused and settling batches so equipment and employees remain protected.
     */
    public boolean hasActiveBatch() {
        return execution.hasUnfinishedBatch();
    }
}
