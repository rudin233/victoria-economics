package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryAndControlsTest {
    @TempDir Path dir;Path file(){return dir.resolve("control.sqlite");}
    @Test void stopDuringLostCompletionReceiptDoesNotDuplicateGoodsOnRecovery() throws Exception {
        UUID batch;
        try (var e = new TestEconomy(file(), true)) {
            batch = e.production.startBatch(e.id).getId();
            e.ticks(199);
            e.inventory.loseSettlementAcknowledgement = true;
            assertThrows(IllegalStateException.class, () -> e.ticks(1));
            assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, e.execution().batch().getStatus());
            var settledStock = e.stockState();
            assertEquals(1, settledStock.settlements().size());

            e.production.stopAfterCurrentBatch(e.id);
            assertFalse(e.execution().automatic());
            assertEquals(ProductionBatch.Status.SETTLING_COMPLETED, e.execution().batch().getStatus());
            assertEquals(settledStock, e.stockState());
        }
        try (var e = new TestEconomy(file(), false)) {
            e.ticks(1);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(70, e.quantity("wood"));
            assertEquals(30, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
        }
    }
    @Test void realArrearsPauseOriginalBatchAndDoNotReleaseInputsOrRollBackWages()throws Exception{
        try(var e=new TestEconomy(file(),true)){var first=e.production.startBatch(e.id);e.production.setManualPause(e.id,true);e.ticks(1200);
            assertEquals(State.ARREARS,e.payroll.state(e.id));assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(first.getId(),e.execution().batch().getId());assertEquals(0,e.execution().batch().getProgressUnits());
            assertEquals(1,e.stockState().reservations().size());assertEquals(14,e.scalar("SELECT matured-paid FROM payroll_accruals"));assertEquals(0,e.scalar("SELECT paid FROM payroll_accruals"));
            e.production.setManualPause(e.id,false);assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(Set.of(ProductionExecution.PauseReason.PAYROLL_ARREARS),e.execution().pauseReasons());
            e.production.setAutomatic(e.id,false);e.ticks(1);assertEquals(first.getId(),e.execution().batch().getId());assertEquals(0,e.execution().batch().getProgressUnits());
        }
    }
    @Test void lifecycleAbortsDuringArrearsAndPreservesDebt()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.production.setManualPause(e.id,true);e.ticks(1200);e.inventory.failSettlement=true;
            assertThrows(IllegalStateException.class,e::beginDemolition);assertEquals(ProductionBatch.Status.SETTLING_ABORTED,e.execution().batch().getStatus());assertFalse(e.execution().automatic());assertEquals(10,e.staffingRepository.load(e.id).occupiedByOccupation().get("laborer"));assertEquals(1,e.stockState().reservations().size());
            e.inventory.failSettlement=false;e.lifecycle.recoverUnfinished();assertNull(e.execution().batch());assertTrue(e.execution().lastBatch().isAborted());assertFalse(e.staffingRepository.load(e.id).occupiedByOccupation().containsKey("laborer"));assertEquals(0,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(14,e.scalar("SELECT matured-paid FROM payroll_accruals"));assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));
        }
    }
    @Test void isolatedPauseReasonsCannotClearManualPauseWhenPayrollReasonDisappears()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);var root=e.execution();root.pause(ProductionExecution.PauseReason.MANUAL);root.pause(ProductionExecution.PauseReason.PAYROLL_ARREARS);root.resume(ProductionExecution.PauseReason.PAYROLL_ARREARS);assertEquals(ProductionBatch.Status.PAUSED,root.batch().getStatus());assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL),root.pauseReasons());root.requestAutomatic(false);root.resume(ProductionExecution.PauseReason.MANUAL);assertTrue(root.batch().isActive());assertFalse(root.automatic());}
    }
    @Test void recoveredPersistentPayrollCursorClearsOnlyItsReasonAndAutomaticOffDoesNotPreventResume()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            UUID batch=e.production.startBatch(e.id).getId();e.ticks(10);e.production.setManualPause(e.id,true);
            new SqliteEconomicTimeRepository(e.connection,e.lock).acceptNext(10);
            assertThrows(IllegalStateException.class,e.production::onServerTick);
            var pauseReasons = Set.of(ProductionExecution.PauseReason.MANUAL, ProductionExecution.PauseReason.PAYROLL_RECOVERY);
            assertEquals(pauseReasons, e.execution().pauseReasons());
            var stock = e.stockState();
            e.production.stopAfterCurrentBatch(e.id);
            assertEquals(pauseReasons, e.execution().pauseReasons());
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            assertEquals(stock, e.stockState());

            e.recover();e.ticks(1);
            assertEquals(State.CURRENT,e.payroll.state(e.id));assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL),e.execution().pauseReasons());
            assertEquals(batch,e.execution().batch().getId());assertEquals(10,e.execution().batch().getProgressUnits());assertEquals(1,e.stockState().reservations().size());
            e.production.setManualPause(e.id,false);e.ticks(1);
            assertEquals(batch,e.execution().batch().getId());assertEquals(11,e.execution().batch().getProgressUnits());assertFalse(e.execution().automatic());
            e.ticks(189);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(30, e.quantity("tools"));
        }
    }
    @Test void recoveryNeverReleasesResourcesOfCommittedBatch()throws Exception{
        try(var e=new TestEconomy(file(),true)){var batch=e.production.startBatch(e.id);assertEquals(1,e.stockState().reservations().size());e.production.recoverAcceptedTick();e.production.retry(e.id);assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(1,e.stockState().reservations().size());}
    }
    @Test void unavailableInventoryAuthorityBlocksStartBeforeAnyWrite()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.faults.beforeSql=sql->{if(sql.startsWith("SELECT state_json, revision"))throw new IllegalStateException("injected unavailable inventory");};assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));e.faults.beforeSql=sql->{};assertTrue(e.stockState().reservations().isEmpty());assertNull(e.execution().batch());}
    }
    @Test void concurrentStopRetryAndCompletionFollowCommitOrder() throws Exception {
        try (var e = new TestEconomy(file(), true); var pool = Executors.newFixedThreadPool(3)) {
            var batch = e.production.startBatch(e.id);
            e.ticks(199);
            var start = new CountDownLatch(1);
            var stop = pool.submit(() -> {
                start.await();
                synchronized (e.lock) {
                    var current = e.execution().batch();
                    boolean nextBatchAlreadyStarted = current != null && !current.getId().equals(batch.getId());
                    e.production.stopAfterCurrentBatch(e.id);
                    return nextBatchAlreadyStarted;
                }
            });
            var tick = pool.submit(() -> { start.await(); e.ticks(2); return null; });
            var retry = pool.submit(() -> { start.await(); e.production.retry(e.id); return null; });
            start.countDown();
            boolean nextBatchAlreadyStarted = stop.get(15, TimeUnit.SECONDS);
            tick.get(15, TimeUnit.SECONDS);
            retry.get(15, TimeUnit.SECONDS);

            var execution = e.execution();
            assertFalse(execution.automatic());
            assertEquals(batch.getId(), execution.lastBatch().getId());
            assertTrue(execution.lastBatch().isCompleted());
            assertEquals(1, e.stockState().settlements().size());
            assertEquals(30, e.quantity("tools"));
            assertEquals(70, e.quantity("wood"));
            assertEquals(nextBatchAlreadyStarted ? 1 : 0, e.stockState().reservations().size());
            assertEquals(nextBatchAlreadyStarted ? 2 : 1, e.scalar("SELECT COUNT(*) FROM production_batches"));
            if (nextBatchAlreadyStarted) {
                assertNotEquals(batch.getId(), execution.batch().getId());
                assertEquals(0, execution.batch().getProgressUnits());
                e.ticks(200);
                assertEquals(execution.batch().getId(), e.execution().lastBatch().getId());
                assertEquals(2, e.stockState().settlements().size());
                assertEquals(60, e.quantity("tools"));
                assertEquals(40, e.quantity("wood"));
            }
            assertNull(e.execution().batch());
        }
    }
    @Test void failedStartDoesNotModifyOtherBuildingReservation() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var other = e.createStockedBuilding().getId();
            var batch = e.production.startBatch(other);
            var before = e.stock.inspect(other);
            e.sql("CREATE TRIGGER reject_start BEFORE INSERT ON production_batches BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            assertEquals(before, e.stock.inspect(other));
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(batch.getId(), e.staffingRepository.load(other).batchId());
        }
    }

    @Test void legacySchemaRejectionPreservesOriginalFile()throws Exception{
        Path legacy=dir.resolve("legacy.sqlite");try(var c=DriverManager.getConnection("jdbc:sqlite:"+legacy);var s=c.createStatement()){s.execute("CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)");s.execute("INSERT INTO economy_schema_migrations VALUES(6)");s.execute("CREATE TABLE employment_state(state_json TEXT)");s.execute("INSERT INTO employment_state VALUES('legacy UUID snapshot')");}
        byte[] before=Files.readAllBytes(legacy);assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(legacy));assertArrayEquals(before,Files.readAllBytes(legacy));
    }
    @Test void opaqueRollbackCheckpointCannotApplyToAnotherRoot(){var a=new ProductionExecution(UUID.randomUUID());var b=new ProductionExecution(UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->b.restoreState(a.state()));}
    @Test void enablingAutomaticAfterRepeatedStopOnlyRestoresNextBatch() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var batch = e.production.startBatch(e.id);
            e.ticks(199);
            var stock = e.stockState();
            e.production.stopAfterCurrentBatch(e.id);
            long revision = e.execution().executionRevision();
            e.production.stopAfterCurrentBatch(e.id);
            assertEquals(revision, e.execution().executionRevision());
            e.production.setAutomatic(e.id, true);
            assertTrue(e.execution().automatic());
            assertEquals(batch.getId(), e.execution().batch().getId());
            assertEquals(199, e.execution().batch().getProgressUnits());
            assertEquals(stock, e.stockState());

            e.ticks(1);
            assertEquals(batch.getId(), e.execution().lastBatch().getId());
            assertNull(e.execution().batch());
            e.ticks(1);
            assertNotEquals(batch.getId(), e.execution().batch().getId());
            assertEquals(0, e.execution().batch().getProgressUnits());
            assertEquals(1, e.stockState().settlements().size());
            assertEquals(1, e.stockState().reservations().size());
        }
    }
}
