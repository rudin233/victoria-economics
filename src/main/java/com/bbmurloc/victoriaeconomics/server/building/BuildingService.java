package com.bbmurloc.victoriaeconomics.server.building;

import java.util.UUID;

public final class BuildingService {

    private final BuildingRegistry buildingRegistry;

    public BuildingService(BuildingRegistry buildingRegistry) {
        this.buildingRegistry = buildingRegistry;
    }

    public EconomicBuilding createBuilding(String buildingTypeId) {
        UUID buildingId = UUID.randomUUID();

        EconomicBuilding building =
                new EconomicBuilding(buildingId, buildingTypeId);

        buildingRegistry.add(building);

        return building;
    }
}