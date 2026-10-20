package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.application.FinanceService;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld.CURRENCY;

class FinancePersistenceTest {
    @TempDir Path dir;
    Path file() { return dir.resolve("finance.sqlite"); }
    TransferReceipt command(UUID source, UUID target, long amount) { return new TransferReceipt(UUID.randomUUID(), source, target, CURRENCY, amount); }
    @Test void zeroOpeningRequiresRealOwnerAndCurrencyAndHasLedger() throws Exception {
        try (var w = new FinancialTestWorld(file())) {
            UUID id = w.companyAccount(); var a = w.accounts.load(id);
            assertEquals(0, a.balance()); assertEquals(0, a.available()); assertEquals(1, w.accounts.ledger(id).size());
            assertEquals(a, w.finance.open(id, a.ownerKind(), a.ownerId(), CURRENCY));
            assertThrows(RuntimeException.class, () -> w.finance.open(UUID.randomUUID(), a.ownerKind(), UUID.randomUUID(), CURRENCY));
            assertThrows(RuntimeException.class, () -> w.finance.open(UUID.randomUUID(), a.ownerKind(), a.ownerId(), new CurrencyId(UUID.randomUUID())));
            assertEquals(1, w.scalar("SELECT COUNT(*) FROM finance_accounts"));
            assertThrows(IllegalArgumentException.class, () -> CurrencyId.parse("700-1"));
        }
    }
    @Test void transferAndLedgerConserveCashAndRestartRetainsExactLong() throws Exception {
        UUID source, target;
        try (var w = new FinancialTestWorld(file())) {
            source = w.companyAccount(); target = w.regionAccount(); w.fundingFixture(source, 9007199254740993L);
            var cmd = command(source, target, 9007199254740000L); assertEquals(cmd, w.finance.transfer(cmd));
            assertEquals(993, w.accounts.load(source).balance()); assertEquals(cmd.amount(), w.accounts.load(target).balance());
            assertEquals(0, w.scalar("SELECT SUM(delta) FROM finance_ledger WHERE event_id='transfer:" + cmd.id() + "'"));
        }
        try (var c = SqliteEconomyDatabase.open(file())) {
            var repo = new SqliteFinanceAccountRepository(c, new Object());
            assertEquals(9007199254740993L, Math.addExact(repo.load(source).balance(), repo.load(target).balance()));
        }
    }
    @Test void insufficientAndReservedCashCannotBeTransferredAndReleaseDoesNotReactivate() throws Exception {
        try (var w = new FinancialTestWorld(file())) {
            UUID a = w.companyAccount(), b = w.regionAccount(); w.fundingFixture(a, 100);
            var reservation = new CashReservation(UUID.randomUUID(), a, 80, "market order fixture", true);
            assertEquals(reservation, w.finance.reserve(reservation)); assertEquals(reservation, w.finance.reserve(reservation));
            assertEquals(20, w.accounts.load(a).available());
            assertThrows(IllegalStateException.class, () -> w.finance.transfer(command(a,b,21)));
            assertThrows(IllegalStateException.class, () -> w.finance.reserve(new CashReservation(UUID.randomUUID(), a,21,"other",true)));
            assertThrows(IllegalArgumentException.class, () -> w.finance.reserve(new CashReservation(reservation.id(),a,79,"market order fixture",true)));
            assertThrows(IllegalArgumentException.class, () -> w.finance.release(reservation.id(),b));
            w.finance.transfer(command(a,b,20)); w.finance.release(reservation.id(),a); w.finance.release(reservation.id(),a);
            assertFalse(w.finance.reserve(reservation).active()); assertEquals(80,w.accounts.load(a).available());
            assertThrows(IllegalStateException.class, () -> w.finance.transfer(command(a,b,81)));
        }
    }
    @Test void completeTransferCommandIsIdempotentAndConflictingKeyIsRejected() throws Exception {
        try (var w = new FinancialTestWorld(file())) {
            UUID a = w.companyAccount(), b = w.regionAccount(); w.fundingFixture(a,100);
            var cmd = command(a,b,60); w.finance.transfer(cmd); w.finance.transfer(cmd);
            assertEquals(40,w.accounts.load(a).balance()); assertEquals(Optional.of(cmd),w.accounts.verify(cmd));
            assertThrows(IllegalArgumentException.class, () -> w.finance.transfer(new TransferReceipt(cmd.id(),a,b,CURRENCY,59)));
            assertThrows(IllegalArgumentException.class, () -> w.accounts.verify(new TransferReceipt(cmd.id(),b,a,CURRENCY,60)));
            assertEquals(1,w.scalar("SELECT COUNT(*) FROM finance_events WHERE kind='TRANSFER'"));
        }
    }
    @Test void currenciesCannotBeMixedAndNoCurrencyIsImplicit() throws Exception {
        try (var w = new FinancialTestWorld(file())) {
            UUID a=w.companyAccount(); w.fundingFixture(a,100);
            var other=new CurrencyId(UUID.randomUUID()); w.finance.registerCurrency(other,"Other explicit currency");
            UUID b=UUID.randomUUID(); w.finance.open(b,FinanceAccount.OwnerKind.REGION,TestIdentities.REGION.value(),other);
            assertThrows(IllegalArgumentException.class,()->w.finance.transfer(command(a,b,1)));
            assertThrows(RuntimeException.class,()->w.finance.registerCurrency(CURRENCY,"duplicate"));
            assertEquals(100,w.accounts.load(a).balance()); assertEquals(0,w.accounts.load(b).balance());
        }
    }
    @ParameterizedTest @ValueSource(strings={"secondAccount","secondLedger","event"})
    void anyTransferWriteFailureRollsBackBothBalancesAndReceipts(String point) throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID a=w.companyAccount(),b=w.regionAccount(); w.fundingFixture(a,100);
            String trigger=switch(point) {
                case "secondAccount"->"BEFORE UPDATE ON finance_accounts WHEN NEW.id='"+b+"'";
                case "secondLedger"->"BEFORE INSERT ON finance_ledger WHEN NEW.line=1";
                default->"BEFORE INSERT ON finance_events WHEN NEW.kind='TRANSFER'";
            };
            w.sql("CREATE TRIGGER failure "+trigger+" BEGIN SELECT RAISE(ABORT,'injected'); END");
            var cmd=command(a,b,40); assertThrows(RuntimeException.class,()->w.finance.transfer(cmd));
            assertEquals(100,w.accounts.load(a).balance()); assertEquals(0,w.accounts.load(b).balance()); assertTrue(w.accounts.verify(cmd).isEmpty());
            assertEquals(0,w.scalar("SELECT COUNT(*) FROM finance_ledger WHERE event_id='transfer:"+cmd.id()+"'"));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownCommitRequiresDurableVerificationAndNeverRetriesAutomatically(boolean committed) throws Exception {
        var faults=new AtomicReference<JdbcFaults>(); TransferReceipt cmd;
        try(var w=new FinancialTestWorld(file(),base->{var f=new JdbcFaults(base);faults.set(f);return f.connection;})) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);cmd=command(a,b,40);
            var f=faults.get();f.commits=0;f.beforeCommit=!committed;f.afterCommit=committed;
            assertThrows(CommitOutcomeUnknownException.class,()->w.finance.transfer(cmd));assertEquals(1,f.commits);
            // A distinct, newly trusted connection is the verification source.
            try(var c=SqliteEconomyDatabase.open(file())) {
                var repo=new SqliteFinanceAccountRepository(c,new Object());
                assertEquals(committed,repo.verify(cmd).isPresent());assertEquals(committed?60:100,repo.load(a).balance());
                if(committed) new FinanceService(c,new Object()).transfer(cmd);
                assertEquals(committed?60:100,repo.load(a).balance());
            }
        }
    }
    @Test void twoConnectionsCannotOverspendSameBalance() throws Exception {
        try(var a=new FinancialTestWorld(file());var b=new FinancialTestWorld(file())) {
            UUID source=a.companyAccount(),target=a.regionAccount();a.fundingFixture(source,100);
            var start=new CountDownLatch(1);var successes=new AtomicInteger();
            try(var pool=Executors.newFixedThreadPool(2)) {
                var futures=List.of(pool.submit(()->attempt(a,source,target,start,successes)),pool.submit(()->attempt(b,source,target,start,successes)));
                start.countDown();for(var f:futures)f.get(15,TimeUnit.SECONDS);
            }
            assertEquals(1,successes.get());assertEquals(20,a.accounts.load(source).balance());assertEquals(80,b.accounts.load(target).balance());
        }
    }
    private void attempt(FinancialTestWorld w,UUID a,UUID b,CountDownLatch latch,AtomicInteger success) {
        try { latch.await();w.finance.transfer(command(a,b,80));success.incrementAndGet(); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("Insufficient")); }
        catch(InterruptedException e) { throw new RuntimeException(e); }
    }
    @Test void destinationOverflowAndRevisionOverflowCannotDeductSource() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,1);w.fundingFixture(b,Long.MAX_VALUE);
            assertThrows(ArithmeticException.class,()->w.finance.transfer(command(a,b,1)));assertEquals(1,w.accounts.load(a).balance());
            w.sql("UPDATE finance_accounts SET revision=9223372036854775807 WHERE id='"+a+"'");
            assertThrows(ArithmeticException.class,()->w.finance.reserve(new CashReservation(UUID.randomUUID(),a,1,"lock",true)));
            assertEquals(0,w.accounts.load(a).reserved());
        }
    }
    @Test void invalidAmountsAndIdentityCannotEnterDomain() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->command(a,b,0));assertThrows(IllegalArgumentException.class,()->command(a,b,-1));
        assertThrows(IllegalArgumentException.class,()->command(a,a,1));
        assertThrows(IllegalArgumentException.class,()->new CashReservation(UUID.randomUUID(),a,0,"x",true));
        assertThrows(IllegalArgumentException.class,()->new FinanceAccount(a,FinanceAccount.OwnerKind.COMPANY,b,CURRENCY,1,2,0));
    }
    @Test void unresolvedTransactionAndExpiredSessionRejectAuthorityReadsAndWrites() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID a=w.companyAccount();var saved=new AtomicReference<SqliteFinanceAccountRepository.Session>();
            SqliteTransactions.write(w.c,tx->{saved.set(w.accounts.inTransaction(tx));return null;});
            assertThrows(IllegalStateException.class,()->saved.get().load(a));
            w.c.setAutoCommit(false);
            try { assertThrows(CommitOutcomeUnknownException.class,()->w.accounts.load(a));assertThrows(CommitOutcomeUnknownException.class,()->w.finance.reserve(new CashReservation(UUID.randomUUID(),a,1,"x",true))); }
            finally {w.c.rollback();w.c.setAutoCommit(true);}
        }
    }
}
