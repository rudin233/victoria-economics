package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld.CURRENCY;

class PayrollPersistenceTest {
    @TempDir Path dir;
    Path file() { return dir.resolve("payroll.sqlite"); }
    @Test void missingAuthorityRateOrEnrollmentNeverMeansCurrentAndEffectiveHiringStaysClosed() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.world.create().getId();
            assertEquals(State.UNAVAILABLE,w.query.state(b));
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->w.payroll.inTransaction(tx).enroll(UUID.randomUUID(),b,CURRENCY)));
            w.sql("UPDATE payroll_runtime SET authority='BUILDING_STAFFING'");
            assertThrows(IllegalStateException.class,()->w.enroll(b));
            assertEquals(State.UNAVAILABLE,w.query.state(b));
            assertThrows(IllegalStateException.class,()->w.world.commands.applyEffectiveChanges(b));
            assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_accruals"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM employment_state"));
        }
    }
    @Test void realFactsDistinguishNotDueCurrentArrearsRecoveryAndUnavailable() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,1200),p=w.enroll(b);
            assertEquals(State.CURRENT,w.query.state(b));
            w.acceptedTimeFixture(1199);assertEquals(State.RECOVERY,w.query.state(b));w.accrual.recoverAcceptedTime();
            assertEquals(1199,w.payroll.load(p).unmatured());assertEquals(0,w.payroll.load(p).unpaid());assertEquals(State.CURRENT,w.query.state(b));
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();
            assertEquals(1200,w.payroll.load(p).unpaid());assertEquals(State.ARREARS,w.query.state(b));
            assertEquals(0,w.payroll.load(p).paid());assertEquals(0,w.scalar("SELECT COUNT(*) FROM finance_accounts"));
            assertEquals(State.UNAVAILABLE,w.query.state(b,new CompanyId(UUID.randomUUID())));
            assertEquals(State.UNAVAILABLE,w.query.state(UUID.randomUUID()));
        }
    }
    @Test void remainderAndLiabilityCursorSurviveRepeatedRestartsWithoutLossOrDuplicatePeriods() throws Exception {
        UUID building,segment;
        try(var w=new FinancialTestWorld(file())) {
            building=w.staffingFixture(3,1);segment=w.enroll(building);w.acceptedTimeFixture(333);w.accrual.recoverAcceptedTime();
            assertEquals(999,w.payroll.load(segment).remainder());
        }
        try(var c=SqliteEconomyDatabase.open(file())) {
            var repo=new SqlitePayrollRepository(c,new Object());assertEquals(999,repo.load(segment).remainder());
        }
        try(var w=new FinancialTestWorld(file())) {
            w.acceptedTimeFixture(1201);w.accrual.recoverAcceptedTime();w.accrual.recoverAcceptedTime();
            var p=w.payroll.load(segment);assertEquals(3,p.accrued());assertEquals(3,p.remainder());assertEquals(3,p.matured());
            assertEquals(List.of(new PayrollLiability(segment,1,3,0)),w.payroll.liabilities(segment));
        }
        try(var c=SqliteEconomyDatabase.open(file())) { assertEquals(State.ARREARS,new SqliteBuildingPayrollQuery(c,new Object()).state(building)); }
    }
    @Test void employeeChangesShareTransactionWithOldHeadcountAccrual() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,1200),p=w.enroll(b);w.acceptedTimeFixture(100);w.changeEmployeesFixture(p,3);
            assertEquals(100,w.payroll.load(p).accrued());assertEquals(3,w.payroll.load(p).employees());
            w.acceptedTimeFixture(200);w.changeEmployeesFixture(p,0);assertEquals(400,w.payroll.load(p).accrued());
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();assertEquals(400,w.payroll.load(p).matured());
            assertTrue(w.world.staffing.load(b).employed().isEmpty());assertEquals(0,w.scalar("SELECT COUNT(*) FROM employment_state"));
        }
    }
    @Test void employeeWriteFailureRollsBackOldAccrualAndHeadcountTogether() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,1200),p=w.enroll(b);w.acceptedTimeFixture(100);
            w.sql("CREATE TRIGGER fail_staff BEFORE UPDATE ON building_staffing BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(RuntimeException.class,()->w.changeEmployeesFixture(p,3));
            assertEquals(0,w.payroll.load(p).lastAccruedTick());assertEquals(1,w.payroll.load(p).employees());assertEquals(1,w.world.staffing.load(b).employed().get("laborer"));
        }
    }
    @Test void rateChangesAccrueOldIntervalAtomicallyAndNeverRepriceHistory() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,1),p=w.enroll(b);w.acceptedTimeFixture(600);
            w.accrual.configureRate(new RegionalWageRate(TestIdentities.REGION,CURRENCY,600,3,1));
            assertEquals(1,w.payroll.load(p).accrued());assertEquals(600,w.payroll.load(p).lastAccruedTick());
            assertThrows(IllegalArgumentException.class,()->w.accrual.configureRate(new RegionalWageRate(TestIdentities.REGION,CURRENCY,599,100,2)));
            assertThrows(IllegalStateException.class,()->w.accrual.configureRate(new RegionalWageRate(TestIdentities.REGION,CURRENCY,600,100,2)));
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();assertEquals(4,w.payroll.load(p).matured());
        }
    }
    @Test void futureRateAndExplicitZeroAreDifferentFromMissingConfiguration() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,0),p=w.enroll(b);
            w.accrual.configureRate(new RegionalWageRate(TestIdentities.REGION,CURRENCY,600,2,1));
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();assertEquals(1,w.payroll.load(p).matured());
        }
    }
    @ParameterizedTest @ValueSource(strings={"STOPPED","DISABLED","PAUSED","SETTLING_ABORTED","OVER_CAPACITY"})
    void retainedEmployeesAccrueRegardlessOfProductionControlBatchOrInventory(String control) throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,1200),p=w.enroll(b);
            w.sql("UPDATE production_executions SET automatic=0 WHERE building_id='"+b+"'");
            if(control.equals("STOPPED")||control.equals("DISABLED"))w.sql("UPDATE economic_buildings SET status='"+control+"' WHERE id='"+b+"'");
            if(control.equals("PAUSED")||control.equals("SETTLING_ABORTED")) {
                var recipe=new ResolvedProductionRecipe(Map.of("wood",1L),Map.of("tools",1L),Map.of("laborer",1));
                var snapshot=new ProductionBatchConfiguration(Map.of("base","test"),recipe,new WorkforcePlan(Map.of("laborer",List.of(UUID.randomUUID())),1,1));
                UUID batch=UUID.randomUUID();
                try(var s=w.c.prepareStatement("INSERT INTO production_batches VALUES(?,?,?,?,?)")) {
                    s.setString(1,batch.toString());s.setString(2,b.toString());s.setString(3,EconomyStateCodec.encodeBatchConfiguration(snapshot));s.setLong(4,20);s.setString(5,control);s.executeUpdate();
                }
                w.sql("UPDATE production_executions SET current_batch_id='"+batch+"' WHERE building_id='"+b+"'");
            }
            if(control.equals("OVER_CAPACITY")) {
                var stock=new SqliteInventoryStore(w.c,w.world.lock);stock.createLocation(b,1);stock.deposit(b,Map.of("wood",1L));
                UUID batch=UUID.randomUUID();stock.reserve(b,batch,Map.of("wood",1L));
                stock.settle(b,new ProductionSettlement(batch,ProductionSettlement.Outcome.COMPLETED,Map.of("wood",1L),Map.of("tools",2L)));
                assertTrue(stock.storageStatus(b).overCapacity());
            }
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();assertEquals(2400,w.payroll.load(p).unpaid());
            assertEquals(State.ARREARS,w.query.state(b));assertEquals(0,w.payroll.load(p).paid());
        }
    }
    @Test void historicalDebtAndFractionsNeverMoveToNewCompany() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,1),old=w.enroll(b);w.acceptedTimeFixture(1801);
            SqliteTransactions.write(w.c,tx->{w.payroll.inTransaction(tx).close(old,1801);return null;});
            var buyer=new CompanyId(UUID.randomUUID());w.world.companies.create(new Company(buyer));
            // Isolated persistence fixture, NOT a sale service or formal owner mutation entry point.
            w.sql("UPDATE economic_buildings SET owner_company_id='"+buyer+"',identity_revision=identity_revision+1 WHERE id='"+b+"'");
            UUID next=w.enroll(b);w.accrual.recoverAcceptedTime();
            assertEquals(State.CURRENT,w.query.state(b,buyer));assertEquals(State.UNAVAILABLE,w.query.state(b,TestIdentities.COMPANY));
            assertEquals(1,w.payroll.load(old).unpaid());assertEquals(601,w.payroll.load(old).remainder());
            w.acceptedTimeFixture(2400);w.accrual.recoverAcceptedTime();
            assertEquals(601,w.payroll.load(old).remainder());assertEquals(599,w.payroll.load(next).remainder());
            assertEquals(TestIdentities.COMPANY,w.payroll.load(old).company());assertEquals(buyer,w.payroll.load(next).company());
        }
        try(var ignored=SqliteEconomyDatabase.open(file())) { /* historical graph passes read-only consistency gate */ }
    }
    @ParameterizedTest @ValueSource(strings={"accrual","liability","cursor"})
    void anyAccrualLiabilityOrCursorWriteFailureRollsBackEntirePeriod(String table) throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,7),p=w.enroll(b);w.acceptedTimeFixture(1200);
            String trigger=switch(table) {case "accrual"->"BEFORE UPDATE ON payroll_accruals";case "liability"->"BEFORE INSERT ON payroll_liabilities";default->"BEFORE UPDATE ON payroll_runtime";};
            w.sql("CREATE TRIGGER failure "+trigger+" BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(RuntimeException.class,w.accrual::recoverAcceptedTime);
            assertEquals(0,w.payroll.load(p).lastAccruedTick());assertEquals(0,w.payroll.load(p).accrued());assertEquals(0,w.time.load().payrollTick());assertTrue(w.payroll.liabilities(p).isEmpty());
            w.sql("DROP TRIGGER failure");w.accrual.recoverAcceptedTime();assertEquals(14,w.payroll.load(p).unpaid());assertEquals(1200,w.time.load().payrollTick());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownAccrualCommitRecoversExactlyFromDurableCursor(boolean committed) throws Exception {
        var ref=new AtomicReference<JdbcFaults>();UUID p,b;
        try(var w=new FinancialTestWorld(file(),base->{var f=new JdbcFaults(base);ref.set(f);return f.connection;})) {
            b=w.staffingFixture(2,7);p=w.enroll(b);w.acceptedTimeFixture(1200);var f=ref.get();f.commits=0;f.beforeCommit=!committed;f.afterCommit=committed;
            assertThrows(CommitOutcomeUnknownException.class,w.accrual::recoverAcceptedTime);assertEquals(1,f.commits);
            try(var c=SqliteEconomyDatabase.open(file())) {
                var repo=new SqlitePayrollRepository(c,new Object());assertEquals(committed?1200:0,repo.load(p).lastAccruedTick());
                var service=new PayrollAccrualService(c,new Object());service.recoverAcceptedTime();service.recoverAcceptedTime();
                assertEquals(14,repo.load(p).unpaid());assertEquals(1,repo.liabilities(p).size());assertEquals(State.ARREARS,new SqliteBuildingPayrollQuery(c,new Object()).state(b));
            }
        }
    }
    @Test void twoConnectionsAccruingSamePeriodCannotDuplicateLiability() throws Exception {
        try(var a=new FinancialTestWorld(file());var b=new FinancialTestWorld(file())) {
            UUID building=a.staffingFixture(2,7),p=a.enroll(building);a.acceptedTimeFixture(2400);var start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var f1=pool.submit(()->{await(start);a.accrual.recoverAcceptedTime();});var f2=pool.submit(()->{await(start);b.accrual.recoverAcceptedTime();});
                start.countDown();f1.get(15,TimeUnit.SECONDS);f2.get(15,TimeUnit.SECONDS);
            }
            assertEquals(28,a.payroll.load(p).unpaid());assertEquals(2,b.payroll.liabilities(p).size());assertEquals(2400,a.time.load().payrollTick());
        }
    }
    private static void await(CountDownLatch latch) {try{latch.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
    @Test void changedStaffingWithoutCutoffIsRejectedInsteadOfRecomputedFromNewCount() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,7),p=w.enroll(b);w.acceptedTimeFixture(600);
            w.sql("UPDATE building_staffing_roles SET employed=2 WHERE building_id='"+b+"'");
            assertThrows(IllegalStateException.class,w.accrual::recoverAcceptedTime);assertEquals(State.RECOVERY,w.query.state(b));
            assertEquals(0,w.payroll.load(p).lastAccruedTick());assertEquals(0,w.time.load().payrollTick());
        }
    }
    @Test void rateInsertFailureCannotCommitOldAccrualOnItsOwn() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,7),p=w.enroll(b);w.acceptedTimeFixture(600);
            w.sql("CREATE TRIGGER fail_rate BEFORE INSERT ON regional_wage_rates BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(RuntimeException.class,()->w.accrual.configureRate(new RegionalWageRate(TestIdentities.REGION,CURRENCY,600,9,1)));
            assertEquals(0,w.payroll.load(p).lastAccruedTick());assertEquals(1,w.scalar("SELECT COUNT(*) FROM regional_wage_rates"));
        }
    }
    @Test void employmentHistoryFailureRollsBackAccrualAndStaffing() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,7),p=w.enroll(b);w.acceptedTimeFixture(600);
            w.sql("CREATE TRIGGER fail_history BEFORE INSERT ON payroll_employment_history BEGIN SELECT RAISE(ABORT,'failed'); END");
            assertThrows(RuntimeException.class,()->w.changeEmployeesFixture(p,3));
            assertEquals(0,w.payroll.load(p).lastAccruedTick());assertEquals(2,w.world.staffing.load(b).employed().get("laborer"));
            assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_employment_history"));
        }
    }
    @Test void unaccruedOrMismatchedStaffingCannotBeAcknowledgedAsNewPayrollBase() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,7),p=w.enroll(b);w.acceptedTimeFixture(600);
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->w.payroll.inTransaction(tx).changeEmployees(p,3,600)));
            SqliteTransactions.write(w.c,tx->{var repo=w.payroll.inTransaction(tx);repo.accrue(repo.load(p),600);return null;});
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(w.c,tx->w.payroll.inTransaction(tx).changeEmployees(p,3,600)));
            assertEquals(1,w.payroll.load(p).employees());
        }
    }
    @Test void overflowRollsBackAmountsRemainderLiabilityAndGlobalCursor() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(2,Long.MAX_VALUE),p=w.enroll(b);w.acceptedTimeFixture(1200);
            assertThrows(ArithmeticException.class,w.accrual::recoverAcceptedTime);
            assertEquals(0,w.payroll.load(p).lastAccruedTick());assertEquals(0,w.payroll.load(p).remainder());assertEquals(0,w.time.load().payrollTick());assertTrue(w.payroll.liabilities(p).isEmpty());
        }
    }
    @Test void missingEnrollmentCannotAdvanceCursorPastEmployedWages() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            w.staffingFixture(1,7);w.acceptedTimeFixture(1200);
            assertThrows(IllegalStateException.class,w.accrual::recoverAcceptedTime);assertEquals(0,w.time.load().payrollTick());
        }
    }
    @Test void staffingHistoryRemainsVerifiableAcrossRestartAfterIncreaseAndDecrease() throws Exception {
        try(var w=new FinancialTestWorld(file())) {
            UUID b=w.staffingFixture(1,7),p=w.enroll(b);w.acceptedTimeFixture(100);w.changeEmployeesFixture(p,3);
            w.acceptedTimeFixture(399);w.changeEmployeesFixture(p,2);w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();
            assertEquals(3,w.scalar("SELECT COUNT(*) FROM payroll_employment_history"));
        }
        try(var ignored=SqliteEconomyDatabase.open(file())) {}
    }
}
