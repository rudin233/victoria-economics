package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.financial.*;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
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

class PaymentConcurrencyTest {
    @TempDir Path dir;Path file(){return dir.resolve("concurrent.sqlite");}
    @Test void independentConnectionsCommitTheSameCompanyPeriodOnlyOnce()throws Exception{
        try(var a=new PaymentTestWorld(file())){
            a.building(1,5);UUID source=a.companyAccount(),target=a.recipient();a.fund(source,5);a.mature(1);a.payments.preparePeriod(1);
            try(var b=new PaymentTestWorld(file());var pool=Executors.newFixedThreadPool(2)){
                var latch=new CountDownLatch(1);var x=pool.submit(()->{await(latch);return a.payments.pay(a.command(1));});var y=pool.submit(()->{await(latch);return b.payments.pay(b.command(1));});latch.countDown();
                assertEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));assertEquals(5,a.f.accounts.load(target).balance());assertEquals(0,a.f.accounts.load(source).balance());assertEquals(1,a.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));assertTrue(b.payments.verify(b.command(1)).isPresent());
            }
        }
    }
    @Test void competingPeriodCoordinatorsCannotRepeatRunsOrRewindTheCursor()throws Exception{
        try(var a=new PaymentTestWorld(file())){
            a.building(1,5);a.companyAccount();a.recipient();a.mature(1);
            try(var b=new PaymentTestWorld(file());var pool=Executors.newFixedThreadPool(2)){
                var latch=new CountDownLatch(1);var x=pool.submit(()->{await(latch);a.periods.recoverAcceptedTime();});var y=pool.submit(()->{await(latch);b.periods.recoverAcceptedTime();});latch.countDown();x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);
                assertEquals(1,a.payments.processedPeriod());assertEquals(1,a.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));assertEquals(5,a.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void paymentOwnsWritePermissionBeforeReadingParentCashOrDebt(String journal)throws Exception{
        var ref=new AtomicReference<JdbcFaults>();
        try(var a=new PaymentTestWorld(file(),c->{var f=new JdbcFaults(c);ref.set(f);return f.connection;});var b=new PaymentTestWorld(file())){
            a.building(1,1);UUID source=a.companyAccount();a.recipient();a.fund(source,1);a.mature(1);a.payments.preparePeriod(1);a.sql("PRAGMA journal_mode="+journal);b.sql("PRAGMA busy_timeout=0");
            var tested=new AtomicBoolean();ref.get().beforeSql=sql->{if(sql.startsWith("SELECT * FROM payroll_payment_runs")&&tested.compareAndSet(false,true)){
                var busy=assertThrows(SQLException.class,()->b.sql("UPDATE finance_accounts SET balance=balance"));assertEquals(5,busy.getErrorCode());
            }};
            a.payments.pay(a.command(1));assertTrue(tested.get());assertEquals(0,a.f.accounts.load(source).balance());
        }
    }
    @Test void oneCompanyFailureCannotEraseAnotherCompanysConfirmedRun()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,1);var company=new CompanyId(new UUID(101,1));w.building(UUID.randomUUID(),company,TestIdentities.REGION,FinancialTestWorld.CURRENCY,1,1);UUID second=w.companyAccount(company,FinancialTestWorld.CURRENCY),target=w.recipient();w.fund(second,1);w.mature(1);
            w.periods.recoverAcceptedTime();assertEquals(1,w.payments.processedPeriod());assertEquals(1,w.f.accounts.load(target).balance());assertEquals(PayrollPaymentRun.Status.COMPLETED,w.payments.verify(PayrollPaymentRun.Command.forPeriod(company,1)).orElseThrow().status());
            assertEquals(PayrollPaymentRun.Status.BLOCKED,w.payments.verify(w.command(1)).orElseThrow().status());
            assertEquals(1201,w.f.time.acceptNext(1200).acceptedTick());w.f.accrual.recoverAcceptedTime();
            w.companyAccount();w.payments.pay(w.command(1));assertEquals(1,w.payments.processedPeriod());assertEquals(1,w.f.accounts.load(target).balance());assertEquals(1,w.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));
        }
    }
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
}
