package com.bbmurloc.victoriaeconomics.server.building;

import java.util.Objects;
import java.util.UUID;

/** Building identity and operating status only; production aggregates are independently persisted. */
public final class EconomicBuilding {
    private final UUID id;
    private final String buildingTypeId;
    private BuildingStatus status = BuildingStatus.ACTIVE;

    public EconomicBuilding(UUID id, String buildingTypeId) {
        this.id = Objects.requireNonNull(id);
        this.buildingTypeId = Objects.requireNonNull(buildingTypeId);
    }
    public UUID getId() { return id; }
    public String getBuildingTypeId() { return buildingTypeId; }
    public BuildingStatus getStatus() { return status; }
    public void setStatus(BuildingStatus status) { this.status = Objects.requireNonNull(status); }
}
