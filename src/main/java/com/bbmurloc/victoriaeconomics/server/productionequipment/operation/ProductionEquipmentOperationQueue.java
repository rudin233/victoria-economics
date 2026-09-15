package com.bbmurloc.victoriaeconomics.server.productionequipment.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 等待执行的 ProductionEquipment 操作队列。
 *
 * Queue 只负责：
 *
 * - 保留提交顺序
 * - 保存 pending operations
 * - 查询某建筑涉及的 pending operations
 *
 * Queue 本身不负责：
 *
 * - 判断 Batch 是否 active
 * - 修改 ProductionEquipmentHolding
 * - 判断 BuildingType compatibility
 * - 判断安装上限
 *
 * 这些仍由 ProductionEquipmentService 负责。
 */
public final class ProductionEquipmentOperationQueue {

    private final List<ProductionEquipmentOperation> pendingOperations =
            new ArrayList<>();

    public void enqueue(
            ProductionEquipmentOperation operation
    ) {
        Objects.requireNonNull(
                operation,
                "operation cannot be null"
        );

        pendingOperations.add(
                operation
        );
    }

    public boolean isEmpty() {
        return pendingOperations.isEmpty();
    }

    public int size() {
        return pendingOperations.size();
    }

    public boolean hasPendingForBuilding(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        for (ProductionEquipmentOperation operation
                : pendingOperations) {

            if (operation.involves(
                    buildingId
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * 返回某栋建筑涉及的所有 pending operation。
     *
     * 返回副本，外部不能直接修改 Queue 内部 List。
     */
    public List<ProductionEquipmentOperation> getForBuilding(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        List<ProductionEquipmentOperation> result =
                new ArrayList<>();

        for (ProductionEquipmentOperation operation
                : pendingOperations) {

            if (operation.involves(
                    buildingId
            )) {
                result.add(
                        operation
                );
            }
        }

        return List.copyOf(
                result
        );
    }

    /**
     * 返回整个 Queue 的不可修改快照。
     */
    public List<ProductionEquipmentOperation> getAll() {
        return List.copyOf(
                pendingOperations
        );
    }

    /**
     * 操作真正成功执行以后，
     * 由 ProductionEquipmentService 从队列删除。
     */
    public boolean remove(
            ProductionEquipmentOperation operation
    ) {
        Objects.requireNonNull(
                operation,
                "operation cannot be null"
        );

        return pendingOperations.remove(
                operation
        );
    }
}