package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionJournal;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProductionSettlementOptimizationTest {
    @TempDir Path directory;
    Path file() { return directory.resolve("economy.sqlite"); }

    @Test void sixthCompletionAndRealPayrollPaymentShareTick1200WithoutAnIdleOrDuplicateBatch() throws Exception {
        try (var e = new TestEconomy(file(), true, 10_000)) {
            try (var funding = new com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld(file())) {
                funding.fundingFixture(e.companyCash, 10_000);
            }
            e.stock.deposit(e.id, Map.of("wood", 1000L));
            e.production.startBatch(e.id); e.ticks(1199);
            var sixth = e.execution().batch(); assertEquals(199, sixth.getProgressUnits());
            e.ticks(1);
            assertEquals(sixth.getId(), e.execution().lastBatch().getId()); assertEquals(0, e.execution().batch().getProgressUnits());
            assertEquals(1200, e.execution().lastCompletedTick()); assertEquals(6, e.stockState().settlements().size());
            assertEquals(180, e.quantity("tools"));
            assertEquals(14, e.scalar("SELECT SUM(paid) FROM payroll_accruals"));
            assertEquals(14, e.scalar("SELECT balance FROM finance_accounts WHERE id='" + e.householdCash + "'"));
            assertEquals(9986, e.scalar("SELECT balance FROM finance_accounts WHERE id='" + e.companyCash + "'"));
            e.production.onEconomicTick(); e.periods.recoverAcceptedTime();
            assertEquals(180, e.quantity("tools")); assertEquals(6, e.stockState().settlements().size());
            assertEquals(14, e.scalar("SELECT SUM(paid) FROM payroll_accruals"));
        }
    }

    @Test void oneExplicitSessionReusesOnlyVerifiedImmutableConfigurationAndStillReadsCurrentRows() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.startBatch(e.id); e.ticks(200);
            try (var recording = EconomyDiagnostics.start()) {
                SqliteTransactions.write(e.connection, tx -> {
                    var repository = e.repository.inTransaction(tx); var root = repository.load(e.id);
                    root.pause(); repository.save(root, root.executionRevision()); return null;
                });
                var counts = recording.snapshot().counts();
                assertEquals(2, counts.get("json.batch.decode")); assertEquals(2, counts.get("json.batch.reused"));
                assertEquals(6, counts.get("sql.read")); // Joined load plus all fresh persistence checks.
            }
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"configuration", "literal", "status"})
    void sameSessionReuseCannotHideChangedCommitmentInvalidJsonOrAnIrreversibleAbort(String tamper) throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.startBatch(e.id); e.ticks(1);
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(e.connection, tx -> {
                var repository = e.repository.inTransaction(tx); var root = repository.load(e.id);
                String change = switch (tamper) {
                    case "configuration" -> "configuration_json=replace(configuration_json,'\"tools\":30','\"tools\":31')";
                    case "literal" -> "configuration_json=replace(configuration_json,'\"fullSpeedTicks\":200','\"fullSpeedTicks\":200.0')";
                    case "status" -> "status='SETTLING_ABORTED'";
                    default -> throw new AssertionError();
                };
                try (var s = e.connection.prepareStatement("UPDATE production_batches SET " + change + " WHERE batch_id=?")) {
                    s.setString(1, root.batch().getId().toString()); assertEquals(1, s.executeUpdate());
                }
                root.tick(1);
                assertThrows(IllegalStateException.class, () -> repository.save(root, root.executionRevision()));
                return null; // Catching the inner error must not allow this poisoned transaction to commit.
            }));
            assertEquals(1, e.execution().batch().getProgressUnits()); assertTrue(e.execution().batch().isActive());
            assertEquals(30L, e.execution().batch().getConfiguration().resolvedRecipe().outputs().get("tools"));
        }
    }

    @Test void joinedLoadAndSinglePriorReadRetainBothFrozenBatchesAndUsePrimaryKeyLookups() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.startBatch(e.id); e.ticks(200);
            var sql = new AtomicReference<String>();
            e.faults.beforeSql = command -> { if (command.startsWith("SELECT * FROM production_executions")) sql.set(command); };
            ProductionExecution loaded;
            try (var recording = EconomyDiagnostics.start()) {
                loaded = e.repository.load(e.id);
                assertEquals(2, recording.snapshot().counts().get("sql.read"));
                assertEquals(2, recording.snapshot().counts().get("json.batch.decode"));
            }
            assertEquals(0, loaded.batch().getProgressUnits());
            assertTrue(loaded.lastBatch().isCompleted());
            var last = loaded.lastBatch();
            loaded.pause();
            try (var recording = EconomyDiagnostics.start()) {
                e.repository.save(loaded, loaded.executionRevision());
                assertEquals(4, recording.snapshot().counts().get("sql.read"));
            }
            var restored = e.execution();
            assertEquals(ProductionBatch.Status.PAUSED, restored.batch().getStatus());
            assertEquals(last.getId(), restored.lastBatch().getId());
            assertEquals(last.getConfiguration(), restored.lastBatch().getConfiguration());
            var plan = new ArrayList<String>();
            try (var s = e.connection.prepareStatement("EXPLAIN QUERY PLAN " + sql.get())) {
                s.setString(1, e.id.toString()); try (var rows = s.executeQuery()) { while (rows.next()) plan.add(rows.getString("detail")); }
            }
            assertEquals(2, plan.stream().filter(line -> line.contains("batch_id=?") && line.contains("INDEX")).count());
            assertTrue(plan.stream().noneMatch(line -> line.contains("SCAN")), plan.toString());
            System.out.println("Execution joined-load plan: " + plan);
        }
    }

    @Test void synchronizedBuildingsFinishAndRestartAtZeroWithoutRepeatingOutput() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            UUID second = e.createBuilding().getId();
            e.stock.createLocation(second, 1000); e.stock.deposit(second, Map.of("wood", 100L, "iron", 100L));
            e.staffing.requestTarget(second, "laborer", 10); e.staffing.requestTarget(second, "machinist", 4);
            UUID firstBatch = e.production.startBatch(e.id).getId(), secondBatch = e.production.startBatch(second).getId();
            e.ticks(200);
            for (UUID id : List.of(e.id, second)) {
                var result = e.repository.load(id);
                assertEquals(id.equals(e.id) ? firstBatch : secondBatch, result.lastBatch().getId());
                assertEquals(0, result.batch().getProgressUnits()); assertEquals(200, result.lastCompletedTick());
                assertEquals(30L, e.stock.inspect(id).quantities().get("tools"));
                assertEquals(1, e.stock.inspect(id).settlements().size());
            }
            e.production.onEconomicTick();
            assertEquals(30, e.quantity("tools")); assertEquals(1, e.stockState().settlements().size());
            e.ticks(1); assertEquals(1, e.execution().batch().getProgressUnits());
        }
    }

    @Test void pendingPmAndStaffingStillShareThePayrollCutoffBeforeTheNextBatch() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var old = e.production.startBatch(e.id);
            e.ticks(99); e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            e.staffing.requestTarget(e.id, "laborer", 7); e.staffing.requestTarget(e.id, "machinist", 3);
            e.ticks(101);
            var next = e.execution().batch();
            assertEquals(old.getId(), e.execution().lastBatch().getId()); assertEquals(old.getConfiguration(), e.execution().lastBatch().getConfiguration());
            assertEquals("efficient_tools", next.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
            assertEquals(Map.of("laborer", 7, "machinist", 3), next.getConfiguration().workforcePlan().participatingWorkers());
            assertEquals(0, next.getProgressUnits()); assertTrue(e.staffingRepository.load(e.id).pending().isEmpty());
            assertEquals(10, e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
            assertEquals(200, e.scalar("SELECT MAX(effective_tick) FROM payroll_employment_history"));
            assertEquals(30, e.quantity("tools")); // Full old frozen recipe, not the new PM's output.
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingInputsOrCommittedOvercapacityBlocksOnlyTheNextStart(boolean overCapacity) throws Exception {
        try (var e = new TestEconomy(file(), true, overCapacity ? 200 : 1000)) {
            if (overCapacity) e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            var batch = e.production.startBatch(e.id);
            if (!overCapacity) e.stock.withdraw(e.id, Map.of("wood", Math.subtractExact(e.quantity("wood"), batch.getConfiguration().resolvedRecipe().inputs().get("wood"))));
            e.ticks(200);
            assertNull(e.execution().batch()); assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(batch.getConfiguration().resolvedRecipe().outputs().get("tools").longValue(), e.quantity("tools")); assertTrue(e.stockState().reservations().isEmpty());
            assertTrue(e.journal.pendingStarts().isEmpty()); assertEquals(200, e.execution().lastCompletedTick());
            assertEquals(overCapacity, e.stock.storageStatus(e.id).overCapacity());
            assertNotNull(e.production.blockedReason(e.id));
        }
    }

    @Test void failedSettlementConfirmationCannotStartAnotherBatchAndRecoveryDoesNotProduceAgain() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var old = e.production.startBatch(e.id); e.ticks(199);
            e.sql("CREATE TRIGGER fail_confirmation BEFORE UPDATE ON production_executions WHEN NEW.boundary='RECONFIGURING' BEGIN SELECT RAISE(ABORT,'confirmation failed'); END");
            assertThrows(IllegalStateException.class, () -> e.ticks(1));
            assertEquals(old.getId(), e.execution().batch().getId()); assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, e.execution().batch().getStatus());
            assertEquals(30, e.quantity("tools")); assertEquals(1, e.stockState().settlements().size());
            assertEquals(1, e.scalar("SELECT COUNT(*) FROM production_batches")); assertEquals(199, e.execution().lastCompletedTick());
            e.sql("DROP TRIGGER fail_confirmation"); e.recover();
            assertEquals(30, e.quantity("tools")); assertEquals(1, e.stockState().settlements().size());
            assertEquals(0, e.execution().batch().getProgressUnits()); assertEquals(200, e.execution().lastCompletedTick());
            var command = e.stockState().settlements().get(old.getId()).command();
            assertEquals(command, e.stock.settle(e.id, command).command());
            var conflicting = new ProductionSettlement(command.batchId(), ProductionSettlement.Outcome.ABORTED, command.inputs(), command.outputs());
            assertThrows(IllegalStateException.class, () -> e.stock.settle(e.id, conflicting));
        }
    }

    @Test void optimizedAndOriginalStagedControlFlowsReachEquivalentEconomicFactsAndCheckpoint() throws Exception {
        try (var optimized = new TestEconomy(file(), true); var original = new TestEconomy(directory.resolve("reference.sqlite"), true)) {
            optimized.production.startBatch(optimized.id); original.production.startBatch(original.id);
            for (int tick = 1; tick <= 240; tick++) {
                if (tick == 100) for (var e : List.of(optimized, original)) {
                    e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
                    e.staffing.requestTarget(e.id, "laborer", 7); e.staffing.requestTarget(e.id, "machinist", 3);
                }
                optimized.ticks(1);
                var time = new SqliteEconomicTimeRepository(original.connection, original.lock);
                time.acceptNext(tick - 1); original.accrual.recoverAcceptedTime(); original.periods.recoverAcceptedTime();
                referenceTick(original); time.acknowledgeProductionCallback(tick);
            }
            assertEquals(facts(original), facts(optimized));
            var snapshot = EconomyCheckpoint.capture(optimized.connection); snapshot.validate();
            try (var restored = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:"))) {
                EconomySchema.initialize(restored); SqliteTransactions.write(restored, tx -> { snapshot.install(restored, tx); return null; });
                assertEquals(snapshot.sha256(), EconomyCheckpoint.capture(restored).sha256());
            }
        }
    }

    /** Test-only pre-optimization orchestration; all reads/writes still use the real repositories and journals. */
    private static void referenceTick(TestEconomy e) {
        long tick = e.coordinator.acceptedTick(); e.production.recoverStarts();
        for (UUID id : e.coordinator.buildingIds()) {
            if (e.coordinator.processTick(id, tick) != ProductionBoundaryCoordinator.TickResult.NEEDS_FOLLOW_UP) continue;
            referenceBoundary(e, id); var current = e.coordinator.execution(id);
            if (current.batch() == null && current.automatic() && !e.coordinator.lifecycleHeld(id)) {
                referenceBoundary(e, id); var plan = e.coordinator.prepare(id); UUID batch = UUID.randomUUID();
                e.journal.recordStart(new ProductionJournal.StartIntent(batch, id));
                e.inventory.reserve(id, batch, plan.configuration().resolvedRecipe().inputs());
                e.coordinator.commitStart(id, batch, plan, true); e.journal.finishStart(batch); e.coordinator.execution(id);
            }
            e.coordinator.finishTick(id, tick);
        }
        e.coordinator.confirmWholeTick(tick);
    }
    private static void referenceBoundary(TestEconomy e, UUID id) {
        var current = e.coordinator.execution(id); var batch = current.batch();
        if (batch != null && batch.needsSettlement()) {
            var recipe = batch.getConfiguration().resolvedRecipe();
            var outcome = batch.getStatus() == ProductionBatch.Status.SETTLING_COMPLETED ? ProductionSettlement.Outcome.COMPLETED : ProductionSettlement.Outcome.ABORTED;
            var command = new ProductionSettlement(batch.getId(), outcome, recipe.inputs(), recipe.outputs());
            assertEquals(command, e.inventory.settle(id, command).command()); e.coordinator.confirmSettlement(id, batch.getId());
        }
        current = e.coordinator.execution(id);
        if (!current.hasUnfinishedBatch() && !e.coordinator.lifecycleHeld(id)) e.coordinator.reconfigure(id);
    }
    private static List<Object> facts(TestEconomy e) throws Exception {
        var execution = e.execution();
        var payroll = SqliteTransactions.write(e.connection, tx -> e.payrollRepository.inTransaction(tx).currentForBuilding(e.id));
        return List.of(e.stockState().quantities(), e.stockState().reservations().values().stream().toList(),
                e.stockState().settlements().values().stream().map(s -> List.of(s.command().outcome(), s.consumed(), s.produced())).toList(),
                execution.batch().getConfiguration(), execution.batch().getProgressUnits(), execution.lastBatch().getConfiguration(),
                execution.lastBatch().getStatus(), execution.executionRevision(), execution.lastCompletedTick(),
                e.staffingRepository.load(e.id).employed(), e.methods().effective().methods(),
                payroll.employees(), payroll.accrued(), payroll.matured(), payroll.paid(), payroll.remainder(), payroll.lastAccruedTick());
    }
}
