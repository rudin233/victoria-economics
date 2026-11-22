package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkforceContractTest {
    @TempDir Path dir;
    Path file() { return dir.resolve("labor.sqlite"); }

    @Test void allPartialHiresParticipateAndFullRecipeSettlesAtBottleneckSpeed() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.setSupply("laborer", 6);
            e.setSupply("machinist", 2);
            var batch = e.production.startBatch(e.id);
            var plan = batch.getConfiguration().workforcePlan();
            assertEquals(Map.of("laborer", 6, "machinist", 2), plan.participatingWorkers());
            assertEquals(1, plan.speedNumerator());
            assertEquals(2, plan.speedDenominator());
            assertEquals(8, e.scalar("SELECT employees FROM payroll_accruals"));
            e.production.stopAfterCurrentBatch(e.id);
            e.ticks(399);
            assertEquals(399, e.execution().batch().getProgressUnits());
            e.ticks(1);
            assertEquals(batch.getId(), e.execution().lastBatch().getId());
            assertEquals(400L, e.execution().lastBatch().endedTick());
            assertEquals(70, e.quantity("wood"));
            assertEquals(30, e.quantity("tools"));
            assertEquals(0, e.staffingRepository.load(e.id).totalOccupied());
            assertEquals(0, e.scalar("SELECT employees FROM payroll_accruals"));
        }
    }

    @Test void missingOneOccupationLeavesEveryOtherOccupationAndResourcesFree() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.setSupply("machinist", 0);
            assertThrows(ProductionStartBlocked.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.staffingRepository.load(e.id).batchId());
            assertEquals(0, e.scalar("SELECT employees FROM payroll_accruals"));
            e.ticks(20);
            assertEquals(0, e.scalar("SELECT remainder FROM payroll_accruals"));
        }
    }

    @Test void historicalSettlementReplayDoesNotReleaseNewerBatchOrWriteWages() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.startBatch(e.id);
            e.ticks(200);
            var oldest = ProductionSettlement.forBatch(e.execution().lastBatch());
            e.ticks(1);
            var next = e.execution().batch();
            var staffing = e.staffingRepository.load(e.id);
            long wageRevision = e.scalar("SELECT revision FROM payroll_accruals");
            e.coordinator.settle(e.id, oldest);
            assertEquals(next.getId(), e.execution().batch().getId());
            assertEquals(staffing, e.staffingRepository.load(e.id));
            assertEquals(wageRevision, e.scalar("SELECT revision FROM payroll_accruals"));
        }
    }

    @Test void threeBuildingsRotateScarceLaborWithoutSameTickRehire() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.setSupply("laborer", 10);
            e.setSupply("machinist", 4);
            var ids = List.of(e.id, e.createStockedBuilding().getId(), e.createStockedBuilding().getId());
            for (var id : ids) e.production.setAutomatic(id, true);
            var winners = new HashSet<UUID>();
            e.ticks(1);
            for (int round = 0; round < 3; round++) {
                long tick = e.clock.elapsedTicks();
                var running = ids.stream().filter(id -> e.repository.load(id).batch() != null).toList();
                assertEquals(1, running.size());
                winners.add(running.getFirst());
                var batch = e.repository.load(running.getFirst()).batch();
                assertEquals(tick, batch.startedTick());
                assertEquals(0, batch.getProgressUnits());
                e.production.onServerTick();
                assertEquals(0, e.repository.load(running.getFirst()).batch().getProgressUnits());
                e.ticks(200);
                assertNull(e.repository.load(running.getFirst()).batch());
                assertEquals(tick + 200, e.repository.load(running.getFirst()).lastBatch().endedTick());
            }
            assertTrue(winners.size() >= 2, "固定首位不能持续独占区域供给");
        }
    }
}
