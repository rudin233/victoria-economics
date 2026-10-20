package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionIntegrationTest {
    @TempDir Path dir;
    Path file(){return dir.resolve("economy.sqlite");}
    @Test void completionStartsNextBatchAtTick200WithZeroProgressThenAdvancesAt201()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var first=e.production.startBatch(e.id);e.ticks(199);assertEquals(199,e.execution().batch().getProgressUnits());
            e.ticks(1);assertEquals(first.getId(),e.execution().lastBatch().getId());assertTrue(e.execution().lastBatch().isCompleted());
            assertNotEquals(first.getId(),e.execution().batch().getId());assertEquals(0,e.execution().batch().getProgressUnits());
            assertEquals(70,e.quantity("wood"));assertEquals(30,e.quantity("tools"));assertEquals(1,e.stockState().settlements().size());
            e.production.onEconomicTick();assertEquals(0,e.execution().batch().getProgressUnits());e.ticks(1);assertEquals(1,e.execution().batch().getProgressUnits());
        }
    }
    @Test void pendingPmAppliesAtomicallyAfterFrozenOldBatchCompletes()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var first=e.production.startBatch(e.id);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");
            e.ticks(199);assertEquals("crude_tools",e.methods().effective().methods().get("tooling_workshop_base"));
            e.ticks(1);assertEquals("reduced_tools",e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(Map.of("laborer",5,"machinist",2),e.staffingRepository.load(e.id).employed());
            assertEquals(Map.of("laborer",10,"machinist",4),first.getConfiguration().workforcePlan().participatingWorkers());
            assertEquals(7,e.payrollRepository.load(UUID.nameUUIDFromBytes(("payroll:"+e.id).getBytes(java.nio.charset.StandardCharsets.UTF_8))).employees());
        }
    }
    @Test void pendingReplacementAndCancellationPersistWithoutChangingBatch()throws Exception{
        UUID batch;
        try(var e=new TestEconomy(file(),true)){
            batch=e.production.startBatch(e.id).getId();e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");
            e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");assertEquals("reduced_tools",e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            e.production.cancelPendingProductionMethods(e.id);e.staffing.requestTarget(e.id,"laborer",0);e.staffing.cancelPending(e.id,"laborer");
        }
        try(var e=new TestEconomy(file(),false)){assertTrue(e.methods().pending().isEmpty());assertEquals(batch,e.execution().batch().getId());assertEquals(10,e.staffingRepository.load(e.id).employed().get("laborer"));}
    }
    @Test void hiringUsesActualSupplyAndPmCapacityAndDoesNotLeaveAutoRecruitment()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.staffing.requestTarget(e.id,"laborer",3);e.setSupply("laborer",5);e.staffing.requestTarget(e.id,"laborer",10);
            assertEquals(5,e.staffingRepository.load(e.id).employed().get("laborer"));assertTrue(e.staffingRepository.load(e.id).pending().isEmpty());
            e.setSupply("laborer",20);e.staffing.applyEffectiveChanges(e.id);assertEquals(5,e.staffingRepository.load(e.id).employed().get("laborer"));
            assertThrows(IllegalArgumentException.class,()->e.staffing.requestTarget(e.id,"laborer",11));
        }
    }
    @Test void insufficientMaterialsLeaveNoIntentOrReservation()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.stock.withdraw(e.id,Map.of("wood",100L));assertThrows(ProductionStartBlocked.class,()->e.production.startBatch(e.id));assertTrue(e.stockState().reservations().isEmpty());assertTrue(e.journal.pendingStarts().isEmpty());assertEquals(0,e.quantity("wood"));}
    }
    @Test void partialMultiMaterialFailureCompensatesAllHeldInputs()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.inventory.reserveOnlyFirstMaterial=true;assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertTrue(e.stockState().reservations().isEmpty());assertTrue(e.journal.pendingStarts().isEmpty());e.inventory.reserveOnlyFirstMaterial=false;assertNotNull(e.production.startBatch(e.id));}
    }
    @Test void failedFinalCommitReleasesReservationAndDoesNotCreateBatch()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.sql("CREATE TRIGGER fail_start BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'failed'); END");assertThrows(RuntimeException.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());assertTrue(e.stockState().reservations().isEmpty());assertTrue(e.journal.pendingStarts().isEmpty());}
    }
    @Test void failedReleaseRemainsJournaledAndRestartReleasesEverything()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.inventory.failAfterReserve=true;e.inventory.failRelease=true;assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertEquals(1,e.journal.pendingStarts().size());assertEquals(1,e.stockState().reservations().size());}
        try(var e=new TestEconomy(file(),false)){assertTrue(e.journal.pendingStarts().isEmpty());assertTrue(e.stockState().reservations().isEmpty());assertEquals(100,e.quantity("wood"));}
    }
    @Test void finalAuthorityRecheckRejectsStaffingChangeAfterReservation()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.inventory.afterReserve=()->e.staffingRaceFixture("laborer",5);assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());assertEquals(5,e.staffingRepository.load(e.id).employed().get("laborer"));assertTrue(e.stockState().reservations().isEmpty());}
    }
    @Test void manualPauseResumesSameBatchWithAutomaticOff()throws Exception{
        try(var e=new TestEconomy(file(),true)){var batch=e.production.startBatch(e.id);e.ticks(50);e.production.setManualPause(e.id,true);e.production.setAutomatic(e.id,false);e.ticks(10);assertEquals(50,e.execution().batch().getProgressUnits());e.production.setManualPause(e.id,false);e.ticks(1);assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(51,e.execution().batch().getProgressUnits());assertFalse(e.execution().automatic());assertEquals(1,e.stockState().reservations().size());}
    }
    @Test void payrollRecoveryFactsBlockOneTickWithoutInventingCurrentThenResume()throws Exception{
        try(var e=new TestEconomy(file(),true)){var batch=e.production.startBatch(e.id);e.ticks(10);
            new SqliteEconomicTimeRepository(e.connection,e.lock).acceptNext(10);
            assertEquals(State.RECOVERY,e.payroll.state(e.id));assertThrows(IllegalStateException.class,e.production::onEconomicTick);
            assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(10,e.execution().batch().getProgressUnits());
            e.recover();assertEquals(State.CURRENT,e.payroll.state(e.id));e.production.setAutomatic(e.id,false);e.ticks(1);
            assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(11,e.execution().batch().getProgressUnits());assertTrue(e.execution().pauseReasons().isEmpty());
        }
    }
    @Test void abortAtNinetyNinePercentRefundsAllInputsAndProducesNothing()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.ticks(198);e.production.abortBatch(e.id);assertTrue(e.execution().lastBatch().isAborted());assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));assertTrue(e.stockState().reservations().isEmpty());assertFalse(e.execution().automatic());}
    }
    @Test void missingWageConfigurationNeverAllowsStart()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.sql("DELETE FROM regional_wage_rates");assertEquals(State.UNAVAILABLE,e.payroll.state(e.id));assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());assertTrue(e.journal.pendingStarts().isEmpty());}
    }
    @Test void restartAfterInventoryReceiptLossCannotDuplicateProductsOrAdvanceTwice()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.ticks(199);e.inventory.loseSettlementAcknowledgement=true;assertThrows(IllegalStateException.class,()->e.ticks(1));assertEquals(30,e.quantity("tools"));assertTrue(e.execution().batch().needsSettlement());assertEquals(200L,e.execution().processingTick());}
        try(var e=new TestEconomy(file(),false)){assertEquals(30,e.quantity("tools"));assertEquals(200,e.execution().lastCompletedTick());assertEquals(0,e.execution().batch().getProgressUnits());e.production.onEconomicTick();assertEquals(0,e.execution().batch().getProgressUnits());}
    }
    @Test void restartPreservesProgressRecipeAndReservation()throws Exception{
        UUID batch;ProductionBatchConfiguration snapshot;
        try(var e=new TestEconomy(file(),true)){var b=e.production.startBatch(e.id);batch=b.getId();snapshot=b.getConfiguration();e.ticks(17);}
        try(var e=new TestEconomy(file(),false)){assertEquals(batch,e.execution().batch().getId());assertEquals(snapshot,e.execution().batch().getConfiguration());assertEquals(17,e.execution().batch().getProgressUnits());assertEquals(1,e.stockState().reservations().size());}
    }
    @Test void otherBuildingInventoryCannotSupplyLocalMissingMaterials()throws Exception{
        try(var e=new TestEconomy(file(),true)){var other=e.createBuilding();e.stock.createLocation(other.getId(),1000);e.stock.deposit(other.getId(),Map.of("iron",500L));e.stock.withdraw(e.id,Map.of("wood",100L));assertThrows(ProductionStartBlocked.class,()->e.production.startBatch(e.id));assertEquals(500,e.stock.inspect(other.getId()).quantities().get("iron"));}
    }
    @Test void committedOverflowBlocksNextStartButAllowsWithdrawal()throws Exception{
        try(var e=new TestEconomy(file(),true,200)){e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");e.production.startBatch(e.id);e.ticks(200);assertTrue(e.stock.storageStatus(e.id).overCapacity());assertNull(e.execution().batch());assertEquals(60,e.quantity("tools"));assertThrows(IllegalStateException.class,()->e.stock.deposit(e.id,Map.of("wood",1L)));e.stock.withdraw(e.id,Map.of("tools",60L));e.ticks(1);assertNotNull(e.execution().batch());assertEquals(0,e.execution().batch().getProgressUnits());}
    }
    @Test void concurrentStartsProduceOnlyOneBatchAndReservation()throws Exception{
        try(var e=new TestEconomy(file(),true);var pool=Executors.newFixedThreadPool(2)){
            var tasks=List.of(pool.submit(()->attemptStart(e)),pool.submit(()->attemptStart(e)));int success=0;for(var result:tasks)success+=result.get(15,TimeUnit.SECONDS);
            assertEquals(1,success);assertEquals(1,e.stockState().reservations().size());assertEquals(1,e.scalar("SELECT COUNT(*) FROM production_batches"));
        }
    }
    private int attemptStart(TestEconomy e){try{e.production.startBatch(e.id);return 1;}catch(IllegalStateException expected){return 0;}}
    @Test void idlePmContractionImmediatelyAndAtomicallyAdjustsEmployeesAndWageBase()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.ticks(71);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");assertEquals(Map.of("laborer",5,"machinist",2),e.staffingRepository.load(e.id).employed());assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(71,e.scalar("SELECT last_accrued_tick FROM payroll_accruals"));assertEquals(994,e.scalar("SELECT remainder FROM payroll_accruals"));}
    }
}
