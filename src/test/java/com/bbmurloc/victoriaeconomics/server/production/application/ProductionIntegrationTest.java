package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;

class ProductionIntegrationTest {
    @TempDir
    Path directory;

    private Path database() {
        return directory.resolve("economy.sqlite");
    }

    @Test
    void normalCompletionConsumesAndDepositsExactlyOnceAndEvaluatesNextBatch() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var batch = e.production.startBatch(e.id);
            assertEquals(30, new GoodsInventory(e.stockState()).reserved("wood"));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            e.ticks(200);
            assertNull(e.execution().batch());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(batch.getId(), e.execution().lastBatch().getId());
            assertEquals(70, e.quantity("wood"));
            assertEquals(30, e.quantity("tools"));
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertEquals(30, e.quantity("tools"));
            e.ticks(1);
            assertNotNull(e.execution().batch());
            assertNotEquals(batch.getId(), e.execution().batch().getId());
            assertEquals(0, e.execution().batch().getProgress());
        }
    }

    @Test
    void pmIsDeferredThroughPauseAndSettlementThenAppliedBeforeTheNextBatch() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var original = e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals("crude_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            e.payroll.set(BuildingPayrollPort.State.RECOVERY);
            e.ticks(50);
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            e.payroll.set(BuildingPayrollPort.State.CURRENT);
            e.ticks(199);
            assertEquals("crude_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            // Refuse the completion checkpoint once: the old frozen recipe and pending PM must remain protected.
            e.repository.failCompletedSave = true;
            e.ticks(1);
            assertTrue(e.execution().batch().needsSettlement());
            e.production.retry(e.id);
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals("crude_tools", original.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void pendingReplacementAndCancellationPersistWithoutChangingRunningBatch() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "reduced_tools");
            assertEquals("reduced_tools", e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            e.production.cancelPendingProductionMethods(e.id);
        }
        try (var e = new TestEconomy(database(), false)) {
            assertTrue(e.methods().pending().isEmpty());
            assertEquals("crude_tools", e.execution().batch().getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
            e.ticks(200);
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void hiringIsLimitedByPmPositionsAndJobReservations() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            UUID worker = new UUID(1, 1);
            e.employment.fire(worker);
            var reservation = e.employment.reservePosition(worker, e.id, "laborer");
            assertThrows(IllegalStateException.class, () -> e.employment.hire(UUID.randomUUID(), e.id, "laborer"));
            e.employment.acceptPosition(reservation.id());
            assertEquals(10, e.employment.countWorkers(e.id, "laborer"));
            assertEquals(1.0, e.production.startBatch(e.id).getConfiguration().productionSpeed());
        }
    }

    @Test
    void insufficientMaterialDoesNotConsumeOrLeaveLocks() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.stock.withdraw(e.id, Map.of("iron", 100L));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(100, e.quantity("wood"));
            assertTrue(e.journal.pendingStarts().isEmpty());
        }
    }

    @Test
    void partialMultiMaterialPortFailureIsCompensatedAndRetryCanStart() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.inventory.reserveOnlyFirstMaterial = true;
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
            assertTrue(e.journal.pendingStarts().isEmpty());
            e.inventory.reserveOnlyFirstMaterial = false;
            e.production.startBatch(e.id);
            assertEquals(2, e.stockState().reservations().values().iterator().next().size());
        }
    }

    @Test
    void failedFinalCommitReleasesAllMaterialReservations() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.repository.failNextSave = true;
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
            assertTrue(e.journal.pendingStarts().isEmpty());
            e.production.startBatch(e.id);
        }
    }

    @Test
    void failedReleaseIsDurableAndRecoveredAfterRestart() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.inventory.failAfterReserve = true;
            e.inventory.failRelease = true;
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertEquals(1, e.journal.pendingStarts().size());
            assertFalse(e.stockState().reservations().isEmpty());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertTrue(e.journal.pendingStarts().isEmpty());
            assertTrue(e.stockState().reservations().isEmpty());
            e.production.startBatch(e.id);
        }
    }

    @Test
    void finalRecheckRejectsChangedPmEmploymentAndWageAuthority() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.inventory.afterReserve = () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
            e.inventory.afterReserve = () -> e.employment.fire(new UUID(1, 1));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
            e.inventory.afterReserve = () -> e.payroll.set(BuildingPayrollPort.State.RECOVERY);
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
        }
    }

    @Test
    void recoveryPausesThenResumesOriginalCommitmentWithoutRelockingOrReselecting() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var batch = e.production.startBatch(e.id);
            e.ticks(50);
            e.payroll.set(BuildingPayrollPort.State.RECOVERY);
            e.ticks(100);
            assertEquals(0.25, e.execution().batch().getProgress());
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            assertEquals(batch.getId(), e.stockState().reservations().keySet().iterator().next());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            e.payroll.set(BuildingPayrollPort.State.CURRENT);
            e.ticks(150);
            assertEquals(batch.getId(), e.execution().lastBatch().getId());
            assertEquals(batch.getConfiguration(), e.execution().lastBatch().getConfiguration());
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void pausedBatchCanFinishOnTheFirstTickAfterPayrollRecovery() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            UUID batch = e.production.startBatch(e.id).getId();
            e.ticks(199);
            e.payroll.set(BuildingPayrollPort.State.RECOVERY);
            e.ticks(1);
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            assertEquals(199.0 / 200, e.execution().batch().getProgress());
            e.payroll.set(BuildingPayrollPort.State.CURRENT);
            e.ticks(1);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(30, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
        }
    }

    @Test
    void recoveryFailureAbortsWithFullRefundAndZeroOutputAndReleasesParticipants() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.ticks(50);
            e.employment.fire(new UUID(1, 1));
            assertNotNull(e.employment.getEmployment(new UUID(1, 1)));
            e.payroll.set(BuildingPayrollPort.State.RECOVERY);
            e.ticks(10);
            e.payroll.set(BuildingPayrollPort.State.ARREARS);
            e.ticks(1);
            assertTrue(e.execution().lastBatch().isAborted());
            assertEquals(0.25, e.execution().lastBatch().getProgress());
            assertEquals(100, e.quantity("wood"));
            assertEquals(0, e.quantity("tools"));
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.employment.getEmployment(new UUID(1, 1)));
            assertEquals(13, e.employees.getAll().size());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
    }

    @Test
    void unavailablePayrollBlocksNewBatchAndPausesRestoredProduction() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.payroll.set(BuildingPayrollPort.State.UNAVAILABLE);
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
            e.payroll.set(BuildingPayrollPort.State.CURRENT);
            e.production.startBatch(e.id);
            e.ticks(50);
        }
        try (var e = new TestEconomy(database(), false)) {
            e.payroll.set(BuildingPayrollPort.State.UNAVAILABLE);
            e.ticks(20);
            assertEquals(0.25, e.execution().batch().getProgress());
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
        }
    }

    @Test
    void restartAfterInventorySettledBeforeCheckpointDoesNotDuplicateProducts() throws Exception {
        UUID batchId;
        try (var e = new TestEconomy(database(), true)) {
            batchId = e.production.startBatch(e.id).getId();
            e.ticks(199);
            e.repository.failCompletedSave = true;
            e.ticks(1);
            assertTrue(e.execution().batch().needsSettlement());
            assertEquals(30, e.quantity("tools"));
            assertTrue(e.stockState().settlements().containsKey(batchId));
            assertTrue(e.stockState().reservations().isEmpty());
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertEquals(batchId, e.execution().lastBatch().getId());
            assertEquals(30, e.quantity("tools"));
            assertEquals(70, e.quantity("wood"));
        }
    }

    @Test
    void restartPreservesProgressMethodsAndMaterialLocks() throws Exception {
        UUID batchId;
        try (var e = new TestEconomy(database(), true)) {
            batchId = e.production.startBatch(e.id).getId();
            e.ticks(60);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals(batchId, e.execution().batch().getId());
            assertEquals(0.3, e.execution().batch().getProgress());
            assertEquals(14, e.employees.getAll().size());
            assertEquals(30, new GoodsInventory(e.stockState()).reserved("wood"));
            e.ticks(140);
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void anotherBuildingsStockCannotSupplyMissingLocalMaterial() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var other = e.buildingService.createBuilding("tooling_workshop");
            e.stock.createLocation(other.getId(), 10000);
            e.stock.deposit(other.getId(), Map.of("wood", 500L));
            e.stock.withdraw(e.id, Map.of("wood", 100L));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertEquals(500, e.stock.inspect(other.getId()).quantities().get("wood"));
        }
    }

    @Test
    void overfullOutputBlocksNextBatchAndOrdinaryDepositButAllowsWithdrawal() throws Exception {
        try (var e = new TestEconomy(database(), true, 200)) {
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.production.startBatch(e.id);
            e.ticks(200);
            assertTrue(new GoodsInventory(e.stockState()).overCapacity()); // 200 - 30 + 60 = 230
            assertEquals(60, e.quantity("tools"));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertThrows(IllegalStateException.class, () -> e.stock.deposit(e.id, Map.of("iron", 1L)));
            e.stock.withdraw(e.id, Map.of("tools", 40L));
            e.production.startBatch(e.id);
        }
    }

    @Test
    void concurrentStartsAreSerializedAndCannotCreateTwoBatchesOrOrphanLocks() throws Exception {
        try (var e = new TestEconomy(database(), true); var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Boolean> start = () -> {
                barrier.await();
                try {
                    e.production.startBatch(e.id);
                    return true;
                } catch (IllegalStateException expected) {
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(start), second = pool.submit(start);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, e.stockState().reservations().size());
            assertTrue(e.journal.pendingStarts().isEmpty());
            e.production.abortBatch(e.id);
            e.production.abortBatch(e.id);
            e.production.retry(e.id);
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(0, e.quantity("tools"));
        }
    }

    @Test
    void changedPmCapacityCancelsReservationsAndBlocksUntilPersonnelAreHandled() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.employment.fire(new UUID(1, 1));
            e.employment.reservePosition(new UUID(1, 1), e.id, "laborer");
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "reduced_tools");
            e.ticks(223);
            assertEquals(ProductionExecution.Boundary.WORKFORCE, e.execution().boundary());
            assertTrue(e.employees.reservations().isEmpty());
            assertTrue(e.execution().batch().isEnded());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertEquals(13, e.employees.getAll().size()); // No automatic dismissal without the real context.
        }
    }
}
