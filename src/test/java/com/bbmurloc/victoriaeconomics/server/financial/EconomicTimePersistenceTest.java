package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.production.application.EconomicClock;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.concurrent.atomic.*;
import java.util.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import static org.junit.jupiter.api.Assertions.*;

class EconomicTimePersistenceTest {
    @TempDir Path dir;
    Path file() { return dir.resolve("time.sqlite"); }
    @Test void real1200CallbacksMakeOneMinuteAndRestartHasNoOfflineCatchup() throws Exception {
        var calls=new AtomicInteger();long nanos=System.nanoTime();
        try(var w=new FinancialTestWorld(file())) {
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,calls::incrementAndGet,()->{},new com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollPeriodService(w.c,w.world.lock)::recoverAcceptedTime);
            for(int i=0;i<1200;i++)clock.onServerTick();
            var t=clock.persistentTime();assertEquals(1200,t.acceptedTick());assertEquals(1,t.periodId());assertEquals(1200,t.lastPeriodBoundary());
            assertTrue(t.payrollCaughtUp());assertFalse(t.hasUnconfirmedProductionCallback());assertEquals(1200,calls.get());
        }
        System.out.println("Persisted 1200 callbacks (accept + payroll + production acknowledgement): "+((System.nanoTime()-nanos)/1_000_000)+" ms");
        try(var w=new FinancialTestWorld(file())) {
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,calls::incrementAndGet,()->{},new com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollPeriodService(w.c,w.world.lock)::recoverAcceptedTime);
            assertEquals(1200,clock.elapsedTicks());assertEquals(1200,calls.get());
            clock.onServerTick();assertEquals(1201,clock.elapsedTicks());assertEquals(1,clock.persistentTime().periodId());
        }
    }
    @Test void acceptedTickIsNotProductionCompletionAndCrashNeverReplaysBatch() throws Exception {
        try(var w=new FinancialTestWorld(file())) {w.time.acceptNext(0);assertFalse(w.time.load().payrollCaughtUp());assertTrue(w.time.load().hasUnconfirmedProductionCallback());}
        var calls=new AtomicInteger();
        try(var w=new FinancialTestWorld(file())) {
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,calls::incrementAndGet);
            assertEquals(0,calls.get());assertTrue(clock.persistentTime().payrollCaughtUp());
            assertEquals(1L,clock.persistentTime().firstUncertainProductionTick());
            clock.onServerTick();assertEquals(1,calls.get());assertEquals(2,clock.elapsedTicks());
            assertTrue(clock.persistentTime().hasUnconfirmedProductionCallback(),"No false end-to-end exactly-once claim");
        }
    }
    @Test void callbackFailureHaltsInstanceAndRestartSkipsPreviouslyAttemptedProduction() throws Exception {
        var calls=new AtomicInteger();
        try(var w=new FinancialTestWorld(file())) {
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,()->{calls.incrementAndGet();throw new IllegalStateException("partial production writes");});
            assertThrows(IllegalStateException.class,clock::onServerTick);assertThrows(IllegalStateException.class,clock::onServerTick);
            assertEquals(1,calls.get());assertEquals(1,w.time.load().acceptedTick());
        }
        try(var w=new FinancialTestWorld(file())) {
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,calls::incrementAndGet);
            assertEquals(1,calls.get());clock.onServerTick();assertEquals(2,calls.get());assertEquals(2,w.time.load().acceptedTick());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownAcceptedTickIsVerifiedNotAutomaticallyAllocatedAgain(boolean committed) throws Exception {
        var faults=new AtomicReference<JdbcFaults>();var calls=new AtomicInteger();
        try(var w=new FinancialTestWorld(file(),base->{var f=new JdbcFaults(base);faults.set(f);return f.connection;})) {
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,calls::incrementAndGet);var f=faults.get();
            f.beforeCommit=!committed;f.afterCommit=committed;f.commits=0;
            assertThrows(CommitOutcomeUnknownException.class,clock::onServerTick);assertEquals(1,f.commits);assertEquals(0,calls.get());
            assertThrows(IllegalStateException.class,clock::onServerTick);assertEquals(1,f.commits);
            try(var c=SqliteEconomyDatabase.open(file())) {
                var time=new SqliteEconomicTimeRepository(c,new Object());assertEquals(committed?1:0,time.load().acceptedTick());
                var payroll=new PayrollAccrualService(c,new Object());var reopened=new EconomicClock(time,payroll::recoverAcceptedTime,calls::incrementAndGet);
                assertEquals(0,calls.get());assertEquals(committed?1:0,reopened.elapsedTicks());
            }
        }
    }
    @Test void tickCasAndOverflowCannotReuseOrWrapPeriodIdentity() throws Exception {
        try(var w=new FinancialTestWorld(file());var other=new FinancialTestWorld(file())) {
            w.time.acceptNext(0);assertThrows(IllegalStateException.class,()->other.time.acceptNext(0));assertEquals(1,other.time.load().acceptedTick());
            w.acceptedTimeFixture(Long.MAX_VALUE);assertThrows(ArithmeticException.class,()->w.time.acceptNext(Long.MAX_VALUE));
            assertEquals(Long.MAX_VALUE,w.time.load().acceptedTick());assertTrue(w.time.load().lastPeriodBoundary()>0);
        }
    }
    @Test void sqlFailureDoesNotPublishAcceptedTick() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            w.sql("CREATE TRIGGER fail_tick BEFORE UPDATE ON economic_time BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(RuntimeException.class,()->w.time.acceptNext(0));assertEquals(0,w.time.load().acceptedTick());
        }
    }
    @Test void crashAfterRealBatchAdvanceNeverReplaysThatAdvanceOnClockRecovery() throws Exception {
        UUID building;
        try(var w=new FinancialTestWorld(file())) {
            building=w.staffingFixture(1,1);var repo=new SqliteProductionExecutionRepository(w.c,w.world.lock);
            var root=repo.load(building);
            var recipe=new ResolvedProductionRecipe(Map.of("ore",1L),Map.of("metal",1L),Map.of("laborer",1));
            root.start(UUID.randomUUID(),new ProductionBatchConfiguration(Map.of("base","test"),recipe,new WorkforcePlan(Map.of("laborer",1),1,1)));
            repo.save(root,root.executionRevision());
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,()->{
                var execution=repo.load(building);long tick=w.time.load().acceptedTick();execution.beginTick(tick);execution.tick(1);execution.finishTick(tick);repo.save(execution,execution.executionRevision());throw new IllegalStateException("crash after production save");
            });
            assertThrows(IllegalStateException.class,clock::onServerTick);assertEquals(1,repo.load(building).batch().getProgressUnits());
        }
        try(var w=new FinancialTestWorld(file())) {
            var repo=new SqliteProductionExecutionRepository(w.c,w.world.lock);
            var clock=new EconomicClock(w.time,w.accrual::recoverAcceptedTime,()->{var execution=repo.load(building);long tick=w.time.load().acceptedTick();execution.beginTick(tick);execution.tick(1);execution.finishTick(tick);repo.save(execution,execution.executionRevision());});
            assertEquals(1,repo.load(building).batch().getProgressUnits());assertTrue(clock.persistentTime().hasUnconfirmedProductionCallback());
            clock.onServerTick();assertEquals(2,repo.load(building).batch().getProgressUnits());
        }
    }
}
