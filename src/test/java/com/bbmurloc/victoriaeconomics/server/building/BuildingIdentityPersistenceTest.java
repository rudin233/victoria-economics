package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import com.bbmurloc.victoriaeconomics.server.workforce.LaborTestWorld;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingIdentityPersistenceTest {
    @TempDir Path directory;
    private Path file() { return directory.resolve("identity.sqlite"); }

    @Test void explicitDirectoriesAreUniqueAndNeverCreateCashOrPopulation() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            assertEquals(TestIdentities.COMPANY, w.companies.find(TestIdentities.COMPANY).orElseThrow().id());
            assertEquals(TestIdentities.REGION, w.regions.find(TestIdentities.REGION).orElseThrow().id());
            assertThrows(IllegalStateException.class, () -> w.companies.create(new Company(TestIdentities.COMPANY)));
            assertThrows(IllegalStateException.class, () -> w.regions.create(new Region(TestIdentities.REGION)));
            assertEquals(0, w.count("regional_labor_supply"));
            assertEquals(0, w.count("economic_buildings"));
            assertThrows(IllegalArgumentException.class, () -> CompanyId.parse("player-name"));
            assertThrows(IllegalArgumentException.class, () -> RegionId.parse("0-0-0-0-1"));
            assertThrows(NullPointerException.class, () -> new CompanyId(null));
        }
    }
    @Test void creationRequiresRegisteredIdentitiesAndDoesNotOverwriteAnExistingId() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            assertThrows(IllegalStateException.class, () -> w.buildings.createBuilding("tooling_workshop"));
            assertThrows(IllegalStateException.class, () -> w.buildings.createBuilding("tooling_workshop", new CompanyId(UUID.randomUUID()), TestIdentities.REGION));
            assertThrows(IllegalStateException.class, () -> w.buildings.createBuilding("tooling_workshop", TestIdentities.COMPANY, new RegionId(UUID.randomUUID())));
            assertTrue(w.registry.getAll().isEmpty());
            var root = w.create();
            assertThrows(IllegalStateException.class, () -> w.buildings.createBuilding(root.getId(), "tooling_workshop", TestIdentities.COMPANY, TestIdentities.REGION));
            assertEquals(1, w.count("economic_buildings"));
            assertEquals(1, w.registry.getAll().size());
            assertEquals(0, w.identities.load(root.getId()).identityRevision());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"economic_buildings", "production_method_configurations", "production_executions", "building_staffing", "payroll_accruals", "payroll_employment_history"})
    void failureAtAnyRootRollsBackAllFourAndPublishesNothing(String table) throws Exception {
        try (var w = new LaborTestWorld(file())) {
            w.sql("CREATE TRIGGER reject_root BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT,'injected creation failure'); END");
            assertThrows(IllegalStateException.class, w::create);
            for (String root : List.of("economic_buildings", "production_method_configurations", "production_executions", "building_staffing", "payroll_accruals", "payroll_employment_history")) assertEquals(0, w.count(root));
            assertTrue(w.registry.getAll().isEmpty());
        }
    }
    @Test void missingRegionalWageConfigurationCannotCreateAStaffableBuilding()throws Exception{
        try(var w=new LaborTestWorld(file())){
            w.sql("DELETE FROM regional_wage_rates");assertThrows(IllegalStateException.class,w::create);
            for(String table:List.of("economic_buildings","production_method_configurations","production_executions","building_staffing","payroll_accruals"))assertEquals(0,w.count(table));
            assertTrue(w.registry.getAll().isEmpty());assertEquals(0,w.count("finance_accounts"));
        }
    }
    @Test void identityCasAndOperationStatusAreIndependentOfLifecycleAndOtherRoots() throws Exception {
        UUID id;
        try (var w = new LaborTestWorld(file())) {
            var root = w.create(); id = root.getId();
            var stopped = root.withStatus(BuildingStatus.STOPPED);
            w.identities.save(stopped, root.identityRevision());
            assertThrows(IllegalStateException.class, () -> w.identities.save(root.withStatus(BuildingStatus.DISABLED), 0));
            var current = w.identities.load(id);
            assertEquals(1, current.identityRevision());
            assertEquals(BuildingLifecycle.EXISTING, current.lifecycle());
            assertEquals(BuildingStatus.STOPPED, current.getStatus());
            assertEquals(0, w.staffing.load(id).revision());
            assertEquals(0, w.methods.load(id).configurationRevision());
            assertEquals(TestIdentities.COMPANY, current.ownerCompanyId());
        }
        try (var w = new LaborTestWorld(file())) {
            var current = w.identities.load(id);
            assertEquals(1, current.identityRevision());
            assertEquals(TestIdentities.REGION, current.regionId());
        }
    }
    @Test void historicalIdentityCannotBePhysicallyDeletedOrUpdatedAsAnOwnershipCommand() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            var root = w.create();
            var other = new CompanyId(UUID.randomUUID()); w.companies.create(new Company(other));
            var forged = new EconomicBuilding(root.getId(), root.getBuildingTypeId(), other, root.regionId(), root.lifecycle(), root.getStatus(), 1);
            assertThrows(IllegalStateException.class, () -> w.identities.save(forged, 0));
            assertThrows(UnsupportedOperationException.class, () -> w.identities.delete(root.getId()));
            // Isolated historical row fixture, not a demolition command.
            w.sql("UPDATE economic_buildings SET lifecycle='DEMOLISHED' WHERE id='" + root.getId() + "'");
            assertThrows(IllegalStateException.class, () -> w.identities.load(root.getId()).withStatus(BuildingStatus.ACTIVE));
            assertEquals(1, w.count("economic_buildings"));
        }
    }
    @Test void identityRevisionOverflowAndSqlFailureDoNotPublishACacheMutation() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            var root = w.create();
            w.sql("UPDATE economic_buildings SET identity_revision=9223372036854775807");
            assertThrows(ArithmeticException.class, () -> w.buildings.stopBuilding(root.getId()));
            assertEquals(BuildingStatus.ACTIVE, w.registry.get(root.getId()).getStatus());
            w.sql("UPDATE economic_buildings SET identity_revision=0");
            w.sql("CREATE TRIGGER reject_status BEFORE UPDATE ON economic_buildings BEGIN SELECT RAISE(ABORT,'status failure'); END");
            assertThrows(IllegalStateException.class, () -> w.buildings.stopBuilding(root.getId()));
            assertEquals(0, w.registry.get(root.getId()).identityRevision());
            assertEquals(BuildingStatus.ACTIVE, w.identities.load(root.getId()).getStatus());
        }
    }
}
