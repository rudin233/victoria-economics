package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryAndControlsTest {
    @TempDir Path dir;Path file(){return dir.resolve("control.sqlite");}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostReceiptRecoversExactOutcomeWithoutDuplicatingGoods(boolean completed)throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.ticks(199);e.inventory.loseSettlementAcknowledgement=true;
            if(completed)assertThrows(IllegalStateException.class,()->e.ticks(1));else assertThrows(CommitOutcomeUnknownException.class,()->e.production.abortBatch(e.id));
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(completed?30:0,e.quantity("tools"));assertEquals(completed?70:100,e.quantity("wood"));assertEquals(1,e.stockState().settlements().size());assertEquals(completed,e.execution().lastBatch().isCompleted());}
    }
    @Test void realArrearsPauseOriginalBatchAndDoNotReleaseInputsOrRollBackWages()throws Exception{
        try(var e=new TestEconomy(file(),true)){var first=e.production.startBatch(e.id);e.production.setManualPause(e.id,true);e.ticks(1200);
            assertEquals(State.ARREARS,e.payroll.state(e.id));assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(first.getId(),e.execution().batch().getId());assertEquals(0,e.execution().batch().getProgressUnits());
            assertEquals(1,e.stockState().reservations().size());assertEquals(14,e.scalar("SELECT matured-paid FROM payroll_accruals"));assertEquals(0,e.scalar("SELECT paid FROM payroll_accruals"));
            e.production.setManualPause(e.id,false);assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(Set.of(ProductionExecution.PauseReason.PAYROLL_ARREARS),e.execution().pauseReasons());
            e.production.setAutomatic(e.id,false);e.ticks(1);assertEquals(first.getId(),e.execution().batch().getId());assertEquals(0,e.execution().batch().getProgressUnits());
        }
    }
    @Test void arrearsDismissalAbortsBeforeChangingPeopleAndPreservesDebt()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.production.setManualPause(e.id,true);e.ticks(1200);e.inventory.failSettlement=true;
            assertThrows(IllegalStateException.class,()->e.staffing.requestTarget(e.id,"laborer",0));assertEquals(ProductionBatch.Status.SETTLING_ABORTED,e.execution().batch().getStatus());assertFalse(e.execution().automatic());assertEquals(10,e.staffingRepository.load(e.id).employed().get("laborer"));assertEquals(1,e.stockState().reservations().size());
            e.inventory.failSettlement=false;e.production.retry(e.id);assertNull(e.execution().batch());assertTrue(e.execution().lastBatch().isAborted());assertFalse(e.staffingRepository.load(e.id).employed().containsKey("laborer"));assertEquals(4,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(14,e.scalar("SELECT matured-paid FROM payroll_accruals"));assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));
        }
    }
    @Test void isolatedPauseReasonsCannotClearManualPauseWhenPayrollReasonDisappears()throws Exception{
        try(var e=new TestEconomy(file(),true)){var root=e.execution();root.start(UUID.randomUUID(),e.coordinator.prepare(e.id).configuration());root.pause(ProductionExecution.PauseReason.MANUAL);root.pause(ProductionExecution.PauseReason.PAYROLL_ARREARS);root.resume(ProductionExecution.PauseReason.PAYROLL_ARREARS);assertEquals(ProductionBatch.Status.PAUSED,root.batch().getStatus());assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL),root.pauseReasons());root.requestAutomatic(false);root.resume(ProductionExecution.PauseReason.MANUAL);assertTrue(root.batch().isActive());assertFalse(root.automatic());}
    }
    @Test void recoveredPersistentPayrollCursorClearsOnlyItsReasonAndAutomaticOffDoesNotPreventResume()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            UUID batch=e.production.startBatch(e.id).getId();e.ticks(10);e.production.setManualPause(e.id,true);e.production.setAutomatic(e.id,false);
            new SqliteEconomicTimeRepository(e.connection,e.lock).acceptNext(10);
            assertThrows(IllegalStateException.class,e.production::onEconomicTick);
            assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL,ProductionExecution.PauseReason.PAYROLL_RECOVERY),e.execution().pauseReasons());
            e.recover();e.ticks(1);
            assertEquals(State.CURRENT,e.payroll.state(e.id));assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL),e.execution().pauseReasons());
            assertEquals(batch,e.execution().batch().getId());assertEquals(10,e.execution().batch().getProgressUnits());assertEquals(1,e.stockState().reservations().size());
            e.production.setManualPause(e.id,false);e.ticks(1);
            assertEquals(batch,e.execution().batch().getId());assertEquals(11,e.execution().batch().getProgressUnits());assertFalse(e.execution().automatic());
        }
    }
    @Test void journalCleanupFailureNeverCompensatesCommittedBatch()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.journal.failCleanup=true;var batch=e.production.startBatch(e.id);assertEquals(1,e.journal.pendingStarts().size());assertEquals(1,e.stockState().reservations().size());e.journal.failCleanup=false;e.production.recoverStarts();assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(1,e.stockState().reservations().size());}
    }
    @Test void unavailableJournalBlocksMutationAndPreservesCommitments()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.journal.failReads=true;assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertTrue(e.stockState().reservations().isEmpty());assertNull(e.execution().batch());}
    }
    @Test void mixedConcurrentControlsNeverLoseOrDuplicateSettlement()throws Exception{
        try(var e=new TestEconomy(file(),true);var pool=Executors.newFixedThreadPool(3)){
            e.production.startBatch(e.id);e.ticks(100);var a=pool.submit(()->e.production.abortBatch(e.id));var b=pool.submit(()->e.production.setAutomatic(e.id,false));var c=pool.submit(()->e.production.retry(e.id));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);c.get(15,TimeUnit.SECONDS);e.production.retry(e.id);assertEquals(1,e.stockState().settlements().size());assertEquals(0,e.quantity("tools"));assertEquals(100,e.quantity("wood"));assertFalse(e.execution().automatic());
        }
    }
    @Test void abandonedIntentUsesItsOwnBuildingForCompensation()throws Exception{
        try(var e=new TestEconomy(file(),true)){var other=e.createBuilding();e.stock.createLocation(other.getId(),100);e.stock.deposit(other.getId(),Map.of("wood",20L));UUID batch=UUID.randomUUID();e.journal.recordStart(new com.bbmurloc.victoriaeconomics.server.production.port.ProductionJournal.StartIntent(batch,other.getId()));e.stock.reserve(other.getId(),batch,Map.of("wood",10L));e.production.recoverStarts();assertTrue(e.stock.inspect(other.getId()).reservations().isEmpty());assertEquals(20,e.stock.inspect(other.getId()).quantities().get("wood"));assertEquals(100,e.quantity("wood"));}
    }
    @Test void legacySchemaRejectionPreservesOriginalFile()throws Exception{
        Path legacy=dir.resolve("legacy.sqlite");try(var c=DriverManager.getConnection("jdbc:sqlite:"+legacy);var s=c.createStatement()){s.execute("CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)");s.execute("INSERT INTO economy_schema_migrations VALUES(6)");s.execute("CREATE TABLE employment_state(state_json TEXT)");s.execute("INSERT INTO employment_state VALUES('legacy UUID snapshot')");}
        byte[] before=Files.readAllBytes(legacy);assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(legacy));assertArrayEquals(before,Files.readAllBytes(legacy));
    }
    @Test void opaqueRollbackCheckpointCannotApplyToAnotherRoot(){var a=new ProductionExecution(UUID.randomUUID());var b=new ProductionExecution(UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->b.restoreState(a.state()));}
    @Test void automaticOffPreventsNextBatchButDoesNotStopCurrentProgress()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.production.setAutomatic(e.id,false);e.ticks(200);assertNull(e.execution().batch());assertTrue(e.execution().lastBatch().isCompleted());assertEquals(30,e.quantity("tools"));}
    }
}
