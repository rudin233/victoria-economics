package com.bbmurloc.victoriaeconomics.server.building.department.hr;

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

    private StaffingExpectation staffingExpectation;

    public HumanResourcesDepartment() {
        this(
                StaffingExpectation.fullStaffing()
        );
    }

    public HumanResourcesDepartment(
            StaffingExpectation staffingExpectation
    ) {
        this.staffingExpectation =
                Objects.requireNonNull(
                        staffingExpectation
                );
    }

    public StaffingExpectation getStaffingPlan() {
        return staffingExpectation;
    }

    public void setStaffingPlan(
            StaffingExpectation staffingExpectation
    ) {
        this.staffingExpectation =
                Objects.requireNonNull(
                        staffingExpectation
                );
    }

    public void setTargetRatio(
            String occupationId,
            double targetRatio
    ) {
        this.staffingExpectation =
                staffingExpectation.withTargetRatio(
                        occupationId,
                        targetRatio
                );
    }
}