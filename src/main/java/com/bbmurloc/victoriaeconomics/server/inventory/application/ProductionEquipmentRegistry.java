package com.bbmurloc.victoriaeconomics.server.inventory.application;

import java.util.*;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.ProductionEquipmentHolding;

/**
 * A query index for Inventory & Storage equipment aggregates.
 */
public final class ProductionEquipmentRegistry {
    private final Map<UUID, ProductionEquipmentHolding> byBuilding = new HashMap<>();

    public void register(ProductionEquipmentHolding holding) {
        if (byBuilding.putIfAbsent(holding.getBuildingId(), holding) != null)
            throw new IllegalStateException("Duplicate equipment holding");
    }

    public void replace(ProductionEquipmentHolding holding) {
        byBuilding.put(holding.getBuildingId(), holding);
    }

    public ProductionEquipmentHolding get(UUID building) {
        return byBuilding.get(building);
    }

    public int getInstalledQuantity(UUID building) {
        var holding = get(building);
        return holding == null ? 0 : holding.getInstalledQuantity();
    }

    public int getTotalQuantity(UUID building) {
        var holding = get(building);
        return holding == null ? 0 : holding.getTotalQuantity();
    }

    public int getUninstalledQuantity(UUID building) {
        var holding = get(building);
        return holding == null ? 0 : holding.getUninstalledQuantity();
    }

    public List<ProductionEquipmentHolding> getAll() {
        return List.copyOf(byBuilding.values());
    }
}
