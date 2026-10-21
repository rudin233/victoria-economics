package com.bbmurloc.victoriaeconomics.server.household;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.financial.*;
import com.bbmurloc.victoriaeconomics.server.payroll.PaymentTestWorld;
import com.bbmurloc.victoriaeconomics.server.household.infrastructure.SqliteHouseholdBudgetRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
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

class HouseholdBudgetConcurrencyTest {
    @TempDir Path dir;Path file(){return dir.resolve("budget.sqlite");}
    UUID cash(PaymentTestWorld w,long amount){UUID target=w.recipient();w.fund(target,amount);return target;}
    void allocate(PaymentTestWorld w,long amount,long revision){w.households.allocate(TestIdentities.REGION,FinancialTestWorld.CURRENCY,amount,0,revision);}
    @Test void twoConnectionsUseOneBudgetCasAndNeitherCreatesCash()throws Exception{
        try(var a=new PaymentTestWorld(file())){
            UUID target=cash(a,100);long ledger=a.scalar("SELECT COUNT(*) FROM finance_ledger");
            try(var b=new PaymentTestWorld(file());var pool=Executors.newFixedThreadPool(2)){
                var latch=new CountDownLatch(1);var x=pool.submit(()->{await(latch);return attempt(()->allocate(a,60,0));});var y=pool.submit(()->{await(latch);return attempt(()->allocate(b,70,0));});latch.countDown();
                assertEquals(1,(x.get(20,TimeUnit.SECONDS)?1:0)+(y.get(20,TimeUnit.SECONDS)?1:0));assertEquals(100,a.f.accounts.load(target).balance());assertEquals(ledger,a.scalar("SELECT COUNT(*) FROM finance_ledger"));assertEquals(1,a.households.budget(TestIdentities.REGION,FinancialTestWorld.CURRENCY).revision());
            }
        }
    }
    @Test void allowanceAndReservationCompetitionCannotBothClaimTheSameCash()throws Exception{
        try(var a=new PaymentTestWorld(file())){
            UUID target=cash(a,100);
            try(var b=new PaymentTestWorld(file());var pool=Executors.newFixedThreadPool(2)){
                var latch=new CountDownLatch(1);var x=pool.submit(()->{await(latch);return attempt(()->allocate(a,100,0));});var y=pool.submit(()->{await(latch);return attempt(()->b.f.finance.reserve(new CashReservation(UUID.randomUUID(),target,100,"competing purpose",true)));});latch.countDown();
                assertEquals(1,(x.get(20,TimeUnit.SECONDS)?1:0)+(y.get(20,TimeUnit.SECONDS)?1:0));var budget=a.households.budget(TestIdentities.REGION,FinancialTestWorld.CURRENCY);assertTrue(budget.allocated()<=a.f.accounts.load(target).available());assertEquals(100,a.f.accounts.load(target).balance());
            }
        }
    }
    @Test void realWageCreditReservationAndBudgetAllocationRaceNeverOverAllocates()throws Exception{
        try(var a=new PaymentTestWorld(file())){
            a.building(1,50);UUID source=a.companyAccount(),target=cash(a,50);a.fund(source,50);a.mature(1);a.payments.preparePeriod(1);
            try(var b=new PaymentTestWorld(file());var c=new PaymentTestWorld(file());var pool=Executors.newFixedThreadPool(3)){
                var latch=new CountDownLatch(1);var x=pool.submit(()->{await(latch);a.payments.pay(a.command(1));});var y=pool.submit(()->{await(latch);return attempt(()->allocate(b,80,0));});var z=pool.submit(()->{await(latch);c.f.finance.reserve(new CashReservation(UUID.randomUUID(),target,20,"concurrent explicit cash lock",true));});latch.countDown();x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);z.get(20,TimeUnit.SECONDS);
                var budget=a.households.budget(TestIdentities.REGION,FinancialTestWorld.CURRENCY);assertEquals(100,a.f.accounts.load(target).balance());assertEquals(20,a.f.accounts.load(target).reserved());assertTrue(budget.allocated()<=80);assertEquals(50,a.scalar("SELECT SUM(paid) FROM payroll_accruals"));assertTrue(a.payments.verify(a.command(1)).isPresent());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownBudgetCommitRequiresFreshCashAndBudgetVerification(boolean committed)throws Exception{
        var ref=new AtomicReference<JdbcFaults>();
        try(var a=new PaymentTestWorld(file(),c->{var f=new JdbcFaults(c);ref.set(f);return f.connection;})){
            cash(a,100);ref.get().beforeSql=sql->{if(sql.startsWith("UPDATE regional_household_budgets SET consumption")){ref.get().beforeCommit=!committed;ref.get().afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->allocate(a,80,0));assertThrows(CommitOutcomeUnknownException.class,()->allocate(a,80,0));
        }
        try(var c=SqliteEconomyDatabase.open(file())){assertEquals(committed?80:0,new SqliteHouseholdBudgetRepository(c,new Object()).load(TestIdentities.REGION,FinancialTestWorld.CURRENCY).allocated());}
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void budgetWriteLocksBeforeReadingAllowanceOrCashAuthority(String journal)throws Exception{
        var ref=new AtomicReference<JdbcFaults>();
        try(var a=new PaymentTestWorld(file(),c->{var f=new JdbcFaults(c);ref.set(f);return f.connection;});var b=new PaymentTestWorld(file())){
            cash(a,100);a.sql("PRAGMA journal_mode="+journal);b.sql("PRAGMA busy_timeout=0");var checked=new AtomicBoolean();
            ref.get().beforeSql=sql->{if(sql.startsWith("SELECT b.*,h.account_id")&&checked.compareAndSet(false,true)){var busy=assertThrows(SQLException.class,()->b.sql("UPDATE finance_accounts SET balance=balance"));assertEquals(5,busy.getErrorCode());}};
            allocate(a,80,0);assertTrue(checked.get());
        }
    }
    @Test void ordinaryBudgetQueryUsesOneDeferredSnapshotAndDoesNotTakeAWriteLock()throws Exception{
        var ref=new AtomicReference<JdbcFaults>();
        try(var a=new PaymentTestWorld(file(),c->{var f=new JdbcFaults(c);ref.set(f);return f.connection;});var b=new PaymentTestWorld(file())){
            UUID target=cash(a,100);allocate(a,80,0);a.sql("PRAGMA journal_mode=WAL");var checked=new AtomicBoolean();
            ref.get().beforeSql=sql->{if(sql.startsWith("SELECT * FROM finance_accounts")&&checked.compareAndSet(false,true))b.f.finance.reserve(new CashReservation(UUID.randomUUID(),target,20,"snapshot concurrency",true));};
            assertEquals(80,a.households.budget(TestIdentities.REGION,FinancialTestWorld.CURRENCY).allocated());assertTrue(checked.get());assertEquals(20,b.f.accounts.load(target).reserved());
        }
    }
    static boolean attempt(Runnable action){try{action.run();return true;}catch(IllegalStateException expected){return false;}}
    static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
}
