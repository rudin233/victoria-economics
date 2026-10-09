package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.server.building.department.hr.HumanResourcesDepartment;
import com.bbmurloc.victoriaeconomics.server.building.department.production.ProductionDepartment;

import java.util.Objects;
import java.util.UUID;

public final class EconomicBuilding {

    /**
     * 经济建筑稳定身份。
     */
    private final UUID id;

    /**
     * 建筑类型定义 ID。
     */
    private final String buildingTypeId;

    /**
     * 整栋建筑级运行状态。
     */
    private BuildingStatus status;

    /**
     * 人事部门。
     */
    private final HumanResourcesDepartment humanResourcesDepartment;

    /**
     * 生产部门。
     * <p>
     * 生产方式和批次执行由该部门负责。
     * 设备实际数量、材料数量和锁定属于 Inventory & Storage。
     */
    private final ProductionDepartment productionDepartment;

    public EconomicBuilding(
            UUID id,
            String buildingTypeId
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "id cannot be null"
                );

        this.buildingTypeId =
                Objects.requireNonNull(
                        buildingTypeId,
                        "buildingTypeId cannot be null"
                );

        this.status =
                BuildingStatus.ACTIVE;

        this.humanResourcesDepartment =
                new HumanResourcesDepartment();

        this.productionDepartment =
                new ProductionDepartment(id);
    }

    public UUID getId() {
        return id;
    }

    public String getBuildingTypeId() {
        return buildingTypeId;
    }

    public BuildingStatus getStatus() {
        return status;
    }

    public void setStatus(
            BuildingStatus status
    ) {
        this.status =
                Objects.requireNonNull(
                        status,
                        "status cannot be null"
                );
    }

    public HumanResourcesDepartment
    getHumanResourcesDepartment() {

        return humanResourcesDepartment;
    }

    public ProductionDepartment
    getProductionDepartment() {

        return productionDepartment;
    }
}
