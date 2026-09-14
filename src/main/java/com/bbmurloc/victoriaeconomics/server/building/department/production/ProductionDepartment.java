package com.bbmurloc.victoriaeconomics.server.building.department.production;

import com.bbmurloc.victoriaeconomics.server.production.batch.ProductionBatch;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ProductionDepartment {

    /**
     * 当前生产部门选择的 PM。
     *
     * groupId -> methodId
     *
     * 表示“下一批生产准备采用的生产方式配置”。
     *
     * 如果当前已经有 active batch，
     * 修改这里不会影响已经开始的批次。
     */
    private final Map<String, String> selectedProductionMethods =
            new HashMap<>();

    /**
     * 当前建筑安装的设备数量。
     *
     * BuildingService 负责保证：
     *
     * 0 <= currentEquipment <= maxEquipment
     */
    private int currentEquipment;

    /**
     * 当前正在执行的生产批次。
     *
     * null 表示当前没有 active batch。
     */
    private ProductionBatch activeBatch;

    public ProductionDepartment() {
        this.currentEquipment = 0;
        this.activeBatch = null;
    }

    public String getSelectedProductionMethodId(
            String groupId
    ) {
        return selectedProductionMethods.get(
                groupId
        );
    }

    public Map<String, String> getSelectedProductionMethods() {
        return Map.copyOf(
                selectedProductionMethods
        );
    }

    public void setSelectedProductionMethod(
            String groupId,
            String methodId
    ) {
        Objects.requireNonNull(
                groupId,
                "groupId cannot be null"
        );

        Objects.requireNonNull(
                methodId,
                "methodId cannot be null"
        );

        selectedProductionMethods.put(
                groupId,
                methodId
        );
    }

    public int getCurrentEquipment() {
        return currentEquipment;
    }

    public void setCurrentEquipment(
            int currentEquipment
    ) {
        if (currentEquipment < 0) {
            throw new IllegalArgumentException(
                    "currentEquipment cannot be negative"
            );
        }

        this.currentEquipment =
                currentEquipment;
    }

    public ProductionBatch getActiveBatch() {
        return activeBatch;
    }

    public boolean hasActiveBatch() {
        return activeBatch != null
                && activeBatch.isActive();
    }

    /**
     * 开始一个新的批次。
     *
     * 一栋建筑同一时刻最多有一个 active batch。
     */
    public void startBatch(
            ProductionBatch batch
    ) {
        Objects.requireNonNull(
                batch,
                "batch cannot be null"
        );

        if (activeBatch != null) {
            throw new IllegalStateException(
                    "Production department already contains a production batch"
            );
        }

        this.activeBatch = batch;
    }

    /**
     * 清除已经结束的批次。
     *
     * active batch 不能直接清除。
     */
    public void clearFinishedBatch() {
        if (activeBatch == null) {
            return;
        }

        if (activeBatch.isActive()) {
            throw new IllegalStateException(
                    "Cannot clear an active production batch"
            );
        }

        activeBatch = null;
    }
}