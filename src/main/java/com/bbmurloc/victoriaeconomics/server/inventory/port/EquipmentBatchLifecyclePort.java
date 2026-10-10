package com.bbmurloc.victoriaeconomics.server.inventory.port;

import java.util.UUID;

/** Inventory consumes durable boundary authorization, not production's internal aggregate graph. */
@FunctionalInterface
public interface EquipmentBatchLifecyclePort {
    void requireEquipmentBoundary(UUID buildingId, UUID endedBatchId);
}
