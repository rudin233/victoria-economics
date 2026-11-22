package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StartCommitRecoveryTest {
    @TempDir Path dir;Path file(){return dir.resolve("starts.sqlite");}
    @Test void pmFirstUsesNewRevisionAndBatchFirstDefersNextPm()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");var b=e.production.startBatch(e.id);assertEquals(e.methods().effectiveRevision(),b.getConfiguration().effectiveRevision());assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals"));e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");assertTrue(e.methods().pending().isPresent());assertEquals("reduced_tools",b.getConfiguration().productionMethodSelections().get("tooling_workshop_base"));}
    }
    @Test void idlePmNeedsRealPayrollButDoesNotNeedInputMaterials()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.stock.withdraw(e.id,Map.of("iron",100L));e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");assertEquals("efficient_tools",e.methods().effective().methods().get("tooling_workshop_base"));
            e.sql("DELETE FROM regional_wage_rates");e.ticks(0);assertThrows(IllegalStateException.class,()->e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools"));assertEquals("efficient_tools",e.methods().effective().methods().get("tooling_workshop_base"));}
    }
    @Test void failedProgressWriteRollsBackReplayMarkerAndStillAllowsRunningPendingRequest()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.sql("CREATE TRIGGER fail_progress BEFORE UPDATE ON production_batches WHEN NEW.progress_units>OLD.progress_units BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(IllegalStateException.class,()->e.ticks(1));assertEquals(0,e.execution().batch().getProgressUnits());assertNull(e.execution().processingTick());
            e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");assertTrue(e.methods().pending().isPresent());e.sql("DROP TRIGGER fail_progress");e.recover();assertEquals(1,e.execution().batch().getProgressUnits());assertTrue(e.methods().pending().isPresent());}
    }
    @Test void unknownExecutionReadRejectsPmWithoutSavingAnything()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.faults.beforeSql=sql->{if(sql.startsWith("SELECT * FROM production_executions"))throw new IllegalStateException("injected unknown execution");};assertThrows(IllegalStateException.class,()->e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools"));e.faults.beforeSql=sql->{};assertEquals(0,e.methods().configurationRevision());}
    }
    @Test void unavailableVerificationLeavesConnectionRetiredAndNeverRetries() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.denyStartVerification = true;
            e.faults.afterCommit = true;
            var failure = assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.startBatch(e.id, false));
            assertTrue(failure.getMessage().contains("Start outcome unverified"));
            assertEquals(1, failure.getSuppressed().length);
            assertThrows(CommitOutcomeUnknownException.class, () -> e.production.startBatch(e.id));
        }
        try (var fresh = new TestEconomy(file(), false)) {
            assertEquals(1, fresh.scalar("SELECT COUNT(*) FROM production_batches"));
            var batch = fresh.execution().batch();
            assertEquals(batch.getId(), fresh.staffingRepository.load(fresh.id).batchId());
            assertEquals(batch.getConfiguration().resolvedRecipe().inputs(), fresh.stockState().reservations().get(batch.getId()));
            assertEquals(14, fresh.scalar("SELECT employees FROM payroll_accruals"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownStartCommitVerifiesCompleteGraphWithoutRetry(boolean committed)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions SET current_batch_id")){e.faults.beforeCommit=!committed;e.faults.afterCommit=committed;}};
            var failure = assertThrows(CommitOutcomeUnknownException.class,()->e.production.startBatch(e.id));
            assertTrue(failure.getMessage().contains(committed ? "verified committed" : "verified not committed"), failure.getMessage());
            e.faults.beforeSql=sql->{};e.faults.beforeCommit=false;e.faults.afterCommit=false;
            assertThrows(CommitOutcomeUnknownException.class,()->e.execution());
            try(var verified=SqliteEconomyDatabase.open(file())){
                assertEquals(committed ? 1 : 0,new com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore(verified,new Object()).inspect(e.id).reservations().size());
            }
            assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(committed,e.execution().batch()!=null);assertEquals(committed?1:0,e.stockState().reservations().size());assertEquals(100,e.quantity("wood"));}
    }
    @Test void unknownMemoryCommitIsVerifiedWithoutTreatingItAsACheckpoint() throws Exception {
        var world = dir.resolve("unknown-start-world");
        var runtime = com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase.open(world);
        UUID id;
        try {
            try (var e = new TestEconomy(runtime, true)) {
                id = e.id;
                runtime.requestCheckpoint().join();
                e.faults.afterCommit = true;
                var failure = assertThrows(CommitOutcomeUnknownException.class, () -> e.coordinator.startBatch(id, false));
                assertTrue(failure.getMessage().contains("verified committed"), failure.getMessage());
                try (var fresh = runtime.openVerificationConnection()) {
                    var execution = new com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository(fresh, new Object()).load(id);
                    assertNotNull(execution.batch());
                }
                assertThrows(CommitOutcomeUnknownException.class, () -> e.production.startBatch(id));
            }
        } finally {
            assertThrows(CommitOutcomeUnknownException.class, runtime::close);
        }
        try (var recovered = com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase.open(world);
             var e = new TestEconomy(recovered, false)) {
            assertEquals(id, e.id);
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(0, e.staffingRepository.load(id).totalOccupied());
            assertEquals(0, e.scalar("SELECT employees FROM payroll_accruals"));
        }
    }

    @Test void lostStartAckDoesNotOverwriteNewPendingAfterVerifiedRecovery()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions SET current_batch_id"))e.faults.afterCommit=true;};assertThrows(CommitOutcomeUnknownException.class,()->e.production.startBatch(e.id));}
        try(var e=new TestEconomy(file(),false)){e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");assertTrue(e.methods().pending().isPresent());}
        try(var e=new TestEconomy(file(),false)){assertEquals("reduced_tools",e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));assertEquals("crude_tools",e.execution().batch().getConfiguration().productionMethodSelections().get("tooling_workshop_base"));}
    }
    @Test void stopDecisionSurvivesSettlementReceiptLossAndRestart() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(file(), true)) {
            batch = e.production.startBatch(e.id).getId();
            e.ticks(99);
            e.production.stopAfterCurrentBatch(e.id);
            e.ticks(100);
            e.inventory.loseSettlementAcknowledgement = true;
            var failure = assertThrows(IllegalStateException.class, () -> e.ticks(1));
            assertInstanceOf(CommitOutcomeUnknownException.class, failure.getSuppressed()[0]);
            assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, e.execution().batch().getStatus());
            assertFalse(e.execution().automatic());
            assertEquals(1, e.stockState().settlements().size());
        }
        try (var e = new TestEconomy(file(), false)) {
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertFalse(e.execution().automatic());
            assertEquals(70, e.quantity("wood"));
            assertEquals(30, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
            assertEquals(200, e.execution().lastCompletedTick());
        }
    }
    @Test void automaticStartReadsLatestManualStopAndManualStartStillEnablesAutomatic() throws Exception {
        try (var e = new TestEconomy(file(), true); var other = new TestEconomy(file(), false)) {
            e.production.setAutomatic(e.id, true);
            other.production.stopAfterCurrentBatch(e.id);
            assertThrows(ProductionStartBlocked.class, () -> e.coordinator.startBatch(e.id, true));
            assertTrue(e.stockState().reservations().isEmpty());
            assertNull(e.execution().batch());
            assertFalse(e.execution().automatic());
            e.production.startBatch(e.id);
            assertTrue(e.execution().automatic());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"inventory", "batch", "execution", "staffing", "roles", "payroll", "history"})
    void failedAcquisitionRollsBackEveryStartWrite(String point) throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var staff = e.staffingRepository.load(e.id);
            long wageRevision = e.scalar("SELECT revision FROM payroll_accruals");
            long wageHistory = e.scalar("SELECT COUNT(*) FROM payroll_employment_history");
            var stock = e.stockState();
            long inventoryRevision = e.scalar("SELECT revision FROM inventory_locations");
            String stage = switch (point) {
                case "inventory" -> "BEFORE UPDATE ON inventory_locations";
                case "batch" -> "BEFORE INSERT ON production_batches";
                case "execution" -> "BEFORE UPDATE ON production_executions";
                case "staffing" -> "BEFORE UPDATE ON building_staffing";
                case "roles" -> "BEFORE INSERT ON building_staffing_roles";
                case "payroll" -> "BEFORE UPDATE ON payroll_accruals WHEN NEW.employees>0";
                default -> "BEFORE INSERT ON payroll_employment_history";
            };
            e.sql("CREATE TRIGGER fail_start " + stage + " BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertEquals(0, e.scalar("SELECT COUNT(*) FROM production_batches"));
            assertEquals(staff, e.staffingRepository.load(e.id));
            assertEquals(0, e.scalar("SELECT employees FROM payroll_accruals"));
            assertEquals(wageRevision, e.scalar("SELECT revision FROM payroll_accruals"));
            assertEquals(wageHistory, e.scalar("SELECT COUNT(*) FROM payroll_employment_history"));
            assertEquals(stock, e.stockState());
            assertEquals(inventoryRevision, e.scalar("SELECT revision FROM inventory_locations"));

            e.sql("DROP TRIGGER fail_start");
            var batch = e.production.startBatch(e.id);
            assertEquals(batch.getId(), e.staffingRepository.load(e.id).batchId());
            assertEquals(14, e.scalar("SELECT employees FROM payroll_accruals"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownTerminalCommitPreservesExactlyOneWageCutoff(boolean committed) throws Exception {
        UUID batch;
        try (var e = new TestEconomy(file(), true)) {
            batch = e.production.startBatch(e.id).getId();
            e.production.stopAfterCurrentBatch(e.id);
            e.ticks(199);
            e.faults.beforeSql = sql -> {
                if (sql.startsWith("UPDATE production_batches SET progress_units=?,status=?,ended_tick=?")) {
                    e.faults.beforeCommit = !committed;
                    e.faults.afterCommit = committed;
                }
            };
            assertThrows(IllegalStateException.class, () -> e.ticks(1));
            assertThrows(CommitOutcomeUnknownException.class, e::execution);
            try (var fresh = SqliteEconomyDatabase.open(file()); var statement = fresh.createStatement();
                 var row = statement.executeQuery("SELECT b.ended_tick,s.batch_id,p.employees FROM production_batches b JOIN building_staffing s USING(building_id) JOIN payroll_accruals p USING(building_id)")) {
                assertTrue(row.next());
                assertEquals(committed ? 200L : null, row.getObject(1) == null ? null : row.getLong(1));
                assertEquals(committed ? null : batch.toString(), row.getString(2));
                assertEquals(committed ? 0 : 14, row.getLong(3));
            }
        }
        try (var recovered = new TestEconomy(file(), false)) {
            assertNull(recovered.execution().batch());
            assertEquals(batch, recovered.execution().lastBatch().getId());
            assertEquals(1, recovered.stockState().settlements().size());
            assertEquals(30, recovered.quantity("tools"));
            assertEquals(0, recovered.staffingRepository.load(recovered.id).totalOccupied());
            assertEquals(3, recovered.scalar("SELECT COUNT(*) FROM payroll_employment_history"));
        }
    }

    @Test void normalStartPublishesAllFourFactsInOnePhysicalCommit() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.faults.commits = 0;
            var batch = e.coordinator.startBatch(e.id, false);
            assertEquals(1, e.faults.commits);
            assertEquals(0, batch.getProgressUnits());
            assertEquals(1, e.scalar("SELECT COUNT(*) FROM production_batches"));
            var allocation = e.staffingRepository.load(e.id);
            assertEquals(batch.getId(), allocation.batchId());
            assertEquals(batch.getConfiguration().workforcePlan().participatingWorkers(), allocation.occupiedByOccupation());
            assertEquals(allocation.totalOccupied(), e.scalar("SELECT employees FROM payroll_accruals"));
            assertEquals(batch.getConfiguration().resolvedRecipe().inputs(), e.stockState().reservations().get(batch.getId()));
        }
    }

    @Test void failedAutomaticStartKeepsAcceptedTickPendingAndRecoversOnce() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.setAutomatic(e.id, true);
            e.sql("CREATE TRIGGER fail_start BEFORE INSERT ON production_batches BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(IllegalStateException.class, () -> e.ticks(1));
            assertEquals(1, e.clock.persistentTime().acceptedTick());
            assertEquals(0, e.clock.persistentTime().productionCallbackReturnedTick());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(0, e.staffingRepository.load(e.id).totalOccupied());
            e.sql("DROP TRIGGER fail_start");
            e.recover();
            var batch = e.execution().batch();
            assertEquals(0, batch.getProgressUnits());
            e.production.recoverAcceptedTick();
            assertEquals(batch.getId(), e.execution().batch().getId());
            assertEquals(1, e.stockState().reservations().size());
            assertEquals(14, e.scalar("SELECT employees FROM payroll_accruals"));
            assertEquals(1, e.clock.persistentTime().productionCallbackReturnedTick());
        }
    }

    @Test void concurrentPmAndStartFollowSuccessfulCommitOrder()throws Exception{
        try(var e=new TestEconomy(file(),true);var pool=Executors.newFixedThreadPool(2)){
            var start=pool.submit(()->e.production.startBatch(e.id));var pm=pool.submit(()->e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools"));start.get(15,TimeUnit.SECONDS);pm.get(15,TimeUnit.SECONDS);
            var frozen=e.execution().batch().getConfiguration();String selected=frozen.productionMethodSelections().get("tooling_workshop_base");
            if(selected.equals("crude_tools")){assertTrue(e.methods().pending().isPresent());assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals"));}
            else{assertEquals("reduced_tools",selected);assertTrue(e.methods().pending().isEmpty());assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals"));}
            assertEquals(1,e.stockState().reservations().size());
        }
    }
}
