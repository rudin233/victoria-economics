package com.bbmurloc.victoriaeconomics.server.production.calculation;

public final class EquipmentCapacityCalculator {

    private EquipmentCapacityCalculator() {
    }

    public static double calculate(
            int currentEquipment,
            int maxEquipment
    ) {
        if (maxEquipment <= 0) {
            throw new IllegalArgumentException(
                    "maxEquipment must be positive"
            );
        }

        if (currentEquipment <= 0) {
            return 0.0;
        }

        double capacity =
                (double) currentEquipment
                        / maxEquipment;

        return Math.min(capacity, 1.0);
    }
}