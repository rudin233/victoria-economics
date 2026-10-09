package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ProductionRecipeResolver;

import java.util.*;

/**
 * Server application entry point; shares the economy lock with production, inventory and employment.
 */
public final class BuildingService {
    private final BuildingRegistry buildings;
    private final BuildingRepository repository;
    private final ProductionRecipeResolver recipes;
    private final Object lock;

    public BuildingService(BuildingRegistry buildings, BuildingRepository repository, BuildingTypeRegistry types,
                           ProductionMethodGroupRegistry groups, ProductionMethodRegistry methods) {
        this(buildings, repository, new ProductionRecipeResolver(types, groups, methods), new Object());
    }

    public BuildingService(BuildingRegistry buildings, BuildingRepository repository, ProductionRecipeResolver recipes, Object lock) {
        this.buildings = buildings;
        this.repository = repository;
        this.recipes = recipes;
        this.lock = lock;
    }

    public EconomicBuilding createBuilding(String type) {
        synchronized (lock) {
            var rules = recipes.rulesFor(type);
            EconomicBuilding building = new EconomicBuilding(UUID.randomUUID(), type);
            building.getProductionDepartment().initializeMethods(rules, rules.defaults(), null);
            repository.save(building);
            buildings.add(building);
            return building;
        }
    }

    public boolean stopBuilding(UUID id) {
        synchronized (lock) {
            EconomicBuilding building = buildings.get(id);
            if (building == null) return false;
            BuildingStatus before = building.getStatus();
            building.setStatus(BuildingStatus.STOPPED);
            try {
                repository.save(building);
            } catch (RuntimeException e) {
                building.setStatus(before);
                throw e;
            }
            return true;
        }
    }

    public void selectProductionMethod(UUID id, String group, String method) {
        synchronized (lock) {
            EconomicBuilding building = require(id);
            mutateMethods(building, () -> building.getProductionDepartment().selectProductionMethod(group, method));
        }
    }

    public void requestProductionMethods(UUID id, Map<String, String> target) {
        synchronized (lock) {
            EconomicBuilding building = require(id);
            mutateMethods(building, () -> building.getProductionDepartment().requestProductionMethods(target));
        }
    }

    public void cancelPendingProductionMethods(UUID id) {
        synchronized (lock) {
            EconomicBuilding building = require(id);
            mutateMethods(building, () -> building.getProductionDepartment().cancelPendingProductionMethods());
        }
    }

    private void mutateMethods(EconomicBuilding building, Runnable change) {
        var department = building.getProductionDepartment();
        var before = department.methodState();
        var executionBefore = department.getExecution().state();
        change.run();
        try {
            repository.save(building);
        } catch (RuntimeException e) {
            department.restoreMethodState(before);
            department.getExecution().restoreState(executionBefore);
            throw e;
        }
    }

    private EconomicBuilding require(UUID id) {
        EconomicBuilding building = buildings.get(id);
        if (building == null) throw new IllegalArgumentException("Unknown building: " + id);
        return building;
    }
}
