package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteStaffingQuery;
import com.bbmurloc.victoriaeconomics.server.workforce.application.StaffingApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StaffingQueryTest {
    @TempDir Path directory;
    private ProductionRecipeResolver recipes(TestEconomy e) { return new ProductionRecipeResolver(e.types, e.groups, e.methods); }
    private StaffingCalculator calculator(TestEconomy e) { return new StaffingCalculator(new SqliteStaffingQuery(e.connection, recipes(e), e.occupations, e.lock)); }
    private StaffingApplicationService commands(TestEconomy e) { return new StaffingApplicationService(e.connection, recipes(e), e.occupations, e.lock); }

    @Test void stagedQueryDoesNotMistakeNpcEmploymentForNewStaffingOrInventPopulation() throws Exception {
        try (var e = new TestEconomy(directory.resolve("full.sqlite"), true)) {
            var before = e.employees.state();
            var snapshot = calculator(e).calculate(e.id);
            assertEquals(0, snapshot.occupations().get("laborer").employed());
            assertEquals(10, snapshot.occupations().get("laborer").positionCapacity());
            assertTrue(snapshot.occupations().get("laborer").pending().isEmpty());
            assertTrue(snapshot.occupations().get("laborer").totalSupply().isEmpty());
            assertTrue(snapshot.occupations().get("laborer").available().isEmpty());
            assertEquals(before, e.employees.state());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.occupations().clear());
            assertEquals(1.0, e.production.startBatch(e.id).getConfiguration().productionSpeed());
        }
    }

    @Test void runningRequestsPersistZeroAndReplacementWithoutChangingTheFrozenBatch() throws Exception {
        Path file = directory.resolve("pending.sqlite");
        try (var e = new TestEconomy(file, true)) {
            var frozen = e.production.startBatch(e.id).getConfiguration();
            commands(e).requestTarget(e.id, "laborer", 8);
            commands(e).requestTarget(e.id, "laborer", 0);
            assertEquals(0, calculator(e).calculate(e.id).occupations().get("laborer").pending().orElseThrow());
            assertEquals(frozen, e.execution().batch().getConfiguration());
            assertThrows(IllegalStateException.class, () -> commands(e).applyEffectiveChanges(e.id));
            assertEquals(14, e.employees.getAll().size());
        }
        try (var e = new TestEconomy(file, false)) {
            assertEquals(0, calculator(e).calculate(e.id).occupations().get("laborer").pending().orElseThrow());
            commands(e).cancelPending(e.id, "laborer");
            assertTrue(calculator(e).calculate(e.id).occupations().get("laborer").pending().isEmpty());
            assertEquals(0, calculator(e).calculate(e.id).occupations().get("laborer").employed());
        }
    }

    @Test void pendingCountsCannotSupplyMissingNpcWorkersOrBypassTenPercent() throws Exception {
        try (var e = new TestEconomy(directory.resolve("minimum.sqlite"), true)) {
            for (int i = 2; i <= 10; i++) e.employment.fire(new UUID(1, i));
            for (int i = 2; i <= 4; i++) e.employment.fire(new UUID(2, i));
            commands(e).requestTarget(e.id, "laborer", 10);
            assertEquals(0, calculator(e).calculate(e.id).occupations().get("laborer").employed());
            var batch = e.production.startBatch(e.id);
            assertEquals(0.1, batch.getConfiguration().productionSpeed());
            e.production.abortBatch(e.id);
            e.employment.fire(new UUID(1, 1));
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertTrue(e.stockState().reservations().isEmpty());
        }
    }
    @Test void threeFifthsBatchKeepsItsNpcSnapshotAndFinishesOnTick334AfterHiringChanges() throws Exception {
        try (var e = new TestEconomy(directory.resolve("frozen.sqlite"), true)) {
            for (int i = 7; i <= 10; i++) e.employment.fire(new UUID(1, i));
            e.employment.fire(new UUID(2, 4));
            var initial = e.production.startBatch(e.id);
            var frozen = initial.getConfiguration();
            e.production.setAutomatic(e.id, false);
            assertEquals(0.6, frozen.productionSpeed());
            assertEquals(6, frozen.activeEmployeesByOccupation().get("laborer").size());
            assertEquals(3, frozen.activeEmployeesByOccupation().get("machinist").size());
            for (int i = 7; i <= 10; i++) e.employment.hire(new UUID(1, i), e.id, "laborer");
            e.employment.fire(new UUID(1, 1));
            assertTrue(e.employees.dismissalPending(new UUID(1, 1)));
            assertNotNull(e.employment.getEmployment(new UUID(1, 1)));
            assertThrows(UnsupportedOperationException.class, () -> frozen.activeEmployeesByOccupation().get("laborer").clear());
            e.ticks(333);
            assertTrue(e.execution().batch().isActive());
            assertEquals(1998, e.execution().batch().getProgressUnits());
            assertEquals(frozen, e.execution().batch().getConfiguration());
            e.ticks(1);
            assertNull(e.execution().batch());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(frozen, e.execution().lastBatch().getConfiguration());
            assertNull(e.employment.getEmployment(new UUID(1, 1)));
            assertEquals(30, e.quantity("tools"));
            assertEquals(70, e.quantity("wood"));
            assertEquals(1, e.stockState().settlements().size());
        }
    }
}
