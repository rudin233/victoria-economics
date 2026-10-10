package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingProductionIsolationTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("economy.sqlite"); }

    @Test void buildingHasOnlyIdentityAndStatusAndBothAggregatesHaveBuildingIdentity() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            assertEquals(Set.of("id", "buildingTypeId", "status"), new HashSet<>(Arrays.stream(EconomicBuilding.class.getDeclaredFields()).map(f -> f.getName()).toList()));
            assertEquals(e.id, e.methods().buildingId());
            assertEquals(e.id, e.execution().buildingId());
            assertEquals(0, e.methods().configurationRevision());
            assertEquals(0, e.execution().executionRevision());
        }
    }

    @Test void jointInitializationRollsBackAllThreeRecordsBeforePublishingIdentity() throws Exception {
        try (var e = new TestEconomy(database(), true); var statement = e.connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER reject_initial_execution BEFORE INSERT ON production_executions BEGIN SELECT RAISE(ABORT,'injected initialization failure'); END");
            assertThrows(IllegalStateException.class, () -> e.buildingService.createBuilding("tooling_workshop"));
            assertEquals(1, e.buildings.getAll().size());
            for (String table : List.of("economic_buildings", "production_method_configurations", "production_executions")) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
        }
    }

    @Test void buildingSaveDoesNotWriteOrRollBackProductionAggregates() throws Exception {
        try (var e = new TestEconomy(database(), true); var statement = e.connection.createStatement()) {
            e.production.startBatch(e.id);
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            long methods = e.methods().configurationRevision(), execution = e.execution().executionRevision();
            statement.executeUpdate("CREATE TRIGGER reject_method_save BEFORE UPDATE ON production_method_configurations BEGIN SELECT RAISE(ABORT,'configuration must not be written'); END");
            statement.executeUpdate("CREATE TRIGGER reject_execution_save BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'execution must not be written'); END");
            assertTrue(e.buildingService.stopBuilding(e.id));
            assertEquals(methods, e.methods().configurationRevision());
            assertEquals(execution, e.execution().executionRevision());
            assertTrue(e.methods().pending().isPresent());
        }
    }

    @Test void detachedQueryMutationsCannotChangeAuthority() throws Exception {
        try (var e = new TestEconomy(database(), true)) {
            e.production.startBatch(e.id);
            var execution = e.production.execution(e.id);
            execution.terminate();
            var methods = e.production.methods(e.id);
            methods.requestSelection("tooling_workshop_base", "efficient_tools", false);
            assertTrue(e.execution().batch().isActive());
            assertEquals("crude_tools", e.methods().effective().methods().get("tooling_workshop_base"));
            assertTrue(e.methods().pending().isEmpty());
        }
    }

    @Test void emptyProductionAggregatesRestoreIndependentlyOnRestart() throws Exception {
        UUID id;
        try (var e = new TestEconomy(database(), true)) { id = e.id; }
        try (var e = new TestEconomy(database(), false)) {
            assertEquals(id, e.id);
            assertEquals(id, e.methods().buildingId());
            assertEquals(id, e.execution().buildingId());
            assertNull(e.execution().batch());
            assertEquals("crude_tools", e.methods().effective().methods().get("tooling_workshop_base"));
        }
    }
}
