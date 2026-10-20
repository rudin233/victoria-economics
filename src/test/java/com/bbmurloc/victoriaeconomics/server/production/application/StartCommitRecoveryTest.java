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
    @Test void failedReleaseKeepsIntentAndBlocksReconfigurationUntilExplicitRecovery()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.inventory.failAfterReserve=true;e.inventory.failRelease=true;assertThrows(RuntimeException.class,()->e.production.startBatch(e.id));assertThrows(IllegalStateException.class,()->e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools"));assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));e.inventory.failRelease=false;e.inventory.failAfterReserve=false;e.production.recoverStarts();e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");assertTrue(e.journal.pendingStarts().isEmpty());}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownFinalStartCommitRetainsIntentUntilNewTrustedConnectionVerifies(boolean committed)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions SET current_batch_id")){e.faults.beforeCommit=!committed;e.faults.afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->e.production.startBatch(e.id));
            e.faults.beforeSql=sql->{};e.faults.beforeCommit=false;e.faults.afterCommit=false;
            assertThrows(CommitOutcomeUnknownException.class,()->e.execution());
            try(var verified=SqliteEconomyDatabase.open(file())){
                try(var s=verified.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM production_start_intents")){r.next();assertEquals(1,r.getLong(1));}
                assertEquals(1,new com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore(verified,new Object()).inspect(e.id).reservations().size());
            }
            assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));
        }
        try(var e=new TestEconomy(file(),false)){assertTrue(e.journal.pendingStarts().isEmpty());assertEquals(committed,e.execution().batch()!=null);assertEquals(committed?1:0,e.stockState().reservations().size());assertEquals(100,e.quantity("wood"));}
    }
    @Test void lostStartAckDoesNotOverwriteNewPendingAfterVerifiedRecovery()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_executions SET current_batch_id"))e.faults.afterCommit=true;};assertThrows(CommitOutcomeUnknownException.class,()->e.production.startBatch(e.id));}
        try(var e=new TestEconomy(file(),false)){e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");assertTrue(e.methods().pending().isPresent());}
        try(var e=new TestEconomy(file(),false)){assertEquals("reduced_tools",e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));assertEquals("crude_tools",e.execution().batch().getConfiguration().productionMethodSelections().get("tooling_workshop_base"));}
    }
    @Test void terminationDecisionSurvivesSettlementReceiptLossAndRestart()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.ticks(99);e.inventory.loseSettlementAcknowledgement=true;assertThrows(CommitOutcomeUnknownException.class,()->e.production.abortBatch(e.id));assertEquals(ProductionBatch.Status.SETTLING_ABORTED,e.execution().batch().getStatus());assertFalse(e.execution().automatic());assertEquals(100,e.quantity("wood"));}
        try(var e=new TestEconomy(file(),false)){assertNull(e.execution().batch());assertTrue(e.execution().lastBatch().isAborted());assertEquals(0,e.quantity("tools"));assertEquals(1,e.stockState().settlements().size());assertFalse(e.execution().automatic());}
    }
    @Test void finalExecutionRevisionRecheckPreservesOtherControlCommand()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.inventory.afterReserve=()->e.production.setAutomatic(e.id,false);assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());assertFalse(e.execution().automatic());assertTrue(e.stockState().reservations().isEmpty());}
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
