package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteEmploymentRepository;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryRecoveryTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("economy.sqlite"); }
    private String effective(TestEconomy e) { return e.methods().effective().methods().get("tooling_workshop_base"); }

    @Test void restartFindsPendingAtWorkforceAfterMethodsReentrySaveFailed() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "pig_iron_tools");
            e.ticks(1200);
            assertEquals(ProductionExecution.Boundary.WORKFORCE, e.execution().boundary());
            assertEquals(14, e.employees.getAll().size()); // No invented consenting transfer or dismissal.
            e.repository.failReturnMethodsSave = true;
            assertThrows(IllegalStateException.class, () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools"));
            assertEquals(ProductionExecution.Boundary.WORKFORCE, e.execution().boundary());
            assertEquals("efficient_tools", e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals("efficient_tools", effective(e));
            assertTrue(e.methods().pending().isEmpty());
            assertNull(e.execution().batch());
            assertEquals(e.methods().effectiveRevision(), e.execution().methodsEffectiveRevision());
            assertEquals(14, e.employees.getAll().size());
            assertEquals(60, e.production.startBatch(e.id).getConfiguration().resolvedRecipe().outputs().get("tools"));
        }
    }

    @Test void restartFindsPendingAtEquipmentWithoutReplayingTheExecutedRequest() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(database(), true)) {
            batch = e.production.startBatch(e.id).getId();
            e.equipment.uninstall(e.id, 20);
            e.repository.failEquipmentBoundarySave = true;
            e.ticks(1200);
            assertEquals(ProductionExecution.Boundary.EQUIPMENT, e.execution().boundary());
            assertEquals(80, e.equipment.getHolding(e.id).getInstalledQuantity());
            e.repository.failReturnMethodsSave = true;
            assertThrows(IllegalStateException.class, () -> e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools"));
            assertEquals(ProductionExecution.Boundary.EQUIPMENT, e.execution().boundary());
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals("efficient_tools", effective(e));
            assertNull(e.execution().batch());
            assertEquals(80, e.equipment.getHolding(e.id).getInstalledQuantity());
            assertEquals(30, e.quantity("tools"));
            e.production.retry(e.id);
            assertEquals(80, e.equipment.getHolding(e.id).getInstalledQuantity());
        }
    }

    @Test void effectiveRevisionMismatchRejectsEquipmentEvenWithoutPending() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            UUID batch = e.production.startBatch(e.id).getId();
            e.equipment.uninstall(e.id, 20);
            e.repository.failEquipmentBoundarySave = true;
            e.ticks(1200);
            // Build an independently committed intermediate state to test the guard and recovery, not a public PM command.
            var configuration = e.methods();
            long revision = configuration.configurationRevision();
            configuration.requestSelection("tooling_workshop_base", "efficient_tools", false);
            e.configurations.save(configuration, revision);
            assertTrue(e.methods().pending().isEmpty());
            assertNotEquals(e.methods().effectiveRevision(), e.execution().methodsEffectiveRevision());
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
            e.production.recoverBoundaries();
            assertNull(e.execution().batch());
            assertEquals(1, e.execution().methodsEffectiveRevision());
            assertEquals(80, e.equipment.getHolding(e.id).getInstalledQuantity());
        }
    }

    @Test void idlePmCommitSurvivesExecutionCheckpointFailureAndRecoversWithAutomaticOff() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.repository.failNextSave = true;
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals("efficient_tools", effective(e));
            assertEquals(1, e.methods().effectiveRevision());
            assertEquals(0, e.execution().methodsEffectiveRevision());
            assertFalse(e.execution().automatic());
            assertNotNull(e.production.blockedReason(e.id));
        }
        try (var e = new TestEconomy(database(), false)) {
            assertFalse(e.execution().automatic());
            assertEquals(1, e.execution().methodsEffectiveRevision());
            assertEquals("efficient_tools", effective(e));
            assertNotNull(e.production.startBatch(e.id));
        }
    }

    @Test void startupRechecksIdleReservationsAndNeverPretendsOverstaffedEmployeesWereTransferred() throws Exception {
        try (var e = new TestEconomy(database(), true); var statement = e.connection.createStatement()) {
            e.employment.fire(new UUID(1, 1));
            e.employment.reservePosition(new UUID(1, 1), e.id, "laborer");
            statement.executeUpdate("CREATE TRIGGER reject_hr BEFORE UPDATE ON employment_state BEGIN SELECT RAISE(ABORT,'injected personnel save failure'); END");
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "pig_iron_tools");
            assertEquals("pig_iron_tools", effective(e));
            assertEquals(1, e.employees.reservations().size());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
            statement.executeUpdate("DROP TRIGGER reject_hr");
        }
        try (var e = new TestEconomy(database(), false)) {
            assertFalse(e.execution().automatic());
            assertTrue(e.employees.reservations().isEmpty());
            assertEquals(13, e.employees.getAll().size());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals(13, e.employees.getAll().size());
            assertNotNull(e.production.startBatch(e.id));
        }
    }

    @Test void reconciliationStartsBeforeFailedMethodsCheckpointButIncompleteStaffingStillBlocks() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.employment.fire(new UUID(1, 1));
            e.employment.reservePosition(new UUID(1, 1), e.id, "laborer");
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "pig_iron_tools");
            e.repository.failMethodsSave = true;
            e.ticks(1334);
            assertEquals(ProductionExecution.Boundary.METHODS, e.execution().boundary());
            assertEquals("pig_iron_tools", effective(e));
            assertTrue(e.methods().pending().isEmpty());
            assertTrue(e.employees.reservations().isEmpty());
            assertEquals(13, e.employees.getAll().size());
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
        }
    }

    @Test void unrelatedBuildingPersonnelRequestsDoNotBlockThisSitesStart() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            UUID other = e.buildingService.createBuilding("tooling_workshop").getId();
            UUID employee = UUID.randomUUID();
            e.employment.hire(employee, other, "laborer");
            e.employees.requestDismissal(employee);
            var reservation = e.employment.reservePosition(UUID.randomUUID(), other, "laborer");
            assertNotNull(e.production.startBatch(e.id));
            assertNotNull(e.employees.reservation(reservation.id()));
            assertTrue(e.employees.dismissalPending(employee));
            assertNotNull(e.employment.getEmployment(employee));
        }
    }

    @Test void equipmentRequiresDurableEndedBoundaryAndOldBatchCannotReleaseNewProtection() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            UUID batch = e.production.startBatch(e.id).getId();
            e.equipment.uninstall(e.id, 50);
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
            e.payroll.set(BuildingPayrollPort.State.RECOVERY);
            e.ticks(1);
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
            e.payroll.set(BuildingPayrollPort.State.CURRENT);
            e.ticks(1199);
            e.repository.failCompletedSave = true;
            e.ticks(1);
            assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, e.execution().batch().getStatus());
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
            assertEquals(100, e.equipment.getHolding(e.id).getInstalledQuantity());
            e.repository.failLoad = true;
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
            e.repository.failLoad = false;
            e.production.retry(e.id);
            UUID next = e.production.startBatch(e.id).getId();
            assertThrows(IllegalStateException.class, () -> e.equipment.flushPendingOperationsForBuilding(e.id, batch));
            assertEquals(next, e.equipment.getHolding(e.id).state().protectedByBatch());
            assertEquals(50, e.equipment.getHolding(e.id).getInstalledQuantity());
        }
    }

    @Test void necessaryPersonnelStateIsRecheckedAfterReservationAndFailedStartCompensates() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var ready = new AtomicBoolean(true);
            var workforce = new WorkforcePort() {
                @Override public Collection<EmploymentFact> effectiveEmployment(UUID id) { return e.employment.effectiveEmployment(id); }
                @Override public boolean qualified(UUID npc, String role) { return e.employment.qualified(npc, role); }
                @Override public boolean reconcile(UUID id, Map<String, Integer> demand) { return ready.get() && e.employment.reconcile(id, demand); }
            };
            var production = production(e, workforce, id -> e.payroll.get());
            e.inventory.afterReserve = () -> ready.set(false);
            assertThrows(IllegalStateException.class, () -> production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
            assertTrue(e.journal.pendingStarts().isEmpty());
            assertNull(e.equipment.getHolding(e.id).state().protectedByBatch());
        }
    }

    @Test void unavailablePayrollAndQualificationRemainRealStartBarriers() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            var withoutPayroll = production(e, e.employment, BuildingPayrollPort.unavailable());
            assertThrows(IllegalStateException.class, () -> withoutPayroll.startBatch(e.id));
            var repository = new SqliteEmploymentRepository(e.connection);
            var withoutQualifications = new EmploymentService(e.employees, e.buildings, e.occupations,
                    (npc, role) -> false, repository::save, e.facts, e.lock);
            var production = production(e, withoutQualifications, id -> e.payroll.get());
            assertThrows(IllegalStateException.class, () -> production.startBatch(e.id));
            assertThrows(IllegalStateException.class, () -> withoutQualifications.hire(UUID.randomUUID(), e.id, "laborer"));
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.execution().batch());
            assertEquals(14, e.employees.getAll().size());
        }
    }

    private ProductionService production(TestEconomy e, WorkforcePort workforce, BuildingPayrollPort payroll) {
        return new ProductionService(e.buildings, e.configurations, e.repository, e.equipment, e.inventory, payroll, workforce, e.journal, e.lock);
    }
}
