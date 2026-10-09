package com.bbmurloc.victoriaeconomics.server.production.domain;

public final class EquipmentCapacityCalculator {

    private EquipmentCapacityCalculator() {
    }

    public static double calculate(
            int installedEquipment,
            int maxEquipment
    ) {
        if (maxEquipment <= 0) {
            throw new IllegalArgumentException(
                    "maxEquipment must be positive"
            );
        }

        if (installedEquipment <= 0) {
            return 0.0;
        }

        double capacity =
                (double) installedEquipment
                        / maxEquipment;

        return Math.min(capacity, 1.0);
    }
}
