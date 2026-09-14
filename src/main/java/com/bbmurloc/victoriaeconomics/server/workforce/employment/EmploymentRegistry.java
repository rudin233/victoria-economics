package com.bbmurloc.victoriaeconomics.server.workforce.employment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EmploymentRegistry {

    /**
     * 主索引：
     *
     * employeeId -> EmploymentRecord
     *
     * 用来快速回答：
     * “某个 NPC 当前在哪里工作？”
     *
     * 同时天然保证：
     * 一个 employeeId 同一时刻最多只有一份 active employment。
     */
    private final Map<UUID, EmploymentRecord> byEmployee =
            new HashMap<>();

    /**
     * 建筑侧查询索引：
     *
     * buildingId
     *      -> occupationId
     *          -> employeeIds
     *
     * 例如：
     *
     * Building A
     *   laborer
     *      NPC 1
     *      NPC 2
     *      NPC 3
     *
     *   machinist
     *      NPC 4
     *      NPC 5
     *
     * 用来快速回答：
     *
     * 1. 某建筑有多少某职业员工？
     * 2. 某建筑某职业具体有哪些员工？
     *
     * ProductionBatch 锁定实际参与员工时
     * 也会使用这个索引。
     */
    private final Map<UUID, Map<String, Set<UUID>>> byBuilding =
            new HashMap<>();

    /**
     * 添加一条 active employment。
     */
    public void add(
            EmploymentRecord record
    ) {
        if (byEmployee.containsKey(
                record.employeeId()
        )) {
            throw new IllegalStateException(
                    "Employee is already employed: "
                            + record.employeeId()
            );
        }

        /*
         * 写入主索引。
         */
        byEmployee.put(
                record.employeeId(),
                record
        );

        /*
         * 同步写入建筑侧索引。
         */
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

    /**
     * 根据 employeeId 查询当前 EmploymentRecord。
     */
    public EmploymentRecord getByEmployee(
            UUID employeeId
    ) {
        return byEmployee.get(
                employeeId
        );
    }

    /**
     * 判断某个 NPC 当前是否已经就业。
     */
    public boolean isEmployed(
            UUID employeeId
    ) {
        return byEmployee.containsKey(
                employeeId
        );
    }

    /**
     * 查询某栋建筑某职业当前有多少员工。
     */
    public int count(
            UUID buildingId,
            String occupationId
    ) {
        Map<String, Set<UUID>> occupations =
                byBuilding.get(
                        buildingId
                );

        if (occupations == null) {
            return 0;
        }

        Set<UUID> employees =
                occupations.get(
                        occupationId
                );

        if (employees == null) {
            return 0;
        }

        return employees.size();
    }

    /**
     * 返回某栋建筑当前各职业的人数快照。
     *
     * 例如：
     *
     * {
     *     laborer = 5,
     *     machinist = 2
     * }
     */
    public Map<String, Integer> getStaffingCounts(
            UUID buildingId
    ) {
        Map<String, Set<UUID>> occupations =
                byBuilding.get(
                        buildingId
                );

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
     * 返回某栋建筑某职业的所有 employeeId。
     *
     * ProductionService 在生产批次开始时，
     * 会用它锁定本批真正参与劳动的 NPC。
     *
     * 返回 Set.copyOf(...)，
     * 防止调用方直接修改 Registry 内部的 Set。
     */
    public Set<UUID> getEmployeeIds(
            UUID buildingId,
            String occupationId
    ) {
        Map<String, Set<UUID>> occupations =
                byBuilding.get(
                        buildingId
                );

        if (occupations == null) {
            return Set.of();
        }

        Set<UUID> employees =
                occupations.get(
                        occupationId
                );

        if (employees == null) {
            return Set.of();
        }

        return Set.copyOf(
                employees
        );
    }

    /**
     * 删除某个 NPC 当前的 active employment。
     */
    public EmploymentRecord remove(
            UUID employeeId
    ) {
        /*
         * 先从主索引删除。
         */
        EmploymentRecord record =
                byEmployee.remove(
                        employeeId
                );

        if (record == null) {
            return null;
        }

        /*
         * 再同步清理建筑侧索引。
         */
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

                /*
                 * 某职业已经没人了，
                 * 把空 Set 对应的 occupation key 一起删掉。
                 */
                if (employees.isEmpty()) {
                    occupations.remove(
                            record.occupationId()
                    );
                }
            }

            /*
             * 这栋建筑已经没有任何员工，
             * 把 buildingId 对应的空 Map 也删掉。
             */
            if (occupations.isEmpty()) {
                byBuilding.remove(
                        record.buildingId()
                );
            }
        }

        return record;
    }
}