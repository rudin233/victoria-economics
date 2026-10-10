package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import java.util.UUID;
import java.util.function.Consumer;

/** Building identity commands. Creation delegates the necessary joint initialization to an application unit of work. */
public final class BuildingService {
    private final BuildingRegistry buildings;
    private final BuildingRepository repository;
    private final BuildingTypeRegistry types;
    private final Consumer<EconomicBuilding> initialize;
    private final Object lock;

    public BuildingService(BuildingRegistry buildings, BuildingRepository repository, BuildingTypeRegistry types,
                           Consumer<EconomicBuilding> initialize, Object lock) {
        this.buildings = buildings;
        this.repository = repository;
        this.types = types;
        this.initialize = initialize;
        this.lock = lock;
    }
    public EconomicBuilding createBuilding(String type) {
        synchronized (lock) {
            if (types.get(type) == null) throw new IllegalArgumentException("Unknown building type: " + type);
            var building = new EconomicBuilding(UUID.randomUUID(), type);
            initialize.accept(building);
            buildings.add(building);
            return building;
        }
    }
    public boolean stopBuilding(UUID id) {
        synchronized (lock) {
            var building = buildings.get(id);
            if (building == null) return false;
            var before = building.getStatus();
            building.setStatus(BuildingStatus.STOPPED);
            try { repository.save(building); }
            catch (RuntimeException failure) { building.setStatus(before); throw failure; }
            return true;
        }
    }
}
