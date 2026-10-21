package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingLifecycleConcurrencyTest {
    @TempDir Path dir;Path file(){return dir.resolve("race.sqlite");}
    @Test void twoConnectionsSameCompleteCommandHaveOneIntentAndOneRefund()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false);var pool=Executors.newFixedThreadPool(2)){
            a.production.startBatch(a.id);var command=BuildingLifecycleTest.command(a,Kind.SALE);var start=new CountDownLatch(1);
            var f1=pool.submit(()->{await(start);return a.lifecycle.request(command);});var f2=pool.submit(()->{await(start);return b.lifecycle.request(command);});start.countDown();
            assertEquals(Status.BLOCKED,f1.get(30,TimeUnit.SECONDS).status());assertEquals(Status.BLOCKED,f2.get(30,TimeUnit.SECONDS).status());
            assertEquals(1,a.scalar("SELECT COUNT(*) FROM building_lifecycle_operations"));assertEquals(1,a.stockState().settlements().size());assertEquals(100,b.quantity("wood"));assertTrue(b.stockState().reservations().isEmpty());
        }
    }
    @Test void twoDifferentOperationsCannotBothClaimOneBuilding()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false);var pool=Executors.newFixedThreadPool(2)){
            a.production.startBatch(a.id);var sale=BuildingLifecycleTest.command(a,Kind.SALE);var demolition=BuildingLifecycleTest.command(a,Kind.DEMOLITION);var start=new CountDownLatch(1);var success=new AtomicInteger();
            var f1=pool.submit(()->{await(start);try{a.lifecycle.request(sale);success.incrementAndGet();}catch(IllegalStateException conflict){}});
            var f2=pool.submit(()->{await(start);try{b.lifecycle.request(demolition);success.incrementAndGet();}catch(IllegalStateException conflict){}});start.countDown();f1.get(30,TimeUnit.SECONDS);f2.get(30,TimeUnit.SECONDS);
            assertEquals(1,success.get());assertEquals(1,a.scalar("SELECT COUNT(*) FROM building_lifecycle_operations"));assertEquals(1,b.stockState().settlements().size());assertEquals(100,b.quantity("wood"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void actualWritePermissionPrecedesFirstLifecycleAuthorityRead(String journal)throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            a.sql("PRAGMA journal_mode="+journal);b.sql("PRAGMA busy_timeout=0");var cmd=BuildingLifecycleTest.command(a,Kind.SALE);var checked=new AtomicBoolean();
            a.faults.beforeSql=sql->{if(sql.startsWith("SELECT * FROM building_lifecycle_operations WHERE id=")&&checked.compareAndSet(false,true)){
                var busy=assertThrows(SQLException.class,()->b.sql("UPDATE economic_buildings SET status=status"));assertEquals(5,busy.getErrorCode());
            }};
            assertEquals(Status.BLOCKED,a.lifecycle.request(cmd).status());assertTrue(checked.get());
        }
    }
    @Test void planningUsesDeferredSnapshotWithoutTakingAWriteLock()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            a.sql("PRAGMA journal_mode=WAL");b.sql("PRAGMA busy_timeout=0");var command=BuildingLifecycleTest.command(a,Kind.SALE);var changed=new AtomicBoolean();
            a.faults.beforeSql=sql->{if(sql.startsWith("SELECT employed FROM building_staffing_roles")&&changed.compareAndSet(false,true)) {
                try{b.sql("UPDATE regional_labor_supply SET total_supply=total_supply+1");}catch(SQLException failure){throw new RuntimeException(failure);}
            }};
            assertFalse(a.lifecycle.assess(command).finalizationAvailable());assertTrue(changed.get());assertEquals(101,b.scalar("SELECT total_supply FROM regional_labor_supply WHERE occupation_id='laborer'"));
        }
    }
    @Test void otherConnectionCannotApplyStaffingAfterIntentAndDoesNotFreeRegionalLabor()throws Exception{
        try(var a=new TestEconomy(file(),true);var b=new TestEconomy(file(),false)){
            a.production.startBatch(a.id);var cmd=BuildingLifecycleTest.command(a,Kind.DEMOLITION);a.inventory.failSettlement=true;
            assertThrows(IllegalStateException.class,()->a.lifecycle.request(cmd));
            assertThrows(IllegalStateException.class,()->b.staffing.requestTarget(a.id,"laborer",0));assertThrows(IllegalStateException.class,()->b.production.setAutomatic(a.id,true));
            assertEquals(14,b.scalar("SELECT SUM(employed) FROM building_staffing_roles"));assertEquals(14,b.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
            a.inventory.failSettlement=false;assertEquals(Status.BLOCKED,a.lifecycle.recover(cmd.id()).status());assertEquals(10,b.supply.occupied(com.bbmurloc.victoriaeconomics.server.TestIdentities.REGION,"laborer"));
        }
    }
    private static void await(CountDownLatch start){try{start.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RuntimeException(failure);}}
}
