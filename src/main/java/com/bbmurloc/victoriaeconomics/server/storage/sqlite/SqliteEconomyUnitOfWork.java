package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionMethodConfigurationRepository;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyUnitOfWork;

import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;

/** Explicit, thread-confined production sessions over one guarded connection and one physical commit. */
public final class SqliteEconomyUnitOfWork implements EconomyUnitOfWork {
    private final Connection connection;
    private final Object lock;
    private final SqliteProductionMethodConfigurationRepository methods;
    private final SqliteProductionExecutionRepository executions;

    public SqliteEconomyUnitOfWork(Connection connection, ProductionRecipeResolver recipes, Object lock) {
        this.connection = Objects.requireNonNull(connection);
        this.lock = Objects.requireNonNull(lock);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        executions = new SqliteProductionExecutionRepository(connection, lock);
    }

    @Override
    public void write(Work work) {
        Objects.requireNonNull(work);
        synchronized (lock) {
            var attempt = new Attempt();
            try {
                SqliteTransactions.write(connection, transaction -> {
                    attempt.open(transaction);
                    work.run(attempt);
                    attempt.flush();
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                // Rewind local working copies only. This is NOT evidence of a database rollback:
                // unknown outcomes still require fresh committed loads and discarding these objects.
                try { attempt.restore(); }
                catch (RuntimeException | Error restore) { failure.addSuppressed(restore); }
                throw failure;
            }
            attempt.confirmCommit();
        }
    }

    private final class Attempt implements EconomyUnitOfWork.Session {
        private SqliteTransactions.WriteSession transaction;
        private ProductionMethodConfigurationRepository.Session methodSql;
        private ProductionExecutionRepository.Session executionSql;
        private Throwable failed;
        private final Map<UUID, ProductionMethodConfiguration> methodRoots = new LinkedHashMap<>();
        private final Map<UUID, ProductionExecution> executionRoots = new LinkedHashMap<>();
        private final Map<UUID, ProductionMethodConfiguration.State> methodCheckpoints = new HashMap<>();
        private final Map<UUID, ProductionExecution.State> executionCheckpoints = new HashMap<>();
        private final Map<UUID, Long> methodSaves = new LinkedHashMap<>();
        private final Map<UUID, Long> executionSaves = new LinkedHashMap<>();

        private void open(SqliteTransactions.WriteSession transaction) {
            this.transaction = transaction;
            methodSql = methods.inTransaction(transaction);
            executionSql = executions.inTransaction(transaction);
        }

        private void requireActive() {
            transaction.requireConnection(connection);
            if (failed != null) throw new IllegalStateException("Economy session has failed; the entire write must roll back", failed);
        }

        private <T> T operation(Supplier<T> work) {
            requireActive();
            try { return work.get(); }
            catch (RuntimeException | Error failure) { failed = failure; throw failure; }
        }

        private final ProductionMethodConfigurationRepository.Session methodSession = new ProductionMethodConfigurationRepository.Session() {
            @Override public ProductionMethodConfiguration load(UUID id) {
                return operation(() -> methodRoots.computeIfAbsent(id, key -> {
                    var root = methodSql.load(key);
                    methodCheckpoints.put(key, root.state());
                    return root;
                }));
            }
            @Override public void save(ProductionMethodConfiguration changed, long expectedRevision) {
                operation(() -> {
                    if (methodRoots.get(changed.buildingId()) != changed)
                        throw new IllegalArgumentException("Save only PM roots loaded by this session");
                    if (changed.configurationRevision() != Math.incrementExact(expectedRevision))
                        throw new IllegalArgumentException("Save exactly one configuration change per commit");
                    if (methodSaves.putIfAbsent(changed.buildingId(), expectedRevision) != null)
                        throw new IllegalStateException("Save each PM root at most once per UoW");
                    return null;
                });
            }
        };

        private final ProductionExecutionRepository.Session executionSession = new ProductionExecutionRepository.Session() {
            @Override public ProductionExecution load(UUID id) {
                return operation(() -> executionRoots.computeIfAbsent(id, key -> {
                    var root = executionSql.load(key);
                    executionCheckpoints.put(key, root.state());
                    return root;
                }));
            }
            @Override public void save(ProductionExecution changed, long expectedRevision) {
                operation(() -> {
                    if (executionRoots.get(changed.buildingId()) != changed)
                        throw new IllegalArgumentException("Save only execution roots loaded by this session");
                    if (changed.executionRevision() != expectedRevision)
                        throw new ProductionRevisionConflict("Stale execution object");
                    Math.incrementExact(expectedRevision);
                    if (executionSaves.putIfAbsent(changed.buildingId(), expectedRevision) != null)
                        throw new IllegalStateException("Save each execution root at most once per UoW");
                    return null;
                });
            }
        };

        @Override public ProductionMethodConfigurationRepository.Session methods() { requireActive(); return methodSession; }
        @Override public ProductionExecutionRepository.Session executions() { requireActive(); return executionSession; }

        private void flush() {
            requireActive();
            // Flush outside application work so a caught second-write failure cannot commit the first.
            methodSaves.forEach((id, expected) -> methodSql.save(methodRoots.get(id), expected));
            executionSaves.forEach((id, expected) -> executionSql.save(executionRoots.get(id), expected));
        }

        private void confirmCommit() {
            // This runs only after the physical commit AND JDBC cleanup have returned successfully.
            executionSaves.forEach((id, expected) -> executionRoots.get(id).acknowledgeCommit(expected));
            // Unsaved edits must not look like durable changes even when the rest of the UoW succeeds.
            methodRoots.forEach((id, root) -> { if (!methodSaves.containsKey(id)) root.restore(methodCheckpoints.get(id)); });
            executionRoots.forEach((id, root) -> { if (!executionSaves.containsKey(id)) root.restoreState(executionCheckpoints.get(id)); });
        }

        private void restore() {
            methodRoots.forEach((id, root) -> root.restore(methodCheckpoints.get(id)));
            executionRoots.forEach((id, root) -> root.restoreState(executionCheckpoints.get(id)));
        }
    }
}
