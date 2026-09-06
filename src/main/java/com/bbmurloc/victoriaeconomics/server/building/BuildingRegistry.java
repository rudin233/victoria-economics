package com.bbmurloc.victoriaeconomics.server.building;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingRegistry {

    private final Map<UUID, EconomicBuilding> buildings = new HashMap<>();

    public void add(EconomicBuilding building) {
        buildings.put(building.getId(), building);
    }

    public EconomicBuilding get(UUID id) {
        return buildings.get(id);
    }

    public boolean contains(UUID id) {
        return buildings.containsKey(id);
    }

    public void remove(UUID id) {
        buildings.remove(id);
    }
}
