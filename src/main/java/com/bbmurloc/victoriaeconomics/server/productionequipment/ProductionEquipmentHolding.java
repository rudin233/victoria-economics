package com.bbmurloc.victoriaeconomics.server.productionequipment;

import java.util.Objects;
import java.util.UUID;

/**
 * 表示某栋建筑当前持有的生产设备。
 *
 * 第一版规则：
 * 1. 一栋建筑最多只有一个 ProductionEquipmentHolding。
 * 2. 一栋建筑只对应一种 ProductionEquipmentType。
 * 3. totalQuantity = installedQuantity + uninstalledQuantity。
 * 4. 0 <= installedQuantity <= totalQuantity。
 *
 * maxProductionEquipment 不属于 Holding 自身约束，
 * 它来自 BuildingTypeDefinition，由 ProductionEquipmentService 检查。
 */
public final class ProductionEquipmentHolding {

    private final UUID buildingId;
    private final String productionEquipmentTypeId;

    private int totalQuantity;
    private int installedQuantity;

    /**
     * 创建一个空的设备持有记录。
     */
    public ProductionEquipmentHolding(
            UUID buildingId,
            String productionEquipmentTypeId
    ) {
        this(
                buildingId,
                productionEquipmentTypeId,
                0,
                0
        );
    }

    /**
     * 创建一个指定状态的设备持有记录。
     *
     * 这个构造器以后也可以供 SQLite 加载使用。
     */
    public ProductionEquipmentHolding(
            UUID buildingId,
            String productionEquipmentTypeId,
            int totalQuantity,
            int installedQuantity
    ) {
        this.buildingId =
                Objects.requireNonNull(
                        buildingId,
                        "buildingId cannot be null"
                );

        this.productionEquipmentTypeId =
                Objects.requireNonNull(
                        productionEquipmentTypeId,
                        "productionEquipmentTypeId cannot be null"
                );

        validateQuantities(
                totalQuantity,
                installedQuantity
        );

        this.totalQuantity = totalQuantity;
        this.installedQuantity = installedQuantity;
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    public String getProductionEquipmentTypeId() {
        return productionEquipmentTypeId;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getInstalledQuantity() {
        return installedQuantity;
    }

    public int getUninstalledQuantity() {
        return totalQuantity - installedQuantity;
    }

    /*
     * 下面这些修改方法暂时使用 package-private。
     *
     * 也就是说 server.productionequipment 包外面的代码
     * 不能直接修改 Holding。
     *
     * 下一阶段 ProductionEquipmentService 会成为正式业务入口。
     */

    void addQuantity(int amount) {
        requirePositiveAmount(
                amount,
                "addQuantity"
        );

        totalQuantity += amount;
    }

    void removeUninstalledQuantity(int amount) {
        requirePositiveAmount(
                amount,
                "removeUninstalledQuantity"
        );

        if (amount > getUninstalledQuantity()) {
            throw new IllegalArgumentException(
                    "Cannot remove "
                            + amount
                            + " production equipment because only "
                            + getUninstalledQuantity()
                            + " uninstalled equipment is available"
            );
        }

        totalQuantity -= amount;
    }

    void install(int amount) {
        requirePositiveAmount(
                amount,
                "install"
        );

        if (amount > getUninstalledQuantity()) {
            throw new IllegalArgumentException(
                    "Cannot install "
                            + amount
                            + " production equipment because only "
                            + getUninstalledQuantity()
                            + " uninstalled equipment is available"
            );
        }

        installedQuantity += amount;
    }

    void uninstall(int amount) {
        requirePositiveAmount(
                amount,
                "uninstall"
        );

        if (amount > installedQuantity) {
            throw new IllegalArgumentException(
                    "Cannot uninstall "
                            + amount
                            + " production equipment because only "
                            + installedQuantity
                            + " equipment is installed"
            );
        }

        installedQuantity -= amount;
    }

    private static void validateQuantities(
            int totalQuantity,
            int installedQuantity
    ) {
        if (totalQuantity < 0) {
            throw new IllegalArgumentException(
                    "totalQuantity cannot be negative"
            );
        }

        if (installedQuantity < 0) {
            throw new IllegalArgumentException(
                    "installedQuantity cannot be negative"
            );
        }

        if (installedQuantity > totalQuantity) {
            throw new IllegalArgumentException(
                    "installedQuantity cannot exceed totalQuantity"
            );
        }
    }

    private static void requirePositiveAmount(
            int amount,
            String operation
    ) {
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    operation + " amount must be positive"
            );
        }
    }
}