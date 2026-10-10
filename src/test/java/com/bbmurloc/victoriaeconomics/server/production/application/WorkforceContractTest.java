package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.EmploymentFact;
import com.bbmurloc.victoriaeconomics.server.production.domain.WorkforcePlanningService;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteEmploymentRepository;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRecord;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class WorkforceContractTest {
    @TempDir
    Path directory;

    private EmploymentService adapter(TestEconomy economy, BiPredicate<UUID, String> qualified) {
        var repository = new SqliteEmploymentRepository(economy.connection);
        return new EmploymentService(economy.employees, economy.buildings, economy.occupations,
                qualified, repository::save, economy.facts, economy.lock);
    }

    private ProductionService production(TestEconomy economy, EmploymentService workforce) {
        return new ProductionService(economy.buildings, economy.configurations, economy.repository, economy.equipment, economy.inventory,
                building -> economy.payroll.get(), workforce, economy.journal, economy.lock);
    }

    @Test
    void adapterKeepsUnqualifiedFormalEmployeesButExcludesOtherBuildingsAndPendingDismissals() throws Exception {
        try (var economy = new TestEconomy(directory.resolve("economy.sqlite"), true)) {
            UUID unqualified = new UUID(1, 1), pending = new UUID(1, 2);
            var workforce = adapter(economy, (npc, role) -> !npc.equals(unqualified));
            var other = economy.buildingService.createBuilding("tooling_workshop");
            economy.employment.hire(UUID.randomUUID(), other.getId(), "laborer");
            economy.employees.requestDismissal(pending);

            var facts = workforce.effectiveEmployment(economy.id);
            assertEquals(13, facts.size());
            assertTrue(facts.contains(new EmploymentFact(unqualified, economy.id, "laborer")));
            assertFalse(workforce.qualified(unqualified, "laborer"));
            assertTrue(facts.stream().allMatch(fact -> fact.buildingId().equals(economy.id)));
            assertFalse(facts.stream().anyMatch(fact -> fact.employeeId().equals(pending)));
            assertThrows(UnsupportedOperationException.class, facts::clear);

            var plan = new WorkforcePlanningService().plan(economy.id, Map.of("laborer", 10, "machinist", 4),
                    100, 100, facts, workforce::qualified);
            assertEquals(0.8, plan.speed());
            assertEquals(8, plan.employees().get("laborer").size());
            assertEquals(4, plan.employees().get("machinist").size());
            assertFalse(plan.employees().get("laborer").contains(unqualified));
            assertNotNull(workforce.getEmployment(unqualified));
        }
    }

    @Test
    void unqualifiedNewEmploymentStillViolatesPositionCapacityAtFinalCommit() throws Exception {
        try (var economy = new TestEconomy(directory.resolve("economy.sqlite"), true)) {
            UUID excess = UUID.randomUUID();
            var workforce = adapter(economy, (npc, role) -> !npc.equals(excess));
            var production = production(economy, workforce);
            // Simulate an authority change after reservation; qualification filtering must not hide overstaffing.
            economy.inventory.afterReserve = () -> economy.employees.add(new EmploymentRecord(excess, economy.id, "laborer"));

            var failure = assertThrows(IllegalStateException.class, () -> production.startBatch(economy.id));
            assertTrue(failure.getMessage().contains("Employment exceeds current PM capacity"));
            assertEquals(11, workforce.effectiveEmployment(economy.id).stream()
                    .filter(fact -> fact.occupationId().equals("laborer")).count());
            assertNull(economy.execution().batch());
            assertTrue(economy.stockState().reservations().isEmpty());
            assertTrue(economy.journal.pendingStarts().isEmpty());
            assertNull(economy.equipment.getHolding(economy.id).state().protectedByBatch());
            assertEquals(100, economy.quantity("wood"));
        }
    }

    @Test
    void qualificationLossAfterReservationIsRecheckedAndCompensated() throws Exception {
        try (var economy = new TestEconomy(directory.resolve("economy.sqlite"), true)) {
            var qualified = new AtomicBoolean(true);
            var workforce = adapter(economy, (npc, role) -> qualified.get() || !npc.equals(new UUID(1, 1)));
            var production = production(economy, workforce);
            economy.inventory.afterReserve = () -> qualified.set(false);

            assertThrows(IllegalStateException.class, () -> production.startBatch(economy.id));
            assertEquals(14, workforce.effectiveEmployment(economy.id).size());
            assertNull(economy.execution().batch());
            assertTrue(economy.stockState().reservations().isEmpty());
            assertTrue(economy.journal.pendingStarts().isEmpty());
            assertNull(economy.equipment.getHolding(economy.id).state().protectedByBatch());

            economy.inventory.afterReserve = () -> {};
            qualified.set(true);
            assertNotNull(production.startBatch(economy.id));
        }
    }
}
