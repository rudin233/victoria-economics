package com.bbmurloc.victoriaeconomics.server.productionequipment.operation;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个尚未执行的生产设备操作请求。
 *
 * Operation 只描述“用户想做什么”，
 * 不负责判断当前是否允许执行，也不负责真正修改 Holding。
 *
 * 是否立即执行、是否排队、何时执行，
 * 由 ProductionEquipmentService 负责。
 */
public sealed interface ProductionEquipmentOperation
        permits ProductionEquipmentOperation.Install,
        ProductionEquipmentOperation.Uninstall,
        ProductionEquipmentOperation.Transfer,
        ProductionEquipmentOperation.RemoveUninstalled {

    int amount();

    /**
     * 判断此操作是否涉及指定建筑。
     *
     * 对普通 install / uninstall 来说只有一栋建筑；
     * Transfer 同时涉及 source 和 destination。
     */
    boolean involves(UUID buildingId);

    /**
     * 安装当前建筑仓库中的生产设备。
     */
    record Install(
            UUID buildingId,
            int amount
    ) implements ProductionEquipmentOperation {

        public Install {
            Objects.requireNonNull(
                    buildingId,
                    "buildingId cannot be null"
            );

            requirePositiveAmount(
                    amount,
                    "Install"
            );
        }

        @Override
        public boolean involves(UUID buildingId) {
            return this.buildingId.equals(
                    buildingId
            );
        }
    }

    /**
     * 将已安装设备卸载到当前建筑仓库。
     */
    record Uninstall(
            UUID buildingId,
            int amount
    ) implements ProductionEquipmentOperation {

        public Uninstall {
            Objects.requireNonNull(
                    buildingId,
                    "buildingId cannot be null"
            );

            requirePositiveAmount(
                    amount,
                    "Uninstall"
            );
        }

        @Override
        public boolean involves(UUID buildingId) {
            return this.buildingId.equals(
                    buildingId
            );
        }
    }

    /**
     * 将未安装设备从一栋建筑转移到另一栋建筑。
     */
    record Transfer(
            UUID sourceBuildingId,
            UUID destinationBuildingId,
            int amount
    ) implements ProductionEquipmentOperation {

        public Transfer {
            Objects.requireNonNull(
                    sourceBuildingId,
                    "sourceBuildingId cannot be null"
            );

            Objects.requireNonNull(
                    destinationBuildingId,
                    "destinationBuildingId cannot be null"
            );

            requirePositiveAmount(
                    amount,
                    "Transfer"
            );

            if (sourceBuildingId.equals(
                    destinationBuildingId
            )) {
                throw new IllegalArgumentException(
                        "Source building and destination building cannot be the same"
                );
            }
        }

        @Override
        public boolean involves(UUID buildingId) {
            return sourceBuildingId.equals(
                    buildingId
            ) || destinationBuildingId.equals(
                    buildingId
            );
        }
    }

    /**
     * 从建筑中移除未安装设备。
     *
     * 以后可以被出售、报废等业务流程使用。
     */
    record RemoveUninstalled(
            UUID buildingId,
            int amount
    ) implements ProductionEquipmentOperation {

        public RemoveUninstalled {
            Objects.requireNonNull(
                    buildingId,
                    "buildingId cannot be null"
            );

            requirePositiveAmount(
                    amount,
                    "RemoveUninstalled"
            );
        }

        @Override
        public boolean involves(UUID buildingId) {
            return this.buildingId.equals(
                    buildingId
            );
        }
    }

    private static void requirePositiveAmount(
            int amount,
            String operationName
    ) {
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    operationName
                            + " amount must be positive"
            );
        }
    }
}