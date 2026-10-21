package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionConcurrencyTest {
    @TempDir Path dir;Path file(){return dir.resolve("concurrent.sqlite");}
    @Test void twoConnectionsProcessingSameAcceptedTickAdvanceEachBuildingOnlyOnce()throws Exception{
        try(var a=new TestEconomy(file(),true)){
            a.production.startBatch(a.id);
            try(var b=new TestEconomy(file(),false);var pool=Executors.newFixedThreadPool(2)){
                new SqliteEconomicTimeRepository(a.connection,a.lock).acceptNext(0);a.accrual.recoverAcceptedTime();
                var start=new CountDownLatch(1);var f1=pool.submit(()->{await(start);a.production.onEconomicTick();});var f2=pool.submit(()->{await(start);b.production.onEconomicTick();});start.countDown();f1.get(20,TimeUnit.SECONDS);f2.get(20,TimeUnit.SECONDS);
                assertEquals(1,a.execution().batch().getProgressUnits());assertEquals(1,a.execution().lastCompletedTick());assertNull(a.execution().processingTick());
                a.production.onEconomicTick();b.production.onEconomicTick();assertEquals(1,a.execution().batch().getProgressUnits());assertEquals(1,a.stockState().reservations().size());assertEquals(1,b.clock.persistentTime().productionCallbackReturnedTick());
            }
        }
    }
    @Test void twoConnectionsCannotCommitTwoStartsForOneBuilding()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false);var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var count=new AtomicInteger();
            var f1=pool.submit(()->{await(start);attemptStart(a,count);});var f2=pool.submit(()->{await(start);attemptStart(b,count);});start.countDown();f1.get(20,TimeUnit.SECONDS);f2.get(20,TimeUnit.SECONDS);
            assertEquals(1,count.get());assertEquals(1,a.scalar("SELECT COUNT(*) FROM production_batches"));assertEquals(1,b.stockState().reservations().size());assertTrue(a.journal.pendingStarts().isEmpty());
        }
    }
    @Test void manualStartBetweenTickAcceptanceAndCallbackDoesNotAdvanceInItsBirthTick()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            new SqliteEconomicTimeRepository(a.connection,a.lock).acceptNext(0);a.accrual.recoverAcceptedTime();
            UUID batch=b.production.startBatch(b.id).getId();
            assertEquals(0,a.execution().batch().getProgressUnits());
            a.production.onEconomicTick();b.production.onEconomicTick();
            assertEquals(batch,a.execution().batch().getId());assertEquals(0,a.execution().batch().getProgressUnits());
            assertEquals(1,a.execution().lastCompletedTick());assertNull(a.execution().processingTick());
            a.ticks(1);assertEquals(batch,a.execution().batch().getId());assertEquals(1,a.execution().batch().getProgressUnits());
        }
    }
    @Test void regionalCompetitionCommitsHeadcountAndPayrollBaseTogetherWithoutRefill()throws Exception{
        try(var a=new TestEconomy(file(),true)){
            a.staffing.requestTarget(a.id,"laborer",0);a.staffing.requestTarget(a.id,"machinist",0);a.setSupply("laborer",12);
            UUID first=a.createBuilding().getId(),second=a.createBuilding().getId();
            try(var b=new TestEconomy(file(),false);var pool=Executors.newFixedThreadPool(2)){
                var start=new CountDownLatch(1);var f1=pool.submit(()->{await(start);a.staffing.requestTarget(first,"laborer",8);});var f2=pool.submit(()->{await(start);b.staffing.requestTarget(second,"laborer",8);});start.countDown();f1.get(20,TimeUnit.SECONDS);f2.get(20,TimeUnit.SECONDS);
                int x=a.staffingRepository.load(first).employed().get("laborer"),y=b.staffingRepository.load(second).employed().get("laborer");
                assertEquals(12,Math.addExact(x,y));assertEquals(Set.of(4,8),Set.of(x,y));
                assertTrue(a.staffingRepository.load(first).pending().isEmpty());assertTrue(b.staffingRepository.load(second).pending().isEmpty());
                assertEquals(12,a.scalar("SELECT SUM(employees) FROM payroll_accruals"));assertEquals(12,a.supply.occupied(TestIdentities.REGION,"laborer"));
                assertThrows(IllegalStateException.class,()->b.setSupply("laborer",11));a.setSupply("laborer",20);a.ticks(1);
                assertEquals(x,a.staffingRepository.load(first).employed().get("laborer"));assertEquals(y,b.staffingRepository.load(second).employed().get("laborer"));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void writePermissionPrecedesHeadcountPayrollAndPmAuthorityReads(String mode)throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            a.sql("PRAGMA journal_mode="+mode);b.sql("PRAGMA busy_timeout=0");var tested=new AtomicBoolean();
            a.faults.beforeSql=sql->{if(sql.startsWith("SELECT b.*")||sql.startsWith("SELECT * FROM economic_buildings")){
                if(tested.compareAndSet(false,true)){var busy=assertThrows(SQLException.class,()->b.sql("UPDATE regional_labor_supply SET total_supply=total_supply"));assertEquals(5,busy.getErrorCode());}
            }};
            a.staffing.requestTarget(a.id,"laborer",5);assertTrue(tested.get());assertEquals(9,a.scalar("SELECT employees FROM payroll_accruals"));
        }
    }
    @Test void oneBuildingFailureDoesNotReplayAnotherBuildingsCommittedAdvance()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var second=e.createBuilding();e.stock.createLocation(second.getId(),1000);e.stock.deposit(second.getId(),Map.of("wood",100L));e.staffing.requestTarget(second.getId(),"laborer",10);e.staffing.requestTarget(second.getId(),"machinist",4);
            e.production.startBatch(e.id);e.production.startBatch(second.getId());
            e.sql("CREATE TRIGGER fail_one BEFORE UPDATE ON production_executions WHEN NEW.building_id='"+e.id+"' AND (NEW.processing_tick IS NOT NULL OR NEW.last_completed_tick>OLD.last_completed_tick) BEGIN SELECT RAISE(ABORT,'one building failed'); END");
            assertThrows(IllegalStateException.class,()->e.ticks(1));assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(1,e.repository.load(second.getId()).batch().getProgressUnits());
            assertEquals(0,e.clock.persistentTime().productionCallbackReturnedTick());e.sql("DROP TRIGGER fail_one");e.recover();
            assertEquals(1,e.execution().batch().getProgressUnits());assertEquals(1,e.repository.load(second.getId()).batch().getProgressUnits());assertFalse(e.clock.persistentTime().hasUnconfirmedProductionCallback());
        }
    }
    private static void await(CountDownLatch start){try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
    @Test void anotherClockCannotAllocatePastAnUnresolvedAcceptedTick()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            a.production.startBatch(a.id);var first=new SqliteEconomicTimeRepository(a.connection,a.lock);var second=new SqliteEconomicTimeRepository(b.connection,b.lock);
            first.acceptNext(0);assertThrows(IllegalStateException.class,()->second.acceptNext(1));
            a.accrual.recoverAcceptedTime();assertThrows(IllegalStateException.class,()->second.acceptNext(1));
            a.production.onEconomicTick();second.acceptNext(1);assertEquals(2,first.load().acceptedTick());assertEquals(1,a.execution().batch().getProgressUnits());
        }
    }
    private static void attemptStart(TestEconomy e,AtomicInteger count){try{e.production.startBatch(e.id);count.incrementAndGet();}catch(IllegalStateException denied){}}
}
