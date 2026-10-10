package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import java.sql.Connection;

/** Joint creation is atomic; later production mutations use independent repositories. */
public final class ProductionBuildingInitializer {
    private final Connection connection;
    private final Object lock;
    private final BuildingRepository buildings;
    private final ProductionMethodConfigurationRepository methods;
    private final ProductionExecutionRepository executions;
    private final ProductionRecipeResolver recipes;

    public ProductionBuildingInitializer(Connection connection, Object lock, BuildingRepository buildings,
                                        ProductionMethodConfigurationRepository methods, ProductionExecutionRepository executions,
                                        ProductionRecipeResolver recipes) {
        this.connection = connection; this.lock = lock; this.buildings = buildings;
        this.methods = methods; this.executions = executions; this.recipes = recipes;
    }
    public void initialize(EconomicBuilding building) {
        synchronized (lock) {
            var rules = recipes.rulesFor(building.getBuildingTypeId());
            SqliteTransactions.run(connection, () -> {
                buildings.save(building);
                methods.create(new ProductionMethodConfiguration(building.getId(), rules, rules.defaults(), null));
                executions.create(new ProductionExecution(building.getId()));
                return null;
            });
        }
    }
}
