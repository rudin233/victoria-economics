package com.bbmurloc.victoriaeconomics.server.building;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingRegistry {

    private final Map<UUID, EconomicBuilding> buildings = new HashMap<>();

    public void add(EconomicBuilding building) {
        if (buildings.putIfAbsent(building.getId(), building) != null)
            throw new IllegalStateException("Duplicate building: " + building.getId());
    }

    public java.util.List<EconomicBuilding> getAll() {
        return java.util.List.copyOf(buildings.values());
    }

    /**
     * Replaces an authority only with a reloaded durable checkpoint.
     */
    public void restore(EconomicBuilding building) {
        if (!buildings.containsKey(building.getId())) throw new IllegalArgumentException("Unknown restored building");
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
