package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.department.hr.StaffingExpectation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StaffingQueryTest {
    @TempDir Path directory;

    private StaffingCalculator calculator(TestEconomy economy) {
        return new StaffingCalculator(economy.employees, economy.types, economy.configurations);
    }

    @Test void fullEmploymentNeedsNoAssetAndHrTargetsCannotCreateOrRemoveProductionCapacity() throws Exception {
        try (var e = new TestEconomy(directory.resolve("full.sqlite"), true)) {
            var query = calculator(e);
            var before = e.employees.state();
            var full = query.calculate(e.building());
            var zeroTarget = query.calculate(e.building(), new StaffingExpectation(Map.of("laborer", 0.0)));
            assertTrue(full.canStart());
            assertTrue(zeroTarget.canStart());
            assertEquals(1.0, full.productionSpeed());
            assertEquals(1.0, zeroTarget.productionSpeed());
            assertEquals(0, zeroTarget.occupations().get("laborer").hiringTargetWorkers());
            assertEquals(10, zeroTarget.occupations().get("laborer").effectiveWorkers());
            assertEquals(10, zeroTarget.occupations().get("laborer").workersAboveHiringTarget());
            assertEquals(before, e.employees.state());
            assertThrows(UnsupportedOperationException.class, () -> full.occupations().clear());
            assertEquals(1.0, e.production.startBatch(e.id).getConfiguration().productionSpeed());
        }
    }

    @Test void staffingQueryUsesOnlyTheBottleneckOccupationRatio() throws Exception {
        try (var e = new TestEconomy(directory.resolve("ratio.sqlite"), true)) {
            for (int i = 7; i <= 10; i++) e.employment.fire(new UUID(1, i));
            e.employment.fire(new UUID(2, 4));
            var snapshot = calculator(e).calculate(e.building(), new StaffingExpectation(Map.of("laborer", 0.8)));
            assertTrue(snapshot.canStart());
            assertEquals(0.6, snapshot.productionSpeed());
            assertEquals(6, snapshot.occupations().get("laborer").effectiveWorkers());
            assertEquals(8, snapshot.occupations().get("laborer").targetWorkers());
            assertEquals(2, snapshot.occupations().get("laborer").hiringShortfall());
            assertEquals(0.75, snapshot.occupations().get("machinist").staffingRatio());
            assertEquals(0.6, e.production.startBatch(e.id).getConfiguration().productionSpeed());
        }
    }

    @Test void eachOccupationStillNeedsTenPercentAndTargetsNeverSupplyMissingWorkers() throws Exception {
        try (var e = new TestEconomy(directory.resolve("minimum.sqlite"), true)) {
            for (int i = 2; i <= 10; i++) e.employment.fire(new UUID(1, i));
            for (int i = 2; i <= 4; i++) e.employment.fire(new UUID(2, i));
            var query = calculator(e);
            assertTrue(query.calculate(e.building()).canStart());
            assertEquals(0.1, query.calculate(e.building()).productionSpeed());
            e.employment.fire(new UUID(1, 1));
            assertFalse(query.calculate(e.building()).canStart());
            assertEquals(0.0, query.calculate(e.building()).productionSpeed());
            assertEquals(1, e.employees.getAll().size());
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
