package com.bbmurloc.victoriaeconomics.server.workforce.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingStaffingTest {
    @Test void emptyAllocationCannotClaimABatchOrKeepWorkers() {
        UUID building = UUID.randomUUID(), batch = UUID.randomUUID();
        assertEquals(0, new BuildingStaffing(building, null, Map.of(), 0).totalOccupied());
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(building, batch, Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(building, null, Map.of("worker", 1), 0));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(building, batch, Map.of("worker", 0), 0));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(building, batch, Map.of("worker", -1), 0));
        assertThrows(IllegalArgumentException.class, () -> new BuildingStaffing(building, null, Map.of(), -1));
    }

    @Test void batchWorkersAreImmutableAndSumWithoutIntegerTruncation() {
        var workers = new HashMap<>(Map.of("worker", Integer.MAX_VALUE, "machinist", Integer.MAX_VALUE));
        var staffing = new BuildingStaffing(UUID.randomUUID(), UUID.randomUUID(), workers, 1);
        workers.clear();
        assertEquals(2L * Integer.MAX_VALUE, staffing.totalOccupied());
        assertThrows(UnsupportedOperationException.class, () -> staffing.occupiedByOccupation().clear());
    }
}
