package com.bbmurloc.victoriaeconomics.server.productionequipment.operation;

import java.util.Objects;

public record ProductionEquipmentOperationSubmission(
        ProductionEquipmentOperation operation,
        Status status
) {

    public enum Status {
        EXECUTED,
        QUEUED
    }

    public ProductionEquipmentOperationSubmission {
        Objects.requireNonNull(
                operation,
                "operation cannot be null"
        );

        Objects.requireNonNull(
                status,
                "status cannot be null"
        );
    }

    public static ProductionEquipmentOperationSubmission executed(
            ProductionEquipmentOperation operation
    ) {
        return new ProductionEquipmentOperationSubmission(
                operation,
                Status.EXECUTED
        );
    }

    public static ProductionEquipmentOperationSubmission queued(
            ProductionEquipmentOperation operation
    ) {
        return new ProductionEquipmentOperationSubmission(
                operation,
                Status.QUEUED
        );
    }

    public boolean isExecuted() {
        return status == Status.EXECUTED;
    }

    public boolean isQueued() {
        return status == Status.QUEUED;
    }
}