package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionRevisionConflict;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomySchema;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.CommitOutcomeUnknownException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ProductionRepositoryTest {
    @TempDir Path directory;
    Connection connection;
    SqliteProductionMethodConfigurationRepository methods;
    SqliteProductionExecutionRepository executions;
    ProductionRecipeResolver recipes;
    UUID id;

    @BeforeEach void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("economy.sqlite"));
        EconomySchema.initialize(connection);
        var types = new BuildingTypeRegistry();
        var groups = new ProductionMethodGroupRegistry();
        var definitions = new ProductionMethodRegistry();
        types.register(new BuildingTypeDefinition("factory", "industry", "equipment", 100, List.of("base")));
        groups.register(new ProductionMethodGroupDefinition("base", "factory", List.of("a", "b")));
        definitions.register(new ProductionMethodDefinition("a", "base", 1, Map.of("ore", 10.0), Map.of("metal", 10.0), Map.of("worker", 10)));
        definitions.register(new ProductionMethodDefinition("b", "base", 2, Map.of("ore", 10.0), Map.of("metal", 20.0), Map.of("worker", 10)));
        recipes = new ProductionRecipeResolver(types, groups, definitions);
        Object lock = new Object();
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        executions = new SqliteProductionExecutionRepository(connection, lock);
        id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO economic_buildings VALUES(?, 'factory', 'ACTIVE')")) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
        }
        methods.create(new ProductionMethodConfiguration(id, recipes.rulesFor("factory"), Map.of("base", "a"), null));
        executions.create(new ProductionExecution(id));
    }

    @AfterEach void close() throws Exception { connection.close(); }

    private ProductionBatchConfiguration commitment() {
        var employees = new ArrayList<EmploymentFact>();
        for (int i = 0; i < 10; i++) employees.add(new EmploymentFact(new UUID(0, i + 1), id, "worker"));
        var configuration = methods.load(id);
        var recipe = configuration.recipe();
        var plan = new WorkforcePlanningService().plan(id, recipe.requiredWorkers(), 100, 100, employees, (npc, role) -> true);
        return new ProductionBatchConfiguration(configuration.effective().methods(), recipe, 100, 100, plan, configuration.effectiveRevision());
    }

    private void sql(String text) throws Exception { try (var statement = connection.createStatement()) { statement.executeUpdate(text); } }
    private long scalar(String text) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(text)) { assertTrue(rows.next()); return rows.getLong(1); }
    }

    @Test void initializationIsRepeatableAndContainsOnlyOneBatchAuthority() throws Exception {
        EconomySchema.initialize(connection);
        assertEquals(EconomySchema.VERSION, scalar("SELECT version FROM economy_schema_migrations"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM production_method_configurations"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM production_executions"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM pragma_table_info('production_executions') WHERE name='state_json'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='building_production_execution'"));
    }

    @Test void configurationPendingAndCancellationCommitWithoutWritingExecution() throws Exception {
        sql("CREATE TRIGGER reject_execution BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'execution must not be written'); END");
        var configuration = methods.load(id);
        configuration.requestSelection("base", "b", true);
        methods.save(configuration, 0);
        assertEquals(1, methods.load(id).configurationRevision());
        assertEquals(0, methods.load(id).effectiveRevision());
        assertEquals("b", methods.load(id).pending().orElseThrow().methods().get("base"));
        var cancel = methods.load(id);
        cancel.cancelPending();
        methods.save(cancel, 1);
        assertTrue(methods.load(id).pending().isEmpty());
        assertEquals(2, methods.load(id).configurationRevision());
        assertEquals(0, executions.load(id).executionRevision());
    }

    @Test void executionAndBatchCommitAtomicallyWithoutWritingConfiguration() throws Exception {
        sql("CREATE TRIGGER reject_configuration BEFORE UPDATE ON production_method_configurations BEGIN SELECT RAISE(ABORT,'configuration must not be written'); END");
        UUID batch = UUID.randomUUID();
        var execution = executions.load(id);
        execution.start(batch, commitment());
        executions.save(execution, 0);
        execution.tick(300);
        executions.save(execution, 1);
        var restored = executions.load(id);
        assertEquals(2, restored.executionRevision());
        assertEquals(0.25, restored.batch().getProgress());
        assertEquals(batch, restored.batch().getId());
        assertEquals(1, scalar("SELECT COUNT(*) FROM production_batches"));
        assertEquals(300, scalar("SELECT progress_units FROM production_batches"));
        assertEquals(0, methods.load(id).configurationRevision());
    }

    @Test void staleConfigurationAndExecutionCannotOverwriteOrCreateAnotherBatch() throws Exception {
        var first = methods.load(id);
        var stale = methods.load(id);
        first.requestSelection("base", "b", true);
        stale.requestSelection("base", "b", false);
        methods.save(first, 0);
        assertThrows(ProductionRevisionConflict.class, () -> methods.save(stale, 0));
        assertEquals("a", methods.load(id).effective().methods().get("base"));
        var execution = executions.load(id);
        var old = executions.load(id);
        execution.start(UUID.randomUUID(), commitment());
        old.start(UUID.randomUUID(), commitment());
        executions.save(execution, 0);
        assertThrows(ProductionRevisionConflict.class, () -> executions.save(old, 0));
        assertEquals(execution.batch().getId(), executions.load(id).batch().getId());
        assertEquals(1, scalar("SELECT COUNT(*) FROM production_batches"));
    }

    @Test void failedBatchInsertRollsBackExecutionAndCommitEvidence() throws Exception {
        sql("CREATE TRIGGER reject_batch BEFORE INSERT ON production_batches BEGIN SELECT RAISE(ABORT,'injected batch failure'); END");
        UUID batch = UUID.randomUUID();
        var execution = executions.load(id);
        execution.start(batch, commitment());
        assertThrows(IllegalStateException.class, () -> executions.save(execution, 0));
        assertEquals(0, execution.executionRevision());
        assertNull(executions.load(id).batch());
        assertEquals(0, executions.load(id).executionRevision());
        assertFalse(new SqliteProductionJournal(connection).containsBatch(batch));
    }

    @Test void persistedTerminationCannotBeRevertedEvenByAStaleOrFabricatedSnapshot() {
        var execution = executions.load(id);
        execution.start(UUID.randomUUID(), commitment());
        executions.save(execution, 0);
        execution.tick(300);
        executions.save(execution, 1);
        execution.terminate();
        executions.save(execution, 2);
        var original = executions.load(id).batch();
        var illegal = new ProductionBatch(original.getId(), id, original.getConfiguration(), original.getProgressUnits(), ProductionBatch.Status.ACTIVE);
        var reverted = ProductionExecution.rehydrate(id, illegal, null, ProductionExecution.Boundary.NONE, true, 3);
        assertThrows(IllegalStateException.class, () -> executions.save(reverted, 3));
        assertEquals(ProductionBatch.Status.SETTLING_ABORTED, executions.load(id).batch().getStatus());
        assertEquals(3, executions.load(id).executionRevision());
    }

    @Test void committedTerminationProgressCannotBeChangedWhileSettlementRetries() {
        var execution = executions.load(id);
        execution.start(UUID.randomUUID(), commitment());
        executions.save(execution, 0);
        execution.tick(300);
        executions.save(execution, 1);
        execution.terminate();
        executions.save(execution, 2);
        var original = executions.load(id).batch();
        var altered = new ProductionBatch(original.getId(), id, original.getConfiguration(),
                original.getProgressUnits() + 1, ProductionBatch.Status.SETTLING_ABORTED);
        var invalid = ProductionExecution.rehydrate(id, altered, null, ProductionExecution.Boundary.NONE, false, 3);
        assertThrows(IllegalStateException.class, () -> executions.save(invalid, 3));
        assertEquals(original.getProgressUnits(), executions.load(id).batch().getProgressUnits());
        assertEquals(3, executions.load(id).executionRevision());
    }

    @Test void actualJdbcCommitAcknowledgementLossLeavesOneVerifiableDurableBatch() {
        var loseAcknowledgement = new AtomicBoolean(true);
        Connection faultConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(connection, arguments);
                        if (method.getName().equals("commit") && loseAcknowledgement.compareAndSet(true, false))
                            throw new SQLException("Injected acknowledgement loss after the actual JDBC commit");
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        var repository = new SqliteProductionExecutionRepository(faultConnection, new Object());
        var execution = executions.load(id);
        UUID batch = UUID.randomUUID();
        execution.start(batch, commitment());
        assertThrows(CommitOutcomeUnknownException.class, () -> repository.save(execution, 0));
        assertEquals(0, execution.executionRevision()); // The unacknowledged object is not published as authority.
        assertTrue(new SqliteProductionJournal(connection).containsBatch(batch));
        assertEquals(batch, executions.load(id).batch().getId());
        assertEquals(1, executions.load(id).executionRevision());
        assertTrue(methods.load(id).pending().isEmpty());
    }

    @Test void unresolvedJdbcTransactionCannotSupplyCommittedExecutionPmOrBatchEvidence() throws Exception {
        connection.setAutoCommit(false);
        try {
            sql("UPDATE production_method_configurations SET configuration_revision=1,effective_revision=1");
            assertThrows(CommitOutcomeUnknownException.class, () -> executions.load(id));
            assertThrows(CommitOutcomeUnknownException.class, () -> methods.load(id));
            var journal = new SqliteProductionJournal(connection);
            assertThrows(CommitOutcomeUnknownException.class, () -> journal.containsBatch(UUID.randomUUID()));
            assertThrows(CommitOutcomeUnknownException.class, journal::pendingStarts);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertEquals(0, methods.load(id).configurationRevision());
        assertNull(executions.load(id).batch());
    }

    @Test void schemaRejectsAnOldDatabaseWithoutChangingItsData() throws Exception {
        try (var legacy = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("old.sqlite")); var statement = legacy.createStatement()) {
            statement.executeUpdate("CREATE TABLE economic_buildings(id TEXT PRIMARY KEY,current_equipment INTEGER)");
            statement.executeUpdate("INSERT INTO economic_buildings VALUES('old',73)");
            var failure = assertThrows(IllegalStateException.class, () -> EconomySchema.initialize(legacy));
            assertTrue(failure.getMessage().contains("new world"));
            try (var rows = statement.executeQuery("SELECT current_equipment FROM economic_buildings")) { assertTrue(rows.next()); assertEquals(73, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
        }
    }

    @Test void unknownSchemaVersionIsRejectedWithoutReinitialization() throws Exception {
        sql("UPDATE economy_schema_migrations SET version=99");
        assertThrows(IllegalStateException.class, () -> EconomySchema.initialize(connection));
        assertEquals(99, scalar("SELECT version FROM economy_schema_migrations"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM economic_buildings"));
    }
}
