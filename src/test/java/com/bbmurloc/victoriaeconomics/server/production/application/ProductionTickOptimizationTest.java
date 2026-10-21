package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bbmurloc.victoriaeconomics.server.production.application.ProductionBoundaryCoordinator.TickResult.*;

/** Atomic completion is allowed only for transaction-local work; resource recovery stays staged. */
class ProductionTickOptimizationTest {
    @TempDir Path dir;
    Path file(){return dir.resolve("optimized.sqlite");}
    private static void accept(TestEconomy e,long previous){new SqliteEconomicTimeRepository(e.connection,e.lock).acceptNext(previous);e.accrual.recoverAcceptedTime();}

    @Test void activeAdvanceAndCheckpointUseOneCommitAndOneRevision()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            UUID batch=e.production.startBatch(e.id).getId();long revision=e.execution().executionRevision();accept(e,0);e.faults.commits=0;
            assertEquals(COMPLETED,e.coordinator.processTick(e.id,1));assertEquals(1,e.faults.commits);
            var result=e.execution();assertEquals(batch,result.batch().getId());assertEquals(1,result.batch().getProgressUnits());
            assertEquals(1,result.lastCompletedTick());assertNull(result.processingTick());assertEquals(revision+1,result.executionRevision());
            assertEquals(ALREADY_COMPLETED,e.coordinator.processTick(e.id,1));assertEquals(1,e.execution().batch().getProgressUnits());
            e.production.onEconomicTick();assertEquals(1,e.clock.persistentTime().productionCallbackReturnedTick());
        }
    }
    @Test void idleAndBlockedLifecycleCompleteWithoutInventingProductionOrEndingWages()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            long prior=e.execution().executionRevision();accept(e,0);e.faults.commits=0;
            assertEquals(COMPLETED,e.coordinator.processTick(e.id,1));assertEquals(1,e.faults.commits);assertEquals(prior+1,e.execution().executionRevision());
            assertNull(e.execution().batch());e.production.onEconomicTick();
            var command=new BuildingLifecycleOperation.Command(UUID.randomUUID(),e.id,TestIdentities.COMPANY,null,BuildingLifecycleOperation.Kind.DEMOLITION,0);
            e.lifecycle.request(command);accept(e,1);e.faults.commits=0;
            assertEquals(COMPLETED,e.coordinator.processTick(e.id,2));assertEquals(1,e.faults.commits);
            assertEquals(BuildingLifecycleOperation.Status.BLOCKED,e.lifecycle.find(command.id()).orElseThrow().status());
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
            assertEquals(2,e.scalar("SELECT last_accrued_tick FROM payroll_accruals WHERE closed_tick IS NULL"));
            assertEquals(14,e.supply.occupied(TestIdentities.REGION,"laborer")+e.supply.occupied(TestIdentities.REGION,"machinist"));
        }
    }
    @Test void pausedBatchKeepsManualReasonReservationAndCheckpointTogether()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            UUID batch=e.production.startBatch(e.id).getId();e.ticks(1);e.production.setManualPause(e.id,true);e.production.setAutomatic(e.id,false);accept(e,1);
            assertEquals(COMPLETED,e.coordinator.processTick(e.id,2));var result=e.execution();
            assertEquals(batch,result.batch().getId());assertEquals(1,result.batch().getProgressUnits());assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL),result.pauseReasons());
            assertEquals(1,e.stockState().reservations().size());assertEquals(2,result.lastCompletedTick());
        }
    }
    @Test void completingBatchRetainsPendingMarkerUntilInventoryAndBoundaryAreConfirmed()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var batch=e.production.startBatch(e.id);e.ticks(199);accept(e,199);
            assertEquals(NEEDS_FOLLOW_UP,e.coordinator.processTick(e.id,200));
            assertEquals(ProductionBatch.Status.SETTLING_COMPLETED,e.execution().batch().getStatus());
            assertEquals(200L,e.execution().processingTick());assertEquals(199,e.execution().lastCompletedTick());assertTrue(e.stockState().settlements().isEmpty());
            e.inventory.failSettlement=true;assertThrows(IllegalStateException.class,e.production::onEconomicTick);assertEquals(200L,e.execution().processingTick());
            e.inventory.failSettlement=false;e.production.onEconomicTick();assertEquals(batch.getId(),e.execution().lastBatch().getId());
            assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(1,e.stockState().settlements().size());assertEquals(200,e.execution().lastCompletedTick());
        }
    }
    @Test void existingMarkerAndBirthTickAlwaysKeepTheStagedProtocol()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            accept(e,0);UUID batch=e.production.startBatch(e.id).getId();
            assertEquals(NEEDS_FOLLOW_UP,e.coordinator.processTick(e.id,1));assertEquals(0,e.execution().batch().getProgressUnits());
            e.production.onEconomicTick();assertEquals(batch,e.execution().batch().getId());assertEquals(0,e.execution().batch().getProgressUnits());
            e.ticks(1);assertEquals(1,e.execution().batch().getProgressUnits());
        }
    }
    @Test void failedBatchWriteRollsBackProgressAndCompletedCheckpointTogether()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);accept(e,0);
            e.sql("CREATE TRIGGER fail_progress BEFORE UPDATE ON production_batches WHEN NEW.progress_units>OLD.progress_units BEGIN SELECT RAISE(ABORT,'progress failure'); END");
            assertThrows(IllegalStateException.class,()->e.coordinator.processTick(e.id,1));
            assertEquals(0,e.execution().lastCompletedTick());assertNull(e.execution().processingTick());assertEquals(0,e.execution().batch().getProgressUnits());
            e.sql("DROP TRIGGER fail_progress");e.recover();assertEquals(1,e.execution().lastCompletedTick());assertEquals(1,e.execution().batch().getProgressUnits());
            e.production.onEconomicTick();assertEquals(1,e.execution().batch().getProgressUnits());
        }
    }
    @ParameterizedTest @CsvSource({"DELETE,false","DELETE,true","WAL,false","WAL,true"})
    void unknownCommitRequiresFreshVerificationOfBothProgressAndCheckpoint(String mode,boolean committed)throws Exception{
        UUID batch;
        try(var e=new TestEconomy(file(),true)){
            e.sql("PRAGMA journal_mode="+mode);batch=e.production.startBatch(e.id).getId();accept(e,0);var handle=e.connection.createStatement();
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions")){e.faults.beforeCommit=!committed;e.faults.afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->e.coordinator.processTick(e.id,1));
            assertThrows(CommitOutcomeUnknownException.class,e::execution);assertThrows(SQLException.class,()->handle.executeQuery("SELECT 1"));handle.close();
            try(var fresh=SqliteEconomyDatabase.open(file())){
                var result=new SqliteProductionExecutionRepository(fresh,new Object()).load(e.id);
                assertEquals(batch,result.batch().getId());assertEquals(committed?1:0,result.batch().getProgressUnits());
                assertEquals(committed?1:0,result.lastCompletedTick());assertNull(result.processingTick());
            }
        }
        try(var reopened=new TestEconomy(file(),false)){
            assertEquals(batch,reopened.execution().batch().getId());assertEquals(1,reopened.execution().batch().getProgressUnits());
            assertEquals(1,reopened.execution().lastCompletedTick());assertEquals(1,reopened.stockState().reservations().size());
            reopened.production.onEconomicTick();assertEquals(1,reopened.execution().batch().getProgressUnits());
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void twoConnectionsRaceOneTickWithoutRepeatingTheAtomicAdvance(String mode)throws Exception{
        try(var a=new TestEconomy(file(),true)){
            a.sql("PRAGMA journal_mode="+mode);a.production.startBatch(a.id);accept(a,0);
            try(var b=new TestEconomy(file(),false);var pool=Executors.newFixedThreadPool(2)){
                // Constructor recovery may already finish Tick 1. Allocate a new real accepted Tick for the race.
                accept(a,1);var gate=new CountDownLatch(1);
                var x=pool.submit(()->{gate.await();return a.coordinator.processTick(a.id,2);});
                var y=pool.submit(()->{gate.await();return b.coordinator.processTick(b.id,2);});gate.countDown();
                assertEquals(Set.of(COMPLETED,ALREADY_COMPLETED),Set.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS)));
                assertEquals(2,a.execution().batch().getProgressUnits());assertEquals(2,a.execution().lastCompletedTick());assertNull(a.execution().processingTick());
                a.production.onEconomicTick();b.production.onEconomicTick();assertEquals(2,a.execution().batch().getProgressUnits());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void atomicAdvanceStillAcquiresWritePermissionBeforeItsFirstAuthorityRead(String mode)throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            a.sql("PRAGMA journal_mode="+mode);b.sql("PRAGMA busy_timeout=0");a.production.startBatch(a.id);accept(a,0);var seen=new java.util.concurrent.atomic.AtomicBoolean();
            a.faults.beforeSql=sql->{if(sql.startsWith("SELECT t.*")&&seen.compareAndSet(false,true)){
                var busy=assertThrows(SQLException.class,()->b.sql("UPDATE economic_time SET revision=revision"));assertEquals(5,busy.getErrorCode());
            }};
            assertEquals(COMPLETED,a.coordinator.processTick(a.id,1));assertTrue(seen.get());
        }
    }
    @Test void duplicateGlobalReceiptUsesCommittedReadWithoutAnotherWriteTransaction()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.ticks(1);e.faults.commits=0;new SqliteEconomicTimeRepository(e.connection,e.lock).acknowledgeProductionCallback(1);
            assertEquals(0,e.faults.commits);assertThrows(IllegalStateException.class,()->new SqliteEconomicTimeRepository(e.connection,e.lock).acknowledgeProductionCallback(0));
            assertEquals(1,e.clock.persistentTime().productionCallbackReturnedTick());
        }
    }
    @Test void idleTickReconfigurationFailureKeepsPmStaffingPayrollAndCheckpointConsistent()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var pm=e.methods();long revision=pm.configurationRevision();pm.requestSelection("tooling_workshop_base","reduced_tools",true);e.configurations.save(pm,revision);
            accept(e,0);e.sql("CREATE TRIGGER fail_history BEFORE INSERT ON payroll_employment_history BEGIN SELECT RAISE(ABORT,'cutoff failure'); END");
            assertThrows(IllegalStateException.class,()->e.coordinator.processTick(e.id,1));
            assertEquals("crude_tools",e.methods().effective().methods().get("tooling_workshop_base"));assertTrue(e.methods().pending().isPresent());
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
            assertEquals(Map.of("laborer",10,"machinist",4),e.staffingRepository.load(e.id).employed());assertEquals(0,e.execution().lastCompletedTick());
            e.sql("DROP TRIGGER fail_history");e.recover();assertEquals(1,e.execution().lastCompletedTick());
            assertEquals("reduced_tools",e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));assertEquals(1,e.scalar("SELECT MAX(effective_tick) FROM payroll_employment_history"));
        }
    }
    @Test void idleFastPathCannotDeclareCompletionWithoutRealWageConfiguration()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            accept(e,0);e.sql("DELETE FROM regional_wage_rates");
            assertThrows(IllegalStateException.class,()->e.coordinator.processTick(e.id,1));
            assertEquals(0,e.execution().lastCompletedTick());assertNull(e.execution().processingTick());assertNull(e.execution().batch());
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void failedRollbackAndCloseCannotPublishTheCombinedCheckpoint(String mode)throws Exception{
        try(var e=new TestEconomy(file(),true);var reader=DriverManager.getConnection("jdbc:sqlite:"+file())){
            e.sql("PRAGMA journal_mode="+mode);e.production.startBatch(e.id);accept(e,0);
            e.sql("CREATE TRIGGER fail_progress BEFORE UPDATE ON production_batches WHEN NEW.progress_units>OLD.progress_units BEGIN SELECT RAISE(ABORT,'progress failure'); END");
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions")){e.faults.rollbackFailure=true;e.faults.closeFailure=true;e.faults.commits=0;e.faults.resets=0;}};
            assertThrows(CommitOutcomeUnknownException.class,()->e.coordinator.processTick(e.id,1));assertEquals(0,e.faults.commits);assertEquals(0,e.faults.resets);
            assertThrows(CommitOutcomeUnknownException.class,e::execution);
            try(var s=reader.createStatement();var r=s.executeQuery("SELECT e.last_completed_tick,b.progress_units FROM production_executions e JOIN production_batches b ON b.batch_id=e.current_batch_id")){
                assertTrue(r.next());assertEquals(0,r.getLong(1));assertEquals(0,r.getLong(2));
            }
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement()){s.execute("DROP TRIGGER fail_progress");}
        try(var e=new TestEconomy(file(),false)){assertEquals(1,e.execution().batch().getProgressUnits());assertEquals(1,e.execution().lastCompletedTick());}
    }
}
