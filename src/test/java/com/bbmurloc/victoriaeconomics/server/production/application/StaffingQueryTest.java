package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteStaffingQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StaffingQueryTest {
    @TempDir Path dir;

    private StaffingSnapshot query(TestEconomy e) {
        return new StaffingCalculator(new SqliteStaffingQuery(e.connection,e.resolver,e.occupations,e.lock)).calculate(e.id);
    }

    @Test void queryDistinguishesIdleDemandAndRunningOccupancy() throws Exception {
        try (var e = new TestEconomy(dir.resolve("facts.sqlite"), true)) {
            var idle = query(e);
            assertNull(idle.batchId());
            assertEquals(0, idle.occupations().get("laborer").batchOccupied());
            assertEquals(100, idle.occupations().get("laborer").available().orElseThrow());

            var batch = e.production.startBatch(e.id);
            var running = query(e);
            assertEquals(batch.getId(), running.batchId());
            var role = running.occupations().get("laborer");
            assertEquals(10, role.requiredWorkers());
            assertEquals(10, role.batchOccupied());
            assertEquals(100, role.totalSupply().orElseThrow());
            assertEquals(10, role.regionalOccupied());
            assertEquals(90, role.available().orElseThrow());
        }
    }

    @Test void regionalSupplyChangeAndPauseDoNotRefillExistingAllocationOnRestart() throws Exception {
        var file = dir.resolve("frozen.sqlite");
        UUID id;
        try (var e = new TestEconomy(file, true)) {
            e.setSupply("laborer", 6);
            id = e.production.startBatch(e.id).getId();
            e.production.setManualPause(e.id, true);
            e.setSupply("laborer", 20);
        }
        try (var e = new TestEconomy(file, false)) {
            var snapshot = query(e);
            assertEquals(id, snapshot.batchId());
            assertEquals(6, snapshot.occupations().get("laborer").batchOccupied());
            assertEquals(14, snapshot.occupations().get("laborer").available().orElseThrow());
            assertEquals(0, e.execution().batch().getProgressUnits());
        }
    }
}
