package com.bbmurloc.victoriaeconomics.server.employment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EmploymentRegistry {

    /**
     * 唯一事实索引：
     *
     * employeeId -> EmploymentRecord
     *
     * 这也天然保证：
     * 一个员工同一时刻只能拥有一份 active employment。
     */
    private final Map<UUID, EmploymentRecord> byEmployee =
            new HashMap<>();

    /**
     * 查询索引：
     *
     * buildingId
     *      -> occupationId
     *          -> employeeIds
     *
     * 用于快速查询：
     * “某栋建筑有多少 laborer？”
     */
    private final Map<UUID, Map<String, Set<UUID>>> byBuilding =
            new HashMap<>();

    public void add(EmploymentRecord record) {

        if (byEmployee.containsKey(
                record.employeeId()
        )) {
            throw new IllegalStateException(
                    "Employee is already employed: "
                            + record.employeeId()
            );
        }

        byEmployee.put(
                record.employeeId(),
                record
        );

        byBuilding
                .computeIfAbsent(
                        record.buildingId(),
                        ignored -> new HashMap<>()
                )
                .computeIfAbsent(
                        record.occupationId(),
                        ignored -> new HashSet<>()
                )
                .add(
                        record.employeeId()
                );
    }

    public EmploymentRecord getByEmployee(
            UUID employeeId
    ) {
        return byEmployee.get(
                employeeId
        );
    }

    public boolean isEmployed(
            UUID employeeId
    ) {
        return byEmployee.containsKey(
                employeeId
        );
    }

    /**
     * 查询某栋建筑某职业的员工数量。
     */
    public int count(
            UUID buildingId,
            String occupationId
    ) {
        Map<String, Set<UUID>> occupations =
                byBuilding.get(buildingId);

        if (occupations == null) {
            return 0;
        }

        Set<UUID> employees =
                occupations.get(occupationId);

        if (employees == null) {
            return 0;
        }

        return employees.size();
    }

    /**
     * 返回某栋建筑当前所有职业的人数快照。
     *
     * 例如：
     *
     * {
     *     laborer   = 50,
     *     machinist = 10
     * }
     */
    public Map<String, Integer> getStaffingCounts(
            UUID buildingId
    ) {
        Map<String, Set<UUID>> occupations =
                byBuilding.get(buildingId);

        if (occupations == null) {
            return Map.of();
        }

        Map<String, Integer> result =
                new HashMap<>();

        for (Map.Entry<String, Set<UUID>> entry
                : occupations.entrySet()) {

            result.put(
                    entry.getKey(),
                    entry.getValue().size()
            );
        }

        return Map.copyOf(
                result
        );
    }

    /**
     * 删除一条 active employment。
     */
    public EmploymentRecord remove(
            UUID employeeId
    ) {
        EmploymentRecord record =
                byEmployee.remove(
                        employeeId
                );

        if (record == null) {
            return null;
        }

        Map<String, Set<UUID>> occupations =
                byBuilding.get(
                        record.buildingId()
                );

        if (occupations != null) {

            Set<UUID> employees =
                    occupations.get(
                            record.occupationId()
                    );

            if (employees != null) {

                employees.remove(
                        employeeId
                );

                if (employees.isEmpty()) {
                    occupations.remove(
                            record.occupationId()
                    );
                }
            }

            if (occupations.isEmpty()) {
                byBuilding.remove(
                        record.buildingId()
                );
            }
        }

        return record;
    }
}