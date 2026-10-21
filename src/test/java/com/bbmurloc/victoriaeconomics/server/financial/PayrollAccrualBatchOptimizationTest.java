package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld.CURRENCY;

class PayrollAccrualBatchOptimizationTest {
    @TempDir Path dir;
    @Test void oneTransactionReusesWageHistoryButTheNextAcceptedIntervalReadsItsNewRate()throws Exception{
        var faults=new AtomicReference<JdbcFaults>();var reads=new AtomicInteger();
        try(var w=new FinancialTestWorld(dir.resolve("rates.sqlite"),c->{var f=new JdbcFaults(c);faults.set(f);return f.connection;})){
            var ids=new ArrayList<UUID>();for(int n=1;n<=4;n++)ids.add(w.enroll(w.staffingFixture(n,1)));
            w.acceptedTimeFixture(1201);faults.get().beforeSql=sql->{if(sql.startsWith("SELECT * FROM regional_wage_rates"))reads.incrementAndGet();};
            w.accrual.recoverAcceptedTime();assertEquals(1,reads.get());
            var expected=new HashMap<UUID,PayrollAccrual>();for(UUID id:ids){var p=w.payroll.load(id);assertEquals(p.employees(),p.remainder());expected.put(id,p);}
            var rate=new RegionalWageRate(TestIdentities.REGION,CURRENCY,1201,3,1);w.accrual.configureRate(rate);w.acceptedTimeFixture(2400);reads.set(0);
            w.accrual.recoverAcceptedTime();assertEquals(1,reads.get());
            for(UUID id:ids){var wanted=expected.get(id).accrueThrough(2400,List.of(new RegionalWageRate(TestIdentities.REGION,CURRENCY,0,1,0),rate)).accrual();
                var actual=w.payroll.load(id);assertEquals(wanted,actual);assertEquals(2,w.payroll.liabilities(id).size());}
        }
    }
    @Test void caughtSecondSegmentFailureStillRollsBackTheFirstAndTheGlobalCursor()throws Exception{
        try(var w=new FinancialTestWorld(dir.resolve("failure.sqlite"))){
            for(int n=1;n<=2;n++)w.staffingFixture(n,1);w.acceptedTimeFixture(100);
            var prior=SqliteTransactions.write(w.c,tx->w.payroll.inTransaction(tx).all());UUID second=prior.get(1).id();
            w.sql("CREATE TRIGGER fail_second BEFORE UPDATE ON payroll_accruals WHEN NEW.id='"+second+"' BEGIN SELECT RAISE(ABORT,'second accrual'); END");
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->{
                try{w.payroll.inTransaction(tx).accrueAll(prior,100);}catch(Exception caught){/* The session must remain poisoned. */}return null;
            }));
            for(var p:prior)assertEquals(p,w.payroll.load(p.id()));assertEquals(0,w.time.load().payrollTick());
            w.sql("DROP TRIGGER fail_second");w.accrual.recoverAcceptedTime();assertEquals(100,w.time.load().payrollTick());
        }
    }
    @Test void reusedHistoriesDoNotAllowAStaleSegmentOrUnaccruedStaffingBase()throws Exception{
        try(var w=new FinancialTestWorld(dir.resolve("stale.sqlite"))){
            UUID id=w.enroll(w.staffingFixture(1,1));var before=w.payroll.load(id);w.acceptedTimeFixture(10);w.accrual.recoverAcceptedTime();
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->{w.payroll.inTransaction(tx).accrueAll(List.of(before),10);return null;}));
            assertEquals(10,w.payroll.load(id).lastAccruedTick());
            // Deliberately invalid persistence fixture: this must never be accepted as a new wage base.
            w.sql("UPDATE building_staffing_roles SET employed=2");
            assertThrows(IllegalStateException.class,w.accrual::recoverAcceptedTime);assertEquals(10,w.time.load().payrollTick());
            w.acceptedTimeFixture(11);
            assertThrows(IllegalStateException.class,w.accrual::recoverAcceptedTime);assertEquals(10,w.time.load().payrollTick());assertEquals(1,w.payroll.load(id).employees());
        }
    }
    @Test void historyReusePreservesClosedEmployerRemaindersAndMaturity()throws Exception{
        try(var w=new FinancialTestWorld(dir.resolve("closed.sqlite"))){
            UUID building=w.staffingFixture(1,1),old=w.enroll(building);w.acceptedTimeFixture(100);w.accrual.recoverAcceptedTime();
            UUID replacement=SqliteTransactions.write(w.c,tx->{var p=w.payroll.inTransaction(tx);p.close(old,100);return p.enroll(UUID.randomUUID(),building,CURRENCY).id();});
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();assertEquals(100,w.payroll.load(old).remainder());
            assertEquals(1100,w.payroll.load(replacement).remainder());assertEquals(1,w.payroll.liabilities(old).size());assertEquals(1,w.payroll.liabilities(replacement).size());
            w.accrual.recoverAcceptedTime();assertEquals(100,w.payroll.load(old).remainder());assertEquals(2,w.scalar("SELECT COUNT(*) FROM payroll_liabilities"));
        }
    }
    @Test void caughtUpRecoveryChecksFactsWithoutInventingAnotherCursorRevision()throws Exception{
        try(var w=new FinancialTestWorld(dir.resolve("caught-up.sqlite"))){
            UUID id=w.enroll(w.staffingFixture(1,1));w.acceptedTimeFixture(1201);w.accrual.recoverAcceptedTime();
            long revision=w.scalar("SELECT revision FROM payroll_runtime");var segment=w.payroll.load(id);
            w.accrual.recoverAcceptedTime();assertEquals(revision,w.scalar("SELECT revision FROM payroll_runtime"));assertEquals(segment,w.payroll.load(id));
            assertEquals(1,w.payroll.liabilities(id).size());
        }
    }
    @Test void skippingAnUnchangedCursorNeverAllowsItsNextRealRevisionToOverflow()throws Exception{
        try(var w=new FinancialTestWorld(dir.resolve("revision.sqlite"))){
            UUID id=w.enroll(w.staffingFixture(1,1));w.sql("UPDATE payroll_runtime SET revision=9223372036854775807");
            w.accrual.recoverAcceptedTime();assertEquals(Long.MAX_VALUE,w.scalar("SELECT revision FROM payroll_runtime"));
            w.acceptedTimeFixture(1);assertThrows(IllegalStateException.class,w.accrual::recoverAcceptedTime);
            assertEquals(0,w.time.load().payrollTick());assertEquals(0,w.payroll.load(id).lastAccruedTick());assertEquals(0,w.payroll.load(id).remainder());
        }
    }
}
