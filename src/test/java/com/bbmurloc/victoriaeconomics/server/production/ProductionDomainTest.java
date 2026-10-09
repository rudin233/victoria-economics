package com.bbmurloc.victoriaeconomics.server.production;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.production.batch.*;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ResolvedProductionRecipe;
import com.bbmurloc.victoriaeconomics.server.production.method.*;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRecord;
import com.bbmurloc.victoriaeconomics.server.workforce.staffing.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProductionDomainTest {
    private ProductionMethodRules rules() {
        return new ProductionMethodRules(new BuildingTypeDefinition("factory", "industry", "equipment", 100, List.of("base", "later")),
                List.of(new ProductionMethodGroupDefinition("base", "factory", List.of("base1", "base2")),
                        new ProductionMethodGroupDefinition("later", "factory", List.of("later1", "later2"))),
                List.of(new ProductionMethodDefinition("base1", "base", 1, Map.of("ore", 10.0), Map.of("metal", 10.0), Map.of("worker", 10)),
                        new ProductionMethodDefinition("base2", "base", 2, Map.of("ore", 10.0), Map.of("metal", 20.0), Map.of("worker", 10)),
                        new ProductionMethodDefinition("later1", "later", 1, Map.of(), Map.of(), Map.of()),
                        new ProductionMethodDefinition("later2", "later", 2, Map.of(), Map.of(), Map.of("worker", -5))));
    }

    @Test
    void rejectsIncompleteForeignAndTooHighCombinationsWithoutChangingState() {
        var config = new ProductionMethodConfiguration(rules(), rules().defaults(), null);
        assertThrows(IllegalArgumentException.class, () -> config.requestTarget(Map.of("base", "base1"), false));
        assertThrows(IllegalArgumentException.class, () -> config.requestSelection("later", "base2", false));
        assertThrows(IllegalArgumentException.class, () -> config.requestSelection("later", "later2", false));
        assertEquals(rules().defaults(), config.effective().methods());
    }

    @Test
    void loweringBaseTierIsRejectedAgainstWholeCombination() {
        var config = new ProductionMethodConfiguration(rules(), Map.of("base", "base2", "later", "later2"), null);
        assertThrows(IllegalArgumentException.class, () -> config.requestSelection("base", "base1", false));
        assertEquals("base2", config.effective().methods().get("base"));
    }

    @Test
    void pendingTargetCanBeReplacedCompletelyAndCancelled() {
        var config = new ProductionMethodConfiguration(rules(), rules().defaults(), null);
        config.requestTarget(Map.of("base", "base2", "later", "later2"), true);
        config.requestTarget(Map.of("base", "base2", "later", "later1"), true);
        assertEquals("later1", config.pending().orElseThrow().methods().get("later"));
        assertEquals(rules().defaults(), config.effective().methods());
        config.cancelPending();
        assertTrue(config.pending().isEmpty());
        config.requestSelection("base", "base2", true);
        assertTrue(config.applyPending());
        assertFalse(config.applyPending());
        config.requestTarget(config.effective().methods(), true);
        assertTrue(config.pending().isEmpty());
    }

    private List<EmploymentRecord> workers(UUID building, String occupation, int count, int offset) {
        List<EmploymentRecord> result = new ArrayList<>();
        for (int i = 0; i < count; i++)
            result.add(new EmploymentRecord(new UUID(0, offset + i + 1), building, occupation));
        return result;
    }

    @Test
    void choosesBottleneckSupportingMinimumWithCeiling() {
        UUID building = UUID.randomUUID();
        var employees = new ArrayList<>(workers(building, "laborer", 90, 0));
        employees.addAll(workers(building, "engineer", 3, 100));
        var plan = new WorkforcePlanningService().plan(building, Map.of("laborer", 100, "engineer", 10), 100, 100, employees, (id, role) -> true);
        assertEquals(0.3, plan.speed());
        assertEquals(30, plan.employees().get("laborer").size());
        assertEquals(3, plan.employees().get("engineer").size());
        var odd = new WorkforcePlanningService().plan(building, Map.of("laborer", 11, "engineer", 10), 100, 100, employees, (id, role) -> true);
        assertEquals(4, odd.employees().get("laborer").size());
    }

    @Test
    void tenPercentThresholdUsesIntegerBoundaries() {
        UUID building = UUID.randomUUID();
        var planner = new WorkforcePlanningService();
        assertThrows(IllegalStateException.class, () -> planner.plan(building, Map.of("worker", 11), 100, 100, workers(building, "worker", 1, 0), (id, role) -> true));
        assertEquals(2.0 / 11, planner.plan(building, Map.of("worker", 11), 100, 100, workers(building, "worker", 2, 0), (id, role) -> true).speed());
        assertEquals(0.1, planner.plan(building, Map.of("worker", 10), 100, 100, workers(building, "worker", 1, 0), (id, role) -> true).speed());
        assertThrows(IllegalStateException.class, () -> planner.plan(building, Map.of("worker", 4), 24, 100, workers(building, "worker", 4, 0), (id, role) -> true));
    }

    @Test
    void equipmentCapsParticipationWithoutAnExtraSpeedMultiplier() {
        UUID building = UUID.randomUUID();
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", 10), 50, 100, workers(building, "worker", 10, 0), (id, role) -> true);
        assertEquals(5, plan.employees().get("worker").size());
        assertEquals(0.5, plan.speed());
    }

    @Test
    void filtersQualificationAndFormalOccupationAndOtherLocations() {
        UUID building = UUID.randomUUID();
        var employees = new ArrayList<>(workers(building, "worker", 4, 0));
        employees.addAll(workers(building, "other", 10, 100));
        employees.addAll(workers(UUID.randomUUID(), "worker", 10, 200));
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", 10), 100, 100, employees, (id, role) -> id.getLeastSignificantBits() != 1);
        assertEquals(3, plan.employees().get("worker").size());
        assertFalse(plan.employees().get("worker").contains(new UUID(0, 1)));
        assertThrows(UnsupportedOperationException.class, () -> plan.employees().get("worker").clear());
    }

    private ProductionBatchConfiguration configuration(int selected, int demand) {
        UUID building = UUID.randomUUID();
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", demand), 100, 100,
                workers(building, "worker", selected, 0), (id, role) -> true);
        return new ProductionBatchConfiguration(Map.of("base", "base1"), new ResolvedProductionRecipe(Map.of("ore", 10.0), Map.of("metal", 10.0), Map.of("worker", demand)), 100, 100, plan);
    }

    @Test
    void exactTickProgressAndSettlementOccupyTheBuilding() {
        UUID building = UUID.randomUUID(), id = UUID.randomUUID();
        var execution = new ProductionExecution(building);
        var immutableInitial = execution.start(id, configuration(1, 3));
        execution.tick(3599);
        assertTrue(execution.batch().isActive());
        execution.tick(1);
        assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, execution.batch().getStatus());
        assertTrue(execution.hasUnfinishedBatch());
        assertEquals(0, immutableInitial.getProgress());
        assertThrows(IllegalStateException.class, () -> execution.start(UUID.randomUUID(), configuration(1, 3)));
        execution.settlementCompleted(id);
        assertFalse(execution.hasUnfinishedBatch());
        execution.methodsApplied();
        execution.equipmentApplied();
        execution.workforceReconciled();
        assertTrue(execution.lastBatch().isCompleted());
        assertNull(execution.batch());
    }

    @Test
    void pausedProductionKeepsCommitmentAndTerminatesAtActualProgress() {
        var execution = new ProductionExecution(UUID.randomUUID());
        execution.start(UUID.randomUUID(), configuration(10, 10));
        execution.tick(300);
        execution.pause();
        execution.tick(10000);
        assertEquals(0.25, execution.batch().getProgress());
        assertTrue(execution.hasUnfinishedBatch());
        execution.resume();
        execution.tick(300);
        execution.terminate();
        assertEquals(0.5, execution.batch().getProgress());
        assertEquals(ProductionBatch.Status.SETTLING_ABORTED, execution.batch().getStatus());
        execution.terminate();
        assertEquals(0.5, execution.batch().getProgress());
    }

    @Test
    void invalidRecipeQuantitiesAndUnsafeSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedProductionRecipe(Map.of("ore", Double.NaN), Map.of("metal", 1.0), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedProductionRecipe(Map.of(), Map.of("metal", -1.0), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProductionBatch(UUID.randomUUID(), UUID.randomUUID(), configuration(10, 10), 1199, ProductionBatch.Status.COMPLETED));
        var execution = new ProductionExecution(UUID.randomUUID());
        execution.start(UUID.randomUUID(), configuration(10, 10));
        execution.tick(Long.MAX_VALUE);
        assertEquals(1.0, execution.batch().getProgress());
        assertThrows(IllegalArgumentException.class, () -> execution.tick(-1));
    }

    @Test
    void clockHasNoMinecraftOrChunkDependency() {
        var execution = new ProductionExecution(UUID.randomUUID());
        execution.start(UUID.randomUUID(), configuration(10, 10));
        var clock = new EconomicClock(() -> execution.tick(1));
        for (int i = 0; i < 1199; i++) clock.onServerTick();
        assertTrue(execution.batch().isActive());
        clock.onServerTick();
        assertEquals(1200, clock.elapsedTicks());
        assertTrue(execution.batch().needsSettlement());
    }
}
