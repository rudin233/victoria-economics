package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollAccrual;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteBuildingStaffingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SettlementTransactionTest {
    @TempDir Path directory;

    private Path file() { return directory.resolve("economy.sqlite"); }

    @ParameterizedTest
    @ValueSource(strings = {"inventory", "after-inventory", "accrual", "staffing", "roles", "headcount", "history", "batch", "execution"})
    void failureRollsBackEverySettlementFactAndRevision(String point) throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            // A lifecycle abort may stop work while accepted time is still ahead of the old wage cursor.
            var command = point.equals("accrual") ? prepareAbortBeforePayroll(e) : prepareCompletion(e);
            var before = facts(e.connection, e.id);
            String injection = switch (point) {
                case "inventory" -> "BEFORE UPDATE ON inventory_locations";
                case "accrual" -> "BEFORE UPDATE ON payroll_accruals WHEN NEW.last_accrued_tick>OLD.last_accrued_tick";
                case "staffing" -> "BEFORE UPDATE ON building_staffing";
                case "roles" -> "BEFORE DELETE ON building_staffing_roles";
                case "headcount" -> "BEFORE UPDATE ON payroll_accruals WHEN NEW.employees=0";
                case "history" -> "BEFORE INSERT ON payroll_employment_history";
                case "batch" -> "BEFORE UPDATE ON production_batches WHEN NEW.status='COMPLETED'";
                case "execution" -> "BEFORE UPDATE ON production_executions WHEN NEW.boundary='RECONFIGURING'";
                default -> null;
            };
            if (injection != null) {
                e.sql("CREATE TRIGGER settlement_fault " + injection + " BEGIN SELECT RAISE(ABORT,'settlement fault'); END");
            } else {
                // First Payroll call after the inventory update, before any other write.
                e.faults.beforeSql = sql -> {
                    if (sql.startsWith("UPDATE inventory_locations SET state_json")) {
                        e.faults.beforeSql = next -> { throw new IllegalStateException("after inventory"); };
                    }
                };
                // The prepared inventory UPDATE itself executes before the next SQL is prepared.
            }
            e.faults.commits = 0;
            var failure = assertThrows(IllegalStateException.class, () -> e.coordinator.settle(e.id, command));
            assertFalse(failure instanceof CommitOutcomeUnknownException);
            assertEquals(0, e.faults.commits);
            e.faults.beforeSql = sql -> {};
            assertEquals(before, facts(e.connection, e.id));
            if (injection != null) e.sql("DROP TRIGGER settlement_fault");

            e.coordinator.settle(e.id, command);
            assertSettled(before, facts(e.connection, e.id), command);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onePhysicalCommitAndMatchingReplayLeaveAllTerminalFactsStable(boolean abort) throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var command = abort ? prepareAbortBeforePayroll(e) : prepareCompletion(e);
            var before = facts(e.connection, e.id);
            e.faults.commits = 0;
            e.coordinator.settle(e.id, command);
            assertEquals(1, e.faults.commits);
            var settled = facts(e.connection, e.id);
            assertSettled(before, settled, command);
            assertEquals(before.historyRows() + 1, settled.historyRows());
            if (abort) {
                assertEquals(4, settled.payroll().lastAccruedTick());
                assertEquals(56, settled.payroll().remainder());
                assertEquals(before.payroll().company(), settled.payroll().company());
            }
            e.coordinator.settle(e.id, command);
            assertTrue(SqliteTransactions.write(e.connection, tx -> e.stock.settle(tx, e.id, command)).replayed());
            var opposite = abort ? ProductionSettlement.Outcome.COMPLETED : ProductionSettlement.Outcome.ABORTED;
            var conflict = new ProductionSettlement(command.batchId(), opposite, command.inputs(), command.outputs());
            assertThrows(IllegalStateException.class, () -> e.coordinator.settle(e.id, conflict));
            assertEquals(settled, facts(e.connection, e.id));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownCommitVerifiesSameRamAndRestartsFromLastCompleteCheckpoint(boolean committed) throws Exception {
        UUID batch;
        String saved;
        try (var db = EconomyDatabase.open(directory); var e = new TestEconomy(db, true)) {
            batch = e.production.startBatch(e.id).getId();
            e.ticks(199);
            saved = db.requestCheckpoint().join().sha256();
            var command = markCompletion(e);
            var before = facts(e.connection, e.id);
            e.faults.beforeCommit = !committed;
            e.faults.afterCommit = committed;
            var unknown = assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.settle(e.id, command));
            assertTrue(unknown.getMessage().contains(committed ? "verified committed" : "verified not committed"), unknown::getMessage);
            assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.settle(e.id, command));
            assertThrows(CommitOutcomeUnknownException.class, e.production::onServerTick);
            try (var fresh = db.openVerificationConnection()) {
                var authoritative = facts(fresh, e.id);
                if (committed) assertSettled(before, authoritative, command);
                else assertEquals(before, authoritative);
                assertThrows(IllegalStateException.class, () -> EconomyCheckpoint.capture(fresh));
            }
            // Closing a retired runtime cannot replace the old checkpoint with the unconfirmed Tick.
            assertThrows(CommitOutcomeUnknownException.class, db::close);
        }
        try (var db = EconomyDatabase.open(directory); var e = new TestEconomy(db, false)) {
            assertEquals(saved, EconomyCheckpoint.capture(e.connection).sha256());
            assertEquals(199, e.execution().batch().getProgressUnits());
            assertTrue(e.stockState().settlements().isEmpty());
            e.ticks(1);
            e.production.recoverAcceptedTick();
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertEquals(1, e.stockState().settlements().size());
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test void unavailableVerificationClosesRuntimeWithoutRetrying() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var command = prepareCompletion(e);
            var before = facts(e.connection, e.id);
            e.denyStartVerification = true;
            e.faults.afterCommit = true;
            var unknown = assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.settle(e.id, command));
            assertTrue(unknown.getMessage().contains("Settlement outcome unverified"));
            assertEquals(1, unknown.getSuppressed().length);
            assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.settle(e.id, command));
            try (var fresh = SqliteEconomyDatabase.open(file())) {
                assertSettled(before, facts(fresh, e.id), command);
            }
        }
    }

    @Test void committedBalancesMustMatchTheReceiptBeforeAnUnknownOutcomeCanBeVerified() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var command = prepareCompletion(e);
            e.sql("""
                    CREATE TEMP TRIGGER change_committed_quantity AFTER UPDATE OF state_json ON inventory_locations
                    BEGIN
                        UPDATE inventory_locations SET state_json=json_set(NEW.state_json,'$.snapshot.quantities.tools',31)
                        WHERE location_id=NEW.location_id;
                    END
                    """);
            e.loseSettlementCommit();
            var failure = assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.settle(e.id, command));
            assertTrue(failure.getMessage().contains("Settlement outcome unverified"), failure::getMessage);
            assertEquals("Committed settlement facts do not match", failure.getSuppressed()[0].getMessage());
            try (var fresh = SqliteEconomyDatabase.open(file())) {
                var observer = facts(fresh, e.id);
                assertEquals(command, observer.stock().settlements().get(command.batchId()).command());
                assertEquals(31L, observer.stock().quantities().get("tools"));
            }
        }
    }

    @Test void legacyPartialSettlementCannotPassValidationOrBeSilentlyAdopted() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var command = prepareCompletion(e);
            // The standalone inventory operation can deliberately reproduce the retired two-commit window.
            e.stock.settle(e.id, command);
            var partial = facts(e.connection, e.id);
            assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, partial.status());
            assertFalse(partial.stock().settlements().isEmpty());
            assertThrows(IllegalStateException.class, () -> EconomySchema.validateExisting(e.connection));
            assertThrows(IllegalStateException.class, () -> EconomyCheckpoint.capture(e.connection));
            var rejection = assertThrows(IllegalStateException.class, () -> e.coordinator.settle(e.id, command));
            assertEquals("Unfinished batch already has an inventory settlement", rejection.getMessage());
            assertEquals(partial, facts(e.connection, e.id));
        }
    }

    @Test void concurrentMatchingSettlementRequestsReleaseAndProduceOnce() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var command = prepareCompletion(e);
            var before = facts(e.connection, e.id);
            try (var fresh = SqliteEconomyDatabase.open(file()); var pool = Executors.newFixedThreadPool(2)) {
                var other = new ProductionBoundaryCoordinator(fresh, e.resolver, e.occupations, new Object(),
                        () -> SqliteEconomyDatabase.open(file()));
                var ready = new CountDownLatch(1);
                var first = pool.submit(() -> { ready.await(); e.coordinator.settle(e.id, command); return null; });
                var second = pool.submit(() -> { ready.await(); other.settle(e.id, command); return null; });
                ready.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertSettled(before, facts(fresh, e.id), command);
            }
        }
    }

    private static ProductionSettlement prepareCompletion(TestEconomy e) {
        e.production.startBatch(e.id);
        e.ticks(199);
        return markCompletion(e);
    }

    private static ProductionSettlement markCompletion(TestEconomy e) {
        new SqliteEconomicTimeRepository(e.connection, e.lock).acceptNext(199);
        e.accrual.recoverAcceptedTime();
        assertEquals(ProductionBoundaryCoordinator.TickResult.NEEDS_FOLLOW_UP, e.coordinator.processTick(e.id, 200));
        return ProductionSettlement.forBatch(e.execution().batch());
    }

    private static ProductionSettlement prepareAbortBeforePayroll(TestEconomy e) throws Exception {
        e.production.startBatch(e.id);
        e.ticks(3);
        new SqliteEconomicTimeRepository(e.connection, e.lock).acceptNext(3);
        e.failInventorySettlement(true);
        var operation = BuildingLifecycleTest.command(e, BuildingLifecycleOperation.Kind.DEMOLITION);
        assertThrows(IllegalStateException.class, () -> e.lifecycle.request(operation));
        assertEquals(BuildingLifecycleOperation.Status.QUIESCING, e.lifecycle.find(operation.id()).orElseThrow().status());
        e.failInventorySettlement(false);
        return ProductionSettlement.forBatch(e.execution().batch());
    }

    private record Facts(ProductionBatch.Status status, long progress, Long endedTick, long executionRevision,
                         GoodsInventory.State stock, long inventoryRevision, BuildingStaffing staffing,
                         PayrollAccrual payroll, long historyRows, long liabilityRows) {}

    private static Facts facts(Connection c, UUID id) {
        return SqliteTransactions.read(c, tx -> {
            var lock = new Object();
            var execution = new SqliteProductionExecutionRepository(c, lock).inSnapshot(tx, id);
            var inventory = new SqliteInventoryStore(c, lock).inSnapshot(tx, id);
            var staff = new SqliteBuildingStaffingRepository(c, lock).inSnapshot(tx, id);
            try (var query = c.prepareStatement("""
                    SELECT i.revision, p.id, (SELECT count(*) FROM payroll_employment_history WHERE segment_id=p.id),
                        (SELECT count(*) FROM payroll_liabilities WHERE segment_id=p.id)
                    FROM inventory_locations i JOIN payroll_accruals p ON p.building_id=i.location_id
                    WHERE i.location_id=? AND p.closed_tick IS NULL
                    """)) {
                query.setString(1, id.toString());
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    var payroll = new SqlitePayrollRepository(c, lock).inSnapshot(tx, UUID.fromString(row.getString(2)));
                    var batch = execution.batch();
                    return new Facts(batch.getStatus(), batch.getProgressUnits(), batch.endedTick(), execution.executionRevision(),
                            inventory, row.getLong(1), staff, payroll, row.getLong(3), row.getLong(4));
                }
            }
        });
    }

    private static void assertSettled(Facts before, Facts after, ProductionSettlement command) {
        boolean completed = command.outcome() == ProductionSettlement.Outcome.COMPLETED;
        assertEquals(completed ? ProductionBatch.Status.COMPLETED : ProductionBatch.Status.ABORTED, after.status());
        assertEquals(after.payroll().lastAccruedTick(), after.endedTick());
        assertEquals(before.progress(), after.progress());
        assertEquals(before.executionRevision() + 1, after.executionRevision());
        assertEquals(before.inventoryRevision() + 1, after.inventoryRevision());
        assertEquals(before.staffing().revision() + 1, after.staffing().revision());
        assertEquals(command, after.stock().settlements().get(command.batchId()).command());
        assertTrue(after.stock().reservations().isEmpty());
        assertEquals(completed ? Map.of("wood", 70L, "iron", 100L, "tools", 30L) : before.stock().quantities(), after.stock().quantities());
        assertNull(after.staffing().batchId());
        assertEquals(0, after.payroll().employees());
        assertEquals(before.historyRows() + 1, after.historyRows());
    }
}
