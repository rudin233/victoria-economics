package com.bbmurloc.victoriaeconomics.server.production;

import com.bbmurloc.victoriaeconomics.server.staffing.StaffingSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 某一生产批次开始瞬间冻结下来的生产条件。
 *
 * 创建以后不可修改。
 *
 * 建筑实时 PM、设备、Employment 后续发生变化，
 * 都不会改变这份 Configuration。
 */
public record ProductionBatchConfiguration(

        /**
         * 批次开始时锁定的：
         *
         * PMG -> PM
         */
        Map<String, String> productionMethodSelections,

        /**
         * 批次开始时解析完成的最终生产配方。
         *
         * 输入、输出、职业需求全部锁定。
         */
        ResolvedProductionRecipe resolvedRecipe,

        /**
         * 批次开始时的设备能力：
         *
         * e = currentEquipment / maxEquipment
         */
        double equipmentCapacity,

        /**
         * 批次开始时的人员配置快照。
         *
         * 其中 productionSpeed 也被锁定。
         */
        StaffingSnapshot staffingSnapshot,

        /**
         * 这一批真正参与劳动的员工。
         *
         * occupationId -> employeeIds
         *
         * 以后用于：
         * 工资结算
         * 实际劳动量记录
         * 中止批次结算
         */
        Map<String, List<UUID>> activeEmployeesByOccupation

) {
    public ProductionBatchConfiguration {

        Objects.requireNonNull(
                productionMethodSelections,
                "productionMethodSelections cannot be null"
        );

        Objects.requireNonNull(
                resolvedRecipe,
                "resolvedRecipe cannot be null"
        );

        Objects.requireNonNull(
                staffingSnapshot,
                "staffingSnapshot cannot be null"
        );

        Objects.requireNonNull(
                activeEmployeesByOccupation,
                "activeEmployeesByOccupation cannot be null"
        );

        if (equipmentCapacity < 0.0
                || equipmentCapacity > 1.0) {

            throw new IllegalArgumentException(
                    "equipmentCapacity must be between 0 and 1"
            );
        }

        productionMethodSelections =
                Map.copyOf(
                        productionMethodSelections
                );

        activeEmployeesByOccupation =
                copyEmployeeMap(
                        activeEmployeesByOccupation
                );
    }

    private static Map<String, List<UUID>>
    copyEmployeeMap(
            Map<String, List<UUID>> source
    ) {
        Map<String, List<UUID>> copy =
                new HashMap<>();

        for (Map.Entry<String, List<UUID>> entry
                : source.entrySet()) {

            copy.put(
                    entry.getKey(),
                    List.copyOf(
                            entry.getValue()
                    )
            );
        }

        return Map.copyOf(
                copy
        );
    }

    public double productionSpeed() {
        return staffingSnapshot
                .productionSpeed();
    }
}