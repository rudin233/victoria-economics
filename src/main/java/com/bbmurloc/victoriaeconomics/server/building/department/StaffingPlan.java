package com.bbmurloc.victoriaeconomics.server.building.department;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 人事部门希望达到的员工配置。
 *
 * targetRatios:
 * occupationId -> 目标满编比例
 *
 * 例如：
 *
 * laborer   -> 0.8
 * machinist -> 1.0
 * engineer  -> 0.5
 *
 * 未明确配置的职业默认目标为 100%。
 */
public record StaffingPlan(
        Map<String, Double> targetRatios
) {

    public static final double DEFAULT_TARGET_RATIO =
            1.0;

    public StaffingPlan {
        Objects.requireNonNull(
                targetRatios,
                "targetRatios cannot be null"
        );

        for (Map.Entry<String, Double> entry
                : targetRatios.entrySet()) {

            Objects.requireNonNull(
                    entry.getKey(),
                    "occupationId cannot be null"
            );

            Objects.requireNonNull(
                    entry.getValue(),
                    "target ratio cannot be null"
            );

            double ratio =
                    entry.getValue();

            if (ratio < 0.0 || ratio > 1.0) {
                throw new IllegalArgumentException(
                        "Staffing target ratio must be between 0 and 1: "
                                + ratio
                );
            }
        }

        targetRatios =
                Map.copyOf(targetRatios);
    }

    /**
     * 默认所有职业都以满编为目标。
     *
     * 空 Map 不代表“0%”，
     * 而表示没有特殊覆盖值。
     */
    public static StaffingPlan fullStaffing() {
        return new StaffingPlan(
                Map.of()
        );
    }
//稀疏配置（sparse configuration）
    public double targetRatio(
            String occupationId
    ) {
        Objects.requireNonNull(
                occupationId,
                "occupationId cannot be null"
        );

        return targetRatios.getOrDefault(
                occupationId,
                DEFAULT_TARGET_RATIO
        );
    }

    /**
     * StaffingPlan 是 immutable record，
     * 修改一个职业的目标时返回一份新的 Plan。
     */
    public StaffingPlan withTargetRatio(
            String occupationId,
            double targetRatio
    ) {
        Objects.requireNonNull(
                occupationId,
                "occupationId cannot be null"
        );

        if (targetRatio < 0.0
                || targetRatio > 1.0) {

            throw new IllegalArgumentException(
                    "Staffing target ratio must be between 0 and 1"
            );
        }

        Map<String, Double> newTargets =
                new HashMap<>(
                        targetRatios
                );

        newTargets.put(
                occupationId,
                targetRatio
        );

        return new StaffingPlan(
                newTargets
        );
    }
}