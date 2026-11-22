package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.workforce.LaborTestWorld;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.application.StaffingCalculator;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StaffingRepositoryTest {
    @TempDir Path directory;
    private Path file() { return directory.resolve("staffing.sqlite"); }

    @Test void currentOccupationDefinitionsCannotSilentlyAcceptAnUnknownPersistedRole() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            w.sql("INSERT INTO occupation_catalog VALUES('unrecognized-role')");
            assertThrows(IllegalStateException.class, () -> SqliteOccupationCatalog.register(w.connection, w.occupations));
            assertEquals(0, w.count("regional_labor_supply"));
            assertEquals(0,w.count("sqlite_master WHERE name='employment_state'"));
        }
    }

    @Test void regionalSumsSeparateRegionsAndOccupations() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            var other = new com.bbmurloc.victoriaeconomics.server.region.domain.RegionId(UUID.randomUUID());
            w.regions.create(new com.bbmurloc.victoriaeconomics.server.region.domain.Region(other));
            new com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService(w.connection,w.lock).configureRate(
                    new com.bbmurloc.victoriaeconomics.server.payroll.domain.RegionalWageRate(other,new com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId(new UUID(700,1)),0,1,0));
            UUID a = w.create().getId();
            UUID b = w.buildings.createBuilding("tooling_workshop", TestIdentities.COMPANY, other).getId();
            w.setSupply(5);
            SqliteTransactions.write(w.connection, tx -> {
                var repository = w.supply.inTransaction(tx);
                repository.create(new RegionalLaborSupply(other, "laborer", 3, 0));
                repository.create(new RegionalLaborSupply(other, "machinist", 2, 0));
                repository.create(new RegionalLaborSupply(TestIdentities.REGION, "machinist", 2, 0));
                return null;
            });
            for (UUID id : List.of(a, b)) { w.stock(id); w.production.startBatch(id); }
            assertEquals(5, w.supply.occupied(TestIdentities.REGION, "laborer"));
            assertEquals(3, w.supply.occupied(other, "laborer"));
            assertEquals(2, w.supply.occupied(TestIdentities.REGION, "machinist"));
            assertEquals(2, w.supply.occupied(other, "machinist"));
        }
    }

    @Test void emptyStaffingSurvivesRestartWithoutUsingNpcData() throws Exception {
        UUID id;
        try (var w = new LaborTestWorld(file())) {
            id = w.create().getId();
            assertEquals(new BuildingStaffing(id, null, Map.of(), 0), w.staffing.load(id));
            assertNull(w.staffing.load(id).batchId());
            assertEquals(0, w.supply.occupied(TestIdentities.REGION, "laborer"));
            assertEquals(0,w.count("sqlite_master WHERE name='employment_state'"));
        }
        try (var w = new LaborTestWorld(file())) {
            assertNull(w.staffing.load(id).batchId());
            assertTrue(w.staffing.load(id).occupiedByOccupation().isEmpty());
        }
    }
    @Test void invalidRoleCapacityAndFormalEffectiveCommandsAreRejectedWithoutChanges() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            UUID id = w.create().getId(); var before = w.staffing.load(id);
            w.stock(id);w.setSupply(10);
            w.setSupply(TestIdentities.REGION, "machinist", 4);
            w.sql("DELETE FROM regional_wage_rates");
            assertThrows(IllegalStateException.class, () -> w.production.startBatch(id));
            assertEquals(before, w.staffing.load(id));
        }
    }
    @Test void partialHiringAndRegionalQueryDeriveOccupancyFromBatches() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            UUID a = w.create().getId(), b = w.create().getId(); w.setSupply(12);
            w.stock(a);w.stock(b);w.setSupply(TestIdentities.REGION, "machinist", 8);
            assertEquals(0, w.supply.occupied(TestIdentities.REGION, "laborer"));
            w.production.startBatch(a);w.production.startBatch(b);
            assertEquals(10, w.staffing.load(a).occupiedByOccupation().get("laborer"));
            assertEquals(2, w.staffing.load(b).occupiedByOccupation().get("laborer"));
            assertNotNull(w.staffing.load(b).batchId());
            assertEquals(12, w.supply.occupied(TestIdentities.REGION, "laborer"));
            var query = new StaffingCalculator(new SqliteStaffingQuery(w.connection, w.recipes, w.occupations, w.lock));
            var snapshot = query.calculate(b).occupations().get("laborer");
            assertEquals(12, snapshot.totalSupply().orElseThrow()); assertEquals(12, snapshot.regionalOccupied());
            assertEquals(0, snapshot.available().orElseThrow());
            assertThrows(IllegalStateException.class, () -> w.setSupply(11));
            assertEquals(12, w.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
            w.setSupply(20);
            assertEquals(2, w.staffing.load(b).occupiedByOccupation().get("laborer")); // No automatic refill.
            assertEquals(2,w.staffing.load(b).occupiedByOccupation().get("laborer"));
            assertEquals(0,w.count("sqlite_master WHERE name='employment_state'"));
        }
    }
    @Test void supplyAndStaffingCasRejectStaleDetachedSnapshots() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            UUID id = w.create().getId(); w.setSupply(10);
            var staleStaffing = w.staffing.load(id); var staleSupply = w.supply.find(TestIdentities.REGION, "laborer").orElseThrow();
            var batch = w.startPayrollFixture(id, 2); w.setSupply(9);
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(w.connection, tx -> {
                w.staffing.inTransaction(tx).release(id, batch.getId(), staleStaffing.revision()); return null;
            }));
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(w.connection, tx -> {
                w.supply.inTransaction(tx).save(staleSupply.withTotal(8), 0); return null;
            }));
            assertEquals(Map.of("laborer", 2), w.staffing.load(id).occupiedByOccupation());
            assertEquals(9, w.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
        }
    }
    @Test void sqlFailureAndCaughtRepositoryFailureRollBackTheWholeControlledWrite() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            UUID id = w.create().getId(); w.setSupply(10);
            w.sql("CREATE TRIGGER reject_role BEFORE INSERT ON building_staffing_roles BEGIN SELECT RAISE(ABORT,'role failure'); END");
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(w.connection, tx -> {
                var supply = w.supply.inTransaction(tx); var before = supply.find(TestIdentities.REGION, "laborer").orElseThrow();
                supply.save(before.withTotal(9), before.revision());
                try {
                    w.acquirePayrollFixture(tx, id, UUID.randomUUID(), 2, 0);
                } catch (IllegalStateException expected) { /* Catching cannot allow the preceding supply update to commit. */ }
                return null;
            }));
            assertEquals(10, w.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
            assertEquals(0, w.staffing.load(id).revision());
        }
    }
    @Test void supplyAndStaffingValidateFkIntegerBoundsAndOverflowWithoutTruncation() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            UUID id = w.create().getId();
            assertThrows(IllegalArgumentException.class, () -> new RegionalLaborSupply(TestIdentities.REGION, "laborer", -1, 0));
            assertThrows(ArithmeticException.class, () -> new RegionalLaborSupply(TestIdentities.REGION, "laborer", 1, Long.MAX_VALUE).withTotal(2));
            w.setSupply(Long.MAX_VALUE);
            assertEquals(Long.MAX_VALUE, w.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
            assertThrows(SQLException.class, () -> w.sql("INSERT INTO building_staffing_roles VALUES('" + id + "','unknown',1)"));
            assertThrows(SQLException.class, () -> w.sql("INSERT INTO building_staffing_roles VALUES('" + id + "','laborer',1.5)"));
            assertThrows(SQLException.class, () -> w.sql("INSERT INTO building_staffing_roles VALUES('" + id + "','laborer',2147483648)"));
            w.sql("UPDATE building_staffing SET revision=9223372036854775807");
            assertThrows(ArithmeticException.class, () -> w.startPayrollFixture(id, 1));
            assertNull(w.staffing.load(id).batchId());
        }
    }
}
