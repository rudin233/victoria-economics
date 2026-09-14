package com.bbmurloc.victoriaeconomics.server.workforce.staffing;

import java.util.Map;
import java.util.Objects;

/**
 * 某栋建筑当前整体人员配置的只读计算结果。
 */
public record StaffingSnapshot(
        double equipmentCapacity,
        Map<String, OccupationStaffingSnapshot> occupations,
        boolean canStart,
        double productionSpeed
) {
    public StaffingSnapshot {

        Objects.requireNonNull(
                occupations,
                "occupations cannot be null"
        );

        occupations =
                Map.copyOf(
                        occupations
                );

        if (equipmentCapacity < 0.0
                || equipmentCapacity > 1.0) {

            throw new IllegalArgumentException(
                    "equipmentCapacity must be between 0 and 1"
            );
        }

        if (productionSpeed < 0.0
                || productionSpeed > 1.0) {

            throw new IllegalArgumentException(
                    "productionSpeed must be between 0 and 1"
            );
        }
    }
}