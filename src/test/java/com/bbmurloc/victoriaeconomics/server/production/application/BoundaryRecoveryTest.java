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
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryRecoveryTest {
    @TempDir Path dir;
    Path file(){return dir.resolve("boundary.sqlite");}
    String failure(String point){return switch(point){
        case "pm"->"BEFORE UPDATE ON production_method_configurations";
        case "staffing"->"BEFORE UPDATE ON building_staffing";
        case "payroll"->"BEFORE UPDATE ON payroll_accruals WHEN NEW.employees<>OLD.employees";
        case "history"->"BEFORE INSERT ON payroll_employment_history";
        default->"BEFORE UPDATE ON production_executions WHEN OLD.boundary='RECONFIGURING' AND NEW.boundary='NONE'";
    };}
    @ParameterizedTest @ValueSource(strings={"pm","staffing","payroll","history","execution"})
    void eachReconfigurationWriteFailureKeepsTheBarrierAndRollsBackAllRoots(String point)throws Exception{
        UUID first;
        try(var e=new TestEconomy(file(),true)){
            first=e.production.startBatch(e.id).getId();e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");e.staffing.requestTarget(e.id,"laborer",10);e.ticks(199);
            e.sql("CREATE TRIGGER failure "+failure(point)+" BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(IllegalStateException.class,()->e.ticks(1));
            assertEquals(ProductionExecution.Boundary.RECONFIGURING,e.execution().boundary());assertTrue(e.execution().batch().isCompleted());
            assertEquals("crude_tools",e.methods().effective().methods().get("tooling_workshop_base"));assertTrue(e.methods().pending().isPresent());
            assertEquals(Map.of("laborer",10,"machinist",4),e.staffingRepository.load(e.id).employed());assertEquals(10,e.staffingRepository.load(e.id).pending().get("laborer"));
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(200,e.scalar("SELECT last_accrued_tick FROM payroll_accruals"));
            assertEquals(1,e.stockState().settlements().size());assertEquals(30,e.quantity("tools"));assertTrue(e.stockState().reservations().isEmpty());
            assertThrows(RuntimeException.class,()->e.production.startBatch(e.id));e.sql("DROP TRIGGER failure");
        }
        try(var e=new TestEconomy(file(),false)){
            assertEquals(ProductionExecution.Boundary.NONE,e.execution().boundary());assertEquals(first,e.execution().lastBatch().getId());
            assertEquals("reduced_tools",e.methods().effective().methods().get("tooling_workshop_base"));assertEquals(Map.of("laborer",5,"machinist",2),e.staffingRepository.load(e.id).employed());
            assertTrue(e.staffingRepository.load(e.id).pending().isEmpty());assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(30,e.quantity("tools"));
            assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(200,e.execution().lastCompletedTick());
        }
    }
    @ParameterizedTest @ValueSource(strings={"pm","staffing","payroll","history"})
    void idlePmAndHeadcountNeverCommitSeparately(String point)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.ticks(51);long staffRev=e.staffingRepository.load(e.id).revision();
            e.sql("CREATE TRIGGER failure "+failure(point)+" BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(RuntimeException.class,()->e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools"));
            assertEquals("crude_tools",e.methods().effective().methods().get("tooling_workshop_base"));assertEquals(staffRev,e.staffingRepository.load(e.id).revision());
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(714,e.scalar("SELECT remainder FROM payroll_accruals"));
        }
    }
    @Test void pmArrivingAfterFailedBoundaryUsesNewestPendingBeforeStart()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");e.ticks(199);
            e.sql("CREATE TRIGGER failure BEFORE UPDATE ON building_staffing BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(IllegalStateException.class,()->e.ticks(1));e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");
            e.sql("DROP TRIGGER failure");e.recover();assertEquals("efficient_tools",e.execution().batch().getConfiguration().productionMethodSelections().get("tooling_workshop_base"));
            assertEquals(Map.of("laborer",10,"machinist",4),e.staffingRepository.load(e.id).employed());
        }
    }
    @Test void activePausedSettlingAndMissingAuthorityCannotClearReconfiguration()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);assertThrows(IllegalStateException.class,()->e.coordinator.reconfigure(e.id));
            e.production.setManualPause(e.id,true);assertThrows(IllegalStateException.class,()->e.coordinator.reconfigure(e.id));
            assertEquals(1,e.stockState().reservations().size());e.inventory.failSettlement=true;
            assertThrows(IllegalStateException.class,()->e.production.abortBatch(e.id));assertThrows(IllegalStateException.class,()->e.coordinator.reconfigure(e.id));
            assertEquals(ProductionBatch.Status.SETTLING_ABORTED,e.execution().batch().getStatus());
        }
    }
    @Test void beginTickCommitAndProgressAreAtomicAndRestartNeverAdvancesTwice()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions SET current_batch_id"))e.faults.afterCommit=true;};
            assertThrows(IllegalStateException.class,()->e.ticks(1));
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(1,e.execution().batch().getProgressUnits());assertEquals(1,e.execution().lastCompletedTick());e.production.onEconomicTick();assertEquals(1,e.execution().batch().getProgressUnits());}
    }
    @Test void callbackConfirmationCannotHideAnUnsettledManualAbortAfterTickCompletion()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.ticks(1);e.inventory.failSettlement=true;
            assertThrows(IllegalStateException.class,()->e.production.abortBatch(e.id));
            assertEquals(1,e.execution().lastCompletedTick());assertTrue(e.coordinator.needsRecovery());
            assertThrows(IllegalStateException.class,()->e.coordinator.confirmWholeTick(1));
            assertEquals(1,e.stockState().reservations().size());
            e.inventory.failSettlement=false;e.production.recoverAcceptedTick();
            assertFalse(e.coordinator.needsRecovery());assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(ProductionBatch.Status.ABORTED,e.execution().lastBatch().getStatus());
        }
    }
    @Test void completionCheckpointFailureAfterStartingNextBatchDoesNotAdvanceNewBatchOnRecovery()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.ticks(199);
            e.sql("CREATE TRIGGER fail_finish BEFORE UPDATE ON production_executions WHEN NEW.last_completed_tick>OLD.last_completed_tick BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(IllegalStateException.class,()->e.ticks(1));assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(200L,e.execution().processingTick());
            e.sql("DROP TRIGGER fail_finish");
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(30,e.quantity("tools"));assertNull(e.execution().processingTick());e.ticks(1);assertEquals(1,e.execution().batch().getProgressUnits());}
    }
}
