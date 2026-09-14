package com.bbmurloc.victoriaeconomics.server.building.department;

import java.util.Objects;

/**
 * 某一栋 EconomicBuilding 自己的人事部门。
 *
 * 当前第一阶段只保存 StaffingPlan。
 *
 * 以后这里还可以逐步增加：
 *
 * baseWage
 * hiringEnabled
 * recruitmentPolicy
 * firingPolicy
 *
 * 但 EmploymentRecord 不存放在这里。
 */
public final class HumanResourcesDepartment {

    private StaffingPlan staffingPlan;

    public HumanResourcesDepartment() {
        this(
                StaffingPlan.fullStaffing()
        );
    }

    public HumanResourcesDepartment(
            StaffingPlan staffingPlan
    ) {
        this.staffingPlan =
                Objects.requireNonNull(
                        staffingPlan
                );
    }

    public StaffingPlan getStaffingPlan() {
        return staffingPlan;
    }

    public void setStaffingPlan(
            StaffingPlan staffingPlan
    ) {
        this.staffingPlan =
                Objects.requireNonNull(
                        staffingPlan
                );
    }

    public void setTargetRatio(
            String occupationId,
            double targetRatio
    ) {
        this.staffingPlan =
                staffingPlan.withTargetRatio(
                        occupationId,
                        targetRatio
                );
    }
}