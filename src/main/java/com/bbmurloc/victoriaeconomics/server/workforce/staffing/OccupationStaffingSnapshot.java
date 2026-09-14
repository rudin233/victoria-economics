package com.bbmurloc.victoriaeconomics.server.workforce.staffing;

import java.util.Objects;

/**
 * 某栋建筑中一个职业在某一时刻的 Staffing 快照。
 */
public record OccupationStaffingSnapshot(

        /**
         * 职业 ID。
         *
         * 例如：
         * laborer
         * machinist
         * engineer
         */
        String occupationId,

        /**
         * 当前生产配方在满编状态下需要的该职业人数。
         *
         * 来源：
         * ResolvedProductionRecipe.requiredWorkers()
         *
         * 例如：
         * laborer = 100
         */
        int requiredWorkers,

        /**
         * HR 对该职业设定的目标配置比例。
         *
         * 取值范围：
         * 0.0 ~ 1.0
         *
         * 例如：
         * 0.8 = 希望达到满编的 80%
         */
        double targetRatio,

        /**
         * 根据 HR 的 targetRatio 计算出的理想目标人数。
         *
         * 计算：
         * ceil(requiredWorkers * targetRatio)
         *
         * 例如：
         * requiredWorkers = 100
         * targetRatio = 0.8
         *
         * targetWorkers = 80
         */
        int targetWorkers,

        /**
         * 当前设备能力允许该职业最多有效配置的人数。
         *
         * 计算：
         * floor(equipmentCapacity * requiredWorkers)
         *
         * 例如：
         * requiredWorkers = 100
         * equipmentCapacity = 0.5
         *
         * equipmentLimitedMaximum = 50
         */
        int equipmentLimitedMaximum,

        /**
         * 当前实际可执行的招聘目标人数。
         *
         * 计算：
         * min(targetWorkers, equipmentLimitedMaximum)
         *
         * 例如：
         * HR 想要 80 人，
         * 但设备最多只允许 50 人，
         *
         * hiringTargetWorkers = 50
         */
        int hiringTargetWorkers,

        /**
         * 当前实际已经存在的该职业雇员人数。
         *
         * 来源：
         * EmploymentRegistry
         *
         * 注意：
         * 这是实际劳动关系数量，
         * 不考虑设备是否能够让所有人有效工作。
         */
        int employedWorkers,

        /**
         * 当前真正能够计入生产的人数。
         *
         * 计算：
         * min(employedWorkers, equipmentLimitedMaximum)
         *
         * 例如：
         * 已雇 80 人，
         * 设备只能支持 50 人，
         *
         * effectiveWorkers = 50
         */
        int effectiveWorkers,

        /**
         * 当前该职业的实际满足率。
         *
         * 计算：
         * effectiveWorkers / requiredWorkers
         *
         * 例如：
         * effectiveWorkers = 40
         * requiredWorkers = 100
         *
         * staffingRatio = 0.4
         *
         * 这个值会参与：
         * 1. 是否达到最低 10% 开工要求
         * 2. productionSpeed = min_i staffingRatio
         */
        double staffingRatio

) {
    public OccupationStaffingSnapshot {

        Objects.requireNonNull(
                occupationId,
                "occupationId cannot be null"
        );

        if (requiredWorkers <= 0) {
            throw new IllegalArgumentException(
                    "requiredWorkers must be positive"
            );
        }

        if (targetRatio < 0.0
                || targetRatio > 1.0) {

            throw new IllegalArgumentException(
                    "targetRatio must be between 0 and 1"
            );
        }

        if (targetWorkers < 0
                || equipmentLimitedMaximum < 0
                || hiringTargetWorkers < 0
                || employedWorkers < 0
                || effectiveWorkers < 0) {

            throw new IllegalArgumentException(
                    "Worker counts cannot be negative"
            );
        }

        if (staffingRatio < 0.0
                || staffingRatio > 1.0) {

            throw new IllegalArgumentException(
                    "staffingRatio must be between 0 and 1"
            );
        }
    }

    /**
     * HR 当前距离可执行招聘目标还缺多少人。
     */
    public int hiringShortfall() {
        return Math.max(
                0,
                hiringTargetWorkers
                        - employedWorkers
        );
    }

    /**
     * 当前人数是否高于 HR 的实际招聘目标。
     *
     * 这里只返回数字；
     * 是否真的裁员由以后 HR policy 决定。
     */
    public int workersAboveHiringTarget() {
        return Math.max(
                0,
                employedWorkers
                        - hiringTargetWorkers
        );
    }
}