package com.bbmurloc.victoriaeconomics.server.production.domain;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.bbmurloc.victoriaeconomics.server.production.application.EconomicClock;

class ProductionDomainTest {
    private ProductionMethodRules rules() {
        return new ProductionMethodRules(new BuildingTypeDefinition("factory", "industry", List.of("base", "later")),
                List.of(new ProductionMethodGroupDefinition("base", "factory", List.of("base1", "base2")),
                        new ProductionMethodGroupDefinition("later", "factory", List.of("later1", "later2"))),
                List.of(new ProductionMethodDefinition("base1", "base", 1, Map.of("ore", 10L), Map.of("metal", 10L), Map.of("worker", 10)),
                        new ProductionMethodDefinition("base2", "base", 2, Map.of("ore", 10L), Map.of("metal", 20L), Map.of("worker", 10)),
                        new ProductionMethodDefinition("later1", "later", 1, Map.of(), Map.of(), Map.of()),
                        new ProductionMethodDefinition("later2", "later", 2, Map.of(), Map.of(), Map.of("worker", -5))));
    }

    @Test
    void rejectsIncompleteForeignAndTooHighCombinationsWithoutChangingState() {
        var config = new ProductionMethodConfiguration(UUID.randomUUID(), rules(), rules().defaults(), null);
        assertThrows(IllegalArgumentException.class, () -> config.requestTarget(Map.of("base", "base1"), false));
        assertThrows(IllegalArgumentException.class, () -> config.requestSelection("later", "base2", false));
        assertThrows(IllegalArgumentException.class, () -> config.requestSelection("later", "later2", false));
        assertEquals(rules().defaults(), config.effective().methods());
    }

    @Test
    void loweringBaseTierIsRejectedAgainstWholeCombination() {
        var config = new ProductionMethodConfiguration(UUID.randomUUID(), rules(), Map.of("base", "base2", "later", "later2"), null);
        assertThrows(IllegalArgumentException.class, () -> config.requestSelection("base", "base1", false));
        assertEquals("base2", config.effective().methods().get("base"));
    }

    @Test
    void pendingTargetCanBeReplacedCompletelyAndCancelled() {
        var config = new ProductionMethodConfiguration(UUID.randomUUID(), rules(), rules().defaults(), null);
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

    private List<EmploymentFact> workers(UUID building, String occupation, int count, int offset) {
        List<EmploymentFact> result = new ArrayList<>();
        for (int i = 0; i < count; i++)
            result.add(new EmploymentFact(new UUID(0, offset + i + 1), building, occupation));
        return result;
    }

    @Test
    void choosesBottleneckSupportingMinimumWithCeiling() {
        UUID building = UUID.randomUUID();
        var employees = new ArrayList<>(workers(building, "laborer", 90, 0));
        employees.addAll(workers(building, "engineer", 3, 100));
        var plan = new WorkforcePlanningService().plan(building, Map.of("laborer", 100, "engineer", 10), employees, (id, role) -> true);
        assertEquals(0.3, plan.speed());
        assertEquals(30, plan.employees().get("laborer").size());
        assertEquals(3, plan.employees().get("engineer").size());
        var odd = new WorkforcePlanningService().plan(building, Map.of("laborer", 11, "engineer", 10), employees, (id, role) -> true);
        assertEquals(4, odd.employees().get("laborer").size());
    }

    @Test
    void tenPercentThresholdUsesIntegerBoundaries() {
        UUID building = UUID.randomUUID();
        var planner = new WorkforcePlanningService();
        assertThrows(IllegalStateException.class, () -> planner.plan(building, Map.of("worker", 11), workers(building, "worker", 1, 0), (id, role) -> true));
        assertEquals(2.0 / 11, planner.plan(building, Map.of("worker", 11), workers(building, "worker", 2, 0), (id, role) -> true).speed());
        assertEquals(0.1, planner.plan(building, Map.of("worker", 10), workers(building, "worker", 1, 0), (id, role) -> true).speed());
        assertThrows(IllegalStateException.class, () -> planner.plan(building, Map.of("worker", 10), workers(building, "worker", 0, 0), (id, role) -> true));
    }

    @Test
    void participationIsLimitedOnlyByFormalDemandWithoutAnAssetFactor() {
        UUID building = UUID.randomUUID();
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", 10), workers(building, "worker", 12, 0), (id, role) -> true);
        assertEquals(10, plan.employees().get("worker").size());
        assertEquals(1.0, plan.speed());
    }

    @Test
    void filtersQualificationAndFormalOccupationAndOtherLocations() {
        UUID building = UUID.randomUUID();
        var employees = new ArrayList<>(workers(building, "worker", 4, 0));
        employees.addAll(workers(building, "other", 10, 100));
        employees.addAll(workers(UUID.randomUUID(), "worker", 10, 200));
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", 10), employees, (id, role) -> id.getLeastSignificantBits() != 1);
        assertEquals(3, plan.employees().get("worker").size());
        assertFalse(plan.employees().get("worker").contains(new UUID(0, 1)));
        assertThrows(UnsupportedOperationException.class, () -> plan.employees().get("worker").clear());
    }

    @Test
    void duplicateFactsDoNotInflateStaffingAndSelectionRemainsDeterministic() {
        UUID building = UUID.randomUUID();
        var employees = new ArrayList<>(workers(building, "worker", 3, 0));
        employees.addAll(employees.subList(0, 3));
        Collections.reverse(employees);
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", 10),
                employees, (id, role) -> true);
        assertEquals(0.3, plan.speed());
        assertEquals(List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3)), plan.employees().get("worker"));
    }

    @Test
    void oneNpcCannotParticipateInTwoFormalOccupations() {
        UUID building = UUID.randomUUID(), employee = UUID.randomUUID();
        var facts = List.of(new EmploymentFact(employee, building, "worker"),
                new EmploymentFact(employee, building, "engineer"));
        assertThrows(IllegalArgumentException.class, () -> new WorkforcePlanningService().plan(building,
                Map.of("worker", 10, "engineer", 10), facts, (id, role) -> true));
    }

    private ProductionBatchConfiguration configuration(int selected, int demand) {
        UUID building = UUID.randomUUID();
        var plan = new WorkforcePlanningService().plan(building, Map.of("worker", demand),
                workers(building, "worker", selected, 0), (id, role) -> true);
        return new ProductionBatchConfiguration(Map.of("base", "base1"), new ResolvedProductionRecipe(Map.of("ore", 10L), Map.of("metal", 10L), Map.of("worker", demand)), plan);
    }

    @Test
    void exactTickProgressAndSettlementOccupyTheBuilding() {
        UUID building = UUID.randomUUID(), id = UUID.randomUUID();
        var execution = new ProductionExecution(building);
        var immutableInitial = execution.start(id, configuration(1, 3));
        execution.tick(599);
        assertTrue(execution.batch().isActive());
        execution.tick(1);
        assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, execution.batch().getStatus());
        assertTrue(execution.hasUnfinishedBatch());
        assertEquals(0, immutableInitial.getProgress());
        assertThrows(IllegalStateException.class, () -> execution.start(UUID.randomUUID(), configuration(1, 3)));
        execution.settlementCompleted(id);
        assertFalse(execution.hasUnfinishedBatch());
        execution.methodsApplied();
        execution.advanceLegacyBoundary();
        execution.workforceReconciled();
        assertTrue(execution.lastBatch().isCompleted());
        assertNull(execution.batch());
    }

    @Test
    void pausedProductionKeepsCommitmentAndTerminatesAtActualProgress() {
        var execution = new ProductionExecution(UUID.randomUUID());
        execution.start(UUID.randomUUID(), configuration(10, 10));
        execution.tick(50);
        execution.pause();
        execution.tick(10000);
        assertEquals(0.25, execution.batch().getProgress());
        assertTrue(execution.hasUnfinishedBatch());
        execution.resume();
        execution.tick(50);
        execution.terminate();
        assertEquals(0.5, execution.batch().getProgress());
        assertEquals(ProductionBatch.Status.SETTLING_ABORTED, execution.batch().getStatus());
        execution.terminate();
        assertEquals(0.5, execution.batch().getProgress());
    }

    @Test
    void invalidRecipeQuantitiesAndUnsafeSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedProductionRecipe(Map.of("ore", 0L), Map.of("metal", 1L), Map.of("worker", 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedProductionRecipe(Map.of(), Map.of("metal", -1L), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProductionBatch(UUID.randomUUID(), UUID.randomUUID(), configuration(10, 10), 199, ProductionBatch.Status.COMPLETED));
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
        for (int i = 0; i < 199; i++) clock.onServerTick();
        assertTrue(execution.batch().isActive());
        clock.onServerTick();
        assertEquals(200, clock.elapsedTicks());
        assertTrue(execution.batch().needsSettlement());
    }

    @Test void threeFifthsSpeedCompletesOnTick334WithExactIntegerProgress() {
        var execution = new ProductionExecution(UUID.randomUUID());
        execution.start(UUID.randomUUID(), configuration(3, 5));
        execution.tick(333);
        assertEquals(999, execution.batch().getProgressUnits());
        assertEquals(1000, execution.batch().getMaximumProgressUnits());
        assertTrue(execution.batch().isActive());
        execution.tick(1);
        assertEquals(1000, execution.batch().getProgressUnits());
        assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, execution.batch().getStatus());
    }

    private ProductionMethodRules deltaRules(long initialInput, long inputDelta, long outputDelta, int workerDelta) {
        return new ProductionMethodRules(new BuildingTypeDefinition("delta", "industry", List.of("base", "modifier")),
                List.of(new ProductionMethodGroupDefinition("base", "delta", List.of("base")),
                        new ProductionMethodGroupDefinition("modifier", "delta", List.of("modifier"))),
                List.of(new ProductionMethodDefinition("base", "base", 1, Map.of("ore", initialInput), Map.of("metal", 5L), Map.of("worker", 5)),
                        new ProductionMethodDefinition("modifier", "modifier", 1, Map.of("ore", inputDelta), Map.of("metal", outputDelta, "tools", 7L), Map.of("worker", workerDelta))));
    }

    @Test void signedIntegerPmDeltasCancelFinalZerosWithoutRounding() {
        var recipe = deltaRules(10, -10, -5, -4).resolve(Map.of("base", "base", "modifier", "modifier"));
        assertTrue(recipe.inputs().isEmpty());
        assertEquals(Map.of("tools", 7L), recipe.outputs());
        assertEquals(Map.of("worker", 1), recipe.requiredWorkers());
    }

    @Test void negativeFinalAmountsZeroWorkersAndArithmeticOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> deltaRules(10, -11, 0, 0).defaults());
        assertThrows(IllegalArgumentException.class, () -> deltaRules(10, 0, 0, -5).defaults());
        assertThrows(ArithmeticException.class, () -> deltaRules(Long.MAX_VALUE, 1, 0, 0).defaults());
        assertThrows(ArithmeticException.class, () -> deltaRules(1, 0, Long.MAX_VALUE, 0).defaults());
        assertThrows(IllegalArgumentException.class, () -> new ResolvedProductionRecipe(Map.of(), Map.of("metal", 0L), Map.of("worker", 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedProductionRecipe(Map.of(), Map.of("metal", 1L), Map.of()));
    }

    @Test void defaultPigIronCombinationIsRejectedWithoutInventingOccupations() {
        var types = new com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry();
        var groups = new ProductionMethodGroupRegistry();
        var methods = new ProductionMethodRegistry();
        com.bbmurloc.victoriaeconomics.common.definition.DefaultDefinitions.registerBuildingTypes(types);
        com.bbmurloc.victoriaeconomics.common.definition.DefaultDefinitions.registerProductionMethodGroups(groups);
        com.bbmurloc.victoriaeconomics.common.definition.DefaultDefinitions.registerProductionMethods(methods);
        var rules = new ProductionRecipeResolver(types, groups, methods).rulesFor("tooling_workshop");
        assertFalse(rules.resolve(rules.defaults()).requiredWorkers().isEmpty());
        assertTrue(methods.get("pig_iron_tools").workerChanges().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> rules.resolve(Map.of("tooling_workshop_base", "pig_iron_tools",
                "tooling_workshop_automation", "hand_assembly")));
    }
}
