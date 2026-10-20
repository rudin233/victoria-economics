package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqliteBuildingPayrollQuery;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
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
import static com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld.CURRENCY;

class FinancialTransactionSafetyTest {
    @TempDir Path dir;
    Path file(){return dir.resolve("fault.sqlite");}
    FinancialTestWorld world(AtomicReference<JdbcFaults> ref)throws Exception{return new FinancialTestWorld(file(),base->{var f=new JdbcFaults(base);ref.set(f);return f.connection;});}
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void financeWriterLockPrecedesFirstAuthoritativeAccountRead(String journal)throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref);var observer=new FinancialTestWorld(file())) {
            w.sql("PRAGMA journal_mode="+journal);observer.sql("PRAGMA busy_timeout=0");UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);
            var tested=new AtomicBoolean();ref.get().beforeSql=sql->{if(sql.startsWith("SELECT * FROM finance_accounts")&&tested.compareAndSet(false,true)){
                var busy=assertThrows(SQLException.class,()->observer.sql("UPDATE finance_accounts SET balance=balance"));assertEquals(5,busy.getErrorCode());
            }};
            w.finance.transfer(new TransferReceipt(UUID.randomUUID(),a,b,CURRENCY,10));assertTrue(tested.get());
            observer.sql("UPDATE finance_accounts SET balance=balance");
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","WAL"})
    void payrollWriterLockPrecedesFirstTimeRead(String journal)throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref);var observer=new FinancialTestWorld(file())) {
            w.sql("PRAGMA journal_mode="+journal);observer.sql("PRAGMA busy_timeout=0");UUID b=w.staffingFixture(1,7);w.enroll(b);w.acceptedTimeFixture(1200);
            var tested=new AtomicBoolean();ref.get().beforeSql=sql->{if(sql.startsWith("SELECT t.*")&&tested.compareAndSet(false,true)) {
                var busy=assertThrows(SQLException.class,()->observer.sql("UPDATE regional_wage_rates SET amount=amount"));assertEquals(5,busy.getErrorCode());
            }};
            w.accrual.recoverAcceptedTime();assertTrue(tested.get());assertEquals(State.ARREARS,w.query.state(b));
        }
    }
    @Test void rollbackFailureQuarantinesFinanceAndDerivedHandlesWithoutFinallyCommit()throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref);var observer=new FinancialTestWorld(file())) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);
            w.sql("CREATE TRIGGER second_write BEFORE UPDATE ON finance_accounts WHEN NEW.id='"+b+"' BEGIN SELECT RAISE(ABORT,'failed'); END");
            var guarded=SqliteConnectionGuard.protect(w.c);try(var cached=guarded.createStatement()) {
                var f=ref.get();f.rollbackFailure=true;f.closeFailure=true;f.commits=0;f.resets=0;
                assertThrows(CommitOutcomeUnknownException.class,()->w.finance.transfer(new TransferReceipt(UUID.randomUUID(),a,b,CURRENCY,10)));
                assertEquals(0,f.commits);assertEquals(0,f.resets);assertEquals(100,observer.accounts.load(a).balance());assertEquals(0,observer.accounts.load(b).balance());
                assertThrows(SQLException.class,()->cached.executeQuery("SELECT 1"));assertThrows(CommitOutcomeUnknownException.class,()->w.accounts.load(a));
            }
        }
    }
    @Test void rollbackFailureCannotExposeUncommittedWagesAsCurrent()throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref);var observer=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,7),p=w.enroll(b);w.acceptedTimeFixture(1200);
            w.sql("CREATE TRIGGER failed_cursor BEFORE UPDATE ON payroll_runtime BEGIN SELECT RAISE(ABORT,'failed'); END");
            var f=ref.get();f.rollbackFailure=true;f.closeFailure=true;f.commits=0;f.resets=0;
            assertThrows(CommitOutcomeUnknownException.class,w.accrual::recoverAcceptedTime);assertEquals(0,f.commits);assertEquals(0,f.resets);
            assertEquals(0,observer.payroll.load(p).accrued());assertEquals(State.RECOVERY,w.query.state(b));assertEquals(State.RECOVERY,observer.query.state(b));
        }
    }
    @Test void failedCleanupAfterDurableTransferRequiresFreshTrustedVerification()throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref)) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);var cmd=new TransferReceipt(UUID.randomUUID(),a,b,CURRENCY,10);
            ref.get().resetFailure=true;assertThrows(CommitOutcomeUnknownException.class,()->w.finance.transfer(cmd));
            assertThrows(CommitOutcomeUnknownException.class,()->w.accounts.verify(cmd));
            try(var c=SqliteEconomyDatabase.open(file())){var repo=new SqliteFinanceAccountRepository(c,new Object());assertEquals(Optional.of(cmd),repo.verify(cmd));assertEquals(90,repo.load(a).balance());}
        }
    }
    @Test void failedSavepointCannotBeCaughtToPublishOuterFinanceCommit()throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref)) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);var cmd=new TransferReceipt(UUID.randomUUID(),a,b,CURRENCY,10);ref.get().releaseFailure=true;
            assertThrows(CommitOutcomeUnknownException.class,()->SqliteTransactions.write(w.c,tx->{
                SqliteTransactions.run(w.c,()->w.accounts.inTransaction(tx).transfer(cmd));return null;
            }));
            try(var c=SqliteEconomyDatabase.open(file())){var repo=new SqliteFinanceAccountRepository(c,new Object());assertEquals(100,repo.load(a).balance());assertTrue(repo.verify(cmd).isEmpty());}
        }
    }
    @Test void swallowedRepositoryFailureStillPoisonsWholeFinanceSession()throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);var cmd=new TransferReceipt(UUID.randomUUID(),a,b,CURRENCY,10);
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->{var session=w.accounts.inTransaction(tx);session.transfer(cmd);
                try{session.reserve(new CashReservation(UUID.randomUUID(),a,100,"unavailable cash",true));}catch(IllegalStateException ignored){}return null;
            }));assertEquals(100,w.accounts.load(a).balance());assertTrue(w.accounts.verify(cmd).isEmpty());
        }
    }
    @Test void unknownPayrollCleanupIsRecoveryUntilFreshStateCanBeRead()throws Exception {
        var ref=new AtomicReference<JdbcFaults>();
        try(var w=world(ref)) {
            UUID b=w.staffingFixture(2,7);w.enroll(b);w.acceptedTimeFixture(1200);ref.get().resetFailure=true;
            assertThrows(CommitOutcomeUnknownException.class,w.accrual::recoverAcceptedTime);assertEquals(State.RECOVERY,w.query.state(b));
            try(var c=SqliteEconomyDatabase.open(file())){assertEquals(State.ARREARS,new SqliteBuildingPayrollQuery(c,new Object()).state(b));}
        }
    }
}
