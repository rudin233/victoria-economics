package com.bbmurloc.victoriaeconomics.server.building;

import java.util.UUID;

public class EconomicBuilding {

    private final UUID id;
    private final String buildingTypeId;
    private BuildingStatus status;

    public EconomicBuilding(
            UUID id,
            String buildingTypeId
    ) {
        this.id = id;
        this.buildingTypeId = buildingTypeId;
        this.status = BuildingStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getBuildingTypeId() {
        return buildingTypeId;
    }

    public BuildingStatus getStatus() {
        return status;
    }

    public void setStatus(BuildingStatus status) {
        this.status = status;
    }
}
