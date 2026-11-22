package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionIntegrationTest {
    @TempDir Path dir;
    Path file(){return dir.resolve("economy.sqlite");}
    @Test void completionReleasesAtTick200StartsAt201AndAdvancesAt202()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var first=e.production.startBatch(e.id);e.ticks(199);assertEquals(199,e.execution().batch().getProgressUnits());
            e.ticks(1);assertEquals(first.getId(),e.execution().lastBatch().getId());assertTrue(e.execution().lastBatch().isCompleted());
            assertNull(e.execution().batch());
            assertEquals(200, first.startedTick() + 200);
            assertEquals(200L, e.execution().lastBatch().endedTick());
            assertNull(e.staffingRepository.load(e.id).batchId());
            assertThrows(ProductionStartBlocked.class, () -> e.production.startBatch(e.id));
            assertEquals(70,e.quantity("wood"));assertEquals(30,e.quantity("tools"));assertEquals(1,e.stockState().settlements().size());
            e.production.onServerTick();assertNull(e.execution().batch());
            e.ticks(1);
            assertNotEquals(first.getId(), e.execution().batch().getId());
            assertEquals(201, e.execution().batch().startedTick());
            assertEquals(0, e.execution().batch().getProgressUnits());
            e.ticks(1);assertEquals(1,e.execution().batch().getProgressUnits());
        }
    }
    @Test void pendingPmAppliesAtomicallyAfterFrozenOldBatchCompletes()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var first=e.production.startBatch(e.id);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");
            e.ticks(199);assertEquals("crude_tools",e.methods().effective().methods().get("tooling_workshop_base"));
            e.ticks(1);assertEquals("reduced_tools",e.methods().effective().methods().get("tooling_workshop_base"));
            assertEquals(0,e.staffingRepository.load(e.id).totalOccupied());
            e.ticks(1);
            assertEquals(Map.of("laborer",5,"machinist",2),e.staffingRepository.load(e.id).occupiedByOccupation());
            assertEquals(Map.of("laborer",10,"machinist",4),first.getConfiguration().workforcePlan().participatingWorkers());
            assertEquals(7,e.payrollRepository.load(UUID.nameUUIDFromBytes(("payroll:"+e.id).getBytes(java.nio.charset.StandardCharsets.UTF_8))).employees());
        }
    }
    @Test void pendingReplacementAndCancellationPersistWithoutChangingBatch()throws Exception{
        UUID batch;
        try(var e=new TestEconomy(file(),true)){
            batch=e.production.startBatch(e.id).getId();e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools");
            e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");assertEquals("reduced_tools",e.methods().pending().orElseThrow().methods().get("tooling_workshop_base"));
            e.production.cancelPendingProductionMethods(e.id);
        }
        try(var e=new TestEconomy(file(),false)){assertTrue(e.methods().pending().isEmpty());assertEquals(batch,e.execution().batch().getId());assertEquals(10,e.staffingRepository.load(e.id).occupiedByOccupation().get("laborer"));}
    }
    @Test void hiringUsesSupplyAtStartAndDoesNotRefillRunningBatch() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.setSupply("laborer", 5);
            assertEquals(0, e.staffingRepository.load(e.id).totalOccupied());
            var first = e.production.startBatch(e.id);
            assertEquals(5, e.staffingRepository.load(e.id).occupiedByOccupation().get("laborer"));
            e.setSupply("laborer", 20);
            e.ticks(399);
            assertEquals(first.getId(), e.execution().batch().getId());
            assertEquals(5, e.staffingRepository.load(e.id).occupiedByOccupation().get("laborer"));
            e.ticks(1);
            assertNull(e.staffingRepository.load(e.id).batchId());
            e.ticks(1);
            assertEquals(10, e.staffingRepository.load(e.id).occupiedByOccupation().get("laborer"));
        }
    }
    @Test void insufficientMaterialsLeaveNoIntentOrReservation()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.stock.withdraw(e.id,Map.of("wood",100L));assertThrows(ProductionStartBlocked.class,()->e.production.startBatch(e.id));assertTrue(e.stockState().reservations().isEmpty());assertEquals(0,e.quantity("wood"));}
    }
    @Test void missingSecondMaterialDoesNotReserveFirstMaterial() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.selectProductionMethod(e.id, "tooling_workshop_base", "efficient_tools");
            assertEquals(Set.of("wood", "iron"), e.methods().recipe().inputs().keySet());
            e.stock.withdraw(e.id, Map.of("iron", 100L));
            var stock = e.stockState();
            assertThrows(ProductionStartBlocked.class, () -> e.production.startBatch(e.id));
            assertEquals(stock, e.stockState());
            assertNull(e.execution().batch());
            e.stock.deposit(e.id, Map.of("iron", 100L));
            assertNotNull(e.production.startBatch(e.id));
        }
    }

    @Test void failedFinalCommitReleasesReservationAndDoesNotCreateBatch()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.sql("CREATE TRIGGER fail_start BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'failed'); END");assertThrows(RuntimeException.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());assertTrue(e.stockState().reservations().isEmpty());}
    }
    @Test void failedAtomicStartLeavesTheSameCompleteCheckpoint() throws Exception {
        var world = dir.resolve("failed-start-world");
        String initial;
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, true)) {
            initial = EconomyCheckpoint.capture(e.connection).sha256();
            e.sql("CREATE TRIGGER reject_start BEFORE INSERT ON production_batches BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(IllegalStateException.class, () -> e.production.startBatch(e.id));
            e.sql("DROP TRIGGER reject_start");
            assertEquals(initial, EconomyCheckpoint.capture(e.connection).sha256());
            runtime.requestCheckpoint().join();
        }
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, false)) {
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(0, e.staffingRepository.load(e.id).totalOccupied());
            assertEquals(0, e.scalar("SELECT employees FROM payroll_accruals"));
        }
    }

    @Test void startReadsSupplyAndReservationsCommittedByOtherWriter() throws Exception {
        try (var e = new TestEconomy(file(), true); var other = new TestEconomy(file(), false)) {
            var competitor = e.createStockedBuilding().getId();
            other.setSupply("laborer", 5);
            var batch = other.production.startBatch(competitor);
            assertEquals(5, batch.getConfiguration().workforcePlan().participatingWorkers().get("laborer"));
            assertThrows(ProductionStartBlocked.class, () -> e.production.startBatch(e.id));
            assertNull(e.execution().batch());
            assertTrue(e.stockState().reservations().isEmpty());
        }
    }

    @Test void manualPauseResumesSameBatchWithAutomaticOff()throws Exception{
        try(var e=new TestEconomy(file(),true)){var batch=e.production.startBatch(e.id);e.ticks(50);e.production.setManualPause(e.id,true);e.production.setAutomatic(e.id,false);e.ticks(10);assertEquals(50,e.execution().batch().getProgressUnits());e.production.setManualPause(e.id,false);e.ticks(1);assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(51,e.execution().batch().getProgressUnits());assertFalse(e.execution().automatic());assertEquals(1,e.stockState().reservations().size());}
    }
    @Test void payrollRecoveryFactsBlockOneTickWithoutInventingCurrentThenResume()throws Exception{
        try(var e=new TestEconomy(file(),true)){var batch=e.production.startBatch(e.id);e.ticks(10);
            new SqliteEconomicTimeRepository(e.connection,e.lock).acceptNext(10);
            assertEquals(State.RECOVERY,e.payroll.state(e.id));assertThrows(IllegalStateException.class,e.production::onServerTick);
            assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(10,e.execution().batch().getProgressUnits());
            e.recover();assertEquals(State.CURRENT,e.payroll.state(e.id));e.production.setAutomatic(e.id,false);e.ticks(1);
            assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(11,e.execution().batch().getProgressUnits());assertTrue(e.execution().pauseReasons().isEmpty());
        }
    }
    @Test void stopAtTick199KeepsBatchIntactAndCompletesExactlyOnce() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            var batch = e.production.startBatch(e.id);
            e.ticks(199);
            var stock = e.stockState();

            e.production.stopAfterCurrentBatch(e.id);
            var stopped = e.execution();
            assertFalse(stopped.automatic());
            assertEquals(batch.getId(), stopped.batch().getId());
            assertEquals(batch.getConfiguration(), stopped.batch().getConfiguration());
            assertEquals(ProductionBatch.Status.ACTIVE, stopped.batch().getStatus());
            assertEquals(199, stopped.batch().getProgressUnits());
            assertEquals(stock, e.stockState());
            assertThrows(ProductionStartBlocked.class, () -> e.production.startBatch(e.id));
            assertEquals(stock, e.stockState());

            e.ticks(1);
            assertNull(e.execution().batch());
            assertEquals(batch.getId(), e.execution().lastBatch().getId());
            assertEquals(ProductionBatch.Status.COMPLETED, e.execution().lastBatch().getStatus());
            assertEquals(200, e.execution().lastCompletedTick());
            assertEquals(70, e.quantity("wood"));
            assertEquals(30, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
            assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(1, e.scalar("SELECT COUNT(*) FROM production_batches"));

            e.ticks(1);
            assertNull(e.execution().batch());
            var manuallyStarted = e.production.startBatch(e.id);
            assertNotEquals(batch.getId(), manuallyStarted.getId());
            assertTrue(e.execution().automatic());
            assertEquals(1, e.stockState().reservations().size());
        }
    }

    @Test void idleStopDoesNotStartOrSettleBatch() throws Exception {
        try (var e = new TestEconomy(file(), true)) {
            e.production.setAutomatic(e.id, true);
            var stock = e.stockState();
            e.production.stopAfterCurrentBatch(e.id);
            long revision = e.execution().executionRevision();
            e.production.stopAfterCurrentBatch(e.id);
            assertEquals(revision, e.execution().executionRevision());
            e.ticks(1);
            assertFalse(e.execution().automatic());
            assertNull(e.execution().batch());
            assertNull(e.execution().lastBatch());
            assertEquals(stock, e.stockState());
        }
    }
    @ParameterizedTest @ValueSource(ints = {50, 199})
    void stopSurvivesCompleteCheckpointAndRestart(int savedTick) throws Exception {
        Path world = dir.resolve("world");
        UUID batch;
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, true)) {
            batch = e.production.startBatch(e.id).getId();
            e.ticks(savedTick);
            e.production.stopAfterCurrentBatch(e.id);
            runtime.requestCheckpoint();
            assertEquals(savedTick, runtime.awaitLatestCheckpoint().economicTick());
        }
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, false)) {
            assertEquals(savedTick, runtime.checkpointStatus().committed().economicTick());
            assertEquals(savedTick, e.clock.elapsedTicks());
            assertFalse(e.execution().automatic());
            assertEquals(batch, e.execution().batch().getId());
            assertEquals(savedTick, e.execution().batch().getProgressUnits());
            assertEquals(1, e.stockState().reservations().size());

            e.ticks(200 - savedTick);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertTrue(e.execution().lastBatch().isCompleted());
            assertEquals(200, e.execution().lastCompletedTick());
            assertEquals(70, e.quantity("wood"));
            assertEquals(30, e.quantity("tools"));
            assertEquals(1, e.stockState().settlements().size());
        }
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, false)) {
            assertEquals(200, e.clock.elapsedTicks());
            e.ticks(1);
            assertNull(e.execution().batch());
            assertEquals(batch, e.execution().lastBatch().getId());
            assertEquals(1, e.stockState().settlements().size());
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test void pausedCheckpointKeepsAllocationPayAndFrozenResourcesWithoutRecruitingAgain() throws Exception {
        Path world = dir.resolve("paused-world");
        UUID batch;
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, true)) {
            batch = e.production.startBatch(e.id).getId();
            e.ticks(25);
            e.production.setManualPause(e.id, true);
            e.production.stopAfterCurrentBatch(e.id);
            e.ticks(5);
            runtime.requestCheckpoint();
            assertEquals(30, runtime.awaitLatestCheckpoint().economicTick());
        }
        try (var runtime = EconomyDatabase.open(world); var e = new TestEconomy(runtime, false)) {
            assertEquals(30, e.clock.elapsedTicks());
            assertEquals(batch, e.execution().batch().getId());
            assertEquals(batch, e.staffingRepository.load(e.id).batchId());
            assertEquals(ProductionBatch.Status.PAUSED, e.execution().batch().getStatus());
            assertEquals(25, e.execution().batch().getProgressUnits());
            assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL), e.execution().pauseReasons());
            assertFalse(e.execution().automatic());
            assertEquals(Map.of("laborer", 10, "machinist", 4), e.staffingRepository.load(e.id).occupiedByOccupation());
            assertEquals(14, e.scalar("SELECT employees FROM payroll_accruals"));
            assertEquals(420, e.scalar("SELECT remainder FROM payroll_accruals"));
            assertEquals(2, e.scalar("SELECT COUNT(*) FROM payroll_employment_history"));
            assertEquals(e.execution().batch().getConfiguration().resolvedRecipe().inputs(), e.stockState().reservations().get(batch));
            e.production.setManualPause(e.id, false);
            e.ticks(175);
            assertEquals(batch, e.execution().lastBatch().getId());
            assertEquals(205L, e.execution().lastBatch().endedTick());
            assertEquals(0, e.staffingRepository.load(e.id).totalOccupied());
            assertEquals(30, e.quantity("tools"));
        }
    }

    @Test void missingWageConfigurationNeverAllowsStart()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.sql("DELETE FROM regional_wage_rates");assertEquals(State.UNAVAILABLE,e.payroll.state(e.id));assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());}
    }
    @Test void restartAfterInventoryReceiptLossCannotDuplicateProductsOrAdvanceTwice()throws Exception{
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);e.ticks(199);e.inventory.loseSettlementAcknowledgement=true;assertThrows(IllegalStateException.class,()->e.ticks(1));assertEquals(30,e.quantity("tools"));assertTrue(e.execution().batch().needsSettlement());assertEquals(200L,e.execution().processingTick());}
        try(var e=new TestEconomy(file(),false)){assertEquals(30,e.quantity("tools"));assertEquals(200,e.execution().lastCompletedTick());assertNull(e.execution().batch());e.production.onServerTick();assertNull(e.execution().batch());e.ticks(1);assertEquals(0,e.execution().batch().getProgressUnits());}
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
        try(var e=new TestEconomy(file(),true)){e.ticks(71);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");assertEquals(0,e.staffingRepository.load(e.id).totalOccupied());assertEquals(0,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(71,e.scalar("SELECT last_accrued_tick FROM payroll_accruals"));assertEquals(0,e.scalar("SELECT remainder FROM payroll_accruals"));}
    }
}
