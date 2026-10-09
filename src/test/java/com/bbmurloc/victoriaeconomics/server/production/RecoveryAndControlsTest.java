package com.bbmurloc.victoriaeconomics.server.production;

import com.bbmurloc.victoriaeconomics.server.inventory.*;
import com.bbmurloc.victoriaeconomics.server.inventory.equipment.*;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionJournal;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomySchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryAndControlsTest {
    @TempDir
    Path directory;

    Path database() {
        return directory.resolve("economy.sqlite");
    }

    @Test
    void missingPayrollFactCannotResumeProtectedProduction() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.ticks(300);
            e.payroll.set(null);
            e.ticks(100);
            assertEquals(0.25, e.execution().batch().getProgress());
            assertEquals(com.bbmurloc.victoriaeconomics.server.production.batch.ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
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
            assertEquals(batch, e.equipment.getHolding(e.id).state().protectedByBatch());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals(batch, e.execution().batch().getId());
            e.ticks(1200);
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void pendingPmSurvivesCheckpointFailureAndEquipmentWaitsForIt() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.buildingService.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.equipment.uninstall(e.id, 50);
            e.repository.failMethodsSave = true;
            e.ticks(1200);
            assertEquals(ProductionExecution.Boundary.METHODS, e.execution().boundary());
            assertEquals("crude_tools", e.building().getProductionDepartment().getSelectedProductionMethodId("tooling_workshop_base"));
            assertTrue(e.building().getProductionDepartment().getPendingProductionMethods().isPresent());
            assertEquals(100, e.equipment.getHolding(e.id).getInstalledQuantity());
            assertEquals(30, e.quantity("tools"));
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            e.production.retry(e.id);
            assertEquals("efficient_tools", e.building().getProductionDepartment().getSelectedProductionMethodId("tooling_workshop_base"));
            assertEquals(50, e.equipment.getHolding(e.id).getInstalledQuantity());
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test
    void equipmentBoundaryReplayCannotExecuteAnAlreadyExecutedRequestTwice() throws Exception {
        UUID request;
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            request = e.equipment.uninstall(e.id, 20).id();
            e.repository.failEquipmentBoundarySave = true;
            e.ticks(1200);
            assertEquals(ProductionExecution.Boundary.EQUIPMENT, e.execution().boundary());
            assertEquals(80, e.equipment.getHolding(e.id).getInstalledQuantity());
        }
        try (var e = new TestEconomy(database(), false)) {
            e.production.retry(e.id);
            assertEquals(80, e.equipment.getHolding(e.id).getInstalledQuantity());
            assertEquals(EquipmentConfigurationRequest.Status.EXECUTED, e.equipment.getHolding(e.id).find(request).status());
        }
    }

    @Test
    void pmArrivingDuringBlockedPersonnelBoundaryIsAppliedBeforeNextStart() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.buildingService.selectProductionMethod(e.id, "tooling_workshop_base", "pig_iron_tools");
            e.ticks(1200);
            assertEquals(ProductionExecution.Boundary.WORKFORCE, e.execution().boundary());
            e.buildingService.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals(ProductionExecution.Boundary.METHODS, e.execution().boundary());
            e.production.retry(e.id);
            assertNull(e.execution().batch());
            assertTrue(e.building().getProductionDepartment().getPendingProductionMethods().isEmpty());
            assertEquals("efficient_tools", e.building().getProductionDepartment().getSelectedProductionMethodId("tooling_workshop_base"));
            assertEquals(60, e.production.startBatch(e.id).getConfiguration().resolvedRecipe().outputs().get("tools"));
        }
    }

    @Test
    void personnelCapacityChangesCancelExcessReservationsEvenWhenNoBatchIsRunning() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.employment.fire(new UUID(1, 1));
            e.employment.reservePosition(new UUID(1, 1), e.id, "laborer");
            e.buildingService.selectProductionMethod(e.id, "tooling_workshop_base", "pig_iron_tools");
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.employees.reservations().isEmpty());
            assertEquals(13, e.employees.getAll().size());
            assertTrue(e.stockState().reservations().isEmpty());
        }
    }

    @Test
    void equipmentTransfersAreAtomicAndRespectInstallationReservationsAndDestinationCapacity() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var other = e.buildingService.createBuilding("tooling_workshop");
            e.equipment.addUninstalledEquipment(other.getId(), 90);
            e.equipment.uninstall(e.id, 20);
            assertThrows(IllegalStateException.class, () -> e.equipment.transferUninstalledEquipment(e.id, other.getId(), 11));
            assertEquals(20, e.equipment.getHolding(e.id).getUninstalledQuantity());
            e.equipment.transferUninstalledEquipment(e.id, other.getId(), 10);
            assertEquals(90, e.equipment.getHolding(e.id).getTotalQuantity());
            assertEquals(100, e.equipment.getHolding(other.getId()).getTotalQuantity());
            e.production.startBatch(e.id);
            e.equipment.install(e.id, 10);
            assertThrows(IllegalStateException.class, () -> e.equipment.transferUninstalledEquipment(e.id, other.getId(), 1));
            e.equipment.removeUninstalledEquipment(other.getId(), 5);
            try (var statement = e.connection.createStatement()) {
                statement.executeUpdate("CREATE TRIGGER reject_destination BEFORE UPDATE ON equipment_holdings WHEN NEW.building_id='" + other.getId() + "' BEGIN SELECT RAISE(ABORT, 'injected destination failure'); END");
            }
            e.production.abortBatch(e.id); // Material progress zero, pending install is applied.
            e.equipment.uninstall(e.id, 10);
            assertThrows(IllegalStateException.class, () -> e.equipment.transferUninstalledEquipment(e.id, other.getId(), 1));
            assertEquals(90, e.equipment.getHolding(e.id).getTotalQuantity());
            assertEquals(95, e.equipment.getHolding(other.getId()).getTotalQuantity());
        }
    }

    @Test
    void mixedConcurrentControlRequestsNeverLoseTheBatchOrDuplicateItsSettlement() throws Exception {
        try (var e = new TestEconomy(database(), true); var pool = Executors.newFixedThreadPool(4)) {
            e.production.startBatch(e.id);
            e.ticks(300);
            var barrier = new CyclicBarrier(4);
            List<Callable<Void>> actions = List.of(
                    () -> {
                        barrier.await();
                        e.buildingService.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
                        return null;
                    },
                    () -> {
                        barrier.await();
                        e.equipment.uninstall(e.id, 40);
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
            assertEquals(92.5, e.quantity("wood"));
            assertEquals(7.5, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(60, e.equipment.getHolding(e.id).getInstalledQuantity());
            assertEquals("efficient_tools", e.building().getProductionDepartment().getSelectedProductionMethodId("tooling_workshop_base"));
        }
    }

    @Test
    void abandonedStartIntentWithoutAcknowledgementIsRecoveredUsingItsOwner() throws Exception {
        UUID abandoned = UUID.randomUUID();
        try (var e = new TestEconomy(database(), true)) {
            e.journal.recordStart(new ProductionJournal.StartIntent(abandoned, e.id));
            e.stock.reserve(e.id, abandoned, Map.of("wood", 30.0));
            e.equipment.protectForBatch(e.id, abandoned);
        }
        try (var e = new TestEconomy(database(), false)) {
            assertTrue(e.stockState().reservations().isEmpty());
            assertTrue(e.journal.pendingStarts().isEmpty());
            assertNull(e.equipment.getHolding(e.id).state().protectedByBatch());
            e.production.startBatch(e.id);
        }
    }

    @Test
    void schemaMigrationIsRepeatableAndPreservesLegacyRowsAndEquipmentColumn() throws Exception {
        UUID building = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE economic_buildings(id TEXT PRIMARY KEY, building_type_id TEXT NOT NULL, status TEXT NOT NULL, current_equipment INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("INSERT INTO economic_buildings VALUES('" + building + "', 'tooling_workshop', 'ACTIVE', 73)");
            statement.executeUpdate("CREATE TABLE building_production_method_selections(building_id TEXT, group_id TEXT, method_id TEXT, PRIMARY KEY(building_id, group_id))");
            statement.executeUpdate("INSERT INTO building_production_method_selections VALUES('" + building + "', 'tooling_workshop_base', 'crude_tools')");
            EconomySchema.initialize(connection);
            EconomySchema.initialize(connection);
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
