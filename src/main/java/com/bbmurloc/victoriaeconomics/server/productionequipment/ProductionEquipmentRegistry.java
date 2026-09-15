package com.bbmurloc.victoriaeconomics.server.productionequipment;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行时生产设备持有记录的索引。
 *
 * 第一版规则：
 * 一个 buildingId 最多对应一个 ProductionEquipmentHolding。
 */
public final class ProductionEquipmentRegistry {

    private final Map<UUID, ProductionEquipmentHolding> byBuilding =
            new HashMap<>();

    public void register(
            ProductionEquipmentHolding holding
    ) {
        Objects.requireNonNull(
                holding,
                "holding cannot be null"
        );

        UUID buildingId =
                holding.getBuildingId();

        if (byBuilding.containsKey(buildingId)) {
            throw new IllegalArgumentException(
                    "Production equipment holding already exists for building: "
                            + buildingId
            );
        }

        byBuilding.put(
                buildingId,
                holding
        );
    }

    public ProductionEquipmentHolding get(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        return byBuilding.get(buildingId);
    }

    public boolean contains(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        return byBuilding.containsKey(buildingId);
    }

    public int getTotalQuantity(
            UUID buildingId
    ) {
        ProductionEquipmentHolding holding =
                get(buildingId);

        if (holding == null) {
            return 0;
        }

        return holding.getTotalQuantity();
    }

    public int getInstalledQuantity(
            UUID buildingId
    ) {
        ProductionEquipmentHolding holding =
                get(buildingId);

        if (holding == null) {
            return 0;
        }

        return holding.getInstalledQuantity();
    }

    public int getUninstalledQuantity(
            UUID buildingId
    ) {
        ProductionEquipmentHolding holding =
                get(buildingId);

        if (holding == null) {
            return 0;
        }

        return holding.getUninstalledQuantity();
    }

    public Collection<ProductionEquipmentHolding> getAll() {
        return List.copyOf(
                byBuilding.values()
        );
    }

    public ProductionEquipmentHolding remove(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        return byBuilding.remove(buildingId);
    }
}