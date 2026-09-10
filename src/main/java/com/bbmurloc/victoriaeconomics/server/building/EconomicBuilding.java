package com.bbmurloc.victoriaeconomics.server.building;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomicBuilding {

    private final UUID id;
    private final String buildingTypeId;
    private BuildingStatus status;
    private final Map<String, String> selectedProductionMethods;

    public EconomicBuilding(
            UUID id,
            String buildingTypeId
    ) {
        this.id = id;
        this.buildingTypeId = buildingTypeId;
        this.status = BuildingStatus.ACTIVE;
        this.selectedProductionMethods = new HashMap<>();

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

    public String getSelectedProductionMethodId(String groupId) {
        return selectedProductionMethods.get(groupId);
    }

    public Map<String, String> getSelectedProductionMethods() {
        return Map.copyOf(selectedProductionMethods);
    }

    public void setSelectedProductionMethod(
            String groupId,
            String methodId
    ) {
        selectedProductionMethods.put(groupId, methodId);
    }
}
