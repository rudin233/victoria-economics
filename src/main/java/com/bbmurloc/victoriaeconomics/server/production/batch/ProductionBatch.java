package com.bbmurloc.victoriaeconomics.server.production.batch;

import java.util.Objects;
import java.util.UUID;

public final class ProductionBatch {

    public enum Status {
        ACTIVE,
        COMPLETED,
        ABORTED
    }

    private static final double EPSILON =
            1.0E-9;

    private final UUID id;

    private final UUID buildingId;

    private final ProductionBatchConfiguration configuration;

    /**
     * 生产进度：
     *
     * 0.0 ~ 1.0
     */
    private double progress;

    private Status status;

    public ProductionBatch(
            UUID id,
            UUID buildingId,
            ProductionBatchConfiguration configuration
    ) {
        this.id =
                Objects.requireNonNull(
                        id
                );

        this.buildingId =
                Objects.requireNonNull(
                        buildingId
                );

        this.configuration =
                Objects.requireNonNull(
                        configuration
                );

        if (!configuration
                .staffingSnapshot()
                .canStart()) {

            throw new IllegalArgumentException(
                    "Cannot create production batch from staffing configuration that cannot start"
            );
        }

        this.progress = 0.0;
        this.status = Status.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    public ProductionBatchConfiguration
    getConfiguration() {
        return configuration;
    }

    public double getProgress() {
        return progress;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public boolean isAborted() {
        return status == Status.ABORTED;
    }

    /**
     * 推进生产进度。
     *
     * fullSpeedProgressDelta 表示：
     *
     * 如果生产速度为 100%，
     * 本次应推进多少标准进度。
     *
     * 实际推进：
     *
     * delta
     * = fullSpeedProgressDelta
     *   * locked productionSpeed
     *
     * EconomicClock 接入后，
     * 会负责计算 fullSpeedProgressDelta。
     */
    public void advance(
            double fullSpeedProgressDelta
    ) {
        if (!isActive()) {
            throw new IllegalStateException(
                    "Production batch is not active"
            );
        }

        if (fullSpeedProgressDelta < 0.0) {
            throw new IllegalArgumentException(
                    "Progress delta cannot be negative"
            );
        }

        double actualProgressDelta =
                fullSpeedProgressDelta
                        * configuration
                        .productionSpeed();

        progress =
                Math.min(
                        1.0,
                        progress
                                + actualProgressDelta
                );

        if (progress
                + EPSILON
                >= 1.0) {

            progress = 1.0;
            status = Status.COMPLETED;
        }
    }

    /**
     * 中止当前批次。
     *
     * 当前阶段只改变状态。
     *
     * 以后会在 ProductionService 中增加：
     * - 返还 reserved inputs
     * - 计算实际劳动量
     * - 结算工资
     * - 不产生产品
     */
    public void abort() {
        if (!isActive()) {
            throw new IllegalStateException(
                    "Production batch is not active"
            );
        }

        status = Status.ABORTED;
    }
}