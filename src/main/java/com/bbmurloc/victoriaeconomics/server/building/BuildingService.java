package com.bbmurloc.victoriaeconomics.server.building;

import java.util.UUID;

public final class BuildingService {

    private final BuildingRegistry buildingRegistry;
    private final BuildingRepository buildingRepository;

    public BuildingService(
            BuildingRegistry buildingRegistry,
            BuildingRepository buildingRepository
    ) {
        this.buildingRegistry = buildingRegistry;
        this.buildingRepository = buildingRepository;
    }

    public EconomicBuilding createBuilding(String buildingTypeId) {
        UUID buildingId = UUID.randomUUID();

        EconomicBuilding building =
                new EconomicBuilding(buildingId, buildingTypeId);

        buildingRepository.save(building);

        buildingRegistry.add(building);

        return building;
    }

    public boolean stopBuilding(UUID buildingId) {
        EconomicBuilding building =
                buildingRegistry.get(buildingId);

        if (building == null) {
            return false;
        }

        building.setStatus(BuildingStatus.STOPPED);

        buildingRepository.save(building);

        return true;
    }
}