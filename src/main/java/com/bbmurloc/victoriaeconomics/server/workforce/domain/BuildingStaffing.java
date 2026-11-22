package com.bbmurloc.victoriaeconomics.server.workforce.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 一个建筑当前批次的用工占用，空闲时没有占用。 */
public record BuildingStaffing(UUID buildingId, UUID batchId, Map<String, Integer> occupiedByOccupation, long revision) {
    public BuildingStaffing {
        Objects.requireNonNull(buildingId);
        occupiedByOccupation = Map.copyOf(occupiedByOccupation);
        if (revision < 0 || (batchId == null) != occupiedByOccupation.isEmpty()
                || occupiedByOccupation.values().stream().anyMatch(count -> count <= 0)) {
            throw new IllegalArgumentException("Invalid batch labor allocation");
        }
    }

    public long totalOccupied() {
        long total = 0;
        for (int count : occupiedByOccupation.values()) total = Math.addExact(total, count);
        return total;
    }
}
