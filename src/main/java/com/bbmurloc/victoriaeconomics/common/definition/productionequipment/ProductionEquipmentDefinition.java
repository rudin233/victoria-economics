package com.bbmurloc.victoriaeconomics.common.definition.productionequipment;

import java.util.Objects;

/**
 * 一种生产设备的静态定义。
 *
 * productionEquipmentTypeId 表示设备“类型”的稳定 ID，
 * 例如：
 *
 * tooling_workshop_equipment
 * iron_mine_equipment
 * logging_camp_equipment
 *
 * 它不是某个运行时设备资产的 ID。
 */
public record ProductionEquipmentDefinition(
        String productionEquipmentTypeId
) {
    public ProductionEquipmentDefinition {
        Objects.requireNonNull(
                productionEquipmentTypeId,
                "productionEquipmentTypeId cannot be null"
        );
    }
}