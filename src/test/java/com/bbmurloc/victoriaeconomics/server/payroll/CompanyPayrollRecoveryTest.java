package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.financial.JdbcFaults;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.payroll.application.CompanyPayrollService;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollPaymentRun;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollPaymentRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyPayrollRecoveryTest {
    @TempDir Path dir;Path file(){return dir.resolve("recovery.sqlite");}
    PaymentTestWorld world(AtomicReference<JdbcFaults> ref)throws Exception{return new PaymentTestWorld(file(),c->{var f=new JdbcFaults(c);ref.set(f);return f.connection;});}
    UUID[] setup(PaymentTestWorld w)throws Exception{
        w.building(1,1);w.building(1,1);UUID source=w.companyAccount(),target=w.recipient();w.fund(source,2);w.mature(1);w.payments.preparePeriod(1);return new UUID[]{source,target};
    }
    String trigger(String point,UUID target){return switch(point){
        case "secondTransfer"->"BEFORE UPDATE ON finance_accounts WHEN NEW.id='"+target+"' AND (SELECT COUNT(*) FROM payroll_payment_lines)=1";
        case "secondLiability"->"BEFORE UPDATE ON payroll_liabilities WHEN (SELECT COUNT(*) FROM payroll_payment_lines)=2";
        case "secondAccrual"->"BEFORE UPDATE ON payroll_accruals WHEN NEW.paid>OLD.paid AND (SELECT COUNT(*) FROM payroll_payment_lines)=2";
        case "secondLedger"->"BEFORE INSERT ON finance_ledger WHEN NEW.line=1 AND (SELECT COUNT(*) FROM payroll_payment_lines)=1";
        case "secondLine"->"BEFORE INSERT ON payroll_payment_lines WHEN NEW.line=1";
        case "partition"->"BEFORE INSERT ON payroll_payment_partitions";
        default->"BEFORE UPDATE ON payroll_payment_runs WHEN NEW.status='COMPLETED'";
    };}
    @ParameterizedTest @ValueSource(strings={"secondTransfer","secondLiability","secondAccrual","secondLedger","secondLine","partition","parent"})
    void anyPostingWriteFailureRollsBackCashDebtAndAllChildrenWhileKeepingIntent(String point)throws Exception{
        try(var w=new PaymentTestWorld(file())){
            var ids=setup(w);w.sql("CREATE TRIGGER failure "+trigger(point,ids[1])+" BEGIN SELECT RAISE(ABORT,'payment failure'); END");
            assertThrows(IllegalStateException.class,()->w.payments.pay(w.command(1)));assertEquals(2,w.f.accounts.load(ids[0]).balance());assertEquals(0,w.f.accounts.load(ids[1]).balance());
            assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_liabilities"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));
            assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_lines"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_partitions"));assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs WHERE status='PENDING'"));assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_failures"));
            w.sql("DROP TRIGGER failure");w.periods.recoverAcceptedTime();assertEquals(0,w.f.accounts.load(ids[0]).balance());assertEquals(2,w.f.accounts.load(ids[1]).balance());assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_failures"));assertTrue(w.payments.verify(w.command(1)).isPresent());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownPostingCommitRetiresTheConnectionAndFreshVerificationNeverDoublePays(boolean committed)throws Exception{
        var ref=new AtomicReference<JdbcFaults>();UUID[] ids;
        try(var w=world(ref)){
            ids=setup(w);var cached=SqliteConnectionGuard.protect(w.c).createStatement();
            ref.get().beforeSql=sql->{if(sql.startsWith("UPDATE payroll_payment_runs SET status")){ref.get().beforeCommit=!committed;ref.get().afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->w.payments.pay(w.command(1)));assertThrows(CommitOutcomeUnknownException.class,()->w.payments.pay(w.command(1)));assertThrows(SQLException.class,()->cached.executeQuery("SELECT 1"));cached.close();
            try(var fresh=SqliteEconomyDatabase.open(file())){
                var observed=new CompanyPayrollService(fresh,new Object()).verify(w.command(1)).orElseThrow();assertEquals(committed?PayrollPaymentRun.Status.COMPLETED:PayrollPaymentRun.Status.PENDING,observed.status());
                var accounts=new SqliteFinanceAccountRepository(fresh,new Object());assertEquals(committed?0:2,accounts.load(ids[0]).balance());assertEquals(committed?2:0,accounts.load(ids[1]).balance());
            }
        }
        try(var w=new PaymentTestWorld(file())){w.periods.recoverAcceptedTime();assertEquals(2,w.f.accounts.load(ids[1]).balance());assertEquals(2,w.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));}
    }
    @Test void unknownCleanupAfterCommitHasVerifiablePaidWagesAndNoRepeatDebit()throws Exception{
        var ref=new AtomicReference<JdbcFaults>();UUID[] ids;
        try(var w=world(ref)){
            ids=setup(w);ref.get().beforeSql=sql->{if(sql.startsWith("UPDATE payroll_payment_runs SET status"))ref.get().resetFailure=true;};
            assertThrows(CommitOutcomeUnknownException.class,()->w.payments.pay(w.command(1)));assertThrows(CommitOutcomeUnknownException.class,()->w.payments.verify(w.command(1)));
        }
        try(var w=new PaymentTestWorld(file())){assertEquals(PayrollPaymentRun.Status.COMPLETED,w.payments.verify(w.command(1)).orElseThrow().status());w.periods.recoverAcceptedTime();assertEquals(2,w.f.accounts.load(ids[1]).balance());}
    }
    @Test void rollbackAndCloseFailuresCannotCommitCashOrDebtFromFinally()throws Exception{
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref);var observer=new PaymentTestWorld(file())){
            var ids=setup(w);w.sql("CREATE TRIGGER failure "+trigger("secondLiability",ids[1])+" BEGIN SELECT RAISE(ABORT,'payment failure'); END");
            var cached=SqliteConnectionGuard.protect(w.c).createStatement();var f=ref.get();f.rollbackFailure=true;f.closeFailure=true;f.commits=0;f.resets=0;
            assertThrows(CommitOutcomeUnknownException.class,()->w.payments.pay(w.command(1)));assertEquals(0,f.commits);assertEquals(0,f.resets);
            assertEquals(2,observer.f.accounts.load(ids[0]).balance());assertEquals(0,observer.f.accounts.load(ids[1]).balance());assertEquals(0,observer.scalar("SELECT SUM(paid) FROM payroll_accruals"));assertEquals(1,observer.scalar("SELECT COUNT(*) FROM payroll_payment_runs WHERE status='PENDING'"));
            assertThrows(SQLException.class,()->cached.executeQuery("SELECT 1"));cached.close();
        }
        try(var raw=DriverManager.getConnection("jdbc:sqlite:"+file());var s=raw.createStatement()){s.execute("DROP TRIGGER failure");}
        try(var w=new PaymentTestWorld(file())){w.periods.recoverAcceptedTime();assertEquals(2,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));}
    }
    @Test void swallowedPostingFailureStillPoisonsTheOuterWriteSession()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            var ids=setup(w);w.sql("CREATE TRIGGER failure "+trigger("secondLiability",ids[1])+" BEGIN SELECT RAISE(ABORT,'payment failure'); END");
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->{
                try{new SqlitePayrollPaymentRepository(w.c,w.f.world.lock).inTransaction(tx).complete(w.command(1));}catch(IllegalStateException ignored){}return null;
            }));assertEquals(2,w.f.accounts.load(ids[0]).balance());assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_lines"));
        }
    }
    @Test void failedSavepointReleaseCannotPublishAnOuterPayment()throws Exception{
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref)){
            var ids=setup(w);ref.get().releaseFailure=true;
            assertThrows(CommitOutcomeUnknownException.class,()->SqliteTransactions.write(w.c,tx->SqliteTransactions.run(w.c,()->new SqlitePayrollPaymentRepository(w.c,w.f.world.lock).inTransaction(tx).complete(w.command(1)))));
            try(var fresh=SqliteEconomyDatabase.open(file())){assertEquals(2,new SqliteFinanceAccountRepository(fresh,new Object()).load(ids[0]).balance());assertEquals(PayrollPaymentRun.Status.PENDING,new CompanyPayrollService(fresh,new Object()).verify(w.command(1)).orElseThrow().status());}
        }
    }
    @Test void paymentCycleCursorFailureRetriesOnlyConfirmationAndBlocksNextTickAcceptance()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            var ids=setup(w);w.sql("CREATE TRIGGER failure BEFORE UPDATE ON payroll_payment_runtime BEGIN SELECT RAISE(ABORT,'cycle failure'); END");
            assertThrows(IllegalStateException.class,w.periods::recoverAcceptedTime);assertEquals(0,w.payments.processedPeriod());assertEquals(2,w.f.accounts.load(ids[1]).balance());assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs WHERE status='COMPLETED'"));
            assertThrows(IllegalStateException.class,()->w.f.time.acceptNext(1200));w.sql("DROP TRIGGER failure");w.periods.recoverAcceptedTime();assertEquals(1,w.payments.processedPeriod());assertEquals(2,w.f.accounts.load(ids[1]).balance());assertEquals(1201,w.f.time.acceptNext(1200).acceptedTick());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownCycleAcknowledgementRecoversItsCursorWithoutRepeatingConfirmedTransfers(boolean acknowledged)throws Exception{
        var ref=new AtomicReference<JdbcFaults>();UUID[] ids;
        try(var w=world(ref)){
            ids=setup(w);w.payments.pay(w.command(1));
            ref.get().beforeSql=sql->{if(sql.startsWith("UPDATE payroll_payment_runtime")){ref.get().beforeCommit=!acknowledged;ref.get().afterCommit=acknowledged;}};
            assertThrows(CommitOutcomeUnknownException.class,w.periods::recoverAcceptedTime);
            try(var fresh=SqliteEconomyDatabase.open(file())){
                var payments=new CompanyPayrollService(fresh,new Object());assertEquals(acknowledged?1:0,payments.processedPeriod());assertEquals(PayrollPaymentRun.Status.COMPLETED,payments.verify(w.command(1)).orElseThrow().status());
            }
        }
        try(var w=new PaymentTestWorld(file())){w.periods.recoverAcceptedTime();assertEquals(1,w.payments.processedPeriod());assertEquals(2,w.f.accounts.load(ids[1]).balance());assertEquals(2,w.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));}
    }
    @Test void failedPeriodIntentCreationLeavesAcceptedTimeUnacknowledgedAndCreatesNoOrphanRun()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,1);UUID source=w.companyAccount();w.recipient();w.fund(source,1);w.mature(1);
            w.sql("CREATE TRIGGER failure BEFORE INSERT ON payroll_payment_runs BEGIN SELECT RAISE(ABORT,'intent failure'); END");assertThrows(IllegalStateException.class,()->w.payments.preparePeriod(1));
            assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_cycles"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));assertThrows(IllegalStateException.class,()->w.f.time.acceptNext(1200));assertEquals(1,w.f.accounts.load(source).balance());
            w.sql("DROP TRIGGER failure");w.periods.recoverAcceptedTime();assertEquals(1,w.payments.processedPeriod());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownIntentCommitIsVerifiedAsAbsentOrWholeBeforeAnyCashPosting(boolean committed)throws Exception{
        var ref=new AtomicReference<JdbcFaults>();UUID source,target;
        try(var w=world(ref)){
            w.building(1,1);source=w.companyAccount();target=w.recipient();w.fund(source,1);w.mature(1);
            ref.get().beforeSql=sql->{if(sql.startsWith("INSERT INTO payroll_payment_runs")){ref.get().beforeCommit=!committed;ref.get().afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->w.payments.preparePeriod(1));
            try(var fresh=SqliteEconomyDatabase.open(file())){
                var payments=new CompanyPayrollService(fresh,new Object());assertEquals(committed,payments.verify(w.command(1)).isPresent());var accounts=new SqliteFinanceAccountRepository(fresh,new Object());assertEquals(1,accounts.load(source).balance());assertEquals(0,accounts.load(target).balance());
            }
        }
        try(var w=new PaymentTestWorld(file())){w.periods.recoverAcceptedTime();assertEquals(1,w.f.accounts.load(target).balance());assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));assertEquals(1,w.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));}
    }
}
