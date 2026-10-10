package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StartCommitRecoveryTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("economy.sqlite"); }

    @Test void pmFirstUsesNewEffectiveRevisionAndBatchFirstDefersTheNextTarget() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            var batch = e.production.startBatch(e.id);
            assertEquals(1, batch.getConfiguration().effectiveRevision());
            assertEquals("efficient_tools", batch.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "crude_tools");
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals("crude_tools", e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            assertEquals(1, e.execution().batch().getConfiguration().effectiveRevision());
        }
    }

    @Test void idlePmCanCommitDespiteMissingMaterialsWagesAndWorkers() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.stock.withdraw(e.id, Map.of("wood", 100.0, "iron", 100.0));
            for (var employee : e.employees.getAll()) e.employment.fire(employee.employeeId());
            e.payroll.set(BuildingPayrollPort.State.UNAVAILABLE);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(1, e.methods().effectiveRevision());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
        }
    }

    @Test void protectedBatchStillAcceptsIndependentPendingAfterProgressSaveFailed() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.repository.failProgressSave = true;
            e.ticks(1);
            assertEquals(0, e.execution().batch().getProgress());
            long revision = e.execution().executionRevision();
            e.repository.failNextSave = true;
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals(revision, e.execution().executionRevision());
            assertTrue(e.repository.failNextSave); // PM must not attempt an execution write.
            assertTrue(e.methods().pending().isPresent());
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals("efficient_tools", e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            assertEquals("crude_tools", e.execution().batch().getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
        }
    }

    @Test void unknownExecutionRejectsPmRatherThanAssumingIdleOrProtected() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.repository.failLoad = true;
            assertThrows(IllegalStateException.class, () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools"));
            assertEquals(0, e.methods().configurationRevision());
            assertTrue(e.methods().pending().isEmpty());
        }
    }

    @Test void confirmedUncommittedStartAllowsPmWhileFailedReleaseBlocksAnotherStart() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.repository.failNextSave = true;
            e.inventory.failRelease = true;
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertEquals(1, e.journal.pendingStarts().size());
            assertNotNull(e.equipment.getHolding(e.id).state().protectedByBatch());
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.equipment.getHolding(e.id).state().protectedByBatch());
            assertTrue(e.journal.pendingStarts().isEmpty());
            assertEquals("efficient_tools", e.production.startBatch(e.id).getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
        }
    }

    @Test void unconfirmedFailedStartFreezesPmAndKeepsBothCommitmentsUntilVerification() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.repository.failNextSave = true;
            e.journal.failContains = true;
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertFalse(e.stockState().reservations().isEmpty());
            assertNotNull(e.equipment.getHolding(e.id).state().protectedByBatch());
            assertThrows(IllegalStateException.class, () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools"));
            assertEquals(0, e.methods().configurationRevision());
            e.journal.failContains = false;
            e.production.recoverStarts();
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.equipment.getHolding(e.id).state().protectedByBatch());
        }
    }

    @Test void unconfirmedSuccessfulCommitRetainsItsBatchAndResourcesWhenVerificationRecovers() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(database(), true)) {
            e.repository.loseStartAcknowledgement = true;
            e.journal.failContains = true;
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            batch = e.execution().batch().getId();
            assertEquals(Set.of(batch), e.stockState().reservations().keySet());
            assertThrows(IllegalStateException.class, () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools"));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals(batch, e.execution().batch().getId());
            assertEquals(Set.of(batch), e.stockState().reservations().keySet());
            assertEquals(batch, e.equipment.getHolding(e.id).state().protectedByBatch());
            assertTrue(e.journal.pendingStarts().isEmpty());
        }
    }

    @Test void lostCommitAcknowledgementDoesNotOverwriteAnIndependentlySavedPending() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.repository.loseStartAcknowledgement = true;
            e.repository.afterSave = () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            var batch = e.production.startBatch(e.id);
            assertEquals("crude_tools", batch.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
            assertEquals("efficient_tools", e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            assertEquals(1, e.methods().configurationRevision());
            assertEquals(Set.of(batch.getId()), e.stockState().reservations().keySet());
            assertTrue(e.journal.pendingStarts().isEmpty());
        }
    }

    @Test void committedTerminationSurvivesSettlementAndCheckpointFailuresAndRestart() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(database(), true)) {
            batch = e.production.startBatch(e.id).getId();
            e.ticks(300);
            e.inventory.failSettlement = true;
            assertThrows(IllegalStateException.class, () -> e.production.abortBatch(e.id));
            assertEquals(ProductionBatch.Status.SETTLING_ABORTED, e.execution().batch().getStatus());
            e.ticks(2);
            assertEquals(0.25, e.execution().batch().getProgress());
            e.inventory.failSettlement = false;
            e.repository.failCompletedSave = true;
            assertThrows(IllegalStateException.class, () -> e.production.retry(e.id));
            assertEquals(ProductionBatch.Status.SETTLING_ABORTED, e.execution().batch().getStatus());
            assertEquals(7.5, e.quantity("tools"));
            assertTrue(e.stockState().reservations().isEmpty());
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertEquals(batch, e.execution().lastBatch().getId());
            assertTrue(e.execution().lastBatch().isAborted());
            assertEquals(0.25, e.execution().lastBatch().getProgress());
            assertEquals(92.5, e.quantity("wood"));
            assertEquals(7.5, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
        }
    }

    @Test void finalExecutionRevisionChangeRejectsStartWithoutUndoingTheOtherCommand() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.inventory.afterReserve = () -> e.production.setAutomatic(e.id, true);
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.execution().automatic());
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.equipment.getHolding(e.id).state().protectedByBatch());
            assertTrue(e.journal.pendingStarts().isEmpty());
        }
    }

    @Test void concurrentPmAndStartFollowSuccessfulCommitOrder() throws Exception {
        try (var e = new TestEconomy(database(), true); var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var start = pool.submit(() -> { barrier.await(); return e.production.startBatch(e.id); });
            var pm = pool.submit(() -> { barrier.await(); e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools"); return null; });
            var batch = start.get(10, TimeUnit.SECONDS);
            pm.get(10, TimeUnit.SECONDS);
            if (batch.getConfiguration().effectiveRevision() == 0) {
                assertEquals("crude_tools", batch.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
                assertEquals("efficient_tools", e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            } else {
                assertEquals(1, batch.getConfiguration().effectiveRevision());
                assertEquals("efficient_tools", batch.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
                assertTrue(e.methods().pending().isEmpty());
            }
            assertEquals(1, e.stockState().reservations().size());
            assertEquals(batch.getId(), e.execution().batch().getId());
        }
    }
}
