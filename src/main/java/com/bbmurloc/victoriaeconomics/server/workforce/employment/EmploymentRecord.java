package com.bbmurloc.victoriaeconomics.server.workforce.employment;

import java.util.Objects;
import java.util.UUID;

/**
 * 一条当前有效的雇佣关系。
 *
 * employeeId:
 *     当前被雇佣的经济 NPC。
 *
 * buildingId:
 *     NPC 当前工作的经济建筑。
 *
 * occupationId:
 *     NPC 在该建筑中担任的职业。
 */
public record EmploymentRecord(
        UUID employeeId,
        UUID buildingId,
        String occupationId
) {
    public EmploymentRecord {
        Objects.requireNonNull(
                employeeId,
                "employeeId cannot be null"
        );

        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        Objects.requireNonNull(
                occupationId,
                "occupationId cannot be null"
        );
    }
}