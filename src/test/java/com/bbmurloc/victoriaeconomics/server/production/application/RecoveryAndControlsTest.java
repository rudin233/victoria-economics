package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionJournal;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomySchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;

class RecoveryAndControlsTest {
    @TempDir
    Path directory;

    Path database() {
        return directory.resolve("economy.sqlite");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lostSettlementReceiptAtNinetyNinePercentRecoversTheExactOutcomeAfterRestart(boolean completed) throws Exception {
        UUID batch;
        var outcome = completed
                ? com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement.Outcome.COMPLETED
                : com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement.Outcome.ABORTED;
        try (var e = new TestEconomy(database(), true)) {
            batch = e.production.startBatch(e.id).getId();
            e.production.setAutomatic(e.id, false);
            e.ticks(199);
            assertEquals(199, e.execution().batch().getProgressUnits());
            e.inventory.loseSettlementAcknowledgement = true;
            if (completed) e.ticks(1);
            else assertThrows(com.bbmurloc.victoriaeconomics.server.storage.sqlite.CommitOutcomeUnknownException.class,
                    () -> e.production.abortBatch(e.id));
            assertTrue(e.execution().batch().needsSettlement());
            assertEquals(outcome, e.stockState().settlements().get(batch).command().outcome());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(completed ? 70L : 100L, e.quantity("wood"));
            assertEquals(completed ? 30L : 0L, e.quantity("tools"));
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertEquals(completed, e.execution().lastBatch().isCompleted());
            assertEquals(!completed, e.execution().lastBatch().isAborted());
            assertEquals(outcome, e.stockState().settlements().get(batch).command().outcome());
            assertEquals(1, e.stockState().settlements().size());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(completed ? 70L : 100L, e.quantity("wood"));
            assertEquals(100L, e.quantity("iron"));
            assertEquals(completed ? 30L : 0L, e.quantity("tools"));
        }
    }

    @Test
    void missingPayrollFactCannotResumeProtectedProduction() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.ticks(50);
            e.payroll.set(null);
            e.ticks(100);
            assertEquals(0.25, e.execution().batch().getProgress());
            assertEquals(com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            assertFalse(e.stockState().reservations().isEmpty());
        }
    }

    @Test
    void lostStartCommitAcknowledgementRetainsTheCommittedBatchAndReservation() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(database(), true)) {
            e.repository.loseStartAcknowledgement = true;
            batch = e.production.startBatch(e.id).getId();
            assertEquals(Set.of(batch), e.stockState().reservations().keySet());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals(batch, e.execution().batch().getId());
            e.ticks(200);
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void effectivePmSurvivesIndependentCheckpointFailureBeforeTheLegacyTransition() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.repository.failMethodsSave = true;
            e.ticks(200);
            assertEquals(ProductionExecution.Boundary.METHODS, e.execution().boundary());
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertTrue(e.methods().pending().isEmpty());
            assertEquals(30, e.quantity("tools"));
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void legacyTransitionPersistenceFailureBlocksStartsAndRestartDoesNotResettle() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(database(), true)) {
            batch = e.production.startBatch(e.id).getId();
            try (var statement = e.connection.createStatement()) {
                statement.executeUpdate("CREATE TRIGGER reject_legacy_transition BEFORE UPDATE ON production_executions WHEN OLD.boundary='EQUIPMENT' AND NEW.boundary='WORKFORCE' BEGIN SELECT RAISE(ABORT,'injected transition failure'); END");
            }
            e.ticks(200);
            assertEquals(ProductionExecution.Boundary.EQUIPMENT, e.execution().boundary());
            assertTrue(e.execution().batch().isCompleted());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertThrows(IllegalStateException.class, () -> e.production.retry(e.id));
            assertEquals(ProductionExecution.Boundary.EQUIPMENT, e.execution().boundary());
            assertEquals(30, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
            try (var statement = e.connection.createStatement()) { statement.executeUpdate("DROP TRIGGER reject_legacy_transition"); }
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertEquals(ProductionExecution.Boundary.NONE, e.execution().boundary());
            assertEquals(30, e.quantity("tools"));
            assertEquals(70, e.quantity("wood"));
            assertEquals(1, e.stockState().settlements().size());
        }
    }

    @Test
    void pmArrivingDuringBlockedPersonnelBoundaryIsAppliedBeforeNextStart() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "reduced_tools");
            e.ticks(200);
            assertEquals(ProductionExecution.Boundary.WORKFORCE, e.execution().boundary());
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals(ProductionExecution.Boundary.METHODS, e.execution().boundary());
            e.production.retry(e.id);
            assertNull(e.execution().batch());
            assertTrue(e.methods().pending().isEmpty());
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(60, e.production.startBatch(e.id).getConfiguration().resolvedRecipe().outputs().get("tools"));
        }
    }

    @Test
    void personnelCapacityChangesCancelExcessReservationsEvenWhenNoBatchIsRunning() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.employment.fire(new UUID(1, 1));
            e.employment.reservePosition(new UUID(1, 1), e.id, "laborer");
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "reduced_tools");
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.employees.reservations().isEmpty());
            assertEquals(13, e.employees.getAll().size());
            assertTrue(e.stockState().reservations().isEmpty());
        }
    }

    @Test
    void mixedConcurrentControlRequestsNeverLoseTheBatchOrDuplicateItsSettlement() throws Exception {
        try (var e = new TestEconomy(database(), true); var pool = Executors.newFixedThreadPool(4)) {
            e.production.startBatch(e.id);
            e.ticks(50);
            var barrier = new CyclicBarrier(4);
            List<Callable<Void>> actions = List.of(
                    () -> {
                        barrier.await();
                        e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
                        return null;
                    },
                    () -> {
                        barrier.await();
                        e.production.setAutomatic(e.id, false);
                        return null;
                    },
                    () -> {
                        barrier.await();
                        e.production.abortBatch(e.id);
                        return null;
                    },
                    () -> {
                        barrier.await();
                        e.production.retry(e.id);
                        return null;
                    });
            var futures = actions.stream().map(pool::submit).toList();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            e.production.retry(e.id);
            assertNull(e.execution().batch());
            assertTrue(e.execution().lastBatch().isAborted());
            assertEquals(0.25, e.execution().lastBatch().getProgress());
            assertEquals(100L, e.quantity("wood"));
            assertEquals(0L, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals("efficient_tools", e.methods().effective().methods().get("tooling_workshop_base"));
        }
    }

    @Test
    void abandonedStartIntentWithoutAcknowledgementIsRecoveredUsingItsOwner() throws Exception {
        UUID abandoned = UUID.randomUUID();
        try (var e = new TestEconomy(database(), true)) {
            e.journal.recordStart(new ProductionJournal.StartIntent(abandoned, e.id));
            e.stock.reserve(e.id, abandoned, Map.of("wood", 30L));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertTrue(e.stockState().reservations().isEmpty());
            assertTrue(e.journal.pendingStarts().isEmpty());
            e.production.startBatch(e.id);
        }
    }

    @Test
    void incompatibleLegacySchemaIsRejectedWithoutChangingRowsOrEquipment() throws Exception {
        UUID building = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE economic_buildings(id TEXT PRIMARY KEY, building_type_id TEXT NOT NULL, status TEXT NOT NULL, current_equipment INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("INSERT INTO economic_buildings VALUES('" + building + "', 'tooling_workshop', 'ACTIVE', 73)");
            statement.executeUpdate("CREATE TABLE building_production_method_selections(building_id TEXT, group_id TEXT, method_id TEXT, PRIMARY KEY(building_id, group_id))");
            statement.executeUpdate("INSERT INTO building_production_method_selections VALUES('" + building + "', 'tooling_workshop_base', 'crude_tools')");
            assertTrue(assertThrows(IllegalStateException.class, () -> EconomySchema.initialize(connection)).getMessage().contains("new world"));
            assertThrows(IllegalStateException.class, () -> EconomySchema.initialize(connection));
            try (var rows = statement.executeQuery("SELECT current_equipment FROM economic_buildings WHERE id='" + building + "'")) {
                assertTrue(rows.next());
                assertEquals(73, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM building_production_method_selections")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void opaqueRollbackCheckpointsCannotBeAppliedToAnotherExecution() {
        var first = new ProductionExecution(UUID.randomUUID());
        var other = new ProductionExecution(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> other.restoreState(first.state()));
    }
}
